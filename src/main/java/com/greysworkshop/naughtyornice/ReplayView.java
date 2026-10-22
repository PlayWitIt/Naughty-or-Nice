package com.greysworkshop.naughtyornice;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The replay map of a recording: everyone's position from above, moved along by a time slider (or played back), with
 * each hit drawn as a line from who hit to who was hit, and deaths, totem pops and explosions marked where they
 * happened. Drawn from the recording alone: where players were is only known while they were in view, and a dot
 * fades once its player hasn't been seen for a few seconds. No coordinates are shown, only a scale.
 */
final class ReplayView {
	/** A player's known positions, in time order. */
	private static final class Track {
		final List<long[]> times = new ArrayList<>();
		final List<double[]> where = new ArrayList<>();

		void add(long t, double x, double z) {
			times.add(new long[] {t});
			where.add(new double[] {x, z});
		}

		/** The last index at or before {@code t}, or -1. */
		int at(long t) {
			int lo = 0, hi = times.size() - 1, best = -1;
			while (lo <= hi) {
				int mid = (lo + hi) >>> 1;
				if (times.get(mid)[0] <= t) {
					best = mid;
					lo = mid + 1;
				} else {
					hi = mid - 1;
				}
			}
			return best;
		}
	}

	private static final long FADE_MS = 4000, MARK_MS = 5000, LINE_MS = 1200, TRAIL_MS = 8000;
	private static final int[] SPEEDS = {1, 2, 4, 8, 16, 32};
	private static final int[] COLORS = {0xFF5BC0FF, 0xFFFF9F43, 0xFFB388FF, 0xFF4DD0A1, 0xFFFF6FA8, 0xFFFFD54F, 0xFF80DEEA, 0xFFC5E1A5, 0xFFFF8A65, 0xFFB0BEC5};

	final List<EventLog.Entry> entries;
	final String dimension;
	private final Map<String, Track> tracks = new LinkedHashMap<>();
	private final Map<String, Integer> colors = new HashMap<>();
	private final long start, end;
	private double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
	long t;
	boolean playing;
	int speed = 4;
	private long lastFrame;
	/** Where the slider is on screen, for clicks and drags. */
	int sliderX, sliderY, sliderW;

	ReplayView(List<EventLog.Entry> all) {
		List<EventLog.Entry> sorted = new ArrayList<>(all);
		sorted.sort(Comparator.comparingLong(e -> e.t));
		// The dimension most of it happened in.
		Map<String, Integer> dims = new HashMap<>();
		for (EventLog.Entry e : sorted) {
			if (e.located() && !e.dim.isEmpty()) dims.merge(e.dim, 1, Integer::sum);
		}
		dimension = dims.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
		entries = sorted.stream().filter(e -> e.located() && e.dim.equals(dimension)).toList();
		// Players are whoever had their position logged; a hit on anything else (a mob) isn't drawn as a player.
		java.util.Set<String> players = new java.util.HashSet<>();
		for (EventLog.Entry e : entries) {
			if (e.type == EventLog.Type.POSITION || e.type == EventLog.Type.SWING || e.type == EventLog.Type.TOTEM) players.add(e.actor);
		}
		for (EventLog.Entry e : entries) {
			String who = switch (e.type) {
				case POSITION, SWING, TOTEM, GEAR -> e.actor;
				case HIT, DEATH -> e.target;
				default -> "";
			};
			if (who.isEmpty() || !players.contains(who)) {
				if (e.type != EventLog.Type.EXPLOSION && e.type != EventLog.Type.CRYSTAL_PLACED) continue;
			} else {
				tracks.computeIfAbsent(who, k -> new Track()).add(e.t, e.x, e.z);
				colors.computeIfAbsent(who, k -> COLORS[colors.size() % COLORS.length]);
			}
			minX = Math.min(minX, e.x);
			maxX = Math.max(maxX, e.x);
			minZ = Math.min(minZ, e.z);
			maxZ = Math.max(maxZ, e.z);
		}
		start = entries.isEmpty() ? 0 : entries.get(0).t;
		end = entries.isEmpty() ? 0 : entries.get(entries.size() - 1).t;
		t = start;
	}

	int players() {
		return tracks.size();
	}

	boolean empty() {
		return tracks.isEmpty();
	}

	/** Jumps to just before the first hit between two players. */
	void toFirstHit(EventLog.Entry first) {
		if (first != null) t = Math.max(start, first.t - 3000);
	}

	void seek(double fraction) {
		t = start + (long) ((end - start) * Math.max(0, Math.min(1, fraction)));
	}

	void faster() {
		for (int i = 0; i < SPEEDS.length; i++) {
			if (SPEEDS[i] == speed) {
				speed = SPEEDS[(i + 1) % SPEEDS.length];
				return;
			}
		}
		speed = 1;
	}

	private int color(String who) {
		return colors.getOrDefault(who, 0xFFDDDDDD);
	}

	/** Draws the map at the current time into x..x+w, y..y+h (the slider and buttons are below, in the last 34). */
	void render(DrawContext context, TextRenderer font, int x, int y, int w, int h) {
		long now = System.currentTimeMillis();
		if (playing) {
			t += (now - lastFrame) * speed;
			if (t >= end) {
				t = end;
				playing = false;
			}
		}
		lastFrame = now;
		int mapH = h - 36;
		context.fill(x, y, x + w, y + mapH, 0xFF0E1216);
		// Fit everything that happened, with a margin, same scale both ways.
		double spanX = Math.max(16, maxX - minX), spanZ = Math.max(16, maxZ - minZ);
		double scale = Math.min((w - 40) / spanX, (mapH - 40) / spanZ);
		double cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
		java.util.function.DoubleUnaryOperator px = v -> x + w / 2.0 + (v - cx) * scale;
		java.util.function.DoubleUnaryOperator pz = v -> y + mapH / 2.0 + (v - cz) * scale;
		context.enableScissor(x, y, x + w, y + mapH);

		// Scale bar: a round number of blocks about a sixth of the width.
		double want = w / 6.0 / scale;
		int blocks = niceNumber(want);
		int bar = (int) (blocks * scale);
		context.fill(x + 8, y + mapH - 10, x + 8 + bar, y + mapH - 9, 0xFF8A949E);
		context.drawText(font, blocks + " blocks", x + 8, y + mapH - 20, 0xFF8A949E, false);

		// Trails, then hits as lines, then marks, then the players on top.
		for (Map.Entry<String, Track> en : tracks.entrySet()) {
			Track tr = en.getValue();
			int i = tr.at(t);
			int c = (color(en.getKey()) & 0x00FFFFFF) | 0x50000000;
			for (int j = i; j >= 0 && t - tr.times.get(j)[0] <= TRAIL_MS; j--) {
				double[] p = tr.where.get(j);
				int sx = (int) px.applyAsDouble(p[0]), sz = (int) pz.applyAsDouble(p[1]);
				context.fill(sx, sz, sx + 2, sz + 2, c);
			}
		}
		List<String> ticker = new ArrayList<>();
		for (EventLog.Entry e : entries) {
			if (e.t > t) break;
			long age = t - e.t;
			if (age > MARK_MS) continue;
			int sx = (int) px.applyAsDouble(e.x), sz = (int) pz.applyAsDouble(e.z);
			switch (e.type) {
				case HIT -> {
					if (age > LINE_MS || e.actor.isEmpty()) break;
					Track from = tracks.get(e.actor);
					int fi = from == null ? -1 : from.at(e.t);
					if (fi >= 0 && e.t - from.times.get(fi)[0] <= FADE_MS) {
						double[] p = from.where.get(fi);
						line(context, (int) px.applyAsDouble(p[0]), (int) pz.applyAsDouble(p[1]), sx, sz, 0xFFFF5A4A);
					}
					ticker.add(e.actor + " hit " + e.target);
				}
				case DEATH -> {
					context.drawText(font, "✖", sx - 3, sz - 4, 0xFFFF5A4A, true);
					ticker.add(e.target + " died" + (e.actor.isEmpty() ? "" : " (" + e.actor + ")"));
				}
				case TOTEM -> {
					if (age < 2500) context.drawText(font, "◎", sx - 3, sz - 12, 0xFFFFD814, true);
					ticker.add(e.actor + " popped a totem");
				}
				case EXPLOSION -> {
					if (age < 2000) {
						int r = (int) Math.max(3, 4 * scale);
						context.fill(sx - r, sz - r, sx + r, sz + r, 0x60FF9F43);
					}
					ticker.add("Explosion");
				}
				case CRYSTAL_PLACED -> {
					if (age < 2000) context.fill(sx - 2, sz - 2, sx + 2, sz + 2, 0xFFB388FF);
				}
				default -> {}
			}
		}
		for (Map.Entry<String, Track> en : tracks.entrySet()) {
			Track tr = en.getValue();
			int i = tr.at(t);
			if (i < 0) continue;
			long seen = t - tr.times.get(i)[0];
			if (seen > 30_000) continue;
			double[] p = tr.where.get(i);
			int sx = (int) px.applyAsDouble(p[0]), sz = (int) pz.applyAsDouble(p[1]);
			boolean fresh = seen <= FADE_MS;
			int c = fresh ? color(en.getKey()) : 0xFF5E6873;
			context.fill(sx - 3, sz - 3, sx + 3, sz + 3, 0xFF000000);
			context.fill(sx - 2, sz - 2, sx + 2, sz + 2, c);
			String label = en.getKey() + (fresh ? "" : " (out of view)");
			context.drawText(font, label, sx - font.getWidth(label) / 2, sz - 13, c, true);
		}
		context.disableScissor();

		// What's happening now, top left.
		int ty = y + 6;
		for (int i = Math.max(0, ticker.size() - 5); i < ticker.size(); i++) {
			context.drawText(font, ticker.get(i), x + 6, ty, 0xFFE7E9EC, true);
			ty += 10;
		}

		// The slider: the whole recording, a tick for each hit, the playhead.
		sliderX = x;
		sliderY = y + mapH + 8;
		sliderW = w;
		context.fill(x, sliderY + 3, x + w, sliderY + 5, 0xFF3A424C);
		for (EventLog.Entry e : entries) {
			if (e.type != EventLog.Type.HIT && e.type != EventLog.Type.DEATH) continue;
			int hx = x + (int) ((e.t - start) * (double) w / Math.max(1, end - start));
			context.fill(hx, sliderY + (e.type == EventLog.Type.DEATH ? 0 : 2), hx + 1, sliderY + 6, e.type == EventLog.Type.DEATH ? 0xFFFF5A4A : 0xFFAA4A40);
		}
		int head = x + (int) ((t - start) * (double) w / Math.max(1, end - start));
		context.fill(head - 1, sliderY - 2, head + 2, sliderY + 10, 0xFFFFFFFF);
		String clock = EventScreen.clock(t) + String.format(Locale.ROOT, "  (+%d:%02d)", (t - start) / 60000, (t - start) / 1000 % 60);
		context.drawText(font, clock, x + w - font.getWidth(clock), sliderY + 14, 0xFFAAAAAA, false);
	}

	/** A line of small squares from one point to another. */
	private static void line(DrawContext context, int x0, int y0, int x1, int y1, int color) {
		int steps = Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) / 2);
		for (int i = 0; i <= steps; i++) {
			int lx = x0 + (x1 - x0) * i / steps, ly = y0 + (y1 - y0) * i / steps;
			context.fill(lx, ly, lx + 2, ly + 2, color);
		}
	}

	private static int niceNumber(double v) {
		int[] nice = {1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 5000, 10000};
		int best = 1;
		for (int n : nice) {
			if (n <= v) best = n;
		}
		return best;
	}
}
