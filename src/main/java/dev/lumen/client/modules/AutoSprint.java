package dev.lumen.client.modules;

import dev.lumen.client.mixin.LocalPlayerAccessor;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;

/**
 * Sprints whenever vanilla would let you start: moving forward, enough food, not
 * sneaking, eating, blind or gliding. Vanilla still decides when sprinting stops.
 */
public final class AutoSprint extends Module {
	public AutoSprint() {
		super("Auto Sprint", "Sprints for you whenever you move forward and the game allows it.", Category.PLAYER);
	}

	@Override
	public void onTick() {
		if (MC.player == null) return;
		if (((LocalPlayerAccessor) (Object) MC.player).lumen$canStartSprinting()) MC.player.setSprinting(true);
	}
}
