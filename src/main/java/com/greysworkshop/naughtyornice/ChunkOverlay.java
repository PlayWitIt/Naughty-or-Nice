package com.greysworkshop.naughtyornice;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunk trails drawn in the world: a flat square on every chunk that's been read, new land red, old land green,
 * chunks of a trail yellow, at one height (your feet, or a height you pick); and a line at the top of the screen saying
 * which the chunk you're in is. K shows or hides both.
 */
public final class ChunkOverlay {
	/** The height setting's value for "at your feet". */
	public static final int FOLLOW = -1000;
	public static final int FRESH = 0xFF4040, OLD = 0x3CDC5A, TRAIL = 0xFFE040, UNKNOWN = 0x9AA4AE;
	/** A square a hair inside its chunk, so neighbours' outlines don't sit on top of each other. */
	private static final double INSET = 0.15;
	private static final VoxelShape SQUARE = VoxelShapes.cuboid(INSET, 0, INSET, 16 - INSET, 0.02, 16 - INSET);
	private static final int MAX_RADIUS = 32;

	private record Square(int cx, int cz, int rgb) {}

	private static List<Square> squares = List.of();
	private static String builtFor = "";
	private static long builtAt;

	private ChunkOverlay() {}

	/** How many squares are being drawn, for the self-test. */
	static int shown() {
		return squares.size();
	}

	public static void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		NNConfig c = NNConfig.INSTANCE;
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		if (!NNConfig.on() || !c.chunkTrails || !c.chunkOverlay || mod == null || client.world == null || client.player == null) return;
		String dim = client.world.getRegistryKey().getValue().toString();
		int pcx = client.player.getBlockX() >> 4, pcz = client.player.getBlockZ() >> 4;
		int r = Math.min(MAX_RADIUS, client.options.getClampedViewDistance() + 2);
		build(mod.ledger.chunkTrails, dim, pcx, pcz, r);
		if (squares.isEmpty()) return;

		MatrixStack matrices = context.matrices();
		if (matrices == null) return;
		Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
		double y = (c.chunkOverlayY == FOLLOW ? Math.floor(client.player.getY()) : c.chunkOverlayY) + 0.03 - camera.y;
		VertexConsumerProvider consumers = context.consumers();
		VertexConsumerProvider.Immediate own = null;
		if (consumers == null) {
			own = client.getBufferBuilders().getEntityVertexConsumers();
			consumers = own;
		}
		// Outlines first, then the fill, as Base Insurance's boxes do.
		VertexConsumer lines = consumers.getBuffer(RenderLayers.lines());
		for (Square s : squares) {
			VertexRendering.drawOutline(matrices, lines, SQUARE, s.cx * 16 - camera.x, y, s.cz * 16 - camera.z, 0xFF000000 | s.rgb, 1.5f);
		}
		if (own != null) own.draw(RenderLayers.lines());
		int alpha = Math.round(c.chunkOverlayFill * 2.55f) << 24;
		if (alpha != 0) {
			VertexConsumer quads = consumers.getBuffer(RenderLayers.debugQuads());
			MatrixStack.Entry entry = matrices.peek();
			for (Square s : squares) {
				float x0 = (float) (s.cx * 16 + INSET - camera.x), z0 = (float) (s.cz * 16 + INSET - camera.z);
				float x1 = x0 + 16 - (float) (2 * INSET), z1 = z0 + 16 - (float) (2 * INSET), fy = (float) y;
				int argb = alpha | s.rgb;
				// One face: this layer isn't culled, so it shows from above and below.
				quad(quads, entry, argb, x0, fy, z0, x0, fy, z1, x1, fy, z1, x1, fy, z0);
			}
			if (own != null) own.draw(RenderLayers.debugQuads());
		}
	}

	/** The squares around you, worked out again when you change chunk, what's known changes, or every half second. */
	private static void build(ChunkTrails trails, String dim, int pcx, int pcz, int r) {
		String key = dim + "/" + pcx + "/" + pcz + "/" + r + "/" + trails.version();
		long now = System.currentTimeMillis();
		if (key.equals(builtFor) && now - builtAt < 500) return;
		// Trails close enough to matter, so each chunk is checked against only a few.
		List<ChunkTrails.Trail> near = new ArrayList<>();
		double reach = (r + 2) * 16 * 1.5;
		for (ChunkTrails.Trail t : trails.trails) {
			if (t.dimension.equals(dim) && ChunkTrails.segmentDistance(t, pcx * 16 + 8, pcz * 16 + 8) <= reach + t.length()) near.add(t);
		}
		List<Square> list = new ArrayList<>();
		for (int i = -r; i <= r; i++) {
			for (int j = -r; j <= r; j++) {
				int cx = pcx + i, cz = pcz + j;
				byte state = trails.state(dim, cx, cz);
				if (state == 0) continue;
				int rgb = state == ChunkTrails.NEW ? FRESH : onTrail(near, cx, cz) ? TRAIL : OLD;
				list.add(new Square(cx, cz, rgb));
			}
		}
		squares = list;
		builtFor = key;
		builtAt = now;
	}

	/** The line at the top of the screen: what the chunk you're standing in is, in its colour. */
	public static void renderHud(net.minecraft.client.gui.DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		NNConfig c = NNConfig.INSTANCE;
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		if (!NNConfig.on() || !c.chunkTrails || !c.chunkOverlay || mod == null || client.world == null || client.player == null
				|| client.options.hudHidden || client.getDebugHud().shouldShowDebugHud()) return;
		String line = hereLine(mod.ledger.chunkTrails, client.world.getRegistryKey().getValue().toString(), client.player.getX(), client.player.getZ());
		int rgb = line.startsWith("New") ? FRESH : line.startsWith("Trail") ? TRAIL : line.startsWith("Old") ? OLD : UNKNOWN;
		var font = client.textRenderer;
		int w = font.getWidth(line) + 18, x = (context.getScaledWindowWidth() - w) / 2, y = 4;
		context.fill(x - 3, y - 3, x + w + 3, y + 12, 0xB0101418);
		context.fill(x, y, x + 10, y + 9, 0xFF000000 | rgb);
		context.drawTextWithShadow(font, line, x + 15, y + 1, 0xFF000000 | rgb);
	}

	/** What the chunk at x, z is, in words: new, old, on a trail (and which way it runs), or not known. */
	static String hereLine(ChunkTrails trails, String dim, double x, double z) {
		int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
		byte state = trails.state(dim, cx, cz);
		if (state == ChunkTrails.NEW) return "New chunk: nobody had been here before you";
		if (state == ChunkTrails.OLD) {
			ChunkTrails.Trail t = trails.nearest(dim, cx * 16 + 8, cz * 16 + 8, 400);
			if (t != null && ChunkTrails.segmentDistance(t, cx * 16 + 8, cz * 16 + 8) <= Math.max(1, t.width) * 8 + 12) {
				String a = ChunkTrails.compass(t.x1 - t.x2, t.z1 - t.z2), b = ChunkTrails.compass(t.x2 - t.x1, t.z2 - t.z1);
				return "Trail: you're on one, it runs " + a + " - " + b;
			}
			return "Old chunk: someone has been here";
		}
		return "Can't tell this chunk yet";
	}

	/** Whether an old chunk is part of a trail that was found: within the trail's width of its line. */
	private static boolean onTrail(List<ChunkTrails.Trail> near, int cx, int cz) {
		for (ChunkTrails.Trail t : near) {
			if (ChunkTrails.segmentDistance(t, cx * 16 + 8, cz * 16 + 8) <= Math.max(1, t.width) * 8 + 12) return true;
		}
		return false;
	}

	private static void quad(VertexConsumer buffer, MatrixStack.Entry entry, int argb,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		buffer.vertex(entry, ax, ay, az).color(argb);
		buffer.vertex(entry, bx, by, bz).color(argb);
		buffer.vertex(entry, cx, cy, cz).color(argb);
		buffer.vertex(entry, dx, dy, dz).color(argb);
	}
}
