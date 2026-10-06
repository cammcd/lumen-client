package dev.lumen.client.schematic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * A Litematica schematic (.litematic): gzipped NBT with one or more regions, each a
 * palette of block states and a tightly packed array of palette indices. This follows
 * Litematica's own reader: a region's Size may be negative, meaning it extends the
 * other way from its Position, and blocks are stored y by z by x from its lowest corner.
 * Block positions here are relative to the lowest corner of the whole schematic.
 */
public final class Schematic {
	public record Entry(BlockPos pos, BlockState state) {
	}

	/** Blocks renamed since older schematics were saved: old id to the id this version uses. */
	private static final Map<String, String> RENAMED = Map.of(
			"minecraft:grass", "minecraft:short_grass",
			"minecraft:grass_path", "minecraft:dirt_path",
			"minecraft:chain", "minecraft:iron_chain");
	private static Map<String, Block> byId;

	private final String name;
	private final List<Entry> blocks;
	private final BlockPos size;
	private final List<String> unknown;

	private Schematic(String name, List<Entry> blocks, BlockPos size, List<String> unknown) {
		this.name = name;
		this.blocks = blocks;
		this.size = size;
		this.unknown = unknown;
	}

	public String name() {
		return name;
	}

	/** Every non-air block, lowest layer first. */
	public List<Entry> blocks() {
		return blocks;
	}

	public BlockPos size() {
		return size;
	}

	/** Block ids this version of the game does not know; those blocks are left out. */
	public List<String> unknownBlocks() {
		return unknown;
	}

	public static Schematic load(Path file) throws IOException {
		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		CompoundTag regions = root.getCompoundOrEmpty("Regions");
		if (regions.isEmpty()) throw new IOException("no regions; is this a .litematic file?");

		List<Entry> raw = new ArrayList<>();
		Set<String> unknown = new LinkedHashSet<>();
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

		for (String key : regions.keySet()) {
			CompoundTag region = regions.getCompoundOrEmpty(key);
			BlockPos pos = readPos(region.getCompoundOrEmpty("Position"));
			BlockPos sizeTag = readPos(region.getCompoundOrEmpty("Size"));
			// As Litematica's getRelativeEndPositionFromAreaSize: the far corner is size - 1 toward its sign.
			BlockPos end = pos.offset(endOffset(sizeTag.getX()), endOffset(sizeTag.getY()), endOffset(sizeTag.getZ()));
			BlockPos lo = new BlockPos(Math.min(pos.getX(), end.getX()), Math.min(pos.getY(), end.getY()), Math.min(pos.getZ(), end.getZ()));
			BlockPos hi = new BlockPos(Math.max(pos.getX(), end.getX()), Math.max(pos.getY(), end.getY()), Math.max(pos.getZ(), end.getZ()));
			int sx = hi.getX() - lo.getX() + 1;
			int sy = hi.getY() - lo.getY() + 1;
			int sz = hi.getZ() - lo.getZ() + 1;
			minX = Math.min(minX, lo.getX());
			minY = Math.min(minY, lo.getY());
			minZ = Math.min(minZ, lo.getZ());
			maxX = Math.max(maxX, hi.getX());
			maxY = Math.max(maxY, hi.getY());
			maxZ = Math.max(maxZ, hi.getZ());

			ListTag palette = region.getListOrEmpty("BlockStatePalette");
			long[] data = region.getLongArray("BlockStates").orElse(new long[0]);
			if (palette.isEmpty() || data.length == 0) continue;

			BlockState[] states = new BlockState[palette.size()];
			for (int i = 0; i < states.length; i++) {
				CompoundTag entry = palette.getCompoundOrEmpty(i);
				// Some tools write the palette as plain strings like "minecraft:carrots[age=7]".
				String text = entry.isEmpty() ? palette.getStringOr(i, "") : entry.getStringOr("Name", "");
				BlockState state = readState(text, entry.getCompoundOrEmpty("Properties"));
				if (state == null) {
					unknown.add(id(text));
					state = Blocks.AIR.defaultBlockState();
				}
				states[i] = state;
			}

			int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
			long layer = (long) sx * sz;
			for (int y = 0; y < sy; y++) {
				for (int z = 0; z < sz; z++) {
					for (int x = 0; x < sx; x++) {
						long index = y * layer + (long) z * sx + x;
						int id = get(data, bits, index);
						if (id < 0 || id >= states.length) continue;
						BlockState state = states[id];
						if (state.isAir()) continue;
						raw.add(new Entry(lo.offset(x, y, z), state));
					}
				}
			}
		}

		if (minX == Integer.MAX_VALUE) throw new IOException("the schematic has no blocks");
		BlockPos min = new BlockPos(minX, minY, minZ);
		List<Entry> blocks = new ArrayList<>(raw.size());
		for (Entry e : raw) blocks.add(new Entry(e.pos().subtract(min), e.state()));
		blocks.sort(Comparator.comparingInt((Entry e) -> e.pos().getY()).thenComparingInt(e -> e.pos().getZ()).thenComparingInt(e -> e.pos().getX()));

		String name = file.getFileName().toString().replaceFirst("\\.litematic$", "");
		BlockPos size = new BlockPos(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
		return new Schematic(name, List.copyOf(blocks), size, List.copyOf(unknown));
	}

	/**
	 * A block state from its id and properties, read by hand rather than through the game's
	 * NBT reader so that the forms other tools write work too: a separate Properties tag or
	 * "id[key=value,...]", with or without "minecraft:", in any case. Properties this version
	 * does not have are ignored. Null if the block itself is unknown.
	 */
	public static BlockState readState(String text, CompoundTag properties) {
		String id = id(text);
		if (id.isEmpty()) return Blocks.AIR.defaultBlockState();
		Block block = block(id);
		if (block == null) return null;
		Map<String, String> values = new LinkedHashMap<>();
		int bracket = text.indexOf('[');
		if (bracket >= 0) {
			String inner = text.substring(bracket + 1).replace("]", "");
			for (String pair : inner.split(",")) {
				int eq = pair.indexOf('=');
				if (eq > 0) values.put(pair.substring(0, eq).trim().toLowerCase(Locale.ROOT), pair.substring(eq + 1).trim().toLowerCase(Locale.ROOT));
			}
		}
		for (String key : properties.keySet()) {
			values.put(key.toLowerCase(Locale.ROOT), properties.getStringOr(key, "").trim().toLowerCase(Locale.ROOT));
		}
		BlockState state = block.defaultBlockState();
		for (Map.Entry<String, String> v : values.entrySet()) {
			Property<?> property = block.getStateDefinition().getProperty(v.getKey());
			if (property != null) state = with(state, property, v.getValue());
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
		return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
	}

	/** "minecraft:oak_log" from " Oak_Log", "oak_log[axis=x]" and the like. */
	private static String id(String text) {
		String id = text.trim().toLowerCase(Locale.ROOT);
		int bracket = id.indexOf('[');
		if (bracket >= 0) id = id.substring(0, bracket).trim();
		if (id.isEmpty()) return "";
		return id.contains(":") ? id : "minecraft:" + id;
	}

	private static Block block(String id) {
		if (byId == null) {
			Map<String, Block> map = new HashMap<>();
			for (Block block : BuiltInRegistries.BLOCK) map.put(BuiltInRegistries.BLOCK.getKey(block).toString(), block);
			byId = map;
		}
		Block block = byId.get(id);
		if (block == null && RENAMED.containsKey(id)) block = byId.get(RENAMED.get(id));
		return block;
	}

	private static int endOffset(int size) {
		return size >= 0 ? size - 1 : size + 1;
	}

	private static BlockPos readPos(CompoundTag tag) {
		return new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
	}

	/** Litematica's LitematicaBitArray.getAt: entries are packed end to end and may span two longs. */
	static int get(long[] data, int bits, long index) {
		long startOffset = index * bits;
		int startArr = (int) (startOffset >> 6);
		int endArr = (int) (((index + 1L) * bits - 1L) >> 6);
		int startBit = (int) (startOffset & 0x3F);
		long mask = (1L << bits) - 1L;
		if (endArr >= data.length) return -1;
		if (startArr == endArr) return (int) (data[startArr] >>> startBit & mask);
		int endOffset = 64 - startBit;
		return (int) ((data[startArr] >>> startBit | data[endArr] << endOffset) & mask);
	}

	/** True for air-like blocks the printer never needs to place. */
	public static boolean isAir(BlockState state) {
		return state.isAir() || state.is(Blocks.VOID_AIR) || state.is(Blocks.CAVE_AIR);
	}
}
