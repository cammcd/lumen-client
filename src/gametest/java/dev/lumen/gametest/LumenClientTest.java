package dev.lumen.gametest;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import dev.lumen.client.Lumen;
import dev.lumen.client.gui.ClickGuiScreen;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.EnumSetting;
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
				.adjustSettings(creator -> creator.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
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

		LOG.info("Opening the click GUI with its key");
		input.pressKey(Lumen.clickGuiKey());
		context.waitForScreen(ClickGuiScreen.class);
		context.waitTicks(20);
		context.takeScreenshot("lumen_05_clickgui");

		int scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		// Default layout: the Render panel sits at (16, 16), its header is 20 high and
		// each module row is 16 high, so Storage ESP is centred at y 46 and Spawner ESP at y 62.
		int panelCenterX = 16 + 68;

		LOG.info("Binding Spawner ESP to G through the GUI");
		input.setCursorPos(panelCenterX * scale, 62 * scale);
		input.pressMouse(InputConstants.MOUSE_BUTTON_MIDDLE);
		context.waitTicks(2);
		input.pressKey(InputConstants.KEY_G);
		context.waitTicks(2);
		String key = context.computeOnClient(mc -> Lumen.modules().spawnerEsp.key());
		LOG.info("Spawner ESP key is now '{}'", key);
		if (key.isEmpty()) throw new AssertionError("Binding through the GUI did not set a key");

		LOG.info("Expanding Storage ESP settings");
		input.setCursorPos(panelCenterX * scale, 46 * scale);
		input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(20);
		context.takeScreenshot("lumen_06_settings");

		LOG.info("Opening the Chests colour picker");
		// Settings start at y 56: Keybind row (14), BLOCKS heading (14), then Chests.
		input.setCursorPos(panelCenterX * scale, 91 * scale);
		input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(20);
		context.takeScreenshot("lumen_07_color_picker");

		LOG.info("Closing the GUI");
		input.pressKey(InputConstants.KEY_ESCAPE);
		context.waitForScreen(null);
		Path config = FabricLoader.getInstance().getConfigDir().resolve("lumen.json");
		if (!Files.exists(config)) throw new AssertionError("Closing the GUI did not save " + config);

		LOG.info("Toggling Spawner ESP with its new key");
		boolean before = context.computeOnClient(mc -> Lumen.modules().spawnerEsp.isEnabled());
		input.pressKey(InputConstants.KEY_G);
		context.waitTicks(4);
		boolean after = context.computeOnClient(mc -> Lumen.modules().spawnerEsp.isEnabled());
		if (before == after) throw new AssertionError("Pressing the bound key did not toggle Spawner ESP");
		context.takeScreenshot("lumen_08_keybind_notification");
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
