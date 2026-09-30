package dev.lumen.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import dev.lumen.client.util.ColorUtil;

/**
 * Immediate-mode drawing helpers. Every colour passes through {@link #c} so the whole
 * GUI can fade in, and clickable regions are recorded while drawing for hit testing.
 */
public final class Ui {
	public interface ClickHandler {
		void click(int button, double mouseX, double mouseY);
	}

	private record Hit(int x1, int y1, int x2, int y2, ClickHandler handler) {
	}

	private record Clip(int x1, int y1, int x2, int y2) {
	}

	public GuiGraphicsExtractor g;
	public int mouseX;
	public int mouseY;
	public float alpha = 1f;
	public boolean textShadow;
	public String tooltip;

	private final List<Hit> hits = new ArrayList<>();
	private final List<Hit> lastHits = new ArrayList<>();
	private final List<Clip> clips = new ArrayList<>();

	public Font font() {
		return Minecraft.getInstance().font;
	}

	public void begin(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		this.g = g;
		this.mouseX = mouseX;
		this.mouseY = mouseY;
		this.tooltip = null;
		lastHits.clear();
		lastHits.addAll(hits);
		hits.clear();
		clips.clear();
	}

	/** Applies the global fade to a colour. */
	public int c(int argb) {
		return alpha >= 1f ? argb : ColorUtil.fade(argb, alpha);
	}

	// ---- clipping ----

	public void pushClip(int x1, int y1, int x2, int y2) {
		if (!clips.isEmpty()) {
			Clip top = clips.getLast();
			x1 = Math.max(x1, top.x1());
			y1 = Math.max(y1, top.y1());
			x2 = Math.min(x2, top.x2());
			y2 = Math.min(y2, top.y2());
		}
		if (x2 < x1) x2 = x1;
		if (y2 < y1) y2 = y1;
		clips.add(new Clip(x1, y1, x2, y2));
		g.enableScissor(x1, y1, x2, y2);
	}

	// The game keeps its own scissor stack, so disabling restores the enclosing clip.
	public void popClip() {
		clips.removeLast();
		g.disableScissor();
	}

	private boolean insideClip(double x, double y) {
		if (clips.isEmpty()) return true;
		Clip top = clips.getLast();
		return x >= top.x1() && x < top.x2() && y >= top.y1() && y < top.y2();
	}

	// ---- hit testing ----

	public boolean hovered(int x1, int y1, int x2, int y2) {
		return mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2 && insideClip(mouseX, mouseY);
	}

	/** Registers a clickable region for this frame, limited to the current clip. */
	public void hit(int x1, int y1, int x2, int y2, ClickHandler handler) {
		if (!clips.isEmpty()) {
			Clip top = clips.getLast();
			x1 = Math.max(x1, top.x1());
			y1 = Math.max(y1, top.y1());
			x2 = Math.min(x2, top.x2());
			y2 = Math.min(y2, top.y2());
			if (x2 <= x1 || y2 <= y1) return;
		}
		hits.add(new Hit(x1, y1, x2, y2, handler));
	}

	/** Dispatches a click to the topmost region drawn last frame. */
	public boolean click(double mx, double my, int button) {
		List<Hit> source = hits.isEmpty() ? lastHits : hits;
		for (int i = source.size() - 1; i >= 0; i--) {
			Hit h = source.get(i);
			if (mx >= h.x1() && mx < h.x2() && my >= h.y1() && my < h.y2()) {
				h.handler().click(button, mx, my);
				return true;
			}
		}
		return false;
	}

	// ---- shapes ----

	public void rect(int x1, int y1, int x2, int y2, int color) {
		if (x2 <= x1 || y2 <= y1 || (color >>> 24) == 0) return;
		g.fill(x1, y1, x2, y2, c(color));
	}

	public void gradientV(int x1, int y1, int x2, int y2, int top, int bottom) {
		if (x2 <= x1 || y2 <= y1) return;
		g.fillGradient(x1, y1, x2, y2, c(top), c(bottom));
	}

	/** Horizontal gradient, drawn as one-unit columns. */
	public void gradientH(int x1, int y1, int x2, int y2, int left, int right) {
		int w = x2 - x1;
		if (w <= 0 || y2 <= y1) return;
		for (int i = 0; i < w; i++) {
			float t = w == 1 ? 0 : i / (float) (w - 1);
			g.fill(x1 + i, y1, x1 + i + 1, y2, c(ColorUtil.lerp(left, right, t)));
		}
	}

	private static int cornerInset(int r, int row) {
		double d = r - row - 0.5;
		return (int) Math.round(r - Math.sqrt(Math.max(0, r * r - d * d)));
	}

	/** Rounded rectangle; roundTop and roundBottom choose which corners are rounded. */
	public void roundRect(int x1, int y1, int x2, int y2, int r, int color, boolean roundTop, boolean roundBottom) {
		if (x2 <= x1 || y2 <= y1 || (color >>> 24) == 0) return;
		r = Math.min(r, Math.min((x2 - x1) / 2, (y2 - y1) / 2));
		if (r <= 0) {
			rect(x1, y1, x2, y2, color);
			return;
		}
		int top = roundTop ? r : 0;
		int bottom = roundBottom ? r : 0;
		rect(x1, y1 + top, x2, y2 - bottom, color);
		for (int i = 0; i < top; i++) {
			int inset = cornerInset(r, i);
			rect(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, color);
		}
		for (int i = 0; i < bottom; i++) {
			int inset = cornerInset(r, i);
			rect(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, color);
		}
	}

	public void roundRect(int x1, int y1, int x2, int y2, int r, int color) {
		roundRect(x1, y1, x2, y2, r, color, true, true);
	}

	/** Rounded rectangle filled with a left-to-right gradient. */
	public void roundGradientH(int x1, int y1, int x2, int y2, int r, int left, int right, boolean roundTop, boolean roundBottom) {
		int w = x2 - x1;
		int h = y2 - y1;
		if (w <= 0 || h <= 0) return;
		r = Math.min(r, Math.min(w / 2, h / 2));
		for (int i = 0; i < w; i++) {
			int insetTop = 0;
			int insetBottom = 0;
			int fromEdge = Math.min(i, w - 1 - i);
			if (r > 0 && fromEdge < r) {
				double d = r - fromEdge - 0.5;
				int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - d * d)));
				if (roundTop) insetTop = inset;
				if (roundBottom) insetBottom = inset;
			}
			float t = w == 1 ? 0 : i / (float) (w - 1);
			rect(x1 + i, y1 + insetTop, x1 + i + 1, y2 - insetBottom, ColorUtil.lerp(left, right, t));
		}
	}

	/** Soft drop shadow made of expanding translucent layers. */
	public void shadow(int x1, int y1, int x2, int y2, int r, int strength) {
		for (int i = 1; i <= 5; i++) {
			int a = Math.max(0, strength - i * (strength / 6));
			roundRect(x1 - i, y1 - i + 2, x2 + i, y2 + i + 2, r + i, ColorUtil.argb(a / 3, 0, 0, 0));
		}
	}

	public void outlineRect(int x1, int y1, int x2, int y2, int color) {
		rect(x1, y1, x2, y1 + 1, color);
		rect(x1, y2 - 1, x2, y2, color);
		rect(x1, y1 + 1, x1 + 1, y2 - 1, color);
		rect(x2 - 1, y1 + 1, x2, y2 - 1, color);
	}

	// ---- text ----

	public int width(String s) {
		return font().width(s);
	}

	public void text(String s, int x, int y, int color) {
		if ((color >>> 24) == 0) return;
		g.text(font(), s, x, y, c(color), textShadow);
	}

	public void textCentered(String s, int cx, int y, int color) {
		text(s, cx - width(s) / 2, y, color);
	}

	public void textRight(String s, int right, int y, int color) {
		text(s, right - width(s), y, color);
	}

	/** Text with a colour gradient running across its characters. */
	public void gradientText(String s, int x, int y, int from, int to) {
		int total = Math.max(1, width(s));
		int cx = x;
		for (int i = 0; i < s.length(); i++) {
			String ch = String.valueOf(s.charAt(i));
			float t = (cx - x + width(ch) / 2f) / total;
			text(ch, cx, y, ColorUtil.lerp(from, to, t));
			cx += width(ch);
		}
	}
}
