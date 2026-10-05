package dev.lumen.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

import dev.lumen.client.Lumen;

@Mixin(Player.class)
public abstract class PlayerMixin {
	/** Safe Walk: the edge check sneaking uses, without having to sneak. */
	@Inject(method = "isStayingOnGroundSurface()Z", at = @At("HEAD"), cancellable = true)
	private void lumen$safeWalk(CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof LocalPlayer) || Lumen.modules() == null) return;
		if (Lumen.modules().safeWalk.isEnabled()) cir.setReturnValue(true);
	}
}
