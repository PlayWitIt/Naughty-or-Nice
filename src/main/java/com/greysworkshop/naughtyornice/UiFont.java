package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.EffectGlyph;
import net.minecraft.client.font.GlyphProvider;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.util.Identifier;

/**
 * The typeface for our own screens: Inter (SIL Open Font License, in assets/naughtyornice/font), a clean modern face
 * instead of the game's pixel font, which reads as a toy at small sizes. Anything Inter has no glyph for (★ ✔ ✖ and
 * other symbols) falls back to the game's font. Screens swap in {@link #renderer()} in their init(); the HUD keeps the
 * game's font so it matches the rest of the game.
 */
public final class UiFont {
	public static final StyleSpriteSource.Font REGULAR = new StyleSpriteSource.Font(Identifier.of(NaughtyOrNiceClient.MOD_ID, "ui"));
	public static final StyleSpriteSource.Font BOLD = new StyleSpriteSource.Font(Identifier.of(NaughtyOrNiceClient.MOD_ID, "ui_bold"));

	private static TextRenderer renderer;
	private static TextRenderer.GlyphsProvider base;

	private UiFont() {}

	/** A text renderer that draws the default font as Inter, and leaves any other font as it is. */
	public static TextRenderer renderer() {
		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer.GlyphsProvider fonts = client.textRenderer.fonts;
		if (renderer == null || base != fonts) {
			base = fonts;
			renderer = new TextRenderer(new TextRenderer.GlyphsProvider() {
				@Override
				public GlyphProvider getGlyphs(StyleSpriteSource source) {
					return fonts.getGlyphs(StyleSpriteSource.DEFAULT.equals(source) ? REGULAR : source);
				}

				@Override
				public EffectGlyph getRectangleGlyph() {
					return fonts.getRectangleGlyph();
				}
			});
		}
		return renderer;
	}
}
