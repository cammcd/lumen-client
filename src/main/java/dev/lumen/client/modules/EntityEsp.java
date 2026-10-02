package dev.lumen.client.modules;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.render.EspBatch;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;

public final class EntityEsp extends HighlightModule {
	private final ColorSetting players = add(new ColorSetting("Players", "Other players.", 0xFFFF5D73, true));
	private final ColorSetting hostile = add(new ColorSetting("Hostile mobs", "Monsters: zombies, creepers, skeletons and the like.", 0xFFFFA23A, true));
	private final ColorSetting passive = add(new ColorSetting("Passive mobs", "Animals, fish, bats and other peaceful creatures.", 0xFF6BE38B, true));
	private final ColorSetting items = add(new ColorSetting("Items", "Dropped items on the ground.", 0xFFFFE066, true));
	private final ColorSetting other = add(new ColorSetting("Other", "Villagers, golems, armor stands and other creatures.", 0xFF7FD4FF, false));
	private final ColorSetting pets = add(new ColorSetting("Pets", "Tamed wolves, cats, parrots and horses: someone lives nearby.", 0xFFFF8AD8, true));
	private final ColorSetting named = add(new ColorSetting("Named mobs", "Mobs given a name tag.", 0xFFB388FF, true));
	private final ColorSetting itemFrames = add(new ColorSetting("Item frames", "Item frames and glow item frames.", 0xFFFFC857, true));
	private final ColorSetting storageVehicles = add(new ColorSetting("Storage carts & boats", "Chest and hopper minecarts, chest boats and rafts.", 0xFF4DD0E1, true));
	private final BoolSetting showInvisible = add(new BoolSetting("Show invisible", "Also highlight entities that are invisible.", false));

	private int lastCount;

	public EntityEsp() {
		super("Entity ESP", "Highlights players, mobs, items, pets, item frames and storage carts.", 128, false, "Entities");
	}

	@Override
	protected void onDisable() {
		lastCount = 0;
	}

	/** Number of entities highlighted in the most recent frame. */
	public int count() {
		return lastCount;
	}

	@Override
	public String hudInfo() {
		return Integer.toString(lastCount);
	}

	private ColorSetting classify(Entity entity) {
		if (entity instanceof Player) return players;
		if (entity instanceof ItemEntity) return items;
		// Base-hunting groups come before the general ones.
		if (entity instanceof ItemFrame) return itemFrames;
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
		if (id.equals("chest_minecart") || id.equals("hopper_minecart") || id.endsWith("_chest_boat")
				|| id.endsWith("_chest_raft")) {
			return storageVehicles;
		}
		if (entity instanceof TamableAnimal tamable && tamable.isTame()) return pets;
		if (entity instanceof AbstractHorse horse && horse.isTamed()) return pets;
		if (entity instanceof LivingEntity && entity.hasCustomName()) return named;
		if (!(entity instanceof LivingEntity)) return null;
		if (entity instanceof Enemy) return hostile;

		MobCategory category = entity.getType().getCategory();
		if (category == MobCategory.MONSTER) return hostile;
		if (category == MobCategory.MISC) return other;
		return passive;
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
		if (MC.level == null || MC.player == null) return;

		float partialTick = MC.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Style style = frameStyle();
		int count = 0;

		for (Entity entity : MC.level.entitiesForRendering()) {
			if (entity == MC.player) continue;

			ColorSetting setting = classify(entity);
			if (setting == null || !setting.isEnabled()) continue;
			if (entity.isInvisible() && !showInvisible.isOn()) continue;

			AABB box = lerpedBox(entity, partialTick);
			if (draw(batch, cam, style, box, setting.color(rainbowOffset(box.getCenter())))) count++;
		}

		lastCount = count;
	}

	/** The entity's box at its interpolated position, so highlights move smoothly between ticks. */
	private static AABB lerpedBox(Entity entity, float partialTick) {
		if (entity.isRemoved()) return entity.getBoundingBox();
		double x = entity.xOld + (entity.getX() - entity.xOld) * partialTick;
		double y = entity.yOld + (entity.getY() - entity.yOld) * partialTick;
		double z = entity.zOld + (entity.getZ() - entity.zOld) * partialTick;
		return entity.getBoundingBox().move(x - entity.getX(), y - entity.getY(), z - entity.getZ());
	}
}
