package dev.lumen.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.player.LocalPlayer;

@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
	/** Vanilla's own check for whether sprinting may start: forward input, hunger, blindness, items, sneaking. */
	@Invoker("canStartSprinting")
	boolean lumen$canStartSprinting();
}
