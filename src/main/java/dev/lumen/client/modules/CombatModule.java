package dev.lumen.client.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.EnumSetting;
import dev.lumen.client.setting.NumberSetting;

/**
 * Shared targeting for the combat modules: which entities count, how far, and which
 * one goes first. Subclass settings are listed above the shared "Targets" section.
 */
public abstract class CombatModule extends Module {
	public enum Priority {
		CLOSEST("Closest"),
		HEALTH("Lowest health"),
		ANGLE("Nearest crosshair");

		private final String label;

		Priority(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	protected final NumberSetting range;
	protected final BoolSetting players;
	protected final BoolSetting hostiles;
	protected final BoolSetting passives;
	protected final BoolSetting ignoreNamed;
	protected final BoolSetting ignoreInvisible;
	protected final BoolSetting throughWalls;
	protected final EnumSetting<Priority> priority;

	protected CombatModule(String name, String description, double defaultRange, double maxRange) {
		this(name, description, defaultRange, maxRange, false);
	}

	protected CombatModule(String name, String description, double defaultRange, double maxRange, boolean defaultThroughWalls) {
		super(name, description, Category.COMBAT);
		setDefaultSection("Targets");
		range = add(new NumberSetting("Range", "Maximum distance to a target, in blocks.", defaultRange, 1, maxRange, 0.1, "m"));
		players = add(new BoolSetting("Players", "Target other players. Creative and spectator players are skipped.", true));
		hostiles = add(new BoolSetting("Hostile mobs", "Target monsters.", true));
		passives = add(new BoolSetting("Passive mobs", "Target animals, villagers and other peaceful mobs.", false));
		ignoreNamed = add(new BoolSetting("Ignore named mobs", "Leave mobs with a name tag alone.", true));
		ignoreInvisible = add(new BoolSetting("Ignore invisible", "Skip invisible entities.", false));
		throughWalls = add(new BoolSetting("Through walls", "Allow targets you cannot see.", defaultThroughWalls));
		priority = add(new EnumSetting<>("Priority", "Which target goes first.", Priority.CLOSEST));

		setDefaultSection("General");
		insertFutureSettingsAtTop();
	}

	/** Whether an entity passes the target filters, ignoring distance. */
	protected boolean isTarget(Entity entity) {
		LocalPlayer self = MC.player;
		if (self == null || entity == self || !(entity instanceof LivingEntity living)) return false;
		if (!living.isAlive() || living.isDeadOrDying()) return false;
		if (ignoreInvisible.isOn() && entity.isInvisible()) return false;

		if (entity instanceof Player player) {
			return players.isOn() && !player.isCreative() && !player.isSpectator();
		}
		if (!(entity instanceof Mob)) return false;
		if (ignoreNamed.isOn() && entity.hasCustomName()) return false;
		if (entity instanceof TamableAnimal pet && pet.isTame()) return false;
		return entity instanceof Enemy ? hostiles.isOn() : passives.isOn();
	}

	/** All valid targets in range, best first. */
	protected List<LivingEntity> targets() {
		List<LivingEntity> found = new ArrayList<>();
		if (MC.level == null || MC.player == null) return found;
		double max = range.get();
		for (Entity entity : MC.level.entitiesForRendering()) {
			if (!isTarget(entity)) continue;
			if (distanceToBox(entity) > max) continue;
			if (!throughWalls.isOn() && !MC.player.hasLineOfSight(entity)) continue;
			found.add((LivingEntity) entity);
		}
		Comparator<LivingEntity> order = switch (priority.get()) {
			case HEALTH -> Comparator.comparingDouble(e -> e.getHealth() + e.getAbsorptionAmount());
			case ANGLE -> Comparator.comparingDouble(CombatModule::angleTo);
			default -> Comparator.comparingDouble(CombatModule::distanceToBox);
		};
		found.sort(order);
		return found;
	}

	/** Distance from the player's eyes to the nearest point of an entity's hitbox, as the server measures reach. */
	protected static double distanceToBox(Entity entity) {
		Vec3 eye = MC.player.getEyePosition();
		var box = entity.getBoundingBox();
		double x = Mth.clamp(eye.x, box.minX, box.maxX);
		double y = Mth.clamp(eye.y, box.minY, box.maxY);
		double z = Mth.clamp(eye.z, box.minZ, box.maxZ);
		return eye.distanceTo(new Vec3(x, y, z));
	}

	/** The yaw and pitch that look from the player's eyes at a point. */
	protected static float[] rotationsTo(Vec3 point) {
		Vec3 eye = MC.player.getEyePosition();
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double flat = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
		return new float[] {yaw, pitch};
	}

	/** Angle in degrees between where the player looks and an entity's centre. */
	protected static double angleTo(Entity entity) {
		float[] rot = rotationsTo(entity.getBoundingBox().getCenter());
		float dyaw = Math.abs(Mth.wrapDegrees(rot[0] - MC.player.getYRot()));
		float dpitch = Math.abs(rot[1] - MC.player.getXRot());
		return Math.sqrt(dyaw * dyaw + dpitch * dpitch);
	}

	/** Turns the player toward a point by at most {@code maxStep} degrees on each axis. */
	protected static void turnToward(Vec3 point, float maxStep) {
		float[] rot = rotationsTo(point);
		float dyaw = Mth.wrapDegrees(rot[0] - MC.player.getYRot());
		float dpitch = rot[1] - MC.player.getXRot();
		MC.player.setYRot(MC.player.getYRot() + Mth.clamp(dyaw, -maxStep, maxStep));
		MC.player.setXRot(Mth.clamp(MC.player.getXRot() + Mth.clamp(dpitch, -maxStep, maxStep), -90f, 90f));
	}

	/** True when the attack cooldown has recovered to at least this fraction. */
	protected static boolean cooldownReady(double fraction) {
		return MC.player.getAttackStrengthScale(0.5f) >= fraction;
	}
}
