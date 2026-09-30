package dev.lumen.client.modules;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.render.CameraUtil;
import dev.lumen.client.render.EspBatch;
import dev.lumen.client.render.WorldRenderable;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.setting.EnumSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ColorUtil;

/**
 * Shared behaviour for block entity highlighters: scanning loaded chunks on a timer,
 * and drawing boxes, outlines and tracers with a common set of style settings.
 */
public abstract class EspModule extends Module implements WorldRenderable {
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

	/** A highlighted block. If fixedColor is non-zero it overrides the setting colour. */
	protected record Target(AABB box, ColorSetting color, int fixedColor) {
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

	private volatile List<Target> targets = List.of();
	private int scanTimer;

	protected EspModule(String name, String description, double defaultRange, boolean defaultTracers) {
		super(name, description, Category.RENDER);
		setDefaultSection("Appearance");

		mode = add(new EnumSetting<>("Mode", "How highlighted blocks are drawn.", Mode.BOTH));
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

		// The subclass's block types come first, under their own heading.
		setDefaultSection("Blocks");
		insertFutureSettingsAtTop();
	}

	/** Returns the target for a block entity, or null if it should not be highlighted. */
	protected abstract Target match(BlockEntity blockEntity, ClientLevel level);

	@Override
	protected void onEnable() {
		scanTimer = 0;
		rescan();
	}

	@Override
	protected void onDisable() {
		targets = List.of();
	}

	@Override
	public void onTick() {
		if (++scanTimer >= 5) {
			scanTimer = 0;
			rescan();
		}
	}

	public int count() {
		return targets.size();
	}

	@Override
	public String hudInfo() {
		return Integer.toString(targets.size());
	}

	private void rescan() {
		ClientLevel level = MC.level;
		LocalPlayer player = MC.player;
		if (level == null || player == null) {
			targets = List.of();
			return;
		}

		double maxRange = range.get();
		double maxRangeSq = maxRange * maxRange;
		Vec3 eye = player.position();
		int chunkRadius = Math.min(MC.options.getEffectiveRenderDistance() + 1, (int) Math.ceil(maxRange / 16.0) + 1);
		ChunkPos center = player.chunkPosition();

		List<Target> found = new ArrayList<>();
		for (int cx = center.x() - chunkRadius; cx <= center.x() + chunkRadius; cx++) {
			for (int cz = center.z() - chunkRadius; cz <= center.z() + chunkRadius; cz++) {
				if (!level.hasChunk(cx, cz)) continue;
				LevelChunk chunk = level.getChunk(cx, cz);
				if (chunk == null) continue;

				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					BlockPos pos = blockEntity.getBlockPos();
					double dx = pos.getX() + 0.5 - eye.x;
					double dy = pos.getY() + 0.5 - eye.y;
					double dz = pos.getZ() + 0.5 - eye.z;
					if (dx * dx + dy * dy + dz * dz > maxRangeSq) continue;

					Target target = match(blockEntity, level);
					if (target != null) found.add(target);
				}
			}
		}

		targets = found;
	}

	/** The outline shape of a block as a world-space box, or a full cube for shapeless blocks. */
	protected static AABB blockBox(ClientLevel level, BlockPos pos, BlockState state) {
		VoxelShape shape = state.getShape(level, pos);
		if (shape.isEmpty()) return new AABB(pos);
		return shape.bounds().move(pos);
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
		List<Target> current = targets;
		if (current.isEmpty()) return;

		boolean walls = throughWalls.isOn();
		boolean drawFill = !mode.is(Mode.OUTLINE);
		boolean drawOutline = !mode.is(Mode.FILL);
		float fillAlpha = fillOpacity.getFloat() / 100f;
		float outlineAlpha = outlineOpacity.getFloat() / 100f;
		float tracerAlpha = tracerOpacity.getFloat() / 100f;
		float width = lineWidth.getFloat();
		double pad = padding.get();
		double maxRange = range.get();

		float pulseFactor = 1f;
		if (pulse.isOn()) {
			double t = System.nanoTime() / 1_000_000_000.0 * pulseSpeed.get() * Math.PI;
			pulseFactor = (float) (0.55 + 0.45 * Math.sin(t));
		}

		float[] tracerStart = tracers.isOn() ? CameraUtil.tracerOrigin() : null;

		for (Target target : current) {
			AABB box = pad > 0 ? target.box().inflate(pad) : target.box();
			Vec3 center = box.getCenter();
			double dist = center.distanceTo(cam);
			if (dist > maxRange) continue;

			float fade = pulseFactor;
			if (distanceFade.isOn()) {
				double start = maxRange * 0.6;
				if (dist > start) {
					fade *= (float) Math.max(0.12, 1.0 - (dist - start) / (maxRange - start));
				}
			}

			int base;
			if (target.fixedColor() != 0) {
				base = target.fixedColor();
			} else {
				base = target.color().color((float) ((center.x + center.z) * 0.004));
			}
			// The colour's own alpha scales everything, so a translucent colour stays translucent.
			float colorAlpha = ColorUtil.alpha(base) / 255f;

			if (drawFill) {
				batch.fill(box, ColorUtil.withAlpha(base, Math.round(255 * fillAlpha * colorAlpha * fade)), walls);
			}
			if (drawOutline) {
				batch.outline(box, ColorUtil.withAlpha(base, Math.round(255 * outlineAlpha * colorAlpha * fade)), width, walls);
			}
			if (tracerStart != null) {
				batch.tracer(tracerStart[0], tracerStart[1], tracerStart[2], center.x, center.y, center.z,
						ColorUtil.withAlpha(base, Math.round(255 * tracerAlpha * colorAlpha * fade)), tracerWidth.getFloat());
			}
		}
	}
}
