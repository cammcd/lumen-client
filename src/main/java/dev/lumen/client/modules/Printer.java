package dev.lumen.client.modules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.loader.api.FabricLoader;

import dev.lumen.client.hud.Notifications;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.render.EspBatch;
import dev.lumen.client.render.WorldRenderable;
import dev.lumen.client.schematic.Schematic;
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
	private final BoolSetting rotate = add(new BoolSetting("Turn for facing",
			"Look the way a block must face for its click only, so stairs, furnaces and the like come out right.", true));
	private final BoolSetting useInventory = add(new BoolSetting("Use inventory", "Move blocks from your inventory into the last hotbar slot.", true));
	private final BoolSetting creative = add(new BoolSetting("Creative blocks", "In creative, take whatever block is needed.", true));
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
	private List<Ghost> ghosts = List.of();
	private final Set<Item> missingItems = new HashSet<>();

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

	/** Blocks that match the schematic, as of the last check. */
	public int correct() {
		return correct;
	}

	@Override
	public String hudInfo() {
		return schematic == null ? "" : correct + "/" + schematic.blocks().size();
	}

	@Override
	protected void onEnable() {
		placed = 0;
		missingItems.clear();
		if (!load()) setEnabled(false);
	}

	@Override
	protected void onDisable() {
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
		for (Schematic.Entry e : schematic.blocks()) byPos.put(e.pos(), e.state());
		statusTimer = 0;

		BlockPos s = schematic.size();
		String detail = schematic.name() + ": " + schematic.blocks().size() + " blocks, " + s.getX() + "x" + s.getY() + "x" + s.getZ()
				+ ", from " + originPos.getX() + " " + originPos.getY() + " " + originPos.getZ();
		if (schematic.unknownBlocks() > 0) detail += " (" + schematic.unknownBlocks() + " unknown block types skipped)";
		Notifications.notice("Printing", detail, 0xFF5AC8FA);
		Finds.chat("Printing " + detail);
		return true;
	}

	private static void fail(String message) {
		Notifications.notice("Printer", message, 0xFFFF6B7A);
		Finds.chat(message);
	}

	@Override
	public void onTick() {
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
			if (correct == schematic.blocks().size() && finishOff.isOn()) {
				Notifications.notice("Build finished", schematic.name() + ": all " + correct + " blocks in place", 0xFF6CF0A0);
				Finds.chat("Finished " + schematic.name() + ": all " + correct + " blocks in place.");
				setEnabled(false);
				return;
			}
		}

		if (MC.gui.screen() != null) return;
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

		List<BlockPos> candidates = new ArrayList<>();
		for (int dx = -ri; dx <= ri; dx++) {
			for (int dy = -ri; dy <= ri; dy++) {
				for (int dz = -ri; dz <= ri; dz++) {
					BlockPos abs = center.offset(dx, dy, dz);
					if (!byPos.containsKey(abs.subtract(originPos))) continue;
					if (eye.distanceTo(Vec3.atCenterOf(abs)) > r + 0.87) continue;
					candidates.add(abs);
				}
			}
		}
		// Lowest first, so each block has something under or beside it; then nearest.
		candidates.sort(Comparator.comparingInt(BlockPos::getY).thenComparingDouble(b -> eye.distanceToSqr(Vec3.atCenterOf(b))));

		int done = 0;
		for (BlockPos abs : candidates) {
			if (tryPlace(p, abs, byPos.get(abs.subtract(originPos)))) done++;
			if (done >= perTick.getInt()) break;
		}
		return done;
	}

	private boolean tryPlace(LocalPlayer p, BlockPos abs, BlockState target) {
		BlockState world = MC.level.getBlockState(abs);
		if (matches(world, target)) return false;
		// The right block facing the wrong way, or something else in the way, is left alone.
		if (world.is(target.getBlock()) || !world.canBeReplaced()) return false;

		Item item = target.getBlock().asItem();
		if (item == Items.AIR || !(item instanceof BlockItem)) return false;
		int slot = ensureInHotbar(p, item);
		if (slot < 0) {
			if (missingItems.add(item)) Finds.chat("Printer needs " + new ItemStack(item).getHoverName().getString() + " in your inventory.");
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
				if (!Placement.isSupport(neighbour)) continue;
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

	/** Same block, and the same value for every property that placement decides. */
	public static boolean matches(BlockState world, BlockState target) {
		if (world.getBlock() != target.getBlock()) return false;
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
		double rangeSq = showRange.get() * showRange.get();
		Vec3 here = p.position();
		for (Schematic.Entry e : schematic.blocks()) {
			BlockPos abs = originPos.offset(e.pos());
			BlockState world = MC.level.getBlockState(abs);
			if (matches(world, e.state())) {
				ok++;
				continue;
			}
			if (list.size() < MAX_GHOSTS && here.distanceToSqr(Vec3.atCenterOf(abs)) <= rangeSq) {
				list.add(new Ghost(abs, !world.canBeReplaced()));
			}
		}
		correct = ok;
		ghosts = list;
	}

	@Override
	public void renderWorld(EspBatch batch, Vec3 cam) {
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
