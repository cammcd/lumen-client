package dev.lumen.client.modules;

import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import dev.lumen.client.Lumen;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.NumberSetting;

/**
 * Steers along trails of old chunks that New Chunks marks, which are where other
 * players have travelled. Pair it with Auto Walk or Elytra+ to follow a trail hands-free.
 */
public final class TrailFollower extends Module {
	private final NumberSetting lookAhead = add(new NumberSetting("Look ahead", "How far ahead to look for old chunks, in chunks.", 6, 2, 12, 1));
	private final NumberSetting spread = add(new NumberSetting("Search angle", "How far left or right of your heading to look.", 60, 10, 120, 5, "°"));
	private final NumberSetting turnSpeed = add(new NumberSetting("Turn speed", "Most degrees turned per tick.", 2, 0.5, 10, 0.5, "°"));

	private float heading = Float.NaN;
	private int recheck;

	public TrailFollower() {
		super("Trail Follower", "Steers along trails of old chunks from New Chunks. Use with Auto Walk or Elytra+.", Category.PLAYER);
	}

	@Override
	public String hudInfo() {
		if (!Lumen.modules().newChunks.isEnabled()) return "needs New Chunks";
		return Float.isNaN(heading) ? "lost" : "";
	}

	@Override
	public void onTick() {
		LocalPlayer p = MC.player;
		NewChunks chunks = Lumen.modules().newChunks;
		if (p == null || !chunks.isEnabled() || MC.gui.screen() != null) return;

		if (--recheck <= 0) {
			recheck = 10;
			heading = bestHeading(p.getX(), p.getZ(), p.getYRot(), chunks::isOld, chunks::isNew, lookAhead.getInt(), spread.getFloat());
		}
		if (Float.isNaN(heading)) return;
		float step = turnSpeed.getFloat();
		p.setYRot(p.getYRot() + Mth.clamp(Mth.wrapDegrees(heading - p.getYRot()), -step, step));
	}

	/**
	 * The yaw, within {@code spread} degrees of the current one, whose line ahead crosses
	 * the most old chunks and fewest new ones; nearer chunks count more. NaN if no line
	 * ahead touches any old chunk.
	 */
	public static float bestHeading(double x, double z, float yaw, Predicate<ChunkPos> old, Predicate<ChunkPos> fresh,
			int lookAheadChunks, float spread) {
		float best = Float.NaN;
		double bestScore = 0;
		int reach = lookAheadChunks * 16;
		for (float offset = -spread; offset <= spread; offset += 5f) {
			float h = yaw + offset;
			double dx = -Math.sin(Math.toRadians(h));
			double dz = Math.cos(Math.toRadians(h));
			double score = 0;
			ChunkPos last = null;
			for (int d = 4; d <= reach; d += 4) {
				ChunkPos cp = new ChunkPos(Mth.floor(x + dx * d) >> 4, Mth.floor(z + dz * d) >> 4);
				if (cp.equals(last)) continue;
				last = cp;
				double weight = 1.0 / (1.0 + d / 64.0);
				if (old.test(cp)) score += weight;
				else if (fresh.test(cp)) score -= weight * 0.5;
			}
			// A small pull toward going straight, so equal trails do not make it wobble.
			score -= Math.abs(offset) * 0.002;
			if (score > bestScore) {
				bestScore = score;
				best = h;
			}
		}
		return best;
	}
}
