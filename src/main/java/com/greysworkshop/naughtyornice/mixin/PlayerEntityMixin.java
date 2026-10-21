package com.greysworkshop.naughtyornice.mixin;

import com.greysworkshop.naughtyornice.Marks;
import com.greysworkshop.naughtyornice.NNConfig;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks listed players' nametags. Client-side copies of players only (not the singleplayer server's). */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void naughtyornice$mark(CallbackInfoReturnable<Text> cir) {
		PlayerEntity self = (PlayerEntity) (Object) this;
		if (!NNConfig.INSTANCE.nametagMarks || !self.getEntityWorld().isClient()) return;
		cir.setReturnValue(Marks.decorate(self.getUuid(), cir.getReturnValue()));
	}
}
