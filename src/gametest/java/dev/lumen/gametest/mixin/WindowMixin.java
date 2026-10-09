package dev.lumen.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.platform.Window;

import dev.lumen.gametest.FakeFocus;

@Mixin(Window.class)
public abstract class WindowMixin {
	@Inject(method = "isFocused", at = @At("HEAD"), cancellable = true)
	private void lumenTest$fakeFocus(CallbackInfoReturnable<Boolean> cir) {
		if (FakeFocus.lost) cir.setReturnValue(false);
	}
}
