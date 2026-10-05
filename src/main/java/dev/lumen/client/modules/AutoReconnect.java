package dev.lumen.client.modules;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;

/** Adds a reconnect button to the disconnect screen and, optionally, counts down and rejoins. */
public final class AutoReconnect extends Module {
	private final NumberSetting delay = add(new NumberSetting("Delay", "Seconds to wait before rejoining.", 5, 1, 120, 1, "s"));
	private final BoolSetting auto = add(new BoolSetting("Automatic", "Rejoin when the countdown ends. Off just adds the button.", true));

	private ServerData last;
	private int attempts;

	public AutoReconnect() {
		super("Auto Reconnect", "Rejoins the last server after a kick or lost connection.", Category.CLIENT);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> onScreen(screen, width, height));
	}

	/** Remembers the server to rejoin. Normally learned while you play; public for the game test. */
	public void remember(ServerData server) {
		last = server;
	}

	/** Reconnect attempts started since the module was loaded; used by the game test. */
	public int attempts() {
		return attempts;
	}

	@Override
	public void onTick() {
		if (MC.level != null && MC.getCurrentServer() != null) last = MC.getCurrentServer();
	}

	private void onScreen(Screen screen, int width, int height) {
		if (!(screen instanceof DisconnectedScreen) || !isEnabled() || last == null) return;
		ServerData target = last;
		int[] ticksLeft = {auto.isOn() ? delay.getInt() * 20 : -1};

		Button button = Button.builder(label(target, ticksLeft[0]), b -> connect(target))
				.bounds(width / 2 - 100, height - 30, 200, 20)
				.build();
		Screens.getWidgets(screen).add(button);

		ScreenEvents.afterTick(screen).register(s -> {
			if (ticksLeft[0] <= 0 || !isEnabled()) return;
			ticksLeft[0]--;
			button.setMessage(label(target, ticksLeft[0]));
			if (ticksLeft[0] == 0) connect(target);
		});
	}

	private static Component label(ServerData target, int ticksLeft) {
		if (ticksLeft > 0) return Component.literal("Reconnecting in " + (ticksLeft + 19) / 20 + "s (click to go now)");
		return Component.literal("Reconnect to " + target.ip);
	}

	private void connect(ServerData target) {
		attempts++;
		ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), MC, ServerAddress.parseString(target.ip), target, false, null);
	}
}
