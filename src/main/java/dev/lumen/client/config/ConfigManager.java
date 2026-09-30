package dev.lumen.client.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import dev.lumen.client.Lumen;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.Setting;

/** Saves modules, settings, keybinds and GUI panel positions to config/lumen.json. */
public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file = FabricLoader.getInstance().getConfigDir().resolve("lumen.json");
	private JsonObject guiState = new JsonObject();

	public JsonObject guiState() {
		return guiState;
	}

	public void load() {
		if (!Files.exists(file)) return;

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject()) return;
			JsonObject obj = root.getAsJsonObject();

			if (obj.has("modules") && obj.get("modules").isJsonObject()) {
				for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject("modules").entrySet()) {
					Module module = Lumen.modules().byName(entry.getKey());
					if (module == null || !entry.getValue().isJsonObject()) continue;
					loadModule(module, entry.getValue().getAsJsonObject());
				}
			}

			if (obj.has("gui") && obj.get("gui").isJsonObject()) {
				guiState = obj.getAsJsonObject("gui");
			}
		} catch (Exception e) {
			Lumen.LOGGER.error("Could not read {}, using defaults", file, e);
		}
	}

	private void loadModule(Module module, JsonObject json) {
		if (json.has("settings") && json.get("settings").isJsonObject()) {
			JsonObject settings = json.getAsJsonObject("settings");
			for (Setting<?> setting : module.settings()) {
				JsonElement value = settings.get(setting.name());
				if (value == null) continue;
				try {
					setting.fromJson(value);
				} catch (Exception e) {
					Lumen.LOGGER.warn("Ignoring bad value for {} / {}", module.name(), setting.name());
				}
			}
		}

		if (json.has("key")) module.setKey(json.get("key").getAsString());
		if (json.has("enabled")) module.loadEnabled(json.get("enabled").getAsBoolean());
	}

	public void save() {
		JsonObject root = new JsonObject();
		JsonObject modules = new JsonObject();

		for (Module module : Lumen.modules().all()) {
			JsonObject json = new JsonObject();
			json.addProperty("enabled", module.isEnabled());
			json.addProperty("key", module.key());

			JsonObject settings = new JsonObject();
			for (Setting<?> setting : module.settings()) {
				settings.add(setting.name(), setting.toJson());
			}
			json.add("settings", settings);
			modules.add(module.name(), json);
		}

		root.add("modules", modules);
		root.add("gui", guiState);

		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("lumen.json.tmp");
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Lumen.LOGGER.error("Could not save {}", file, e);
		}
	}
}
