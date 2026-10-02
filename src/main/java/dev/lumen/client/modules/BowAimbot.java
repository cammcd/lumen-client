package dev.lumen.client.modules;

import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.setting.BoolSetting;

/** While you draw a bow, aims at the best target with arrow drop and target movement allowed for. */
public final class BowAimbot extends CombatModule {
	/** Arrow drop per tick squared, as fitted to vanilla arrows at the speeds a bow fires. */
	private static final double GRAVITY = 0.006;

	private final BoolSetting predict = add(new BoolSetting("Predict movement", "Lead moving targets.", true));

	private LivingEntity target;

	public BowAimbot() {
		super("Bow Aimbot", "Aims your bow at the best target while you draw it, allowing for arrow drop.", 48, 120);
	}

	public LivingEntity target() {
		return target;
	}

	@Override
	public void onTick() {
		target = null;
		LocalPlayer p = MC.player;
		if (p == null || !p.isUsingItem() || !p.getUseItem().is(Items.BOW)) return;

		List<LivingEntity> found = targets();
		if (found.isEmpty()) return;
		target = found.get(0);

		// Bow power from draw time, as the bow itself computes it.
		float power = p.getTicksUsingItem() / 20f;
		power = (power * power + power * 2) / 3;
		if (power < 0.1f) return;
		if (power > 1f) power = 1f;

		float[] rot = aim(p.getEyePosition(), target, power, predict.isOn());
		p.setYRot(rot[0]);
		p.setXRot(rot[1]);
	}

	/** Yaw and pitch that land an arrow of this power on the target. */
	static float[] aim(Vec3 eye, LivingEntity target, float power, boolean predict) {
		Vec3 center = target.getBoundingBox().getCenter();
		if (predict) {
			double ticks = eye.distanceTo(center) / (power * 3.0);
			Vec3 motion = target.getDeltaMovement();
			center = center.add(motion.x * ticks, 0, motion.z * ticks);
		}
		double dx = center.x - eye.x;
		double dy = center.y - eye.y;
		double dz = center.z - eye.z;
		double flat = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;

		double v2 = power * power;
		double root = v2 * v2 - GRAVITY * (GRAVITY * flat * flat + 2 * dy * v2);
		float pitch = root < 0
				? -45f // out of range: 45 degrees carries furthest
				: (float) -Math.toDegrees(Math.atan((v2 - Math.sqrt(root)) / (GRAVITY * flat)));
		return new float[] {yaw, pitch};
	}
}
