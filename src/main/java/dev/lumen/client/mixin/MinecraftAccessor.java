package dev.lumen.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.Minecraft;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Accessor("fps")
	static int lumen$getFps() {
		throw new AssertionError();
	}

	/** A left click, exactly as pressing attack does it. */
	@Invoker("startAttack")
	boolean lumen$startAttack();

	/** A right click, exactly as pressing use does it. */
	@Invoker("startUseItem")
	void lumen$startUseItem();
}
