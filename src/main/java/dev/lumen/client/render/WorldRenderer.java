package dev.lumen.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.Lumen;
import dev.lumen.client.module.Module;

/** Entry point for all in-world overlays, called once per frame after the level has rendered. */
public final class WorldRenderer {
	private WorldRenderer() {
	}

	public static void render(CameraRenderState cameraState) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || Lumen.modules() == null) return;

		Vec3 cam = cameraState.pos;
		EspBatch batch = new EspBatch(cam.x, cam.y, cam.z);

		for (Module module : Lumen.modules().all()) {
			if (module.isEnabled() && module instanceof WorldRenderable renderable) {
				renderable.renderWorld(batch, cam);
			}
		}

		if (batch.isEmpty()) return;

		// Geometry is camera-relative; the camera's rotation is applied here because this
		// runs outside the level renderer's own transforms.
		PoseStack poseStack = new PoseStack();
		poseStack.mulPose(cameraState.viewRotationMatrix);
		batch.drawNow(poseStack);
	}
}
