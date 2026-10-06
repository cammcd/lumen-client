package dev.lumen.client.modules;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import dev.lumen.client.Lumen;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;

/** Shift-clicks everything out of chests, barrels and shulker boxes as you open them. */
public final class ChestStealer extends Module {
	private final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between items. 20 ticks is one second.", 2, 0, 20, 1, "t"));
	private final NumberSetting openDelay = add(new NumberSetting("Open delay", "Ticks to wait after the menu opens.", 4, 0, 40, 1, "t"));
	private final BoolSetting shulkers = add(new BoolSetting("Shulker boxes", "Also empty shulker boxes.", true));
	private final BoolSetting autoClose = add(new BoolSetting("Close when done", "Close the menu once it is empty or your inventory is full.", true));

	private int menuId = -1;
	private boolean fromContainer;
	private int wait;
	private int taken;
	private final Set<Integer> tried = new HashSet<>();

	public ChestStealer() {
		super("Chest Stealer", "Takes everything from chests, barrels and shulker boxes when you open them.", Category.PLAYER);
	}

	/** Stacks moved since the module was loaded; used by the game test. */
	public int taken() {
		return taken;
	}

	@Override
	protected void onDisable() {
		menuId = -1;
		tried.clear();
	}

	@Override
	public void onTick() {
		if (MC.player == null || MC.gameMode == null) return;
		if (!(MC.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
			menuId = -1;
			return;
		}

		AbstractContainerMenu menu = screen.getMenu();
		int size;
		if (menu instanceof ChestMenu chest) size = chest.getRowCount() * 9;
		else if (menu instanceof ShulkerBoxMenu && shulkers.isOn()) size = 27;
		else return;

		if (menu.containerId != menuId) {
			menuId = menu.containerId;
			wait = openDelay.getInt();
			tried.clear();
			// Server menus like /shop and /ah are chests too, and clicking in them can buy
			// things, so only menus opened from a container you are looking at are emptied.
			fromContainer = lookingAtContainer();
		}
		if (!fromContainer || Lumen.modules().printer.buying()) return;
		if (wait > 0) {
			wait--;
			return;
		}

		// The container's own slots come first in the menu, before the player's inventory.
		for (int i = 0; i < size && i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			if (!slot.hasItem() || tried.contains(i)) continue;
			// A slot still full after one try means your inventory has no room for it.
			tried.add(i);
			MC.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, MC.player);
			taken++;
			wait = delay.getInt();
			if (wait > 0) return;
		}

		if (autoClose.isOn()) MC.player.closeContainer();
		else wait = 10;
	}

	private static boolean lookingAtContainer() {
		HitResult hit = MC.hitResult;
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && MC.level != null) {
			BlockEntity be = MC.level.getBlockEntity(block.getBlockPos());
			return be instanceof Container || be instanceof EnderChestBlockEntity;
		}
		return hit instanceof EntityHitResult entity && entity.getEntity() instanceof Container;
	}
}
