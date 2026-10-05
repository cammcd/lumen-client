package dev.lumen.client.modules;

import net.minecraft.client.Camera;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.Lumen;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.render.CameraUtil;
import dev.lumen.client.render.EspBatch;
import dev.lumen.client.render.WorldRenderable;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ColorUtil;

/**
 * Detaches the camera from the player. Movement keys and the mouse fly the camera
 * through blocks while the player stays where it is. Only the view moves; nothing
 * about the player's position changes on the server.
 */
public final class Freecam extends Module implements WorldRenderable {
	public final NumberSetting speed = add(new NumberSetting("Speed", "Flying speed in blocks per tick. Scroll to change it while flying.", 1.0, 0.1, 10, 0.1, "x"));
	public final NumberSetting verticalSpeed = add(new NumberSetting("Vertical speed", "Up and down speed, relative to Speed.", 1.0, 0.1, 3, 0.1, "x"));
	public final NumberSetting sprintBoost = add(new NumberSetting("Sprint boost", "Speed multiplier while the sprint key is held.", 2.0, 1, 5, 0.1, "x"));
	public final BoolSetting smooth = add(new BoolSetting("Smooth movement", "Ease in and out of movement instead of starting and stopping instantly.", true));
	public final NumberSetting smoothness = add(new NumberSetting("Smoothness", "How long the camera takes to speed up and glide to a stop.", 0.6, 0.1, 0.95, 0.05))
			.visibleWhen(smooth::isOn);
	public final BoolSetting scrollToChangeSpeed = add(new BoolSetting("Scroll changes speed", "Mouse wheel adjusts Speed while flying, instead of the hotbar.", true));
	public final BoolSetting hideHand = add(new BoolSetting("Hide hand", "Hide your hand and held item while flying.", true));
	public final BoolSetting hideBlockOutline = add(new BoolSetting("Hide block outline", "Hide the outline of the block your body is looking at.", true));
	public final BoolSetting tracer = add(new BoolSetting("Tracer", "Box around your body and a line pointing back to it.", true));
	public final ColorSetting tracerColor = add(new ColorSetting("Tracer color", "Colour of the box and line.", 0xCCFFFFFF))
			.visibleWhen(tracer::isOn);
	public final BoolSetting disableOnDamage = add(new BoolSetting("Disable on damage", "Snap back to your body when you take damage.", true));

	private Vec3 pos = Vec3.ZERO;
	private Vec3 prevPos = Vec3.ZERO;
	private Vec3 velocity = Vec3.ZERO;
	private float yaw;
	private float pitch;
	private float lastHealth;

	// The player's real input while it is swapped out during the player tick.
	private ClientInput realInput;
	private int swapDepth;

	public Freecam() {
		super("Freecam", "Fly the camera freely through blocks while your body stays still.", Category.RENDER);
	}

	/** The enabled Freecam module while in a world, or null. */
	public static Freecam active() {
		if (Lumen.modules() == null || MC.player == null) return null;
		Freecam freecam = Lumen.modules().freecam;
		return freecam.isEnabled() ? freecam : null;
	}

	/** Freecam never starts switched on, so it is not restored from the config. */
	@Override
	public void loadEnabled(boolean enabled) {
	}

	@Override
	protected void onEnable() {
		LocalPlayer player = MC.player;
		if (player == null) {
			setEnabled(false);
			return;
		}

		Camera camera = MC.gameRenderer.mainCamera();
		pos = camera != null ? camera.position() : player.getEyePosition();
		prevPos = pos;
		velocity = Vec3.ZERO;
		yaw = camera != null ? camera.yRot() : player.getYRot();
		pitch = camera != null ? camera.xRot() : player.getXRot();
		lastHealth = player.getHealth();
		// Stop the body where it is rather than letting a run carry it on.
		player.setSprinting(false);
		Vec3 motion = player.getDeltaMovement();
		player.setDeltaMovement(0, motion.y, 0);
	}

	@Override
	protected void onDisable() {
		velocity = Vec3.ZERO;
		if (realInput != null && MC.player != null) MC.player.input = realInput;
		realInput = null;
		swapDepth = 0;
	}

	@Override
	public void onTick() {
		LocalPlayer player = MC.player;
		if (player == null || MC.level == null) {
			setEnabled(false);
			return;
		}

		float health = player.getHealth();
		if (disableOnDamage.isOn() && health < lastHealth) {
			toggle();
			return;
		}
		lastHealth = health;

		Vec3 target = Vec3.ZERO;
		if (MC.gui.screen() == null) {
			// x is strafe (left positive), y is forward.
			Vec2 move = player.input.getMoveVector();
			double yawRad = Math.toRadians(yaw);
			double sin = Math.sin(yawRad);
			double cos = Math.cos(yawRad);
			double dx = move.x * cos - move.y * sin;
			double dz = move.x * sin + move.y * cos;

			double dy = 0;
			if (MC.options.keyJump.isDown()) dy += 1;
			if (MC.options.keyShift.isDown()) dy -= 1;

			double h = speed.get();
			if (MC.options.keySprint.isDown()) h *= sprintBoost.get();
			target = new Vec3(dx * h, dy * h * verticalSpeed.get(), dz * h);
		}

		if (smooth.isOn()) {
			velocity = velocity.lerp(target, 1.0 - smoothness.get());
		} else {
			velocity = target;
		}
		if (velocity.lengthSqr() < 1.0e-6) velocity = Vec3.ZERO;

		prevPos = pos;
		pos = pos.add(velocity);
	}

	@Override
	public String hudInfo() {
		return speed.display();
	}

	// ---- hooks used by the mixins ----

	public Vec3 cameraPos(float partialTick) {
		return prevPos.lerp(pos, partialTick);
	}

	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}

	/** Mouse movement, scaled the same way the game turns the player. */
	public void turn(double deltaYaw, double deltaPitch) {
		yaw += (float) (deltaYaw * 0.15);
		pitch = Math.max(-90f, Math.min(90f, pitch + (float) (deltaPitch * 0.15)));
	}

	/** Returns true if the scroll was used to change speed and should not reach the hotbar. */
	public boolean handleScroll(double amount) {
		if (!scrollToChangeSpeed.isOn() || MC.gui.screen() != null || amount == 0) return false;
		double current = speed.get();
		double step = current < 1 ? 0.1 : current < 3 ? 0.25 : 0.5;
		speed.set(current + (amount > 0 ? step : -step));
		return true;
	}

	/** Swaps an empty input in so the player's own tick sees no movement keys. */
	public void swapInput(LocalPlayer player) {
		if (++swapDepth > 1) return;
		realInput = player.input;
		// The real input still has to read the keys, since the camera uses it.
		realInput.tick();
		player.input = new ClientInput();
	}

	public void restoreInput(LocalPlayer player) {
		if (swapDepth > 0) swapDepth--;
		if (swapDepth > 0 || realInput == null) return;
		player.input = realInput;
		realInput = null;
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cameraPos) {
		if (!tracer.isOn() || MC.player == null) return;

		AABB box = MC.player.getBoundingBox().inflate(0.05);
		int color = tracerColor.color();
		batch.fill(box, ColorUtil.fade(color, 0.18f), true);
		batch.outline(box, color, 1.5f, true);

		float[] start = CameraUtil.tracerOrigin();
		Vec3 center = box.getCenter();
		batch.tracer(start[0], start[1], start[2], center.x, center.y, center.z, color, 1.5f);
	}
}
