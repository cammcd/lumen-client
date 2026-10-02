package dev.lumen.client.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.hud.Notifications;
import dev.lumen.client.render.EspBatch;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ColorUtil;
import dev.lumen.client.util.Finds;

/** Flags chunks holding an unusual number of containers, which is what stashes look like. */
public final class StashFinder extends WorldScanModule {
	/** A flagged chunk: container counts and the height range they span. */
	public record Stash(ChunkPos pos, int total, int chests, int shulkers, int barrels, int hoppers, int other,
			int minY, int maxY, int centerX, int centerZ) {
	}

	private final BoolSetting chests = add(new BoolSetting("Chests", "Count chests and trapped chests.", true));
	private final BoolSetting barrels = add(new BoolSetting("Barrels", "Count barrels.", true));
	private final BoolSetting shulkers = add(new BoolSetting("Shulker boxes", "Count shulker boxes.", true));
	private final BoolSetting hoppers = add(new BoolSetting("Hoppers", "Count hoppers.", true));
	private final BoolSetting dispensers = add(new BoolSetting("Droppers & dispensers", "Count droppers and dispensers.", false));
	private final BoolSetting enderChests = add(new BoolSetting("Ender chests", "Count ender chests.", false));
	private final NumberSetting threshold = add(new NumberSetting("Threshold", "Containers in one chunk needed to flag it.", 12, 2, 100, 1));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Pop-up when a stash is found.", true));
	private final BoolSetting chat = add(new BoolSetting("Chat message", "Client-side chat line with the coordinates. Nothing is sent to the server.", true));
	private final BoolSetting log = add(new BoolSetting("Log to file", "Append finds to config/lumen/stashes.csv.", true));
	private final BoolSetting highlight = add(new BoolSetting("Highlight", "Outline flagged chunks in the world.", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Colour of flagged chunks.", 0xFFFFD166))
			.visibleWhen(highlight::isOn);

	private final Map<ChunkPos, Stash> stashes = new HashMap<>();
	private final Set<ChunkPos> reported = new HashSet<>();

	public StashFinder() {
		super("Stash Finder", "Flags chunks with lots of containers, then notifies you and logs the coordinates.");
	}

	@Override
	protected void reset() {
		stashes.clear();
		reported.clear();
	}

	public Map<ChunkPos, Stash> stashes() {
		return Map.copyOf(stashes);
	}

	@Override
	public String hudInfo() {
		return Integer.toString(stashes.size());
	}

	@Override
	protected void scan(ClientLevel level, LevelChunk chunk) {
		int c = 0, s = 0, b = 0, h = 0, o = 0;
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		long sumX = 0, sumZ = 0;

		for (BlockEntity be : chunk.getBlockEntities().values()) {
			boolean counted = true;
			if (be instanceof ChestBlockEntity && chests.isOn()) c++;
			else if (be instanceof ShulkerBoxBlockEntity && shulkers.isOn()) s++;
			else if (be instanceof BarrelBlockEntity && barrels.isOn()) b++;
			else if (be instanceof HopperBlockEntity && hoppers.isOn()) h++;
			else if (be instanceof DispenserBlockEntity && dispensers.isOn()) o++;
			else if (be instanceof EnderChestBlockEntity && enderChests.isOn()) o++;
			else counted = false;

			if (counted) {
				int y = be.getBlockPos().getY();
				minY = Math.min(minY, y);
				maxY = Math.max(maxY, y);
				sumX += be.getBlockPos().getX();
				sumZ += be.getBlockPos().getZ();
			}
		}

		ChunkPos pos = chunk.getPos();
		int total = c + s + b + h + o;
		if (total < threshold.getInt()) {
			stashes.remove(pos);
			return;
		}

		Stash stash = new Stash(pos, total, c, s, b, h, o, minY, maxY, (int) (sumX / total), (int) (sumZ / total));
		stashes.put(pos, stash);
		if (reported.add(pos)) report(stash);
	}

	private void report(Stash stash) {
		String where = stash.centerX() + ", " + stash.centerZ();
		String detail = stash.total() + " containers at " + where;
		if (notify.isOn()) Notifications.notice("Stash found", detail, 0xFFFFD166);
		if (chat.isOn()) {
			Finds.chat("Stash: " + detail + " (" + stash.chests() + " chests, " + stash.shulkers() + " shulkers, "
					+ stash.barrels() + " barrels, " + stash.hoppers() + " hoppers)");
		}
		if (log.isOn()) {
			Finds.log("stashes.csv", "chunk_x,chunk_z,x,y,z,total,chests,shulkers,barrels,hoppers,other",
					stash.pos().x() + "," + stash.pos().z() + "," + stash.centerX() + "," + stash.minY() + "," + stash.centerZ()
							+ "," + stash.total() + "," + stash.chests() + "," + stash.shulkers() + "," + stash.barrels()
							+ "," + stash.hoppers() + "," + stash.other());
		}
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
		if (!highlight.isOn() || stashes.isEmpty()) return;
		int base = color.color();
		for (Stash stash : stashes.values()) {
			if (chunkDistance(stash.pos()) > 40) continue;
			AABB column = chunkBox(stash.pos(), stash.minY() - 1, stash.maxY() + 2);
			batch.fill(column, ColorUtil.fade(base, 0.12f), true);
			batch.outline(column, ColorUtil.fade(base, 0.9f), 2f, true);
		}
	}
}
