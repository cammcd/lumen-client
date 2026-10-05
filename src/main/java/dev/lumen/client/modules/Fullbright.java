package dev.lumen.client.modules;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;

/**
 * Lights everything up by giving you night vision on your own client: endless, with no
 * particles or icon, so the game's own lighting does the rest. The server never knows.
 */
public final class Fullbright extends Module {
	public Fullbright() {
		super("Fullbright", "Lights up caves and nights as if it were day, using night vision only your client knows about.", Category.RENDER);
	}

	@Override
	public void onTick() {
		LocalPlayer p = MC.player;
		// Effects are cleared on death and dimension change, so put it back whenever it is gone.
		if (p != null && !p.hasEffect(MobEffects.NIGHT_VISION)) {
			p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
		}
	}

	@Override
	protected void onDisable() {
		LocalPlayer p = MC.player;
		// Only take away our own: a real night vision potion from the server stays.
		if (p != null && isOurs(p.getEffect(MobEffects.NIGHT_VISION))) p.removeEffect(MobEffects.NIGHT_VISION);
	}

	private static boolean isOurs(MobEffectInstance effect) {
		return effect != null && effect.isInfiniteDuration() && !effect.isVisible() && !effect.showIcon();
	}
}
