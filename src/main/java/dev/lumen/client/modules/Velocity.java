package dev.lumen.client.modules;

import net.minecraft.world.phys.Vec3;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;

/**
 * Scales knockback the server applies to you. The packet handlers record your motion
 * before they run and this scales whatever change they made, so it works the same for
 * hits, arrows and explosions without depending on packet internals.
 */
public final class Velocity extends Module {
	private final NumberSetting horizontal = add(new NumberSetting("Horizontal", "Share of sideways knockback you take.", 0, 0, 100, 5, "%"));
	private final NumberSetting vertical = add(new NumberSetting("Vertical", "Share of upward knockback you take.", 0, 0, 100, 5, "%"));
	private final BoolSetting hits = add(new BoolSetting("Hits", "Knockback from attacks, arrows and other motion the server sets.", true));
	private final BoolSetting explosions = add(new BoolSetting("Explosions", "Knockback from explosions.", true));

	private Vec3 before;

	public Velocity() {
		super("Velocity", "Cuts the knockback you take from hits and explosions.", Category.COMBAT);
	}

	@Override
	public String hudInfo() {
		return horizontal.getInt() + "% " + vertical.getInt() + "%";
	}

	/** Called at the start of a motion or explosion packet handler. */
	public void beforePacket() {
		before = null;
		// Handlers first run on the network thread only to hand themselves to the game thread.
		if (!isEnabled() || MC.player == null || !MC.isSameThread()) return;
		before = MC.player.getDeltaMovement();
	}

	/** Called when the handler returns; scales any change it made to your motion. */
	public void afterPacket(boolean explosion) {
		Vec3 start = before;
		before = null;
		if (start == null || MC.player == null) return;
		if (explosion ? !explosions.isOn() : !hits.isOn()) return;

		Vec3 now = MC.player.getDeltaMovement();
		if (now.equals(start)) return;
		double h = horizontal.get() / 100.0;
		double v = vertical.get() / 100.0;
		MC.player.setDeltaMovement(new Vec3(
				start.x + (now.x - start.x) * h,
				start.y + (now.y - start.y) * v,
				start.z + (now.z - start.z) * h));
	}
}
