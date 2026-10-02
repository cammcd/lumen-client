package dev.lumen.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.Camera;

@Mixin(Camera.class)
public interface CameraAccessor {
	/** The vertical field of view in degrees, including zoom, sprint and other effects. */
	@Invoker("calculateFov")
	float lumen$calculateFov(float partialTick);
}
