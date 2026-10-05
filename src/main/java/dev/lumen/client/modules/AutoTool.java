package dev.lumen.client.modules;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;

/** Picks the fastest hotbar tool for the block you are mining, then switches back. */
public final class AutoTool extends Module {
	private final BoolSetting switchBack = add(new BoolSetting("Switch back", "Return to your previous slot when you stop mining.", true));
	private final BoolSetting saveTools = add(new BoolSetting("Save tools", "Skip tools that are about to break.", true));
	private final NumberSetting minDurability = add(new NumberSetting("Min durability", "Uses left below which a tool is skipped.", 10, 1, 100, 1))
			.visibleWhen(saveTools::isOn);

	private int previousSlot = -1;

	public AutoTool() {
		super("Auto Tool", "Switches to the best tool in your hotbar while mining.", Category.PLAYER);
	}

	@Override
	protected void onDisable() {
		previousSlot = -1;
	}

	@Override
	public void onTick() {
		if (MC.player == null || MC.level == null) return;
		Inventory inventory = MC.player.getInventory();

		boolean mining = MC.gui.screen() == null && MC.options.keyAttack.isDown()
				&& MC.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK;
		if (!mining) {
			if (previousSlot != -1 && switchBack.isOn()) inventory.setSelectedSlot(previousSlot);
			previousSlot = -1;
			return;
		}

		BlockState state = MC.level.getBlockState(((BlockHitResult) MC.hitResult).getBlockPos());
		int current = inventory.getSelectedSlot();
		int best = bestSlot(inventory, state, current);
		if (best != current) {
			if (previousSlot == -1) previousSlot = current;
			inventory.setSelectedSlot(best);
		}
	}

	/** The hotbar slot that mines this block fastest, preferring the current one on a tie. */
	private int bestSlot(Inventory inventory, BlockState state, int current) {
		int best = current;
		float bestSpeed = usable(inventory.getItem(current)) ? inventory.getItem(current).getDestroySpeed(state) : 0f;
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!usable(stack)) continue;
			float speed = stack.getDestroySpeed(state);
			if (speed > bestSpeed + 0.01f) {
				best = slot;
				bestSpeed = speed;
			}
		}
		return best;
	}

	private boolean usable(ItemStack stack) {
		if (!saveTools.isOn() || stack.isEmpty() || !stack.isDamageableItem()) return true;
		return stack.getMaxDamage() - stack.getDamageValue() > minDurability.getInt();
	}
}
