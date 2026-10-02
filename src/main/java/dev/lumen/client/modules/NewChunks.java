package dev.lumen.client.modules;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.render.EspBatch;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.setting.EnumSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ColorUtil;

/**
 * Tells freshly generated chunks apart from ones that existed before, using how
 * liquids behave. Water and lava always generate still and only start flowing once
 * the chunk is ticked. So a chunk that already contains flowing liquid when it loads
 * has been loaded before (old), and a chunk whose liquid starts flowing right after
 * it arrives was just generated (new). Old chunks in fresh land are player trails.
 */
public final class NewChunks extends WorldScanModule {
	public enum Show {
		BOTH("New and old"), NEW("New only"), OLD("Old only");

		private final String label;

		Show(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private final EnumSetting<Show> show = add(new EnumSetting<>("Show", "Which chunks to draw.", Show.BOTH));
	private final ColorSetting newColor = add(new ColorSetting("New chunks", "Colour of freshly generated chunks.", 0xFFFF4D5E));
	private final ColorSetting oldColor = add(new ColorSetting("Old chunks", "Colour of chunks that existed before: likely player trails.", 0xFF4D8BFF));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "Opacity of the chunk squares.", 30, 5, 100, 1, "%"));
	private final BoolSetting outline = add(new BoolSetting("Outline", "Draw an outline around each square.", true));
	private final BoolSetting followPlayer = add(new BoolSetting("Follow player", "Draw the squares just below your feet. Turn off to use a fixed height.", true));
	private final NumberSetting height = add(new NumberSetting("Height", "Fixed height of the squares.", 62, -64, 320, 1))
			.visibleWhen(() -> !followPlayer.isOn());
	private final NumberSetting drawDistance = add(new NumberSetting("Draw distance", "How far to draw squares, in chunks.", 24, 4, 64, 1, " ch"));
	private final BoolSetting throughWalls = add(new BoolSetting("Through walls", "Draw squares on top of terrain.", true));

	private final Set<ChunkPos> newChunks = new HashSet<>();
	private final Set<ChunkPos> oldChunks = new HashSet<>();

	public NewChunks() {
		super("New Chunks", "Colours new and old chunks so player trails through fresh land stand out.");
	}

	@Override
	protected int chunksPerTick() {
		return 8;
	}

	@Override
	protected void reset() {
		newChunks.clear();
		oldChunks.clear();
	}

	public boolean isNew(ChunkPos pos) {
		return newChunks.contains(pos);
	}

	public boolean isOld(ChunkPos pos) {
		return oldChunks.contains(pos);
	}

	@Override
	public String hudInfo() {
		return newChunks.size() + "/" + oldChunks.size();
	}

	private static boolean isFlowing(BlockState state) {
		FluidState fluid = state.getFluidState();
		return !fluid.isEmpty() && !fluid.isSource();
	}

	/** On load: any flowing liquid already present means the chunk is old. */
	@Override
	protected void scan(ClientLevel level, LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		if (newChunks.contains(pos) || oldChunks.contains(pos)) return;

		for (LevelChunkSection section : chunk.getSections()) {
			if (section == null || section.hasOnlyAir()) continue;
			// The palette check skips the block-by-block loop for most sections.
			if (!section.maybeHas(NewChunks::isFlowing)) continue;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (isFlowing(section.getBlockState(x, y, z))) {
							oldChunks.add(pos);
							return;
						}
					}
				}
			}
		}
	}

	/** Only new detection uses block updates; rescans would misread liquids we saw start flowing. */
	@Override
	public void onBlockUpdate(BlockPos pos) {
		if (MC.level == null) return;
		ChunkPos chunk = ChunkPos.containing(pos);
		if (newChunks.contains(chunk) || oldChunks.contains(chunk)) return;
		if (isFlowing(MC.level.getBlockState(pos))) newChunks.add(chunk);
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
		if (MC.player == null) return;
		double y = followPlayer.isOn() ? Math.floor(MC.player.getY()) - 0.98 : height.get();
		double maxDistance = drawDistance.get();
		float alpha = opacity.getFloat() / 100f;
		boolean walls = throughWalls.isOn();

		if (show.get() != Show.OLD) draw(batch, newChunks, newColor.color(), y, maxDistance, alpha, walls);
		if (show.get() != Show.NEW) draw(batch, oldChunks, oldColor.color(), y, maxDistance, alpha, walls);
	}

	private void draw(EspBatch batch, Set<ChunkPos> chunks, int color, double y, double maxDistance, float alpha, boolean walls) {
		int fill = ColorUtil.fade(color, alpha);
		int line = ColorUtil.fade(color, Math.min(1f, alpha * 2.2f));
		for (ChunkPos pos : chunks) {
			if (chunkDistance(pos) > maxDistance) continue;
			// Inset slightly so neighbouring squares read as a grid.
			AABB square = chunkBox(pos, y, y + 0.02).deflate(0.35, 0, 0.35);
			batch.fill(square, fill, walls);
			if (outline.isOn()) batch.outline(square, line, 1.5f, walls);
		}
	}
}
