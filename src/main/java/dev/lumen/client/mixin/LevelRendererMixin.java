package dev.lumen.client.mixin;

import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import dev.lumen.client.render.WorldRenderer;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	/** Draws ESP after everything else in the level, so nothing rendered later can cover it. */
	@Inject(method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
			at = @At("RETURN"))
	private void lumen$afterLevel(GraphicsResourceAllocator allocator, boolean renderBlockOutline, CameraRenderState cameraState,
			GpuBufferSlice fog, Vector4f fogColor, boolean renderSky, boolean consistentDepth, CallbackInfo ci) {
		WorldRenderer.render(cameraState);
	}
}
