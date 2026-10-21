package com.greysworkshop.naughtyornice.mixin;

import com.greysworkshop.naughtyornice.Marks;
import com.greysworkshop.naughtyornice.NNConfig;
import com.greysworkshop.naughtyornice.Safe;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks listed players in the tab list. */
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
	@Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
	private void naughtyornice$mark(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
		if (!NNConfig.INSTANCE.tabListMarks) return;
		Text name = cir.getReturnValue();
		cir.setReturnValue(Safe.get("marking the tab list", () -> Marks.decorate(entry.getProfile().id(), name), name));
	}
}
