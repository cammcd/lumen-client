package dev.lumen.client.hud;

import java.util.ArrayList;
import java.util.List;

import dev.lumen.client.Lumen;
import dev.lumen.client.module.Module;

/** Short-lived pop-ups shown when a module is switched on or off. */
public final class Notifications {
	public record Entry(String title, boolean on, long startNanos) {
		public float ageSeconds() {
			return (System.nanoTime() - startNanos) / 1_000_000_000f;
		}
	}

	public static final float SHOW_SECONDS = 1.8f;
	public static final float FADE_SECONDS = 0.25f;

	private static final List<Entry> ENTRIES = new ArrayList<>();

	private Notifications() {
	}

	public static void moduleToggled(Module module) {
		if (Lumen.modules() == null) return;
		var hud = Lumen.modules().hud;
		if (!hud.isEnabled() || !hud.notifications.isOn()) return;

		ENTRIES.removeIf(e -> e.title().equals(module.name()));
		ENTRIES.add(new Entry(module.name(), module.isEnabled(), System.nanoTime()));
		while (ENTRIES.size() > 5) ENTRIES.removeFirst();
	}

	/** Live entries, oldest first. Expired ones are dropped. */
	public static List<Entry> entries() {
		ENTRIES.removeIf(e -> e.ageSeconds() > SHOW_SECONDS + FADE_SECONDS);
		return List.copyOf(ENTRIES);
	}
}
