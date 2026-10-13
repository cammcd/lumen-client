package dev.lumen.client.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.hud.Notifications;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.render.EspBatch;
import dev.lumen.client.render.WorldRenderable;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.setting.TextSetting;
import dev.lumen.client.util.ColorUtil;
import dev.lumen.client.util.Finds;

/**
 * Shows slime chunks, two ways. From the world seed it is exact: the game decides a slime
 * chunk with the seed alone, and this is the same sum. In singleplayer the seed is read by
 * itself; on a server it has to be typed in. Without a seed, chunks where slimes are seen
 * spawning below Y 40 are marked, since below Y 40 slimes spawn naturally only in slime
 * chunks. A spawn in a chunk the seed rules out means the seed or salt is wrong.
 */
public final class SlimeChunks extends Module implements WorldRenderable {
	/** The salt vanilla mixes into the seed for slime chunks; Spigot's seed-slime defaults to it too. */
	public static final long VANILLA_SALT = 987234911L;
	private static final int SETTLE_TICKS = 100;

	private final TextSetting seedText = add(new TextSetting("Seed",
			"The world seed, for a server: from /seed if you may use it. In singleplayer it is read by itself.", "", 32));
	private final TextSetting saltText = add(new TextSetting("Salt",
			"Only change if the server set its own slime seed (Spigot's seed-slime).", Long.toString(VANILLA_SALT), 24));
	private final BoolSetting watch = add(new BoolSetting("Watch spawns",
			"Mark chunks where slimes are seen spawning below Y 40. Works without a seed; stand still for it to count.", true));
	private final BoolSetting announce = add(new BoolSetting("Announce", "Say when you walk into a slime chunk.", true));
	private final ColorSetting slimeColor = add(new ColorSetting("Slime chunks", "Colour of slime chunks from the seed.", 0xFF5DD65D));
	private final ColorSetting seenColor = add(new ColorSetting("Seen spawning", "Colour of chunks where slimes were seen spawning.", 0xFFFFD84D));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "Opacity of the chunk squares.", 30, 5, 100, 1, "%"));
	private final NumberSetting drawDistance = add(new NumberSetting("Draw distance", "How far to draw squares, in chunks.", 8, 2, 32, 1, " ch"));
	private final BoolSetting followPlayer = add(new BoolSetting("Follow player", "Draw the squares just below your feet. Turn off to use a fixed height.", true));
	private final NumberSetting height = add(new NumberSetting("Height", "Fixed height of the squares.", 30, -64, 320, 1))
			.visibleWhen(() -> !followPlayer.isOn());
	private final BoolSetting throughWalls = add(new BoolSetting("Through walls", "Draw squares on top of terrain.", true));

	private final Map<Long, Boolean> cache = new HashMap<>();
	private long cacheSeed;
	private long cacheSalt;
	private final Map<ChunkPos, Integer> seen = new HashMap<>();
	private final Set<ChunkPos> contradicted = new HashSet<>();
	private final Set<Integer> knownSlimes = new HashSet<>();
	private ClientLevel level;
	private Vec3 anchor;
	private int anchorTick;
	private int ticks;
	private ChunkPos lastChunk;

	public SlimeChunks() {
		super("Slime Chunks", "Shows slime chunks: exactly from the world seed, or from where slimes are seen spawning.", Category.WORLD);
	}

	/** Exactly the game's test: WorldgenRandom.seedSlimeChunk(x, z, seed, salt).nextInt(10) == 0. */
	public static boolean isSlimeChunk(long seed, int x, int z, long salt) {
		long mixed = seed + (long) (x * x * 4987142) + (long) (x * 5947611) + (long) (z * z) * 4392871L + (long) (z * 389711) ^ salt;
		return new Random(mixed).nextInt(10) == 0;
	}

	/** The seed: the integrated server's in singleplayer, else the one typed in; empty if neither. */
	public OptionalLong seed() {
		MinecraftServer server = MC.getSingleplayerServer();
		if (server != null && server.overworld() != null) return OptionalLong.of(server.overworld().getSeed());
		String text = seedText.get().trim();
		if (text.isEmpty()) return OptionalLong.empty();
		try {
			return OptionalLong.of(Long.parseLong(text));
		} catch (NumberFormatException e) {
			// As the game does for a seed typed as words.
			return OptionalLong.of(text.hashCode());
		}
	}

	public long salt() {
		try {
			return Long.parseLong(saltText.get().trim());
		} catch (NumberFormatException e) {
			return VANILLA_SALT;
		}
	}

	/** True or false from the seed; null without a seed or outside the Overworld. */
	public Boolean slimeChunk(ChunkPos pos) {
		if (MC.level == null || MC.level.dimension() != Level.OVERWORLD) return null;
		OptionalLong seed = seed();
		if (seed.isEmpty()) return null;
		long salt = salt();
		if (seed.getAsLong() != cacheSeed || salt != cacheSalt) {
			cache.clear();
			cacheSeed = seed.getAsLong();
			cacheSalt = salt;
		}
		long key = ((long) pos.x() << 32) | (pos.z() & 0xFFFFFFFFL);
		return cache.computeIfAbsent(key, k -> isSlimeChunk(cacheSeed, pos.x(), pos.z(), cacheSalt));
	}

	/** Slimes seen spawning in a chunk since the module was turned on. */
	public int seen(ChunkPos pos) {
		return seen.getOrDefault(pos, 0);
	}

	/** Chunks where slimes spawned although the seed says they cannot. */
	public boolean contradicted(ChunkPos pos) {
		return contradicted.contains(pos);
	}

	/** The verdict for the chunk you stand in. */
	public String status() {
		if (MC.player == null) return "";
		if (MC.level.dimension() != Level.OVERWORLD) return "overworld only";
		ChunkPos here = ChunkPos.containing(MC.player.blockPosition());
		Boolean exact = slimeChunk(here);
		int spawns = seen(here);
		if (exact != null) return (exact ? "yes" : "no") + (spawns > 0 ? ", " + spawns + " seen" : "");
		return spawns > 0 ? "yes, " + spawns + " seen" : "no seed";
	}

	@Override
	public String hudInfo() {
		return status();
	}

	@Override
	protected void onEnable() {
		reset();
	}

	private void reset() {
		seen.clear();
		contradicted.clear();
		knownSlimes.clear();
		anchor = null;
		lastChunk = null;
		level = MC.level;
	}

	@Override
	public void onTick() {
		LocalPlayer p = MC.player;
		if (p == null || MC.level == null) return;
		if (MC.level != level) reset();
		ticks++;
		if (MC.level.dimension() != Level.OVERWORLD) return;

		ChunkPos here = ChunkPos.containing(p.blockPosition());
		if (!here.equals(lastChunk)) {
			Boolean exact = slimeChunk(here);
			if (lastChunk != null && announce.isOn() && Boolean.TRUE.equals(exact)) {
				Notifications.notice("Slime chunk", "Chunk " + here.x() + " " + here.z() + ", blocks " + (here.x() * 16) + " " + (here.z() * 16)
						+ " to " + (here.x() * 16 + 15) + " " + (here.z() * 16 + 15), slimeColor.color());
			}
			lastChunk = here;
		}
		if (watch.isOn()) watchSpawns(p);
	}

	/**
	 * A slime that appears below Y 40, between 24 and 48 blocks away, while you have stayed
	 * put for five seconds, has just spawned there: mobs never spawn closer than 24 blocks,
	 * and nothing new comes into view while you stand still unless it was just made.
	 */
	private void watchSpawns(LocalPlayer p) {
		Vec3 pos = p.position();
		if (anchor == null || pos.distanceTo(anchor) > 16) {
			anchor = pos;
			anchorTick = ticks;
		}
		boolean settled = ticks - anchorTick >= SETTLE_TICKS;
		Set<Integer> present = new HashSet<>();
		for (Entity e : MC.level.entitiesForRendering()) {
			if (!BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("slime")) continue;
			present.add(e.getId());
			if (!knownSlimes.add(e.getId()) || !settled || e.getY() >= 40) continue;
			double dx = e.getX() - pos.x;
			double dz = e.getZ() - pos.z;
			double flat = Math.sqrt(dx * dx + dz * dz);
			if (flat < 24 || flat > 48) continue;
			record(ChunkPos.containing(e.blockPosition()));
		}
		knownSlimes.retainAll(present);
	}

	private void record(ChunkPos chunk) {
		int count = seen.merge(chunk, 1, Integer::sum);
		Boolean exact = slimeChunk(chunk);
		if (Boolean.FALSE.equals(exact) && contradicted.add(chunk)) {
			Finds.chat("A slime spawned in chunk " + chunk.x() + " " + chunk.z() + ", which the seed says is not a slime chunk. "
					+ "The seed or salt is not this server's.");
		}
		if (count == 1) {
			Finds.chat("Slime seen spawning in chunk " + chunk.x() + " " + chunk.z() + " (blocks " + (chunk.x() * 16) + " " + (chunk.z() * 16)
					+ " to " + (chunk.x() * 16 + 15) + " " + (chunk.z() * 16 + 15) + "): a slime chunk.");
			Finds.log("slime_chunks.csv", "time,server,chunk_x,chunk_z,block_x,block_z,seed_says",
					java.time.Instant.now() + "," + Finds.csv(Finds.server()) + "," + chunk.x() + "," + chunk.z() + "," + (chunk.x() * 16) + ","
							+ (chunk.z() * 16) + "," + (exact == null ? "" : exact));
		}
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
		if (MC.player == null || MC.level == null || MC.level.dimension() != Level.OVERWORLD) return;
		double y = followPlayer.isOn() ? Math.floor(MC.player.getY()) - 0.98 : height.get();
		float alpha = opacity.getFloat() / 100f;
		boolean walls = throughWalls.isOn();
		int radius = drawDistance.getInt();
		ChunkPos center = ChunkPos.containing(MC.player.blockPosition());
		boolean exact = seed().isPresent();

		int fill = ColorUtil.fade(slimeColor.color(), alpha);
		int line = ColorUtil.fade(slimeColor.color(), Math.min(1f, alpha * 2.2f));
		if (exact) {
			for (int cx = center.x() - radius; cx <= center.x() + radius; cx++) {
				for (int cz = center.z() - radius; cz <= center.z() + radius; cz++) {
					ChunkPos pos = new ChunkPos(cx, cz);
					if (!Boolean.TRUE.equals(slimeChunk(pos))) continue;
					AABB square = WorldScanModule.chunkBox(pos, y, y + 0.02).deflate(0.35, 0, 0.35);
					batch.fill(square, fill, walls);
					batch.outline(square, line, 1.5f, walls);
				}
			}
		}
		int seenFill = ColorUtil.fade(seenColor.color(), alpha);
		int seenLine = ColorUtil.fade(seenColor.color(), Math.min(1f, alpha * 2.2f));
		for (ChunkPos pos : seen.keySet()) {
			if (WorldScanModule.chunkDistance(pos) > radius) continue;
			// A thinner square inside, so it shows on top of a slime chunk from the seed.
			AABB square = WorldScanModule.chunkBox(pos, y + 0.03, y + 0.05).deflate(2.5, 0, 2.5);
			batch.fill(square, seenFill, walls);
			batch.outline(square, seenLine, 2f, walls);
		}
	}
}
