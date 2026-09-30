package dev.lumen.client.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class NumberSetting extends Setting<Double> {
	private final double min;
	private final double max;
	private final double step;
	private final String suffix;

	public NumberSetting(String name, String description, double defaultValue, double min, double max, double step) {
		this(name, description, defaultValue, min, max, step, "");
	}

	public NumberSetting(String name, String description, double defaultValue, double min, double max, double step, String suffix) {
		super(name, description, defaultValue);
		this.min = min;
		this.max = max;
		this.step = step;
		this.suffix = suffix;
	}

	@Override
	public void set(Double newValue) {
		double v = Math.max(min, Math.min(max, newValue));
		if (step > 0) {
			v = Math.round((v - min) / step) * step + min;
			v = Math.max(min, Math.min(max, v));
		}
		this.value = v;
	}

	public double min() {
		return min;
	}

	public double max() {
		return max;
	}

	public double step() {
		return step;
	}

	public float getFloat() {
		return value.floatValue();
	}

	public int getInt() {
		return (int) Math.round(value);
	}

	/** Position of the value between min and max, from 0 to 1. */
	public double fraction() {
		return max == min ? 0 : (value - min) / (max - min);
	}

	public void setFraction(double fraction) {
		set(min + (max - min) * Math.max(0, Math.min(1, fraction)));
	}

	public String display() {
		String number;
		if (step >= 1) {
			number = Integer.toString((int) Math.round(value));
		} else if (step >= 0.1) {
			number = String.format(java.util.Locale.ROOT, "%.1f", value);
		} else {
			number = String.format(java.util.Locale.ROOT, "%.2f", value);
		}
		return number + suffix;
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
			set(json.getAsDouble());
		}
	}
}
