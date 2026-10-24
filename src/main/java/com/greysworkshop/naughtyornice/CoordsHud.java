package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your coordinates in the bottom-right corner (or the bottom left, under the chat), so F3 isn't needed: x y z, which way you face, and the matching spot in the
 * nether (or the overworld, from the nether), in green, a size smaller than the rest of the HUD. On a dark backing so they read on snow and sand too, and split onto two
 * lines (then lifted above the hotbar) when they'd run into it. Hidden while F3 is open; in stream-safe mode a note
 * says they're hidden and how to bring them back, so they never just vanish. /nn coords says why they aren't showing.
 */
public final class CoordsHud {
	private CoordsHud() {}

	private static final int BACKING = 0x90000000;
	/** Your x y z in green, the matching spot in the other dimension a paler green. */
	private static final int GREEN = 0xFF55FF55;
	private static final int PALE_GREEN = 0xFF9EE6A8;
	private static final int NOTE = 0xFFAAAAAA;
	/** The hotbar's half-width, plus room for the offhand slot to its left. */
	private static final int HOTBAR_HALF = 91 + 29;

	public static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null || client.options.hudHidden || f3Open(client)) return;
		if (!NNConfig.on() || !NNConfig.INSTANCE.showCoords) return;
		int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
		float s = scale(client);
		if (ModsPanel.streamSafe()) {
			draw(context, client.textRenderer, List.of("Coordinates hidden: stream-safe (F7)"), w, h, s, true);
			return;
		}
		draw(context, client.textRenderer, lines(client.textRenderer, line(client), w, s), w, h, s, false);
	}

	/**
	 * How big the text is drawn: one GUI scale step smaller than the rest of the screen when Small coordinates is on
	 * (2 of 3, 3 of 4...), so every letter still lands on whole pixels and stays sharp. At GUI scale 2 a whole step
	 * would halve it, too small to read, so it's three quarters there.
	 */
	public static float scale(MinecraftClient client) {
		int gui = (int) Math.round(client.getWindow().getScaleFactor());
		if (!NNConfig.INSTANCE.smallCoords || gui <= 1) return 1f;
		return gui == 2 ? 0.75f : (gui - 1) / (float) gui;
	}

	/** Height of one line on screen at that scale. */
	static int lineHeight(float s) {
		return (int) Math.ceil(9 * s) + 2;
	}

	/** Bottom line last. Lifted above the hotbar when any line would reach it. */
	private static void draw(DrawContext context, TextRenderer tr, List<String> lines, int w, int h, float s, boolean note) {
		int lh = lineHeight(s);
		int y = bottomY(tr, lines, w, h, s) - (lines.size() - 1) * lh;
		for (int i = 0; i < lines.size(); i++) {
			String l = lines.get(i);
			int color = note ? NOTE : l.startsWith("XYZ") ? GREEN : PALE_GREEN;
			int tw = (int) Math.ceil(tr.getWidth(l) * s);
			int x = left(tw, w);
			context.fill(x - 2, y - 1, x + tw + 2, y + lh - 1, BACKING);
			var m = context.getMatrices();
			m.pushMatrix();
			m.translate(x, y);
			m.scale(s, s);
			context.drawTextWithShadow(tr, l, 0, 0, color);
			m.popMatrix();
			y += lh;
		}
	}

	/** Where a line of that width starts: in the bottom-right corner (by default), or the bottom-left under the chat. */
	static int left(int textWidth, int screenWidth) {
		return NNConfig.INSTANCE.coordsRight ? screenWidth - 4 - textWidth : 4;
	}

	/** The top of the bottom line: just above the screen's edge, or above the hotbar when a line would reach it. */
	static int bottomY(TextRenderer tr, List<String> lines, int w, int h, float s) {
		int bottom = h - lineHeight(s) - 1;
		for (String l : lines) {
			if (tr.getWidth(l) * s > room(w)) return bottom - 24;
		}
		return bottom;
	}

	/** How wide the text in the corner can be before it reaches the hotbar. */
	static int room(int screenWidth) {
		return screenWidth / 2 - HOTBAR_HALF - 10;
	}

	/** The line as one, or split before the nether/overworld part when it's too wide for the space beside the hotbar. */
	static List<String> lines(TextRenderer tr, String line, int screenWidth, float s) {
		int cut = line.indexOf("  ·  ");
		if (cut < 0 || tr.getWidth(line) * s <= room(screenWidth)) return List.of(line);
		List<String> out = new ArrayList<>();
		out.add(line.substring(cut + 5));
		out.add(line.substring(0, cut));
		return out;
	}

	/** Why the coordinates aren't on screen right now, or null when they are. */
	public static String hiddenReason(MinecraftClient client) {
		if (!NNConfig.on()) return "Naughty or Nice is switched off (Mods panel, Right Shift on the pause menu).";
		if (!NNConfig.INSTANCE.showCoords) return "the My coordinates setting is off.";
		if (ModsPanel.streamSafe()) return "stream-safe mode is on. Press F7 to turn it off.";
		if (client.options.hudHidden) return "the HUD is hidden (F1).";
		if (f3Open(client)) return "F3 is open, and it shows them already.";
		return null;
	}

	/**
	 * Whether the F3 screen is open. Not the game's own "debug HUD showing", which since 1.21.9 is also true whenever any
	 * F3 entry is set to always on (hitboxes, say), F3 closed or not, and would hide everything here for good.
	 */
	public static boolean f3Open(MinecraftClient client) {
		return client.debugHudEntryList.isF3Enabled();
	}

	static String line(MinecraftClient client) {
		var p = client.player;
		int x = p.getBlockX(), y = p.getBlockY(), z = p.getBlockZ();
		// The way you're looking, the game's way: yaw 0 is south.
		double yaw = Math.toRadians(p.getYaw());
		String facing = ChunkTrails.compass(-Math.sin(yaw), Math.cos(yaw));
		String line = String.format(Locale.ROOT, "XYZ %d %d %d  %s", x, y, z, facing);
		if (client.world.getRegistryKey() == World.OVERWORLD) line += String.format(Locale.ROOT, "  ·  Nether %d %d", Math.floorDiv(x, 8), Math.floorDiv(z, 8));
		else if (client.world.getRegistryKey() == World.NETHER) line += String.format(Locale.ROOT, "  ·  Overworld %d %d", x * 8, z * 8);
		return line;
	}
}
