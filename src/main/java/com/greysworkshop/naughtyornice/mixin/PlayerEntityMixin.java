package com.greysworkshop.naughtyornice.mixin;

import com.greysworkshop.naughtyornice.Marks;
import com.greysworkshop.naughtyornice.NNConfig;
import com.greysworkshop.naughtyornice.Safe;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks listed players' nametags, and the totems they've popped. Client-side copies of players only (not the singleplayer server's). */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void naughtyornice$mark(CallbackInfoReturnable<Text> cir) {
		PlayerEntity self = (PlayerEntity) (Object) this;
		NNConfig c = NNConfig.INSTANCE;
		if (!NNConfig.on() || !c.nametagMarks && !c.totemCounter || !self.getEntityWorld().isClient()) return;
		Text name = cir.getReturnValue();
		cir.setReturnValue(Safe.get("marking a nametag", () -> {
			Text marked = c.nametagMarks ? Marks.decorate(self.getUuid(), name) : name;
			return c.totemCounter ? Marks.totems(self.getUuid(), marked) : marked;
		}, name));
	}
}
