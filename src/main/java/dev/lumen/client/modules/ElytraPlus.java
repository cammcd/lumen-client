package dev.lumen.client.modules;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.EnumSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.Placement;

/** Cruise control for elytra flight: holds a pitch or an altitude and fires rockets to keep speed up. */
public final class ElytraPlus extends Module {
	public enum Mode {
		CRUISE("Cruise"),
		ALTITUDE("Hold altitude");

		private final String label;

		Mode(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Hold a fixed pitch, or steer pitch to hold an altitude.", Mode.CRUISE));
	private final NumberSetting pitch = add(new NumberSetting("Pitch", "Pitch held while cruising. Positive looks down.", 4, -30, 30, 0.5, "°"))
			.visibleWhen(() -> mode.is(Mode.CRUISE));
	private final NumberSetting altitude = add(new NumberSetting("Altitude", "Height to hold.", 160, -64, 320, 1))
			.visibleWhen(() -> mode.is(Mode.ALTITUDE));
	private final NumberSetting turnSpeed = add(new NumberSetting("Turn speed", "Most degrees of pitch changed per tick.", 3, 0.5, 15, 0.5, "°"));
	private final BoolSetting autoFirework = add(new BoolSetting("Auto firework", "Fire a rocket from your hotbar or offhand when speed drops.", true));
	private final NumberSetting minSpeed = add(new NumberSetting("Min speed", "Speed in blocks per second below which a rocket is fired.", 18, 5, 40, 1, "m/s"))
			.visibleWhen(autoFirework::isOn);
	private final NumberSetting cooldown = add(new NumberSetting("Rocket cooldown", "Least time between rockets.", 2.5, 0.5, 10, 0.5, "s"))
			.visibleWhen(autoFirework::isOn);

	private int cooldownTicks;
	private int rockets;

	public ElytraPlus() {
		super("Elytra+", "Holds your pitch or altitude while gliding and fires rockets to keep your speed up.", Category.PLAYER);
	}

	/** Rockets fired since the module was loaded; used by the game test. */
	public int rockets() {
		return rockets;
	}

	@Override
	public String hudInfo() {
		if (MC.player == null || !MC.player.isFallFlying()) return "";
		return Math.round(MC.player.getDeltaMovement().length() * 20) + " m/s";
	}

	@Override
	public void onTick() {
		LocalPlayer p = MC.player;
		if (cooldownTicks > 0) cooldownTicks--;
		if (p == null || !p.isFallFlying() || MC.gui.screen() != null) return;

		float target = mode.is(Mode.CRUISE)
				? pitch.getFloat()
				// Below the target height, look up to climb; above it, look down.
				: Mth.clamp((float) (p.getY() - altitude.get()) * 2f, -30f, 25f);
		float step = turnSpeed.getFloat();
		p.setXRot(p.getXRot() + Mth.clamp(target - p.getXRot(), -step, step));

		if (autoFirework.isOn() && cooldownTicks == 0 && p.getDeltaMovement().length() * 20 < minSpeed.get()) {
			if (fireRocket(p)) {
				rockets++;
				cooldownTicks = (int) Math.round(cooldown.get() * 20);
			}
		}
	}

	/** Uses a rocket from the offhand, or from the hotbar by switching to it and back. */
	private boolean fireRocket(LocalPlayer p) {
		InteractionHand hand = InteractionHand.MAIN_HAND;
		int slot = -1;
		if (p.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
			hand = InteractionHand.OFF_HAND;
		} else {
			slot = Placement.hotbarSlot(stack -> stack.is(Items.FIREWORK_ROCKET));
			if (slot < 0) return false;
		}

		Inventory inv = p.getInventory();
		int previous = inv.getSelectedSlot();
		boolean swap = hand == InteractionHand.MAIN_HAND && slot != previous;
		if (swap) inv.setSelectedSlot(slot);

		SwingAnimation animation = p.getItemInHand(hand).getInteractAnimation();
		InteractionResult result = MC.gameMode.useItem(p, hand);
		if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
			p.swing(hand, animation, false);
		}

		if (swap) inv.setSelectedSlot(previous);
		return result.consumesAction();
	}
}
