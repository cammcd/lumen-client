package dev.lumen.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import dev.lumen.client.Lumen;
import dev.lumen.client.module.Module;

/** Entry point for all in-world overlays, called once per frame while submits are collected. */
public final class WorldRenderer {
	private WorldRenderer() {
	}

	public static void render(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || Lumen.modules() == null) return;

		PoseStack poseStack = context.poseStack();
		if (poseStack == null) return;

		Vec3 cam = context.levelState().cameraRenderState.pos;
		EspBatch batch = new EspBatch(cam.x, cam.y, cam.z);

		for (Module module : Lumen.modules().all()) {
			if (module.isEnabled() && module instanceof WorldRenderable renderable) {
				renderable.renderWorld(batch, cam);
			}
		}

		if (batch.isEmpty()) return;

		poseStack.pushPose();
		batch.submit(context.submitNodeCollector(), poseStack);
		poseStack.popPose();
	}
}
