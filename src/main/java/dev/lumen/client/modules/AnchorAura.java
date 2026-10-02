package dev.lumen.client.modules;

import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ExplosionDamage;
import dev.lumen.client.util.Placement;

/**
 * Places a respawn anchor next to a target, charges it with glowstone and sets it off,
 * which outside the Nether makes it explode. One step per tick.
 */
public final class AnchorAura extends CombatModule {
	private final NumberSetting placeRange = add(new NumberSetting("Place range", "How far away an anchor can be placed or used.", 4.5, 1, 6, 0.1, "m"));
	private final NumberSetting minDamage = add(new NumberSetting("Min damage", "Least damage to the target worth an anchor.", 6, 0, 36, 0.5));
	private final NumberSetting maxSelf = add(new NumberSetting("Max self damage", "Most damage you will take from one anchor.", 8, 0, 36, 0.5));
	private final BoolSetting antiSuicide = add(new BoolSetting("Anti suicide", "Never set off an anchor that would kill you.", true));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between steps.", 1, 0, 20, 1, "t"));

	private LivingEntity target;
	private int timer;
	private int placed;
	private int detonated;

	public AnchorAura() {
		super("Anchor Aura", "Places, charges and sets off respawn anchors next to targets outside the Nether.", 8, 16);
	}

	public int placed() {
		return placed;
	}

	public int detonated() {
		return detonated;
	}

	@Override
	public String hudInfo() {
		if (MC.level != null && MC.level.dimension() == Level.NETHER) return "Nether";
		return target != null ? target.getName().getString() : "";
	}

	@Override
	public void onTick() {
		target = null;
		LocalPlayer p = MC.player;
		if (p == null || MC.level == null || MC.gameMode == null || MC.gui.screen() != null) return;
		// In the Nether an anchor sets your spawn instead of exploding.
		if (MC.level.dimension() == Level.NETHER || p.isUsingItem()) return;

		List<LivingEntity> found = targets();
		if (found.isEmpty()) return;
		target = found.get(0);
		if (timer > 0) {
			timer--;
			return;
		}

		if (useExistingAnchor(p) || placeAnchor(p)) timer = delay.getInt();
	}

	/** Sets off a charged anchor in a good spot, or charges an empty one. */
	private boolean useExistingAnchor(LocalPlayer p) {
		double reach = placeRange.get();
		int r = (int) Math.ceil(reach);
		Vec3 eye = p.getEyePosition();
		BlockPos origin = p.blockPosition();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos pos = origin.offset(dx, dy, dz);
					BlockState state = MC.level.getBlockState(pos);
					if (!state.is(Blocks.RESPAWN_ANCHOR)) continue;
					if (eye.distanceTo(Vec3.atCenterOf(pos)) > reach || !worthIt(p, pos)) continue;

					if (state.getValue(RespawnAnchorBlock.CHARGE) > 0) {
						int slot = detonateSlot(p.getInventory());
						if (slot < 0) return false;
						Placement.click(slot, InteractionHand.MAIN_HAND, Placement.topFace(pos));
						detonated++;
					} else {
						int slot = Placement.hotbarSlot(stack -> stack.is(Items.GLOWSTONE));
						if (slot < 0) return false;
						Placement.click(slot, InteractionHand.MAIN_HAND, Placement.topFace(pos));
					}
					return true;
				}
			}
		}
		return false;
	}

	private boolean placeAnchor(LocalPlayer p) {
		int slot = Placement.hotbarSlot(stack -> stack.is(Items.RESPAWN_ANCHOR));
		if (slot < 0 || Placement.hotbarSlot(stack -> stack.is(Items.GLOWSTONE)) < 0) return false;

		double reach = placeRange.get();
		int r = (int) Math.ceil(reach);
		Vec3 eye = p.getEyePosition();
		BlockPos origin = p.blockPosition();
		BlockPos best = null;
		float bestDamage = 0f;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos pos = origin.offset(dx, dy, dz);
					if (!MC.level.getBlockState(pos).canBeReplaced()) continue;
					if (eye.distanceTo(Vec3.atCenterOf(pos)) > reach || !hasSupport(pos) || occupied(pos)) continue;
					Vec3 center = Vec3.atCenterOf(pos);
					float self = ExplosionDamage.estimate(p, center, ExplosionDamage.ANCHOR, pos);
					if (!ExplosionDamage.safe(self, maxSelf.getFloat(), antiSuicide.isOn())) continue;
					float damage = ExplosionDamage.estimate(target, center, ExplosionDamage.ANCHOR, pos);
					if (damage >= minDamage.get() && damage > bestDamage) {
						best = pos;
						bestDamage = damage;
					}
				}
			}
		}
		if (best == null) return false;
		if (!Placement.place(best, slot, reach + 0.5)) return false;
		placed++;
		return true;
	}

	private boolean worthIt(LocalPlayer p, BlockPos pos) {
		Vec3 center = Vec3.atCenterOf(pos);
		float self = ExplosionDamage.estimate(p, center, ExplosionDamage.ANCHOR, pos);
		if (!ExplosionDamage.safe(self, maxSelf.getFloat(), antiSuicide.isOn())) return false;
		return ExplosionDamage.estimate(target, center, ExplosionDamage.ANCHOR, pos) >= minDamage.get();
	}

	private static boolean hasSupport(BlockPos pos) {
		for (Direction dir : Direction.values()) {
			if (Placement.isSupport(pos.relative(dir))) return true;
		}
		return false;
	}

	/** True if any entity stands in the block space, which would stop the anchor being placed. */
	private static boolean occupied(BlockPos pos) {
		return !MC.level.getEntities((Entity) null, new AABB(pos)).isEmpty();
	}

	/** A hotbar slot holding anything but glowstone, since glowstone would charge instead of detonate. */
	private static int detonateSlot(Inventory inv) {
		if (!inv.getItem(inv.getSelectedSlot()).is(Items.GLOWSTONE)) return inv.getSelectedSlot();
		for (int i = 0; i < 9; i++) {
			if (!inv.getItem(i).is(Items.GLOWSTONE)) return i;
		}
		return -1;
	}
}
