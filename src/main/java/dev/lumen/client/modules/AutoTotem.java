package dev.lumen.client.modules;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.EnumSetting;
import dev.lumen.client.setting.NumberSetting;

/** Keeps a totem of undying in your offhand, swapping in a fresh one after each pop. */
public final class AutoTotem extends Module {
	public enum Mode {
		ALWAYS("Always"),
		LOW_HEALTH("Low health");

		private final String label;

		Mode(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/** Button value that swaps a slot with the offhand. */
	static final int OFFHAND_BUTTON = 40;

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Always keep a totem, or only when health is low.", Mode.ALWAYS));
	private final NumberSetting health = add(new NumberSetting("Health", "Health plus absorption at or below which a totem goes in.", 10, 1, 36, 1))
			.visibleWhen(() -> mode.is(Mode.LOW_HEALTH));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Ticks to wait between swaps.", 0, 0, 20, 1, "t"));

	private int wait;

	public AutoTotem() {
		super("Auto Totem", "Keeps a totem of undying in your offhand.", Category.COMBAT);
	}

	@Override
	public String hudInfo() {
		if (MC.player == null) return "";
		int count = MC.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) ? MC.player.getOffhandItem().getCount() : 0;
		Inventory inv = MC.player.getInventory();
		for (int i = 0; i < 36; i++) {
			if (inv.getItem(i).is(Items.TOTEM_OF_UNDYING)) count += inv.getItem(i).getCount();
		}
		return Integer.toString(count);
	}

	@Override
	public void onTick() {
		LocalPlayer p = MC.player;
		if (p == null || MC.gameMode == null) return;
		// Inventory clicks only line up with the player's own inventory, not an open chest.
		if (p.containerMenu != p.inventoryMenu) return;
		if (p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
		if (mode.is(Mode.LOW_HEALTH) && p.getHealth() + p.getAbsorptionAmount() > health.get()) return;
		if (wait > 0) {
			wait--;
			return;
		}

		int slot = find(p.getInventory());
		if (slot < 0) return;
		MC.gameMode.handleContainerInput(p.inventoryMenu.containerId, menuSlot(slot), OFFHAND_BUTTON, ContainerInput.SWAP, p);
		wait = delay.getInt();
	}

	private static int find(Inventory inv) {
		for (int i = 0; i < 36; i++) {
			if (inv.getItem(i).is(Items.TOTEM_OF_UNDYING)) return i;
		}
		return -1;
	}

	/** The inventory menu slot for an inventory index: the hotbar sits at 36 to 44. */
	static int menuSlot(int inventoryIndex) {
		return inventoryIndex < 9 ? inventoryIndex + 36 : inventoryIndex;
	}
}
