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
		testInfoModules(context, sp);
		testXRayAndSigns(context, sp);
		testCombat(context, sp);
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
		context.runOnClient(mc -> {
			Lumen.modules().criticals.setEnabled(true);
			Lumen.modules().killAura.setEnabled(true);
		});
		context.waitTicks(12);
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
		// Zombie centre is 2 east and 3.5 south of the player: yaw atan2(3.5, 2) - 90.
		float expectedYaw = (float) Math.toDegrees(Math.atan2(3.5, 2.0)) - 90f;
		LOG.info("Aim Assist: yaw {} (target {})", yaw, expectedYaw);
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
					if (e.getType() == net.minecraft.world.entity.EntityType.ZOMBIE && e instanceof net.minecraft.world.entity.LivingEntity z) return z.getHealth();
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
		command(server, "/damage @a 13 minecraft:magic");
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
					if (e.getType() == net.minecraft.world.entity.EntityType.ZOMBIE && e.isAlive()) n++;
				}
				return n;
			});
			if (alive <= count) return true;
			context.waitTicks(5);
		}
		return false;
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
