package dev.lumen.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;

import dev.lumen.client.Lumen;
import dev.lumen.client.modules.FakeName;

/** Fake Name: every piece of text is broken into characters here before it is drawn or measured. */
@Mixin(StringDecomposer.class)
public abstract class StringDecomposerMixin {
	@Inject(method = "iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z",
			at = @At("HEAD"), cancellable = true)
	private static void lumen$fakeName(String text, int offset, Style style, Style resetStyle, FormattedCharSink sink,
			CallbackInfoReturnable<Boolean> cir) {
		// Only whole strings: a later offset would no longer line up once the text is swapped.
		if (offset != 0 || FakeName.REPLACING.get() || Lumen.modules() == null) return;
		String swapped = Lumen.modules().fakeName.apply(text);
		if (swapped == text) return;
		FakeName.REPLACING.set(true);
		try {
			cir.setReturnValue(StringDecomposer.iterateFormatted(swapped, 0, style, resetStyle, sink));
		} finally {
			FakeName.REPLACING.set(false);
		}
	}
}
