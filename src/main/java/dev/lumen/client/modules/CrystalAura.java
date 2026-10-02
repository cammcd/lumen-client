package dev.lumen.client.modules;

import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ExplosionDamage;
import dev.lumen.client.util.Placement;

/**
 * Places end crystals on obsidian or bedrock near a target and breaks them, choosing
 * spots that hurt the target most while keeping your own damage under a limit.
 */
public final class CrystalAura extends CombatModule {
	private final BoolSetting doPlace = add(new BoolSetting("Place", "Place crystals.", true));
	private final BoolSetting doBreak = add(new BoolSetting("Break", "Break crystals, yours or anyone's.", true));
	private final NumberSetting placeRange = add(new NumberSetting("Place range", "How far away a crystal can be placed.", 4.5, 1, 6, 0.1, "m"));
	private final NumberSetting breakRange = add(new NumberSetting("Break range", "How far away a crystal can be broken.", 4.5, 1, 6, 0.1, "m"));
	private final NumberSetting minDamage = add(new NumberSetting("Min damage", "Least damage to the target worth a crystal.", 6, 0, 36, 0.5));
	private final NumberSetting maxSelf = add(new NumberSetting("Max self damage", "Most damage you will take from one crystal.", 8, 0, 36, 0.5));
	private final BoolSetting antiSuicide = add(new BoolSetting("Anti suicide", "Never place or break a crystal that would kill you.", true));
	private final NumberSetting placeDelay = add(new NumberSetting("Place delay", "Ticks between placements.", 1, 0, 20, 1, "t"));
	private final NumberSetting breakDelay = add(new NumberSetting("Break delay", "Ticks between breaks.", 1, 0, 20, 1, "t"));
	private final BoolSetting pauseUsing = add(new BoolSetting("Pause while using items", "Hold off while eating or drinking.", true));

	private LivingEntity target;
	private int placeTimer;
	private int breakTimer;
	private int placed;
	private int broken;

	public CrystalAura() {
		super("Crystal Aura", "Places and breaks end crystals near targets, keeping your own damage under a limit.", 10, 16);
	}

	public int placed() {
		return placed;
	}

	public int broken() {
		return broken;
	}

	@Override
	public String hudInfo() {
		return target != null ? target.getName().getString() : "";
	}

	@Override
	public void onTick() {
		target = null;
		LocalPlayer p = MC.player;
		if (p == null || MC.level == null || MC.gameMode == null || MC.gui.screen() != null) return;
		if (pauseUsing.isOn() && p.isUsingItem()) return;

		List<LivingEntity> found = targets();
		if (found.isEmpty()) return;
		target = found.get(0);

		if (breakTimer > 0) breakTimer--;
		if (placeTimer > 0) placeTimer--;
		if (doBreak.isOn() && breakTimer == 0 && breakBest(p)) {
			breakTimer = breakDelay.getInt();
			return;
		}
		if (doPlace.isOn() && placeTimer == 0 && placeBest(p)) {
			placeTimer = placeDelay.getInt();
		}
	}

	private boolean breakBest(LocalPlayer p) {
		EndCrystal best = null;
		float bestDamage = 0f;
		for (Entity entity : MC.level.entitiesForRendering()) {
			if (!(entity instanceof EndCrystal crystal) || !crystal.isAlive()) continue;
			if (distanceToBox(crystal) > breakRange.get()) continue;
			Vec3 at = crystal.position();
			float self = ExplosionDamage.estimate(p, at, ExplosionDamage.CRYSTAL, null);
			if (!ExplosionDamage.safe(self, maxSelf.getFloat(), antiSuicide.isOn())) continue;
			float damage = ExplosionDamage.estimate(target, at, ExplosionDamage.CRYSTAL, null);
			if (damage >= minDamage.get() && damage > bestDamage) {
				best = crystal;
				bestDamage = damage;
			}
		}
		if (best == null) return false;
		MC.gameMode.attack(p, best);
		p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
		broken++;
		return true;
	}

	private boolean placeBest(LocalPlayer p) {
		boolean offhand = p.getOffhandItem().is(Items.END_CRYSTAL);
		int slot = offhand ? -1 : Placement.hotbarSlot(stack -> stack.is(Items.END_CRYSTAL));
		if (!offhand && slot < 0) return false;

		double reach = placeRange.get();
		int r = (int) Math.ceil(reach);
		Vec3 eye = p.getEyePosition();
		BlockPos origin = p.blockPosition();
		BlockPos best = null;
		float bestDamage = 0f;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos base = origin.offset(dx, dy, dz);
					if (!canHoldCrystal(base)) continue;
					Vec3 top = new Vec3(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
					if (eye.distanceTo(top) > reach) continue;
					float self = ExplosionDamage.estimate(p, top, ExplosionDamage.CRYSTAL, null);
					if (!ExplosionDamage.safe(self, maxSelf.getFloat(), antiSuicide.isOn())) continue;
					float damage = ExplosionDamage.estimate(target, top, ExplosionDamage.CRYSTAL, null);
					if (damage >= minDamage.get() && damage > bestDamage) {
						best = base;
						bestDamage = damage;
					}
				}
			}
		}
		if (best == null) return false;
		Placement.click(slot, offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, Placement.topFace(best));
		placed++;
		return true;
	}

	/** Obsidian or bedrock with an empty block above and nothing standing in the crystal's space. */
	private static boolean canHoldCrystal(BlockPos base) {
		BlockState state = MC.level.getBlockState(base);
		if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) return false;
		BlockPos above = base.above();
		if (!MC.level.getBlockState(above).isAir()) return false;
		AABB space = new AABB(above.getX(), above.getY(), above.getZ(), above.getX() + 1, above.getY() + 2, above.getZ() + 1);
		return MC.level.getEntities((Entity) null, space).isEmpty();
	}
}
