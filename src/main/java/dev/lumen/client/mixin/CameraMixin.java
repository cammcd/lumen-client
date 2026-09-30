package dev.lumen.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.modules.Freecam;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(Vec3 pos);

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	/** Moves the camera to the Freecam position once the game has placed it on the player. */
	@Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER))
	private void lumen$applyFreecam(DeltaTracker deltaTracker, CallbackInfo ci) {
		Freecam freecam = Freecam.active();
		if (freecam == null) return;

		detached = true;
		setPosition(freecam.cameraPos(deltaTracker.getGameTimeDeltaPartialTick(true)));
		setRotation(freecam.yaw(), freecam.pitch());
	}

	/** Occlusion culling assumes the camera is in open air, which is not true inside blocks. */
	@Inject(method = "extractRenderState(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/DeltaTracker;)V",
			at = @At("RETURN"))
	private void lumen$disableSmartCull(CameraRenderState state, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (Freecam.active() != null) state.smartCull = false;
	}
}
