package dev.lumen.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.Minecraft;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Accessor("fps")
	static int lumen$getFps() {
		throw new AssertionError();
	}
}
