package dev.lumen.client.render;

import net.minecraft.world.phys.Vec3;

/** A module that draws into the world each frame while it is enabled. */
public interface WorldRenderable {
	void renderWorld(EspBatch batch, Vec3 cameraPos);
}
