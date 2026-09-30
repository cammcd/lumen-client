package dev.lumen.client.modules;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.render.CameraUtil;
import dev.lumen.client.render.EspBatch;
import dev.lumen.client.render.WorldRenderable;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.EnumSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ColorUtil;

/**
 * Shared look for every highlighter (blocks and entities): fill and outline boxes,
 * tracers, range, distance fade and pulse. Subclasses decide what to highlight.
 */
public abstract class HighlightModule extends Module implements WorldRenderable {
	public enum Mode {
		BOTH("Fill + Outline"),
		OUTLINE("Outline"),
		FILL("Fill");

		private final String label;

		Mode(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/** Per-frame values read once from the settings, then reused for every target. */
	protected record Style(boolean throughWalls, boolean fill, boolean outline, float fillAlpha, float outlineAlpha,
			float tracerAlpha, float lineWidth, float tracerWidth, double padding, double range, boolean distanceFade,
			float pulse, float[] tracerStart) {
	}

	protected final EnumSetting<Mode> mode;
	protected final BoolSetting throughWalls;
	protected final NumberSetting fillOpacity;
	protected final NumberSetting outlineOpacity;
	protected final NumberSetting lineWidth;
	protected final NumberSetting padding;
	protected final BoolSetting tracers;
	protected final NumberSetting tracerWidth;
	protected final NumberSetting tracerOpacity;
	protected final NumberSetting range;
	protected final BoolSetting distanceFade;
	protected final BoolSetting pulse;
	protected final NumberSetting pulseSpeed;

	protected HighlightModule(String name, String description, double defaultRange, boolean defaultTracers, String targetsSection) {
		super(name, description, Category.RENDER);
		setDefaultSection("Appearance");

		mode = add(new EnumSetting<>("Mode", "How highlights are drawn.", Mode.BOTH));
		throughWalls = add(new BoolSetting("Through walls", "Draw highlights on top of terrain so they show through walls.", true));
		fillOpacity = add(new NumberSetting("Fill opacity", "Opacity of the filled box.", 22, 0, 100, 1, "%"))
				.visibleWhen(() -> !mode.is(Mode.OUTLINE));
		outlineOpacity = add(new NumberSetting("Outline opacity", "Opacity of the box edges.", 95, 0, 100, 1, "%"))
				.visibleWhen(() -> !mode.is(Mode.FILL));
		lineWidth = add(new NumberSetting("Line width", "Thickness of outlines.", 2, 0.5, 6, 0.5))
				.visibleWhen(() -> !mode.is(Mode.FILL));
		padding = add(new NumberSetting("Padding", "Grows each box outward, in blocks.", 0.0, 0, 0.25, 0.01));
		tracers = add(new BoolSetting("Tracers", "Draw a line from the crosshair to each highlight.", defaultTracers));
		tracerWidth = add(new NumberSetting("Tracer width", "Thickness of tracer lines.", 1.5, 0.5, 5, 0.5))
				.visibleWhen(tracers::isOn);
		tracerOpacity = add(new NumberSetting("Tracer opacity", "Opacity of tracer lines.", 75, 0, 100, 1, "%"))
				.visibleWhen(tracers::isOn);
		range = add(new NumberSetting("Range", "Maximum distance to highlight, in blocks.", defaultRange, 8, 512, 8, "m"));
		distanceFade = add(new BoolSetting("Distance fade", "Fade highlights out as they approach the range limit.", true));
		pulse = add(new BoolSetting("Pulse", "Gently breathe the highlight opacity.", false));
		pulseSpeed = add(new NumberSetting("Pulse speed", "How fast highlights breathe.", 1.0, 0.2, 4, 0.1, "x"))
				.visibleWhen(pulse::isOn);

		// The subclass's own targets come first, under their own heading.
		setDefaultSection(targetsSection);
		insertFutureSettingsAtTop();
	}

	protected Style frameStyle() {
		float pulseFactor = 1f;
		if (pulse.isOn()) {
			double t = System.nanoTime() / 1_000_000_000.0 * pulseSpeed.get() * Math.PI;
			pulseFactor = (float) (0.55 + 0.45 * Math.sin(t));
		}
		return new Style(
				throughWalls.isOn(),
				!mode.is(Mode.OUTLINE),
				!mode.is(Mode.FILL),
				fillOpacity.getFloat() / 100f,
				outlineOpacity.getFloat() / 100f,
				tracerOpacity.getFloat() / 100f,
				lineWidth.getFloat(),
				tracerWidth.getFloat(),
				padding.get(),
				range.get(),
				distanceFade.isOn(),
				pulseFactor,
				tracers.isOn() ? CameraUtil.tracerOrigin() : null);
	}

	/**
	 * Draws one highlight. The colour's own alpha scales everything, so a translucent
	 * colour stays translucent. Returns false if the box is out of range.
	 */
	protected boolean draw(EspBatch batch, Vec3 cam, Style s, AABB rawBox, int baseColor) {
		AABB box = s.padding() > 0 ? rawBox.inflate(s.padding()) : rawBox;
		Vec3 center = box.getCenter();
		double dist = center.distanceTo(cam);
		if (dist > s.range()) return false;

		float fade = s.pulse();
		if (s.distanceFade()) {
			double start = s.range() * 0.6;
			if (dist > start) fade *= (float) Math.max(0.12, 1.0 - (dist - start) / (s.range() - start));
		}
		float colorAlpha = ColorUtil.alpha(baseColor) / 255f;

		if (s.fill()) {
			batch.fill(box, ColorUtil.withAlpha(baseColor, Math.round(255 * s.fillAlpha() * colorAlpha * fade)), s.throughWalls());
		}
		if (s.outline()) {
			batch.outline(box, ColorUtil.withAlpha(baseColor, Math.round(255 * s.outlineAlpha() * colorAlpha * fade)), s.lineWidth(), s.throughWalls());
		}
		if (s.tracerStart() != null) {
			float[] t = s.tracerStart();
			batch.tracer(t[0], t[1], t[2], center.x, center.y, center.z,
					ColorUtil.withAlpha(baseColor, Math.round(255 * s.tracerAlpha() * colorAlpha * fade)), s.tracerWidth());
		}
		return true;
	}

	/** A rainbow phase offset that makes rainbow colours flow across space. */
	protected static float rainbowOffset(Vec3 pos) {
		return (float) ((pos.x + pos.z) * 0.004);
	}
}
