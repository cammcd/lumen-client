package dev.lumen.client.modules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.module.Category;
import dev.lumen.client.render.EspBatch;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ColorUtil;

/**
 * Highlights chosen ores and blocks through terrain. Servers with anti-xray hide or
 * fake ores before sending chunks, and this can only show what the server sends.
 */
public final class XRay extends WorldScanModule {
	private record Group(ColorSetting setting, String[] ids) {
	}

	private record Hit(BlockPos pos, ColorSetting color) {
	}

	private final List<Group> groups = new ArrayList<>();
	private final NumberSetting range;
	private final NumberSetting fillOpacity;
	private final BoolSetting outline;
	private final BoolSetting exposedOnly;

	private final Map<ChunkPos, List<Hit>> hits = new HashMap<>();
	private Map<Block, ColorSetting> targets;
	private String lastSignature = "";

	public XRay() {
		super("X-Ray", "Highlights chosen ores and blocks through terrain.", Category.RENDER);
		setDefaultSection("Blocks");
		group("Diamonds", 0xFF4DE8E8, true, "diamond_ore", "deepslate_diamond_ore");
		group("Ancient debris", 0xFFB06A4F, true, "ancient_debris");
		group("Emeralds", 0xFF2EE66B, true, "emerald_ore", "deepslate_emerald_ore");
		group("Gold", 0xFFFFD447, false, "gold_ore", "deepslate_gold_ore", "nether_gold_ore");
		group("Iron", 0xFFD8AF93, false, "iron_ore", "deepslate_iron_ore");
		group("Redstone", 0xFFFF4040, false, "redstone_ore", "deepslate_redstone_ore");
		group("Lapis", 0xFF3B5BDB, false, "lapis_ore", "deepslate_lapis_ore");
		group("Copper", 0xFFE08A5B, false, "copper_ore", "deepslate_copper_ore");
		group("Coal", 0xFF404048, false, "coal_ore", "deepslate_coal_ore");
		group("Nether quartz", 0xFFEDE6DA, false, "nether_quartz_ore");
		group("Obsidian", 0xFF5A3D8A, false, "obsidian", "crying_obsidian");
		group("Budding amethyst", 0xFFB37BFF, false, "budding_amethyst");

		setDefaultSection("Appearance");
		range = add(new NumberSetting("Range", "Maximum distance to highlight, in blocks.", 64, 16, 256, 8, "m"));
		fillOpacity = add(new NumberSetting("Fill opacity", "Opacity of the filled boxes.", 30, 0, 100, 1, "%"));
		outline = add(new BoolSetting("Outline", "Draw box edges.", true));
		exposedOnly = add(new BoolSetting("Exposed only", "Only blocks touching air. Cuts down fake ores from some anti-xray setups.", false));
	}

	private void group(String name, int color, boolean on, String... ids) {
		ColorSetting setting = add(new ColorSetting(name, "Highlight " + name.toLowerCase(java.util.Locale.ROOT) + ".", color, on));
		groups.add(new Group(setting, ids));
	}

	@Override
	protected int chunksPerTick() {
		return 6;
	}

	@Override
	protected void reset() {
		hits.clear();
		targets = null;
	}

	public int count() {
		int n = 0;
		for (List<Hit> list : hits.values()) n += list.size();
		return n;
	}

	@Override
	public String hudInfo() {
		return Integer.toString(count());
	}

	/** Which groups are on, and exposed-only; a change means everything must be rescanned. */
	private String signature() {
		StringBuilder sb = new StringBuilder(exposedOnly.isOn() ? "e" : "a");
		for (Group g : groups) sb.append(g.setting().isEnabled() ? '1' : '0');
		return sb.toString();
	}

	@Override
	public void onTick() {
		String signature = signature();
		if (!signature.equals(lastSignature)) {
			lastSignature = signature;
			rescanAll();
		}
		super.onTick();
	}

	private Map<Block, ColorSetting> targets() {
		if (targets == null) {
			targets = new IdentityHashMap<>();
			Map<String, ColorSetting> byId = new HashMap<>();
			for (Group g : groups) {
				if (!g.setting().isEnabled()) continue;
				for (String id : g.ids()) byId.put(id, g.setting());
			}
			for (Block block : BuiltInRegistries.BLOCK) {
				ColorSetting s = byId.get(BuiltInRegistries.BLOCK.getKey(block).getPath());
				if (s != null) targets.put(block, s);
			}
		}
		return targets;
	}

	@Override
	protected void scan(ClientLevel level, LevelChunk chunk) {
		Map<Block, ColorSetting> wanted = targets();
		ChunkPos pos = chunk.getPos();
		if (wanted.isEmpty()) {
			hits.remove(pos);
			return;
		}

		List<Hit> found = new ArrayList<>();
		int baseX = pos.getMinBlockX();
		int baseZ = pos.getMinBlockZ();
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length && found.size() < 1024; i++) {
			LevelChunkSection section = sections[i];
			if (section == null || section.hasOnlyAir()) continue;
			if (!section.maybeHas(state -> wanted.containsKey(state.getBlock()))) continue;
			int baseY = chunk.getMinY() + i * 16;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						ColorSetting color = wanted.get(state.getBlock());
						if (color == null) continue;
						BlockPos p = new BlockPos(baseX + x, baseY + y, baseZ + z);
						if (exposedOnly.isOn() && !exposed(level, p)) continue;
						found.add(new Hit(p, color));
					}
				}
			}
		}

		if (found.isEmpty()) hits.remove(pos);
		else hits.put(pos, found);
	}

	private static boolean exposed(ClientLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockState neighbour = level.getBlockState(pos.relative(d));
			if (neighbour.isAir() || !neighbour.getFluidState().isEmpty()) return true;
		}
		return false;
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
		double max = range.get();
		double maxSq = max * max;
		float fill = fillOpacity.getFloat() / 100f;
		for (List<Hit> list : hits.values()) {
			for (Hit hit : list) {
				BlockPos p = hit.pos();
				double dx = p.getX() + 0.5 - cam.x, dy = p.getY() + 0.5 - cam.y, dz = p.getZ() + 0.5 - cam.z;
				if (dx * dx + dy * dy + dz * dz > maxSq) continue;
				int c = hit.color().color();
				AABB box = new AABB(p);
				if (fill > 0) batch.fill(box, ColorUtil.fade(c, fill), true);
				if (outline.isOn()) batch.outline(box, ColorUtil.fade(c, 0.95f), 1.5f, true);
			}
		}
	}
}
