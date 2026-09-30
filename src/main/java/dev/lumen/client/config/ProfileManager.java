package dev.lumen.client.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import dev.lumen.client.Lumen;

/**
 * Named snapshots of every module's settings, keybinds and on/off state, stored in
 * config/lumen/profiles. Panel positions are not part of a profile.
 */
public final class ProfileManager {
	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9 _-]{1,32}");

	private final Path dir = FabricLoader.getInstance().getConfigDir().resolve("lumen").resolve("profiles");

	public static boolean isValidName(String name) {
		return name != null && VALID_NAME.matcher(name).matches() && !name.isBlank();
	}

	public Path file(String name) {
		return dir.resolve(name.trim() + ".json");
	}

	/** Saved profile names, alphabetically. */
	public List<String> list() {
		List<String> names = new ArrayList<>();
		if (!Files.isDirectory(dir)) return names;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
			for (Path path : stream) {
				String file = path.getFileName().toString();
				names.add(file.substring(0, file.length() - ".json".length()));
			}
		} catch (IOException e) {
			Lumen.LOGGER.error("Could not list profiles in {}", dir, e);
		}
		names.sort((a, b) -> a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT)));
		return names;
	}

	public boolean exists(String name) {
		return isValidName(name) && Files.exists(file(name));
	}

	/** Saves the current setup under a name, replacing any profile with that name. */
	public boolean save(String name) {
		if (!isValidName(name)) return false;
		JsonObject json = new JsonObject();
		json.add("modules", Lumen.config().modulesJson());
		try {
			ConfigManager.writeJson(file(name), json);
			setActive(name.trim());
			return true;
		} catch (IOException e) {
			Lumen.LOGGER.error("Could not save profile {}", name, e);
			return false;
		}
	}

	/** Applies a saved profile to every module, then saves the main config. */
	public boolean load(String name) {
		if (!exists(name)) return false;
		try (Reader reader = Files.newBufferedReader(file(name), StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject() || !root.getAsJsonObject().has("modules")) return false;
			Lumen.config().applyModules(root.getAsJsonObject().getAsJsonObject("modules"));
			setActive(name.trim());
			return true;
		} catch (Exception e) {
			Lumen.LOGGER.error("Could not load profile {}", name, e);
			return false;
		}
	}

	public boolean delete(String name) {
		if (!exists(name)) return false;
		try {
			Files.delete(file(name));
			if (name.trim().equals(active())) setActive("");
			return true;
		} catch (IOException e) {
			Lumen.LOGGER.error("Could not delete profile {}", name, e);
			return false;
		}
	}

	/** The profile most recently saved or loaded, or an empty string. */
	public String active() {
		JsonObject gui = Lumen.config().guiState();
		return gui.has("activeProfile") ? gui.get("activeProfile").getAsString() : "";
	}

	private void setActive(String name) {
		Lumen.config().guiState().addProperty("activeProfile", name);
		Lumen.config().save();
	}
}
