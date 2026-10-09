package dev.lumen.client.modules;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;

/**
 * Holds right click for you. Holding the use key is all it takes: vanilla repeats the
 * click every four ticks, and keeps eating or drawing for as long as it is held. With the
 * game in the background it keeps going, because the pause menu that would otherwise open
 * when the window loses focus, and stop all key handling, is held off.
 */
public final class AutoUse extends Module {
	private final BoolSetting background = add(new BoolSetting("In background",
			"Keep going when you switch to another window: the game does not open the pause menu when it loses focus.", true));

	private boolean holding;

	public AutoUse() {
		super("Auto Use", "Holds right click for you, even with the game in the background. For farms you have to keep right clicking.",
				Category.PLAYER);
	}

	/** True while the game should not pause when its window loses focus. */
	public boolean keepsRunning() {
		return isEnabled() && background.isOn();
	}

	@Override
	protected void onDisable() {
		if (holding) MC.options.keyUse.setDown(false);
		holding = false;
	}

	@Override
	public void onTick() {
		if (MC.player == null) return;
		// Your own menus come first; it carries on when they close.
		if (MC.gui.screen() != null) {
			if (holding) MC.options.keyUse.setDown(false);
			holding = false;
			return;
		}
		MC.options.keyUse.setDown(true);
		holding = true;
	}
}
