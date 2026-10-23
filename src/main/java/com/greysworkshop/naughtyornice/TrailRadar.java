package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The chunk-trail radar: a small map on the right of the screen that turns with you, so ahead is always up. New land
 * is a faint red, old land a faint green, and trails are thick yellow lines with an arrow on the end that leads away
 * from spawn. Under it, in words: where the nearest trail is, which way it runs, and how to turn to follow it.
 */
public final class TrailRadar {
	private static final int SIZE = 112;
	/** Chunks from the middle to the edge. */
	private static final int SPAN = 32;
	private static final int FRESH = 0x60000000 | ChunkOverlay.FRESH, OLD_LAND = 0x80000000 | ChunkOverlay.OLD, UNKNOWN_DOT = 0x60FFFFFF,
			TRAIL = 0xFF000000 | ChunkOverlay.TRAIL, BACK = 0xB0101418, EDGE = 0xFF3A4048;

	/** The last analysis, refreshed by the watcher every two seconds. */
	static List<int[]> patches = new ArrayList<>();

	private TrailRadar() {}

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
		int x0 = context.getScaledWindowWidth() - SIZE - 6;
		int y0 = context.getScaledWindowHeight() / 2 - SIZE / 2 - 20;
		int cx = x0 + SIZE / 2, cy = y0 + SIZE / 2;

		context.fill(x0 - 1, y0 - 1, x0 + SIZE + 1, y0 + SIZE + 1, EDGE);
		context.fill(x0, y0, x0 + SIZE, y0 + SIZE, BACK);
		context.enableScissor(x0, y0, x0 + SIZE, y0 + SIZE);
		Matrix3x2fStack m = context.getMatrices();
		m.pushMatrix();
		m.translate(cx, cy);
		m.rotate((float) Math.toRadians(-(yaw + 180)));
		float scale = SIZE / 2f / (SPAN * 16f);
		m.scale(scale, scale);
		// Land, chunk by chunk (a little past the corners, since the map turns).
		int pcx = (int) Math.floor(me.x) >> 4, pcz = (int) Math.floor(me.z) >> 4;
		int reach = (int) Math.ceil(SPAN * 1.45);
		// What's been read around you, so it's clear the radar is working even with no trail in sight.
		int fresh = 0, old = 0, unclear = 0;
		for (int i = -reach; i <= reach; i++) {
			for (int j = -reach; j <= reach; j++) {
				byte s = trails.state(dim, pcx + i, pcz + j);
				if (Math.abs(i) <= SPAN && Math.abs(j) <= SPAN) {
					if (s == ChunkTrails.NEW) fresh++;
					else if (s == ChunkTrails.OLD) old++;
					else if (client.world.getChunkManager().isChunkLoaded(pcx + i, pcz + j)) unclear++;
				}
				int bx = (int) Math.round((pcx + i) * 16 - me.x), bz = (int) Math.round((pcz + j) * 16 - me.z);
				if (s == 0) {
					// Loaded, but nothing in it to tell by: dotted, so it's clearly "looked, can't tell", not "never seen".
					if (client.world.getChunkManager().isChunkLoaded(pcx + i, pcz + j)) {
						context.fill(bx + 6, bz + 6, bx + 10, bz + 10, UNKNOWN_DOT);
					}
					continue;
				}
				context.fill(bx, bz, bx + 16, bz + 16, s == ChunkTrails.NEW ? FRESH : OLD_LAND);
			}
		}
		// Trails: thick lines, an arrow on the end away from spawn.
		for (ChunkTrails.Trail t : trails.trails) {
			if (!t.dimension.equals(dim)) continue;
			if (ChunkTrails.segmentDistance(t, me.x, me.z) > SPAN * 16 * 2) continue;
			line(context, t.x1 - me.x, t.z1 - me.z, t.x2 - me.x, t.z2 - me.z, Math.max(10, t.width * 4), TRAIL);
			if (t.away != 0) {
				int[] end = t.awayEnd();
				int[] from = t.away == 1 ? new int[] {t.x2, t.z2} : new int[] {t.x1, t.z1};
				arrow(context, end[0] - me.x, end[1] - me.z, end[0] - from[0], end[1] - from[1], TRAIL);
			}
		}
		m.popMatrix();
		context.disableScissor();

		// You, pointing ahead; north on the edge.
		context.drawText(font, "▲", cx - font.getWidth("▲") / 2, cy - 4, 0xFFFFFFFF, true);
		double north = Math.toRadians(-(yaw + 180)) - Math.PI / 2;
		double half = SIZE / 2.0 - 5;
		double nx = Math.cos(north), nz = Math.sin(north);
		double edge = half / Math.max(Math.abs(nx), Math.abs(nz));
		int lx = (int) Math.round(cx + nx * edge), ly = (int) Math.round(cy + nz * edge);
		context.fill(lx - 4, ly - 5, lx + 4, ly + 5, 0xC0000000);
		context.drawTextWithShadow(font, "N", lx - font.getWidth("N") / 2 + 1, ly - 4, 0xFFFF5050);

		// In words.
		String[] say = trails.guidance(dim, me.x, me.z, yaw);
		List<String> lines = new ArrayList<>();
		if (say != null) {
			lines.add("§6" + say[0]);
			lines.add("§f" + say[1]);
		} else if (insideOld(trails, dim, me)) {
			lines.add("§7You're inside old land");
			lines.add("§8It may be a wide trail: fly sideways to find its edges");
		} else if (!patches.isEmpty()) {
			int[] p = patches.get(0);
			double turn = ChunkTrails.turn(yaw, p[0] - me.x, p[1] - me.z);
			lines.add("§7Old land in fresh land");
			lines.add(String.format(java.util.Locale.ROOT, "§f%,d blocks %s: fly over it", (int) Math.hypot(p[0] - me.x, p[1] - me.z), ChunkTrails.side(turn)));
		} else if (fresh + old == 0) {
			lines.add("§7Nothing to read here yet");
			lines.add("§8It reads land by its water and lava");
		} else {
			lines.add("§7No trail here");
			lines.add("§8Fly through fresh land to find one");
		}
		lines.add(String.format(java.util.Locale.ROOT, "§8Around you: §c%d new §8· §a%d old §8· %d can't tell", fresh, old, unclear));
		int ty = y0 + SIZE + 4;
		for (String line : lines) {
			int w = font.getWidth(line);
			context.drawTextWithShadow(font, line, Math.min(x0 + SIZE - w, context.getScaledWindowWidth() - 4 - w), ty, 0xFFFFFFFF);
			ty += 10;
		}
		// The key.
		// The key, in words.
		context.drawTextWithShadow(font, "§c■§7 new  §a■§7 old (someone was here)", x0, y0 - 20, 0xFFFFFFFF);
		context.drawTextWithShadow(font, "§f·§7 can't tell  §e■§7 trail", x0, y0 - 10, 0xFFFFFFFF);
	}

	/** Old land all around and no fresh land in sight: you can't see where it ends. */
	private static boolean insideOld(ChunkTrails trails, String dim, Vec3d me) {
		int[] n = trails.around(dim, (int) Math.floor(me.x) >> 4, (int) Math.floor(me.z) >> 4, 8);
		return n[0] >= 12 && n[1] == 0;
	}

	/** A thick line in world units (blocks from you). */
	private static void line(DrawContext context, double x1, double z1, double x2, double z2, int thickness, int color) {
		double len = Math.hypot(x2 - x1, z2 - z1);
		int steps = (int) Math.max(1, len / Math.max(4, thickness / 2.0));
		int h = thickness / 2;
		for (int k = 0; k <= steps; k++) {
			double t = (double) k / steps;
			int x = (int) Math.round(x1 + (x2 - x1) * t), z = (int) Math.round(z1 + (z2 - z1) * t);
			context.fill(x - h, z - h, x + h, z + h, color);
		}
	}

	/** An arrowhead at (x, z) pointing along (dx, dz). */
	private static void arrow(DrawContext context, double x, double z, double dx, double dz, int color) {
		double len = Math.hypot(dx, dz);
		if (len < 1) return;
		double ux = dx / len, uz = dz / len;
		for (int k = 0; k < 40; k += 4) {
			double back = k, wide = k * 0.9;
			double bx = x - ux * back, bz = z - uz * back;
			// A row across the arrow at this distance back from the tip.
			double px = -uz, pz = ux;
			for (double s = -wide; s <= wide; s += 4) {
				int fx = (int) Math.round(bx + px * s), fz = (int) Math.round(bz + pz * s);
				context.fill(fx - 3, fz - 3, fx + 3, fz + 3, color);
			}
		}
	}
}
