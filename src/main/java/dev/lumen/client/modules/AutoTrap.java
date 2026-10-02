package dev.lumen.client.modules;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.Placement;

/** Boxes the target in with obsidian: around the feet, around the head, and a roof. */
public final class AutoTrap extends CombatModule {
	private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

	private final NumberSetting perTick = add(new NumberSetting("Blocks per tick", "Most blocks placed in one tick.", 2, 1, 8, 1));
	private final BoolSetting head = add(new BoolSetting("Head", "Also wall in the head, not just the feet.", true));
	private final BoolSetting roof = add(new BoolSetting("Roof", "Cover the top so the target cannot jump out.", true));

	private LivingEntity target;

	public AutoTrap() {
		super("Auto Trap", "Traps the nearest target in obsidian.", 4.5, 6);
	}

	@Override
	public String hudInfo() {
		return target != null ? target.getName().getString() : "";
	}

	@Override
	public void onTick() {
		target = null;
		LocalPlayer p = MC.player;
		if (p == null || MC.level == null || MC.gui.screen() != null) return;
		List<LivingEntity> found = targets();
		if (found.isEmpty()) return;
		target = found.get(0);

		int slot = Placement.hotbarSlot(stack -> stack.is(Items.OBSIDIAN));
		if (slot < 0) return;

		int done = 0;
		for (BlockPos pos : plan(target.blockPosition())) {
			if (!MC.level.getBlockState(pos).canBeReplaced()) continue;
			if (Placement.place(pos, slot, 5)) done++;
			if (done >= perTick.getInt()) return;
		}
	}

	/** The trap's blocks in an order where each one has something to be placed against. */
	public List<BlockPos> plan(BlockPos feet) {
		List<BlockPos> blocks = new ArrayList<>();
		for (Direction dir : SIDES) blocks.add(feet.relative(dir));
		if (head.isOn()) {
			for (Direction dir : SIDES) blocks.add(feet.above().relative(dir));
		}
		if (roof.isOn()) {
			// The roof block touches nothing solid until one block beside it is in place.
			blocks.add(feet.above(2).relative(Direction.NORTH));
			blocks.add(feet.above(2));
		}
		return blocks;
	}
}
