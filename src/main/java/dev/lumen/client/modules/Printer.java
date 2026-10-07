package dev.lumen.client.modules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.loader.api.FabricLoader;

import dev.lumen.client.hud.Notifications;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.render.EspBatch;
import dev.lumen.client.render.WorldRenderable;
import dev.lumen.client.schematic.Buyer;
import dev.lumen.client.schematic.Schematic;
import dev.lumen.client.schematic.ShopText;
import dev.lumen.client.schematic.Walker;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.setting.TextSetting;
import dev.lumen.client.util.ColorUtil;
import dev.lumen.client.util.Finds;
import dev.lumen.client.util.Placement;

/**
 * Builds a Litematica schematic for you. Blocks go in from the bottom up; for each one
 * the game's own placement logic is asked which click and look direction give the
 * facing, half or axis the schematic wants, and only that combination is used.
 */
public final class Printer extends Module implements WorldRenderable {
	/** Properties set by how a block is placed. The rest, like fence connections, follow from neighbours. */
	private static final List<Property<?>> PLACED = List.of(
			BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.FACING_HOPPER,
			BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS, BlockStateProperties.HALF,
			BlockStateProperties.SLAB_TYPE, BlockStateProperties.ATTACH_FACE, BlockStateProperties.ROTATION_16,
			BlockStateProperties.ORIENTATION, BlockStateProperties.DOOR_HINGE);
	private static final int SPARE_SLOT = 8;
	/** What a hoe turns into farmland (coarse dirt goes to dirt first, then farmland). */
	private static final Set<Block> TILLABLE = Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.DIRT_PATH, Blocks.COARSE_DIRT);
	private static final int MAX_GHOSTS = 2500;

	private record Plan(BlockHitResult hit, float yaw, float pitch, boolean turn) {
	}

	private record Ghost(BlockPos pos, boolean wrong) {
	}

	private final TextSetting file = add(new TextSetting("File",
			"Schematic name, from .minecraft/schematics (where Litematica saves) or config/lumen/schematics.", "", 64));
	private final TextSetting origin = add(new TextSetting("Origin",
			"x y z of the build's lowest corner. Leave empty to use where you stand when you turn it on.", "", 40));
	private final NumberSetting perTick = add(new NumberSetting("Blocks per tick", "Most blocks placed in one tick.", 2, 1, 8, 1));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Ticks to wait between rounds of placing.", 0, 0, 10, 1, "t"));
	private final NumberSetting reach = add(new NumberSetting("Reach", "How far away blocks are placed.", 4.5, 2, 5.5, 0.1, "m"));
	private final BoolSetting walk = add(new BoolSetting("Walk to blocks",
			"Walk around the build to the blocks still to do, lowest layer first. It never turns your view; pressing a movement key takes over while you hold it.", true));
	private final BoolSetting rotate = add(new BoolSetting("Turn for facing",
			"Look the way a block must face for its click only, so stairs, furnaces and the like come out right.", true));
	private final BoolSetting useInventory = add(new BoolSetting("Use inventory", "Move blocks from your inventory into the last hotbar slot.", true));
	private final BoolSetting creative = add(new BoolSetting("Creative blocks", "In creative, take whatever block is needed.", true));
	private final BoolSetting breakWrong = add(new BoolSetting("Break wrong blocks",
			"Mine blocks that differ from the schematic, then place the right one. Containers and anything else holding items are never broken.", true));
	private final BoolSetting clearInside = add(new BoolSetting("Clear inside",
			"Also mine whatever is inside the schematic's area where it has air, such as terrain.", false));
	private final BoolSetting placeWater = add(new BoolSetting("Place water", "Pour water sources from water buckets in your inventory.", true));
	private final BoolSetting buy = add(new BoolSetting("Buy missing",
			"When you run out of a block, buy more with /shop, then the auction house. Made for DonutSMP.", false));
	private final BoolSetting buyShop = add(new BoolSetting("Use shop", "Look for the block in the server shop first.", true))
			.visibleWhen(buy::isOn);
	private final BoolSetting buyAuction = add(new BoolSetting("Use auction house",
			"When the shop does not sell it, buy the auction listing that covers what is needed for the least money.", true))
			.visibleWhen(buy::isOn);
	private final TextSetting maxEach = add(new TextSetting("Max price each", "Most to pay per block, like 50, 2.5k or 1m.", "100", 16))
			.visibleWhen(buy::isOn);
	private final TextSetting maxSpend = add(new TextSetting("Max spend",
			"Most to spend in total each time the Printer is turned on, like 50k or 2m.", "50k", 16))
			.visibleWhen(buy::isOn);
	private final TextSetting shopCommand = add(new TextSetting("Shop command", "The command that opens the server shop.", "shop", 32))
			.visibleWhen(buy::isOn);
	private final TextSetting auctionCommand = add(new TextSetting("Auction command",
			"The command that searches the auction house. {item} becomes the block's name.", "ah {item}", 48))
			.visibleWhen(buy::isOn);
	private final BoolSetting finishOff = add(new BoolSetting("Turn off when done", "Stop once every block is in place.", true));
	private final BoolSetting showMissing = add(new BoolSetting("Show missing", "Outline blocks still to place, and wrong blocks in red.", true));
	private final ColorSetting missingColor = add(new ColorSetting("Missing color", "Outline of blocks still to place.", 0xFF5AC8FA))
			.visibleWhen(showMissing::isOn);
	private final ColorSetting wrongColor = add(new ColorSetting("Wrong color", "Outline of blocks that differ from the schematic.", 0xFFFF5A5A))
			.visibleWhen(showMissing::isOn);
	private final NumberSetting showRange = add(new NumberSetting("Show range", "How far away outlines are drawn.", 24, 8, 96, 4, "m"))
			.visibleWhen(showMissing::isOn);

	private Schematic schematic;
	private final Map<BlockPos, BlockState> byPos = new HashMap<>();
	private BlockPos originPos;
	private String loadedFile = "";
	private String loadedOrigin = "";
	private int timer;
	private int statusTimer;
	private int placed;
	private int correct;
	private int total;
	private List<Ghost> ghosts = List.of();
	private final Set<Item> missingItems = new HashSet<>();
	private final Buyer buyer = new Buyer();
	private final Set<Item> wanted = new LinkedHashSet<>();
	private final Set<Item> unbuyable = new HashSet<>();
	private double spent;
	private boolean warnedLimits;
	private boolean warnedHoe;
	private int ticks;
	/** When each spot was last hoed: the server answers a hoe click, the client does not predict it. */
	private final Map<BlockPos, Integer> tilledAt = new HashMap<>();
	private BlockPos mining;
	private Direction miningFace;
	private int miningTicks;
	private int miningSlot = -1;
	/** How often each spot was mined; one the server keeps putting back is left alone. */
	private final Map<BlockPos, Integer> breakTries = new HashMap<>();
	private boolean warnedProtected;
	private int mined;
	private final Walker walker = new Walker();
	/** Blocks still to do that can be done: an item for them is carried or can be bought, or what is in the way can be mined. */
	private final Set<BlockPos> pending = new HashSet<>();
	/** Spots given up on for a while: nothing could be done there from where the walk ended. */
	private final Map<BlockPos, Integer> walkSkip = new HashMap<>();
	private BlockPos walkTarget;
	private int walkTimer;
	private int walkFails;
	private int lastProgress;
	private int lastProgressTick;
	/** When water was last poured at each spot: the server places it, the client does not predict it. */
	private final Map<BlockPos, Integer> pouredAt = new HashMap<>();

	public Printer() {
		super("Printer", "Builds a Litematica schematic block by block, with the right facing.", Category.PLAYER);
	}

	public Schematic schematic() {
		return schematic;
	}

	public BlockPos originPos() {
		return originPos;
	}

	/** Blocks placed since the module was turned on; used by the game test. */
	public int placed() {
		return placed;
	}

	/** Blocks the Printer can place, which it counts toward finishing. */
	public int total() {
		return total;
	}

	/** Blocks that match the schematic, as of the last check. */
	public int correct() {
		return correct;
	}

	/** True while a block is being mined; vanilla is kept from cancelling it meanwhile. */
	public boolean isMining() {
		return isEnabled() && mining != null;
	}

	/** Blocks mined since the module was turned on; used by the game test. */
	public int mined() {
		return mined;
	}

	/** Money spent buying blocks since the module was turned on. */
	public double spent() {
		return spent;
	}

	public boolean buying() {
		return buyer.busy();
	}

	/** Blocks that could not be bought this run; used by the game test. */
	public Set<Item> couldNotBuy() {
		return Set.copyOf(unbuyable);
	}

	@Override
	public String hudInfo() {
		if (schematic == null) return "";
		if (buyer.busy()) return "buying " + new ItemStack(buyer.item()).getHoverName().getString();
		return correct + "/" + total;
	}

	@Override
	protected void onEnable() {
		placed = 0;
		missingItems.clear();
		wanted.clear();
		unbuyable.clear();
		spent = 0;
		warnedLimits = false;
		warnedHoe = false;
		tilledAt.clear();
		breakTries.clear();
		warnedProtected = false;
		mining = null;
		mined = 0;
		pouredAt.clear();
		walker.stop();
		pending.clear();
		walkSkip.clear();
		walkTarget = null;
		walkTimer = 0;
		walkFails = 0;
		lastProgress = 0;
		lastProgressTick = ticks;
		buyer.forget();
		if (!load()) setEnabled(false);
	}

	@Override
	protected void onDisable() {
		walker.stop();
		if (mining != null && MC.player != null) stopMining(MC.player);
		buyer.cancel();
		wanted.clear();
		schematic = null;
		byPos.clear();
		ghosts = List.of();
	}

	private boolean load() {
		LocalPlayer p = MC.player;
		if (p == null) return false;
		String name = file.get().trim();
		if (name.isEmpty()) {
			fail("Type the schematic's file name in Printer's File setting first.");
			return false;
		}
		Path path = find(name);
		if (path == null) {
			fail("No schematic named " + name + " in .minecraft/schematics or config/lumen/schematics.");
			return false;
		}
		try {
			schematic = Schematic.load(path);
		} catch (IOException | RuntimeException e) {
			fail("Could not read " + path.getFileName() + ": " + e.getMessage());
			return false;
		}

		BlockPos parsed = parseOrigin(origin.get());
		if (parsed == null) {
			parsed = p.blockPosition();
			origin.set(parsed.getX() + " " + parsed.getY() + " " + parsed.getZ());
		}
		originPos = parsed;
		loadedFile = file.get();
		loadedOrigin = origin.get();
		byPos.clear();
		Set<String> byHand = new java.util.TreeSet<>();
		total = 0;
		for (Schematic.Entry e : schematic.blocks()) {
			if (isFlowing(e.state())) continue;
			byPos.put(e.pos(), e.state());
			if (placeable(e.state())) total++;
			else byHand.add(e.state().getBlock().getName().getString());
		}
		statusTimer = 0;
		correct = 0;

		List<String> unknown = schematic.unknownBlocks();
		if (!unknown.isEmpty()) {
			Finds.chat(unknown.size() + " block types in " + schematic.name() + " are not in this version of the game and are skipped: "
					+ String.join(", ", unknown.subList(0, Math.min(8, unknown.size()))) + (unknown.size() > 8 ? ", ..." : ""));
		}
		if (total == 0) {
			fail(schematic.name() + " has no blocks the Printer can place" + (unknown.isEmpty() ? "." : "; see chat for the block types it did not know."));
			schematic = null;
			return false;
		}
		if (!byHand.isEmpty()) Finds.chat("Place these by hand, they have no item to place: " + String.join(", ", byHand) + ".");

		BlockPos s = schematic.size();
		String detail = schematic.name() + ": " + total + " blocks, " + s.getX() + "x" + s.getY() + "x" + s.getZ()
				+ ", from " + originPos.getX() + " " + originPos.getY() + " " + originPos.getZ();
		if (!unknown.isEmpty()) detail += " (" + unknown.size() + " unknown block types skipped)";
		Notifications.notice("Printing", detail, 0xFF5AC8FA);
		Finds.chat("Printing " + detail);
		return true;
	}

	/** Lava, fire and the like have no item to place them with. Farmland is made with a hoe, water poured from a bucket. */
	private boolean placeable(BlockState state) {
		if (isWaterSource(state)) return placeWater.isOn();
		return state.is(Blocks.FARMLAND) || state.getBlock().asItem() instanceof BlockItem;
	}

	private static boolean isWaterSource(BlockState state) {
		return state.is(Blocks.WATER) && state.getFluidState().isSource();
	}

	/** Water or lava that is not a source: it flows into place by itself and is never placed. */
	private static boolean isFlowing(BlockState state) {
		return (state.is(Blocks.WATER) || state.is(Blocks.LAVA)) && !state.getFluidState().isSource();
	}

	/** In survival there is no farmland item, so it is made as by hand: dirt, then a hoe. */
	private boolean tillsFarmland(LocalPlayer p) {
		return !(creative.isOn() && p.getAbilities().instabuild && Blocks.FARMLAND.asItem() instanceof BlockItem);
	}

	/** The item a block is placed with: dirt for farmland that will be hoed. */
	private Item sourceItem(LocalPlayer p, BlockState target) {
		if (isWaterSource(target)) return Items.WATER_BUCKET;
		return target.is(Blocks.FARMLAND) && tillsFarmland(p) ? Items.DIRT : target.getBlock().asItem();
	}

	private static void fail(String message) {
		Notifications.notice("Printer", message, 0xFFFF6B7A);
		Finds.chat(message);
	}

	@Override
	public void onTick() {
		ticks++;
		LocalPlayer p = MC.player;
		if (p == null || MC.level == null || MC.gameMode == null) return;
		if (schematic == null) return;
		// Editing the file or origin while it runs starts over with the new one.
		if (!file.get().equals(loadedFile) || !origin.get().equals(loadedOrigin)) {
			if (!load()) {
				setEnabled(false);
				return;
			}
		}

		if (--statusTimer <= 0) {
			statusTimer = 10;
			updateStatus(p);
			if (correct == total && finishOff.isOn()) {
				Notifications.notice("Build finished", schematic.name() + ": all " + correct + " blocks in place", 0xFF6CF0A0);
				Finds.chat("Finished " + schematic.name() + ": all " + correct + " blocks in place."
						+ (spent > 0 ? " Spent " + ShopText.format(spent) + " on blocks." : ""));
				setEnabled(false);
				return;
			}
		}

		updateWalking(p);
		// Buying drives the server's menus, so nothing is placed until it is done.
		if (buyer.busy()) {
			buyer.tick();
			if (!buyer.busy()) finishBuying(buyer.takeResult());
			return;
		}
		if (MC.gui.screen() != null) return;
		if (!wanted.isEmpty()) {
			startBuying(p);
			if (buyer.busy()) return;
		}
		if (mining != null) {
			mine(p);
			return;
		}
		if (timer > 0) {
			timer--;
			return;
		}
		if (placeSome(p) > 0) timer = delay.getInt();
	}

	private int placeSome(LocalPlayer p) {
		Vec3 eye = p.getEyePosition();
		double r = reach.get();
		int ri = (int) Math.ceil(r) + 1;
		BlockPos center = BlockPos.containing(eye);
		BlockPos size = schematic.size();

		List<BlockPos> candidates = new ArrayList<>();
		for (int dx = -ri; dx <= ri; dx++) {
			for (int dy = -ri; dy <= ri; dy++) {
				for (int dz = -ri; dz <= ri; dz++) {
					BlockPos abs = center.offset(dx, dy, dz);
					BlockPos rel = abs.subtract(originPos);
					boolean inBox = rel.getX() >= 0 && rel.getY() >= 0 && rel.getZ() >= 0
							&& rel.getX() < size.getX() && rel.getY() < size.getY() && rel.getZ() < size.getZ();
					if (!byPos.containsKey(rel) && !(clearInside.isOn() && inBox)) continue;
					if (eye.distanceTo(Vec3.atCenterOf(abs)) > r + 0.87) continue;
					candidates.add(abs);
				}
			}
		}

		// Mine first, highest first, so nothing falls into a spot already cleared.
		if (breakWrong.isOn() || clearInside.isOn()) {
			candidates.sort(Comparator.<BlockPos>comparingInt(b -> -b.getY()).thenComparingDouble(b -> eye.distanceToSqr(Vec3.atCenterOf(b))));
			for (BlockPos abs : candidates) {
				if (needsBreaking(p, abs)) {
					startMining(p, abs);
					return 1;
				}
			}
		}

		// Lowest first, so each block has something under or beside it; then nearest.
		candidates.sort(Comparator.<BlockPos>comparingInt(b -> b.getY()).thenComparingDouble(b -> eye.distanceToSqr(Vec3.atCenterOf(b))));
		int done = 0;
		for (BlockPos abs : candidates) {
			BlockState target = byPos.get(abs.subtract(originPos));
			if (target == null || isWaterSource(target)) continue;
			if (tryPlace(p, abs, target)) done++;
			if (done >= perTick.getInt()) break;
		}
		// Water last, once nothing else here can be placed, so it does not run over spots still to fill.
		if (done == 0 && placeWater.isOn()) {
			for (BlockPos abs : candidates) {
				BlockState target = byPos.get(abs.subtract(originPos));
				if (target != null && isWaterSource(target) && pourWater(p, abs)) return 1;
			}
		}
		return done;
	}

	// ---- walking ----

	/** Moves this tick's walking into the player; called from LocalPlayer.applyInput. */
	public void applyWalk(LocalPlayer p) {
		if (isEnabled() && walk.isOn() && walker.walking() && !userMoving()) walker.apply(p);
	}

	public boolean walking() {
		return walker.walking();
	}

	private static boolean userMoving() {
		return MC.options.keyUp.isDown() || MC.options.keyDown.isDown() || MC.options.keyLeft.isDown()
				|| MC.options.keyRight.isDown() || MC.options.keyJump.isDown();
	}

	private void updateWalking(LocalPlayer p) {
		if (!walk.isOn() || buyer.busy() || mining != null || MC.gui.screen() != null || userMoving()) {
			walker.stop();
			return;
		}
		int progress = placed + mined;
		if (progress != lastProgress) {
			lastProgress = progress;
			lastProgressTick = ticks;
		}
		if (walker.walking()) {
			// Done from where the walk got to: choose again.
			if (walkTarget != null && !pending.contains(walkTarget)) {
				walker.stop();
				return;
			}
			if (!walker.tick()) {
				walker.stop();
				walkTimer = 0;
				if (++walkFails >= 3 && walkTarget != null) {
					walkSkip.put(walkTarget, ticks);
					walkFails = 0;
				}
			}
			return;
		}
		if (--walkTimer > 0) return;
		walkTimer = 10;

		Vec3 eye = p.getEyePosition();
		double r = reach.get();
		boolean inReach = false;
		for (BlockPos pos : pending) {
			if (!skipped(pos) && eye.distanceTo(Vec3.atCenterOf(pos)) <= r) {
				inReach = true;
				break;
			}
		}
		if (inReach) {
			if (ticks - lastProgressTick < 60) return;
			// Three seconds with nothing done: these cannot be done from here, so try others for a while.
			for (BlockPos pos : pending) {
				if (eye.distanceTo(Vec3.atCenterOf(pos)) <= r) walkSkip.put(pos, ticks);
			}
			lastProgressTick = ticks;
		}

		BlockPos target = null;
		double targetDist = 0;
		for (BlockPos pos : pending) {
			if (skipped(pos)) continue;
			double d = p.distanceToSqr(Vec3.atCenterOf(pos));
			if (target == null || pos.getY() < target.getY() || pos.getY() == target.getY() && d < targetDist) {
				target = pos;
				targetDist = d;
			}
		}
		if (target == null) return;
		Vec3 center = Vec3.atCenterOf(target);
		double near = Math.max(1.5, r - 0.7);
		// Stand within reach, but never where a block still has to go, or on one about to be mined.
		boolean found = walker.walkTo(n -> !pending.contains(n) && !pending.contains(n.above()) && !pending.contains(n.below())
				&& new Vec3(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5).distanceTo(center) <= near, center);
		if (found) {
			walkTarget = target;
			walkFails = 0;
		} else {
			walkSkip.put(target, ticks);
		}
	}

	private boolean skipped(BlockPos pos) {
		Integer at = walkSkip.get(pos);
		return at != null && ticks - at < 600;
	}

	/** Whether the Printer can get this block done, so it is worth walking to. */
	private boolean doable(LocalPlayer p, BlockPos abs, BlockState target, BlockState world, Map<Item, Integer> carried, boolean hoe) {
		boolean tillable = target.is(Blocks.FARMLAND) && tillsFarmland(p) && TILLABLE.contains(world.getBlock());
		if (tillable) return hoe;
		if (!world.canBeReplaced()) {
			// Something is in the way: only worth the walk if it will be mined.
			if (!breakWrong.isOn() || world.hasBlockEntity() || world.getDestroySpeed(MC.level, abs) < 0) return false;
		}
		if (creative.isOn() && p.getAbilities().instabuild) return true;
		Item item = sourceItem(p, target);
		if (carried.getOrDefault(item, 0) > 0) return true;
		return canBuy(p) && !unbuyable.contains(item);
	}

	// ---- breaking ----

	/** A block in the way: wrong for the schematic, or (with Clear inside) where the schematic has air. */
	private boolean needsBreaking(LocalPlayer p, BlockPos abs) {
		BlockState world = MC.level.getBlockState(abs);
		if (world.isAir() || world.is(Blocks.WATER) || world.is(Blocks.LAVA) || world.is(Blocks.BUBBLE_COLUMN)) return false;
		BlockState target = byPos.get(abs.subtract(originPos));
		if (target == null) {
			if (!clearInside.isOn()) return false;
		} else {
			if (!breakWrong.isOn() || matches(world, target)) return false;
			// Grass, flowers and the like are replaced by placing; dirt and grass under farmland are hoed.
			if (world.canBeReplaced()) return false;
			if (target.is(Blocks.FARMLAND) && tillsFarmland(p) && TILLABLE.contains(world.getBlock())) return false;
		}
		return canMine(p, abs, world);
	}

	private boolean canMine(LocalPlayer p, BlockPos abs, BlockState world) {
		// Chests, shulker boxes, spawners, signs and anything else with contents stay.
		if (world.hasBlockEntity() || world.getDestroySpeed(MC.level, abs) < 0) return false;
		if (breakTries.getOrDefault(abs, 0) >= 3) return false;
		// Not the block underfoot, and nothing that would let lava out.
		if (p.getBoundingBox().expandTowards(0, -0.6, 0).intersects(new AABB(abs))) return false;
		for (Direction d : Direction.values()) {
			if (MC.level.getBlockState(abs.relative(d)).is(Blocks.LAVA)) return false;
		}
		return p.getEyePosition().distanceTo(Vec3.atCenterOf(abs)) <= reach.get() + 0.5;
	}

	private void startMining(LocalPlayer p, BlockPos abs) {
		int tries = breakTries.merge(abs, 1, Integer::sum);
		if (tries == 3 && !warnedProtected) {
			warnedProtected = true;
			Finds.chat("A block at " + abs.getX() + " " + abs.getY() + " " + abs.getZ()
					+ " keeps coming back after mining (a protected area?), so the Printer leaves it.");
		}
		mining = abs;
		miningTicks = 0;
		miningFace = faceToward(abs, p.getEyePosition());
		miningSlot = p.getInventory().getSelectedSlot();
		int tool = bestTool(p, MC.level.getBlockState(abs));
		if (tool >= 0) p.getInventory().setSelectedSlot(tool);
		// Look at the block while mining it, as a player would.
		float[] look = CombatModule.rotationsTo(Vec3.atCenterOf(abs));
		MC.getConnection().send(new ServerboundMovePlayerPacket.Rot(look[0], look[1], p.onGround(), p.horizontalCollision));
	}

	private void mine(LocalPlayer p) {
		BlockState state = MC.level.getBlockState(mining);
		boolean gone = state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA);
		boolean outOfReach = p.getEyePosition().distanceTo(Vec3.atCenterOf(mining)) > reach.get() + 1.5;
		if (gone || outOfReach || ++miningTicks > 400) {
			if (gone) mined++;
			stopMining(p);
			return;
		}
		// As vanilla's continueAttack does for each tick of mining.
		var animation = p.getMainHandItem().getAttackAnimation();
		if (MC.gameMode.continueDestroyBlock(mining, miningFace)) {
			p.swing(InteractionHand.MAIN_HAND, animation, false);
			p.connection.send(ServerboundPunchPacket.INSTANCE);
		}
	}

	private void stopMining(LocalPlayer p) {
		if (MC.gameMode.isDestroying()) MC.gameMode.stopDestroyBlock();
		if (miningSlot >= 0) p.getInventory().setSelectedSlot(miningSlot);
		MC.getConnection().send(new ServerboundMovePlayerPacket.Rot(p.getYRot(), p.getXRot(), p.onGround(), p.horizontalCollision));
		mining = null;
		miningSlot = -1;
	}

	/** The fastest hotbar tool for the block, skipping tools about to break; -1 to keep what is held. */
	private static int bestTool(LocalPlayer p, BlockState state) {
		Inventory inv = p.getInventory();
		int best = -1;
		float bestSpeed = 1.0f;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 2) continue;
			float speed = stack.getDestroySpeed(state);
			if (speed > bestSpeed + 0.01f) {
				best = i;
				bestSpeed = speed;
			}
		}
		return best;
	}

	/** The face of the block that points most toward the eye. */
	private static Direction faceToward(BlockPos pos, Vec3 eye) {
		Vec3 d = eye.subtract(Vec3.atCenterOf(pos));
		double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
		if (ay >= ax && ay >= az) return d.y > 0 ? Direction.UP : Direction.DOWN;
		if (ax >= az) return d.x > 0 ? Direction.EAST : Direction.WEST;
		return d.z > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	// ---- water ----

	/**
	 * Pours a water source from a bucket. A bucket places water next to whatever face the
	 * player looks at, so a neighbour face the eye can see is found and looked at for the use.
	 */
	private boolean pourWater(LocalPlayer p, BlockPos abs) {
		if (!MC.level.getBlockState(abs).canBeReplaced()) return false;
		Integer last = pouredAt.get(abs);
		if (last != null && ticks - last < 20) return false;
		int slot = ensureInHotbar(p, Items.WATER_BUCKET);
		if (slot < 0) {
			if (canBuy(p) && !unbuyable.contains(Items.WATER_BUCKET)) wanted.add(Items.WATER_BUCKET);
			else if (missingItems.add(Items.WATER_BUCKET)) Finds.chat("Printer needs water buckets in your inventory to place water.");
			return false;
		}
		Vec3 eye = p.getEyePosition();
		for (Direction dir : Direction.values()) {
			BlockPos neighbour = abs.relative(dir);
			if (!canClick(neighbour)) continue;
			Direction face = dir.getOpposite();
			Vec3 faceCenter = Vec3.atCenterOf(neighbour).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			for (Vec3 point : facePoints(faceCenter, face)) {
				if (eye.distanceTo(point) > reach.get()) continue;
				Vec3 end = point.add(face.getStepX() * -0.05, face.getStepY() * -0.05, face.getStepZ() * -0.05);
				BlockHitResult seen = MC.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
				if (seen.getType() != HitResult.Type.BLOCK || !seen.getBlockPos().equals(neighbour) || seen.getDirection() != face) continue;
				useAt(p, slot, CombatModule.rotationsTo(point));
				pouredAt.put(abs, ticks);
				placed++;
				return true;
			}
		}
		return false;
	}

	private static List<Vec3> facePoints(Vec3 c, Direction face) {
		List<Vec3> points = new ArrayList<>();
		points.add(c);
		for (double a : new double[] {-0.3, 0.3}) {
			for (double b : new double[] {-0.3, 0.3}) {
				points.add(switch (face.getAxis()) {
					case X -> c.add(0, a, b);
					case Y -> c.add(a, 0, b);
					case Z -> c.add(a, b, 0);
				});
			}
		}
		return points;
	}

	/** Uses the item in a hotbar slot looking a given way, as vanilla does, then looks back. */
	private static void useAt(LocalPlayer p, int slot, float[] look) {
		Inventory inv = p.getInventory();
		int previous = inv.getSelectedSlot();
		float yaw = p.getYRot();
		float pitch = p.getXRot();
		float yawO = p.yRotO;
		float pitchO = p.xRotO;
		MC.getConnection().send(new ServerboundMovePlayerPacket.Rot(look[0], look[1], p.onGround(), p.horizontalCollision));
		inv.setSelectedSlot(slot);
		p.setYRot(look[0]);
		p.setXRot(look[1]);
		p.yRotO = look[0];
		p.xRotO = look[1];
		try {
			var animation = p.getItemInHand(InteractionHand.MAIN_HAND).getInteractAnimation();
			InteractionResult result = MC.gameMode.useItem(p, InteractionHand.MAIN_HAND);
			if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
				p.swing(InteractionHand.MAIN_HAND, animation, false);
			}
		} finally {
			p.setYRot(yaw);
			p.setXRot(pitch);
			p.yRotO = yawO;
			p.xRotO = pitchO;
			inv.setSelectedSlot(previous);
			MC.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, p.onGround(), p.horizontalCollision));
		}
	}

	private boolean tryPlace(LocalPlayer p, BlockPos abs, BlockState target) {
		BlockState world = MC.level.getBlockState(abs);
		if (matches(world, target)) return false;
		if (target.is(Blocks.FARMLAND) && tillsFarmland(p)) {
			if (TILLABLE.contains(world.getBlock())) return till(p, abs);
			// Dirt goes down first; the next round hoes it.
			target = Blocks.DIRT.defaultBlockState();
		}
		// The right block facing the wrong way, or something else in the way, is left alone.
		if (world.is(target.getBlock()) || !world.canBeReplaced()) return false;

		Item item = target.getBlock().asItem();
		if (item == Items.AIR || !(item instanceof BlockItem)) return false;
		int slot = ensureInHotbar(p, item);
		if (slot < 0) {
			if (canBuy(p) && !unbuyable.contains(item)) wanted.add(item);
			else if (missingItems.add(item)) Finds.chat("Printer needs " + new ItemStack(item).getHoverName().getString() + " in your inventory.");
			return false;
		}

		Plan plan = plan(p, abs, target, p.getInventory().getItem(slot));
		if (plan == null) return false;

		float realYaw = p.getYRot();
		float realPitch = p.getXRot();
		if (plan.turn()) {
			MC.getConnection().send(new ServerboundMovePlayerPacket.Rot(plan.yaw(), plan.pitch(), p.onGround(), p.horizontalCollision));
			p.setYRot(plan.yaw());
			p.setXRot(plan.pitch());
		}
		Placement.click(slot, InteractionHand.MAIN_HAND, plan.hit());
		if (plan.turn()) {
			p.setYRot(realYaw);
			p.setXRot(realPitch);
			MC.getConnection().send(new ServerboundMovePlayerPacket.Rot(realYaw, realPitch, p.onGround(), p.horizontalCollision));
		}
		placed++;
		return true;
	}

	/** Hoes the block from above, the way farmland is made by hand. */
	private boolean till(LocalPlayer p, BlockPos abs) {
		Integer last = tilledAt.get(abs);
		if (last != null && ticks - last < 20) return false;
		if (!MC.level.getBlockState(abs.above()).isAir()) return false;
		Vec3 hitVec = new Vec3(abs.getX() + 0.5, abs.getY() + 1.0, abs.getZ() + 0.5);
		if (p.getEyePosition().distanceTo(hitVec) > reach.get()) return false;
		int slot = hoeSlot(p);
		if (slot < 0) {
			if (!warnedHoe) Finds.chat("Printer needs a hoe in your inventory to turn dirt into farmland.");
			warnedHoe = true;
			return false;
		}
		Placement.click(slot, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, Direction.UP, abs, false));
		tilledAt.put(abs, ticks);
		placed++;
		return true;
	}

	private int hoeSlot(LocalPlayer p) {
		int slot = Placement.hotbarSlot(Printer::isHoe);
		if (slot >= 0) return slot;
		if (useInventory.isOn() && p.containerMenu == p.inventoryMenu) {
			Inventory inv = p.getInventory();
			for (int i = 9; i < 36; i++) {
				if (!isHoe(inv.getItem(i))) continue;
				MC.gameMode.handleContainerInput(p.inventoryMenu.containerId, i, SPARE_SLOT, ContainerInput.SWAP, p);
				return isHoe(inv.getItem(SPARE_SLOT)) ? SPARE_SLOT : -1;
			}
		}
		return -1;
	}

	private static boolean isHoe(ItemStack stack) {
		return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_hoe");
	}

	/**
	 * A block that can be clicked to place against: anything solid that does not open or
	 * toggle. Unlike the combat modules' check it need not be a full block, so crops go on
	 * farmland and blocks on slabs; the placement is simulated before any click anyway.
	 */
	private static boolean canClick(BlockPos pos) {
		BlockState state = MC.level.getBlockState(pos);
		if (state.canBeReplaced() || state.hasBlockEntity()) return false;
		if (state.getCollisionShape(MC.level, pos).isEmpty()) return false;
		return Placement.isSupportBlock(state);
	}

	/** The click (and, if needed, look direction) that the game says gives exactly the target's placed properties. */
	private Plan plan(LocalPlayer p, BlockPos abs, BlockState target, ItemStack stack) {
		Vec3 eye = p.getEyePosition();
		List<float[]> looks = new ArrayList<>();
		looks.add(new float[] {p.getYRot(), p.getXRot()});
		if (rotate.isOn()) {
			for (float yaw : new float[] {0f, 90f, 180f, -90f}) {
				for (float pitch : new float[] {0f, 80f, -80f}) looks.add(new float[] {yaw, pitch});
			}
		}

		for (int i = 0; i < looks.size(); i++) {
			float[] look = looks.get(i);
			for (Direction dir : Direction.values()) {
				BlockPos neighbour = abs.relative(dir);
				if (!canClick(neighbour)) continue;
				Direction face = dir.getOpposite();
				double[] heights = face.getAxis().isVertical() ? new double[] {0.5} : new double[] {0.25, 0.75};
				for (double h : heights) {
					Vec3 hitVec = face.getAxis().isVertical()
							? new Vec3(neighbour.getX() + 0.5, neighbour.getY() + (face == Direction.UP ? 1.0 : 0.0), neighbour.getZ() + 0.5)
							: new Vec3(neighbour.getX() + 0.5 + face.getStepX() * 0.5, neighbour.getY() + h, neighbour.getZ() + 0.5 + face.getStepZ() * 0.5);
					if (eye.distanceTo(hitVec) > reach.get()) continue;
					BlockHitResult hit = new BlockHitResult(hitVec, face, neighbour, false);
					BlockState predicted = simulate(p, stack, hit, look, abs);
					if (predicted != null && matches(predicted, target)) return new Plan(hit, look[0], look[1], i > 0);
				}
			}
		}
		return null;
	}

	/** Asks the block what state it would take from this click with the player looking this way. */
	private static BlockState simulate(LocalPlayer p, ItemStack stack, BlockHitResult hit, float[] look, BlockPos expected) {
		if (!(stack.getItem() instanceof BlockItem item)) return null;
		float yaw = p.getYRot();
		float pitch = p.getXRot();
		float yawO = p.yRotO;
		float pitchO = p.xRotO;
		p.setYRot(look[0]);
		p.setXRot(look[1]);
		p.yRotO = look[0];
		p.xRotO = look[1];
		try {
			BlockPlaceContext ctx = new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack, hit);
			if (!ctx.canPlace() || !ctx.getClickedPos().equals(expected)) return null;
			BlockState state = item.getBlock().getStateForPlacement(ctx);
			if (state == null || !state.canSurvive(MC.level, expected)) return null;
			return state;
		} finally {
			p.setYRot(yaw);
			p.setXRot(pitch);
			p.yRotO = yawO;
			p.xRotO = pitchO;
		}
	}

	/** A hotbar slot holding the item, fetched from the inventory or conjured in creative if allowed; -1 if none. */
	private int ensureInHotbar(LocalPlayer p, Item item) {
		int slot = Placement.hotbarSlot(stack -> stack.is(item));
		if (slot >= 0) return slot;
		Inventory inv = p.getInventory();
		if (creative.isOn() && p.getAbilities().instabuild) {
			ItemStack stack = new ItemStack(item, item.getDefaultMaxStackSize());
			inv.setItem(SPARE_SLOT, stack);
			MC.gameMode.handleCreativeModeItemAdd(stack, 36 + SPARE_SLOT);
			return SPARE_SLOT;
		}
		if (useInventory.isOn() && p.containerMenu == p.inventoryMenu) {
			for (int i = 9; i < 36; i++) {
				if (!inv.getItem(i).is(item)) continue;
				MC.gameMode.handleContainerInput(p.inventoryMenu.containerId, i, SPARE_SLOT, ContainerInput.SWAP, p);
				return inv.getItem(SPARE_SLOT).is(item) ? SPARE_SLOT : -1;
			}
		}
		return -1;
	}

	private boolean canBuy(LocalPlayer p) {
		return buy.isOn() && !p.getAbilities().instabuild && (buyShop.isOn() || buyAuction.isOn());
	}

	private void startBuying(LocalPlayer p) {
		Item item = wanted.iterator().next();
		wanted.remove(item);
		int need = needed(p, item);
		if (need <= 0) {
			// Carried but not where the Printer takes blocks from, so buying more would not help.
			unbuyable.add(item);
			if (missingItems.add(item)) Finds.chat("Printer needs " + new ItemStack(item).getHoverName().getString() + " in your hotbar.");
			return;
		}
		double each = ShopText.money(maxEach.get());
		double cap = ShopText.money(maxSpend.get());
		if (Double.isNaN(each) || each <= 0 || Double.isNaN(cap) || cap <= 0) {
			unbuyable.add(item);
			if (!warnedLimits) fail("Set Max price each and Max spend in the Printer's settings, like 100 and 50k, to buy blocks.");
			warnedLimits = true;
			return;
		}
		String name = new ItemStack(item).getHoverName().getString();
		Finds.chat("Buying " + need + " " + name + ", at most " + ShopText.format(each) + " each, "
				+ ShopText.format(Math.max(0, cap - spent)) + " left to spend.");
		buyer.start(item, need, new Buyer.Config(buyShop.isOn(), buyAuction.isOn(), shopCommand.get(), auctionCommand.get(), each, cap - spent));
		if (!buyer.busy()) finishBuying(buyer.takeResult());
	}

	private void finishBuying(Buyer.Result r) {
		if (r == null) return;
		spent += r.spent();
		String name = new ItemStack(r.item()).getHoverName().getString();
		if (r.bought() > 0) {
			Finds.chat("Bought " + r.bought() + " " + name + " for " + ShopText.format(r.spent()) + " (" + ShopText.format(spent) + " spent so far).");
		}
		if (r.problem() != null) {
			unbuyable.add(r.item());
			fail("Could not buy " + (r.bought() > 0 ? "enough " : "") + name + ": " + r.problem() + ".");
		}
	}

	/** Blocks of this item the schematic still needs placed, less what is carried. */
	private int needed(LocalPlayer p, Item item) {
		int n = 0;
		for (Schematic.Entry e : schematic.blocks()) {
			if (sourceItem(p, e.state()) != item) continue;
			BlockState world = MC.level.getBlockState(originPos.offset(e.pos()));
			if (matches(world, e.state()) || !world.canBeReplaced()) continue;
			n++;
		}
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(item)) n -= inv.getItem(i).getCount();
		}
		return n;
	}

	/** Same block, and the same value for every property that placement decides. */
	public static boolean matches(BlockState world, BlockState target) {
		if (world.getBlock() != target.getBlock()) return false;
		if (target.is(Blocks.WATER) || target.is(Blocks.LAVA)) return world.getFluidState().isSource() == target.getFluidState().isSource();
		for (Property<?> property : PLACED) {
			if (target.hasProperty(property) && !sameValue(world, target, property)) return false;
		}
		return true;
	}

	private static <T extends Comparable<T>> boolean sameValue(BlockState a, BlockState b, Property<T> property) {
		return a.hasProperty(property) && a.getValue(property).equals(b.getValue(property));
	}

	private void updateStatus(LocalPlayer p) {
		int ok = 0;
		List<Ghost> list = new ArrayList<>();
		Map<Item, Integer> carried = new HashMap<>();
		boolean hoe = false;
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			carried.merge(stack.getItem(), stack.getCount(), Integer::sum);
			if (isHoe(stack)) hoe = true;
		}
		pending.clear();
		double rangeSq = showRange.get() * showRange.get();
		Vec3 here = p.position();
		for (Map.Entry<BlockPos, BlockState> e : byPos.entrySet()) {
			BlockPos abs = originPos.offset(e.getKey());
			BlockState world = MC.level.getBlockState(abs);
			if (matches(world, e.getValue())) {
				if (placeable(e.getValue())) ok++;
				continue;
			}
			if (placeable(e.getValue()) && doable(p, abs, e.getValue(), world, carried, hoe)) pending.add(abs);
			if (list.size() < MAX_GHOSTS && here.distanceToSqr(Vec3.atCenterOf(abs)) <= rangeSq) {
				list.add(new Ghost(abs, !world.canBeReplaced()));
			}
		}
		correct = ok;
		ghosts = list;
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
		// The walking route, as small marks on the ground.
		for (BlockPos node : walker.route()) {
			AABB mark = new AABB(node.getX() + 0.4, node.getY() + 0.02, node.getZ() + 0.4, node.getX() + 0.6, node.getY() + 0.12, node.getZ() + 0.6);
			batch.fill(mark, ColorUtil.fade(missingColor.color(), 0.7f), false);
		}
		if (!showMissing.isOn() || ghosts.isEmpty()) return;
		int missing = missingColor.color();
		int wrong = wrongColor.color();
		for (Ghost g : ghosts) {
			AABB box = new AABB(g.pos()).deflate(0.04);
			int c = g.wrong() ? wrong : missing;
			batch.fill(box, ColorUtil.fade(c, 0.10f), false);
			batch.outline(box, ColorUtil.fade(c, 0.8f), 1.5f, false);
		}
	}

	private static BlockPos parseOrigin(String text) {
		String[] parts = text.trim().split("[\\s,]+");
		if (parts.length != 3) return null;
		try {
			return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Looks for the file by name, with or without .litematic, in both schematic folders and their subfolders. */
	private static Path find(String name) {
		String want = name.toLowerCase(Locale.ROOT);
		String wanted = want.endsWith(".litematic") ? want : want + ".litematic";
		List<Path> roots = List.of(
				FabricLoader.getInstance().getGameDir().resolve("schematics"),
				FabricLoader.getInstance().getConfigDir().resolve("lumen").resolve("schematics"));
		for (Path root : roots) {
			try {
				Files.createDirectories(root);
				try (Stream<Path> files = Files.walk(root, 4)) {
					Optional<Path> hit = files.filter(Files::isRegularFile)
							.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).equals(wanted))
							.findFirst();
					if (hit.isPresent()) return hit.get();
				}
			} catch (IOException ignored) {
				// An unreadable folder just means nothing found there.
			}
		}
		return null;
	}
}
