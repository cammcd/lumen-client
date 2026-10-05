package dev.lumen.client.modules;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;

/**
 * Stops you walking off edges, the way sneaking does, at full walking speed. The
 * work happens in PlayerMixin, which answers the game's edge check while this is on.
 */
public final class SafeWalk extends Module {
	public SafeWalk() {
		super("Safe Walk", "Stops you walking off the edge of blocks, without sneaking.", Category.PLAYER);
	}
}
