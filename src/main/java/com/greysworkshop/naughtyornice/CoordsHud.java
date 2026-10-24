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
 * Your coordinates in the bottom left, so F3 isn't needed: x y z, which way you face, and the matching spot in the
 * nether (or the overworld, from the nether). On a dark backing so they read on snow and sand too, and split onto two
 * lines (then lifted above the hotbar) when they'd run into it. Hidden while F3 is open; in stream-safe mode a note
 * says they're hidden and how to bring them back, so they never just vanish. /nn coords says why they aren't showing.
 */
public final class CoordsHud {
	private CoordsHud() {}

	private static final int BACKING = 0x90000000;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int NOTE = 0xFFAAAAAA;
	/** The hotbar's half-width, plus room for the offhand slot to its left. */
	private static final int HOTBAR_HALF = 91 + 29;

	public static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null || client.options.hudHidden || f3Open(client)) return;
		if (!NNConfig.on() || !NNConfig.INSTANCE.showCoords) return;
		int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
		if (ModsPanel.streamSafe()) {
			draw(context, client.textRenderer, List.of("Coordinates hidden: stream-safe (F7)"), w, h, NOTE);
			return;
		}
		draw(context, client.textRenderer, lines(client.textRenderer, line(client), w), w, h, TEXT);
	}

	/** Bottom line last. Lifted above the hotbar when any line would reach it. */
	private static void draw(DrawContext context, TextRenderer tr, List<String> lines, int w, int h, int color) {
		int y = bottomY(tr, lines, w, h) - (lines.size() - 1) * 11;
		for (String l : lines) {
			context.fill(2, y - 2, 4 + tr.getWidth(l) + 2, y + 9, BACKING);
			context.drawTextWithShadow(tr, l, 4, y, color);
			y += 11;
		}
	}

	/** Where the bottom line goes: just above the screen's edge, or above the hotbar when a line would reach it. */
	static int bottomY(TextRenderer tr, List<String> lines, int w, int h) {
		int room = room(w);
		for (String l : lines) {
			if (tr.getWidth(l) > room) return h - 24 - 12;
		}
		return h - 12;
	}

	/** How wide the bottom-left text can be before it reaches the hotbar. */
	static int room(int screenWidth) {
		return screenWidth / 2 - HOTBAR_HALF - 10;
	}

	/** The line as one, or split before the nether/overworld part when it's too wide for the space beside the hotbar. */
	static List<String> lines(TextRenderer tr, String line, int screenWidth) {
		int cut = line.indexOf("  ·  ");
		if (cut < 0 || tr.getWidth(line) <= room(screenWidth)) return List.of(line);
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
