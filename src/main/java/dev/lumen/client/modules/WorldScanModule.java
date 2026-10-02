package dev.lumen.client.modules;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.render.WorldRenderable;

/**
 * Base for modules that analyse whole chunks. Chunks are queued when they load or
 * change, then scanned a few per tick so exploring never stalls a frame. Results are
 * cleared when the world or dimension changes.
 */
public abstract class WorldScanModule extends Module implements WorldRenderable {
	private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
	private final Set<ChunkPos> queued = new HashSet<>();
	private ClientLevel lastLevel;

	protected WorldScanModule(String name, String description) {
		super(name, description, Category.WORLD);
	}

	/** Clears all results. Called on enable and when the world changes. */
	protected abstract void reset();

	/** Analyses one loaded chunk. */
	protected abstract void scan(ClientLevel level, LevelChunk chunk);

	protected int chunksPerTick() {
		return 4;
	}

	@Override
	protected void onEnable() {
		lastLevel = MC.level;
		queue.clear();
		queued.clear();
		reset();
		enqueueLoaded();
	}

	@Override
	protected void onDisable() {
		queue.clear();
		queued.clear();
	}

	public void onChunkLoad(LevelChunk chunk) {
		enqueue(chunk.getPos());
	}

	/** A block changed; rescan its chunk soon. */
	public void onBlockUpdate(BlockPos pos) {
		enqueue(ChunkPos.containing(pos));
	}

	protected void enqueue(ChunkPos pos) {
		if (queued.add(pos)) queue.add(pos);
	}

	@Override
	public void onTick() {
		if (MC.level != lastLevel) {
			lastLevel = MC.level;
			queue.clear();
			queued.clear();
			reset();
			enqueueLoaded();
		}
		ClientLevel level = MC.level;
		if (level == null) return;

		int budget = chunksPerTick();
		while (budget > 0 && !queue.isEmpty()) {
			ChunkPos pos = queue.poll();
			queued.remove(pos);
			if (!level.hasChunk(pos.x(), pos.z())) continue;
			LevelChunk chunk = level.getChunk(pos.x(), pos.z());
			if (chunk == null) continue;
			scan(level, chunk);
			budget--;
		}
	}

	private void enqueueLoaded() {
		ClientLevel level = MC.level;
		LocalPlayer player = MC.player;
		if (level == null || player == null) return;
		int radius = MC.options.getEffectiveRenderDistance() + 1;
		ChunkPos center = player.chunkPosition();
		for (int cx = center.x() - radius; cx <= center.x() + radius; cx++) {
			for (int cz = center.z() - radius; cz <= center.z() + radius; cz++) {
				if (level.hasChunk(cx, cz)) enqueue(new ChunkPos(cx, cz));
			}
		}
	}

	/** A box covering a whole chunk between two heights. */
	protected static AABB chunkBox(ChunkPos pos, double minY, double maxY) {
		return new AABB(pos.getMinBlockX(), minY, pos.getMinBlockZ(), pos.getMinBlockX() + 16, maxY, pos.getMinBlockZ() + 16);
	}

	/** Horizontal distance from the player to a chunk's centre, in chunks. */
	protected static double chunkDistance(ChunkPos pos) {
		if (MC.player == null) return Double.MAX_VALUE;
		ChunkPos here = MC.player.chunkPosition();
		double dx = pos.x() - here.x();
		double dz = pos.z() - here.z();
		return Math.sqrt(dx * dx + dz * dz);
	}
}
