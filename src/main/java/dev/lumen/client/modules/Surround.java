package dev.lumen.client.modules;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.Placement;

/** Rings your feet with obsidian so crystals cannot be placed beside you. */
public final class Surround extends Module {
	private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

	private final NumberSetting perTick = add(new NumberSetting("Blocks per tick", "Most blocks placed in one tick.", 4, 1, 8, 1));
	private final BoolSetting onlyOnGround = add(new BoolSetting("Only on ground", "Wait until you are standing on something.", true));
	private final BoolSetting disableOnMove = add(new BoolSetting("Disable when you move", "Turn off once you step out of the block you started in.", true));
	private final BoolSetting enderChests = add(new BoolSetting("Use ender chests", "Fall back to ender chests when out of obsidian.", true));

	private BlockPos origin;

	public Surround() {
		super("Surround", "Places obsidian around your feet to block crystals beside you.", Category.COMBAT);
	}

	@Override
	protected void onEnable() {
		origin = MC.player != null ? MC.player.blockPosition() : null;
	}

	@Override
	public String hudInfo() {
		return MC.player == null ? "" : (4 - missing(MC.player.blockPosition()).size()) + "/4";
	}

	@Override
	public void onTick() {
		LocalPlayer p = MC.player;
		if (p == null || MC.level == null || MC.gui.screen() != null) return;
		BlockPos feet = p.blockPosition();
		if (origin == null) origin = feet;
		// Jumping changes your height, not your column, so only the column counts as moving.
		if (disableOnMove.isOn() && (feet.getX() != origin.getX() || feet.getZ() != origin.getZ())) {
			setEnabled(false);
			return;
		}
		if (onlyOnGround.isOn() && !p.onGround()) return;

		int slot = Placement.hotbarSlot(this::isBlock);
		if (slot < 0) return;

		int done = 0;
		for (BlockPos side : missing(feet)) {
			// A side over a drop needs a block under it first.
			if (MC.level.getBlockState(side.below()).canBeReplaced() && Placement.place(side.below(), slot, 5)) done++;
			if (done >= perTick.getInt()) return;
			if (Placement.place(side, slot, 5)) done++;
			if (done >= perTick.getInt()) return;
		}
	}

	/** Sides of the feet block that are still open. */
	public List<BlockPos> missing(BlockPos feet) {
		List<BlockPos> open = new ArrayList<>();
		for (Direction dir : SIDES) {
			BlockPos side = feet.relative(dir);
			if (MC.level.getBlockState(side).canBeReplaced()) open.add(side);
		}
		return open;
	}

	private boolean isBlock(ItemStack stack) {
		return stack.is(Items.OBSIDIAN) || stack.is(Items.CRYING_OBSIDIAN) || (enderChests.isOn() && stack.is(Items.ENDER_CHEST));
	}
}
