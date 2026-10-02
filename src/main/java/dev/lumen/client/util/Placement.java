package dev.lumen.client.util;

import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Places blocks and clicks block faces the way a right click does: the same
 * gameMode.useItemOn call, and the same swing vanilla makes when the result says so.
 */
public final class Placement {
	private static final Minecraft MC = Minecraft.getInstance();

	/** Blocks that open or toggle when clicked, so they are never used as a support. */
	private static final String[] INTERACTIVE = {
			"crafting_table", "anvil", "door", "trapdoor", "button", "lever", "fence_gate", "bed",
			"note_block", "repeater", "comparator", "respawn_anchor", "smithing_table", "loom",
			"grindstone", "stonecutter", "cartography_table", "enchanting_table", "lectern", "bell",
			"cake", "crafter", "chiseled_bookshelf", "daylight_detector", "beacon",
	};

	private Placement() {
	}

	/** A hotbar slot whose item matches, preferring the selected slot, or -1. */
	public static int hotbarSlot(Predicate<ItemStack> match) {
		LocalPlayer p = MC.player;
		if (p == null) return -1;
		Inventory inv = p.getInventory();
		if (match.test(inv.getItem(inv.getSelectedSlot()))) return inv.getSelectedSlot();
		for (int i = 0; i < 9; i++) {
			if (match.test(inv.getItem(i))) return i;
		}
		return -1;
	}

	/** True if this block can hold a block placed against it without opening or toggling. */
	public static boolean isSupport(BlockPos pos) {
		BlockState state = MC.level.getBlockState(pos);
		if (state.canBeReplaced() || state.hasBlockEntity()) return false;
		if (!state.isCollisionShapeFullBlock(MC.level, pos)) return false;
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		for (String word : INTERACTIVE) {
			if (path.contains(word)) return false;
		}
		return true;
	}

	/**
	 * Places the item from a hotbar slot into an empty block space by clicking a face of
	 * a solid neighbour within reach. Returns true if the click was sent.
	 */
	public static boolean place(BlockPos pos, int slot, double reach) {
		LocalPlayer p = MC.player;
		if (p == null || MC.level == null || MC.gameMode == null || slot < 0) return false;
		if (!MC.level.getBlockState(pos).canBeReplaced()) return false;

		Vec3 eye = p.getEyePosition();
		for (Direction dir : Direction.values()) {
			BlockPos neighbour = pos.relative(dir);
			if (!isSupport(neighbour)) continue;
			Direction face = dir.getOpposite();
			Vec3 hit = Vec3.atCenterOf(neighbour).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			if (eye.distanceTo(hit) > reach) continue;
			click(slot, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, neighbour, false));
			return true;
		}
		return false;
	}

	/** The middle of a block's top face, for clicking it from above. */
	public static BlockHitResult topFace(BlockPos pos) {
		return new BlockHitResult(new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5), Direction.UP, pos, false);
	}

	/**
	 * Right-clicks a block face with the item in a hotbar slot (or the offhand), switching
	 * to the slot for the click and straight back.
	 */
	public static InteractionResult click(int slot, InteractionHand hand, BlockHitResult hit) {
		LocalPlayer p = MC.player;
		Inventory inv = p.getInventory();
		int previous = inv.getSelectedSlot();
		boolean swap = hand == InteractionHand.MAIN_HAND && slot >= 0 && slot != previous;
		if (swap) inv.setSelectedSlot(slot);

		SwingAnimation animation = p.getItemInHand(hand).getInteractAnimation();
		InteractionResult result = MC.gameMode.useItemOn(p, hand, hit);
		if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
			p.swing(hand, animation, false);
		}

		if (swap) inv.setSelectedSlot(previous);
		return result;
	}
}
