package dev.lumen.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import dev.lumen.client.Lumen;
import dev.lumen.client.gui.ClickGuiScreen;
import dev.lumen.client.gui.ProfilesScreen;
import dev.lumen.client.gui.WaypointsScreen;
import dev.lumen.client.module.Module;
import dev.lumen.client.modules.SignReader;
import dev.lumen.client.modules.SoundLocator;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.EnumSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.setting.Setting;

/**
 * Launches the game with Lumen, builds a small scene of storage blocks and spawners,
 * and exercises the ESP modules, the click GUI and keybinds. Screenshots land in
 * the run directory's screenshots folder and are published by CI.
 */
public final class LumenClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("Lumen Test");

	// Storage blocks in the scene that are highlighted by default. The double chest
	// counts once; the dispenser and hopper are off by default.
	private static final int EXPECTED_STORAGE = 9;
	private static final int EXPECTED_SPAWNERS = 2;
	// Creeper (hostile), pig (passive), a dropped diamond (item), a named pig, a chest
	// minecart and an item frame. The villager is in the Other group, which is off by
	// default, and the test player is skipped.
	private static final int EXPECTED_ENTITIES = 6;

	// Default click GUI layout: panels start below the search bar at y 34, the header
	// is 20 high and module rows are 16 high.
	private static final int PANEL_CENTER_X = 16 + 68;
	private static final int STORAGE_ROW_Y = 34 + 20 + 2 + 8;
	private static final int SPAWNER_ROW_Y = STORAGE_ROW_Y + 16;
	// Settings start 2 below the module row: Keybind row (14), BLOCKS heading (14), Chests.
	private static final int CHESTS_ROW_Y = STORAGE_ROW_Y + 8 + 2 + 14 + 14 + 7;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			mc.options.hideSplashTexts().set(true);
			mc.options.guiScale().set(2);
		});
		context.getInput().resizeWindow(1280, 720);
		context.waitForScreen(TitleScreen.class);
		context.waitTicks(60);

		LOG.info("Auditing mixins");
		MixinEnvironment.getCurrentEnvironment().audit();

		try (TestSingleplayerContext sp = context.worldBuilder()
				.adjustSettings(creator -> {
					creator.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					// Keeps setblock feedback out of chat so screenshots stay clean.
					creator.getGameRules().set(GameRules.SEND_COMMAND_FEEDBACK, false, null);
				})
				.create()) {
			testInWorld(context, sp);
		}

		testAutoReconnect(context);
		LOG.info("Lumen client test complete");
	}

	/**
	 * Opens a disconnect screen for a server on a closed local port: the countdown button
	 * must appear, and when it runs out a real connection attempt must be made, which
	 * fails straight back to a disconnect screen.
	 */
	private void testAutoReconnect(ClientGameTestContext context) {
		LOG.info("Testing Auto Reconnect");
		context.runOnClient(mc -> {
			var module = Lumen.modules().autoReconnect;
			((NumberSetting) setting(module, "Delay")).set(1.0);
			module.setEnabled(true);
			module.remember(new net.minecraft.client.multiplayer.ServerData("Lumen test", "127.0.0.1:1",
					net.minecraft.client.multiplayer.ServerData.Type.OTHER));
			mc.gui.setScreen(new net.minecraft.client.gui.screens.DisconnectedScreen(new TitleScreen(),
					net.minecraft.network.chat.Component.literal("Disconnected"),
					net.minecraft.network.chat.Component.literal("Kicked for testing")));
		});
		context.waitTicks(5);
		String label = context.computeOnClient(mc -> {
			for (var widget : net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(mc.gui.screen())) {
				String text = widget.getMessage().getString();
				if (text.startsWith("Reconnect")) return text;
			}
			return null;
		});
		LOG.info("Auto Reconnect button: {}", label);
		context.takeScreenshot("lumen_24_auto_reconnect");
		if (label == null) throw new AssertionError("Auto Reconnect did not add its button to the disconnect screen");

		int attempts = 0;
		for (int i = 0; i < 80 && attempts < 1; i++) {
			context.waitTicks(5);
			attempts = context.computeOnClient(mc -> Lumen.modules().autoReconnect.attempts());
		}
		LOG.info("Auto Reconnect: {} connection attempts", attempts);
		if (attempts < 1) throw new AssertionError("Auto Reconnect never tried to reconnect after its countdown");
		// The closed port refuses the connection, which lands back on a disconnect screen.
		boolean failedBack = false;
		for (int i = 0; i < 40 && !failedBack; i++) {
			context.waitTicks(5);
			failedBack = context.computeOnClient(mc -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen);
		}
		LOG.info("Auto Reconnect: the attempt failed back to the disconnect screen {}", failedBack);

		context.runOnClient(mc -> {
			Lumen.modules().autoReconnect.setEnabled(false);
			mc.gui.setScreen(new TitleScreen());
		});
		context.waitForScreen(TitleScreen.class);
	}

	private void testInWorld(ClientGameTestContext context, TestSingleplayerContext sp) {
		TestServerContext server = sp.getServer();
		TestInput input = context.getInput();

		command(server, "/time set noon");
		buildScene(server);
		command(server, "/tp @a 0.5 -60 -8.5 0 22");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(10);
		input.lookAt(0f, 22f);
		context.waitTicks(2);

		LOG.info("Screenshot: HUD only");
		context.takeScreenshot("lumen_01_hud");

		LOG.info("Enabling Storage ESP and Spawner ESP");
		context.runOnClient(mc -> {
			Lumen.modules().storageEsp.setEnabled(true);
			Lumen.modules().spawnerEsp.setEnabled(true);
		});
		context.waitTicks(12);

		int storage = context.computeOnClient(mc -> Lumen.modules().storageEsp.count());
		int spawners = context.computeOnClient(mc -> Lumen.modules().spawnerEsp.count());
		LOG.info("Storage ESP targets: {}, Spawner ESP targets: {}", storage, spawners);
		if (storage != EXPECTED_STORAGE) {
			throw new AssertionError("Storage ESP found " + storage + " targets, expected " + EXPECTED_STORAGE);
		}
		if (spawners != EXPECTED_SPAWNERS) {
			throw new AssertionError("Spawner ESP found " + spawners + " targets, expected " + EXPECTED_SPAWNERS);
		}
		context.takeScreenshot("lumen_02_esp");

		LOG.info("Screenshot: ESP through a wall");
		command(server, "/fill -8 -60 -4 8 -57 -4 minecraft:stone");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(4);
		context.takeScreenshot("lumen_03_through_wall");
		command(server, "/fill -8 -60 -4 8 -57 -4 minecraft:air");
		sp.getConnection().waitForChunksRender();

		LOG.info("Screenshot: outline mode with tracers");
		context.runOnClient(mc -> {
			Module esp = Lumen.modules().storageEsp;
			((EnumSetting<?>) setting(esp, "Mode")).cycle(true);
			((BoolSetting) setting(esp, "Tracers")).set(true);
		});
		context.waitTicks(4);
		context.takeScreenshot("lumen_04_outline_tracers");
		context.runOnClient(mc -> {
			Module esp = Lumen.modules().storageEsp;
			setting(esp, "Mode").reset();
			setting(esp, "Tracers").reset();
		});

		LOG.info("Enabling Entity ESP");
		context.runOnClient(mc -> Lumen.modules().entityEsp.setEnabled(true));
		context.waitTicks(6);
		int entities = context.computeOnClient(mc -> Lumen.modules().entityEsp.count());
		LOG.info("Entity ESP targets: {}", entities);
		if (entities != EXPECTED_ENTITIES) {
			throw new AssertionError("Entity ESP found " + entities + " targets, expected " + EXPECTED_ENTITIES);
		}
		// Block highlights sit in front of the mobs, so hide them for this shot.
		context.runOnClient(mc -> {
			Lumen.modules().storageEsp.setEnabled(false);
			Lumen.modules().spawnerEsp.setEnabled(false);
		});
		context.waitTicks(3);
		context.takeScreenshot("lumen_05_entity_esp");
		context.runOnClient(mc -> {
			Lumen.modules().storageEsp.setEnabled(true);
			Lumen.modules().spawnerEsp.setEnabled(true);
		});
		context.waitTicks(8);

		LOG.info("Opening the click GUI with its key");
		input.pressKey(Lumen.clickGuiKey());
		context.waitForScreen(ClickGuiScreen.class);
		context.waitTicks(20);
		context.takeScreenshot("lumen_06_clickgui");

		int scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());

		LOG.info("Binding Spawner ESP to G through the GUI");
		input.setCursorPos(PANEL_CENTER_X * scale, SPAWNER_ROW_Y * scale);
		input.pressMouse(InputConstants.MOUSE_BUTTON_MIDDLE);
		context.waitTicks(2);
		input.pressKey(InputConstants.KEY_G);
		context.waitTicks(2);
		String key = context.computeOnClient(mc -> Lumen.modules().spawnerEsp.key());
		LOG.info("Spawner ESP key is now '{}'", key);
		if (key.isEmpty()) throw new AssertionError("Binding through the GUI did not set a key");
		String queryAfterBind = context.computeOnClient(mc -> ((ClickGuiScreen) mc.gui.screen()).query());
		if (!queryAfterBind.isEmpty()) {
			throw new AssertionError("The bound key was also typed into the search box: '" + queryAfterBind + "'");
		}

		LOG.info("Expanding Storage ESP settings");
		input.setCursorPos(PANEL_CENTER_X * scale, STORAGE_ROW_Y * scale);
		input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(20);
		context.takeScreenshot("lumen_07_settings");

		LOG.info("Opening the Chests colour picker");
		input.setCursorPos(PANEL_CENTER_X * scale, CHESTS_ROW_Y * scale);
		input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(20);
		context.takeScreenshot("lumen_08_color_picker");

		// "spawner", not "spawn": search also matches descriptions, and Anchor Aura's mentions respawn anchors.
		LOG.info("Searching for 'spawner'");
		input.typeChars("spawner");
		context.waitTicks(10);
		List<String> visible = context.computeOnClient(mc -> ((ClickGuiScreen) mc.gui.screen()).visibleModules());
		LOG.info("Visible modules while searching: {}", visible);
		if (!visible.equals(List.of("Spawner ESP"))) {
			throw new AssertionError("Searching 'spawner' showed " + visible + ", expected only Spawner ESP");
		}
		context.takeScreenshot("lumen_09_search");
		input.pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(2);
		String queryAfterEsc = context.computeOnClient(mc -> mc.gui.screen() instanceof ClickGuiScreen gui ? gui.query() : "<closed>");
		if (!queryAfterEsc.isEmpty()) {
			throw new AssertionError("Esc should clear the search and keep the GUI open, got '" + queryAfterEsc + "'");
		}

		LOG.info("Saving a profile through the Profiles screen");
		int[] profiles = context.computeOnClient(mc -> ((ClickGuiScreen) mc.gui.screen()).profilesButtonCenter());
		input.setCursorPos(profiles[0] * scale, profiles[1] * scale);
		input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitForScreen(ProfilesScreen.class);
		context.waitTicks(10);
		input.typeChars("Test profile");
		input.pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(10);
		boolean saved = context.computeOnClient(mc -> Lumen.profiles().exists("Test profile"));
		if (!saved) throw new AssertionError("Pressing Enter on the Profiles screen did not save the profile");
		context.takeScreenshot("lumen_10_profiles");
		input.pressKey(InputConstants.KEY_ESCAPE);
		context.waitForScreen(ClickGuiScreen.class);
		context.waitTicks(5);

		LOG.info("Closing the GUI");
		input.pressKey(InputConstants.KEY_ESCAPE);
		context.waitForScreen(null);
		Path config = FabricLoader.getInstance().getConfigDir().resolve("lumen.json");
		if (!Files.exists(config)) throw new AssertionError("Closing the GUI did not save " + config);

		LOG.info("Loading the profile restores changed settings");
		context.runOnClient(mc -> ((NumberSetting) setting(Lumen.modules().storageEsp, "Fill opacity")).set(80.0));
		context.runOnClient(mc -> Lumen.profiles().load("Test profile"));
		double fill = context.computeOnClient(mc -> ((NumberSetting) setting(Lumen.modules().storageEsp, "Fill opacity")).get());
		LOG.info("Storage ESP fill opacity after loading the profile: {}", fill);
		if (fill != 22.0) throw new AssertionError("Loading the profile did not restore Fill opacity, got " + fill);

		LOG.info("Toggling Spawner ESP with its new key");
		boolean before = context.computeOnClient(mc -> Lumen.modules().spawnerEsp.isEnabled());
		input.pressKey(InputConstants.KEY_G);
		context.waitTicks(4);
		boolean after = context.computeOnClient(mc -> Lumen.modules().spawnerEsp.isEnabled());
		if (before == after) throw new AssertionError("Pressing the bound key did not toggle Spawner ESP");
		context.takeScreenshot("lumen_11_keybind_notification");

		testFreecam(context);
		testWorldFinders(context, sp);
		testInfoModules(context, sp);
		testXRayAndSigns(context, sp);
		testCombat(context, sp);
		testCrystalPvp(context, sp);
		testUtility(context, sp);
		testFreecamSurvival(context, sp);
		testFullbright(context, sp);
		testPrinter(context, sp);
		testSchematicForms(context);
		testPrinterBuying(context, sp);
		testPrinterFarmland(context, sp);
		testFakeName(context);
	}

	// The test schematic, by position relative to its lowest corner.
	private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> printerExpected() {
		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> m = new java.util.LinkedHashMap<>();
		var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
		var glass = net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState();
		for (int x = 0; x < 3; x++) {
			for (int z = 0; z < 3; z++) m.put(new BlockPos(x, 0, z), stone);
		}
		m.put(new BlockPos(1, 0, 1), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState()
				.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, net.minecraft.core.Direction.Axis.X));
		m.put(new BlockPos(0, 1, 0), net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState()
				.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.WEST));
		m.put(new BlockPos(2, 1, 0), net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState()
				.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.EAST)
				.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.BOTTOM));
		m.put(new BlockPos(1, 1, 1), glass);
		m.put(new BlockPos(2, 1, 1), glass);
		m.put(new BlockPos(1, 1, 2), glass);
		m.put(new BlockPos(0, 1, 2), net.minecraft.world.level.block.Blocks.OAK_SLAB.defaultBlockState()
				.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP));
		m.put(new BlockPos(2, 2, 1), glass);
		m.put(new BlockPos(1, 2, 1), net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState()
				.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH)
				.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.TOP));
		var gold = net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState();
		m.put(new BlockPos(0, 0, 5), gold);
		m.put(new BlockPos(1, 0, 5), gold);
		return m;
	}

	/**
	 * Writes the test schematic exactly as Litematica would: gzipped NBT, regions with a
	 * Position and Size, a palette with air first, and indices packed by a copy of
	 * Litematica's LitematicaBitArray.setAt. The "Side" region has a negative x Size,
	 * as Litematica saves a selection made from right to left.
	 */
	private static void writeTestSchematic(Path file) {
		var all = printerExpected();
		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> main = new java.util.HashMap<>();
		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> side = new java.util.HashMap<>();
		all.forEach((pos, state) -> {
			if (pos.getZ() < 5) main.put(pos, state);
			else side.put(pos.offset(0, 0, -5), state);
		});
		net.minecraft.nbt.CompoundTag regions = new net.minecraft.nbt.CompoundTag();
		regions.put("Main", litematicRegion(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3), 3, 3, 3, main));
		regions.put("Side", litematicRegion(new BlockPos(1, 0, 5), new BlockPos(-2, 1, 1), 2, 1, 1, side));
		net.minecraft.nbt.CompoundTag meta = new net.minecraft.nbt.CompoundTag();
		meta.putString("Name", "Lumen test");
		net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
		root.putInt("MinecraftDataVersion", 4500);
		root.putInt("Version", 7);
		root.putInt("SubVersion", 1);
		root.put("Metadata", meta);
		root.put("Regions", regions);
		try {
			Files.createDirectories(file.getParent());
			net.minecraft.nbt.NbtIo.writeCompressed(root, file);
		} catch (java.io.IOException e) {
			throw new AssertionError("Could not write the test schematic", e);
		}
	}

	/** One Litematica region; blocks are keyed by position from the region's lowest corner. */
	private static net.minecraft.nbt.CompoundTag litematicRegion(BlockPos position, BlockPos size, int sx, int sy, int sz,
			java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks) {
		var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		List<net.minecraft.world.level.block.state.BlockState> palette = new java.util.ArrayList<>();
		palette.add(air);
		for (var state : blocks.values()) {
			if (!palette.contains(state)) palette.add(state);
		}
		int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
		long volume = (long) sx * sy * sz;
		long[] data = new long[(int) ((volume * bits + 63) / 64)];
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					var state = blocks.getOrDefault(new BlockPos(x, y, z), air);
					litematicaSetAt(data, bits, (long) y * sx * sz + (long) z * sx + x, palette.indexOf(state));
				}
			}
		}
		net.minecraft.nbt.ListTag paletteTag = new net.minecraft.nbt.ListTag();
		for (var state : palette) paletteTag.add(net.minecraft.nbt.NbtUtils.writeBlockState(state));
		net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
		tag.put("Position", posTag(position));
		tag.put("Size", posTag(size));
		tag.put("BlockStatePalette", paletteTag);
		tag.put("BlockStates", new net.minecraft.nbt.LongArrayTag(data));
		tag.put("TileEntities", new net.minecraft.nbt.ListTag());
		tag.put("Entities", new net.minecraft.nbt.ListTag());
		return tag;
	}

	private static net.minecraft.nbt.CompoundTag posTag(BlockPos pos) {
		net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
		tag.putInt("x", pos.getX());
		tag.putInt("y", pos.getY());
		tag.putInt("z", pos.getZ());
		return tag;
	}

	/** Copied from Litematica's LitematicaBitArray.setAt. */
	private static void litematicaSetAt(long[] longArray, int bitsPerEntry, long index, int value) {
		long maxEntryValue = (1L << bitsPerEntry) - 1L;
		long startOffset = index * (long) bitsPerEntry;
		int startArrIndex = (int) (startOffset >> 6);
		int endArrIndex = (int) (((index + 1L) * (long) bitsPerEntry - 1L) >> 6);
		int startBitOffset = (int) (startOffset & 0x3F);
		longArray[startArrIndex] = longArray[startArrIndex] & ~(maxEntryValue << startBitOffset) | ((long) value & maxEntryValue) << startBitOffset;
		if (startArrIndex != endArrIndex) {
			int endOffset = 64 - startBitOffset;
			int j1 = bitsPerEntry - endOffset;
			longArray[endArrIndex] = longArray[endArrIndex] >>> j1 << j1 | ((long) value & maxEntryValue) >> endOffset;
		}
	}

	private static net.minecraft.nbt.CompoundTag paletteEntry(String name, String... properties) {
		net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
		tag.putString("Name", name);
		if (properties.length > 0) {
			net.minecraft.nbt.CompoundTag props = new net.minecraft.nbt.CompoundTag();
			for (int i = 0; i + 1 < properties.length; i += 2) props.putString(properties[i], properties[i + 1]);
			tag.put("Properties", props);
		}
		return tag;
	}

	/** A one-region schematic one block deep, with palette index x + 1 at each x. */
	private static void writeRowSchematic(Path file, net.minecraft.nbt.ListTag palette) {
		int n = palette.size() - 1;
		int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
		long[] data = new long[(n * bits + 63) / 64];
		for (int x = 0; x < n; x++) litematicaSetAt(data, bits, x, x + 1);
		net.minecraft.nbt.CompoundTag region = new net.minecraft.nbt.CompoundTag();
		region.put("Position", posTag(new BlockPos(0, 0, 0)));
		region.put("Size", posTag(new BlockPos(n, 1, 1)));
		region.put("BlockStatePalette", palette);
		region.put("BlockStates", new net.minecraft.nbt.LongArrayTag(data));
		region.put("TileEntities", new net.minecraft.nbt.ListTag());
		region.put("Entities", new net.minecraft.nbt.ListTag());
		net.minecraft.nbt.CompoundTag regions = new net.minecraft.nbt.CompoundTag();
		regions.put("Main", region);
		net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
		root.putInt("MinecraftDataVersion", 3955);
		root.putInt("Version", 6);
		root.put("Regions", regions);
		try {
			Files.createDirectories(file.getParent());
			net.minecraft.nbt.NbtIo.writeCompressed(root, file);
		} catch (java.io.IOException e) {
			throw new AssertionError("Could not write the test schematic", e);
		}
	}

	/**
	 * Schematics from other tools and older versions write their palettes differently from
	 * this game's own writer. Every form must decode, unknown blocks must be named, and a
	 * schematic with no known blocks must not be "finished" with nothing built.
	 */
	private void testSchematicForms(ClientGameTestContext context) {
		LOG.info("Testing schematic palette forms");
		Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
		net.minecraft.nbt.ListTag palette = new net.minecraft.nbt.ListTag();
		palette.add(paletteEntry("minecraft:air"));
		palette.add(paletteEntry("minecraft:oak_stairs", "facing", "south", "half", "top"));
		palette.add(paletteEntry("minecraft:oak_log[axis=z]"));
		palette.add(paletteEntry(" Cobblestone "));
		palette.add(paletteEntry("minecraft:grass"));
		palette.add(paletteEntry("examplemod:widget"));
		palette.add(paletteEntry("minecraft:water", "level", "0"));
		palette.add(paletteEntry("minecraft:carrots", "age", "7"));
		Path forms = dir.resolve("lumen_forms.litematic");
		writeRowSchematic(forms, palette);

		String decoded = context.computeOnClient(mc -> {
			dev.lumen.client.schematic.Schematic schematic;
			try {
				schematic = dev.lumen.client.schematic.Schematic.load(forms);
			} catch (java.io.IOException e) {
				return "failed: " + e.getMessage();
			}
			java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> want = new java.util.HashMap<>();
			want.put(new BlockPos(0, 0, 0), net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState()
					.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.SOUTH)
					.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.TOP));
			want.put(new BlockPos(1, 0, 0), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState()
					.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, net.minecraft.core.Direction.Axis.Z));
			want.put(new BlockPos(2, 0, 0), net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState());
			want.put(new BlockPos(3, 0, 0), net.minecraft.world.level.block.Blocks.SHORT_GRASS.defaultBlockState());
			want.put(new BlockPos(5, 0, 0), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
			want.put(new BlockPos(6, 0, 0), net.minecraft.world.level.block.Blocks.CARROTS.defaultBlockState()
					.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7, 7));
			java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> got = new java.util.HashMap<>();
			for (var e : schematic.blocks()) got.put(e.pos(), e.state());
			if (!got.equals(want)) return "got " + got;
			if (!schematic.unknownBlocks().equals(List.of("examplemod:widget"))) return "unknown " + schematic.unknownBlocks();
			return "ok";
		});
		LOG.info("Schematic forms: decoded {}", decoded);
		if (!decoded.equals("ok")) throw new AssertionError("Palette forms did not decode: " + decoded);

		net.minecraft.nbt.ListTag unknownPalette = new net.minecraft.nbt.ListTag();
		unknownPalette.add(paletteEntry("minecraft:air"));
		unknownPalette.add(paletteEntry("examplemod:frame"));
		unknownPalette.add(paletteEntry("examplemod:gear"));
		writeRowSchematic(dir.resolve("lumen_unknown.litematic"), unknownPalette);
		boolean stayedOn = context.computeOnClient(mc -> {
			Module printer = Lumen.modules().printer;
			((dev.lumen.client.setting.TextSetting) setting(printer, "File")).set("lumen_unknown");
			((dev.lumen.client.setting.TextSetting) setting(printer, "Origin")).set("0 -60 0");
			printer.setEnabled(true);
			return printer.isEnabled();
		});
		context.waitTicks(2);
		LOG.info("Schematic forms: Printer stayed on for a schematic with no known blocks: {}", stayedOn);
		if (stayedOn) throw new AssertionError("The Printer turned on for a schematic with no blocks it knows");
	}

	private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> farmExpected() {
		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> m = new java.util.LinkedHashMap<>();
		for (int x = 0; x < 3; x++) {
			m.put(new BlockPos(x, 0, 0), net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState());
			m.put(new BlockPos(x, 1, 0), net.minecraft.world.level.block.Blocks.CARROTS.defaultBlockState());
		}
		return m;
	}

	/**
	 * Survival has no farmland item, so the Printer makes it by hand: two spots are grass and
	 * are hoed where they are; the third is a hole, so dirt from the inventory goes in first
	 * and is then hoed. Carrots go on top, on farmland, which is not a full block.
	 */
	private void testPrinterFarmland(ClientGameTestContext context, TestSingleplayerContext sp) {
		TestServerContext server = sp.getServer();
		TestInput input = context.getInput();
		LOG.info("Testing the Printer making farmland");
		command(server, "/fill 357 -60 -44 365 -55 -34 minecraft:air");
		command(server, "/fill 357 -61 -44 365 -61 -34 minecraft:grass_block");
		command(server, "/setblock 362 -61 -40 minecraft:air");
		command(server, "/gamemode survival @a");
		command(server, "/clear @a");
		command(server, "/item replace entity @a hotbar.0 with minecraft:iron_hoe");
		command(server, "/item replace entity @a hotbar.2 with minecraft:carrot 8");
		command(server, "/item replace entity @a inventory.0 with minecraft:dirt 4");
		writeSchematic(FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("lumen_farm.litematic"), "Lumen farm test",
				new BlockPos(3, 2, 1), farmExpected());
		command(server, "/tp @a 361.5 -60 -37.5 180 45");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(10);
		input.lookAt(180f, 45f);
		context.waitTicks(2);

		context.runOnClient(mc -> {
			mc.player.getInventory().setSelectedSlot(0);
			Module printer = Lumen.modules().printer;
			((dev.lumen.client.setting.TextSetting) setting(printer, "File")).set("lumen_farm");
			((dev.lumen.client.setting.TextSetting) setting(printer, "Origin")).set("360 -61 -40");
			printer.setEnabled(true);
		});
		for (int i = 0; i < 100; i++) {
			context.waitTicks(4);
			if (!context.computeOnClient(mc -> Lumen.modules().printer.isEnabled())) break;
		}
		context.waitTicks(5);
		context.takeScreenshot("lumen_37_printer_farmland");

		String built = context.computeOnClient(mc -> {
			StringBuilder wrong = new StringBuilder();
			for (var entry : farmExpected().entrySet()) {
				var world = mc.level.getBlockState(new BlockPos(360, -61, -40).offset(entry.getKey()));
				if (!world.is(entry.getValue().getBlock())) wrong.append(' ').append(entry.getKey().toShortString()).append(" is ").append(world).append(';');
			}
			return wrong.toString();
		});
		String carried = context.computeOnClient(mc -> count(mc.player, net.minecraft.world.item.Items.DIRT) + " dirt, "
				+ count(mc.player, net.minecraft.world.item.Items.CARROT) + " carrots, "
				+ count(mc.player, net.minecraft.world.item.Items.IRON_HOE) + " hoe");
		boolean finished = context.computeOnClient(mc -> !Lumen.modules().printer.isEnabled());
		LOG.info("Printer farmland: finished {}, carrying {}, wrong:{}", finished, carried, built.isEmpty() ? " none" : built);
		context.runOnClient(mc -> Lumen.modules().printer.setEnabled(false));
		command(server, "/gamemode creative @a");

		if (!built.isEmpty()) throw new AssertionError("The farm was not built:" + built);
		if (!finished) throw new AssertionError("The Printer did not finish the farm");
		if (!carried.equals("3 dirt, 5 carrots, 1 hoe")) throw new AssertionError("Left carrying " + carried);
	}

	/** A one-region schematic of the given blocks, keyed by position from its lowest corner. */
	private static void writeSchematic(Path file, String name, BlockPos size,
			java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks) {
		net.minecraft.nbt.CompoundTag regions = new net.minecraft.nbt.CompoundTag();
		regions.put("Main", litematicRegion(new BlockPos(0, 0, 0), size, size.getX(), size.getY(), size.getZ(), blocks));
		net.minecraft.nbt.CompoundTag meta = new net.minecraft.nbt.CompoundTag();
		meta.putString("Name", name);
		net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
		root.putInt("MinecraftDataVersion", 4500);
		root.putInt("Version", 7);
		root.putInt("SubVersion", 1);
		root.put("Metadata", meta);
		root.put("Regions", regions);
		try {
			Files.createDirectories(file.getParent());
			net.minecraft.nbt.NbtIo.writeCompressed(root, file);
		} catch (java.io.IOException e) {
			throw new AssertionError("Could not write the test schematic", e);
		}
	}

	private static java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> buyExpected() {
		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> m = new java.util.LinkedHashMap<>();
		var obsidian = net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState();
		var glass = net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState();
		for (int x = 0; x < 4; x++) {
			m.put(new BlockPos(x, 0, 0), obsidian);
			m.put(new BlockPos(x, 0, 1), glass);
		}
		m.put(new BlockPos(0, 1, 1), glass);
		m.put(new BlockPos(1, 1, 1), glass);
		m.put(new BlockPos(3, 1, 0), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
		return m;
	}

	/**
	 * Survival, empty inventory: the Printer has to buy every block. Obsidian is in the
	 * stand-in /shop's Gear category; glass is not in the shop, so it comes from /ah, where
	 * the cheapest way to cover 6 is 4 for $40 and then 8 for $480 (not the $3.2K stack, the
	 * $1,000-each listing or the glass panes); oak logs cost more than the $100 limit and
	 * are refused. Chest Stealer stays on and must leave the shop's menus alone.
	 */
	private void testPrinterBuying(ClientGameTestContext context, TestSingleplayerContext sp) {
		TestServerContext server = sp.getServer();
		TestInput input = context.getInput();
		LOG.info("Testing the Printer buying blocks");
		server.runOnServer(s -> MockDonutServer.reset());
		command(server, "/fill 336 -60 -44 346 -54 -33 minecraft:air");
		command(server, "/gamemode survival @a");
		command(server, "/clear @a");
		command(server, "/effect clear @a");
		writeSchematic(FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("lumen_buy.litematic"), "Lumen buy test",
				new BlockPos(4, 2, 2), buyExpected());
		command(server, "/tp @a 341.5 -60 -36.5 180 30");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(10);
		input.lookAt(180f, 30f);
		context.waitTicks(2);

		context.runOnClient(mc -> {
			Lumen.modules().chestStealer.setEnabled(true);
			Module printer = Lumen.modules().printer;
			((dev.lumen.client.setting.TextSetting) setting(printer, "File")).set("lumen_buy");
			((dev.lumen.client.setting.TextSetting) setting(printer, "Origin")).set("340 -60 -40");
			((BoolSetting) setting(printer, "Buy missing")).set(true);
			((dev.lumen.client.setting.TextSetting) setting(printer, "Max price each")).set("100");
			((dev.lumen.client.setting.TextSetting) setting(printer, "Max spend")).set("5k");
			printer.setEnabled(true);
		});

		boolean shotMenu = false;
		boolean done = false;
		for (int i = 0; i < 400 && !done; i++) {
			context.waitTicks(3);
			if (!shotMenu && context.computeOnClient(mc -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen)) {
				context.waitTicks(4);
				context.takeScreenshot("lumen_35_printer_shop_menu");
				shotMenu = true;
			}
			done = context.computeOnClient(mc -> {
				var printer = Lumen.modules().printer;
				return printer.correct() == 10 && !printer.buying()
						&& printer.couldNotBuy().contains(net.minecraft.world.item.Items.OAK_LOG);
			});
		}
		context.waitTicks(10);
		context.takeScreenshot("lumen_36_printer_bought");

		String built = context.computeOnClient(mc -> {
			StringBuilder wrong = new StringBuilder();
			for (var entry : buyExpected().entrySet()) {
				var world = mc.level.getBlockState(new BlockPos(340, -60, -40).offset(entry.getKey()));
				boolean log = entry.getValue().is(net.minecraft.world.level.block.Blocks.OAK_LOG);
				// The oak log could not be bought, so its spot must still be empty.
				boolean ok = log ? world.isAir() : dev.lumen.client.modules.Printer.matches(world, entry.getValue());
				if (!ok) wrong.append(' ').append(entry.getKey().toShortString()).append(" is ").append(world).append(';');
			}
			return wrong.toString();
		});
		java.util.UUID uuid = context.computeOnClient(mc -> mc.player.getUUID());
		double balance = server.computeOnServer(s -> MockDonutServer.balance(uuid));
		List<String> purchases = server.computeOnServer(s -> MockDonutServer.purchases());
		int clicks = server.computeOnServer(s -> MockDonutServer.clicks());
		double spent = context.computeOnClient(mc -> Lumen.modules().printer.spent());
		String carried = context.computeOnClient(mc -> count(mc.player, net.minecraft.world.item.Items.GLASS) + " glass, "
				+ count(mc.player, net.minecraft.world.item.Items.GLASS_PANE) + " glass panes, "
				+ count(mc.player, net.minecraft.world.item.Items.OBSIDIAN) + " obsidian");
		boolean menuClosed = context.computeOnClient(mc -> mc.gui.screen() == null);
		LOG.info("Printer buying: purchases {}, spent {}, balance {}, {} menu clicks, carrying {}, menu closed {}, wrong:{}",
				purchases, spent, balance, clicks, carried, menuClosed, built.isEmpty() ? " none" : built);

		context.runOnClient(mc -> {
			Lumen.modules().printer.setEnabled(false);
			Lumen.modules().chestStealer.setEnabled(false);
			((BoolSetting) setting(Lumen.modules().printer, "Buy missing")).set(false);
		});
		command(server, "/gamemode creative @a");

		if (!done) throw new AssertionError("The Printer did not finish buying and building; wrong:" + built);
		if (!built.isEmpty()) throw new AssertionError("The bought blocks were not all placed:" + built);
		List<String> sorted = new java.util.ArrayList<>(purchases);
		java.util.Collections.sort(sorted);
		if (!sorted.equals(List.of("ah 4 glass $40", "ah 8 glass $480", "shop 4 obsidian $240"))) {
			throw new AssertionError("The Printer bought " + purchases);
		}
		if (Math.abs(balance - (MockDonutServer.START_BALANCE - 760)) > 0.001) throw new AssertionError("Balance is " + balance + ", expected 9240");
		if (Math.abs(spent - 760) > 0.001) throw new AssertionError("The Printer counted " + spent + " spent, expected 760");
		if (!carried.equals("6 glass, 0 glass panes, 0 obsidian")) throw new AssertionError("Left carrying " + carried);
		if (!menuClosed) throw new AssertionError("The Printer left a shop menu open");
	}

	private void testPrinter(ClientGameTestContext context, TestSingleplayerContext sp) {
		TestServerContext server = sp.getServer();
		TestInput input = context.getInput();
		LOG.info("Testing the Printer");
		command(server, "/gamemode creative @a");
		command(server, "/clear @a");
		writeTestSchematic(FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("lumen_test.litematic"));

		// The player stands in the gap between the two regions, facing north toward the build.
		command(server, "/tp @a 321.5 -60 -36.5 180 30");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(10);
		input.lookAt(180f, 30f);
		context.waitTicks(2);

		context.runOnClient(mc -> {
			Module printer = Lumen.modules().printer;
			((dev.lumen.client.setting.TextSetting) setting(printer, "File")).set("lumen_test");
			((dev.lumen.client.setting.TextSetting) setting(printer, "Origin")).set("320 -60 -40");
			printer.setEnabled(true);
		});
		context.waitTicks(2);

		// First, the reader must have decoded exactly what was written.
		String decoded = context.computeOnClient(mc -> {
			var schematic = Lumen.modules().printer.schematic();
			if (schematic == null) return "not loaded";
			java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> got = new java.util.HashMap<>();
			for (var e : schematic.blocks()) got.put(e.pos(), e.state());
			return got.equals(printerExpected()) ? "ok" : "got " + got;
		});
		LOG.info("Printer: schematic decoded {}", decoded);
		if (!decoded.equals("ok")) throw new AssertionError("The schematic did not decode as written: " + decoded);

		context.waitTicks(4);
		context.takeScreenshot("lumen_33_printer");
		for (int i = 0; i < 60; i++) {
			boolean running = context.computeOnClient(mc -> Lumen.modules().printer.isEnabled());
			if (!running) break;
			context.waitTicks(5);
		}
		context.takeScreenshot("lumen_34_printer_done");

		String result = context.computeOnClient(mc -> {
			StringBuilder wrong = new StringBuilder();
			int ok = 0;
			for (var entry : printerExpected().entrySet()) {
				var world = mc.level.getBlockState(new BlockPos(320, -60, -40).offset(entry.getKey()));
				if (dev.lumen.client.modules.Printer.matches(world, entry.getValue())) ok++;
				else wrong.append(' ').append(entry.getKey().toShortString()).append(" is ").append(world).append(';');
			}
			return ok + " of " + printerExpected().size() + " right" + (wrong.isEmpty() ? "" : ":" + wrong);
		});
		int placed = context.computeOnClient(mc -> Lumen.modules().printer.placed());
		LOG.info("Printer: {} ({} placements)", result, placed);
		if (!result.startsWith(printerExpected().size() + " of")) throw new AssertionError("Printer built " + result);
	}

	/**
	 * A sealed stone room at midnight, photographed with Fullbright off and then on. The
	 * centre of the second picture must be much brighter, measured from the pixels.
	 */
	private void testFullbright(ClientGameTestContext context, TestSingleplayerContext sp) {
		TestServerContext server = sp.getServer();
		TestInput input = context.getInput();
		LOG.info("Testing Fullbright");
		command(server, "/time set midnight");
		command(server, "/fill 268 -61 -44 274 -55 -36 minecraft:stone hollow");
		command(server, "/tp @a 271.5 -60 -40.5 0 0");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(20);
		input.lookAt(0f, 10f);
		context.waitTicks(5);

		double dark = brightness(context.takeScreenshot("lumen_31_dark_room"));
		context.runOnClient(mc -> Lumen.modules().fullbright.setEnabled(true));
		context.waitTicks(10);
		boolean nightVision = context.computeOnClient(mc -> mc.player.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION));
		double lit = brightness(context.takeScreenshot("lumen_32_fullbright"));
		context.runOnClient(mc -> Lumen.modules().fullbright.setEnabled(false));
		context.waitTicks(3);
		boolean removed = context.computeOnClient(mc -> !mc.player.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION));
		command(server, "/time set noon");

		LOG.info("Fullbright: centre brightness {} in the dark room, {} with Fullbright; night vision {}, removed after {}",
				dark, lit, nightVision, removed);
		if (!nightVision) throw new AssertionError("Fullbright did not give the client night vision");
		if (lit < dark + 40) throw new AssertionError("Fullbright only raised the brightness from " + dark + " to " + lit);
		if (!removed) throw new AssertionError("Turning Fullbright off did not take its night vision away");
	}

	/** Average brightness, 0 to 255, of the middle third of a screenshot, away from the HUD. */
	private static double brightness(Path screenshot) {
		try {
			java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(screenshot.toFile());
			int w = image.getWidth();
			int h = image.getHeight();
			long sum = 0;
			int n = 0;
			for (int y = h / 3; y < 2 * h / 3; y += 2) {
				for (int x = w / 3; x < 2 * w / 3; x += 2) {
					int rgb = image.getRGB(x, y);
					sum += ((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114;
					n++;
				}
			}
			return sum / 1000.0 / n;
		} catch (java.io.IOException e) {
			throw new AssertionError("Could not read the screenshot " + screenshot, e);
		}
	}

	/**
	 * Freecam the way it is played: survival, walking forward and sideways while
	 * sprinting, jumping and looking around with the mouse. The body must not move or turn.
	 */
	private void testFreecamSurvival(ClientGameTestContext context, TestSingleplayerContext sp) {
		TestServerContext server = sp.getServer();
		TestInput input = context.getInput();
		LOG.info("Testing Freecam in survival with every movement key and the mouse");
		command(server, "/gamemode survival @a");
		command(server, "/tp @a 250.5 -60 -40.5 0 0");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(10);
		input.lookAt(0f, 0f);
		context.waitTicks(2);

		Vec3 bodyBefore = context.computeOnClient(mc -> mc.player.position());
		float[] rotBefore = context.computeOnClient(mc -> new float[] {mc.player.getYRot(), mc.player.getXRot()});
		context.runOnClient(mc -> Lumen.modules().freecam.setEnabled(true));
		context.waitTicks(2);

		input.holdKey(options -> options.keyUp);
		input.holdKey(options -> options.keyLeft);
		input.holdKey(options -> options.keySprint);
		for (int i = 0; i < 6; i++) {
			input.moveCursor(120, 40);
			input.pressKey(options -> options.keyJump);
			context.waitTicks(5);
		}
		input.releaseKey(options -> options.keyUp);
		input.releaseKey(options -> options.keyLeft);
		input.releaseKey(options -> options.keySprint);
		context.waitTicks(10);

		Vec3 bodyAfter = context.computeOnClient(mc -> mc.player.position());
		float[] rotAfter = context.computeOnClient(mc -> new float[] {mc.player.getYRot(), mc.player.getXRot()});
		float camYaw = context.computeOnClient(mc -> Lumen.modules().freecam.yaw());
		Vec3 camera = context.computeOnClient(mc -> mc.gameRenderer.mainCamera().position());
		context.takeScreenshot("lumen_30_freecam_survival");
		context.runOnClient(mc -> Lumen.modules().freecam.setEnabled(false));
		context.waitTicks(3);

		double moved = Math.hypot(bodyAfter.x - bodyBefore.x, bodyAfter.z - bodyBefore.z);
		double rose = bodyAfter.y - bodyBefore.y;
		LOG.info("Freecam survival: body moved {} sideways and {} up; body rotation {}/{} -> {}/{}; camera yaw {}, camera {} blocks away",
				moved, rose, rotBefore[0], rotBefore[1], rotAfter[0], rotAfter[1], camYaw, camera.distanceTo(bodyAfter));
		if (moved > 0.05 || Math.abs(rose) > 0.05) {
			throw new AssertionError("The body moved " + moved + " sideways and " + rose + " up while Freecam was on");
		}
		if (rotAfter[0] != rotBefore[0] || rotAfter[1] != rotBefore[1]) {
			throw new AssertionError("The body turned while Freecam was on: " + rotBefore[0] + "/" + rotBefore[1] + " -> " + rotAfter[0] + "/" + rotAfter[1]);
		}
		if (camYaw == rotBefore[0]) throw new AssertionError("Moving the mouse did not turn the Freecam camera");
		if (camera.distanceTo(bodyAfter) < 3) throw new AssertionError("The Freecam camera did not fly away from the body");
	}

	private void testFakeName(ClientGameTestContext context) {
		TestInput input = context.getInput();
		LOG.info("Testing Fake Name and the text field");
		context.runOnClient(mc -> Lumen.modules().fakeName.setEnabled(true));

		// Type the name through the click GUI, the way a player would.
		input.pressKey(Lumen.clickGuiKey());
		context.waitForScreen(ClickGuiScreen.class);
		context.waitTicks(5);
		input.typeChars("fake name");
		context.waitTicks(10);
		List<String> visible = context.computeOnClient(mc -> ((ClickGuiScreen) mc.gui.screen()).visibleModules());
		if (!visible.equals(List.of("Fake Name"))) throw new AssertionError("Searching 'fake name' showed " + visible);
		int scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		// Client is the third panel, and with the search on, Fake Name is its first row.
		int clientCenterX = 16 + 2 * (136 + 12) + 68;
		input.setCursorPos(clientCenterX * scale, STORAGE_ROW_Y * scale);
		input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(15);
		// Below the row: 2 of padding, the keybind row (14), then the Name label with its box 12 to 24 down.
		int fieldY = STORAGE_ROW_Y - 8 + 16 + 2 + 14 + 18;
		input.setCursorPos(clientCenterX * scale, fieldY * scale);
		input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		boolean editing = context.computeOnClient(mc -> ((ClickGuiScreen) mc.gui.screen()).isEditingText());
		if (!editing) {
			context.takeScreenshot("lumen_28_fake_name_field");
			throw new AssertionError("Clicking the Name field did not start editing it");
		}
		input.typeChars("Lumen-Fake");
		context.waitTicks(3);
		context.takeScreenshot("lumen_28_fake_name_field");
		input.pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(2);
		String typed = context.computeOnClient(mc -> (String) setting(Lumen.modules().fakeName, "Name").get());
		LOG.info("Fake Name: the field saved '{}'", typed);
		if (!"Lumen-Fake".equals(typed)) throw new AssertionError("The Name field saved '" + typed + "', expected 'Lumen-Fake'");
		input.pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(2);
		input.pressKey(InputConstants.KEY_ESCAPE);
		context.waitForScreen(null);

		// Every string drawn on screen is broken into characters here.
		String drawn = context.computeOnClient(mc -> drawnText("<Lumen-Bot> hello"));
		LOG.info("Fake Name: '<Lumen-Bot> hello' draws as '{}'", drawn);
		if (!drawn.equals("<Lumen-Fake> hello")) throw new AssertionError("With Fake Name on, the text draws as '" + drawn + "'");

		context.runOnClient(mc -> mc.gui.hud.getChat().addClientSystemMessage(
				net.minecraft.network.chat.Component.literal("Lumen-Bot joined the game")));
		input.holdKey(options -> options.keyPlayerList);
		context.waitTicks(5);
		context.takeScreenshot("lumen_29_fake_name");
		input.releaseKey(options -> options.keyPlayerList);

		context.runOnClient(mc -> Lumen.modules().fakeName.setEnabled(false));
		String off = context.computeOnClient(mc -> drawnText("<Lumen-Bot> hello"));
		if (!off.equals("<Lumen-Bot> hello")) throw new AssertionError("With Fake Name off, the text draws as '" + off + "'");
	}

	/** The characters a piece of text is drawn as, after any client-side rewriting. */
	private static String drawnText(String text) {
		StringBuilder sb = new StringBuilder();
		net.minecraft.util.StringDecomposer.iterateFormatted(text, net.minecraft.network.chat.Style.EMPTY, (index, style, codepoint) -> {
			sb.appendCodePoint(codepoint);
			return true;
		});
		return sb.toString();
	}

	private void testUtility(ClientGameTestContext context, TestSingleplayerContext sp) {
		TestServerContext server = sp.getServer();
		TestInput input = context.getInput();
		LOG.info("Testing utility and movement modules");

		command(server, "/clear @a");
		command(server, "/kill @e[type=minecraft:zombie]");
		command(server, "/effect give @a minecraft:resistance infinite 4 true");
		// Saturation keeps food full, which sprinting needs.
		command(server, "/effect give @a minecraft:saturation infinite 0 true");

		// ---- Auto Tool: the pickaxe while mining stone, then back to the stick ----
		command(server, "/tp @a 200.5 -60 -40.5 0 0");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(10);
		command(server, "/setblock 200 -59 -38 minecraft:stone");
		command(server, "/item replace entity @a hotbar.0 with minecraft:stick");
		command(server, "/item replace entity @a hotbar.3 with minecraft:diamond_pickaxe");
		command(server, "/item replace entity @a hotbar.5 with minecraft:diamond_shovel");
		context.waitTicks(3);
		context.runOnClient(mc -> {
			mc.player.getInventory().setSelectedSlot(0);
			Lumen.modules().autoTool.setEnabled(true);
		});
		lookAt(context, input, new Vec3(200.5, -58.5, -37.5));
		context.waitTicks(3);
		input.holdKey(options -> options.keyAttack);
		context.waitTicks(3);
		int miningSlot = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		input.releaseKey(options -> options.keyAttack);
		context.waitTicks(5);
		int afterSlot = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		LOG.info("Auto Tool: slot {} while mining stone, {} after", miningSlot, afterSlot);
		if (miningSlot != 3) throw new AssertionError("Auto Tool held slot " + miningSlot + " while mining stone, expected the pickaxe in 3");
		if (afterSlot != 0) throw new AssertionError("Auto Tool did not switch back to slot 0, it is on " + afterSlot);
		context.runOnClient(mc -> Lumen.modules().autoTool.setEnabled(false));

		// ---- Chest Stealer: empties a real chest and closes it ----
		command(server, "/setblock 202 -60 -38 minecraft:chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:5},"
				+ "{Slot:13b,id:\"minecraft:emerald\",count:3},{Slot:26b,id:\"minecraft:gold_ingot\",count:7}]}");
		context.waitTicks(5);
		context.runOnClient(mc -> Lumen.modules().chestStealer.setEnabled(true));
		lookAt(context, input, new Vec3(202.5, -59.5, -37.5));
		context.waitTicks(3);
		input.pressKey(options -> options.keyUse);
		context.waitTicks(4);
		context.takeScreenshot("lumen_25_chest_stealer");
		context.waitTicks(40);
		String loot = context.computeOnClient(mc -> count(mc.player, net.minecraft.world.item.Items.DIAMOND) + " diamonds, "
				+ count(mc.player, net.minecraft.world.item.Items.EMERALD) + " emeralds, "
				+ count(mc.player, net.minecraft.world.item.Items.GOLD_INGOT) + " gold");
		boolean closed = context.computeOnClient(mc -> mc.gui.screen() == null);
		LOG.info("Chest Stealer: took {}, menu closed {}", loot, closed);
		if (!loot.equals("5 diamonds, 3 emeralds, 7 gold")) throw new AssertionError("Chest Stealer took " + loot);
		if (!closed) throw new AssertionError("Chest Stealer did not close the emptied chest");
		context.runOnClient(mc -> Lumen.modules().chestStealer.setEnabled(false));

		// ---- Anti AFK ----
		float yawBefore = context.computeOnClient(mc -> mc.player.getYRot());
		context.runOnClient(mc -> {
			Module afk = Lumen.modules().antiAfk;
			((NumberSetting) setting(afk, "Interval")).set(5.0);
			((NumberSetting) setting(afk, "Jitter")).set(0.0);
			afk.setEnabled(true);
		});
		context.waitTicks(110);
		int afkActions = context.computeOnClient(mc -> Lumen.modules().antiAfk.actions());
		float yawAfter = context.computeOnClient(mc -> mc.player.getYRot());
		LOG.info("Anti AFK: {} rounds in 5.5 seconds at a 5 second interval, yaw {} -> {}", afkActions, yawBefore, yawAfter);
		if (afkActions < 1) throw new AssertionError("Anti AFK did nothing in 5.5 seconds at a 5 second interval");
		if (yawAfter == yawBefore) throw new AssertionError("Anti AFK's turn did not change the yaw");
		context.runOnClient(mc -> Lumen.modules().antiAfk.setEnabled(false));

		// ---- Auto Walk and Auto Sprint ----
		command(server, "/tp @a 200.5 -60 -30.5 0 0");
		context.waitTicks(5);
		input.lookAt(0f, 0f);
		Vec3 walkStart = context.computeOnClient(mc -> mc.player.position());
		context.runOnClient(mc -> {
			Lumen.modules().autoWalk.setEnabled(true);
			Lumen.modules().autoSprint.setEnabled(true);
		});
		context.waitTicks(30);
		boolean sprinting = context.computeOnClient(mc -> mc.player.isSprinting());
		double walked = context.computeOnClient(mc -> mc.player.position().distanceTo(walkStart));
		context.runOnClient(mc -> {
			Lumen.modules().autoWalk.setEnabled(false);
			Lumen.modules().autoSprint.setEnabled(false);
		});
		LOG.info("Auto Walk and Auto Sprint: moved {} blocks in 1.5 seconds, sprinting {}", walked, sprinting);
		if (walked < 4) throw new AssertionError("Auto Walk only moved " + walked + " blocks in 1.5 seconds");
		if (!sprinting) throw new AssertionError("Auto Sprint did not make the player sprint");

		// ---- Safe Walk: stays on a floating block; without it, falls off ----
		// Standing at z -40.5 means standing over block z -41.
		command(server, "/setblock 210 -50 -41 minecraft:stone");
		command(server, "/tp @a 210.5 -49 -40.5 0 0");
		context.waitTicks(10);
		context.runOnClient(mc -> {
			Lumen.modules().safeWalk.setEnabled(true);
			Lumen.modules().autoWalk.setEnabled(true);
		});
		context.waitTicks(30);
		double safeY = context.computeOnClient(mc -> mc.player.getY());
		context.runOnClient(mc -> Lumen.modules().safeWalk.setEnabled(false));
		context.waitTicks(30);
		double unsafeY = context.computeOnClient(mc -> mc.player.getY());
		context.runOnClient(mc -> Lumen.modules().autoWalk.setEnabled(false));
		LOG.info("Safe Walk: y {} with it walking at the edge, y {} after turning it off", safeY, unsafeY);
		if (safeY < -49.1) throw new AssertionError("With Safe Walk on, the player still walked off the block (y " + safeY + ")");
		if (unsafeY > -50) throw new AssertionError("The control failed: with Safe Walk off the player stayed up (y " + unsafeY + ")");

		// ---- Scaffold: bridges out from a floating block ----
		command(server, "/setblock 220 -50 -41 minecraft:stone");
		command(server, "/tp @a 220.5 -49 -40.5 0 0");
		command(server, "/item replace entity @a hotbar.0 with minecraft:cobblestone 64");
		context.waitTicks(10);
		context.runOnClient(mc -> {
			mc.player.getInventory().setSelectedSlot(0);
			Lumen.modules().scaffold.setEnabled(true);
			Lumen.modules().autoWalk.setEnabled(true);
		});
		context.waitTicks(40);
		context.takeScreenshot("lumen_26_scaffold");
		int bridge = context.computeOnClient(mc -> {
			int n = 0;
			for (int z = -40; z <= -20; z++) {
				if (mc.level.getBlockState(new BlockPos(220, -50, z)).is(net.minecraft.world.level.block.Blocks.COBBLESTONE)) n++;
			}
			return n;
		});
		double bridgeY = context.computeOnClient(mc -> mc.player.getY());
		context.runOnClient(mc -> {
			Lumen.modules().scaffold.setEnabled(false);
			Lumen.modules().autoWalk.setEnabled(false);
		});
		LOG.info("Scaffold: {} blocks of bridge, player at y {}", bridge, bridgeY);
		if (bridge < 5) throw new AssertionError("Scaffold only placed " + bridge + " blocks of bridge in 2 seconds");
		if (bridgeY < -49.5) throw new AssertionError("The player fell off the Scaffold bridge (y " + bridgeY + ")");

		// ---- Elytra+: holds pitch and fires a rocket in real flight ----
		command(server, "/item replace entity @a armor.chest with minecraft:elytra");
		command(server, "/item replace entity @a hotbar.1 with minecraft:firework_rocket 16");
		command(server, "/tp @a 230.5 0 -40.5 0 0");
		context.waitTicks(8);
		input.holdKeyFor(options -> options.keyJump, 2);
		context.waitTicks(3);
		boolean gliding = context.computeOnClient(mc -> mc.player.isFallFlying());
		LOG.info("Elytra+: gliding after the jump {}", gliding);
		if (!gliding) throw new AssertionError("The test could not start elytra flight");
		context.runOnClient(mc -> {
			// A high minimum speed makes it fire straight away, so one rocket is certain.
			((NumberSetting) setting(Lumen.modules().elytraPlus, "Min speed")).set(40.0);
			Lumen.modules().elytraPlus.setEnabled(true);
		});
		context.waitTicks(30);
		context.takeScreenshot("lumen_27_elytra");
		boolean stillGliding = context.computeOnClient(mc -> mc.player.isFallFlying());
		float flightPitch = context.computeOnClient(mc -> mc.player.getXRot());
		int rockets = context.computeOnClient(mc -> Lumen.modules().elytraPlus.rockets());
		context.runOnClient(mc -> Lumen.modules().elytraPlus.setEnabled(false));
		command(server, "/item replace entity @a armor.chest with minecraft:air");
		command(server, "/tp @a 230.5 -60 -40.5 0 0");
		LOG.info("Elytra+: gliding {}, pitch {} (target 4), rockets {}", stillGliding, flightPitch, rockets);
		if (!stillGliding) throw new AssertionError("Elytra flight stopped with Elytra+ on");
		if (Math.abs(flightPitch - 4f) > 1.5f) throw new AssertionError("Elytra+ held pitch " + flightPitch + ", expected 4");
		if (rockets < 1) throw new AssertionError("Elytra+ did not fire a rocket below its minimum speed");

		// ---- Trail Follower: picks the heading of a trail of old chunks ----
		java.util.Set<ChunkPos> trail = new java.util.HashSet<>();
		double trailYaw = Math.toRadians(-40);
		for (int d = 0; d <= 200; d += 4) {
			trail.add(new ChunkPos(net.minecraft.util.Mth.floor(8 - Math.sin(trailYaw) * d) >> 4,
					net.minecraft.util.Mth.floor(8 + Math.cos(trailYaw) * d) >> 4));
		}
		float heading = dev.lumen.client.modules.TrailFollower.bestHeading(8, 8, 0f, trail::contains, c -> false, 6, 60);
		float noTrail = dev.lumen.client.modules.TrailFollower.bestHeading(8, 8, 0f, c -> false, c -> false, 6, 60);
		LOG.info("Trail Follower: heading {} for a trail at -40, {} with no trail", heading, noTrail);
		if (Float.isNaN(heading) || Math.abs(net.minecraft.util.Mth.wrapDegrees(heading + 40f)) > 5f) {
			throw new AssertionError("Trail Follower chose heading " + heading + " for a trail at -40");
		}
		if (!Float.isNaN(noTrail)) throw new AssertionError("Trail Follower chose heading " + noTrail + " with no trail at all");
	}

	/** Turns the camera to look at a point from the player's eyes. */
	private static void lookAt(ClientGameTestContext context, TestInput input, Vec3 point) {
		float[] rot = context.computeOnClient(mc -> {
			Vec3 eye = mc.player.getEyePosition();
			double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
			return new float[] {(float) Math.toDegrees(Math.atan2(dz, dx)) - 90f,
					(float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))};
		});
		input.lookAt(rot[0], rot[1]);
	}

	private static int count(net.minecraft.world.entity.player.Player player, net.minecraft.world.item.Item item) {
		int n = 0;
		for (int i = 0; i < 36; i++) {
			if (player.getInventory().getItem(i).is(item)) n += player.getInventory().getItem(i).getCount();
		}
		return n;
	}

	private void testCrystalPvp(ClientGameTestContext context, TestSingleplayerContext sp) {
		TestServerContext server = sp.getServer();
		TestInput input = context.getInput();
		LOG.info("Testing crystal PvP modules");

		// Resistance V makes every explosion harmless, so the self-damage limits allow them.
		command(server, "/kill @e[type=minecraft:zombie]");
		command(server, "/effect give @a minecraft:resistance infinite 4 true");
		command(server, "/clear @a");

		// ---- Surround ----
		command(server, "/tp @a 130.5 -60 -40.5 0 0");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(10);
		command(server, "/item replace entity @a hotbar.0 with minecraft:obsidian 64");
		context.waitTicks(3);
		context.runOnClient(mc -> {
			mc.player.getInventory().setSelectedSlot(0);
			Lumen.modules().surround.setEnabled(true);
		});
		context.waitTicks(10);
		int open = context.computeOnClient(mc -> Lumen.modules().surround.missing(mc.player.blockPosition()).size());
		LOG.info("Surround: {} of 4 sides still open", open);
		if (open != 0) throw new AssertionError("Surround left " + open + " sides open");
		context.runOnClient(mc -> Lumen.modules().surround.setEnabled(false));
		command(server, "/fill 129 -60 -42 131 -60 -40 minecraft:air");

		// ---- Auto Trap ----
		command(server, "/summon minecraft:zombie 133.5 -60 -40.5 {NoAI:1b,Silent:1b}");
		context.waitTicks(5);
		input.lookAt(-90f, 10f);
		context.runOnClient(mc -> Lumen.modules().autoTrap.setEnabled(true));
		context.waitTicks(30);
		int trapBlocks = context.computeOnClient(mc -> {
			for (var e : mc.level.entitiesForRendering()) {
				if (!isZombie(e)) continue;
				int n = 0;
				for (BlockPos pos : Lumen.modules().autoTrap.plan(e.blockPosition())) {
					if (mc.level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)) n++;
				}
				return n;
			}
			return -1;
		});
		LOG.info("Auto Trap: {} of 10 trap blocks in place", trapBlocks);
		String trapState = context.computeOnClient(mc -> {
			StringBuilder sb = new StringBuilder();
			for (var e : mc.level.entitiesForRendering()) {
				if (!isZombie(e)) continue;
				sb.append("zombie at ").append(e.blockPosition().toShortString()).append(':');
				for (BlockPos pos : Lumen.modules().autoTrap.plan(e.blockPosition())) {
					sb.append(' ').append(pos.toShortString()).append('=')
							.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).getPath());
				}
			}
			return sb.toString();
		});
		LOG.info("Auto Trap blocks: {}", trapState);
		context.takeScreenshot("lumen_20_auto_trap");
		command(server, "/tp @a 130.5 -55 -40.5 -90 35");
		context.waitTicks(5);
		input.lookAt(-90f, 35f);
		context.takeScreenshot("lumen_20b_auto_trap_above");
		command(server, "/tp @a 130.5 -60 -40.5 -90 10");
		context.waitTicks(5);
		if (trapBlocks != 10) throw new AssertionError("Auto Trap placed " + trapBlocks + " of 10 blocks around the zombie");
		context.runOnClient(mc -> Lumen.modules().autoTrap.setEnabled(false));
		command(server, "/kill @e[type=minecraft:zombie]");
		command(server, "/fill 132 -60 -42 134 -58 -40 minecraft:air");

		// ---- Crystal Aura: it has to kill the zombie with crystals ----
		command(server, "/fill 131 -61 -43 136 -61 -38 minecraft:obsidian");
		command(server, "/summon minecraft:zombie 134.5 -60 -40.5 {NoAI:1b,Silent:1b}");
		command(server, "/item replace entity @a hotbar.1 with minecraft:end_crystal 16");
		context.waitTicks(5);
		input.lookAt(-90f, 15f);
		context.runOnClient(mc -> Lumen.modules().crystalAura.setEnabled(true));
		context.waitTicks(3);
		context.takeScreenshot("lumen_21_crystal_aura");
		boolean crystalKilled = waitForZombies(context, 0, 120);
		int crystalsPlaced = context.computeOnClient(mc -> Lumen.modules().crystalAura.placed());
		int crystalsBroken = context.computeOnClient(mc -> Lumen.modules().crystalAura.broken());
		LOG.info("Crystal Aura: zombie dead {}, crystals placed {}, broken {}", crystalKilled, crystalsPlaced, crystalsBroken);
		if (!crystalKilled || crystalsPlaced < 1 || crystalsBroken < 1) {
			throw new AssertionError("Crystal Aura did not kill the zombie with crystals (placed " + crystalsPlaced + ", broken " + crystalsBroken + ")");
		}
		context.runOnClient(mc -> Lumen.modules().crystalAura.setEnabled(false));
		command(server, "/kill @e[type=minecraft:end_crystal]");

		// ---- Anchor Aura: place, charge and set off anchors until the zombie dies ----
		command(server, "/tp @a 160.5 -60 -40.5 0 0");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(10);
		command(server, "/summon minecraft:zombie 163.5 -60 -40.5 {NoAI:1b,Silent:1b}");
		command(server, "/item replace entity @a hotbar.2 with minecraft:respawn_anchor 8");
		command(server, "/item replace entity @a hotbar.3 with minecraft:glowstone 16");
		context.waitTicks(5);
		input.lookAt(-90f, 15f);
		context.runOnClient(mc -> Lumen.modules().anchorAura.setEnabled(true));
		context.waitTicks(2);
		context.takeScreenshot("lumen_22_anchor_aura");
		boolean anchorKilled = waitForZombies(context, 0, 120);
		int anchorsPlaced = context.computeOnClient(mc -> Lumen.modules().anchorAura.placed());
		int anchorsDetonated = context.computeOnClient(mc -> Lumen.modules().anchorAura.detonated());
		LOG.info("Anchor Aura: zombie dead {}, anchors placed {}, detonated {}", anchorKilled, anchorsPlaced, anchorsDetonated);
		if (!anchorKilled || anchorsPlaced < 1 || anchorsDetonated < 1) {
			throw new AssertionError("Anchor Aura did not kill the zombie with anchors (placed " + anchorsPlaced + ", detonated " + anchorsDetonated + ")");
		}
		context.runOnClient(mc -> Lumen.modules().anchorAura.setEnabled(false));

		boolean alive = context.computeOnClient(mc -> mc.player.isAlive() && !(mc.gui.screen() instanceof DeathScreen));
		if (!alive) throw new AssertionError("The player died during the crystal tests");
	}

	private void testCombat(ClientGameTestContext context, TestSingleplayerContext sp) {
		TestServerContext server = sp.getServer();
		TestInput input = context.getInput();
		LOG.info("Testing combat modules");

		// Survival, so hits, arrows, eating and knockback behave as they do for players.
		command(server, "/gamemode survival @a");
		command(server, "/clear @a");
		command(server, "/effect give @a minecraft:resistance infinite 4 true");
		command(server, "/kill @e[type=minecraft:zombie]");

		// ---- Kill Aura with Criticals ----
		command(server, "/tp @a 40.5 -60 -40.5 0 0");
		command(server, "/item replace entity @a weapon.mainhand with minecraft:netherite_sword");
		command(server, "/summon minecraft:zombie 42.5 -60 -40.5 {NoAI:1b,Silent:1b}");
		// A barrier right behind each zombie stops knockback carrying it out of reach.
		command(server, "/fill 43 -60 -42 43 -58 -39 minecraft:barrier");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(5);
		// Face the zombie only so the screenshot shows it; Kill Aura does not need it.
		input.lookAt(-90f, 15f);
		context.runOnClient(mc -> {
			Lumen.modules().criticals.setEnabled(true);
			Lumen.modules().killAura.setEnabled(true);
		});
		context.waitTicks(4);
		context.takeScreenshot("lumen_18_kill_aura");
		boolean auraKilled = waitForZombies(context, 0, 160);
		int auraHits = context.computeOnClient(mc -> Lumen.modules().killAura.hits());
		int auraCrits = context.computeOnClient(mc -> Lumen.modules().killAura.crits());
		LOG.info("Kill Aura: zombie dead {}, hits {}, crits {}", auraKilled, auraHits, auraCrits);
		if (!auraKilled) throw new AssertionError("Kill Aura did not kill the zombie (" + auraHits + " hits)");
		if (auraCrits < 1) throw new AssertionError("Criticals did not land a single critical hit in " + auraHits + " hits");
		context.runOnClient(mc -> {
			Lumen.modules().killAura.setEnabled(false);
			Lumen.modules().criticals.setEnabled(false);
		});

		// ---- Trigger Bot ----
		command(server, "/summon minecraft:zombie 40.5 -60 -37.5 {NoAI:1b,Silent:1b}");
		command(server, "/fill 39 -60 -37 42 -58 -37 minecraft:barrier");
		context.waitTicks(5);
		input.lookAt(0f, 12f);
		context.runOnClient(mc -> Lumen.modules().triggerBot.setEnabled(true));
		boolean triggerKilled = waitForZombies(context, 0, 160);
		int triggerHits = context.computeOnClient(mc -> Lumen.modules().triggerBot.hits());
		LOG.info("Trigger Bot: zombie dead {}, hits {}", triggerKilled, triggerHits);
		if (!triggerKilled) throw new AssertionError("Trigger Bot did not kill the zombie under the crosshair (" + triggerHits + " hits)");
		context.runOnClient(mc -> Lumen.modules().triggerBot.setEnabled(false));
		command(server, "/fill 39 -60 -37 42 -58 -37 minecraft:air");

		// ---- Aim Assist ----
		command(server, "/summon minecraft:zombie 42.5 -60 -37 {NoAI:1b,Silent:1b}");
		context.waitTicks(5);
		input.lookAt(0f, 10f);
		context.runOnClient(mc -> {
			((BoolSetting) setting(Lumen.modules().aimAssist, "Only while clicking")).set(false);
			Lumen.modules().aimAssist.setEnabled(true);
		});
		context.waitTicks(25);
		float yaw = context.computeOnClient(mc -> mc.player.getYRot());
		// The yaw from the player's eyes to the zombie's centre, from where both actually are.
		float expectedYaw = context.computeOnClient(mc -> {
			for (var e : mc.level.entitiesForRendering()) {
				if (!isZombie(e)) continue;
				Vec3 c = e.getBoundingBox().getCenter();
				Vec3 eye = mc.player.getEyePosition();
				return (float) Math.toDegrees(Math.atan2(c.z - eye.z, c.x - eye.x)) - 90f;
			}
			return Float.NaN;
		});
		LOG.info("Aim Assist: yaw {} (target {}, started at 0)", yaw, expectedYaw);
		if (Float.isNaN(expectedYaw) || Math.abs(expectedYaw) < 15f) {
			throw new AssertionError("The Aim Assist zombie was not placed well off-aim (target yaw " + expectedYaw + ")");
		}
		if (Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - expectedYaw)) > 3f) {
			throw new AssertionError("Aim Assist turned to yaw " + yaw + ", expected about " + expectedYaw);
		}
		context.runOnClient(mc -> Lumen.modules().aimAssist.setEnabled(false));
		command(server, "/kill @e[type=minecraft:zombie]");

		// ---- Auto Clicker ----
		command(server, "/summon minecraft:zombie 40.5 -60 -38 {NoAI:1b,Silent:1b,Invulnerable:1b}");
		context.waitTicks(5);
		input.lookAt(0f, 14f);
		context.runOnClient(mc -> {
			((NumberSetting) setting(Lumen.modules().autoClicker, "Min CPS")).set(10.0);
			((NumberSetting) setting(Lumen.modules().autoClicker, "Max CPS")).set(10.0);
			Lumen.modules().autoClicker.setEnabled(true);
		});
		int clicksBefore = context.computeOnClient(mc -> Lumen.modules().autoClicker.clicks());
		input.holdKeyFor(options -> options.keyAttack, 40);
		int clicks = context.computeOnClient(mc -> Lumen.modules().autoClicker.clicks()) - clicksBefore;
		LOG.info("Auto Clicker: {} clicks in 2 seconds at 10 CPS", clicks);
		if (clicks < 15 || clicks > 25) throw new AssertionError("Auto Clicker made " + clicks + " clicks in 2 seconds at 10 CPS");
		context.runOnClient(mc -> Lumen.modules().autoClicker.setEnabled(false));
		command(server, "/kill @e[type=minecraft:zombie]");

		// ---- Bow Aimbot: the arrow has to actually hit ----
		command(server, "/item replace entity @a weapon.mainhand with minecraft:bow");
		command(server, "/give @a minecraft:arrow 16");
		command(server, "/summon minecraft:zombie 44.5 -60 -22.5 {NoAI:1b,Silent:1b}");
		context.waitTicks(5);
		input.lookAt(0f, 0f);
		context.runOnClient(mc -> Lumen.modules().bowAimbot.setEnabled(true));
		// Vanilla arrows spread a little at random, so allow up to three shots.
		float zombieHealth = -1f;
		for (int shot = 1; shot <= 3; shot++) {
			input.holdKeyFor(options -> options.keyUse, 25);
			context.waitTicks(30);
			zombieHealth = context.computeOnClient(mc -> {
				for (var e : mc.level.entitiesForRendering()) {
					if (isZombie(e) && e instanceof net.minecraft.world.entity.LivingEntity z) return z.getHealth();
				}
				return -1f;
			});
			LOG.info("Bow Aimbot: zombie health after shot {}: {}", shot, zombieHealth);
			if (zombieHealth >= 0 && zombieHealth < 20f) break;
		}
		if (zombieHealth < 0 || zombieHealth >= 20f) {
			throw new AssertionError("Bow Aimbot missed the zombie 18 blocks away three times (health " + zombieHealth + ")");
		}
		context.runOnClient(mc -> Lumen.modules().bowAimbot.setEnabled(false));
		command(server, "/kill @e[type=minecraft:zombie]");
		command(server, "/kill @e[type=minecraft:arrow]");

		// ---- Auto Totem ----
		command(server, "/clear @a");
		command(server, "/give @a minecraft:totem_of_undying 2");
		context.waitTicks(3);
		context.runOnClient(mc -> Lumen.modules().autoTotem.setEnabled(true));
		context.waitTicks(10);
		boolean totem = context.computeOnClient(mc -> mc.player.getOffhandItem().is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING));
		LOG.info("Auto Totem: totem in offhand {}", totem);
		if (!totem) throw new AssertionError("Auto Totem did not move a totem into the offhand");

		// ---- Auto Armor ----
		command(server, "/item replace entity @a armor.chest with minecraft:leather_chestplate");
		command(server, "/give @a minecraft:diamond_helmet");
		command(server, "/give @a minecraft:iron_chestplate");
		command(server, "/give @a minecraft:netherite_boots");
		context.waitTicks(3);
		context.runOnClient(mc -> Lumen.modules().autoArmor.setEnabled(true));
		context.waitTicks(30);
		String armor = context.computeOnClient(mc -> armorNames(mc.player));
		boolean leatherKept = context.computeOnClient(mc -> {
			int n = 0;
			for (int i = 0; i < 36; i++) {
				if (mc.player.getInventory().getItem(i).is(net.minecraft.world.item.Items.LEATHER_CHESTPLATE)) n++;
			}
			return n == 1;
		});
		LOG.info("Auto Armor: wearing {}, old chestplate kept in inventory {}", armor, leatherKept);
		if (!armor.equals("diamond_helmet,iron_chestplate,,netherite_boots")) {
			throw new AssertionError("Auto Armor left the player wearing " + armor);
		}
		if (!leatherKept) throw new AssertionError("Auto Armor lost the leather chestplate it replaced");

		// ---- Auto Gapple ----
		command(server, "/effect clear @a");
		command(server, "/item replace entity @a hotbar.4 with minecraft:golden_apple 3");
		context.runOnClient(mc -> {
			mc.player.getInventory().setSelectedSlot(0);
			Lumen.modules().autoGapple.setEnabled(true);
		});
		context.waitTicks(3);
		// Magic damage ignores the armor Auto Armor just put on.
		command(server, "/damage @a[limit=1] 13 minecraft:magic");
		context.waitTicks(2);
		float hurtHealth = context.computeOnClient(mc -> mc.player.getHealth());
		LOG.info("Auto Gapple: health after the hit {}", hurtHealth);
		if (hurtHealth > 10f) throw new AssertionError("The test hit only brought health down to " + hurtHealth);
		context.waitTicks(60);
		int eaten = context.computeOnClient(mc -> Lumen.modules().autoGapple.eaten());
		float absorption = context.computeOnClient(mc -> mc.player.getAbsorptionAmount());
		int slot = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		LOG.info("Auto Gapple: apples eaten {}, absorption {}, selected slot {}", eaten, absorption, slot);
		if (eaten < 1 || absorption <= 0) throw new AssertionError("Auto Gapple did not eat a golden apple at low health");
		if (slot != 0) throw new AssertionError("Auto Gapple did not switch back to the original hotbar slot");
		context.runOnClient(mc -> Lumen.modules().autoGapple.setEnabled(false));

		// ---- Velocity: knockback from the same blast, without and then with it ----
		command(server, "/effect give @a minecraft:resistance infinite 4 true");
		double without = blastKnockback(context, sp, 70);
		context.runOnClient(mc -> Lumen.modules().velocity.setEnabled(true));
		double with = blastKnockback(context, sp, 100);
		LOG.info("Velocity: blast moved the player {} blocks without it and {} with it", without, with);
		if (without < 1.0) throw new AssertionError("The test blast only moved the player " + without + " blocks, so it proves nothing");
		if (with > 0.3) throw new AssertionError("With Velocity on, the blast still moved the player " + with + " blocks");

		LOG.info("Screenshot: the Combat panel");
		input.pressKey(Lumen.clickGuiKey());
		context.waitForScreen(ClickGuiScreen.class);
		context.waitTicks(10);
		context.takeScreenshot("lumen_19_combat_gui");
		input.pressKey(InputConstants.KEY_ESCAPE);
		context.waitForScreen(null);
	}

	/** Waits until at most {@code count} zombies are alive, for up to {@code maxTicks}. */
	private static boolean waitForZombies(ClientGameTestContext context, int count, int maxTicks) {
		for (int waited = 0; waited < maxTicks; waited += 5) {
			long alive = context.computeOnClient(mc -> {
				long n = 0;
				for (var e : mc.level.entitiesForRendering()) {
					if (isZombie(e) && e.isAlive()) n++;
				}
				return n;
			});
			if (alive <= count) return true;
			context.waitTicks(5);
		}
		return false;
	}

	private static boolean isZombie(net.minecraft.world.entity.Entity entity) {
		return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().equals("zombie");
	}

	/** Detonates TNT two blocks east of the player at this x and returns how far it pushed them sideways. */
	private static double blastKnockback(ClientGameTestContext context, TestSingleplayerContext sp, int x) {
		TestServerContext server = sp.getServer();
		command(server, "/tp @a " + x + ".5 -60 -40.5 0 0");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(10);
		Vec3 start = context.computeOnClient(mc -> mc.player.position());
		command(server, "/summon minecraft:tnt " + (x + 2) + ".5 -60 -40.5 {fuse:0}");
		context.waitTicks(20);
		Vec3 end = context.computeOnClient(mc -> mc.player.position());
		return Math.sqrt((end.x - start.x) * (end.x - start.x) + (end.z - start.z) * (end.z - start.z));
	}

	private static String armorNames(net.minecraft.world.entity.player.Player player) {
		StringBuilder sb = new StringBuilder();
		net.minecraft.world.entity.EquipmentSlot[] slots = {
				net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};
		for (int i = 0; i < slots.length; i++) {
			if (i > 0) sb.append(',');
			var stack = player.getItemBySlot(slots[i]);
			if (!stack.isEmpty()) sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
		}
		return sb.toString();
	}

	private void testXRayAndSigns(ClientGameTestContext context, TestSingleplayerContext sp) {
		TestServerContext server = sp.getServer();
		TestInput input = context.getInput();
		LOG.info("Testing X-Ray and Sign Reader");

		checkCoordinates("Base at 1200 64 -3400", "1200 64 -3400");
		checkCoordinates("X: 1200 Z: -3400", "1200 -3400");
		checkCoordinates("x 100 y 70 z -200", "100 70 -200");
		checkCoordinates("stash 5000, -7000", "5000 -7000");
		checkCoordinates("Shop open 9 to 5", null);
		checkCoordinates("Welcome to spawn", null);

		// The flat world has no ores of its own, so these two are the only ones X-Ray can find.
		command(server, "/setblock -2 -63 -6 minecraft:diamond_ore");
		// Signs facing the player: one with coordinates, one without.
		command(server, "/setblock 3 -60 -6 minecraft:oak_sign[rotation=8]{front_text:{messages:[\"Base at\",\"1200 64 -3400\",\"\",\"\"]}}");
		command(server, "/setblock -4 -60 -6 minecraft:oak_sign[rotation=8]{front_text:{messages:[\"Welcome\",\"to spawn\",\"\",\"\"]}}");
		command(server, "/tp @a 0.5 -60 -11.5 0 30");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(5);
		input.lookAt(0f, 30f);

		context.runOnClient(mc -> {
			Lumen.modules().xRay.setEnabled(true);
			Lumen.modules().signReader.setEnabled(true);
		});
		context.waitTicks(3);
		// Placed after enabling, so the block update path is covered as well as the first scan.
		command(server, "/setblock 2 -62 -6 minecraft:ancient_debris");
		context.waitTicks(5);
		for (int i = 0; i < 80; i++) {
			int pending = context.computeOnClient(mc -> Lumen.modules().xRay.pending() + Lumen.modules().signReader.pending());
			if (pending == 0) break;
			context.waitTicks(5);
		}
		context.waitTicks(5);

		int ores = context.computeOnClient(mc -> Lumen.modules().xRay.count());
		List<SignReader.Sign> signs = context.computeOnClient(mc -> Lumen.modules().signReader.signs());
		LOG.info("X-Ray found {} blocks. Sign Reader found {}", ores, signs);
		if (ores != 2) throw new AssertionError("X-Ray found " + ores + " blocks, expected the diamond ore and the ancient debris");
		if (signs.size() != 2) throw new AssertionError("Sign Reader found " + signs.size() + " signs, expected 2");
		boolean coords = signs.stream().anyMatch(s -> s.pos().equals(new BlockPos(3, -60, -6)) && "1200 64 -3400".equals(s.coords()));
		boolean plain = signs.stream().anyMatch(s -> s.pos().equals(new BlockPos(-4, -60, -6)) && !s.hasCoords()
				&& s.front().equals(List.of("Welcome", "to spawn")));
		if (!coords) throw new AssertionError("Sign Reader did not read coordinates 1200 64 -3400 from the sign at 3, -60, -6");
		if (!plain) throw new AssertionError("Sign Reader did not read the plain sign at -4, -60, -6 correctly");

		Path signLog = FabricLoader.getInstance().getConfigDir().resolve("lumen").resolve("signs.csv");
		if (!Files.exists(signLog)) throw new AssertionError("Sign Reader did not write " + signLog);

		context.takeScreenshot("lumen_16_xray_signs");

		LOG.info("Screenshot: far markers on the scene from about 60 blocks away");
		command(server, "/tp @a 0.5 -56 -60.5 0 4");
		sp.getConnection().waitForChunksRender();
		context.waitTicks(10);
		input.lookAt(0f, 4f);
		context.waitTicks(5);
		context.takeScreenshot("lumen_17_far_markers");
	}

	private static void checkCoordinates(String text, String expected) {
		String got = SignReader.coordinates(text);
		LOG.info("Coordinates in \"{}\": {}", text, got);
		if (!java.util.Objects.equals(got, expected)) {
			throw new AssertionError("Coordinates in \"" + text + "\" read as " + got + ", expected " + expected);
		}
	}

	private void testInfoModules(ClientGameTestContext context, TestSingleplayerContext sp) {
		TestServerContext server = sp.getServer();
		TestInput input = context.getInput();
		LOG.info("Testing Nametags, Logout Spots, Sound Locator, Waypoints and Radar");

		command(server, "/tp @a 0.5 -60 -8.5 0 22");
		context.waitTicks(5);
		input.lookAt(0f, 22f);
		context.runOnClient(mc -> {
			Lumen.modules().nametags.setEnabled(true);
			((BoolSetting) setting(Lumen.modules().nametags, "Named mobs")).set(true);
			Lumen.modules().logoutSpots.setEnabled(true);
			Lumen.modules().soundLocator.setEnabled(true);
			Lumen.modules().radar.setEnabled(true);
		});
		context.waitTicks(2);

		LOG.info("Playing a loud sound about 90 blocks away");
		command(server, "/playsound minecraft:entity.generic.explode master @a 60 -60 60 8");
		context.waitTicks(5);
		List<SoundLocator.Marker> markers = context.computeOnClient(mc -> Lumen.modules().soundLocator.markers());
		LOG.info("Sound Locator markers: {}", markers);
		boolean located = markers.stream().anyMatch(m -> m.pos().distanceTo(new Vec3(60, -60, 60)) < 2);
		if (!located) throw new AssertionError("Sound Locator did not mark the explosion at 60, -60, 60");

		LOG.info("Adding a waypoint with its key");
		int waypointsBefore = context.computeOnClient(mc -> Lumen.modules().waypoints.here().size());
		input.pressKey(Lumen.addWaypointKey());
		context.waitTicks(3);
		int waypointsAfter = context.computeOnClient(mc -> Lumen.modules().waypoints.here().size());
		if (waypointsAfter != waypointsBefore + 1) {
			throw new AssertionError("The add-waypoint key did not add a waypoint (" + waypointsBefore + " -> " + waypointsAfter + ")");
		}
		context.runOnClient(mc -> Lumen.modules().waypoints.add("Stash", new BlockPos(21, -59, 22), false));
		context.waitTicks(5);
		context.takeScreenshot("lumen_14_info_overlays");

		LOG.info("Dying saves a death waypoint");
		long deathsBefore = context.computeOnClient(mc -> Lumen.modules().waypoints.here().stream().filter(w -> w.death).count());
		command(server, "/kill @a");
		context.waitForScreen(DeathScreen.class);
		context.waitTicks(40);
		long deathsAfter = context.computeOnClient(mc -> Lumen.modules().waypoints.here().stream().filter(w -> w.death).count());
		LOG.info("Death waypoints: {} -> {}", deathsBefore, deathsAfter);
		if (deathsAfter != deathsBefore + 1) throw new AssertionError("Dying did not save a death waypoint");
		context.clickScreenButton("deathScreen.respawn");
		context.waitForScreen(null);
		context.waitTicks(10);

		LOG.info("Opening the Waypoints screen from the click GUI");
		input.pressKey(Lumen.clickGuiKey());
		context.waitForScreen(ClickGuiScreen.class);
		context.waitTicks(10);
		int scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		int[] button = context.computeOnClient(mc -> ((ClickGuiScreen) mc.gui.screen()).waypointsButtonCenter());
		input.setCursorPos(button[0] * scale, button[1] * scale);
		input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitForScreen(WaypointsScreen.class);
		context.waitTicks(10);
		context.takeScreenshot("lumen_15_waypoints_screen");
		input.pressKey(InputConstants.KEY_ESCAPE);
		context.waitForScreen(ClickGuiScreen.class);
		input.pressKey(InputConstants.KEY_ESCAPE);
		context.waitForScreen(null);
	}

	private void testWorldFinders(ClientGameTestContext context, TestSingleplayerContext sp) {
		TestServerContext server = sp.getServer();
		TestInput input = context.getInput();
		LOG.info("Testing New Chunks, Stash Finder and Base Finder");

		context.runOnClient(mc -> Lumen.modules().newChunks.setEnabled(true));
		context.waitTicks(2);

		// Water poured on flat ground starts flowing, which is how a freshly generated chunk behaves.
		command(server, "/setblock 8 -60 40 minecraft:water");
		// A stash: 48 barrels in chunk (1, 1).
		command(server, "/fill 20 -60 20 23 -59 25 minecraft:barrel");
		// A base: blocks that never generate naturally, in chunk (-2, 1).
		command(server, "/setblock -24 -60 20 minecraft:ender_chest");
		command(server, "/setblock -22 -60 20 minecraft:beacon");
		command(server, "/setblock -20 -60 20 minecraft:respawn_anchor");
		command(server, "/setblock -24 -60 22 minecraft:crafting_table");
		// A stray block 30 above the base; the box should stay at the base, not stretch up to it.
		command(server, "/setblock -20 -30 22 minecraft:ender_chest");
		context.waitTicks(40);

		context.runOnClient(mc -> {
			Lumen.modules().stashFinder.setEnabled(true);
			Lumen.modules().baseFinder.setEnabled(true);
		});
		context.waitTicks(30);

		boolean newChunk = context.computeOnClient(mc -> Lumen.modules().newChunks.isNew(new ChunkPos(0, 2)));
		boolean stash = context.computeOnClient(mc -> Lumen.modules().stashFinder.stashes().containsKey(new ChunkPos(1, 1)));
		int stashCount = context.computeOnClient(mc -> {
			var found = Lumen.modules().stashFinder.stashes().get(new ChunkPos(1, 1));
			return found == null ? 0 : found.total();
		});
		boolean base = context.computeOnClient(mc -> Lumen.modules().baseFinder.bases().containsKey(new ChunkPos(-2, 1)));
		int baseScore = context.computeOnClient(mc -> {
			var found = Lumen.modules().baseFinder.bases().get(new ChunkPos(-2, 1));
			return found == null ? 0 : found.score();
		});
		// The earlier scene in chunk (0, 0) holds an ender chest, two shulker boxes and a hopper,
		// which is player-made too, so it should be flagged as well. Nothing else should be.
		java.util.Set<ChunkPos> flagged = context.computeOnClient(mc -> Lumen.modules().baseFinder.bases().keySet());
		java.util.Set<ChunkPos> expected = java.util.Set.of(new ChunkPos(-2, 1), new ChunkPos(0, 0));
		LOG.info("New Chunks marked (0, 2) new: {}. Stash in (1, 1): {} ({} containers). Base in (-2, 1): {} (score {}). Flagged chunks: {}",
				newChunk, stash, stashCount, base, baseScore, flagged);
		if (!newChunk) throw new AssertionError("New Chunks did not mark the chunk with flowing water as new");
		if (!stash || stashCount != 48) throw new AssertionError("Stash Finder found " + stashCount + " containers in (1, 1), expected 48");
		if (!base) throw new AssertionError("Base Finder did not flag the chunk with an ender chest, beacon and respawn anchor");
		if (!flagged.equals(expected)) throw new AssertionError("Base Finder flagged " + flagged + ", expected " + expected);
		int[] baseBox = context.computeOnClient(mc -> {
			var found = Lumen.modules().baseFinder.bases().get(new ChunkPos(-2, 1));
			return new int[] {found.minY(), found.maxY(), found.core(16)[0], found.core(16)[1]};
		});
		LOG.info("Base Finder: blocks from y {} to {}, box from y {} to {}", baseBox[0], baseBox[1], baseBox[2], baseBox[3]);
		if (baseBox[1] != -30) throw new AssertionError("The stray ender chest at y -30 was not counted (top " + baseBox[1] + ")");
		if (baseBox[2] != -60 || baseBox[3] != -60) {
			throw new AssertionError("Base Finder's box spans y " + baseBox[2] + " to " + baseBox[3] + ", expected just the base at -60");
		}

		Path stashLog = FabricLoader.getInstance().getConfigDir().resolve("lumen").resolve("stashes.csv");
		Path baseLog = FabricLoader.getInstance().getConfigDir().resolve("lumen").resolve("bases.csv");
		if (!Files.exists(stashLog)) throw new AssertionError("Stash Finder did not write " + stashLog);
		if (!Files.exists(baseLog)) throw new AssertionError("Base Finder did not write " + baseLog);

		LOG.info("Screenshot: world finders from above");
		context.runOnClient(mc -> {
			Module newChunks = Lumen.modules().newChunks;
			((BoolSetting) setting(newChunks, "Follow player")).set(false);
			((NumberSetting) setting(newChunks, "Height")).set(-60.0);
		});
		command(server, "/setblock 0 -41 6 minecraft:barrier");
		command(server, "/tp @a 0.5 -40 6.5 0 60");
		context.waitTicks(10);
		input.lookAt(0f, 60f);
		sp.getConnection().waitForChunksRender();
		context.waitTicks(10);
		context.takeScreenshot("lumen_13_world_finders");
	}

	private void testFreecam(ClientGameTestContext context) {
		TestInput input = context.getInput();
		LOG.info("Testing Freecam");

		Vec3 bodyBefore = context.computeOnClient(mc -> mc.player.position());
		context.runOnClient(mc -> Lumen.modules().freecam.setEnabled(true));
		context.waitTicks(2);

		// Fly backwards and up, then let the camera glide to a stop.
		input.holdKeyFor(options -> options.keyDown, 25);
		input.holdKeyFor(options -> options.keyJump, 12);
		context.waitTicks(15);

		Vec3 bodyAfter = context.computeOnClient(mc -> mc.player.position());
		Vec3 camera = context.computeOnClient(mc -> mc.gameRenderer.mainCamera().position());
		double bodyMoved = bodyAfter.distanceTo(bodyBefore);
		double cameraDistance = camera.distanceTo(bodyAfter);
		LOG.info("Freecam: body moved {} blocks, camera is {} blocks from the body", bodyMoved, cameraDistance);
		if (bodyMoved > 0.05) throw new AssertionError("The body moved " + bodyMoved + " blocks while Freecam was flying");
		if (cameraDistance < 5) throw new AssertionError("The camera is only " + cameraDistance + " blocks from the body");
		context.takeScreenshot("lumen_12_freecam");

		LOG.info("Scrolling to change Freecam speed");
		double speedBefore = context.computeOnClient(mc -> Lumen.modules().freecam.speed.get());
		int slotBefore = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		input.scroll(1.0);
		context.waitTicks(2);
		double speedAfter = context.computeOnClient(mc -> Lumen.modules().freecam.speed.get());
		int slotAfter = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		LOG.info("Freecam speed {} -> {}, hotbar slot {} -> {}", speedBefore, speedAfter, slotBefore, slotAfter);
		if (speedAfter <= speedBefore) throw new AssertionError("Scrolling did not raise Freecam speed");
		if (slotAfter != slotBefore) throw new AssertionError("Scrolling in Freecam also changed the hotbar slot");

		context.runOnClient(mc -> Lumen.modules().freecam.setEnabled(false));
		context.waitTicks(3);
		double offset = context.computeOnClient(mc -> mc.gameRenderer.mainCamera().position().distanceTo(mc.player.getEyePosition()));
		LOG.info("After Freecam: camera is {} blocks from the eyes", offset);
		if (offset > 0.5) throw new AssertionError("The camera did not return to the body after Freecam was turned off");
	}

	private static void buildScene(TestServerContext server) {
		// Front row, facing the player.
		command(server, "/setblock -6 -60 0 minecraft:chest[facing=north]");
		command(server, "/setblock -4 -60 0 minecraft:chest[facing=north,type=left]");
		command(server, "/setblock -3 -60 0 minecraft:chest[facing=north,type=right]");
		command(server, "/setblock -1 -60 0 minecraft:trapped_chest[facing=north]");
		command(server, "/setblock 1 -60 0 minecraft:ender_chest[facing=north]");
		command(server, "/setblock 3 -60 0 minecraft:red_shulker_box");
		command(server, "/setblock 5 -60 0 minecraft:lime_shulker_box");
		// Back row.
		command(server, "/setblock -6 -60 3 minecraft:dropper[facing=north]");
		command(server, "/setblock -4 -60 3 minecraft:barrel[facing=up]");
		command(server, "/setblock -2 -60 3 minecraft:shulker_box");
		command(server, "/setblock 0 -60 3 minecraft:spawner");
		command(server, "/setblock 2 -60 3 minecraft:trial_spawner");
		command(server, "/setblock 4 -60 3 minecraft:dispenser[facing=north]");
		command(server, "/setblock 6 -60 3 minecraft:hopper");
		// Entities, behind the blocks. NoAI keeps them in place.
		command(server, "/difficulty easy");
		command(server, "/summon minecraft:creeper -3 -60 6 {NoAI:1b,Silent:1b}");
		command(server, "/summon minecraft:pig 0 -60 6 {NoAI:1b,Silent:1b}");
		command(server, "/summon minecraft:villager 3 -60 6 {NoAI:1b,Silent:1b}");
		command(server, "/summon minecraft:item 5.5 -60 6.5 {Item:{id:\"minecraft:diamond\",count:1}}");
		command(server, "/summon minecraft:pig 1.5 -60 9 {NoAI:1b,Silent:1b,CustomName:\"Bob\"}");
		command(server, "/summon minecraft:chest_minecart -1.5 -60 9");
		command(server, "/summon minecraft:item_frame 3.5 -60 9 {Facing:1b}");
	}

	private static void command(TestServerContext server, String command) {
		server.runCommand(command);
	}

	private static Setting<?> setting(Module module, String name) {
		for (Setting<?> s : module.settings()) {
			if (s.name().equals(name)) return s;
		}
		throw new AssertionError(module.name() + " has no setting named " + name);
	}
}
