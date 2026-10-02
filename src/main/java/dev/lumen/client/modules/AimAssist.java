package dev.lumen.client.modules;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;

/** Gently pulls your aim toward the nearest target in front of you. */
public final class AimAssist extends CombatModule {
	private final NumberSetting speed = add(new NumberSetting("Speed", "Degrees turned per tick.", 4, 0.5, 30, 0.5, "°"));
	private final NumberSetting fov = add(new NumberSetting("FOV", "Only targets within this cone in front of you.", 70, 10, 360, 5, "°"));
	private final BoolSetting onlyClicking = add(new BoolSetting("Only while clicking", "Only assist while you hold attack.", true));
	private final BoolSetting vertical = add(new BoolSetting("Vertical", "Also adjust pitch, not just yaw.", true));

	private LivingEntity target;

	public AimAssist() {
		super("Aim Assist", "Smoothly turns your aim toward the nearest target in front of you.", 5, 8);
	}

	public LivingEntity target() {
		return target;
	}

	@Override
	public void onTick() {
		target = null;
		if (MC.player == null || MC.gui.screen() != null) return;
		if (onlyClicking.isOn() && !MC.options.keyAttack.isDown()) return;

		double half = fov.get() / 2.0;
		for (LivingEntity candidate : targets()) {
			if (angleTo(candidate) <= half) {
				target = candidate;
				break;
			}
		}
		if (target == null) return;

		Vec3 aim = target.getBoundingBox().getCenter();
		float step = speed.getFloat();
		if (vertical.isOn()) {
			turnToward(aim, step);
		} else {
			float[] rot = rotationsTo(aim);
			float dyaw = Mth.wrapDegrees(rot[0] - MC.player.getYRot());
			MC.player.setYRot(MC.player.getYRot() + Mth.clamp(dyaw, -step, step));
		}
	}
}
