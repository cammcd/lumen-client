package dev.lumen.client.setting;

import java.util.function.BooleanSupplier;

import com.google.gson.JsonElement;

public abstract class Setting<T> {
	private final String name;
	private final String description;
	private final T defaultValue;
	protected T value;
	private BooleanSupplier visibility = () -> true;
	private String section;

	protected Setting(String name, String description, T defaultValue) {
		this.name = name;
		this.description = description;
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public String name() {
		return name;
	}

	public String description() {
		return description;
	}

	public T get() {
		return value;
	}

	public void set(T value) {
		this.value = value;
	}

	public T defaultValue() {
		return defaultValue;
	}

	public void reset() {
		set(defaultValue);
	}

	/** Hides this setting in the GUI unless the condition holds. */
	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S visibleWhen(BooleanSupplier condition) {
		this.visibility = condition;
		return (S) this;
	}

	/** Optional heading this setting is grouped under in the GUI. */
	public String section() {
		return section;
	}

	public void setSection(String section) {
		this.section = section;
	}

	public boolean isVisible() {
		return visibility.getAsBoolean();
	}

	public abstract JsonElement toJson();

	public abstract void fromJson(JsonElement json);
}
