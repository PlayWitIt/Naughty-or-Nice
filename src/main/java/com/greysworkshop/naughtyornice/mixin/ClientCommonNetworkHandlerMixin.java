package com.greysworkshop.naughtyornice.mixin;

import com.greysworkshop.naughtyornice.Disconnects;
import com.greysworkshop.naughtyornice.Safe;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records disconnects (see {@link Disconnects}): errors that cause one, and the reason once it's happened. */
@Mixin(ClientCommonNetworkHandler.class)
public abstract class ClientCommonNetworkHandlerMixin {
	@Inject(method = "createDisconnectionInfo", at = @At("HEAD"))
	private void naughtyornice$error(Text reason, Throwable error, CallbackInfoReturnable<DisconnectionInfo> cir) {
		if (error != null) Safe.run("noting a disconnect error", () -> Disconnects.packetError(error));
	}

	@Inject(method = "onDisconnected", at = @At("HEAD"))
	private void naughtyornice$disconnected(DisconnectionInfo info, CallbackInfo ci) {
		Safe.run("recording a disconnect", () -> Disconnects.disconnected(info));
	}
}
