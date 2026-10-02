package dev.lumen.client.util;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import net.fabricmc.loader.api.FabricLoader;

import dev.lumen.client.Lumen;

/** Shared output for the world scanners: chat lines and CSV logs in config/lumen. */
public final class Finds {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private Finds() {
	}

	/** A client-side chat line. Nothing is sent to the server. */
	public static void chat(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null || mc.gui.hud == null) return;
		mc.gui.hud.getChat().addClientSystemMessage(Component.literal("§d[Lumen]§r " + message));
	}

	public static String server() {
		Minecraft mc = Minecraft.getInstance();
		ServerData data = mc.getCurrentServer();
		return data != null ? data.ip : "singleplayer";
	}

	public static String dimension() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return "unknown";
		if (mc.level.dimension() == Level.OVERWORLD) return "overworld";
		if (mc.level.dimension() == Level.NETHER) return "nether";
		if (mc.level.dimension() == Level.END) return "end";
		return "other";
	}

	public static Path logFile(String name) {
		return FabricLoader.getInstance().getConfigDir().resolve("lumen").resolve(name);
	}

	/** Appends one CSV row, writing the header first if the file is new. */
	public static void log(String fileName, String header, String row) {
		Path file = logFile(fileName);
		try {
			Files.createDirectories(file.getParent());
			boolean fresh = !Files.exists(file);
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				if (fresh) writer.write("time,server,dimension," + header + "\n");
				writer.write(LocalDateTime.now().format(TIME) + "," + csv(server()) + "," + dimension() + "," + row + "\n");
			}
		} catch (IOException e) {
			Lumen.LOGGER.error("Could not write {}", file, e);
		}
	}

	private static String csv(String value) {
		if (value.contains(",") || value.contains("\"")) return "\"" + value.replace("\"", "\"\"") + "\"";
		return value;
	}
}
