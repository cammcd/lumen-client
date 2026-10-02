package dev.lumen.client.modules;

import net.minecraft.client.player.LocalPlayer;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;

/**
 * Times Kill Aura and Trigger Bot hits for critical damage: they hop when a target is
 * ready and swing on the way down, the same way a player lands a crit by hand.
 */
public final class Criticals extends Module {
	private final BoolSetting jump = add(new BoolSetting("Jump", "Hop when a hit is ready so it lands as a crit.", true));
	private final NumberSetting maxWait = add(new NumberSetting("Max wait", "Ticks to wait for a crit before hitting anyway.", 14, 0, 40, 1, "t"));

	private int waited;
	private boolean holdingJump;

	public Criticals() {
		super("Criticals", "Makes Kill Aura and Trigger Bot land critical hits by timing them with a jump.", Category.COMBAT);
	}

	@Override
	protected void onDisable() {
		releaseJump();
		waited = 0;
	}

	@Override
	public void onTick() {
		releaseJump();
	}

	private void releaseJump() {
		if (holdingJump) MC.options.keyJump.setDown(false);
		holdingJump = false;
	}

	/** True when a crit is impossible right now, so waiting for one would only delay the hit. */
	private static boolean cannotCrit(LocalPlayer p) {
		return p.isInWater() || p.isInLava() || p.onClimbable() || p.isPassenger() || p.isSprinting()
				|| p.getAbilities().flying || p.isFallFlying();
	}

	/** True when a hit right now would be critical. */
	public boolean critNow() {
		LocalPlayer p = MC.player;
		return p != null && !cannotCrit(p) && !p.onGround() && p.fallDistance > 0 && p.getDeltaMovement().y < 0;
	}

	/**
	 * Called by an attacker that has a target and a charged attack. Returns true if it
	 * should hit now; otherwise it hops, if needed, and the attacker tries again next tick.
	 */
	public boolean prepare() {
		LocalPlayer p = MC.player;
		if (p == null || cannotCrit(p) || critNow()) {
			waited = 0;
			return true;
		}
		if (++waited > maxWait.getInt()) {
			waited = 0;
			return true;
		}
		if (jump.isOn() && p.onGround()) {
			MC.options.keyJump.setDown(true);
			holdingJump = true;
		}
		return false;
	}
}
