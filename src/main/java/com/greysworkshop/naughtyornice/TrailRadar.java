package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * The trail radar: a round radar screen on the right that turns with you (ahead is up), showing only what it's for.
 * Old land (chunks someone loaded before you) is a green square; a trail (a long strip of old land through new land:
 * someone's path) is a thick amber line with an arrow on the end away from spawn. New land is left dark, and there's
 * no terrain, so it can't be mistaken for a map. Rings mark the distance. Under it, in plain words: where the nearest
 * trail is and how to turn to follow it. Off until you turn it on; three sizes.
 */
public final class TrailRadar {
	/** Its diameter on screen for Small, Medium and Large. */
	private static final int[] SIZES = {96, 128, 168};
	private static final int SCREEN = 0xD00A140E, RIM = 0xFF3C6A4A, RING = 0x5050A070, CROSS = 0x2850A070,
			OLD = 0xC040D060, OLD_FAR = 0x7040D060, FRESH = 0x14FFFFFF, TRAIL = 0xFF000000 | ChunkOverlay.TRAIL,
			SWEEP = 0x3050FF80;

	/** The last analysis, refreshed by the watcher every two seconds. */
	static List<int[]> patches = new ArrayList<>();
	/** For the self-test: old and new chunks counted on the last frame drawn. */
	static int lastOld, lastNew;

	private TrailRadar() {}

	/** Where the radar's title was drawn last frame (for the self-tests). */
	public static int lastTop = -1;

	/** Where the radar circle's right edge was last frame (for the self-tests). */
	public static int lastRight = -1;

	private static java.lang.reflect.Method minimapBottom, minimapLeft;
	private static Object topRight;
	private static boolean minimapLooked;

	/** Base Insurance's minimap in the top right corner last frame, as {left x, bottom y}, or null if it isn't there. */
	static int[] minimapEdges() {
		if (!minimapLooked) {
			minimapLooked = true;
			if (NaughtyOrNiceClient.withBaseInsurance()) {
				try {
					Class<?> corner = Class.forName("com.greysworkshop.baseinsurance.BIConfig$Corner");
					Class<?> minimap = Class.forName("com.greysworkshop.baseinsurance.Minimap");
					minimapBottom = minimap.getMethod("bottomIn", corner);
					minimapLeft = minimap.getMethod("leftIn", corner);
					topRight = corner.getField("TOP_RIGHT").get(null);
				} catch (ReflectiveOperationException | LinkageError e) {
					minimapBottom = null;
				}
			}
		}
		if (minimapBottom == null) return null;
		try {
			int bottom = (int) minimapBottom.invoke(null, topRight), left = (int) minimapLeft.invoke(null, topRight);
			return bottom < 0 || left < 0 ? null : new int[] {left, bottom};
		} catch (ReflectiveOperationException | LinkageError e) {
			minimapBottom = null;
			return null;
		}
	}

	public static void render(DrawContext context, RenderTickCounter tickCounter) {
		if (!NNConfig.on()) return;
		MinecraftClient client = MinecraftClient.getInstance();
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		if (mod == null || !NNConfig.INSTANCE.chunkTrails || !NNConfig.INSTANCE.trailRadar || client.world == null || client.player == null
				|| client.options.hudHidden || CoordsHud.f3Open(client)) return;
		ChunkTrails trails = mod.ledger.chunkTrails;
		String dim = client.world.getRegistryKey().getValue().toString();
		float delta = tickCounter.getTickProgress(true);
		Vec3d me = client.player.getLerpedPos(delta);
		float yaw = client.player.getYaw(delta);
		TextRenderer font = client.textRenderer;
		int size = SIZES[Math.max(0, Math.min(2, NNConfig.INSTANCE.radarSize))];
		int radius = size / 2;
		// As far as the land is loaded.
		int span = Math.max(6, Math.min(32, client.options.getClampedViewDistance()));
		double blocks = span * 16.0;
		float ts = CoordsHud.scale(client);
		int lh = (int) Math.ceil(10 * ts);

		int x0 = context.getScaledWindowWidth() - size - 8;
		// Top right, below the title and below potion effect icons, so the words under it stay clear of the hotbar and
		// messages. Base Insurance's minimap may be there too: then below that, or beside it if there's no room below.
		int y0 = lh + 6;
		var effects = client.player.getStatusEffects();
		if (effects.stream().anyMatch(e -> e.shouldShowIcon() && e.getEffectType().value().isBeneficial())) y0 += 26;
		if (effects.stream().anyMatch(e -> e.shouldShowIcon() && !e.getEffectType().value().isBeneficial())) y0 += 26;
		int[] minimap = minimapEdges();
		if (minimap != null) {
			int below = Math.max(y0, minimap[1] + lh + 6);
			// Room under it for the circle and the lines of words below it.
			if (below + size + 3 * lh + 16 <= context.getScaledWindowHeight()) {
				y0 = below;
			} else {
				x0 = minimap[0] - size - 8;
			}
		}
		lastTop = y0 - lh - 3;
		lastRight = x0 + size;
		int cx = x0 + radius, cy = y0 + radius;

		// Title, so it's clear what this is and whose it is.
		int titleY = y0 - lh - 3;
		small(context, font, "§6TRAIL RADAR §8· Naughty or Nice", x0 + size, titleY, ts, context.getScaledWindowWidth() - 4);

		// The round screen, its rim, distance rings and cross.
		disc(context, cx, cy, radius, SCREEN);
		ring(context, cx, cy, radius, RIM);
		ring(context, cx, cy, radius - 1, RIM);
		ring(context, cx, cy, radius / 2, RING);
		context.fill(cx - radius + 2, cy, cx + radius - 2, cy + 1, CROSS);
		context.fill(cx, cy - radius + 2, cx + 1, cy + radius - 2, CROSS);

		// Chunks, in blocks from you, turned so ahead is up. Only cells inside the screen are drawn.
		double scale = radius / blocks;
		double angle = Math.toRadians(-(yaw + 180));
		double cos = Math.cos(angle), sin = Math.sin(angle);
		int pcx = (int) Math.floor(me.x) >> 4, pcz = (int) Math.floor(me.z) >> 4;
		int cell = Math.max(2, (int) Math.round(16 * scale));
		int oldCount = 0, newCount = 0;
		for (int i = -span; i <= span; i++) {
			for (int j = -span; j <= span; j++) {
				byte s = trails.state(dim, pcx + i, pcz + j);
				if (s == 0) continue;
				if (s == ChunkTrails.OLD) oldCount++;
				else newCount++;
				double bx = (pcx + i) * 16 + 8 - me.x, bz = (pcz + j) * 16 + 8 - me.z;
				int[] p = toScreen(bx, bz, scale, cos, sin, cx, cy);
				if (Math.hypot(p[0] - cx, p[1] - cy) + cell * 0.71 > radius - 3) continue;
				boolean loaded = client.world.getChunkManager().isChunkLoaded(pcx + i, pcz + j);
				int color = s == ChunkTrails.OLD ? (loaded ? OLD : OLD_FAR) : FRESH;
				context.fill(p[0] - cell / 2, p[1] - cell / 2, p[0] - cell / 2 + cell, p[1] - cell / 2 + cell, color);
			}
		}
		lastOld = oldCount;
		lastNew = newCount;

		// Trails: thick amber lines, an arrow on the end away from spawn.
		for (ChunkTrails.Trail t : trails.trails) {
			if (!t.dimension.equals(dim)) continue;
			if (ChunkTrails.segmentDistance(t, me.x, me.z) > blocks * 1.5) continue;
			int thick = Math.max(3, (int) Math.round(t.width * 16 * scale * 0.6));
			line(context, t.x1 - me.x, t.z1 - me.z, t.x2 - me.x, t.z2 - me.z, scale, cos, sin, cx, cy, radius - 3, thick);
			if (t.away != 0) {
				int[] end = t.awayEnd();
				int[] from = t.away == 1 ? new int[] {t.x2, t.z2} : new int[] {t.x1, t.z1};
				arrow(context, end[0] - me.x, end[1] - me.z, end[0] - from[0], end[1] - from[1], scale, cos, sin, cx, cy, radius - 3);
			}
		}

		// A slow sweep, so it reads as a radar at a glance.
		double sweep = (System.currentTimeMillis() % 4000) / 4000.0 * Math.PI * 2;
		for (int k = 0; k < 6; k++) {
			double a = sweep - k * 0.06;
			int alpha = (SWEEP >>> 24) * (6 - k) / 6;
			spoke(context, cx, cy, radius - 3, a, (alpha << 24) | (SWEEP & 0xFFFFFF));
		}

		// You, pointing ahead; N, E, S, W on the rim.
		context.drawText(font, "▲", cx - font.getWidth("▲") / 2 + 1, cy - 4, 0xFFFFFFFF, true);
		String[] names = {"N", "E", "S", "W"};
		for (int k = 0; k < 4; k++) {
			// North is -z; turn it like the chunks.
			double wx = k == 1 ? 1 : k == 3 ? -1 : 0, wz = k == 0 ? -1 : k == 2 ? 1 : 0;
			double sx = wx * cos - wz * sin, sz = wx * sin + wz * cos;
			int lx = (int) Math.round(cx + sx * (radius - 7)), ly = (int) Math.round(cy + sz * (radius - 7));
			context.drawTextWithShadow(font, names[k], lx - font.getWidth(names[k]) / 2 + 1, ly - 4, k == 0 ? 0xFFFF5050 : 0xFFB0C8B8);
		}

		// In words, under the screen: what to do.
		String[] say = trails.guidance(dim, me.x, me.z, yaw);
		List<String> lines = new ArrayList<>();
		if (say != null) {
			lines.add("§6" + say[0]);
			lines.add("§f" + say[1]);
		} else if (insideOld(trails, dim, me)) {
			lines.add("§aYou're on old land");
			lines.add("§7It may be a wide trail: fly sideways to find its edges");
		} else if (!patches.isEmpty()) {
			int[] p = patches.get(0);
			double turn = ChunkTrails.turn(yaw, p[0] - me.x, p[1] - me.z);
			lines.add("§aOld land ahead, in new land");
			lines.add(String.format(java.util.Locale.ROOT, "§f%,d blocks %s: fly over it", (int) Math.hypot(p[0] - me.x, p[1] - me.z), ChunkTrails.side(turn)));
		} else if (oldCount + newCount == 0) {
			lines.add("§7Reading the land…");
			lines.add("§8It tells old from new by water and lava");
		} else {
			lines.add("§7No trail in range");
			lines.add("§8Fly through new land and one will show up");
		}
		int ty = y0 + size + 4;
		for (String line : lines) {
			small(context, font, line, x0 + size, ty, ts, context.getScaledWindowWidth() - 4);
			ty += lh;
		}
		small(context, font, "§a■§7 someone was here  §6━§7 trail  §7◯ " + (int) (blocks / 2) + "m", x0 + size, ty + 2, ts * 0.9f, context.getScaledWindowWidth() - 4);
	}

	/** A spot in blocks from you, on screen: turned so ahead is up, scaled. */
	private static int[] toScreen(double bx, double bz, double scale, double cos, double sin, int cx, int cy) {
		double sx = bx * cos - bz * sin, sz = bx * sin + bz * cos;
		return new int[] {(int) Math.round(cx + sx * scale), (int) Math.round(cy + sz * scale)};
	}

	/** A filled circle, row by row. */
	private static void disc(DrawContext context, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.floor(Math.sqrt((double) r * r - dy * dy));
			context.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	/** A circle's outline, a pixel wide. */
	private static void ring(DrawContext context, int cx, int cy, int r, int color) {
		int steps = Math.max(24, r * 6);
		for (int k = 0; k < steps; k++) {
			double a = k * Math.PI * 2 / steps;
			int x = (int) Math.round(cx + Math.cos(a) * r), y = (int) Math.round(cy + Math.sin(a) * r);
			context.fill(x, y, x + 1, y + 1, color);
		}
	}

	private static void spoke(DrawContext context, int cx, int cy, int r, double a, int color) {
		for (int k = 2; k < r; k++) {
			int x = (int) Math.round(cx + Math.cos(a) * k), y = (int) Math.round(cy + Math.sin(a) * k);
			context.fill(x, y, x + 1, y + 1, color);
		}
	}

	/** A thick line between two spots (in blocks from you), cut off at the edge of the screen. */
	private static void line(DrawContext context, double x1, double z1, double x2, double z2, double scale, double cos, double sin,
			int cx, int cy, int limit, int thickness) {
		int[] a = toScreen(x1, z1, scale, cos, sin, cx, cy), b = toScreen(x2, z2, scale, cos, sin, cx, cy);
		double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
		int steps = (int) Math.max(1, len);
		int h = thickness / 2;
		for (int k = 0; k <= steps; k++) {
			double t = (double) k / steps;
			int x = (int) Math.round(a[0] + (b[0] - a[0]) * t), y = (int) Math.round(a[1] + (b[1] - a[1]) * t);
			// Thinner towards the rim, so the line never spills past the round screen.
			double d = Math.hypot(x - cx, y - cy);
			if (d > limit) continue;
			int hh = Math.min(h, (int) ((limit - d) / 1.42));
			context.fill(x - hh, y - hh, x + hh + 1, y + hh + 1, TRAIL);
		}
	}

	/** An arrowhead at a spot (blocks from you) pointing along (dx, dz), if it's on the screen. */
	private static void arrow(DrawContext context, double x, double z, double dx, double dz, double scale, double cos, double sin,
			int cx, int cy, int limit) {
		double len = Math.hypot(dx, dz);
		if (len < 1) return;
		int[] tip = toScreen(x, z, scale, cos, sin, cx, cy);
		double ux = dx / len, uz = dz / len;
		double sx = ux * cos - uz * sin, sz = ux * sin + uz * cos;
		for (int back = 0; back <= 6; back++) {
			for (int across = -back; across <= back; across++) {
				int px = (int) Math.round(tip[0] - sx * back - sz * across * 0.8), py = (int) Math.round(tip[1] - sz * back + sx * across * 0.8);
				if (Math.hypot(px - cx, py - cy) > limit) continue;
				context.fill(px, py, px + 1, py + 1, TRAIL);
			}
		}
	}

	/** A line of text at scale {@code s}, its right end at {@code right} (never past {@code limit}). */
	private static void small(DrawContext context, TextRenderer font, String line, int right, int y, float s, int limit) {
		int w = (int) Math.ceil(font.getWidth(line) * s);
		int x = Math.min(right - w, limit - w);
		var m = context.getMatrices();
		m.pushMatrix();
		m.translate(x, y);
		m.scale(s, s);
		context.drawTextWithShadow(font, line, 0, 0, 0xFFFFFFFF);
		m.popMatrix();
	}

	/** Old land all around and no fresh land in sight: you can't see where it ends. */
	private static boolean insideOld(ChunkTrails trails, String dim, Vec3d me) {
		int[] n = trails.around(dim, (int) Math.floor(me.x) >> 4, (int) Math.floor(me.z) >> 4, 8);
		return n[0] >= 12 && n[1] == 0;
	}
}
