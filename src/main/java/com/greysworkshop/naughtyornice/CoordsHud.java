package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.world.World;

import java.util.Locale;

/**
 * Your coordinates in the bottom left, so F3 isn't needed: x y z, which way you face, and the matching spot in the
 * nether (or the overworld, from the nether). Hidden while stream-safe mode is on, and while F3 is open.
 */
public final class CoordsHud {
	private CoordsHud() {}

	public static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!NNConfig.on() || !NNConfig.INSTANCE.showCoords || client.player == null || client.world == null || client.options.hudHidden
				|| CoordsHud.f3Open(client) || ModsPanel.streamSafe()) return;
		context.drawTextWithShadow(client.textRenderer, line(client), 4, context.getScaledWindowHeight() - 12, 0xFFFFFFFF);
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
