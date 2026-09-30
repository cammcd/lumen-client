package dev.lumen.client.gui;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import dev.lumen.client.Lumen;
import dev.lumen.client.config.ProfileManager;
import dev.lumen.client.modules.ClickGuiModule;
import dev.lumen.client.util.ColorUtil;

/** Save the whole setup under a name, and load or delete saved setups. */
public final class ProfilesScreen extends Screen {
	private static final int W = 250;
	private static final int HEADER_H = 20;
	private static final int ROW_H = 20;
	private static final int MAX_ROWS = 8;
	private static final int DIM_TEXT = 0xFF9EA3B8;
	private static final int GOOD = 0xFF6CF0A0;
	private static final int BAD = 0xFFFF6B7A;
	private static final Object OPEN_KEY = new Object();

	private final Screen parent;
	private final Ui ui = new Ui();
	private EditBox name;
	private String typed = "";

	private String status = "";
	private int statusColor = DIM_TEXT;
	private String confirmDelete;
	private long confirmUntil;
	private int scroll;

	private int x1, y1, x2;

	public ProfilesScreen(Screen parent) {
		super(Component.literal("Lumen Profiles"));
		this.parent = parent;
		Anim.set(OPEN_KEY, 0f);
	}

	private static ClickGuiModule theme() {
		return Lumen.modules().clickGui;
	}

	private int panelHeight() {
		return HEADER_H + 8 + 18 + 10 + MAX_ROWS * ROW_H + 36;
	}

	@Override
	protected void init() {
		x1 = (width - W) / 2;
		x2 = x1 + W;
		y1 = Math.max(10, (height - panelHeight()) / 2);

		name = new EditBox(font, x1 + 14, y1 + HEADER_H + 13, W - 96, 10, Component.literal("Profile name"));
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
		ui.text("Profiles", x1 + 8, y + 6, 0xFFFFFFFF);
		String active = Lumen.profiles().active();
		if (!active.isEmpty()) ui.textRight("Active: " + active, x2 - 8, y + 6, 0xC0FFFFFF);

		// Name field and Save button.
		int fy = y + HEADER_H + 8;
		ui.roundRect(x1 + 8, fy, x2 - 72, fy + 18, 3, 0xFF1C1F2B);
		ui.roundGradientH(x1 + 11, fy + 17, x2 - 75, fy + 18, 0, t.accentAt(0f), t.accentAt(1f), false, false);
		if (typed.isEmpty()) ui.text("Name this setup...", x1 + 14, fy + 5, ColorUtil.darken(DIM_TEXT, 0.15f));
		name.extractRenderState(graphics, mouseX, mouseY, partialTicks);
		button(x2 - 64, fy, x2 - 8, fy + 18, "Save", t.accentAt(0.3f), this::save);

		// Saved profiles.
		int ly = fy + 28;
		List<String> names = Lumen.profiles().list();
		int maxScroll = Math.max(0, names.size() - MAX_ROWS);
		scroll = Math.max(0, Math.min(scroll, maxScroll));

		if (names.isEmpty()) {
			ui.textCentered("No saved profiles yet.", width / 2, ly + 30, DIM_TEXT);
			ui.textCentered("Type a name above and press Save.", width / 2, ly + 42, ColorUtil.darken(DIM_TEXT, 0.2f));
		}

		for (int i = 0; i < Math.min(MAX_ROWS, names.size() - scroll); i++) {
			String n = names.get(i + scroll);
			int ry = ly + i * ROW_H;
			boolean isActive = n.equals(active);
			boolean hover = ui.hovered(x1 + 6, ry, x2 - 6, ry + ROW_H - 2);
			if (hover) ui.roundRect(x1 + 6, ry, x2 - 6, ry + ROW_H - 2, 3, 0x14FFFFFF);
			if (isActive) ui.roundRect(x1 + 11, ry + 7, x1 + 15, ry + 11, 2, t.accentAt(0f));
			ui.text(n, x1 + 20, ry + 5, isActive ? t.textColor.color() : ColorUtil.lerp(DIM_TEXT, t.textColor.color(), hover ? 0.6f : 0f));

			boolean confirming = n.equals(confirmDelete) && System.currentTimeMillis() < confirmUntil;
			button(x2 - 58, ry + 1, x2 - 10, ry + ROW_H - 3, confirming ? "Sure?" : "Delete", BAD, () -> delete(n));
			button(x2 - 106, ry + 1, x2 - 62, ry + ROW_H - 3, "Load", t.accentAt(0.3f), () -> load(n));
		}

		if (maxScroll > 0) {
			int trackH = MAX_ROWS * ROW_H;
			int barH = Math.max(12, trackH * MAX_ROWS / names.size());
			int barY = ly + Math.round((trackH - barH) * (scroll / (float) maxScroll));
			ui.roundRect(x2 - 4, barY, x2 - 2, barY + barH, 1, ColorUtil.fade(t.accentAt(0.5f), 0.7f));
		}

		// Status line and hint.
		int sy = ly + MAX_ROWS * ROW_H + 8;
		ui.rect(x1 + 8, sy - 4, x2 - 8, sy - 3, 0x1AFFFFFF);
		// Status on its own line; the key hint sits underneath so the two never overlap.
		if (!status.isEmpty()) ui.text(status, x1 + 10, sy + 1, statusColor);
		ui.textCentered("Enter saves    Esc goes back", width / 2, sy + 14, ColorUtil.darken(DIM_TEXT, 0.25f));
	}

	private void button(int bx1, int by1, int bx2, int by2, String label, int color, Runnable action) {
		boolean hover = ui.hovered(bx1, by1, bx2, by2);
		ui.roundRect(bx1, by1, bx2, by2, 3, ColorUtil.fade(color, hover ? 0.45f : 0.22f));
		ui.textCentered(label, (bx1 + bx2) / 2, by1 + (by2 - by1 - 8) / 2, hover ? 0xFFFFFFFF : ColorUtil.brighten(color, 0.4f));
		ui.hit(bx1, by1, bx2, by2, (button, mx, my) -> {
			if (button == InputConstants.MOUSE_BUTTON_LEFT) action.run();
		});
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	private void save() {
		String n = typed.trim();
		if (!ProfileManager.isValidName(n)) {
			setStatus("Use 1 to 32 letters, numbers, spaces, - or _", BAD);
			return;
		}
		boolean replaced = Lumen.profiles().exists(n);
		if (Lumen.profiles().save(n)) {
			setStatus((replaced ? "Updated " : "Saved ") + n, GOOD);
			name.setValue("");
		} else {
			setStatus("Could not save " + n, BAD);
		}
	}

	private void load(String n) {
		if (Lumen.profiles().load(n)) setStatus("Loaded " + n, GOOD);
		else setStatus("Could not load " + n, BAD);
	}

	private void delete(String n) {
		if (n.equals(confirmDelete) && System.currentTimeMillis() < confirmUntil) {
			confirmDelete = null;
			if (Lumen.profiles().delete(n)) setStatus("Deleted " + n, GOOD);
			else setStatus("Could not delete " + n, BAD);
		} else {
			confirmDelete = n;
			confirmUntil = System.currentTimeMillis() + 3000;
			setStatus("Click Sure? to delete " + n, DIM_TEXT);
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
			save();
			return true;
		}
		return super.keyPressed(event);
	}
}
