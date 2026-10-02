package dev.lumen.client.modules;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;

/** Eats a golden apple from your hotbar when your health drops, then switches back. */
public final class AutoGapple extends Module {
	private final NumberSetting health = add(new NumberSetting("Health", "Health plus absorption at or below which you eat.", 10, 1, 36, 1));
	private final BoolSetting preferEnchanted = add(new BoolSetting("Prefer enchanted", "Eat an enchanted golden apple first when you have one.", true));

	private boolean eating;
	private int eatTicks;
	private int previousSlot = -1;
	private int eaten;

	public AutoGapple() {
		super("Auto Gapple", "Eats a golden apple from your hotbar when your health is low.", Category.COMBAT);
	}

	/** Apples started since the module was loaded; used by the game test. */
	public int eaten() {
		return eaten;
	}

	@Override
	protected void onDisable() {
		if (eating) stop();
	}

	@Override
	public void onTick() {
		LocalPlayer p = MC.player;
		if (p == null) return;

		if (eating) {
			eatTicks++;
			// Eating starts a tick after the key goes down, so give it a moment before checking.
			boolean finished = eatTicks > 5 && !p.isUsingItem();
			if (finished || eatTicks > 80 || MC.gui.screen() != null) stop();
			return;
		}

		if (MC.gui.screen() != null || p.isUsingItem()) return;
		if (p.getHealth() + p.getAbsorptionAmount() > health.get()) return;

		int slot = findApple(p.getInventory());
		if (slot < 0) return;
		previousSlot = p.getInventory().getSelectedSlot();
		p.getInventory().setSelectedSlot(slot);
		MC.options.keyUse.setDown(true);
		eating = true;
		eatTicks = 0;
		eaten++;
	}

	private void stop() {
		MC.options.keyUse.setDown(false);
		if (MC.player != null && previousSlot >= 0) MC.player.getInventory().setSelectedSlot(previousSlot);
		previousSlot = -1;
		eating = false;
	}

	private int findApple(Inventory inv) {
		int plain = -1;
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).is(Items.ENCHANTED_GOLDEN_APPLE) && preferEnchanted.isOn()) return i;
			if (inv.getItem(i).is(Items.GOLDEN_APPLE) && plain < 0) plain = i;
		}
		if (plain >= 0) return plain;
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).is(Items.ENCHANTED_GOLDEN_APPLE)) return i;
		}
		return -1;
	}
}
