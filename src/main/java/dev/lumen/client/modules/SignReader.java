package dev.lumen.client.modules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import dev.lumen.client.gui.Ui;
import dev.lumen.client.hud.HudOverlay;
import dev.lumen.client.hud.Notifications;
import dev.lumen.client.render.EspBatch;
import dev.lumen.client.render.Projection;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ColorUtil;
import dev.lumen.client.util.Finds;

/**
 * Reads both sides of every loaded sign, shows the text over it and flags signs that
 * look like they hold coordinates. Only reads what the server already sent.
 */
public final class SignReader extends WorldScanModule implements HudOverlay {
	/** A sign with readable text. {@code coords} is null unless the text looks like a location. */
	public record Sign(BlockPos pos, AABB box, List<String> front, List<String> back, String coords) {
		public boolean hasCoords() {
			return coords != null;
		}

		public String text() {
			List<String> all = new ArrayList<>(front);
			all.addAll(back);
			return String.join(" / ", all);
		}
	}

	// "x: 1200 z: -3400", "X1200 Y64 Z-3400"
	private static final Pattern LABELLED = Pattern.compile(
			"(?i)\\bx\\s*[:=]?\\s*(-?\\d{1,8})\\b.*?(?:\\by\\s*[:=]?\\s*(-?\\d{1,4})\\b.*?)?\\bz\\s*[:=]?\\s*(-?\\d{1,8})\\b");
	// "1200 64 -3400", "1200, 64, -3400"
	private static final Pattern TRIPLE = Pattern.compile(
			"(?<![\\w.])(-?\\d{1,8})[\\s,/;]+(-?\\d{1,4})[\\s,/;]+(-?\\d{1,8})(?![\\w.])");
	// "1200 -3400", "1200, -3400"
	private static final Pattern PAIR = Pattern.compile(
			"(?<![\\w.])(-?\\d{2,8})[\\s,/;]+(-?\\d{2,8})(?![\\w.])");

	private final BoolSetting showText = add(new BoolSetting("Show text", "Draw sign text above each sign, through walls.", true));
	private final NumberSetting textRange = add(new NumberSetting("Text range", "Maximum distance for text labels, in blocks.", 24, 4, 96, 2, "m"))
			.visibleWhen(showText::isOn);
	private final NumberSetting textScale = add(new NumberSetting("Text scale", "Size of the labels.", 1.0, 0.5, 2.0, 0.05, "x"))
			.visibleWhen(showText::isOn);
	private final BoolSetting highlight = add(new BoolSetting("Highlight", "Outline signs in the world.", true));
	private final NumberSetting range = add(new NumberSetting("Highlight range", "Maximum distance for outlines, in blocks.", 96, 16, 256, 8, "m"))
			.visibleWhen(highlight::isOn);
	private final BoolSetting onlyCoords = add(new BoolSetting("Only coordinates", "Only show signs that look like they hold coordinates.", false));
	private final ColorSetting signColor = add(new ColorSetting("Sign color", "Colour of ordinary signs.", 0xFFE8D7A8));
	private final ColorSetting coordsColor = add(new ColorSetting("Coordinates color", "Colour of signs with coordinates.", 0xFFFF8A3D));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Pop-up when a sign with coordinates is found.", true));
	private final BoolSetting chat = add(new BoolSetting("Chat message", "Client-side chat line with the sign text. Nothing is sent to the server.", true));
	private final BoolSetting log = add(new BoolSetting("Log to file", "Append signs with coordinates to config/lumen/signs.csv.", true));
	private final BoolSetting logAll = add(new BoolSetting("Log all signs", "Also log signs without coordinates.", false))
			.visibleWhen(log::isOn);

	private final Map<ChunkPos, List<Sign>> signs = new HashMap<>();
	private final Set<String> reported = new HashSet<>();

	public SignReader() {
		super("Sign Reader", "Shows sign text through walls and flags signs with coordinates.");
	}

	@Override
	protected void reset() {
		signs.clear();
		reported.clear();
	}

	public List<Sign> signs() {
		List<Sign> all = new ArrayList<>();
		for (List<Sign> list : signs.values()) all.addAll(list);
		return all;
	}

	@Override
	public String hudInfo() {
		int total = 0, coords = 0;
		for (List<Sign> list : signs.values()) {
			for (Sign sign : list) {
				total++;
				if (sign.hasCoords()) coords++;
			}
		}
		return coords > 0 ? total + " (" + coords + " coords)" : Integer.toString(total);
	}

	@Override
	protected void scan(ClientLevel level, LevelChunk chunk) {
		List<Sign> found = new ArrayList<>();
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			if (!(be instanceof SignBlockEntity sign)) continue;
			List<String> front = new ArrayList<>();
			List<String> back = new ArrayList<>();
			for (SignTextSlot slot : SignTextSlot.values()) {
				(slot.name().contains("BACK") ? back : front).addAll(lines(sign.getText(slot)));
			}
			if (front.isEmpty() && back.isEmpty()) continue;

			BlockPos pos = sign.getBlockPos().immutable();
			List<String> all = new ArrayList<>(front);
			all.addAll(back);
			found.add(new Sign(pos, box(level, pos), front, back, coordinates(String.join(" ", all))));
		}

		ChunkPos pos = chunk.getPos();
		if (found.isEmpty()) signs.remove(pos);
		else signs.put(pos, found);

		for (Sign sign : found) {
			if (reported.add(sign.pos().asLong() + "|" + sign.text())) report(sign);
		}
	}

	private static List<String> lines(SignText text) {
		List<String> lines = new ArrayList<>();
		for (Component message : text.getMessages(false)) {
			String line = message.getString().strip();
			if (!line.isEmpty()) lines.add(line);
		}
		return lines;
	}

	private static AABB box(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		VoxelShape shape = state.getShape(level, pos);
		if (shape.isEmpty()) return new AABB(pos);
		return shape.bounds().move(pos);
	}

	/** The coordinates written in a sign's text, formatted "x y z" or "x z", or null if there are none. */
	public static String coordinates(String text) {
		Matcher m = LABELLED.matcher(text);
		if (m.find()) {
			return m.group(2) != null ? m.group(1) + " " + m.group(2) + " " + m.group(3) : m.group(1) + " " + m.group(3);
		}
		m = TRIPLE.matcher(text);
		while (m.find()) {
			long x = Long.parseLong(m.group(1)), y = Long.parseLong(m.group(2)), z = Long.parseLong(m.group(3));
			if (y >= -64 && y <= 320 && Math.max(Math.abs(x), Math.abs(z)) >= 100) return x + " " + y + " " + z;
		}
		m = PAIR.matcher(text);
		while (m.find()) {
			long x = Long.parseLong(m.group(1)), z = Long.parseLong(m.group(2));
			if (Math.max(Math.abs(x), Math.abs(z)) >= 100) return x + " " + z;
		}
		return null;
	}

	private void report(Sign sign) {
		BlockPos p = sign.pos();
		String where = p.getX() + ", " + p.getY() + ", " + p.getZ();
		if (sign.hasCoords()) {
			if (notify.isOn()) Notifications.notice("Coordinates on a sign", sign.coords() + "  (sign at " + where + ")", coordsColor.color());
			if (chat.isOn()) Finds.chat("Sign at " + where + " reads coordinates " + sign.coords() + ": \"" + sign.text() + "\"");
		}
		if (log.isOn() && (sign.hasCoords() || logAll.isOn())) {
			Finds.log("signs.csv", "x,y,z,coordinates,front,back",
					p.getX() + "," + p.getY() + "," + p.getZ() + "," + Finds.csv(sign.hasCoords() ? sign.coords() : "")
							+ "," + Finds.csv(String.join(" / ", sign.front())) + "," + Finds.csv(String.join(" / ", sign.back())));
		}
	}

	private boolean shown(Sign sign) {
		return !onlyCoords.isOn() || sign.hasCoords();
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
		if (!highlight.isOn()) return;
		double maxSq = range.get() * range.get();
		for (List<Sign> list : signs.values()) {
			for (Sign sign : list) {
				if (!shown(sign) || sign.box().getCenter().distanceToSqr(cam) > maxSq) continue;
				int c = sign.hasCoords() ? coordsColor.color() : signColor.color();
				AABB box = sign.box().inflate(0.03);
				batch.fill(box, ColorUtil.fade(c, 0.18f), true);
				batch.outline(box, ColorUtil.fade(c, 0.95f), 2f, true);
			}
		}
	}

	private record Label(Sign sign, Projection.Point point) {
	}

	@Override
	public void drawOverlay(Ui ui, int screenWidth, int screenHeight) {
		if (!showText.isOn()) return;
		List<Label> labels = new ArrayList<>();
		for (List<Sign> list : signs.values()) {
			for (Sign sign : list) {
				if (!shown(sign)) continue;
				AABB box = sign.box();
				Projection.Point point = Projection.toScreen(box.getCenter().add(0, box.getYsize() / 2 + 0.3, 0));
				if (point == null || point.distance() > textRange.get()) continue;
				labels.add(new Label(sign, point));
			}
		}
		// Far labels first, so near ones draw on top.
		labels.sort((a, b) -> Double.compare(b.point().distance(), a.point().distance()));
		for (Label label : labels) draw(ui, label);
	}

	private void draw(Ui ui, Label label) {
		Sign sign = label.sign();
		Projection.Point point = label.point();
		float s = textScale.getFloat() * (float) Math.max(0.6, Math.min(1.0, 12.0 / Math.max(1.0, point.distance())));

		List<String> lines = new ArrayList<>(sign.front());
		int backStart = lines.size();
		lines.addAll(sign.back());
		int accent = sign.hasCoords() ? coordsColor.color() : signColor.color();

		int width = 0;
		for (String line : lines) width = Math.max(width, ui.width(line));
		int lineH = 10;
		int gap = !sign.front().isEmpty() && !sign.back().isEmpty() ? 4 : 0;
		int height = lines.size() * lineH + gap;

		var pose = ui.g.pose();
		pose.pushMatrix();
		pose.translate(point.x(), point.y());
		pose.scale(s, s);

		int x1 = -width / 2 - 5;
		int x2 = width / 2 + 5;
		int y1 = -height - 4;
		ui.roundRect(x1, y1, x2, 1, 3, 0xB008090E);
		ui.rect(x1 + 3, 0, x2 - 3, 1, ColorUtil.fade(accent, 0.85f));

		int y = y1 + 3;
		for (int i = 0; i < lines.size(); i++) {
			if (i == backStart && gap > 0) {
				ui.rect(x1 + 6, y + 1, x2 - 6, y + 2, 0x40FFFFFF);
				y += gap;
			}
			String line = lines.get(i);
			int color = i >= backStart ? 0xFFB7BCCF : 0xFFFFFFFF;
			if (sign.hasCoords() && coordinates(line) != null) color = accent;
			ui.text(line, -ui.width(line) / 2, y, color);
			y += lineH;
		}
		pose.popMatrix();
	}
}
