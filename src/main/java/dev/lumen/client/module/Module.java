package dev.lumen.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;

import dev.lumen.client.hud.Notifications;
import dev.lumen.client.setting.Setting;

public abstract class Module {
	protected static final Minecraft MC = Minecraft.getInstance();

	private final String name;
	private final String description;
	private final Category category;
	private final boolean toggleable;
	private final List<Setting<?>> settings = new ArrayList<>();
	private boolean enabled;
	/** Key name as reported by InputConstants, for example "key.keyboard.r". Empty means unbound. */
	private String key = "";
	private String defaultSection;
	private int insertIndex = -1;

	protected Module(String name, String description, Category category) {
		this(name, description, category, true);
	}

	protected Module(String name, String description, Category category, boolean toggleable) {
		this.name = name;
		this.description = description;
		this.category = category;
		this.toggleable = toggleable;
	}

	protected <S extends Setting<?>> S add(S setting) {
		if (setting.section() == null) setting.setSection(defaultSection);
		if (insertIndex >= 0) {
			settings.add(insertIndex++, setting);
		} else {
			settings.add(setting);
		}
		return setting;
	}

	/** Settings added after this call are grouped under the given heading. */
	protected void setDefaultSection(String section) {
		this.defaultSection = section;
	}

	/**
	 * Settings added after this call are placed before the existing ones. Base classes
	 * use it so a subclass's own settings appear first in the GUI.
	 */
	protected void insertFutureSettingsAtTop() {
		insertIndex = 0;
	}

	public String name() {
		return name;
	}

	public String description() {
		return description;
	}

	public Category category() {
		return category;
	}

	public boolean isToggleable() {
		return toggleable;
	}

	public List<Setting<?>> settings() {
		return Collections.unmodifiableList(settings);
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		if (!toggleable || this.enabled == enabled) return;
		this.enabled = enabled;
		if (enabled) {
			onEnable();
		} else {
			onDisable();
		}
	}

	public void toggle() {
		setEnabled(!enabled);
		if (toggleable) Notifications.moduleToggled(this);
	}

	/** Restores state from config without firing notifications. */
	public void loadEnabled(boolean enabled) {
		setEnabled(enabled);
	}

	public String key() {
		return key;
	}

	public void setKey(String key) {
		this.key = key == null ? "" : key;
	}

	/** Short text shown after the module name in the HUD list, or null. */
	public String hudInfo() {
		return null;
	}

	protected void onEnable() {
	}

	protected void onDisable() {
	}

	public void onTick() {
	}
}
