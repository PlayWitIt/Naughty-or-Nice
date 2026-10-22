package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Where players logged out while you could see them, shown on screen at that spot ("Steve logged out · 4m ago").
 * Only ever what you saw yourself; a spot goes when they come back online, after two hours, or when you leave.
 */
public final class LogoutSpots {
	record Spot(UUID id, String name, String dimension, double x, double y, double z, long time) {}

	private static final List<Spot> spots = new ArrayList<>();
	private static final long KEEP_MS = 2 * 3_600_000L;
	private static final int MAX = 30;

	private LogoutSpots() {}

	static void add(UUID id, String name, String dimension, double x, double y, double z, long time) {
		spots.removeIf(s -> s.id.equals(id));
		spots.add(new Spot(id, name, dimension, x, y, z, time));
		while (spots.size() > MAX) spots.remove(0);
	}

	static void back(UUID id) {
		spots.removeIf(s -> s.id.equals(id));
	}

	static void clear() {
		spots.clear();
	}

	static List<Spot> spots() {
		return List.copyOf(spots);
	}

	public static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!NNConfig.on() || !NNConfig.INSTANCE.logoutSpots || spots.isEmpty() || client.world == null || client.player == null || client.options.hudHidden) return;
		long now = System.currentTimeMillis();
		spots.removeIf(s -> now - s.time > KEEP_MS);
		String dimension = client.world.getRegistryKey().getValue().toString();
		Camera camera = client.gameRenderer.getCamera();
		Vec3d eye = camera.getCameraPos();
		double yaw = Math.toRadians(camera.getYaw()), pitch = Math.toRadians(camera.getPitch());
		// Camera axes in world space: forward, right and up (as Hoard Eye's markers do it).
		Vec3d forward = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3d right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3d up = right.crossProduct(forward);
		double tanHalfFov = Math.tan(Math.toRadians(client.options.getFov().getValue()) / 2);
		int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
		double halfW = w / 2.0, halfH = h / 2.0;
		TextRenderer font = client.textRenderer;
		for (Spot s : spots) {
			if (!s.dimension.equals(dimension)) continue;
			Vec3d d = new Vec3d(s.x, s.y + 1.0, s.z).subtract(eye);
			double cx = d.dotProduct(right), cy = d.dotProduct(up), cz = d.dotProduct(forward);
			if (cz < 0.5) continue;
			int sx = (int) (halfW + cx / cz / tanHalfFov * halfH), sy = (int) (halfH - cy / cz / tanHalfFov * halfH);
			if (sx < 0 || sx > w || sy < 0 || sy > h) continue;
			String top = "⏻ " + s.name, bottom = String.format(Locale.ROOT, "logged out %s · %d m", ShopScreen.ago(s.time), (int) d.length());
			int tw = Math.max(font.getWidth(top), font.getWidth(bottom));
			context.fill(sx - tw / 2 - 3, sy - 11, sx + tw / 2 + 3, sy + 10, 0x90000000);
			context.drawCenteredTextWithShadow(font, top, sx, sy - 9, 0xFFFFC14D);
			context.drawCenteredTextWithShadow(font, bottom, sx, sy + 1, 0xFFB8C0C8);
		}
	}
}
