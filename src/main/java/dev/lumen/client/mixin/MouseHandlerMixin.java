package dev.lumen.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;

import dev.lumen.client.modules.Freecam;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	/** While Freecam is on, mouse movement turns the camera instead of the player. */
	@WrapOperation(method = "turnPlayer(D)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void lumen$turn(LocalPlayer player, double deltaYaw, double deltaPitch, Operation<Void> original) {
		Freecam freecam = Freecam.active();
		if (freecam != null) {
			freecam.turn(deltaYaw, deltaPitch);
			return;
		}
		original.call(player, deltaYaw, deltaPitch);
	}

	/** Lets the scroll wheel change Freecam speed instead of the hotbar slot. */
	@Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
	private void lumen$scroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		Freecam freecam = Freecam.active();
		if (freecam != null && freecam.handleScroll(vertical)) ci.cancel();
	}
}
