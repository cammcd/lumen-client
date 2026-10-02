package dev.lumen.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
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
import dev.lumen.client.module.Module;
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

		LOG.info("Lumen client test complete");
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

		LOG.info("Searching for 'spawn'");
		input.typeChars("spawn");
		context.waitTicks(10);
		List<String> visible = context.computeOnClient(mc -> ((ClickGuiScreen) mc.gui.screen()).visibleModules());
		LOG.info("Visible modules while searching: {}", visible);
		if (!visible.equals(List.of("Spawner ESP"))) {
			throw new AssertionError("Searching 'spawn' showed " + visible + ", expected only Spawner ESP");
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
