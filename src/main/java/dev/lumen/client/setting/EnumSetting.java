package dev.lumen.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class EnumSetting<E extends Enum<E>> extends Setting<E> {
	private final E[] constants;

	public EnumSetting(String name, String description, E defaultValue) {
		super(name, description, defaultValue);
		this.constants = defaultValue.getDeclaringClass().getEnumConstants();
	}

	public void cycle(boolean forward) {
		int i = value.ordinal() + (forward ? 1 : -1);
		if (i < 0) i = constants.length - 1;
		if (i >= constants.length) i = 0;
		set(constants[i]);
	}

	public boolean is(E constant) {
		return value == constant;
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value.name());
	}

	@Override
	public void fromJson(JsonElement json) {
		if (!json.isJsonPrimitive()) return;
		String name = json.getAsString();
		for (E constant : constants) {
			if (constant.name().equals(name)) {
				set(constant);
				return;
			}
		}
	}
}
