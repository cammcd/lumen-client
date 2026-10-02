package dev.lumen.client.modules;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

import dev.lumen.client.Lumen;
import dev.lumen.client.gui.Ui;
import dev.lumen.client.hud.HudOverlay;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ColorUtil;

/** A small north-up map of nearby chunks with trails, finds, players and waypoints. */
public final class Radar extends Module implements HudOverlay {
	private final NumberSetting size = add(new NumberSetting("Size", "Width and height of the map.", 112, 64, 200, 4, "px"));
	private final NumberSetting range = add(new NumberSetting("Range", "Chunks shown in each direction.", 10, 4, 32, 1, " ch"));
	private final BoolSetting chunks = add(new BoolSetting("Chunk colours", "Colour new and old chunks (needs New Chunks on).", true));
	private final BoolSetting finds = add(new BoolSetting("Finds", "Mark stashes and bases (needs the finders on).", true));
	private final BoolSetting players = add(new BoolSetting("Players", "Show other players as dots.", true));
	private final BoolSetting waypoints = add(new BoolSetting("Waypoints", "Show waypoints as dots.", true));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "Background opacity.", 75, 0, 100, 1, "%"));

	public Radar() {
		super("Radar", "Minimap of nearby chunks with trails, stashes, bases, players and waypoints.", Category.WORLD);
	}

	@Override
	public void drawOverlay(Ui ui, int screenWidth, int screenHeight) {
		if (MC.player == null || MC.level == null) return;
		HudModule hud = Lumen.modules().hud;
		int s = size.getInt();
		int x1 = 4;
		int y1 = hud.isEnabled() && hud.watermark.isOn() ? 26 : 4;
		int x2 = x1 + s;
		int y2 = y1 + s;
		int r = Lumen.modules().clickGui.cornerRadius.getInt();

		ui.roundRect(x1, y1, x2, y2, r, ColorUtil.withAlpha(Lumen.modules().clickGui.panelColor.color(), Math.round(opacity.getFloat() / 100f * 255)));

		double px = MC.player.getX();
		double pz = MC.player.getZ();
		int chunkRange = range.getInt();
		double scale = s / (chunkRange * 2.0 * 16.0);
		float cx = x1 + s / 2f;
		float cy = y1 + s / 2f;

		ui.pushClip(x1 + 1, y1 + 1, x2 - 1, y2 - 1);
		ChunkPos here = MC.player.chunkPosition();
		NewChunks newChunks = Lumen.modules().newChunks;
		StashFinder stash = Lumen.modules().stashFinder;
		BaseFinder base = Lumen.modules().baseFinder;
		var stashes = finds.isOn() && stash.isEnabled() ? stash.stashes() : java.util.Map.<ChunkPos, StashFinder.Stash>of();
		var bases = finds.isOn() && base.isEnabled() ? base.bases() : java.util.Map.<ChunkPos, BaseFinder.Base>of();

		for (int dx = -chunkRange - 1; dx <= chunkRange + 1; dx++) {
			for (int dz = -chunkRange - 1; dz <= chunkRange + 1; dz++) {
				ChunkPos pos = new ChunkPos(here.x() + dx, here.z() + dz);
				int color = 0;
				if (chunks.isOn() && newChunks.isEnabled()) {
					if (newChunks.isNew(pos)) color = 0x70FF4D5E;
					else if (newChunks.isOld(pos)) color = 0x704D8BFF;
				}
				if (bases.containsKey(pos)) color = 0xD0FF8A3D;
				if (stashes.containsKey(pos)) color = 0xD0FFD166;
				if (color == 0) continue;
				int sx = Math.round((float) (cx + (pos.getMinBlockX() - px) * scale));
				int sz = Math.round((float) (cy + (pos.getMinBlockZ() - pz) * scale));
				int cell = Math.max(1, (int) Math.round(16 * scale));
				ui.rect(sx, sz, sx + cell, sz + cell, color);
			}
		}

		if (waypoints.isOn() && Lumen.modules().waypoints.isEnabled()) {
			for (Waypoints.Waypoint w : Lumen.modules().waypoints.here()) {
				if (!w.visible) continue;
				int wx = Math.round((float) (cx + (w.x + 0.5 - px) * scale));
				int wz = Math.round((float) (cy + (w.z + 0.5 - pz) * scale));
				ui.rect(wx - 2, wz - 2, wx + 2, wz + 2, w.color);
			}
		}

		if (players.isOn()) {
			for (Player other : MC.level.players()) {
				if (other == MC.player) continue;
				int ox = Math.round((float) (cx + (other.getX() - px) * scale));
				int oz = Math.round((float) (cy + (other.getZ() - pz) * scale));
				ui.rect(ox - 1, oz - 1, ox + 2, oz + 2, 0xFFFF5D73);
			}
		}

		// Arrow for the player, pointing where they face (yaw 0 is south, which is down).
		var pose = ui.g.pose();
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.rotate((float) Math.toRadians(MC.player.getYRot() + 180f));
		int arrow = Lumen.modules().clickGui.accentAt(0.2f);
		ui.rect(-1, -4, 1, 3, 0xFFFFFFFF);
		ui.rect(-2, -2, 2, -1, 0xFFFFFFFF);
		ui.rect(-3, -1, -1, 1, arrow);
		ui.rect(1, -1, 3, 1, arrow);
		pose.popMatrix();
		ui.popClip();

		ui.text("N", Math.round(cx) - 2, y1 + 2, 0xB0FFFFFF);
	}
}
