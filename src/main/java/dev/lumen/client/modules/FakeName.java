package dev.lumen.client.modules;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.TextSetting;

/**
 * Shows a different name in place of yours, on your screen only. StringDecomposerMixin
 * swaps it in where all text is broken into characters for drawing, so chat, the tab
 * list, nametags, the scoreboard and signs all change together. Nothing is sent to the server.
 */
public final class FakeName extends Module {
	/** Set while drawing swapped text, so the swapped text is not swapped again. */
	public static final ThreadLocal<Boolean> REPLACING = ThreadLocal.withInitial(() -> false);

	private final TextSetting name = add(new TextSetting("Name", "What your name shows as on your screen.", "", 16));

	public FakeName() {
		super("Fake Name", "Shows a different name in place of yours on your screen: chat, tab list, nametags and scoreboard.", Category.CLIENT);
	}

	@Override
	public String hudInfo() {
		return name.get();
	}

	/** The text with your real name swapped for the fake one, or the same instance if nothing changes. */
	public String apply(String text) {
		if (!isEnabled() || text == null || text.isEmpty()) return text;
		String fake = name.get();
		String real = MC.getUser().getName();
		if (fake.isEmpty() || real.isEmpty() || fake.equals(real) || !text.contains(real)) return text;
		return text.replace(real, fake);
	}
}
