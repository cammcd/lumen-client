package dev.lumen.client.modules;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.Lumen;
import dev.lumen.client.gui.Ui;
import dev.lumen.client.hud.HudOverlay;
import dev.lumen.client.hud.Notifications;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.render.EspBatch;
import dev.lumen.client.render.Projection;
import dev.lumen.client.render.WorldRenderable;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ColorUtil;
import dev.lumen.client.util.Finds;

/**
 * Saved locations, kept per server and dimension in config/lumen/waypoints.json.
 * Drawn as a beam with a label and distance. A waypoint is added automatically
 * where you die.
 */
public final class Waypoints extends Module implements WorldRenderable, HudOverlay {
	private static final int[] PALETTE = {0xFF8B6CFF, 0xFF3FD0FF, 0xFF6CF0A0, 0xFFFFD166, 0xFFFF8A3D, 0xFFFF6B9D};
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

	public static final class Waypoint {
		public String name;
		public final int x, y, z;
		public final String server;
		public final String dimension;
		public final int color;
		public final boolean death;
		public boolean visible = true;

		Waypoint(String name, int x, int y, int z, String server, String dimension, int color, boolean death) {
			this.name = name;
			this.x = x;
			this.y = y;
			this.z = z;
			this.server = server;
			this.dimension = dimension;
			this.color = color;
			this.death = death;
		}

		public Vec3 center() {
			return new Vec3(x + 0.5, y, z + 0.5);
		}
	}

	private final BoolSetting beams = add(new BoolSetting("Beams", "Draw a tall beam at each waypoint.", true));
	private final BoolSetting labels = add(new BoolSetting("Labels", "Show the name and distance.", true));
	private final NumberSetting maxDistance = add(new NumberSetting("Max distance", "Hide waypoints farther than this, in blocks.", 20000, 100, 100000, 100, "m"));
	private final BoolSetting deathWaypoints = add(new BoolSetting("Death waypoints", "Add a waypoint where you die.", true));
	private final NumberSetting keepDeaths = add(new NumberSetting("Keep deaths", "Death waypoints kept per world; older ones are removed.", 3, 1, 10, 1))
			.visibleWhen(deathWaypoints::isOn);

	private final List<Waypoint> all = new ArrayList<>();
	private final Path file = Finds.logFile("waypoints.json");
	private boolean loaded;
	private boolean wasDead;

	public Waypoints() {
		super("Waypoints", "Saved locations with beams and distances. Press N to add one where you stand.", Category.WORLD);
		loadEnabled(true);
	}

	// ---- storage ----

	private void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		if (!Files.exists(file)) return;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonArray()) return;
			for (JsonElement e : root.getAsJsonArray()) {
				JsonObject o = e.getAsJsonObject();
				Waypoint w = new Waypoint(o.get("name").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
						o.get("server").getAsString(), o.get("dimension").getAsString(), o.get("color").getAsInt(),
						o.has("death") && o.get("death").getAsBoolean());
				w.visible = !o.has("visible") || o.get("visible").getAsBoolean();
				all.add(w);
			}
		} catch (Exception ex) {
			Lumen.LOGGER.error("Could not read {}", file, ex);
		}
	}

	private void save() {
		JsonArray array = new JsonArray();
		for (Waypoint w : all) {
			JsonObject o = new JsonObject();
			o.addProperty("name", w.name);
			o.addProperty("x", w.x);
			o.addProperty("y", w.y);
			o.addProperty("z", w.z);
			o.addProperty("server", w.server);
			o.addProperty("dimension", w.dimension);
			o.addProperty("color", w.color);
			o.addProperty("death", w.death);
			o.addProperty("visible", w.visible);
			array.add(o);
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(array), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			Lumen.LOGGER.error("Could not save {}", file, ex);
		}
	}

	/** Waypoints for the current server and dimension. */
	public List<Waypoint> here() {
		ensureLoaded();
		String server = Finds.server();
		String dimension = Finds.dimension();
		List<Waypoint> result = new ArrayList<>();
		for (Waypoint w : all) {
			if (w.server.equals(server) && w.dimension.equals(dimension)) result.add(w);
		}
		return result;
	}

	public Waypoint add(String name, BlockPos pos, boolean death) {
		ensureLoaded();
		int color = death ? 0xFFFF5252 : PALETTE[here().size() % PALETTE.length];
		Waypoint w = new Waypoint(name, pos.getX(), pos.getY(), pos.getZ(), Finds.server(), Finds.dimension(), color, death);
		all.add(w);
		if (death) trimDeaths();
		save();
		return w;
	}

	/** Adds a waypoint where the player stands. Used by the keybind and the screen. */
	public Waypoint addHere(String name) {
		if (MC.player == null) return null;
		BlockPos pos = MC.player.blockPosition();
		String n = name == null || name.isBlank() ? "Waypoint " + (here().size() + 1) : name.trim();
		Waypoint w = add(n, pos, false);
		Notifications.notice("Waypoint added", n + "  " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ(), w.color);
		return w;
	}

	public void remove(Waypoint w) {
		all.remove(w);
		save();
	}

	public void setVisible(Waypoint w, boolean visible) {
		w.visible = visible;
		save();
	}

	private void trimDeaths() {
		List<Waypoint> deaths = new ArrayList<>();
		for (Waypoint w : here()) if (w.death) deaths.add(w);
		while (deaths.size() > keepDeaths.getInt()) all.remove(deaths.removeFirst());
	}

	@Override
	public String hudInfo() {
		return Integer.toString(here().size());
	}

	// ---- death tracking ----

	@Override
	public void onTick() {
		if (MC.player == null) {
			wasDead = false;
			return;
		}
		boolean dead = MC.player.isDeadOrDying();
		if (dead && !wasDead && deathWaypoints.isOn()) {
			BlockPos pos = MC.player.blockPosition();
			add("Death " + LocalTime.now().format(CLOCK), pos, true);
			Finds.chat("Death waypoint saved at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
		}
		wasDead = dead;
	}

	// ---- drawing ----

	/** Where to draw a waypoint: far ones are pulled in along the same bearing so they stay visible. */
	private static Vec3 drawPos(Vec3 target, Vec3 cam) {
		Vec3 delta = target.subtract(cam);
		double dist = delta.length();
		if (dist <= 160) return target;
		return cam.add(delta.scale(160 / dist));
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
		if (!beams.isOn()) return;
		for (Waypoint w : here()) {
			if (!w.visible) continue;
			Vec3 target = w.center();
			double dist = target.distanceTo(cam);
			if (dist > maxDistance.get()) continue;
			Vec3 p = drawPos(target, cam);
			double scale = Math.min(1.0, 160 / Math.max(1, dist));
			double r = Math.max(0.05, 0.15 * scale);
			batch.fill(new AABB(p.x - r, p.y, p.z - r, p.x + r, p.y + 256 * scale, p.z + r), ColorUtil.fade(w.color, 0.55f), true);
			if (dist <= 160) {
				batch.outline(new AABB(w.x, w.y, w.z, w.x + 1, w.y + 1, w.z + 1), w.color, 2f, true);
			}
		}
	}

	@Override
	public void drawOverlay(Ui ui, int screenWidth, int screenHeight) {
		if (!labels.isOn()) return;
		Vec3 cam = Projection.camera();
		for (Waypoint w : here()) {
			if (!w.visible) continue;
			Vec3 target = w.center().add(0, 1.4, 0);
			double dist = target.distanceTo(cam);
			if (dist > maxDistance.get()) continue;
			Projection.Point point = Projection.toScreen(drawPos(target, cam));
			if (point == null) continue;

			String distance = dist >= 1000 ? String.format(java.util.Locale.ROOT, "%.1fkm", dist / 1000) : Math.round(dist) + "m";
			int nameW = ui.width(w.name);
			int distW = ui.width(distance);
			int total = nameW + 6 + distW;
			int x = Math.round(point.x()) - total / 2;
			int y = Math.round(point.y()) - 10;
			ui.roundRect(x - 5, y - 2, x + total + 5, y + 10, 3, 0x8C08090E);
			ui.rect(x - 5, y - 2, x - 3, y + 10, w.color);
			ui.text(w.name, x, y, 0xFFFFFFFF);
			ui.text(distance, x + nameW + 6, y, 0xFFB7BCCF);
		}
	}
}
