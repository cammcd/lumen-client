package dev.lumen.client.modules;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.setting.EnumSetting;
import dev.lumen.client.setting.NumberSetting;

public final class HudModule extends Module {
	public enum Corner {
		TOP_RIGHT("Top right"), TOP_LEFT("Top left"), BOTTOM_RIGHT("Bottom right"), BOTTOM_LEFT("Bottom left");

		private final String label;

		Corner(String label) {
			this.label = label;
		}

		public boolean right() {
			return this == TOP_RIGHT || this == BOTTOM_RIGHT;
		}

		public boolean bottom() {
			return this == BOTTOM_RIGHT || this == BOTTOM_LEFT;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	public enum ColorMode {
		ACCENT("Accent"), RAINBOW("Rainbow"), STATIC("Custom");

		private final String label;

		ColorMode(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	public enum Sort {
		LENGTH("Length"), ALPHABETICAL("A to Z");

		private final String label;

		Sort(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	public final NumberSetting scale = add(new NumberSetting("Scale", "Size of everything on the HUD.", 1.0, 0.5, 2.0, 0.05, "x"));
	public final EnumSetting<ColorMode> colorMode = add(new EnumSetting<>("Color mode", "Colouring of HUD text and bars.", ColorMode.ACCENT));
	public final ColorSetting customColor = add(new ColorSetting("Custom color", "Used when colour mode is Custom.", 0xFF7CF2C4))
			.visibleWhen(() -> colorMode.is(ColorMode.STATIC));
	public final BoolSetting textShadow = add(new BoolSetting("Text shadow", "Drop shadow under HUD text.", true));

	public final BoolSetting watermark = add(new BoolSetting("Watermark", "Client name and version in the top-left corner.", true));
	public final BoolSetting watermarkFps = add(new BoolSetting("Watermark FPS", "Show frames per second beside the watermark.", true))
			.visibleWhen(watermark::isOn);

	public final BoolSetting moduleList = add(new BoolSetting("Module list", "List of enabled modules.", true));
	public final EnumSetting<Corner> listCorner = add(new EnumSetting<>("List corner", "Screen corner for the module list.", Corner.TOP_RIGHT))
			.visibleWhen(moduleList::isOn);
	public final EnumSetting<Sort> listSort = add(new EnumSetting<>("List sort", "Ordering of the module list.", Sort.LENGTH))
			.visibleWhen(moduleList::isOn);
	public final BoolSetting listBackground = add(new BoolSetting("List background", "Dark backdrop behind each entry.", true))
			.visibleWhen(moduleList::isOn);
	public final NumberSetting listBackgroundOpacity = add(new NumberSetting("Backdrop opacity", "Opacity of the list backdrop.", 45, 0, 100, 1, "%"))
			.visibleWhen(() -> moduleList.isOn() && listBackground.isOn());
	public final BoolSetting listBar = add(new BoolSetting("List accent bar", "Coloured bar on the outer edge of each entry.", true))
			.visibleWhen(moduleList::isOn);
	public final BoolSetting listInfo = add(new BoolSetting("List info", "Show counts after module names, like Storage ESP 12.", true))
			.visibleWhen(moduleList::isOn);

	public final BoolSetting coordinates = add(new BoolSetting("Coordinates", "Your position, plus the matching Nether or Overworld position.", true));
	public final BoolSetting notifications = add(new BoolSetting("Notifications", "Small pop-ups when a module is toggled.", true));

	public HudModule() {
		super("HUD", "On-screen overlay: watermark, module list, coordinates and notifications.", Category.CLIENT);
		loadEnabled(true);
	}
}
