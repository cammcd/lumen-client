package dev.lumen.client.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;

import dev.lumen.client.Lumen;
import dev.lumen.client.gui.Anim;
import dev.lumen.client.gui.Ui;
import dev.lumen.client.mixin.MinecraftAccessor;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.modules.ClickGuiModule;
import dev.lumen.client.modules.HudModule;
import dev.lumen.client.util.ColorUtil;

public final class HudRenderer {
	private static final Ui UI = new Ui();
	private static final int ENTRY_H = 11;

	private record Entry(Module module, String name, String info, int width, float anim) {
	}

	private HudRenderer() {
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (Lumen.modules() == null || mc.player == null) return;
		HudModule hud = Lumen.modules().hud;
		if (!hud.isEnabled()) return;

		float scale = hud.scale.getFloat();
		int sw = Math.round(g.guiWidth() / scale);
		int sh = Math.round(g.guiHeight() / scale);

		g.pose().pushMatrix();
		g.pose().scale(scale, scale);

		UI.begin(g, -1, -1);
		UI.alpha = 1f;
		UI.textShadow = hud.textShadow.isOn();

		int topLeftY = 4;
		if (hud.watermark.isOn()) {
			topLeftY = drawWatermark(hud) + 4;
		}

		int bottomLeftY = sh - 4;
		if (hud.coordinates.isOn()) {
			bottomLeftY = drawCoordinates(hud, mc.player, sh) - 3;
		}

		if (hud.moduleList.isOn()) {
			drawModuleList(hud, sw, sh, topLeftY, bottomLeftY);
		}

		if (hud.notifications.isOn()) {
			drawNotifications(hud, sw, sh);
		}

		g.pose().popMatrix();
	}

	/** Colour along the HUD gradient, t from 0 to 1. */
	private static int color(HudModule hud, float t) {
		return switch (hud.colorMode.get()) {
			case ACCENT -> Lumen.modules().clickGui.accentAt(t);
			case RAINBOW -> ColorUtil.rainbow(1f, t * 0.35f, 255);
			case STATIC -> hud.customColor.color(t * 0.35f);
		};
	}

	private static int drawWatermark(HudModule hud) {
		ClickGuiModule theme = Lumen.modules().clickGui;
		String name = Lumen.NAME;
		String version = " " + Lumen.version();
		String fps = hud.watermarkFps.isOn() ? "   " + MinecraftAccessor.lumen$getFps() + " fps" : "";

		int x = 4;
		int y = 4;
		int w = UI.width(name) + UI.width(version) + UI.width(fps) + 16;
		int h = 16;
		int r = theme.cornerRadius.getInt();

		UI.roundRect(x, y, x + w, y + h, r, theme.panelColor.color());
		UI.roundGradientH(x + r, y, x + w - r, y + 1, 0, color(hud, 0f), color(hud, 1f), false, false);

		int tx = x + 8;
		UI.gradientText(name, tx, y + 4, color(hud, 0f), color(hud, 1f));
		tx += UI.width(name);
		UI.text(version, tx, y + 4, 0xFF8C91A6);
		tx += UI.width(version);
		if (!fps.isEmpty()) UI.text(fps, tx, y + 4, 0xFFC7CBDA);

		return y + h;
	}

	private static int drawCoordinates(HudModule hud, LocalPlayer player, int sh) {
		Minecraft mc = Minecraft.getInstance();
		int x = (int) Math.floor(player.getX());
		int y = (int) Math.floor(player.getY());
		int z = (int) Math.floor(player.getZ());

		String label = "XYZ ";
		String main = x + " " + y + " " + z;
		String other = "";
		if (mc.level != null) {
			if (mc.level.dimension() == Level.NETHER) {
				other = "  Overworld " + (x * 8) + " " + (z * 8);
			} else if (mc.level.dimension() == Level.OVERWORLD) {
				other = "  Nether " + Math.floorDiv(x, 8) + " " + Math.floorDiv(z, 8);
			}
		}

		int ty = sh - 12;
		int tx = 4;
		UI.text(label, tx, ty, color(hud, 0f));
		tx += UI.width(label);
		UI.text(main, tx, ty, 0xFFEDEFF7);
		tx += UI.width(main);
		if (!other.isEmpty()) UI.text(other, tx, ty, 0xFF8C91A6);
		return ty;
	}

	private static void drawModuleList(HudModule hud, int sw, int sh, int topLeftY, int bottomLeftY) {
		List<Entry> entries = new ArrayList<>();
		for (Module m : Lumen.modules().all()) {
			if (!m.isToggleable() || m.category() == Category.CLIENT) continue;
			float a = Anim.approach(new AnimKey(m), m.isEnabled() ? 1f : 0f, 0f);
			if (a < 0.01f) continue;
			String info = hud.listInfo.isOn() ? m.hudInfo() : null;
			int width = UI.width(m.name()) + (info != null ? UI.width(" " + info) : 0);
			entries.add(new Entry(m, m.name(), info, width, a));
		}
		if (entries.isEmpty()) return;

		if (hud.listSort.is(HudModule.Sort.LENGTH)) {
			entries.sort(Comparator.comparingInt(Entry::width).reversed());
		} else {
			entries.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
		}

		HudModule.Corner corner = hud.listCorner.get();
		boolean right = corner.right();
		boolean bottom = corner.bottom();

		float totalH = 0;
		for (Entry e : entries) totalH += ENTRY_H * e.anim();

		float y;
		if (!bottom) {
			y = right ? 2 : topLeftY;
		} else {
			y = (right ? sh - 2 : bottomLeftY) - totalH;
		}

		int bgAlpha = Math.round(hud.listBackgroundOpacity.getFloat() / 100f * 255);
		int count = entries.size();
		for (int i = 0; i < count; i++) {
			Entry e = entries.get(i);
			float t = count == 1 ? 0f : i / (float) (count - 1);
			int col = color(hud, t);
			int w = e.width() + 8;
			int slide = Math.round((1f - e.anim()) * (w + 6));
			int iy = Math.round(y);

			int x1;
			int x2;
			if (right) {
				x2 = sw - 2 + slide;
				x1 = x2 - w;
			} else {
				x1 = 2 - slide;
				x2 = x1 + w;
			}

			int entryAlpha = Math.round(255 * e.anim());
			if (hud.listBackground.isOn()) {
				UI.rect(x1, iy, x2, iy + ENTRY_H, ColorUtil.argb(Math.round(bgAlpha * e.anim()), 8, 9, 14));
			}
			if (hud.listBar.isOn()) {
				if (right) UI.rect(x2, iy, x2 + 2, iy + ENTRY_H, ColorUtil.withAlpha(col, entryAlpha));
				else UI.rect(x1 - 2, iy, x1, iy + ENTRY_H, ColorUtil.withAlpha(col, entryAlpha));
			}

			int tx = x1 + 4;
			UI.text(e.name(), tx, iy + 2, ColorUtil.withAlpha(col, entryAlpha));
			if (e.info() != null) {
				UI.text(" " + e.info(), tx + UI.width(e.name()), iy + 2, ColorUtil.argb(entryAlpha, 150, 155, 172));
			}

			y += ENTRY_H * e.anim();
		}
	}

	private static void drawNotifications(HudModule hud, int sw, int sh) {
		List<Notifications.Entry> entries = Notifications.entries();
		if (entries.isEmpty()) return;

		ClickGuiModule theme = Lumen.modules().clickGui;
		// Stay clear of the module list when it sits in the bottom-right corner.
		boolean top = hud.moduleList.isOn() && hud.listCorner.is(HudModule.Corner.BOTTOM_RIGHT);
		int r = theme.cornerRadius.getInt();
		int h = 26;
		float y = top ? 4 : sh - 4 - h;

		for (int i = entries.size() - 1; i >= 0; i--) {
			Notifications.Entry n = entries.get(i);
			float age = n.ageSeconds();
			float show = n.showSeconds();
			float a;
			if (age < 0.2f) a = ease(age / 0.2f);
			else if (age > show) a = 1f - ease((age - show) / Notifications.FADE_SECONDS);
			else a = 1f;
			a = Math.max(0f, Math.min(1f, a));

			String state = n.detail();
			int stateColor = n.color();
			int w = Math.max(UI.width(n.title()), UI.width(state)) + 22;
			int slide = Math.round((1f - a) * (w + 8));
			int x2 = sw - 4 + slide;
			int x1 = x2 - w;
			int iy = Math.round(y);

			float saved = UI.alpha;
			UI.alpha = a;
			UI.roundRect(x1, iy, x2, iy + h, r, theme.panelColor.color());
			UI.roundRect(x1 + 4, iy + 5, x1 + 6, iy + h - 5, 1, stateColor);
			UI.text(n.title(), x1 + 11, iy + 5, 0xFFEDEFF7);
			UI.text(state, x1 + 11, iy + 15, stateColor);

			// Progress line showing how long the notification stays.
			float life = Math.max(0f, Math.min(1f, 1f - age / show));
			int lw = Math.round((w - 2 * r) * life);
			if (lw > 0) UI.rect(x1 + r, iy + h - 1, x1 + r + lw, iy + h, ColorUtil.fade(color(hud, 0.5f), 0.8f));
			UI.alpha = saved;

			float step = (h + 4) * a;
			y += top ? step : -step;
		}
	}

	private static float ease(float t) {
		t = Math.max(0f, Math.min(1f, t));
		return 1f - (1f - t) * (1f - t) * (1f - t);
	}

	private record AnimKey(Module module) {
	}
}
