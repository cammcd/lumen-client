package dev.lumen.client.modules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.hud.Notifications;
import dev.lumen.client.render.EspBatch;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ColorUtil;
import dev.lumen.client.util.Finds;

/**
 * Scores each chunk for blocks that do not generate naturally. Strong signals
 * (ender chests, shulker boxes, beacons, concrete, hoppers) count most. Blocks that
 * villages and other structures also contain count little, and their total is
 * capped, so a village alone cannot reach the threshold.
 */
public final class BaseFinder extends WorldScanModule {
	private static final int STRONG = 10;
	private static final int MEDIUM = 4;
	private static final int LIGHT = 2;
	private static final int WEAK = 1;

	/**
	 * A flagged chunk. Reasons map block names to how many were found. minY and maxY are
	 * the full height range of the blocks found; weightAtY holds their points per height,
	 * counted up from floorY, so the box can show where they are concentrated.
	 */
	public record Base(ChunkPos pos, int score, Map<String, Integer> reasons, List<BlockPos> hits, int minY, int maxY,
			int floorY, int[] weightAtY) {
		/**
		 * The lowest and highest flagged block inside the band of at most {@code maxHeight}
		 * blocks holding the most points, so a stray block far above or below the base
		 * does not stretch the box.
		 */
		public int[] core(int maxHeight) {
			int window = Math.max(1, maxHeight);
			long sum = 0;
			long best = -1;
			int bestStart = 0;
			for (int i = 0; i < weightAtY.length; i++) {
				sum += weightAtY[i];
				if (i >= window) sum -= weightAtY[i - window];
				if (sum > best) {
					best = sum;
					bestStart = Math.max(0, i - window + 1);
				}
			}
			int end = Math.min(weightAtY.length - 1, bestStart + window - 1);
			int lo = -1;
			int hi = -1;
			for (int i = bestStart; i <= end; i++) {
				if (weightAtY[i] == 0) continue;
				if (lo < 0) lo = i;
				hi = i;
			}
			if (lo < 0) return new int[] {minY, maxY};
			return new int[] {floorY + lo, floorY + hi};
		}
	}

	private record Weight(int points, int perTypeCap, boolean weak) {
	}

	private final NumberSetting threshold = add(new NumberSetting("Threshold", "Score needed to flag a chunk. Lower finds more, with more false alarms.", 20, 5, 100, 1));
	private final NumberSetting scanSpeed = add(new NumberSetting("Scan speed", "Chunks checked per tick.", 4, 1, 16, 1));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Pop-up when a likely base is found.", true));
	private final BoolSetting chat = add(new BoolSetting("Chat message", "Client-side chat line with the coordinates and what was found.", true));
	private final BoolSetting log = add(new BoolSetting("Log to file", "Append finds to config/lumen/bases.csv.", true));
	private final BoolSetting highlight = add(new BoolSetting("Highlight", "Outline flagged chunks in the world.", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Colour of flagged chunks.", 0xFFFF8A3D))
			.visibleWhen(highlight::isOn);
	private final NumberSetting boxHeight = add(new NumberSetting("Max box height",
			"The box covers the band of at most this many blocks where most of the base is, not every stray block.", 16, 4, 384, 4))
			.visibleWhen(highlight::isOn);
	private final BoolSetting markBlocks = add(new BoolSetting("Mark blocks", "Outline the blocks that raised the score.", true));
	private final ColorSetting markerColor = add(new ColorSetting("Marker color", "Colour of block markers.", 0xFFFFE066))
			.visibleWhen(markBlocks::isOn);

	private final Map<ChunkPos, Base> bases = new HashMap<>();
	private final Set<ChunkPos> reported = new HashSet<>();
	private Map<Block, Weight> overworldWeights;
	private Map<Block, Weight> otherDimensionWeights;

	public BaseFinder() {
		super("Base Finder", "Scores chunks for player-made blocks and flags the ones that look like bases.");
	}

	@Override
	protected int chunksPerTick() {
		return scanSpeed.getInt();
	}

	@Override
	protected void reset() {
		bases.clear();
		reported.clear();
	}

	public Map<ChunkPos, Base> bases() {
		return Map.copyOf(bases);
	}

	@Override
	public String hudInfo() {
		return Integer.toString(bases.size());
	}

	// ---- weights ----

	/** Points for a block, by registry name. Zero means it is ignored. */
	private static Weight rule(String id, boolean otherDimension) {
		if (id.equals("ender_chest") || id.endsWith("shulker_box") || id.equals("beacon") || id.equals("conduit")
				|| id.equals("respawn_anchor") || id.equals("nether_portal") || id.equals("netherite_block")
				|| id.equals("diamond_block") || id.equals("emerald_block") || id.endsWith("_concrete")
				|| id.endsWith("_concrete_powder") || id.equals("enchanting_table") || id.equals("daylight_detector")
				|| id.equals("target") || id.equals("jukebox") || id.equals("note_block") || id.equals("slime_block")
				|| id.equals("honey_block") || id.equals("redstone_block") || id.equals("observer")
				|| id.equals("comparator") || id.equals("hopper") || id.equals("beehive") || id.equals("powered_rail")
				|| id.equals("detector_rail") || id.equals("activator_rail") || id.equals("lodestone")) {
			return new Weight(STRONG, 5, false);
		}
		if (id.equals("anvil") || id.equals("chipped_anvil") || id.equals("damaged_anvil") || id.equals("piston")
				|| id.equals("crafter") || id.equals("scaffolding") || id.equals("iron_door") || id.endsWith("_sign")
				|| id.equals("iron_block") || id.equals("lapis_block") || id.equals("redstone_torch")
				|| id.equals("redstone_wall_torch") || id.equals("dropper")) {
			return new Weight(MEDIUM, 3, false);
		}
		if (id.equals("sticky_piston") || id.equals("repeater") || id.equals("dispenser") || id.equals("lever")
				|| id.endsWith("_glazed_terracotta")) {
			return new Weight(LIGHT, 3, false);
		}

		boolean weak = id.equals("crafting_table") || id.equals("furnace") || id.equals("blast_furnace")
				|| id.equals("smoker") || id.endsWith("_bed") || id.equals("glass") || id.endsWith("_glass")
				|| id.endsWith("glass_pane") || id.endsWith("_wool") || id.endsWith("_carpet") || id.equals("torch")
				|| id.equals("wall_torch") || id.equals("soul_torch") || id.equals("soul_wall_torch")
				|| id.equals("lantern") || id.equals("obsidian") || id.equals("ladder")
				|| (id.endsWith("_door") && !id.equals("iron_door")) || id.endsWith("_trapdoor")
				|| id.endsWith("_fence_gate") || id.equals("campfire") || id.equals("lectern")
				|| id.equals("brewing_stand") || id.equals("flower_pot") || id.startsWith("potted_")
				|| id.equals("rail") || id.equals("cartography_table") || id.equals("fletching_table")
				|| id.equals("smithing_table") || id.equals("loom") || id.equals("stonecutter")
				|| id.equals("grindstone") || id.equals("composter") || id.equals("bell");
		// Cobblestone is everywhere in the Overworld but never generates in the Nether or End.
		if (otherDimension && (id.equals("cobblestone") || id.equals("stone_bricks") || id.equals("oak_planks")
				|| id.equals("spruce_planks"))) {
			weak = true;
		}
		if (weak) return new Weight(otherDimension ? WEAK * 3 : WEAK, 4, true);
		return null;
	}

	private Map<Block, Weight> weights(boolean otherDimension) {
		if (overworldWeights == null) {
			overworldWeights = new IdentityHashMap<>();
			otherDimensionWeights = new IdentityHashMap<>();
			for (Block block : BuiltInRegistries.BLOCK) {
				String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
				Weight a = rule(id, false);
				Weight b = rule(id, true);
				if (a != null) overworldWeights.put(block, a);
				if (b != null) otherDimensionWeights.put(block, b);
			}
		}
		return otherDimension ? otherDimensionWeights : overworldWeights;
	}

	// ---- scanning ----

	@Override
	protected void scan(ClientLevel level, LevelChunk chunk) {
		boolean otherDimension = level.dimension() != Level.OVERWORLD;
		Map<Block, Weight> weights = weights(otherDimension);
		Map<Block, Integer> counts = new HashMap<>();
		List<BlockPos> hits = new ArrayList<>();
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;

		ChunkPos pos = chunk.getPos();
		int baseX = pos.getMinBlockX();
		int baseZ = pos.getMinBlockZ();
		LevelChunkSection[] sections = chunk.getSections();
		int[] weightAtY = new int[sections.length * 16];
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section == null || section.hasOnlyAir()) continue;
			if (!section.maybeHas(state -> weights.containsKey(state.getBlock()))) continue;

			int baseY = chunk.getMinY() + i * 16;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						Weight weight = weights.get(state.getBlock());
						if (weight == null) continue;
						counts.merge(state.getBlock(), 1, Integer::sum);
						weightAtY[i * 16 + y] += weight.points();
						if (!weight.weak() && hits.size() < 32) hits.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
						minY = Math.min(minY, baseY + y);
						maxY = Math.max(maxY, baseY + y);
					}
				}
			}
		}

		int score = 0;
		int weakScore = 0;
		Map<String, Integer> reasons = new LinkedHashMap<>();
		List<Map.Entry<Block, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort((a, b) -> Integer.compare(
				weights.get(b.getKey()).points() * b.getValue(), weights.get(a.getKey()).points() * a.getValue()));
		for (Map.Entry<Block, Integer> entry : sorted) {
			Weight weight = weights.get(entry.getKey());
			int points = weight.points() * Math.min(entry.getValue(), weight.perTypeCap());
			if (weight.weak()) weakScore += points;
			else score += points;
			reasons.put(BuiltInRegistries.BLOCK.getKey(entry.getKey()).getPath(), entry.getValue());
		}
		score += Math.min(weakScore, otherDimension ? 24 : 8);

		if (score < threshold.getInt()) {
			bases.remove(pos);
			return;
		}

		Base base = new Base(pos, score, reasons, List.copyOf(hits), minY, maxY, chunk.getMinY(), weightAtY);
		bases.put(pos, base);
		if (reported.add(pos)) report(base);
	}

	private void report(Base base) {
		int x = base.pos().getMinBlockX() + 8;
		int z = base.pos().getMinBlockZ() + 8;
		StringBuilder top = new StringBuilder();
		int shown = 0;
		for (Map.Entry<String, Integer> reason : base.reasons().entrySet()) {
			if (shown++ == 4) break;
			if (!top.isEmpty()) top.append(", ");
			top.append(reason.getValue()).append(' ').append(reason.getKey().replace('_', ' '));
		}

		String detail = "Score " + base.score() + " at " + x + ", " + z;
		if (notify.isOn()) Notifications.notice("Possible base", detail, 0xFFFF8A3D);
		if (chat.isOn()) Finds.chat("Possible base: " + detail + " (" + top + ")");
		if (log.isOn()) {
			Finds.log("bases.csv", "chunk_x,chunk_z,x,y,z,score,found",
					base.pos().x() + "," + base.pos().z() + "," + x + "," + base.core(boxHeight.getInt())[0] + "," + z + "," + base.score()
							+ ",\"" + top + "\"");
		}
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
		if (bases.isEmpty()) return;
		int base = color.color();
		int marker = markerColor.color();
		float limit = threshold.getFloat() * 3f;

		for (Base b : bases.values()) {
			if (chunkDistance(b.pos()) > 40) continue;
			if (highlight.isOn()) {
				// Stronger finds glow brighter.
				float strength = Math.min(1f, b.score() / limit);
				int[] core = b.core(boxHeight.getInt());
				AABB column = chunkBox(b.pos(), core[0] - 1, core[1] + 2);
				batch.fill(column, ColorUtil.fade(base, 0.06f + 0.12f * strength), true);
				batch.outline(column, ColorUtil.fade(base, 0.55f + 0.45f * strength), 2f, true);
			}
			if (markBlocks.isOn()) {
				for (BlockPos hit : b.hits()) {
					batch.outline(new AABB(hit).inflate(0.02), ColorUtil.fade(marker, 0.9f), 1.5f, true);
				}
			}
		}
	}
}
