package dev.lumen.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;

public final class CameraUtil {
	private CameraUtil() {
	}

	/** A point one block in front of the camera along its view direction, camera-relative. */
	public static float[] tracerOrigin() {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		if (camera == null) return new float[] {0, 0, 0};
		double yaw = Math.toRadians(camera.yRot());
		double pitch = Math.toRadians(camera.xRot());
		float x = (float) (-Math.sin(yaw) * Math.cos(pitch));
		float y = (float) (-Math.sin(pitch));
		float z = (float) (Math.cos(yaw) * Math.cos(pitch));
		return new float[] {x, y, z};
	}
}
