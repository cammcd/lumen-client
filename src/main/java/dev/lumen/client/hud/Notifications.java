package dev.lumen.client.hud;

import java.util.ArrayList;
import java.util.List;

import dev.lumen.client.Lumen;
import dev.lumen.client.module.Module;

/** Short-lived pop-ups: module toggles, and finds reported by the world scanners. */
public final class Notifications {
	public record Entry(String title, String detail, int color, long startNanos, float showSeconds) {
		public float ageSeconds() {
			return (System.nanoTime() - startNanos) / 1_000_000_000f;
		}
	}

	public static final float SHOW_SECONDS = 1.8f;
	public static final float FADE_SECONDS = 0.25f;
	public static final int ON_COLOR = 0xFF6CF0A0;
	public static final int OFF_COLOR = 0xFFFF6B7A;

	private static final List<Entry> ENTRIES = new ArrayList<>();

	private Notifications() {
	}

	private static boolean shown() {
		if (Lumen.modules() == null) return false;
		var hud = Lumen.modules().hud;
		return hud.isEnabled() && hud.notifications.isOn();
	}

	public static void moduleToggled(Module module) {
		if (!shown()) return;
		ENTRIES.removeIf(e -> e.title().equals(module.name()));
		add(new Entry(module.name(), module.isEnabled() ? "Enabled" : "Disabled",
				module.isEnabled() ? ON_COLOR : OFF_COLOR, System.nanoTime(), SHOW_SECONDS));
	}

	/** A longer-lived notice, used for finds such as stashes and bases. */
	public static void notice(String title, String detail, int color) {
		if (!shown()) return;
		add(new Entry(title, detail, color, System.nanoTime(), 5f));
	}

	private static void add(Entry entry) {
		ENTRIES.add(entry);
		while (ENTRIES.size() > 5) ENTRIES.removeFirst();
	}

	/** Live entries, oldest first. Expired ones are dropped. */
	public static List<Entry> entries() {
		ENTRIES.removeIf(e -> e.ageSeconds() > e.showSeconds() + FADE_SECONDS);
		return List.copyOf(ENTRIES);
	}
}
