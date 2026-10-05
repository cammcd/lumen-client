package dev.lumen.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.player.LocalPlayer;

import dev.lumen.client.modules.Freecam;

/** Keeps the player still while Freecam is using the movement keys. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@Inject(method = "tick()V", at = @At("HEAD"))
	private void lumen$tickHead(CallbackInfo ci) {
		Freecam freecam = Freecam.active();
		if (freecam != null) freecam.swapInput((LocalPlayer) (Object) this);
	}

	@Inject(method = "tick()V", at = @At("RETURN"))
	private void lumen$tickReturn(CallbackInfo ci) {
		Freecam freecam = Freecam.active();
		if (freecam != null) freecam.restoreInput((LocalPlayer) (Object) this);
	}

	@Inject(method = "rideTick()V", at = @At("HEAD"))
	private void lumen$rideTickHead(CallbackInfo ci) {
		Freecam freecam = Freecam.active();
		if (freecam != null) freecam.swapInput((LocalPlayer) (Object) this);
	}

	@Inject(method = "rideTick()V", at = @At("RETURN"))
	private void lumen$rideTickReturn(CallbackInfo ci) {
		Freecam freecam = Freecam.active();
		if (freecam != null) freecam.restoreInput((LocalPlayer) (Object) this);
	}

	/**
	 * Movement keys become movement here. Whatever input object is in place, the body
	 * gets none of it while Freecam flies, so no route can walk it.
	 */
	@Inject(method = "applyInput()V", at = @At("HEAD"), cancellable = true)
	private void lumen$freezeInput(CallbackInfo ci) {
		if (Freecam.active() == null) return;
		LocalPlayer self = (LocalPlayer) (Object) this;
		self.xxa = 0f;
		self.zza = 0f;
		self.setJumping(false);
		ci.cancel();
	}

	/** Holding sneak lowers the camera; the body should not crouch. */
	@Inject(method = "isShiftKeyDown()Z", at = @At("HEAD"), cancellable = true)
	private void lumen$noSneak(CallbackInfoReturnable<Boolean> cir) {
		if (Freecam.active() != null) cir.setReturnValue(false);
	}
}
