package dev.lumen.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import dev.lumen.client.Lumen;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.modules.ClickGuiModule;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.setting.EnumSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.setting.TextSetting;
import dev.lumen.client.setting.Setting;
import dev.lumen.client.util.ColorUtil;
import dev.lumen.client.util.KeyNames;

public final class ClickGuiScreen extends Screen {
	private static final int PANEL_W = 136;
	private static final int HEADER_H = 20;
	private static final int ROW_H = 16;
	private static final int DIM_TEXT = 0xFF9EA3B8;
	// Button numbers come from the game's constants: under SDL they are not 0, 1, 2.
	private static final int LEFT = InputConstants.MOUSE_BUTTON_LEFT;
	private static final int RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;
	private static final int MIDDLE = InputConstants.MOUSE_BUTTON_MIDDLE;
	private static final String HINT = "Type to search   Left click: toggle   Right click: settings   Middle click: bind";
	private static final int TOP_BAR_Y = 8;
	private static final int TOP_BAR_H = 18;
	private static final int SEARCH_W = 170;

	private record Key(Object owner, String part) {
	}

	private interface DragHandler {
		void drag(double mouseX, double mouseY);
	}

	private static final class Panel {
		final Category category;
		int x;
		int y;
		boolean collapsed;
		float scroll;
		int contentHeight;
		int x1, y1, x2, y2;

		Panel(Category category, int x, int y) {
			this.category = category;
			this.x = x;
			this.y = y;
		}
	}

	private static final Object OPEN_KEY = new Object();
	private static final Object SEARCH_GLOW_KEY = new Object();
	private static final Object PROFILES_HOVER_KEY = new Object();
	private static final Object WAYPOINTS_HOVER_KEY = new Object();

	// Remembered across openings so the GUI comes back the way it was left.
	private static final Set<String> EXPANDED = new HashSet<>();
	private static final Set<ColorSetting> OPEN_PICKERS = new HashSet<>();

	private final Ui ui = new Ui();
	private final List<Panel> panels = new ArrayList<>();
	private final Map<Object, Integer> measured = new HashMap<>();
	private final Map<ColorSetting, float[]> hsbCache = new HashMap<>();

	private Panel dragging;
	private double dragOffsetX;
	private double dragOffsetY;
	private DragHandler activeDrag;
	private boolean mouseDown;
	private Module listening;
	private TextSetting editingText;
	private String textBuffer = "";

	private EditBox search;
	private String query = "";
	// A key press that sets a bind is followed by a typed character; it must not reach the search box.
	private long swallowCharsUntil;
	private final List<String> visibleModules = new ArrayList<>();
	private int profilesX1, profilesY1, profilesX2, profilesY2;
	private int waypointsX1, waypointsX2;

	public ClickGuiScreen() {
		super(Component.literal("Lumen"));
		Anim.set(OPEN_KEY, 0f);
		loadState();
	}

	private static ClickGuiModule theme() {
		return Lumen.modules().clickGui;
	}

	// ---- persistence ----

	private void loadState() {
		JsonObject state = Lumen.config().guiState();
		JsonObject saved = state.has("panels") && state.get("panels").isJsonObject() ? state.getAsJsonObject("panels") : new JsonObject();

		int x = 16;
		Panel client = null;
		Panel player = null;
		boolean playerSaved = false;
		for (Category category : Category.values()) {
			Panel panel = new Panel(category, x, TOP_BAR_Y + TOP_BAR_H + 8);
			JsonElement e = saved.get(category.name());
			if (e != null && e.isJsonObject()) {
				JsonObject o = e.getAsJsonObject();
				if (o.has("x")) panel.x = o.get("x").getAsInt();
				if (o.has("y")) panel.y = o.get("y").getAsInt();
				if (o.has("collapsed")) panel.collapsed = o.get("collapsed").getAsBoolean();
				if (category == Category.PLAYER) playerSaved = true;
			}
			if (category == Category.CLIENT) client = panel;
			if (category == Category.PLAYER) player = panel;
			panels.add(panel);
			// Player sits under the short Client panel rather than in a fifth column off screen.
			if (category != Category.PLAYER) x += PANEL_W + 12;
		}
		if (player != null && client != null && !playerSaved) {
			int clientRows = Lumen.modules().byCategory(Category.CLIENT).size();
			player.x = client.x;
			player.y = client.y + HEADER_H + 2 + clientRows * ROW_H + 5 + 10;
		}

		if (EXPANDED.isEmpty() && state.has("expanded") && state.get("expanded").isJsonArray()) {
			for (JsonElement e : state.getAsJsonArray("expanded")) EXPANDED.add(e.getAsString());
		}
	}

	private void saveState() {
		JsonObject state = Lumen.config().guiState();
		JsonObject saved = new JsonObject();
		for (Panel panel : panels) {
			JsonObject o = new JsonObject();
			o.addProperty("x", panel.x);
			o.addProperty("y", panel.y);
			o.addProperty("collapsed", panel.collapsed);
			saved.add(panel.category.name(), o);
		}
		state.add("panels", saved);

		JsonArray expanded = new JsonArray();
		for (String name : EXPANDED) expanded.add(name);
		state.add("expanded", expanded);
	}

	// ---- screen lifecycle ----

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		saveState();
		Lumen.config().save();
		super.onClose();
	}

	@Override
	protected void init() {
		search = new EditBox(font, width / 2 - SEARCH_W / 2 + 20, TOP_BAR_Y + 5, SEARCH_W - 50, 10, Component.literal("Search"));
		search.setBordered(false);
		search.setMaxLength(32);
		search.setTextColor(0xFFEDEFF7);
		search.setValue(query);
		search.setResponder(value -> query = value);
		addWidget(search);
		setFocused(search);
		search.setFocused(true);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
		if (theme().blur.isOn()) {
			super.extractBackground(graphics, mouseX, mouseY, partialTicks);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
		ClickGuiModule t = theme();
		float open = Anim.approach(OPEN_KEY, 1f, 0f);

		ui.begin(graphics, mouseX, mouseY);
		ui.alpha = open;
		ui.textShadow = t.textShadow.isOn();

		if (dragging != null) {
			dragging.x = clamp((int) Math.round(mouseX - dragOffsetX), -PANEL_W + 30, width - 30);
			dragging.y = clamp((int) Math.round(mouseY - dragOffsetY), 0, height - HEADER_H);
		}
		if (activeDrag != null && mouseDown) {
			activeDrag.drag(mouseX, mouseY);
		}

		int dim = (int) Math.round(t.dim.get() / 100.0 * 255);
		ui.gradientV(0, 0, width, height, ColorUtil.argb(dim * 3 / 4, 6, 6, 12), ColorUtil.argb(dim, 6, 6, 12));

		visibleModules.clear();
		for (Panel panel : panels) {
			drawPanel(panel, open);
		}

		drawTopBar(graphics, mouseX, mouseY, partialTicks);
		drawFooter();
	}

	// ---- search and profiles ----

	private void drawTopBar(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
		ClickGuiModule t = theme();
		int r = t.cornerRadius.getInt();

		// Search box, centred.
		int sx1 = width / 2 - SEARCH_W / 2;
		int sx2 = sx1 + SEARCH_W;
		int sy1 = TOP_BAR_Y;
		int sy2 = sy1 + TOP_BAR_H;
		if (t.shadows.isOn()) ui.shadow(sx1, sy1, sx2, sy2, r, 70);
		ui.roundRect(sx1, sy1, sx2, sy2, r, t.panelColor.color());
		boolean searching = !query.isEmpty();
		float glow = Anim.approach(SEARCH_GLOW_KEY, searching ? 1f : 0.35f);
		ui.roundGradientH(sx1 + r, sy2 - 1, sx2 - r, sy2, 0,
				ColorUtil.fade(t.accentAt(0f), glow), ColorUtil.fade(t.accentAt(1f), glow), false, false);

		// Magnifier: a ring and a short handle.
		int mx = sx1 + 8;
		int my = sy1 + 5;
		int icon = ColorUtil.lerp(DIM_TEXT, t.accentAt(0.3f), glow);
		ui.roundRect(mx, my, mx + 7, my + 7, 3, icon);
		ui.roundRect(mx + 1, my + 1, mx + 6, my + 6, 2, t.panelColor.color() | 0xFF000000);
		ui.rect(mx + 6, my + 6, mx + 8, my + 8, icon);
		ui.rect(mx + 7, my + 7, mx + 9, my + 9, icon);

		if (searching) {
			String n = Integer.toString(visibleModules.size());
			ui.textRight(n, sx2 - 8, sy1 + 5, ColorUtil.fade(t.accentAt(0.6f), 0.9f));
		} else {
			ui.text("Type to search...", sx1 + 20, sy1 + 5, ColorUtil.darken(DIM_TEXT, 0.15f));
		}
		search.extractRenderState(graphics, mouseX, mouseY, partialTicks);

		// Profiles button, top right.
		String active = Lumen.profiles().active();
		String label = active.isEmpty() ? "Profiles" : "Profiles: " + active;
		profilesX2 = width - 8;
		profilesX1 = profilesX2 - ui.width(label) - 16;
		profilesY1 = TOP_BAR_Y;
		profilesY2 = TOP_BAR_Y + TOP_BAR_H;
		boolean hover = ui.hovered(profilesX1, profilesY1, profilesX2, profilesY2);
		float hov = Anim.approach(PROFILES_HOVER_KEY, hover ? 1f : 0f);
		if (t.shadows.isOn()) ui.shadow(profilesX1, profilesY1, profilesX2, profilesY2, r, 70);
		ui.roundRect(profilesX1, profilesY1, profilesX2, profilesY2, r, t.panelColor.color());
		if (hov > 0.01f) {
			ui.roundGradientH(profilesX1, profilesY1, profilesX2, profilesY2, r,
					ColorUtil.fade(t.accentAt(0f), 0.35f * hov), ColorUtil.fade(t.accentAt(1f), 0.2f * hov), true, true);
		}
		ui.roundGradientH(profilesX1 + r, profilesY2 - 1, profilesX2 - r, profilesY2, 0, t.accentAt(0f), t.accentAt(1f), false, false);
		ui.text(label, profilesX1 + 8, profilesY1 + 5, ColorUtil.lerp(DIM_TEXT, t.textColor.color(), 0.5f + 0.5f * hov));
		if (hover) ui.tooltip = "Save your whole setup under a name, or load a saved one.";
		ui.hit(profilesX1, profilesY1, profilesX2, profilesY2, (button, px, py) -> {
			if (button == LEFT) openProfiles();
		});

		// Waypoints button, to the left of Profiles.
		String wpLabel = "Waypoints";
		waypointsX2 = profilesX1 - 6;
		waypointsX1 = waypointsX2 - ui.width(wpLabel) - 16;
		boolean wpHover = ui.hovered(waypointsX1, profilesY1, waypointsX2, profilesY2);
		float wpHov = Anim.approach(WAYPOINTS_HOVER_KEY, wpHover ? 1f : 0f);
		if (t.shadows.isOn()) ui.shadow(waypointsX1, profilesY1, waypointsX2, profilesY2, r, 70);
		ui.roundRect(waypointsX1, profilesY1, waypointsX2, profilesY2, r, t.panelColor.color());
		if (wpHov > 0.01f) {
			ui.roundGradientH(waypointsX1, profilesY1, waypointsX2, profilesY2, r,
					ColorUtil.fade(t.accentAt(0f), 0.35f * wpHov), ColorUtil.fade(t.accentAt(1f), 0.2f * wpHov), true, true);
		}
		ui.roundGradientH(waypointsX1 + r, profilesY2 - 1, waypointsX2 - r, profilesY2, 0, t.accentAt(0f), t.accentAt(1f), false, false);
		ui.text(wpLabel, waypointsX1 + 8, profilesY1 + 5, ColorUtil.lerp(DIM_TEXT, t.textColor.color(), 0.5f + 0.5f * wpHov));
		if (wpHover) ui.tooltip = "Saved locations for this server and dimension.";
		ui.hit(waypointsX1, profilesY1, waypointsX2, profilesY2, (button, px, py) -> {
			if (button == LEFT) {
				saveState();
				minecraft.gui.setScreen(new WaypointsScreen(this));
			}
		});
	}

	/** Centre of the Waypoints button in GUI coordinates, from the most recent frame. */
	public int[] waypointsButtonCenter() {
		return new int[] {(waypointsX1 + waypointsX2) / 2, (profilesY1 + profilesY2) / 2};
	}

	private void openProfiles() {
		saveState();
		minecraft.gui.setScreen(new ProfilesScreen(this));
	}

	private boolean matches(Module m) {
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return true;
		if (m.name().toLowerCase(Locale.ROOT).contains(q)) return true;
		if (m.description().toLowerCase(Locale.ROOT).contains(q)) return true;
		for (Setting<?> s : m.settings()) {
			if (s.name().toLowerCase(Locale.ROOT).contains(q)) return true;
		}
		return false;
	}

	/** The current search text. */
	public String query() {
		return query;
	}

	/** Names of the modules drawn in the most recent frame, after search filtering. */
	public List<String> visibleModules() {
		return List.copyOf(visibleModules);
	}

	/** Centre of the Profiles button in GUI coordinates, from the most recent frame. */
	public int[] profilesButtonCenter() {
		return new int[] {(profilesX1 + profilesX2) / 2, (profilesY1 + profilesY2) / 2};
	}

	// ---- panels ----

	private void drawPanel(Panel p, float open) {
		ClickGuiModule t = theme();
		int r = t.cornerRadius.getInt();
		int x = p.x;
		int y = p.y + Math.round((1f - open) * 12f);
		int w = PANEL_W;

		float expand = Anim.approach(new Key(p, "collapse"), p.collapsed ? 0f : 1f);
		// Leave room for the hint bar along the bottom of the screen.
		int maxContent = Math.max(48, height - y - HEADER_H - 38);
		int visible = Math.round(Math.min(p.contentHeight, maxContent) * expand);
		int bottom = y + HEADER_H + visible + (visible > 0 ? 5 : 0);

		p.x1 = x;
		p.y1 = y;
		p.x2 = x + w;
		p.y2 = bottom;

		if (t.shadows.isOn()) ui.shadow(x, y, x + w, bottom, r, 110);
		ui.roundRect(x, y, x + w, bottom, r, t.panelColor.color());

		// Header: accent gradient with a faint gloss on the upper half.
		ui.roundGradientH(x, y, x + w, y + HEADER_H, r, t.accentAt(0f), t.accentAt(1f), true, visible <= 0);
		ui.rect(x + r, y + 1, x + w - r, y + HEADER_H / 2, 0x16FFFFFF);
		ui.text(p.category.displayName(), x + 8, y + (HEADER_H - 8) / 2, 0xFFFFFFFF);

		List<Module> all = Lumen.modules().byCategory(p.category);
		List<Module> modules = new ArrayList<>();
		for (Module m : all) {
			if (matches(m)) modules.add(m);
		}
		int enabled = 0;
		int toggleable = 0;
		for (Module m : all) {
			if (m.isToggleable()) {
				toggleable++;
				if (m.isEnabled()) enabled++;
			}
		}
		String count = toggleable > 0 ? enabled + "/" + toggleable : p.collapsed ? "+" : "-";
		ui.textRight(count, x + w - 8, y + (HEADER_H - 8) / 2, 0xC0FFFFFF);

		ui.hit(x, y, x + w, y + HEADER_H, (button, mx, my) -> {
			bringToFront(p);
			if (button == LEFT) {
				dragging = p;
				dragOffsetX = mx - p.x;
				dragOffsetY = my - p.y;
			} else if (button == RIGHT) {
				p.collapsed = !p.collapsed;
			}
		});

		int contentTop = y + HEADER_H + 2;
		p.scroll = Math.max(0, Math.min(p.scroll, Math.max(0, p.contentHeight - maxContent)));
		float scroll = Anim.approach(new Key(p, "scroll"), p.scroll);

		ui.pushClip(x, contentTop, x + w, contentTop + visible);
		int cy = contentTop - Math.round(scroll);
		int start = cy;
		for (Module m : modules) {
			visibleModules.add(m.name());
			cy += drawModule(m, x, cy, w);
		}
		if (modules.isEmpty() && !query.isEmpty()) {
			ui.text("No matches", x + 11, cy + 4, ColorUtil.darken(DIM_TEXT, 0.2f));
			cy += ROW_H;
		}
		p.contentHeight = cy - start + 1;
		ui.popClip();

		// Scrollbar when content overflows.
		if (visible > 0 && p.contentHeight > maxContent) {
			int trackH = visible;
			int barH = Math.max(12, trackH * maxContent / p.contentHeight);
			int barY = contentTop + Math.round((trackH - barH) * (scroll / Math.max(1f, p.contentHeight - maxContent)));
			ui.roundRect(x + w - 3, barY, x + w - 1, barY + barH, 1, ColorUtil.fade(t.accentAt(0.5f), 0.7f));
		}
	}

	private void bringToFront(Panel p) {
		panels.remove(p);
		panels.add(p);
	}

	// ---- modules ----

	private int drawModule(Module m, int x, int y, int w) {
		ClickGuiModule t = theme();
		int h = ROW_H;
		int x1 = x + 4;
		int x2 = x + w - 4;

		boolean hover = ui.hovered(x1, y, x2, y + h);
		float hov = Anim.approach(new Key(m, "hover"), hover ? 1f : 0f);
		float on = Anim.approach(new Key(m, "on"), m.isEnabled() ? 1f : 0f);

		if (hov > 0.01f) ui.roundRect(x1, y + 1, x2, y + h - 1, 3, ColorUtil.argb(Math.round(hov * 20), 255, 255, 255));
		if (on > 0.01f) {
			ui.roundGradientH(x1, y + 1, x2, y + h - 1, 3,
					ColorUtil.fade(t.accentAt(0f), 0.38f * on), ColorUtil.fade(t.accentAt(1f), 0.08f * on), true, true);
			int barH = Math.round((h - 6) * on);
			int by = y + (h - barH) / 2;
			ui.rect(x1, by, x1 + 2, by + barH, t.accentAt(0f));
		}

		int textColor = ColorUtil.lerp(DIM_TEXT, t.textColor.color(), Math.max(on, hov * 0.6f));
		if (!m.isToggleable()) textColor = ColorUtil.lerp(0xFFC4C8D8, t.textColor.color(), hov);
		ui.text(m.name(), x1 + 7 + Math.round(on * 2f), y + (h - 8) / 2, textColor);

		boolean isExpanded = EXPANDED.contains(m.name());
		int right = x2 - 5;
		ui.textRight(isExpanded ? "-" : "+", right, y + (h - 8) / 2, ColorUtil.argb(Math.round(110 + 110 * hov), 255, 255, 255));
		right -= 10;
		if (listening == m) {
			ui.textRight("...", right, y + (h - 8) / 2, t.accentAt(0.5f));
		} else if (!m.key().isEmpty()) {
			ui.textRight(KeyNames.pretty(m.key()), right, y + (h - 8) / 2, 0x80FFFFFF);
		}

		if (hover) ui.tooltip = m.description();

		ui.hit(x1, y, x2, y + h, (button, mx, my) -> {
			if (button == LEFT) {
				if (m.isToggleable()) {
					m.toggle();
				} else {
					toggleExpanded(m);
				}
			} else if (button == RIGHT) {
				toggleExpanded(m);
			} else if (button == MIDDLE && m.isToggleable()) {
				listening = m;
			}
		});

		int total = h;
		float ex = Anim.approach(new Key(m, "expand"), isExpanded ? 1f : 0f);
		if (ex > 0.001f) {
			int full = measured.getOrDefault(m, 0);
			int visible = Math.round(full * ex);
			ui.pushClip(x, y + h, x + w, y + h + visible);
			measured.put(m, drawSettings(m, x, y + h, w));
			ui.popClip();
			total += visible;
		}
		return total;
	}

	private void toggleExpanded(Module m) {
		if (!EXPANDED.remove(m.name())) EXPANDED.add(m.name());
	}

	// ---- settings ----

	private int drawSettings(Module m, int x, int y, int w) {
		ClickGuiModule t = theme();
		int x1 = x + 13;
		int x2 = x + w - 9;
		int cy = y + 2;

		if (m.isToggleable()) cy += drawBind(m, x1, cy, x2);

		String section = null;
		for (Setting<?> s : m.settings()) {
			if (!s.isVisible()) continue;
			if (s.section() != null && !s.section().equals(section)) {
				section = s.section();
				cy += drawSection(section, x1, cy, x2);
			}
			cy += switch (s) {
				case BoolSetting b -> drawBool(b, x1, cy, x2);
				case NumberSetting n -> drawNumber(n, x1, cy, x2);
				case EnumSetting<?> e -> drawEnum(e, x1, cy, x2);
				case ColorSetting c -> drawColor(c, x1, cy, x2);
				case TextSetting text -> drawText(text, x1, cy, x2);
				default -> 0;
			};
		}
		cy += 3;

		ui.rect(x + 8, y + 3, x + 9, cy - 3, ColorUtil.fade(t.accentAt(0.3f), 0.45f));
		return cy - y;
	}

	private int drawSection(String title, int x1, int y, int x2) {
		String label = title.toUpperCase(java.util.Locale.ROOT);
		int color = ColorUtil.fade(theme().accentAt(0.2f), 0.85f);
		ui.text(label, x1, y + 4, color);
		int lx = x1 + ui.width(label) + 4;
		ui.rect(lx, y + 8, x2, y + 9, 0x26FFFFFF);
		return 14;
	}

	private int drawBind(Module m, int x1, int y, int x2) {
		int h = 14;
		boolean hover = ui.hovered(x1 - 3, y, x2, y + h);
		ui.text("Keybind", x1, y + 3, hover ? theme().textColor.color() : DIM_TEXT);
		String value = listening == m ? "Press a key..." : KeyNames.pretty(m.key());
		ui.textRight(value, x2, y + 3, listening == m ? theme().accentAt(0.5f) : 0xB0FFFFFF);
		if (hover) ui.tooltip = "Click, then press a key. Backspace clears. Right click to unbind.";
		ui.hit(x1 - 3, y, x2, y + h, (button, mx, my) -> {
			if (button == LEFT) listening = m;
			else if (button == RIGHT) m.setKey("");
		});
		return h;
	}

	private int drawBool(BoolSetting b, int x1, int y, int x2) {
		ClickGuiModule t = theme();
		int h = 14;
		boolean hover = ui.hovered(x1 - 3, y, x2, y + h);
		ui.text(b.name(), x1, y + 3, hover ? t.textColor.color() : DIM_TEXT);

		float on = Anim.approach(new Key(b, "on"), b.isOn() ? 1f : 0f);
		int sw = 16;
		int sh = 8;
		int sx = x2 - sw;
		int sy = y + (h - sh) / 2;
		ui.roundRect(sx, sy, sx + sw, sy + sh, 4, ColorUtil.lerp(0xFF343747, t.accentAt(0.2f), on));
		int kx = sx + 1 + Math.round((sw - 8) * on);
		ui.roundRect(kx, sy + 1, kx + 6, sy + 7, 3, 0xFFFFFFFF);

		if (hover) ui.tooltip = b.description();
		ui.hit(x1 - 3, y, x2, y + h, (button, mx, my) -> {
			if (button == LEFT) b.toggle();
			else if (button == RIGHT) b.reset();
		});
		return h;
	}

	private int drawNumber(NumberSetting n, int x1, int y, int x2) {
		ClickGuiModule t = theme();
		int h = 22;
		boolean hover = ui.hovered(x1 - 3, y, x2, y + h);
		ui.text(n.name(), x1, y + 2, hover ? t.textColor.color() : DIM_TEXT);
		ui.textRight(n.display(), x2, y + 2, t.accentAt(0.4f));

		float frac = Anim.approach(new Key(n, "frac"), (float) n.fraction());
		int ty = y + 14;
		int tw = x2 - x1;
		ui.roundRect(x1, ty, x2, ty + 3, 1, 0xFF2B2E3B);
		int fill = Math.round(tw * frac);
		if (fill > 0) ui.roundGradientH(x1, ty, x1 + fill, ty + 3, 1, t.accentAt(0f), t.accentAt(frac), true, true);
		int kx = x1 + fill;
		ui.roundRect(kx - 3, ty - 2, kx + 3, ty + 5, 3, 0xFFFFFFFF);

		if (hover) ui.tooltip = n.description() + "  (right click resets)";
		ui.hit(x1 - 3, y, x2 + 3, y + h, (button, mx, my) -> {
			if (button == LEFT) {
				activeDrag = (dx, dy) -> n.setFraction((dx - x1) / (double) tw);
				activeDrag.drag(mx, my);
			} else if (button == RIGHT) {
				n.reset();
			}
		});
		return h;
	}

	private int drawText(TextSetting s, int x1, int y, int x2) {
		ClickGuiModule t = theme();
		int h = 26;
		boolean editing = editingText == s;
		boolean hover = ui.hovered(x1 - 3, y, x2, y + h);
		ui.text(s.name(), x1, y + 2, hover || editing ? t.textColor.color() : DIM_TEXT);

		int by = y + 12;
		ui.roundRect(x1, by, x2, by + 12, 3, editing ? 0xFF262A38 : 0xFF1E212C);
		if (editing) ui.outlineRect(x1, by, x2, by + 12, ColorUtil.fade(t.accentAt(0.5f), 0.8f));
		String shown = editing ? textBuffer : s.get();
		boolean placeholder = shown.isEmpty() && !editing;
		if (placeholder) shown = "Click to type";
		// Show the end of long text, where you are typing.
		while (ui.width(shown) > x2 - x1 - 10 && shown.length() > 1) shown = shown.substring(1);
		ui.text(shown, x1 + 4, by + 2, placeholder ? 0x60FFFFFF : 0xFFFFFFFF);
		if (editing && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = x1 + 4 + ui.width(shown) + 1;
			ui.rect(cx, by + 2, cx + 1, by + 10, 0xFFFFFFFF);
		}

		if (hover) ui.tooltip = s.description() + "  (Enter saves, Esc cancels, right click resets)";
		ui.hit(x1 - 3, y, x2, y + h, (button, mx, my) -> {
			if (button == LEFT) {
				editingText = s;
				textBuffer = s.get();
			} else if (button == RIGHT) {
				s.reset();
				if (editingText == s) textBuffer = s.get();
			}
		});
		return h;
	}

	private int drawEnum(EnumSetting<?> e, int x1, int y, int x2) {
		ClickGuiModule t = theme();
		int h = 14;
		boolean hover = ui.hovered(x1 - 3, y, x2, y + h);
		ui.text(e.name(), x1, y + 3, hover ? t.textColor.color() : DIM_TEXT);
		String value = e.get().toString();
		int vx = x2 - ui.width(value) - 8;
		ui.text(value, vx, y + 3, t.accentAt(0.4f));
		ui.text("<", vx - 8, y + 3, ColorUtil.argb(hover ? 200 : 90, 255, 255, 255));
		ui.textRight(">", x2, y + 3, ColorUtil.argb(hover ? 200 : 90, 255, 255, 255));

		if (hover) ui.tooltip = e.description();
		ui.hit(x1 - 3, y, x2, y + h, (button, mx, my) -> {
			if (button == LEFT) e.cycle(true);
			else if (button == RIGHT) e.cycle(false);
		});
		return h;
	}

	private int drawColor(ColorSetting c, int x1, int y, int x2) {
		ClickGuiModule t = theme();
		int h = 14;
		boolean hover = ui.hovered(x1 - 3, y, x2, y + h);
		int textX = x1;

		if (c.isToggleable()) {
			float on = Anim.approach(new Key(c, "on"), c.isEnabled() ? 1f : 0f);
			ui.roundRect(x1, y + 3, x1 + 8, y + 11, 2, 0xFF2C2F3C);
			if (on > 0.01f) {
				int inset = Math.round((1f - on) * 3f);
				ui.roundRect(x1 + 1 + inset, y + 4 + inset, x1 + 7 - inset, y + 10 - inset, 2, ColorUtil.withAlpha(c.color(), Math.round(255 * on)));
			}
			textX += 12;
		}

		int labelColor;
		if (c.isToggleable() && !c.isEnabled()) labelColor = ColorUtil.darken(DIM_TEXT, 0.25f);
		else labelColor = hover ? t.textColor.color() : DIM_TEXT;
		ui.text(c.name(), textX, y + 3, labelColor);

		// Swatch, with a checkerboard showing through translucent colours.
		int sx1 = x2 - 18;
		int sy1 = y + 3;
		ui.rect(sx1, sy1, sx1 + 9, sy1 + 4, 0xFF9A9A9A);
		ui.rect(sx1 + 9, sy1, sx1 + 18, sy1 + 4, 0xFF5E5E5E);
		ui.rect(sx1, sy1 + 4, sx1 + 9, sy1 + 8, 0xFF5E5E5E);
		ui.rect(sx1 + 9, sy1 + 4, sx1 + 18, sy1 + 8, 0xFF9A9A9A);
		ui.rect(sx1, sy1, sx1 + 18, sy1 + 8, c.color());
		ui.outlineRect(sx1 - 1, sy1 - 1, sx1 + 19, sy1 + 9, OPEN_PICKERS.contains(c) ? t.accentAt(0.5f) : 0x40FFFFFF);

		if (hover) {
			ui.tooltip = c.description() + (c.isToggleable() ? "  (click to toggle, click the swatch to edit)" : "  (click to edit, right click resets)");
		}

		ui.hit(x1 - 3, y, x2, y + h, (button, mx, my) -> {
			if (button == LEFT) {
				if (c.isToggleable()) c.setEnabled(!c.isEnabled());
				else togglePicker(c);
			} else if (button == RIGHT) {
				if (c.isToggleable()) togglePicker(c);
				else resetColor(c);
			}
		});
		ui.hit(sx1 - 2, y, x2 + 1, y + h, (button, mx, my) -> {
			if (button == LEFT) togglePicker(c);
			else if (button == RIGHT) resetColor(c);
		});

		int total = h;
		float pa = Anim.approach(new Key(c, "picker"), OPEN_PICKERS.contains(c) ? 1f : 0f);
		if (pa > 0.001f) {
			int full = measured.getOrDefault(c, 0);
			int visible = Math.round(full * pa);
			ui.pushClip(x1 - 3, y + h, x2 + 3, y + h + visible);
			measured.put(c, drawPicker(c, x1, y + h, x2));
			ui.popClip();
			total += visible;
		}
		return total;
	}

	private void togglePicker(ColorSetting c) {
		if (!OPEN_PICKERS.remove(c)) {
			OPEN_PICKERS.add(c);
			hsbCache.put(c, ColorUtil.toHsb(c.get()));
		}
	}

	private void resetColor(ColorSetting c) {
		c.reset();
		hsbCache.put(c, ColorUtil.toHsb(c.get()));
	}

	private int drawPicker(ColorSetting c, int x1, int y, int x2) {
		ClickGuiModule t = theme();
		float[] hsb = hsbCache.computeIfAbsent(c, k -> ColorUtil.toHsb(k.get()));
		// Re-sync if the colour changed elsewhere (reset, config load).
		if ((ColorUtil.hsb(hsb[0], hsb[1], hsb[2]) & 0xFFFFFF) != (c.get() & 0xFFFFFF)) {
			float[] fresh = ColorUtil.toHsb(c.get());
			if (fresh[1] > 0 && fresh[2] > 0) hsb[0] = fresh[0];
			hsb[1] = fresh[1];
			hsb[2] = fresh[2];
		}

		int w = x2 - x1;
		int top = y + 3;

		// Saturation (x) and brightness (y) square.
		int sbH = 44;
		for (int i = 0; i < w; i++) {
			float s = w == 1 ? 0 : i / (float) (w - 1);
			ui.gradientV(x1 + i, top, x1 + i + 1, top + sbH, ColorUtil.hsb(hsb[0], s, 1f), 0xFF000000);
		}
		int mx = x1 + Math.round(hsb[1] * (w - 1));
		int my = top + Math.round((1f - hsb[2]) * (sbH - 1));
		ui.outlineRect(mx - 2, my - 2, mx + 3, my + 3, 0xFFFFFFFF);
		ui.outlineRect(mx - 3, my - 3, mx + 4, my + 4, 0x80000000);
		ui.hit(x1, top, x2, top + sbH, (button, px, py) -> {
			if (button != LEFT) return;
			activeDrag = (dx, dy) -> {
				hsb[1] = clamp01((float) ((dx - x1) / Math.max(1, w - 1)));
				hsb[2] = 1f - clamp01((float) ((dy - top) / Math.max(1, sbH - 1)));
				applyHsb(c, hsb);
			};
			activeDrag.drag(px, py);
		});

		// Hue bar.
		int hy = top + sbH + 4;
		for (int i = 0; i < w; i++) {
			ui.rect(x1 + i, hy, x1 + i + 1, hy + 5, ColorUtil.hsb(i / (float) Math.max(1, w - 1), 1f, 1f));
		}
		int hx = x1 + Math.round(hsb[0] * (w - 1));
		ui.rect(hx - 1, hy - 1, hx + 2, hy + 6, 0xFFFFFFFF);
		ui.hit(x1, hy - 1, x2, hy + 6, (button, px, py) -> {
			if (button != LEFT) return;
			activeDrag = (dx, dy) -> {
				hsb[0] = clamp01((float) ((dx - x1) / Math.max(1, w - 1)));
				applyHsb(c, hsb);
			};
			activeDrag.drag(px, py);
		});

		// Alpha bar over a checkerboard.
		int ay = hy + 9;
		for (int i = 0; i < w; i += 3) {
			ui.rect(x1 + i, ay, Math.min(x2, x1 + i + 3), ay + 3, (i / 3) % 2 == 0 ? 0xFF9A9A9A : 0xFF5E5E5E);
			ui.rect(x1 + i, ay + 3, Math.min(x2, x1 + i + 3), ay + 5, (i / 3) % 2 == 0 ? 0xFF5E5E5E : 0xFF9A9A9A);
		}
		int rgb = c.get() & 0xFFFFFF;
		ui.gradientH(x1, ay, x2, ay + 5, rgb, 0xFF000000 | rgb);
		int ax = x1 + Math.round(ColorUtil.alpha(c.get()) / 255f * (w - 1));
		ui.rect(ax - 1, ay - 1, ax + 2, ay + 6, 0xFFFFFFFF);
		ui.hit(x1, ay - 1, x2, ay + 6, (button, px, py) -> {
			if (button != LEFT) return;
			activeDrag = (dx, dy) -> {
				int alpha = Math.round(clamp01((float) ((dx - x1) / Math.max(1, w - 1))) * 255);
				c.set(ColorUtil.withAlpha(c.get(), alpha));
			};
			activeDrag.drag(px, py);
		});

		// Rainbow toggle and hex readout.
		int ry = ay + 9;
		float rb = Anim.approach(new Key(c, "rainbow"), c.isRainbow() ? 1f : 0f);
		int sw = 16;
		ui.roundRect(x1, ry + 1, x1 + sw, ry + 9, 4, ColorUtil.lerp(0xFF343747, ColorUtil.rainbow(1f, 0f, 255), rb));
		int kx = x1 + 1 + Math.round((sw - 8) * rb);
		ui.roundRect(kx, ry + 2, kx + 6, ry + 8, 3, 0xFFFFFFFF);
		ui.text("Rainbow", x1 + sw + 5, ry + 1, DIM_TEXT);
		// Opaque colours show as #RRGGBB. The readout is dropped if it would touch the label.
		String hex = ColorUtil.alpha(c.get()) == 255
				? String.format("#%06X", c.get() & 0xFFFFFF)
				: ColorUtil.toHex(c.get());
		int labelEnd = x1 + sw + 5 + ui.width("Rainbow");
		if (x2 - ui.width(hex) >= labelEnd + 6) ui.textRight(hex, x2, ry + 1, 0x90FFFFFF);
		ui.hit(x1, ry, x1 + sw + 5 + ui.width("Rainbow"), ry + 10, (button, px, py) -> {
			if (button == LEFT) c.setRainbow(!c.isRainbow());
		});
		if (ui.hovered(x1, ry, x2, ry + 10)) ui.tooltip = "Cycle through every hue over time.";

		return ry + 12 - y;
	}

	private static void applyHsb(ColorSetting c, float[] hsb) {
		int rgb = ColorUtil.hsb(hsb[0], hsb[1], hsb[2]);
		c.set(ColorUtil.withAlpha(rgb, ColorUtil.alpha(c.get())));
	}

	// ---- footer ----

	private void drawFooter() {
		ClickGuiModule t = theme();
		String text;
		if (listening != null) {
			text = "Press a key to bind " + listening.name() + ". Esc cancels, Backspace clears.";
		} else if (editingText != null) {
			text = "Typing " + editingText.name() + ". Enter saves, Esc cancels.";
		} else if (ui.tooltip != null && t.descriptions.isOn()) {
			text = ui.tooltip;
		} else {
			text = HINT;
		}

		int tw = ui.width(text);
		int pw = tw + 20;
		int x1 = (width - pw) / 2;
		int y1 = height - 24;
		int r = t.cornerRadius.getInt();
		if (t.shadows.isOn()) ui.shadow(x1, y1, x1 + pw, y1 + 16, r, 70);
		ui.roundRect(x1, y1, x1 + pw, y1 + 16, r, t.panelColor.color());
		ui.roundGradientH(x1 + r, y1, x1 + pw - r, y1 + 1, 0, t.accentAt(0f), t.accentAt(1f), false, false);
		ui.text(text, x1 + 10, y1 + 4, ColorUtil.lerp(DIM_TEXT, t.textColor.color(), 0.6f));

		String brand = Lumen.NAME + " " + Lumen.version();
		ui.gradientText(brand, 8, height - 16, t.accentAt(0f), t.accentAt(1f));
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		mouseDown = true;
		if (listening != null && event.button() != MIDDLE) {
			listening = null;
		}
		// Clicking away from a text field keeps what was typed.
		if (editingText != null) {
			editingText.set(textBuffer);
			editingText = null;
		}
		ui.click(event.x(), event.y(), event.button());
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		mouseDown = false;
		dragging = null;
		activeDrag = null;
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		// Dragging is applied every frame from the cursor position in extractRenderState.
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		for (int i = panels.size() - 1; i >= 0; i--) {
			Panel p = panels.get(i);
			if (mouseX >= p.x1 && mouseX < p.x2 && mouseY >= p.y1 && mouseY < p.y2) {
				p.scroll -= (float) (verticalAmount * 16);
				return true;
			}
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (editingText != null) {
			int key = event.key();
			if (key == InputConstants.KEY_ESCAPE) {
				editingText = null;
			} else if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
				editingText.set(textBuffer);
				editingText = null;
			} else if (key == InputConstants.KEY_BACKSPACE && !textBuffer.isEmpty()) {
				textBuffer = textBuffer.substring(0, textBuffer.length() - 1);
			}
			return true;
		}
		if (listening != null) {
			int key = event.key();
			swallowCharsUntil = System.nanoTime() + 150_000_000L;
			if (key == InputConstants.KEY_ESCAPE) {
				listening = null;
				return true;
			}
			if (key == InputConstants.KEY_BACKSPACE || key == InputConstants.KEY_DELETE) {
				listening.setKey("");
			} else {
				listening.setKey(InputConstants.getKey(event).getName());
			}
			listening = null;
			return true;
		}
		// Esc clears the search first, then closes the GUI.
		if (event.key() == InputConstants.KEY_ESCAPE && !query.isEmpty()) {
			search.setValue("");
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (System.nanoTime() < swallowCharsUntil) return true;
		if (editingText != null) {
			String typed = Character.toString(event.codepoint());
			if (textBuffer.length() + typed.length() <= editingText.maxLength()) textBuffer += typed;
			return true;
		}
		return super.charTyped(event);
	}

	/** True while a text setting is being typed into; used by the game test. */
	public boolean isEditingText() {
		return editingText != null;
	}

	// ---- helpers ----

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}
}
