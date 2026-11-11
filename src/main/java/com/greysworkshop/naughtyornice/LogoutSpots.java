package com.greysworkshop.naughtyornice;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Where players logged out while you could see them: a faint outline of them at that spot, and a small name tag above it
 * ("⏻ Steve", with "· 4m ago · 12 blocks" when you're close or aim at it).
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

	/** Within this, the ghost outline of the player is drawn in the world where they left. */
	private static final double GHOST_RANGE = 64;
	/** Within this, or when you aim at it, the tag says when they left and how far it is. */
	private static final double DETAIL_RANGE = 12;
	/** Beyond this a spot is only a small dot, unless you aim at it. */
	private static final double LABEL_RANGE = 128;
	/** How close to the crosshair (in GUI pixels) a spot counts as aimed at. */
	private static final int AIM = 20;
	private static final int AMBER = 0xFFC14D;
	private static final int GREY = 0xB8C0C8;
	private static final VoxelShape GHOST = VoxelShapes.cuboid(-0.3, 0, -0.3, 0.3, 1.8, 0.3);

	/** What the last frame drew, for the self-test: {tags with details, plain tags, dots}. */
	static volatile int[] lastDrawn = {0, 0, 0};

	/** A thin amber outline of the player where they logged out, so up close you see the very spot without a tag over it. */
	public static void renderWorld(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!NNConfig.on() || !NNConfig.INSTANCE.logoutSpots || spots.isEmpty() || client.world == null || client.player == null) return;
		MatrixStack matrices = context.matrices();
		if (matrices == null) return;
		String dimension = client.world.getRegistryKey().getValue().toString();
		Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
		VertexConsumerProvider consumers = context.consumers();
		VertexConsumerProvider.Immediate own = null;
		if (consumers == null) {
			own = client.getBufferBuilders().getEntityVertexConsumers();
			consumers = own;
		}
		VertexConsumer lines = consumers.getBuffer(RenderLayers.lines());
		for (Spot s : spots) {
			if (!s.dimension.equals(dimension)) continue;
			double dist = camera.distanceTo(new Vec3d(s.x, s.y + 0.9, s.z));
			if (dist > GHOST_RANGE) continue;
			// Fades as you walk away, so it never stands out more than the player would have.
			int alpha = (int) Math.round(255 * Math.max(0.35, 1 - dist / GHOST_RANGE));
			VertexRendering.drawOutline(matrices, lines, GHOST, s.x - camera.x, s.y - camera.y, s.z - camera.z, alpha << 24 | AMBER, 1.5f);
		}
		if (own != null) own.draw(RenderLayers.lines());
	}

	/**
	 * A small tag above where they stood: just the name, half the size of the HUD, fading with distance. When you're
	 * close or aim at it, it also says when they left and how far it is. Far away it's only a dot. Tags never pile on top
	 * of each other: the nearest one gets the tag, ones behind it become dots.
	 */
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
		float scale = CoordsHud.textScale(client);
		int lineH = CoordsHud.lineHeight(scale);

		List<Spot> here = new ArrayList<>();
		for (Spot s : spots) if (s.dimension.equals(dimension)) here.add(s);
		here.sort(java.util.Comparator.comparingDouble(s -> eye.squaredDistanceTo(s.x, s.y, s.z)));
		List<int[]> taken = new ArrayList<>();
		int detailed = 0, plain = 0, dots = 0;
		for (Spot s : here) {
			// Just above their head, so up close the tag sits over the outline, not across it.
			Vec3d d = new Vec3d(s.x, s.y + 2.1, s.z).subtract(eye);
			double cx = d.dotProduct(right), cy = d.dotProduct(up), cz = d.dotProduct(forward);
			if (cz < 0.5) continue;
			int sx = (int) Math.round(halfW + cx / cz / tanHalfFov * halfH), sy = (int) Math.round(halfH - cy / cz / tanHalfFov * halfH);
			if (sx < 0 || sx > w || sy < 0 || sy > h) continue;
			double dist = d.length();
			boolean aimed = Math.abs(sx - halfW) <= AIM && Math.abs(sy - halfH) <= AIM;
			boolean detail = aimed || dist <= DETAIL_RANGE;
			// Full strength up close, fading to 60% by the label range.
			double fade = Math.max(0.6, 1 - 0.4 * Math.max(0, dist - 32) / (LABEL_RANGE - 32));
			if (!detail && dist > LABEL_RANGE) {
				dot(context, sx, sy, fade);
				dots++;
				continue;
			}
			String text = "⏻ " + s.name + (detail ? String.format(Locale.ROOT, " · %s · %d blocks", ShopScreen.ago(s.time), (int) dist) : "");
			String name = "⏻ " + s.name;
			int tw = (int) Math.ceil(font.getWidth(text) * scale);
			int x0 = sx - tw / 2, y0 = sy - lineH;
			int[] box = {x0 - 2, y0 - 1, x0 + tw + 2, y0 + lineH - 1};
			if (overlaps(taken, box)) {
				dot(context, sx, sy, fade);
				dots++;
				continue;
			}
			taken.add(box);
			context.fill(box[0], box[1], box[2], box[3], (int) Math.round(0x60 * fade) << 24);
			int a = (int) Math.round(255 * fade) << 24;
			var m = context.getMatrices();
			m.pushMatrix();
			m.translate(x0, y0);
			m.scale(scale, scale);
			context.drawTextWithShadow(font, name, 0, 0, a | AMBER);
			if (detail) context.drawTextWithShadow(font, text.substring(name.length()), font.getWidth(name), 0, a | GREY);
			m.popMatrix();
			if (detail) detailed++;
			else plain++;
		}
		lastDrawn = new int[] {detailed, plain, dots};
	}

	/** A small amber dot with a dark rim. */
	private static void dot(DrawContext context, int x, int y, double fade) {
		int a = (int) Math.round(255 * fade) << 24;
		context.fill(x - 2, y - 2, x + 2, y + 2, (int) Math.round(0xA0 * fade) << 24);
		context.fill(x - 1, y - 1, x + 1, y + 1, a | AMBER);
	}

	private static boolean overlaps(List<int[]> taken, int[] b) {
		for (int[] t : taken) {
			if (b[0] < t[2] && t[0] < b[2] && b[1] < t[3] && t[1] < b[3]) return true;
		}
		return false;
	}
}
