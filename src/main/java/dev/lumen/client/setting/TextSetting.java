package dev.lumen.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** A short line of text, edited in the click GUI by clicking it and typing. */
public class TextSetting extends Setting<String> {
	private final int maxLength;

	public TextSetting(String name, String description, String defaultValue, int maxLength) {
		super(name, description, defaultValue);
		this.maxLength = maxLength;
	}

	public int maxLength() {
		return maxLength;
	}

	@Override
	public void set(String value) {
		if (value == null) value = "";
		super.set(value.length() > maxLength ? value.substring(0, maxLength) : value);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) set(json.getAsString());
	}
}
