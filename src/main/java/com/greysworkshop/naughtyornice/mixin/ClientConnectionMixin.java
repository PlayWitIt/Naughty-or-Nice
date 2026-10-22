package com.greysworkshop.naughtyornice.mixin;

import com.greysworkshop.naughtyornice.Disconnects;
import com.greysworkshop.naughtyornice.Safe;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes who closes the connection, at the moment they do (see {@link Disconnects}). */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
	@Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionInfo;)V", at = @At("HEAD"))
	private void naughtyornice$closing(DisconnectionInfo info, CallbackInfo ci) {
		if (!com.greysworkshop.naughtyornice.NNConfig.on()) return;
		ClientConnection self = (ClientConnection) (Object) this;
		Safe.run("noting a disconnect", () -> Disconnects.closing(self, info));
	}
}
