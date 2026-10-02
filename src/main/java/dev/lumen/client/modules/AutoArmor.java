package dev.lumen.client.modules;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;

/** Puts on the best armor in your inventory, one piece at a time. */
public final class AutoArmor extends Module {
	private record Piece(EquipmentSlot slot, int menuSlot, String suffix) {
	}

	private static final Piece[] PIECES = {
			new Piece(EquipmentSlot.HEAD, 5, "_helmet"),
			new Piece(EquipmentSlot.CHEST, 6, "_chestplate"),
			new Piece(EquipmentSlot.LEGS, 7, "_leggings"),
			new Piece(EquipmentSlot.FEET, 8, "_boots"),
	};

	private final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between pieces.", 2, 0, 20, 1, "t"));
	private final BoolSetting keepElytra = add(new BoolSetting("Keep elytra", "Never swap a worn elytra for a chestplate.", true));

	private int wait;

	public AutoArmor() {
		super("Auto Armor", "Equips the best armor in your inventory.", Category.COMBAT);
	}

	@Override
	public void onTick() {
		LocalPlayer p = MC.player;
		if (p == null || MC.gameMode == null) return;
		if (p.containerMenu != p.inventoryMenu) return;
		if (MC.gui.screen() != null && !(MC.gui.screen() instanceof InventoryScreen)) return;
		if (wait > 0) {
			wait--;
			return;
		}

		Inventory inv = p.getInventory();
		for (Piece piece : PIECES) {
			ItemStack worn = p.getItemBySlot(piece.slot());
			if (piece.slot() == EquipmentSlot.CHEST && keepElytra.isOn() && worn.is(Items.ELYTRA)) continue;

			int wornScore = score(worn, piece.suffix());
			int best = -1;
			int bestScore = wornScore;
			for (int i = 0; i < 36; i++) {
				int s = score(inv.getItem(i), piece.suffix());
				if (s > bestScore) {
					best = i;
					bestScore = s;
				}
			}
			if (best < 0) continue;

			int from = AutoTotem.menuSlot(best);
			int id = p.inventoryMenu.containerId;
			if (worn.isEmpty()) {
				MC.gameMode.handleContainerInput(id, from, 0, ContainerInput.QUICK_MOVE, p);
			} else {
				// Pick up the new piece, swap it with the worn one, and put the old one where the new one was.
				MC.gameMode.handleContainerInput(id, from, 0, ContainerInput.PICKUP, p);
				MC.gameMode.handleContainerInput(id, piece.menuSlot(), 0, ContainerInput.PICKUP, p);
				MC.gameMode.handleContainerInput(id, from, 0, ContainerInput.PICKUP, p);
			}
			wait = delay.getInt();
			return;
		}
	}

	/** Higher is better: material first, then remaining durability. Zero means not armor for this slot. */
	static int score(ItemStack stack, String suffix) {
		if (stack.isEmpty()) return 0;
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (!path.endsWith(suffix)) return 0;
		boolean turtle = path.equals("turtle_helmet");

		int rank;
		if (path.startsWith("netherite_")) rank = 7;
		else if (path.startsWith("diamond_")) rank = 6;
		else if (path.startsWith("iron_") || turtle) rank = 5;
		else if (path.startsWith("chainmail_")) rank = 4;
		else if (path.startsWith("copper_")) rank = 3;
		else if (path.startsWith("golden_")) rank = 2;
		else if (path.startsWith("leather_")) rank = 1;
		else return 0;

		int durability = stack.isDamageableItem() && stack.getMaxDamage() > 0
				? 100 * (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage()
				: 100;
		return rank * 1000 + durability;
	}
}
