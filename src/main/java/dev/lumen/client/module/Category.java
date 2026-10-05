package dev.lumen.client.module;

public enum Category {
	RENDER("Render"),
	WORLD("World"),
	CLIENT("Client"),
	// Added after the first three, so saved panel positions from older versions do not overlap them.
	COMBAT("Combat"),
	// Placed under the Client panel by default; see ClickGuiScreen.loadState.
	PLAYER("Player");

	private final String displayName;

	Category(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
