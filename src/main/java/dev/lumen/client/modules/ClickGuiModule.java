package dev.lumen.client.modules;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ColorUtil;

/** Theme settings for the click GUI. Not toggleable: it is opened with its own key (Right Shift). */
public final class ClickGuiModule extends Module {
	public final ColorSetting accent = add(new ColorSetting("Accent", "Main accent colour.", 0xFF8B6CFF));
	public final ColorSetting accent2 = add(new ColorSetting("Accent 2", "Second colour for gradients.", 0xFF3FD0FF));
	public final BoolSetting gradients = add(new BoolSetting("Gradients", "Blend the two accents across headers and highlights.", true));
	public final ColorSetting panelColor = add(new ColorSetting("Panel color", "Background colour of panels.", 0xE0141620));
	public final ColorSetting textColor = add(new ColorSetting("Text color", "Main text colour.", 0xFFEDEFF7));
	public final NumberSetting cornerRadius = add(new NumberSetting("Corner radius", "Roundness of panels and controls.", 4, 0, 8, 1));
	public final BoolSetting shadows = add(new BoolSetting("Shadows", "Soft shadows behind panels.", true));
	public final NumberSetting dim = add(new NumberSetting("Background dim", "How much the world darkens behind the GUI.", 45, 0, 90, 1, "%"));
	public final BoolSetting blur = add(new BoolSetting("Blur", "Blur the world behind the GUI.", true));
	public final NumberSetting animationSpeed = add(new NumberSetting("Animation speed", "Speed of GUI animations. 0 turns them off.", 1.0, 0, 3, 0.1, "x"));
	public final BoolSetting descriptions = add(new BoolSetting("Descriptions", "Show a description when hovering a module or setting.", true));
	public final BoolSetting textShadow = add(new BoolSetting("Text shadow", "Drop shadow under GUI text.", false));

	public ClickGuiModule() {
		super("Click GUI", "Look and feel of this menu. Open it with Right Shift (rebindable in Controls).", Category.CLIENT, false);
	}

	/** Accent colour at a position from 0 to 1 along a gradient. */
	public int accentAt(float t) {
		int a = accent.color(t * 0.3f);
		if (!gradients.isOn()) return a;
		int b = accent2.color(t * 0.3f + 0.5f);
		return ColorUtil.lerp(a, b, t);
	}
}
