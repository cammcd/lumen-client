package dev.lumen.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.lumen.client.modules.BaseFinder;
import dev.lumen.client.modules.ClickGuiModule;
import dev.lumen.client.modules.EntityEsp;
import dev.lumen.client.modules.Freecam;
import dev.lumen.client.modules.HudModule;
import dev.lumen.client.modules.LogoutSpots;
import dev.lumen.client.modules.Nametags;
import dev.lumen.client.modules.NewChunks;
import dev.lumen.client.modules.Radar;
import dev.lumen.client.modules.SignReader;
import dev.lumen.client.modules.SoundLocator;
import dev.lumen.client.modules.SpawnerEsp;
import dev.lumen.client.modules.StashFinder;
import dev.lumen.client.modules.StorageEsp;
import dev.lumen.client.modules.Waypoints;
import dev.lumen.client.modules.XRay;

public final class ModuleManager {
	private final List<Module> modules = new ArrayList<>();

	public final StorageEsp storageEsp;
	public final SpawnerEsp spawnerEsp;
	public final EntityEsp entityEsp;
	public final Nametags nametags;
	public final XRay xRay;
	public final Freecam freecam;
	public final NewChunks newChunks;
	public final StashFinder stashFinder;
	public final BaseFinder baseFinder;
	public final SignReader signReader;
	public final LogoutSpots logoutSpots;
	public final SoundLocator soundLocator;
	public final Waypoints waypoints;
	public final Radar radar;
	public final HudModule hud;
	public final ClickGuiModule clickGui;

	public ModuleManager() {
		storageEsp = register(new StorageEsp());
		spawnerEsp = register(new SpawnerEsp());
		entityEsp = register(new EntityEsp());
		nametags = register(new Nametags());
		xRay = register(new XRay());
		freecam = register(new Freecam());
		newChunks = register(new NewChunks());
		stashFinder = register(new StashFinder());
		baseFinder = register(new BaseFinder());
		signReader = register(new SignReader());
		logoutSpots = register(new LogoutSpots());
		soundLocator = register(new SoundLocator());
		waypoints = register(new Waypoints());
		radar = register(new Radar());
		hud = register(new HudModule());
		clickGui = register(new ClickGuiModule());
	}

	private <M extends Module> M register(M module) {
		modules.add(module);
		return module;
	}

	public List<Module> all() {
		return Collections.unmodifiableList(modules);
	}

	public List<Module> byCategory(Category category) {
		List<Module> result = new ArrayList<>();
		for (Module module : modules) {
			if (module.category() == category) result.add(module);
		}
		return result;
	}

	public Module byName(String name) {
		for (Module module : modules) {
			if (module.name().equalsIgnoreCase(name)) return module;
		}
		return null;
	}

	public void onTick() {
		for (Module module : modules) {
			if (module.isEnabled()) module.onTick();
		}
	}

	public void onKey(String keyName) {
		if (keyName == null || keyName.isEmpty()) return;
		for (Module module : modules) {
			if (module.isToggleable() && keyName.equals(module.key())) {
				module.toggle();
			}
		}
	}
}
