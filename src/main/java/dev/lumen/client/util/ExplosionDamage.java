package dev.lumen.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Estimates explosion damage the way the game computes it: distance and exposure,
 * then difficulty for players, armor and Resistance. Blast Protection and other
 * enchantments are left out, so estimates run high, which errs safe for self-damage.
 */
public final class ExplosionDamage {
	private static final Minecraft MC = Minecraft.getInstance();

	/** An end crystal explodes with power 6, a respawn anchor with power 5. */
	public static final float CRYSTAL = 6f;
	public static final float ANCHOR = 5f;

	private ExplosionDamage() {
	}

	/**
	 * Damage an explosion of this power at this point would deal to an entity.
	 * {@code ignore} is a block destroyed by the explosion itself, such as the anchor,
	 * which should not shield anything; it may be null.
	 */
	public static float estimate(LivingEntity entity, Vec3 center, float power, BlockPos ignore) {
		double radius = power * 2.0;
		double distance = Math.sqrt(entity.distanceToSqr(center)) / radius;
		if (distance > 1.0) return 0f;

		double impact = (1.0 - distance) * seenPercent(center, entity, ignore);
		float damage = (float) ((impact * impact + impact) / 2.0 * 7.0 * radius + 1.0);

		if (entity instanceof Player) {
			Difficulty difficulty = MC.level.getDifficulty();
			if (difficulty == Difficulty.PEACEFUL) damage = 0f;
			else if (difficulty == Difficulty.EASY) damage = Math.min(damage / 2f + 1f, damage);
			else if (difficulty == Difficulty.HARD) damage = damage * 3f / 2f;
		}

		float armor = entity.getArmorValue();
		float toughness = (float) entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
		float f = 2f + toughness / 4f;
		float g = Mth.clamp(armor - damage / f, armor * 0.2f, 20f);
		damage *= 1f - g / 25f;

		MobEffectInstance resistance = entity.getEffect(MobEffects.RESISTANCE);
		if (resistance != null) damage *= Math.max(0f, 1f - (resistance.getAmplifier() + 1) * 0.2f);
		return Math.max(0f, damage);
	}

	/** Fraction of rays from points on the entity's hitbox that reach the explosion unblocked. */
	static double seenPercent(Vec3 center, LivingEntity entity, BlockPos ignore) {
		AABB box = entity.getBoundingBox();
		double dx = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
		double dy = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
		double dz = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
		double ox = (1.0 - Math.floor(1.0 / dx) * dx) / 2.0;
		double oz = (1.0 - Math.floor(1.0 / dz) * dz) / 2.0;
		if (dx < 0 || dy < 0 || dz < 0) return 0;

		int seen = 0;
		int total = 0;
		for (double a = 0; a <= 1; a += dx) {
			for (double b = 0; b <= 1; b += dy) {
				for (double c = 0; c <= 1; c += dz) {
					Vec3 from = new Vec3(Mth.lerp(a, box.minX, box.maxX) + ox, Mth.lerp(b, box.minY, box.maxY), Mth.lerp(c, box.minZ, box.maxZ) + oz);
					BlockHitResult hit = MC.level.clip(new ClipContext(from, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
					if (hit.getType() == HitResult.Type.MISS || (ignore != null && hit.getBlockPos().equals(ignore))) seen++;
					total++;
				}
			}
		}
		return total == 0 ? 0 : (double) seen / total;
	}

	/** True if taking this much damage stays within the limit and does not kill the player. */
	public static boolean safe(float selfDamage, float maxSelf, boolean antiSuicide) {
		Player p = MC.player;
		if (selfDamage > maxSelf) return false;
		return !antiSuicide || selfDamage < p.getHealth() + p.getAbsorptionAmount();
	}
}
