package dev.lumen.client.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.commands.RenderPass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.AABB;

/**
 * Collects ESP geometry for one frame in camera-relative coordinates, then draws it
 * in at most four draws.
 */
public final class EspBatch {
	private record Box(float x1, float y1, float z1, float x2, float y2, float z2, int color) {
	}

	private record Line(float x1, float y1, float z1, float x2, float y2, float z2, int color, float width) {
	}

	private final List<Box> fillsThrough = new ArrayList<>();
	private final List<Box> fillsDepth = new ArrayList<>();
	private final List<Line> linesThrough = new ArrayList<>();
	private final List<Line> linesDepth = new ArrayList<>();

	private final double camX;
	private final double camY;
	private final double camZ;

	public EspBatch(double camX, double camY, double camZ) {
		this.camX = camX;
		this.camY = camY;
		this.camZ = camZ;
	}

	/** Adds a filled box. The box is in world coordinates. */
	public void fill(AABB box, int color, boolean throughWalls) {
		if ((color >>> 24) == 0) return;
		(throughWalls ? fillsThrough : fillsDepth).add(new Box(
				(float) (box.minX - camX), (float) (box.minY - camY), (float) (box.minZ - camZ),
				(float) (box.maxX - camX), (float) (box.maxY - camY), (float) (box.maxZ - camZ),
				color));
	}

	/** Adds the twelve edges of a box. The box is in world coordinates. */
	public void outline(AABB box, int color, float width, boolean throughWalls) {
		if ((color >>> 24) == 0) return;
		float x1 = (float) (box.minX - camX), y1 = (float) (box.minY - camY), z1 = (float) (box.minZ - camZ);
		float x2 = (float) (box.maxX - camX), y2 = (float) (box.maxY - camY), z2 = (float) (box.maxZ - camZ);
		List<Line> out = throughWalls ? linesThrough : linesDepth;

		// bottom
		out.add(new Line(x1, y1, z1, x2, y1, z1, color, width));
		out.add(new Line(x2, y1, z1, x2, y1, z2, color, width));
		out.add(new Line(x2, y1, z2, x1, y1, z2, color, width));
		out.add(new Line(x1, y1, z2, x1, y1, z1, color, width));
		// top
		out.add(new Line(x1, y2, z1, x2, y2, z1, color, width));
		out.add(new Line(x2, y2, z1, x2, y2, z2, color, width));
		out.add(new Line(x2, y2, z2, x1, y2, z2, color, width));
		out.add(new Line(x1, y2, z2, x1, y2, z1, color, width));
		// verticals
		out.add(new Line(x1, y1, z1, x1, y2, z1, color, width));
		out.add(new Line(x2, y1, z1, x2, y2, z1, color, width));
		out.add(new Line(x2, y1, z2, x2, y2, z2, color, width));
		out.add(new Line(x1, y1, z2, x1, y2, z2, color, width));
	}

	/** Adds a line from a camera-relative start to a world-space end. */
	public void tracer(float startX, float startY, float startZ, double endX, double endY, double endZ, int color, float width) {
		if ((color >>> 24) == 0) return;
		linesThrough.add(new Line(startX, startY, startZ,
				(float) (endX - camX), (float) (endY - camY), (float) (endZ - camZ), color, width));
	}

	public boolean isEmpty() {
		return fillsThrough.isEmpty() && fillsDepth.isEmpty() && linesThrough.isEmpty() && linesDepth.isEmpty();
	}

	/**
	 * Draws everything collected this frame in its own render pass. This runs after the
	 * level has finished rendering, so blocks, block entities and mobs drawn earlier
	 * cannot cover highlights that are meant to show through them.
	 */
	public void drawNow(PoseStack poseStack) {
		if (isEmpty()) return;

		StagedVertexBuffer staged = new StagedVertexBuffer(() -> "Lumen ESP", RenderType.BIG_BUFFER_SIZE);
		List<RenderType> types = new ArrayList<>();
		List<StagedVertexBuffer.Draw> draws = new ArrayList<>();
		try {
			PoseStack.Pose pose = poseStack.last();
			appendBoxes(staged, types, draws, pose, fillsDepth, LumenRenderTypes.quads(false));
			appendBoxes(staged, types, draws, pose, fillsThrough, LumenRenderTypes.quads(true));
			appendLines(staged, types, draws, pose, linesDepth, LumenRenderTypes.lines(false));
			appendLines(staged, types, draws, pose, linesThrough, LumenRenderTypes.lines(true));
			if (draws.isEmpty()) return;

			staged.upload();
			RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Lumen ESP",
					target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
				RenderSystem.bindDefaultUniforms(pass);
				for (int i = 0; i < draws.size(); i++) {
					StagedVertexBuffer.ExecuteInfo info = staged.getExecuteInfo(draws.get(i));
					if (info != null) types.get(i).prepare().drawFromBuffer(info, pass);
				}
			}
			staged.endDraw();
		} finally {
			staged.close();
		}
	}

	private static StagedVertexBuffer.Draw begin(StagedVertexBuffer staged, RenderType type) {
		return staged.appendDraw(type.format(), type.primitiveTopology(),
				type.sortOnUpload() ? RenderSystem.getProjectionType().vertexSorting() : null);
	}

	private static void appendBoxes(StagedVertexBuffer staged, List<RenderType> types, List<StagedVertexBuffer.Draw> draws,
			PoseStack.Pose pose, List<Box> boxes, RenderType type) {
		if (boxes.isEmpty()) return;
		StagedVertexBuffer.Draw draw = begin(staged, type);
		VertexConsumer buffer = staged.getVertexBuilder(draw);
		for (Box b : boxes) drawBox(pose, buffer, b);
		types.add(type);
		draws.add(draw);
	}

	private static void appendLines(StagedVertexBuffer staged, List<RenderType> types, List<StagedVertexBuffer.Draw> draws,
			PoseStack.Pose pose, List<Line> lines, RenderType type) {
		if (lines.isEmpty()) return;
		StagedVertexBuffer.Draw draw = begin(staged, type);
		VertexConsumer buffer = staged.getVertexBuilder(draw);
		for (Line l : lines) drawLine(pose, buffer, l);
		types.add(type);
		draws.add(draw);
	}

	// Faces are wound counter-clockwise seen from outside, so back faces are culled
	// and each box reads as a single translucent volume.
	private static void drawBox(PoseStack.Pose pose, VertexConsumer buffer, Box b) {
		int c = b.color();
		float x1 = b.x1(), y1 = b.y1(), z1 = b.z1(), x2 = b.x2(), y2 = b.y2(), z2 = b.z2();

		// bottom (-Y)
		buffer.addVertex(pose, x1, y1, z1).setColor(c);
		buffer.addVertex(pose, x2, y1, z1).setColor(c);
		buffer.addVertex(pose, x2, y1, z2).setColor(c);
		buffer.addVertex(pose, x1, y1, z2).setColor(c);
		// top (+Y)
		buffer.addVertex(pose, x1, y2, z1).setColor(c);
		buffer.addVertex(pose, x1, y2, z2).setColor(c);
		buffer.addVertex(pose, x2, y2, z2).setColor(c);
		buffer.addVertex(pose, x2, y2, z1).setColor(c);
		// north (-Z)
		buffer.addVertex(pose, x1, y1, z1).setColor(c);
		buffer.addVertex(pose, x1, y2, z1).setColor(c);
		buffer.addVertex(pose, x2, y2, z1).setColor(c);
		buffer.addVertex(pose, x2, y1, z1).setColor(c);
		// east (+X)
		buffer.addVertex(pose, x2, y1, z1).setColor(c);
		buffer.addVertex(pose, x2, y2, z1).setColor(c);
		buffer.addVertex(pose, x2, y2, z2).setColor(c);
		buffer.addVertex(pose, x2, y1, z2).setColor(c);
		// south (+Z)
		buffer.addVertex(pose, x1, y1, z2).setColor(c);
		buffer.addVertex(pose, x2, y1, z2).setColor(c);
		buffer.addVertex(pose, x2, y2, z2).setColor(c);
		buffer.addVertex(pose, x1, y2, z2).setColor(c);
		// west (-X)
		buffer.addVertex(pose, x1, y1, z1).setColor(c);
		buffer.addVertex(pose, x1, y1, z2).setColor(c);
		buffer.addVertex(pose, x1, y2, z2).setColor(c);
		buffer.addVertex(pose, x1, y2, z1).setColor(c);
	}

	private static void drawLine(PoseStack.Pose pose, VertexConsumer buffer, Line l) {
		float dx = l.x2() - l.x1();
		float dy = l.y2() - l.y1();
		float dz = l.z2() - l.z1();
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1.0e-6f) return;
		float nx = dx / len, ny = dy / len, nz = dz / len;

		buffer.addVertex(pose, l.x1(), l.y1(), l.z1()).setColor(l.color())
				.setNormal(pose, nx, ny, nz).setLineWidth(l.width());
		buffer.addVertex(pose, l.x2(), l.y2(), l.z2()).setColor(l.color())
				.setNormal(pose, nx, ny, nz).setLineWidth(l.width());
	}
}
