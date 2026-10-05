package dev.lumen.client.modules;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.world.InteractionHand;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;

/** Small, varied actions on a timer so idle-kick plugins see you as active. */
public final class AntiAfk extends Module {
	private final NumberSetting interval = add(new NumberSetting("Interval", "Seconds between actions.", 20, 5, 300, 5, "s"));
	private final NumberSetting jitter = add(new NumberSetting("Jitter", "Random extra delay, so the timing is not exact.", 5, 0, 60, 1, "s"));
	private final BoolSetting jump = add(new BoolSetting("Jump", "Jump once.", true));
	private final BoolSetting swing = add(new BoolSetting("Swing", "Swing your arm.", true));
	private final BoolSetting turn = add(new BoolSetting("Turn", "Look a little left or right.", true));
	private final BoolSetting sneak = add(new BoolSetting("Sneak", "Crouch for a moment.", false));

	private int ticksLeft;
	private int releaseIn;
	private int actions;

	public AntiAfk() {
		super("Anti AFK", "Jumps, swings and looks around on a timer so you are not kicked for idling.", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		schedule();
		releaseIn = 0;
	}

	@Override
	protected void onDisable() {
		release();
	}

	private void schedule() {
		int extra = jitter.getInt() > 0 ? ThreadLocalRandom.current().nextInt(jitter.getInt() * 20 + 1) : 0;
		ticksLeft = interval.getInt() * 20 + extra;
	}

	private void release() {
		if (releaseIn > 0) {
			MC.options.keyJump.setDown(false);
			MC.options.keyShift.setDown(false);
		}
		releaseIn = 0;
	}

	@Override
	public String hudInfo() {
		return (ticksLeft / 20) + "s";
	}

	@Override
	public void onTick() {
		if (MC.player == null) return;
		if (releaseIn > 0 && --releaseIn == 0) {
			MC.options.keyJump.setDown(false);
			MC.options.keyShift.setDown(false);
		}
		if (--ticksLeft > 0) return;
		schedule();
		act();
	}

	/** Runs one round of the chosen actions now. */
	public void act() {
		if (MC.player == null) return;
		ThreadLocalRandom random = ThreadLocalRandom.current();
		if (jump.isOn()) {
			MC.options.keyJump.setDown(true);
			releaseIn = Math.max(releaseIn, 2);
		}
		if (sneak.isOn()) {
			MC.options.keyShift.setDown(true);
			releaseIn = Math.max(releaseIn, 8);
		}
		if (swing.isOn()) MC.player.swing(InteractionHand.MAIN_HAND, MC.player.getMainHandItem().getAttackAnimation(), false);
		if (turn.isOn()) {
			float delta = (10 + random.nextFloat() * 25) * (random.nextBoolean() ? 1 : -1);
			MC.player.setYRot(MC.player.getYRot() + delta);
		}
		actions++;
	}

	/** Rounds of actions run since the module was loaded; used by the game test. */
	public int actions() {
		return actions;
	}
}
