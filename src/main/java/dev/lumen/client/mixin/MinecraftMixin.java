package dev.lumen.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

import dev.lumen.client.Lumen;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/**
	 * Every tick the attack key is not held, vanilla stops whatever block is being broken.
	 * The Printer mines without the key, so that is skipped while it is mining; holding
	 * attack yourself still works as normal.
	 */
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void lumen$keepPrinterMining(boolean attacking, CallbackInfo ci) {
		if (!attacking && Lumen.modules() != null && Lumen.modules().printer.isMining()) ci.cancel();
	}

	/** Losing focus opens the pause menu after half a second; Auto Use keeps the game going instead. */
	@Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
	private void lumen$runInBackground(CallbackInfo ci) {
		if (Lumen.modules() != null && Lumen.modules().autoUse.keepsRunning()) ci.cancel();
	}
}
