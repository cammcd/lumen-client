package dev.lumen.client;

import org.lwjgl.sdl.SDLScancode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import dev.lumen.client.config.ConfigManager;
import dev.lumen.client.gui.ClickGuiScreen;
import dev.lumen.client.hud.HudRenderer;
import dev.lumen.client.module.ModuleManager;
import dev.lumen.client.modules.Freecam;
import dev.lumen.client.render.WorldRenderer;

public final class Lumen implements ClientModInitializer {
	public static final String MOD_ID = "lumen";
	public static final String NAME = "Lumen";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

	private static ModuleManager modules;
	private static ConfigManager config;
	private static KeyMapping clickGuiKey;
	private static int ticksSinceSave;

	@Override
	public void onInitializeClient() {
		modules = new ModuleManager();
		config = new ConfigManager();
		config.load();

		KeyMapping.Category category = KeyMapping.Category.register(id("lumen"));
		clickGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.lumen.click_gui", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_RSHIFT, category));

		ClientTickEvents.END_CLIENT_TICK.register(Lumen::onEndTick);
		LevelRenderEvents.COLLECT_SUBMITS.register(WorldRenderer::render);
		LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register((context, hit) -> {
			// The outline belongs to the body's view, which is misleading from a detached camera.
			Freecam freecam = Freecam.active();
			if (freecam != null && freecam.hideBlockOutline.isOn()) {
				context.levelState().blockOutlineRenderState = null;
			}
		});
		HudElementRegistry.addLast(id("hud"), HudRenderer::render);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> config.save());

		LOGGER.info("{} loaded with {} modules", NAME, modules.all().size());
	}

	private static void onEndTick(Minecraft client) {
		while (clickGuiKey.consumeClick()) {
			if (client.gui.screen() == null) {
				client.gui.setScreen(new ClickGuiScreen());
			}
		}

		modules.onTick();

		// Autosave every 30 seconds so a crash does not lose changes.
		if (++ticksSinceSave >= 600) {
			ticksSinceSave = 0;
			config.save();
		}
	}

	public static ModuleManager modules() {
		return modules;
	}

	public static ConfigManager config() {
		return config;
	}

	public static KeyMapping clickGuiKey() {
		return clickGuiKey;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static String version() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.map(v -> v.contains("+") ? v.substring(0, v.indexOf('+')) : v)
				.orElse("dev");
	}
}
