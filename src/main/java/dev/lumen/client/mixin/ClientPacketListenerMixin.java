package dev.lumen.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;

import dev.lumen.client.Lumen;
import dev.lumen.client.modules.SoundLocator;
import dev.lumen.client.modules.WorldScanHooks;

/** Feeds block changes from the server to the world scanners. Only reads; sends nothing. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleBlockUpdate(Lnet/minecraft/network/protocol/game/ClientboundBlockUpdatePacket;)V", at = @At("TAIL"))
	private void lumen$blockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		WorldScanHooks.blockUpdated(packet.getPos());
	}

	@Inject(method = "handleChunkBlocksUpdate(Lnet/minecraft/network/protocol/game/ClientboundSectionBlocksUpdatePacket;)V", at = @At("TAIL"))
	private void lumen$sectionUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
		packet.runUpdates((pos, state) -> WorldScanHooks.blockUpdated(pos.immutable()));
	}

	@Inject(method = "handleSoundEvent(Lnet/minecraft/network/protocol/game/ClientboundSoundPacket;)V", at = @At("TAIL"))
	private void lumen$sound(ClientboundSoundPacket packet, CallbackInfo ci) {
		SoundLocator locator = Lumen.modules() == null ? null : Lumen.modules().soundLocator;
		if (locator == null || !locator.isEnabled()) return;
		locator.onSound(packet.getSound().value().location().toString(), packet.getSource(), packet.getX(), packet.getY(), packet.getZ());
	}

	@Inject(method = "handleLevelEvent(Lnet/minecraft/network/protocol/game/ClientboundLevelEventPacket;)V", at = @At("TAIL"))
	private void lumen$levelEvent(ClientboundLevelEventPacket packet, CallbackInfo ci) {
		SoundLocator locator = Lumen.modules() == null ? null : Lumen.modules().soundLocator;
		if (locator == null || !locator.isEnabled()) return;
		locator.onLevelEvent(packet.getType(), packet.getPos(), packet.isGlobalEvent());
	}
}
