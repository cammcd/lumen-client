package dev.lumen.client.modules;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.world.phys.EntityHitResult;

import dev.lumen.client.mixin.MinecraftAccessor;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;

/** Clicks for you at a set rate while you hold a mouse button. */
public final class AutoClicker extends Module {
	private final NumberSetting minCps = add(new NumberSetting("Min CPS", "Lowest clicks per second.", 9, 1, 20, 1));
	private final NumberSetting maxCps = add(new NumberSetting("Max CPS", "Highest clicks per second. Each second picks a rate in between.", 12, 1, 20, 1));
	private final BoolSetting left = add(new BoolSetting("Left click", "Click while holding attack.", true));
	private final BoolSetting onlyEntities = add(new BoolSetting("Only on entities", "Only left-click while aiming at an entity, so mining is not interrupted.", true))
			.visibleWhen(left::isOn);
	private final BoolSetting right = add(new BoolSetting("Right click", "Click while holding use, for placing blocks quickly.", false));

	private double leftBudget;
	private double rightBudget;
	private double cps;
	private int ticksToReroll;
	private int clicks;

	public AutoClicker() {
		super("Auto Clicker", "Clicks at a steady rate while you hold a mouse button.", Category.COMBAT);
	}

	/** Clicks made since the module was loaded; used by the game test. */
	public int clicks() {
		return clicks;
	}

	@Override
	public String hudInfo() {
		return Math.round(cps) + " CPS";
	}

	@Override
	public void onTick() {
		if (MC.player == null || MC.gui.screen() != null) {
			leftBudget = 0;
			rightBudget = 0;
			return;
		}
		if (--ticksToReroll <= 0) {
			double lo = Math.min(minCps.get(), maxCps.get());
			double hi = Math.max(minCps.get(), maxCps.get());
			cps = lo + ThreadLocalRandom.current().nextDouble() * (hi - lo);
			ticksToReroll = 20;
		}
		MinecraftAccessor mc = (MinecraftAccessor) (Object) MC;

		boolean leftActive = left.isOn() && MC.options.keyAttack.isDown()
				&& (!onlyEntities.isOn() || MC.hitResult instanceof EntityHitResult);
		if (leftActive) {
			leftBudget += cps / 20.0;
			for (int n = 0; leftBudget >= 1 && n < 3; n++) {
				leftBudget--;
				mc.lumen$startAttack();
				clicks++;
			}
		} else {
			leftBudget = 0;
		}

		if (right.isOn() && MC.options.keyUse.isDown()) {
			rightBudget += cps / 20.0;
			for (int n = 0; rightBudget >= 1 && n < 3; n++) {
				rightBudget--;
				mc.lumen$startUseItem();
				clicks++;
			}
		} else {
			rightBudget = 0;
		}
	}
}
