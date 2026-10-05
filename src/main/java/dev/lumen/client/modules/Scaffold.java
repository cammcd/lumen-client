package dev.lumen.client.modules;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.Placement;

/** Places blocks from your hotbar under your feet as you walk, so you can bridge over gaps. */
public final class Scaffold extends Module {
	private final NumberSetting perTick = add(new NumberSetting("Blocks per tick", "Most blocks placed in one tick.", 2, 1, 4, 1));
	private final BoolSetting predict = add(new BoolSetting("Predict", "Also place where you will be next tick, so sprinting does not outrun it.", true));
	private final BoolSetting keepY = add(new BoolSetting("Keep height", "Always bridge at the height you turned it on, even after falling.", false));

	private int bridgeY = Integer.MIN_VALUE;
	private int startY;
	private int placed;

	public Scaffold() {
		super("Scaffold", "Places blocks under your feet as you walk, to bridge across gaps.", Category.PLAYER);
	}

	/** Blocks placed since the module was loaded; used by the game test. */
	public int placed() {
		return placed;
	}

	@Override
	protected void onEnable() {
		if (MC.player != null) {
			startY = Mth.floor(MC.player.getY()) - 1;
			bridgeY = startY;
		}
	}

	@Override
	public void onTick() {
		LocalPlayer p = MC.player;
		if (p == null || MC.level == null || MC.gui.screen() != null) return;
		// Bridge at the height you last stood at, so a jump does not build a tower under you.
		if (p.onGround()) bridgeY = Mth.floor(p.getY()) - 1;
		int y = keepY.isOn() ? startY : bridgeY;
		if (y == Integer.MIN_VALUE) return;

		int slot = Placement.hotbarSlot(Scaffold::isScaffoldBlock);
		if (slot < 0) return;

		List<BlockPos> spots = new ArrayList<>();
		spots.add(new BlockPos(Mth.floor(p.getX()), y, Mth.floor(p.getZ())));
		if (predict.isOn()) {
			Vec3 next = p.position().add(p.getDeltaMovement().scale(2));
			BlockPos ahead = new BlockPos(Mth.floor(next.x), y, Mth.floor(next.z));
			if (!ahead.equals(spots.get(0))) spots.add(ahead);
		}

		int done = 0;
		for (BlockPos spot : spots) {
			if (!MC.level.getBlockState(spot).canBeReplaced()) continue;
			if (Placement.place(spot, slot, 4.5)) {
				done++;
				placed++;
			} else {
				// Nothing to place against, as when walking off a corner: lay a block beside it first.
				for (Direction dir : Direction.Plane.HORIZONTAL) {
					BlockPos side = spot.relative(dir);
					if (MC.level.getBlockState(side).canBeReplaced() && Placement.place(side, slot, 4.5)) {
						done++;
						placed++;
						break;
					}
				}
			}
			if (done >= perTick.getInt()) return;
		}
	}

	/** Full, solid blocks that stay put and do nothing when clicked. */
	static boolean isScaffoldBlock(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem item)) return false;
		Block block = item.getBlock();
		if (block instanceof FallingBlock) return false;
		BlockState state = block.defaultBlockState();
		return !state.hasBlockEntity() && state.isCollisionShapeFullBlock(MC.level, BlockPos.ZERO)
				&& Placement.isSupportBlock(state);
	}
}
