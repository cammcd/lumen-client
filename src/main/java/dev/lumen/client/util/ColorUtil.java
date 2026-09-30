package dev.lumen.client.util;

public final class ColorUtil {
	private ColorUtil() {
	}

	public static int alpha(int argb) {
		return argb >>> 24;
	}

	public static int red(int argb) {
		return (argb >> 16) & 0xFF;
	}

	public static int green(int argb) {
		return (argb >> 8) & 0xFF;
	}

	public static int blue(int argb) {
		return argb & 0xFF;
	}

	public static int argb(int a, int r, int g, int b) {
		return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
	}

	public static int withAlpha(int argb, int alpha) {
		return (clamp(alpha) << 24) | (argb & 0x00FFFFFF);
	}

	/** Multiplies the existing alpha by a factor from 0 to 1. */
	public static int fade(int argb, float factor) {
		return withAlpha(argb, Math.round(alpha(argb) * Math.max(0f, Math.min(1f, factor))));
	}

	public static int lerp(int from, int to, float t) {
		t = Math.max(0f, Math.min(1f, t));
		return argb(
				Math.round(alpha(from) + (alpha(to) - alpha(from)) * t),
				Math.round(red(from) + (red(to) - red(from)) * t),
				Math.round(green(from) + (green(to) - green(from)) * t),
				Math.round(blue(from) + (blue(to) - blue(from)) * t));
	}

	public static int brighten(int argb, float amount) {
		return lerp(argb, withAlpha(0xFFFFFF, alpha(argb)), amount);
	}

	public static int darken(int argb, float amount) {
		return lerp(argb, withAlpha(0x000000, alpha(argb)), amount);
	}

	/** Hue, saturation and brightness from 0 to 1. Returns an opaque colour. */
	public static int hsb(float hue, float saturation, float brightness) {
		hue = hue - (float) Math.floor(hue);
		saturation = Math.max(0f, Math.min(1f, saturation));
		brightness = Math.max(0f, Math.min(1f, brightness));

		float h = hue * 6f;
		int sector = (int) Math.floor(h) % 6;
		float f = h - (float) Math.floor(h);
		float p = brightness * (1f - saturation);
		float q = brightness * (1f - f * saturation);
		float t = brightness * (1f - (1f - f) * saturation);

		float r, g, b;
		switch (sector) {
			case 0 -> { r = brightness; g = t; b = p; }
			case 1 -> { r = q; g = brightness; b = p; }
			case 2 -> { r = p; g = brightness; b = t; }
			case 3 -> { r = p; g = q; b = brightness; }
			case 4 -> { r = t; g = p; b = brightness; }
			default -> { r = brightness; g = p; b = q; }
		}

		return argb(255, Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
	}

	/** Returns {hue, saturation, brightness}, each from 0 to 1. */
	public static float[] toHsb(int argb) {
		float r = red(argb) / 255f;
		float g = green(argb) / 255f;
		float b = blue(argb) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float delta = max - min;

		float hue;
		if (delta == 0) {
			hue = 0;
		} else if (max == r) {
			hue = ((g - b) / delta) / 6f;
		} else if (max == g) {
			hue = ((b - r) / delta + 2f) / 6f;
		} else {
			hue = ((r - g) / delta + 4f) / 6f;
		}
		if (hue < 0) hue += 1f;

		float saturation = max == 0 ? 0 : delta / max;
		return new float[] {hue, saturation, max};
	}

	/** A rainbow colour that cycles over time. Offset shifts the phase. */
	public static int rainbow(float speed, float offset, int alpha) {
		double seconds = System.nanoTime() / 1_000_000_000.0;
		float hue = (float) ((seconds * 0.15 * speed + offset) % 1.0);
		return withAlpha(hsb(hue, 0.65f, 1f), alpha);
	}

	public static String toHex(int argb) {
		return String.format("#%08X", argb);
	}

	public static int parseHex(String hex, int fallback) {
		try {
			String s = hex.startsWith("#") ? hex.substring(1) : hex;
			long v = Long.parseLong(s, 16);
			if (s.length() <= 6) v |= 0xFF000000L;
			return (int) v;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(255, v));
	}
}
