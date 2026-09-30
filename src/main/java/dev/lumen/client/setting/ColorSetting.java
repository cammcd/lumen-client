package dev.lumen.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.lumen.client.util.ColorUtil;

/**
 * An ARGB colour with an optional rainbow mode. When created as toggleable it also
 * carries an on/off switch, which is how per-block-type rows are built.
 */
public class ColorSetting extends Setting<Integer> {
	private final boolean toggleable;
	private final boolean defaultEnabled;
	private boolean enabled;
	private boolean rainbow;

	public ColorSetting(String name, String description, int defaultArgb) {
		super(name, description, defaultArgb);
		this.toggleable = false;
		this.defaultEnabled = true;
		this.enabled = true;
	}

	public ColorSetting(String name, String description, int defaultArgb, boolean defaultEnabled) {
		super(name, description, defaultArgb);
		this.toggleable = true;
		this.defaultEnabled = defaultEnabled;
		this.enabled = defaultEnabled;
	}

	/** The colour to draw with right now, with rainbow applied. */
	public int color() {
		return color(0f);
	}

	public int color(float rainbowOffset) {
		if (rainbow) {
			return ColorUtil.rainbow(1f, rainbowOffset, ColorUtil.alpha(value));
		}
		return value;
	}

	public boolean isToggleable() {
		return toggleable;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isRainbow() {
		return rainbow;
	}

	public void setRainbow(boolean rainbow) {
		this.rainbow = rainbow;
	}

	@Override
	public void reset() {
		super.reset();
		rainbow = false;
		enabled = defaultEnabled;
	}

	@Override
	public JsonElement toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("color", ColorUtil.toHex(value));
		json.addProperty("rainbow", rainbow);
		if (toggleable) json.addProperty("enabled", enabled);
		return json;
	}

	@Override
	public void fromJson(JsonElement json) {
		if (!json.isJsonObject()) return;
		JsonObject obj = json.getAsJsonObject();
		if (obj.has("color")) set(ColorUtil.parseHex(obj.get("color").getAsString(), defaultValue()));
		if (obj.has("rainbow")) rainbow = obj.get("rainbow").getAsBoolean();
		if (toggleable && obj.has("enabled")) enabled = obj.get("enabled").getAsBoolean();
	}
}
