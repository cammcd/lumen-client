package dev.lumen.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

import dev.lumen.client.Lumen;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"))
	private void lumen$onKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
		// Action 1 is a fresh press. Repeats and releases are ignored, and module
		// keybinds only fire while no screen (chat, inventory, menus) is open.
		if (action != 1) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() != null || Lumen.modules() == null) return;

		Lumen.modules().onKey(InputConstants.getKey(event).getName());
	}
}
