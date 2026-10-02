package dev.lumen.client.modules;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;

import dev.lumen.client.Lumen;
import dev.lumen.client.module.Module;

/** Routes chunk loads and block updates to whichever world scanners are enabled. */
public final class WorldScanHooks {
	private WorldScanHooks() {
	}

	public static void chunkLoaded(ClientLevel level, LevelChunk chunk) {
		if (Lumen.modules() == null) return;
		for (Module module : Lumen.modules().all()) {
			if (module.isEnabled() && module instanceof WorldScanModule scanner) scanner.onChunkLoad(chunk);
		}
	}

	public static void blockUpdated(BlockPos pos) {
		if (Lumen.modules() == null) return;
		for (Module module : Lumen.modules().all()) {
			if (module.isEnabled() && module instanceof WorldScanModule scanner) scanner.onBlockUpdate(pos);
		}
	}
}
