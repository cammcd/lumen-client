package dev.lumen.client.util;

import java.util.Locale;

/** Turns InputConstants key names such as "key.keyboard.right.shift" into "Right Shift". */
public final class KeyNames {
	private KeyNames() {
	}

	public static String pretty(String keyName) {
		if (keyName == null || keyName.isEmpty()) return "None";

		String s = keyName;
		boolean mouse = false;
		if (s.startsWith("key.keyboard.")) {
			s = s.substring("key.keyboard.".length());
		} else if (s.startsWith("key.mouse.")) {
			s = s.substring("key.mouse.".length());
			mouse = true;
		}

		if (s.startsWith("keypad.")) {
			s = "numpad " + s.substring("keypad.".length());
		}

		String[] parts = s.split("\\.");
		StringBuilder out = new StringBuilder(mouse ? "Mouse " : "");
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];
			if (part.isEmpty()) continue;
			if (i > 0) out.append(' ');
			out.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
		}
		return out.toString();
	}
}
