package dev.lumen.client.modules;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;

/** Holds the forward key for you. Pauses while a menu is open. */
public final class AutoWalk extends Module {
	private final BoolSetting jumpInWater = add(new BoolSetting("Swim up", "Hold jump in water so you keep to the surface.", true));
	private final BoolSetting stopOnDamage = add(new BoolSetting("Stop on damage", "Turn off when you take damage.", false));

	private boolean holding;
	private boolean swimming;
	private float lastHealth = -1;

	public AutoWalk() {
		super("Auto Walk", "Walks forward on its own. Pairs with Auto Sprint, Safe Walk and Trail Follower.", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		lastHealth = MC.player != null ? MC.player.getHealth() : -1;
	}

	@Override
	protected void onDisable() {
		if (holding) MC.options.keyUp.setDown(false);
		if (swimming) MC.options.keyJump.setDown(false);
		holding = false;
		swimming = false;
	}

	@Override
	public void onTick() {
		if (MC.player == null) return;
		if (stopOnDamage.isOn()) {
			float health = MC.player.getHealth();
			if (lastHealth >= 0 && health < lastHealth) {
				setEnabled(false);
				return;
			}
			lastHealth = health;
		}

		if (MC.gui.screen() != null) {
			if (holding) MC.options.keyUp.setDown(false);
			if (swimming) MC.options.keyJump.setDown(false);
			holding = false;
			swimming = false;
			return;
		}

		MC.options.keyUp.setDown(true);
		holding = true;

		boolean wet = jumpInWater.isOn() && MC.player.isInWater();
		if (wet) {
			MC.options.keyJump.setDown(true);
			swimming = true;
		} else if (swimming) {
			MC.options.keyJump.setDown(false);
			swimming = false;
		}
	}
}
