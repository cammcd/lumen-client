package dev.lumen.client.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.mixin.CameraAccessor;

/**
 * Turns world positions into GUI coordinates so labels can be drawn as crisp 2D text
 * over the world. Updated once per frame after the level renders.
 */
public final class Projection {
	/** A projected point: GUI x and y, and the distance from the camera in blocks. */
	public record Point(float x, float y, double distance) {
	}

	private static final Matrix4f VIEW = new Matrix4f();
	private static final Matrix4f PROJECTION = new Matrix4f();
	private static final Matrix4f COMBINED = new Matrix4f();
	private static Vec3 camera = Vec3.ZERO;
	private static boolean ready;

	private Projection() {
	}

	static void update(Matrix4f viewRotation, Vec3 cameraPos) {
		Minecraft mc = Minecraft.getInstance();
		Camera cam = mc.gameRenderer.mainCamera();
		if (cam == null) return;

		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		float fov = ((CameraAccessor) (Object) cam).lumen$calculateFov(partialTick);
		float aspect = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());

		VIEW.set(viewRotation);
		PROJECTION.setPerspective((float) Math.toRadians(fov), aspect, 0.05f, 4096f);
		PROJECTION.mul(VIEW, COMBINED);
		camera = cameraPos;
		ready = true;
	}

	/** Projects a world position, or returns null if it is behind the camera. */
	public static Point toScreen(Vec3 world) {
		if (!ready) return null;
		Minecraft mc = Minecraft.getInstance();
		Vector4f v = new Vector4f((float) (world.x - camera.x), (float) (world.y - camera.y), (float) (world.z - camera.z), 1f);
		COMBINED.transform(v);
		if (v.w <= 0.05f) return null;

		float ndcX = v.x / v.w;
		float ndcY = v.y / v.w;
		float x = (ndcX * 0.5f + 0.5f) * mc.getWindow().getGuiScaledWidth();
		float y = (1f - (ndcY * 0.5f + 0.5f)) * mc.getWindow().getGuiScaledHeight();
		return new Point(x, y, world.distanceTo(camera));
	}

	public static Vec3 camera() {
		return camera;
	}
}
