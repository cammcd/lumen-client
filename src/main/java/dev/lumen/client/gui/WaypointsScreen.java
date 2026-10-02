package dev.lumen.client.gui;

import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.Lumen;
import dev.lumen.client.modules.ClickGuiModule;
import dev.lumen.client.modules.Waypoints;
import dev.lumen.client.util.ColorUtil;
import dev.lumen.client.util.Finds;

/** Lists waypoints for this server and dimension, and adds new ones where you stand. */
public final class WaypointsScreen extends Screen {
	private static final int W = 280;
	private static final int HEADER_H = 20;
	private static final int ROW_H = 20;
	private static final int MAX_ROWS = 9;
	private static final int DIM_TEXT = 0xFF9EA3B8;
	private static final int BAD = 0xFFFF6B7A;
	private static final Object OPEN_KEY = new Object();

	private final Screen parent;
	private final Ui ui = new Ui();
	private EditBox name;
	private String typed = "";
	private Waypoints.Waypoint confirmDelete;
	private long confirmUntil;
	private int scroll;
	private int x1, y1, x2;

	public WaypointsScreen(Screen parent) {
		super(Component.literal("Lumen Waypoints"));
		this.parent = parent;
		Anim.set(OPEN_KEY, 0f);
	}

	private static ClickGuiModule theme() {
		return Lumen.modules().clickGui;
	}

	private int panelHeight() {
		return HEADER_H + 8 + 18 + 10 + MAX_ROWS * ROW_H + 24;
	}

	@Override
	protected void init() {
		x1 = (width - W) / 2;
		x2 = x1 + W;
		y1 = Math.max(10, (height - panelHeight()) / 2);
		name = new EditBox(font, x1 + 14, y1 + HEADER_H + 13, W - 110, 10, Component.literal("Waypoint name"));
		name.setBordered(false);
		name.setMaxLength(32);
		name.setTextColor(0xFFEDEFF7);
		name.setValue(typed);
		name.setResponder(value -> typed = value);
		addWidget(name);
		setFocused(name);
		name.setFocused(true);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
		if (theme().blur.isOn()) super.extractBackground(graphics, mouseX, mouseY, partialTicks);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
		ClickGuiModule t = theme();
		float open = Anim.approach(OPEN_KEY, 1f, 0f);
		ui.begin(graphics, mouseX, mouseY);
		ui.alpha = open;
		ui.textShadow = t.textShadow.isOn();

		int dim = (int) Math.round(t.dim.get() / 100.0 * 255);
		ui.gradientV(0, 0, width, height, ColorUtil.argb(dim * 3 / 4, 6, 6, 12), ColorUtil.argb(dim, 6, 6, 12));

		int r = t.cornerRadius.getInt();
		int y = y1 + Math.round((1f - open) * 12f);
		int bottom = y + panelHeight();
		if (t.shadows.isOn()) ui.shadow(x1, y, x2, bottom, r, 110);
		ui.roundRect(x1, y, x2, bottom, r, t.panelColor.color());
		ui.roundGradientH(x1, y, x2, y + HEADER_H, r, t.accentAt(0f), t.accentAt(1f), true, false);
		ui.rect(x1 + r, y + 1, x2 - r, y + HEADER_H / 2, 0x16FFFFFF);
		ui.text("Waypoints", x1 + 8, y + 6, 0xFFFFFFFF);
		ui.textRight(Finds.dimension(), x2 - 8, y + 6, 0xC0FFFFFF);

		Waypoints module = Lumen.modules().waypoints;
		int fy = y + HEADER_H + 8;
		ui.roundRect(x1 + 8, fy, x2 - 88, fy + 18, 3, 0xFF1C1F2B);
		ui.roundGradientH(x1 + 11, fy + 17, x2 - 91, fy + 18, 0, t.accentAt(0f), t.accentAt(1f), false, false);
		if (typed.isEmpty()) ui.text("Name (optional)...", x1 + 14, fy + 5, ColorUtil.darken(DIM_TEXT, 0.15f));
		name.extractRenderState(graphics, mouseX, mouseY, partialTicks);
		button(x2 - 80, fy, x2 - 8, fy + 18, "Add here", t.accentAt(0.3f), this::addHere);

		int ly = fy + 28;
		List<Waypoints.Waypoint> list = module.here();
		int maxScroll = Math.max(0, list.size() - MAX_ROWS);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		if (list.isEmpty()) {
			ui.textCentered("No waypoints in this dimension yet.", width / 2, ly + 30, DIM_TEXT);
			ui.textCentered("Press N in game, or Add here.", width / 2, ly + 42, ColorUtil.darken(DIM_TEXT, 0.2f));
		}

		Vec3 me = minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
		for (int i = 0; i < Math.min(MAX_ROWS, list.size() - scroll); i++) {
			Waypoints.Waypoint w = list.get(i + scroll);
			int ry = ly + i * ROW_H;
			boolean hover = ui.hovered(x1 + 6, ry, x2 - 6, ry + ROW_H - 2);
			if (hover) ui.roundRect(x1 + 6, ry, x2 - 6, ry + ROW_H - 2, 3, 0x14FFFFFF);
			ui.roundRect(x1 + 10, ry + 5, x1 + 13, ry + 13, 1, w.visible ? w.color : ColorUtil.fade(w.color, 0.3f));
			ui.text(w.name, x1 + 18, ry + 5, w.visible ? t.textColor.color() : DIM_TEXT);
			double dist = w.center().distanceTo(me);
			String info = w.x + ", " + w.y + ", " + w.z + "   " + (dist >= 1000 ? String.format(Locale.ROOT, "%.1fkm", dist / 1000) : Math.round(dist) + "m");
			ui.text(info, x1 + 18 + Math.max(70, ui.width(w.name) + 8), ry + 5, ColorUtil.darken(DIM_TEXT, 0.1f));

			boolean confirming = w == confirmDelete && System.currentTimeMillis() < confirmUntil;
			button(x2 - 54, ry + 1, x2 - 10, ry + ROW_H - 3, confirming ? "Sure?" : "Delete", BAD, () -> delete(w));
			button(x2 - 98, ry + 1, x2 - 58, ry + ROW_H - 3, w.visible ? "Hide" : "Show", t.accentAt(0.3f),
					() -> module.setVisible(w, !w.visible));
		}

		int sy = ly + MAX_ROWS * ROW_H + 6;
		ui.rect(x1 + 8, sy - 3, x2 - 8, sy - 2, 0x1AFFFFFF);
		ui.textCentered("Enter adds a waypoint here    Esc goes back", width / 2, sy + 4, ColorUtil.darken(DIM_TEXT, 0.25f));
	}

	private void button(int bx1, int by1, int bx2, int by2, String label, int color, Runnable action) {
		boolean hover = ui.hovered(bx1, by1, bx2, by2);
		ui.roundRect(bx1, by1, bx2, by2, 3, ColorUtil.fade(color, hover ? 0.45f : 0.22f));
		ui.textCentered(label, (bx1 + bx2) / 2, by1 + (by2 - by1 - 8) / 2, hover ? 0xFFFFFFFF : ColorUtil.brighten(color, 0.4f));
		ui.hit(bx1, by1, bx2, by2, (button, mx, my) -> {
			if (button == InputConstants.MOUSE_BUTTON_LEFT) action.run();
		});
	}

	private void addHere() {
		Lumen.modules().waypoints.addHere(typed);
		name.setValue("");
	}

	private void delete(Waypoints.Waypoint w) {
		if (w == confirmDelete && System.currentTimeMillis() < confirmUntil) {
			confirmDelete = null;
			Lumen.modules().waypoints.remove(w);
		} else {
			confirmDelete = w;
			confirmUntil = System.currentTimeMillis() + 3000;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		ui.click(event.x(), event.y(), event.button());
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll -= (int) Math.signum(verticalAmount);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN) {
			addHere();
			return true;
		}
		return super.keyPressed(event);
	}
}
