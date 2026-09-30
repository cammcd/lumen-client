package dev.lumen.client.modules;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;

import dev.lumen.client.setting.ColorSetting;

public final class SpawnerEsp extends EspModule {
	private final ColorSetting spawners = add(new ColorSetting("Spawners", "Monster spawners.", 0xFFFF4D6A, true));
	private final ColorSetting trialSpawners = add(new ColorSetting("Trial spawners", "Trial chamber spawners.", 0xFFFFA53D, true));

	public SpawnerEsp() {
		// Spawners are rare, so reach further and draw tracers by default.
		super("Spawner ESP", "Highlights monster spawners and trial spawners.", 256, true);
	}

	@Override
	protected Target match(BlockEntity be, ClientLevel level) {
		ColorSetting setting;
		if (be instanceof SpawnerBlockEntity) setting = spawners;
		else if (be instanceof TrialSpawnerBlockEntity) setting = trialSpawners;
		else return null;

		if (!setting.isEnabled()) return null;
		return new Target(blockBox(level, be.getBlockPos(), be.getBlockState()), setting, 0);
	}
}
