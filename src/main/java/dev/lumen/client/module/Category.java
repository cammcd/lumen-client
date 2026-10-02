package dev.lumen.client.module;

public enum Category {
	RENDER("Render"),
	WORLD("World"),
	CLIENT("Client"),
	// Last, so saved panel positions from older versions do not overlap it.
	COMBAT("Combat");

	private final String displayName;

	Category(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
