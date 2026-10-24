package com.greysworkshop.naughtyornice.mixin;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The tab list's header and footer, for the Server tab (servers often put their TPS, ping or name there). */
@Mixin(PlayerListHud.class)
public interface PlayerListHudAccessor {
	@Accessor("header")
	Text naughtyornice$header();

	@Accessor("footer")
	Text naughtyornice$footer();
}
