package dev.lumen.client.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.gui.Ui;
import dev.lumen.client.hud.HudOverlay;
import dev.lumen.client.hud.Notifications;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.render.EspBatch;
import dev.lumen.client.render.Projection;
import dev.lumen.client.render.WorldRenderable;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.util.ColorUtil;
import dev.lumen.client.util.Finds;

/**
 * Marks where players disconnected. A player who vanishes from view but is still in
 * the tab list just walked away; one who vanishes from the tab list too logged out.
 */
public final class LogoutSpots extends Module implements WorldRenderable, HudOverlay {
	public record Spot(UUID id, String name, AABB box, float health, long timeMillis) {
	}

	private record Seen(String name, AABB box, float health) {
	}

	private final ColorSetting color = add(new ColorSetting("Color", "Colour of logout markers.", 0xFFFF6B9D));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Pop-up when a nearby player logs out.", true));
	private final BoolSetting chat = add(new BoolSetting("Chat message", "Client-side chat line with where they logged out.", true));
	private final BoolSetting log = add(new BoolSetting("Log to file", "Append logouts to config/lumen/logouts.csv.", true));

	private final Map<UUID, Seen> seen = new HashMap<>();
	private final Map<UUID, Spot> spots = new LinkedHashMap<>();
	private ClientLevel lastLevel;

	public LogoutSpots() {
		super("Logout Spots", "Marks where players logged out. Players often log out at their base.", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		seen.clear();
		spots.clear();
		lastLevel = MC.level;
	}

	public Map<UUID, Spot> spots() {
		return Map.copyOf(spots);
	}

	@Override
	public String hudInfo() {
		return Integer.toString(spots.size());
	}

	@Override
	public void onTick() {
		if (MC.level != lastLevel) {
			lastLevel = MC.level;
			seen.clear();
			spots.clear();
		}
		if (MC.level == null || MC.player == null || MC.getConnection() == null) return;

		Set<UUID> present = new HashSet<>();
		for (Player player : MC.level.players()) {
			if (player == MC.player) continue;
			UUID id = player.getUUID();
			present.add(id);
			seen.put(id, new Seen(player.getName().getString(), player.getBoundingBox(), player.getHealth()));
			// They are back: clear their old spot.
			spots.remove(id);
		}

		var it = seen.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			if (present.contains(entry.getKey())) continue;
			it.remove();
			if (MC.getConnection().getPlayerInfo(entry.getKey()) != null) continue;

			Seen last = entry.getValue();
			Spot spot = new Spot(entry.getKey(), last.name(), last.box(), last.health(), System.currentTimeMillis());
			spots.put(entry.getKey(), spot);
			report(spot);
		}
	}

	private void report(Spot spot) {
		Vec3 c = spot.box().getCenter();
		String where = (int) Math.floor(c.x) + ", " + (int) Math.floor(spot.box().minY) + ", " + (int) Math.floor(c.z);
		if (notify.isOn()) Notifications.notice(spot.name() + " logged out", where, color.color());
		if (chat.isOn()) Finds.chat(spot.name() + " logged out at " + where);
		if (log.isOn()) Finds.log("logouts.csv", "player,x,y,z", spot.name() + "," + where.replace(" ", ""));
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
		int c = color.color();
		for (Spot spot : spots.values()) {
			batch.fill(spot.box(), ColorUtil.fade(c, 0.2f), true);
			batch.outline(spot.box(), c, 2f, true);
		}
	}

	@Override
	public void drawOverlay(Ui ui, int screenWidth, int screenHeight) {
		long now = System.currentTimeMillis();
		for (Spot spot : spots.values()) {
			Vec3 top = spot.box().getCenter().add(0, spot.box().getYsize() / 2 + 0.5, 0);
			Projection.Point p = Projection.toScreen(top);
			if (p == null) continue;
			long minutes = (now - spot.timeMillis()) / 60000;
			String label = spot.name() + "  " + (minutes == 0 ? "just now" : minutes + "m ago") + "  " + Math.round(p.distance()) + "m";
			int w = ui.width(label);
			int x = Math.round(p.x()) - w / 2;
			int y = Math.round(p.y()) - 10;
			ui.roundRect(x - 4, y - 2, x + w + 4, y + 10, 3, 0x8C08090E);
			ui.text(label, x, y, color.color());
		}
	}
}
