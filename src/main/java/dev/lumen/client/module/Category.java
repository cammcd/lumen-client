package dev.lumen.client.module;

public enum Category {
	RENDER("Render"),
	WORLD("World"),
	CLIENT("Client");

	private final String displayName;

	Category(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
