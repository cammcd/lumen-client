package dev.lumen.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.lumen.client.modules.ClickGuiModule;
import dev.lumen.client.modules.EntityEsp;
import dev.lumen.client.modules.Freecam;
import dev.lumen.client.modules.HudModule;
import dev.lumen.client.modules.SpawnerEsp;
import dev.lumen.client.modules.StorageEsp;

public final class ModuleManager {
	private final List<Module> modules = new ArrayList<>();

	public final StorageEsp storageEsp;
	public final SpawnerEsp spawnerEsp;
	public final EntityEsp entityEsp;
	public final Freecam freecam;
	public final HudModule hud;
	public final ClickGuiModule clickGui;

	public ModuleManager() {
		storageEsp = register(new StorageEsp());
		spawnerEsp = register(new SpawnerEsp());
		entityEsp = register(new EntityEsp());
		freecam = register(new Freecam());
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
