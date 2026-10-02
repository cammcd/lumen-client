package dev.lumen.client.hud;

import dev.lumen.client.gui.Ui;

/** A module that draws 2D labels over the world, such as nametags and waypoint names. */
public interface HudOverlay {
	void drawOverlay(Ui ui, int screenWidth, int screenHeight);
}
