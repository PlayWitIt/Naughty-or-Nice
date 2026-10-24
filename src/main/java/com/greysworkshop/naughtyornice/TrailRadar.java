package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The chunk-trail radar: a map on the right of the screen that turns with you, so ahead is always up. The land is
 * drawn as it is, like a map (each spot's top block in its map colour, shaded by height, from the chunks you have
 * loaded). Old land (someone was there) is tinted green, new land is left as it is, and trails are as thick yellow lines with an arrow on the end that
 * leads away from spawn. It reaches as far as your render distance. Under it, in small words: where the nearest trail
 * is, which way it runs, and how to turn to follow it. Off until you turn it on (J, or Settings); three sizes.
 */
public final class TrailRadar {
	/** Its size on screen for Small, Medium and Large. */
	private static final int[] SIZES = {80, 112, 150};
	private static final int OLD_LAND = 0x70000000 | ChunkOverlay.OLD,
			FRESH_FAR = 0x70000000 | ChunkOverlay.FRESH, OLD_FAR = 0x80000000 | ChunkOverlay.OLD, UNKNOWN_DOT = 0x60FFFFFF,
			TRAIL = 0xFF000000 | ChunkOverlay.TRAIL, BACK = 0xC0101418, EDGE = 0xFF3A4048;
	/** Blocks across each square of land drawn (a chunk is 2 by 2 of them). */
	private static final int CELL = 8;
	/** A chunk's land colours (2 by 2, ARGB), and when they were read: kept a while, read again as you pass. */
	private record Land(int[] colors, long at) {}

	private static final java.util.Map<Long, Land> land = new java.util.HashMap<>();
	private static String landDim = "";

	/** The last analysis, refreshed by the watcher every two seconds. */
	static List<int[]> patches = new ArrayList<>();

	private TrailRadar() {}

	/** For the self-test: how many chunks' land has been read for the map. */
	static int landRead() {
		return land.size();
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
		// As far as the land is loaded, a little past it.
		int span = Math.max(6, Math.min(32, client.options.getClampedViewDistance() + 1));
		if (!dim.equals(landDim)) {
			land.clear();
			landDim = dim;
		}
		int x0 = context.getScaledWindowWidth() - size - 6;
		int y0 = context.getScaledWindowHeight() / 2 - size / 2 - 20;
		int cx = x0 + size / 2, cy = y0 + size / 2;

		context.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, EDGE);
		context.fill(x0, y0, x0 + size, y0 + size, BACK);
		context.enableScissor(x0, y0, x0 + size, y0 + size);
		Matrix3x2fStack m = context.getMatrices();
		m.pushMatrix();
		m.translate(cx, cy);
		m.rotate((float) Math.toRadians(-(yaw + 180)));
		float scale = size / 2f / (span * 16f);
		m.scale(scale, scale);
		// Land, chunk by chunk (a little past the corners, since the map turns).
		int pcx = (int) Math.floor(me.x) >> 4, pcz = (int) Math.floor(me.z) >> 4;
		int reach = (int) Math.ceil(span * 1.45);
		// What's been read around you, so it's clear the radar is working even with no trail in sight.
		int fresh = 0, old = 0, unclear = 0;
		long now = System.currentTimeMillis();
		int reads = 0;
		for (int i = -reach; i <= reach; i++) {
			for (int j = -reach; j <= reach; j++) {
				int chx = pcx + i, chz = pcz + j;
				byte s = trails.state(dim, chx, chz);
				boolean loaded = client.world.getChunkManager().isChunkLoaded(chx, chz);
				if (Math.abs(i) <= span && Math.abs(j) <= span) {
					if (s == ChunkTrails.NEW) fresh++;
					else if (s == ChunkTrails.OLD) old++;
					else if (loaded) unclear++;
				}
				int bx = (int) Math.round(chx * 16 - me.x), bz = (int) Math.round(chz * 16 - me.z);
				// The land itself, where it's loaded; read a few chunks a frame so it never stutters.
				Land l = land.get(ChunkPos.toLong(chx, chz));
				if (loaded && (l == null || now - l.at() > 15_000) && reads < 24) {
					reads++;
					l = read(client, chx, chz, me.y, now);
					land.put(ChunkPos.toLong(chx, chz), l);
				}
				if (loaded && l != null) {
					for (int k = 0; k < 4; k++) {
						int ox = (k & 1) * CELL, oz = (k >> 1) * CELL;
						context.fill(bx + ox, bz + oz, bx + ox + CELL, bz + oz + CELL, l.colors()[k]);
					}
				}
				if (s == 0) {
					// Loaded, but nothing in it to tell by: dotted, so it's clearly "looked, can't tell", not "never seen".
					if (loaded) context.fill(bx + 6, bz + 6, bx + 10, bz + 10, UNKNOWN_DOT);
					continue;
				}
				// Loaded land keeps its own colours where it's new (most of it, exploring), and old land is tinted green so it stands
				// out; past what's loaded there's no land to show, so both are drawn in their colours, as remembered.
				if (loaded && s == ChunkTrails.NEW) continue;
				context.fill(bx, bz, bx + 16, bz + 16, s == ChunkTrails.NEW ? FRESH_FAR : loaded ? OLD_LAND : OLD_FAR);
			}
		}
		if (land.size() > 6000) land.entrySet().removeIf(e -> now - e.getValue().at() > 60_000);
		// Trails: thick lines, an arrow on the end away from spawn.
		for (ChunkTrails.Trail t : trails.trails) {
			if (!t.dimension.equals(dim)) continue;
			if (ChunkTrails.segmentDistance(t, me.x, me.z) > span * 16 * 2) continue;
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
		double half = size / 2.0 - 5;
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
		// In small words, under the map; the key above it.
		float ts = CoordsHud.scale(client);
		int lh = (int) Math.ceil(10 * ts);
		int ty = y0 + size + 4;
		for (String line : lines) {
			small(context, font, line, x0 + size, ty, ts, context.getScaledWindowWidth() - 4);
			ty += lh;
		}
		small(context, font, "§a■§7 old (someone was here)  §e■§7 trail  §7rest: new", x0 + size, y0 - lh - 1, ts, context.getScaledWindowWidth() - 4);
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

	/**
	 * A chunk's land as a map shows it: for each 8 by 8 square, the top block's map colour, lighter where it's higher
	 * than the square north of it and darker where lower. In the nether, whose top is the bedrock roof, the floor
	 * nearest your height instead.
	 */
	private static Land read(MinecraftClient client, int chx, int chz, double myY, long now) {
		var world = client.world;
		var chunk = world.getChunk(chx, chz);
		boolean roof = world.getDimension().hasCeiling();
		int[] colors = new int[4];
		int[] heights = new int[4];
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int k = 0; k < 4; k++) {
			int lx = (k & 1) * CELL + CELL / 2, lz = (k >> 1) * CELL + CELL / 2;
			int wx = chx * 16 + lx, wz = chz * 16 + lz;
			int y;
			if (roof) {
				y = Integer.MIN_VALUE;
				int top = Math.min(world.getTopYInclusive() - 1, (int) myY + 24);
				for (int yy = top; yy > world.getBottomY(); yy--) {
					pos.set(wx, yy, wz);
					if (!chunk.getBlockState(pos).isAir()) {
						pos.set(wx, yy + 1, wz);
						if (chunk.getBlockState(pos).isAir()) {
							y = yy;
							break;
						}
					}
				}
				if (y == Integer.MIN_VALUE) y = (int) myY;
			} else {
				y = chunk.sampleHeightmap(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, lx, lz);
			}
			pos.set(wx, y, wz);
			var state = chunk.getBlockState(pos);
			int rgb = state.isAir() ? 0x202428 : state.getMapColor(world, pos).color;
			heights[k] = y;
			colors[k] = rgb;
		}
		for (int k = 0; k < 4; k++) {
			// Shade by the square to the north (the one above it in the chunk, or itself on the top row).
			int north = k >= 2 ? heights[k - 2] : heights[k];
			float f = heights[k] > north ? 1.12f : heights[k] < north ? 0.82f : 1f;
			colors[k] = 0xFF000000 | shade(colors[k], f);
		}
		return new Land(colors, now);
	}

	private static int shade(int rgb, float f) {
		int r = Math.min(255, (int) ((rgb >> 16 & 0xFF) * f)), g = Math.min(255, (int) ((rgb >> 8 & 0xFF) * f)), b = Math.min(255, (int) ((rgb & 0xFF) * f));
		return r << 16 | g << 8 | b;
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
