package dev.lumen.client.schematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Walks the player along a route found on the blocks around them: flat ground, one-block
 * steps up, drops of up to three blocks, never through water, lava or anything that hurts.
 * It steers with the movement input (forward and sideways), so the camera is never turned.
 */
public final class Walker {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final int MAX_NODES = 6000;
	private static final int MAX_DROP = 3;
	private static final Set<Block> DANGER = Set.of(Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS,
			Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW, Blocks.COBWEB, Blocks.WITHER_ROSE, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
			Blocks.POINTED_DRIPSTONE);
	private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private record Node(BlockPos pos, Node parent, double g, double f) {
	}

	private List<BlockPos> path = List.of();
	private int index;
	private float strafe;
	private float forward;
	private boolean jump;
	private Vec3 checkPos;
	private int checkTicks;

	public boolean walking() {
		return !path.isEmpty();
	}

	/** What is left of the route, for drawing. */
	public List<BlockPos> route() {
		return path.isEmpty() ? List.of() : path.subList(Math.min(index, path.size()), path.size());
	}

	public void stop() {
		path = List.of();
		index = 0;
		strafe = 0;
		forward = 0;
		jump = false;
	}

	/**
	 * Finds a route from where the player stands to the nearest spot the goal accepts,
	 * searching toward a point. False if none was found within the search limit.
	 */
	public boolean walkTo(Predicate<BlockPos> goal, Vec3 toward) {
		LocalPlayer p = MC.player;
		if (p == null || MC.level == null) return false;
		BlockPos start = BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
		if (!standable(start) && standable(start.below())) start = start.below();

		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
		Map<BlockPos, Double> best = new HashMap<>();
		open.add(new Node(start, null, 0, h(start, toward)));
		best.put(start, 0.0);
		int expanded = 0;
		while (!open.isEmpty() && expanded++ < MAX_NODES) {
			Node n = open.poll();
			if (n.g() > best.getOrDefault(n.pos(), Double.MAX_VALUE)) continue;
			if (goal.test(n.pos())) {
				List<BlockPos> route = new ArrayList<>();
				for (Node at = n; at != null; at = at.parent()) route.add(at.pos());
				Collections.reverse(route);
				path = route;
				index = route.size() > 1 ? 1 : 0;
				checkPos = p.position();
				checkTicks = 0;
				return true;
			}
			for (int[] step : STEPS) {
				boolean diagonal = step[0] != 0 && step[1] != 0;
				BlockPos side = n.pos().offset(step[0], 0, step[1]);
				if (diagonal) {
					// Only cut a corner when both sides of it are open.
					BlockPos a = n.pos().offset(step[0], 0, 0);
					BlockPos b = n.pos().offset(0, 0, step[1]);
					if (!clear(a) || !clear(b) || !standable(side)) continue;
					push(open, best, n, side, 1.414, toward);
					continue;
				}
				if (standable(side)) {
					push(open, best, n, side, 1, toward);
				} else if (standable(side.above()) && passable(n.pos().above(2))) {
					push(open, best, n, side.above(), 2, toward);
				} else if (clear(side)) {
					for (int k = 1; k <= MAX_DROP; k++) {
						BlockPos down = side.below(k);
						if (standable(down)) {
							push(open, best, n, down, 1 + k * 0.5, toward);
							break;
						}
						if (!passable(down)) break;
					}
				}
			}
		}
		return false;
	}

	private static void push(PriorityQueue<Node> open, Map<BlockPos, Double> best, Node from, BlockPos to, double cost, Vec3 toward) {
		double g = from.g() + cost;
		if (g >= best.getOrDefault(to, Double.MAX_VALUE)) return;
		best.put(to, g);
		open.add(new Node(to, from, g, g + h(to, toward)));
	}

	private static double h(BlockPos pos, Vec3 toward) {
		return Math.sqrt(pos.distToCenterSqr(toward));
	}

	/**
	 * Works out this tick's movement. False when the player is stuck or has left the route,
	 * so it should be planned again.
	 */
	public boolean tick() {
		LocalPlayer p = MC.player;
		if (p == null || path.isEmpty()) {
			stop();
			return true;
		}
		BlockPos node = path.get(index);
		double dx = node.getX() + 0.5 - p.getX();
		double dz = node.getZ() + 0.5 - p.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist < 0.3 && p.getY() > node.getY() - 0.5 && p.getY() < node.getY() + 1.0) {
			if (++index >= path.size()) {
				stop();
				return true;
			}
			node = path.get(index);
			dx = node.getX() + 0.5 - p.getX();
			dz = node.getZ() + 0.5 - p.getZ();
			dist = Math.sqrt(dx * dx + dz * dz);
		}
		if (p.onGround() && p.getY() < node.getY() - 1.5) return false;

		// Turn the wanted direction into forward and sideways input for the way the player faces.
		double yaw = Math.toRadians(p.getYRot());
		double c = Math.cos(yaw);
		double s = Math.sin(yaw);
		double nx = dist > 1e-4 ? dx / dist : 0;
		double nz = dist > 1e-4 ? dz / dist : 0;
		double speed = index == path.size() - 1 ? Math.min(1.0, dist * 2.0) : 1.0;
		strafe = (float) ((nx * c + nz * s) * speed);
		forward = (float) ((nz * c - nx * s) * speed);
		boolean stepUp = node.getY() > p.getY() + 0.5 && dist < 1.4;
		jump = p.onGround() && (stepUp || (p.horizontalCollision && dist > 0.4));

		if (++checkTicks >= 30) {
			boolean moved = p.position().distanceTo(checkPos) > 0.5;
			checkPos = p.position();
			checkTicks = 0;
			if (!moved) return false;
		}
		return true;
	}

	/** Puts this tick's movement into the player, in place of the keys. */
	public void apply(LocalPlayer p) {
		p.xxa = strafe;
		p.zza = forward;
		p.setJumping(jump);
	}

	// ---- the ground ----

	/** Feet and head fit, there is something to stand on, and nothing there hurts. */
	public static boolean standable(BlockPos feet) {
		if (!passable(feet) || !passable(feet.above())) return false;
		BlockState ground = MC.level.getBlockState(feet.below());
		VoxelShape shape = ground.getCollisionShape(MC.level, feet.below());
		// Fences and walls are taller than a block; standing on them is not walking.
		if (shape.isEmpty() || shape.max(Direction.Axis.Y) > 1.0001 || DANGER.contains(ground.getBlock())) return false;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			if (MC.level.getBlockState(feet.relative(d)).is(Blocks.LAVA)) return false;
		}
		return true;
	}

	/** Air or something without a hitbox, and not water, lava or anything that hurts. */
	public static boolean passable(BlockPos pos) {
		BlockState state = MC.level.getBlockState(pos);
		if (!state.getFluidState().isEmpty() || DANGER.contains(state.getBlock())) return false;
		return state.getCollisionShape(MC.level, pos).isEmpty();
	}

	private static boolean clear(BlockPos feet) {
		return passable(feet) && passable(feet.above());
	}
}
