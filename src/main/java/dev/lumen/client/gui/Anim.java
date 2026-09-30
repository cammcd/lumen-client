package dev.lumen.client.gui;

import java.util.HashMap;
import java.util.Map;

import dev.lumen.client.Lumen;

/**
 * Frame-rate independent easing for GUI values, keyed by any object. Each key keeps
 * its own clock, so the HUD and the click GUI can animate in the same frame.
 */
public final class Anim {
	private static final class State {
		float value;
		long lastNanos;

		State(float value, long lastNanos) {
			this.value = value;
			this.lastNanos = lastNanos;
		}
	}

	private static final Map<Object, State> STATES = new HashMap<>();

	private Anim() {
	}

	private static float speed() {
		return Lumen.modules() == null ? 1f : Lumen.modules().clickGui.animationSpeed.getFloat();
	}

	/** Moves the stored value toward target and returns it. Starts at target on first use. */
	public static float approach(Object key, float target) {
		return approach(key, target, target);
	}

	public static float approach(Object key, float target, float initial) {
		long now = System.nanoTime();
		State state = STATES.get(key);
		if (state == null) {
			state = new State(initial, now);
			STATES.put(key, state);
		}

		float dt = Math.min(0.1f, (now - state.lastNanos) / 1_000_000_000f);
		state.lastNanos = now;

		float s = speed();
		if (s <= 0f) {
			state.value = target;
		} else {
			float rate = 14f * s;
			state.value += (target - state.value) * (1f - (float) Math.exp(-dt * rate));
			if (Math.abs(target - state.value) < 0.001f) state.value = target;
		}
		return state.value;
	}

	public static void set(Object key, float value) {
		STATES.put(key, new State(value, System.nanoTime()));
	}
}
