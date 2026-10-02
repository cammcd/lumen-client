package dev.lumen.client.modules;

import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import dev.lumen.client.Lumen;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;

/** Attacks the best target in range whenever your attack is charged. */
public final class KillAura extends CombatModule {
	private final NumberSetting cooldown = add(new NumberSetting("Cooldown", "How charged your attack must be. 100% deals full damage.", 100, 0, 100, 5, "%"));
	private final BoolSetting onlyWeapon = add(new BoolSetting("Only with weapon", "Only attack while holding a sword, axe, mace, trident or spear.", false));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Turn to face the target.", false));
	private final NumberSetting rotateSpeed = add(new NumberSetting("Rotate speed", "Degrees turned per tick.", 35, 5, 180, 5, "°"))
			.visibleWhen(rotate::isOn);
	private final BoolSetting pauseUsing = add(new BoolSetting("Pause while using items", "Hold off while eating, drinking, blocking or drawing a bow.", true));
	private final BoolSetting pauseInMenus = add(new BoolSetting("Pause in menus", "Hold off while a menu is open.", true));

	private LivingEntity target;
	private int hits;
	private int crits;

	public KillAura() {
		super("Kill Aura", "Attacks nearby targets automatically whenever your attack is charged.", 3.5, 6);
	}

	@Override
	protected void onDisable() {
		target = null;
	}

	public LivingEntity target() {
		return target;
	}

	/** Hits landed since the module was loaded; used by the game test. */
	public int hits() {
		return hits;
	}

	@Override
	public String hudInfo() {
		return target != null ? target.getName().getString() : "";
	}

	@Override
	public void onTick() {
		target = null;
		LocalPlayer p = MC.player;
		if (p == null || MC.gameMode == null) return;
		if (pauseInMenus.isOn() && MC.gui.screen() != null) return;
		if (pauseUsing.isOn() && p.isUsingItem()) return;
		if (onlyWeapon.isOn() && !isWeapon(p.getMainHandItem())) return;

		List<LivingEntity> found = targets();
		if (found.isEmpty()) return;
		target = found.get(0);

		if (rotate.isOn()) turnToward(target.getBoundingBox().getCenter(), rotateSpeed.getFloat());
		if (!cooldownReady(cooldown.get() / 100.0)) return;

		Criticals criticals = Lumen.modules().criticals;
		if (criticals.isEnabled() && !criticals.prepare()) return;

		if (criticals.critNow()) crits++;
		// The same two calls vanilla makes for a left click; attack() also resets the cooldown.
		MC.gameMode.attack(p, target);
		p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
		hits++;
	}

	/** Hits that landed as critical hits; used by the game test. */
	public int crits() {
		return crits;
	}

	static boolean isWeapon(ItemStack stack) {
		if (stack.isEmpty()) return false;
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		return path.endsWith("_sword") || path.endsWith("_axe") || path.endsWith("_spear")
				|| path.equals("mace") || path.equals("trident");
	}
}
