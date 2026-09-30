package dev.lumen.client.modules;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import dev.lumen.client.render.EspBatch;
import dev.lumen.client.setting.ColorSetting;

/** Highlights block entities found by scanning loaded chunks on a short timer. */
public abstract class EspModule extends HighlightModule {
	/** A highlighted block. If fixedColor is non-zero it overrides the setting colour. */
	protected record Target(AABB box, ColorSetting color, int fixedColor) {
	}

	private volatile List<Target> targets = List.of();
	private int scanTimer;

	protected EspModule(String name, String description, double defaultRange, boolean defaultTracers) {
		super(name, description, defaultRange, defaultTracers, "Blocks");
	}

	/** Returns the target for a block entity, or null if it should not be highlighted. */
	protected abstract Target match(BlockEntity blockEntity, ClientLevel level);

	@Override
	protected void onEnable() {
		scanTimer = 0;
		rescan();
	}

	@Override
	protected void onDisable() {
		targets = List.of();
	}

	@Override
	public void onTick() {
		if (++scanTimer >= 5) {
			scanTimer = 0;
			rescan();
		}
	}

	public int count() {
		return targets.size();
	}

	@Override
	public String hudInfo() {
		return Integer.toString(targets.size());
	}

	private void rescan() {
		ClientLevel level = MC.level;
		LocalPlayer player = MC.player;
		if (level == null || player == null) {
			targets = List.of();
			return;
		}

		double maxRange = range.get();
		double maxRangeSq = maxRange * maxRange;
		Vec3 eye = player.position();
		int chunkRadius = Math.min(MC.options.getEffectiveRenderDistance() + 1, (int) Math.ceil(maxRange / 16.0) + 1);
		ChunkPos center = player.chunkPosition();

		List<Target> found = new ArrayList<>();
		for (int cx = center.x() - chunkRadius; cx <= center.x() + chunkRadius; cx++) {
			for (int cz = center.z() - chunkRadius; cz <= center.z() + chunkRadius; cz++) {
				if (!level.hasChunk(cx, cz)) continue;
				LevelChunk chunk = level.getChunk(cx, cz);
				if (chunk == null) continue;

				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					BlockPos pos = blockEntity.getBlockPos();
					double dx = pos.getX() + 0.5 - eye.x;
					double dy = pos.getY() + 0.5 - eye.y;
					double dz = pos.getZ() + 0.5 - eye.z;
					if (dx * dx + dy * dy + dz * dz > maxRangeSq) continue;

					Target target = match(blockEntity, level);
					if (target != null) found.add(target);
				}
			}
		}

		targets = found;
	}

	/** The outline shape of a block as a world-space box, or a full cube for shapeless blocks. */
	protected static AABB blockBox(ClientLevel level, BlockPos pos, BlockState state) {
		VoxelShape shape = state.getShape(level, pos);
		if (shape.isEmpty()) return new AABB(pos);
		return shape.bounds().move(pos);
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
		List<Target> current = targets;
		if (current.isEmpty()) return;

		Style style = frameStyle();
		for (Target target : current) {
			int base = target.fixedColor() != 0
					? target.fixedColor()
					: target.color().color(rainbowOffset(target.box().getCenter()));
			draw(batch, cam, style, target.box(), base);
		}
	}
}
