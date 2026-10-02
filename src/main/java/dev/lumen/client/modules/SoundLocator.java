package dev.lumen.client.modules;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.Lumen;
import dev.lumen.client.gui.Ui;
import dev.lumen.client.hud.HudOverlay;
import dev.lumen.client.hud.Notifications;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.render.CameraUtil;
import dev.lumen.client.render.EspBatch;
import dev.lumen.client.render.Projection;
import dev.lumen.client.render.WorldRenderable;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ColorUtil;
import dev.lumen.client.util.Finds;

/**
 * Marks where loud or server-wide sounds came from. Some events (a wither spawning,
 * the dragon dying, an end portal opening) are announced to every player, and sound
 * packets carry the position they were played at.
 */
public final class SoundLocator extends Module implements WorldRenderable, HudOverlay {
	public record Marker(String label, Vec3 pos, long timeMillis, boolean global) {
	}

	private final BoolSetting globalEvents = add(new BoolSetting("Global events", "Wither spawns, dragon deaths and end portals, which every player hears.", true));
	private final BoolSetting distantSounds = add(new BoolSetting("Distant sounds", "Any sound played far from you, like explosions or goat horns.", true));
	private final NumberSetting minDistance = add(new NumberSetting("Min distance", "Ignore sounds closer than this.", 64, 16, 256, 8, "m"))
			.visibleWhen(distantSounds::isOn);
	private final BoolSetting ignoreWeather = add(new BoolSetting("Ignore thunder", "Skip weather sounds.", true))
			.visibleWhen(distantSounds::isOn);
	private final NumberSetting lifetime = add(new NumberSetting("Keep for", "How long markers stay, in minutes.", 5, 1, 60, 1, " min"));
	private final ColorSetting color = add(new ColorSetting("Color", "Colour of sound markers.", 0xFF5CE1E6));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Pop-up for each new marker.", true));
	private final BoolSetting chat = add(new BoolSetting("Chat message", "Client-side chat line with the position.", true));
	private final BoolSetting log = add(new BoolSetting("Log to file", "Append to config/lumen/sounds.csv.", true));

	private final List<Marker> markers = new ArrayList<>();

	public SoundLocator() {
		super("Sound Locator", "Marks where loud and server-wide sounds came from.", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		markers.clear();
	}

	public List<Marker> markers() {
		return List.copyOf(markers);
	}

	@Override
	public String hudInfo() {
		return Integer.toString(markers.size());
	}

	@Override
	public void onTick() {
		long cutoff = System.currentTimeMillis() - (long) (lifetime.get() * 60_000);
		markers.removeIf(m -> m.timeMillis() < cutoff);
	}

	/** A sound packet arrived. Called from the packet listener mixin. */
	public void onSound(String soundId, SoundSource source, double x, double y, double z) {
		if (!distantSounds.isOn() || MC.player == null) return;
		if (ignoreWeather.isOn() && source == SoundSource.WEATHER) return;
		if (source == SoundSource.MUSIC || source == SoundSource.AMBIENT) return;
		Vec3 pos = new Vec3(x, y, z);
		if (pos.distanceTo(MC.player.position()) < minDistance.get()) return;
		String name = soundId.contains(":") ? soundId.substring(soundId.indexOf(':') + 1) : soundId;
		add(new Marker(name.replace('.', ' ').replace('_', ' '), pos, System.currentTimeMillis(), false));
	}

	/** A level event arrived. Only global ones are interesting. */
	public void onLevelEvent(int type, BlockPos pos, boolean global) {
		if (!global || !globalEvents.isOn()) return;
		String label = switch (type) {
			case 1023 -> "Wither spawned";
			case 1028 -> "Ender dragon died";
			case 1038 -> "End portal opened";
			default -> "Global event " + type;
		};
		add(new Marker(label, Vec3.atCenterOf(pos), System.currentTimeMillis(), true));
	}

	private void add(Marker marker) {
		// Collapse repeats of the same sound from the same spot.
		for (Marker m : markers) {
			if (m.label().equals(marker.label()) && m.pos().distanceTo(marker.pos()) < 4
					&& marker.timeMillis() - m.timeMillis() < 10_000) {
				return;
			}
		}
		markers.add(marker);
		while (markers.size() > 64) markers.removeFirst();

		String where = (int) Math.floor(marker.pos().x) + ", " + (int) Math.floor(marker.pos().y) + ", " + (int) Math.floor(marker.pos().z);
		if (notify.isOn()) Notifications.notice(capitalize(marker.label()), where, color.color());
		if (chat.isOn()) Finds.chat(capitalize(marker.label()) + " at " + where);
		if (log.isOn()) Finds.log("sounds.csv", "sound,global,x,y,z", "\"" + marker.label() + "\"," + marker.global() + "," + where.replace(" ", ""));
	}

	private static String capitalize(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
		int c = color.color();
		float[] start = CameraUtil.tracerOrigin();
		for (Marker m : markers) {
			Vec3 p = m.pos();
			batch.outline(new AABB(p.x - 0.5, p.y - 0.5, p.z - 0.5, p.x + 0.5, p.y + 0.5, p.z + 0.5), c, 2f, true);
			// A tall thin column makes far markers easy to spot.
			batch.fill(new AABB(p.x - 0.08, p.y, p.z - 0.08, p.x + 0.08, p.y + 48, p.z + 0.08), ColorUtil.fade(c, 0.6f), true);
			batch.tracer(start[0], start[1], start[2], p.x, p.y, p.z, ColorUtil.fade(c, 0.6f), 1.5f);
		}
	}

	@Override
	public void drawOverlay(Ui ui, int screenWidth, int screenHeight) {
		for (Marker m : markers) {
			Projection.Point point = Projection.toScreen(m.pos().add(0, 1.2, 0));
			if (point == null) continue;
			String label = capitalize(m.label()) + "  " + Math.round(point.distance()) + "m";
			int w = ui.width(label);
			int x = Math.round(point.x()) - w / 2;
			int y = Math.round(point.y()) - 10;
			ui.roundRect(x - 4, y - 2, x + w + 4, y + 10, 3, 0x8C08090E);
			ui.text(label, x, y, m.global() ? Lumen.modules().clickGui.accentAt(0.3f) : color.color());
		}
	}
}
