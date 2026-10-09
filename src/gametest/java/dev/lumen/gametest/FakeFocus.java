package dev.lumen.gametest;

/** Lets the test make the game window look unfocused, as switching to another window does. */
public final class FakeFocus {
	public static volatile boolean lost;

	private FakeFocus() {
	}
}
