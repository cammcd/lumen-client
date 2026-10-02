package dev.lumen.client.modules;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;

import dev.lumen.client.Lumen;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;

/** Attacks whatever valid target is under your crosshair once your attack is charged. */
public final class TriggerBot extends CombatModule {
	private final NumberSetting cooldown = add(new NumberSetting("Cooldown", "How charged your attack must be. 100% deals full damage.", 100, 0, 100, 5, "%"));
	private final NumberSetting delay = add(new NumberSetting("Reaction delay", "Ticks a target must be under the crosshair before the first hit.", 2, 0, 10, 1, "t"));
	private final BoolSetting onlyWeapon = add(new BoolSetting("Only with weapon", "Only attack while holding a sword, axe, mace, trident or spear.", false));

	private Entity aimed;
	private int aimedTicks;
	private int hits;

	public TriggerBot() {
		super("Trigger Bot", "Hits the target under your crosshair as soon as your attack is charged.", 4.5, 6);
	}

	public int hits() {
		return hits;
	}

	@Override
	public void onTick() {
		LocalPlayer p = MC.player;
		if (p == null || MC.gameMode == null || MC.gui.screen() != null || p.isUsingItem()) {
			aimed = null;
			return;
		}
		if (onlyWeapon.isOn() && !KillAura.isWeapon(p.getMainHandItem())) return;

		Entity entity = MC.hitResult instanceof EntityHitResult hit ? hit.getEntity() : null;
		if (entity == null || !isTarget(entity) || distanceToBox(entity) > range.get()) {
			aimed = null;
			return;
		}
		if (entity != aimed) {
			aimed = entity;
			aimedTicks = 0;
		}
		if (aimedTicks++ < delay.getInt()) return;
		if (!cooldownReady(cooldown.get() / 100.0)) return;

		Criticals criticals = Lumen.modules().criticals;
		if (criticals.isEnabled() && !criticals.prepare()) return;

		MC.gameMode.attack(p, entity);
		p.swingAndResetAttackStrength(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
		hits++;
	}
}
