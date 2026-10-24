package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import net.minecraft.client.font.TextRenderer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bar at the top of the screen whenever Baritone is taking you somewhere (a trip to a place, a highway roam, or
 * anything you set it walking to): where to, how far is left (along the highways when the route is by them), about
 * how long that is at the speed you're closing in, how fast you're going, and how. Small, like the coordinates, with
 * where to on the first row and the rest wrapped under it so it never runs off the screen. Off in Settings (Trip bar).
 */
public final class TripBar {
	private TripBar() {}

	/** Where you're being taken: a name for it, where it ends, the blocks left, and whether it's by elytra. */
	record Where(String name, int x, int z, double left, boolean flying) {}

	/** Once a second: (time, blocks left, x, z), for how fast you're closing in and how fast you're moving. */
	private static final ArrayDeque<double[]> samples = new ArrayDeque<>();
	private static long lastSample;
	private static String lastName = "";

	private static final Pattern XZ = Pattern.compile("x=(-?\\d+)(?:,\\s*y=-?\\d+)?,\\s*z=(-?\\d+)");

	/** What the bar is about now, or null when nothing's going anywhere. */
	static Where where(MinecraftClient client) {
		double px = client.player.getX(), pz = client.player.getZ();
		String trip = SmartTravel.tripName();
		if (trip != null) {
			int[] t = SmartTravel.tripTarget();
			return new Where(trip, t[0], t[1], SmartTravel.remaining(px, pz), SmartTravel.flying());
		}
		if (HighwayRoam.active()) {
			int[] t = HighwayRoam.targetXZ();
			HighwayRoam.Axis a = HighwayRoam.axis();
			String name = NNConfig.INSTANCE.roamToCoords ? "Highway roam" : "Roaming the " + (a == null ? "" : a.name() + " ") + "highway";
			return new Where(name, t[0], t[1], HighwayRoam.remaining(px, pz), HighwayRoam.flying());
		}
		// Anything else Baritone is walking to: its goal, if it has a place in it.
		if (!BaritoneBridge.isPathing()) return null;
		int[] goal = goalXZ(BaritoneBridge.goal());
		if (goal == null) return null;
		return new Where("Baritone", goal[0], goal[1], Math.hypot(goal[0] - px, goal[1] - pz), false);
	}

	/** The x and z in Baritone's goal ("GoalBlock{x=1,y=2,z=3}", "GoalXZ{x=1,z=3}"), or null. */
	static int[] goalXZ(String goal) {
		if (goal == null) return null;
		Matcher m = XZ.matcher(goal);
		return m.find() ? new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
	}

	public static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!NNConfig.INSTANCE.tripBar || client.player == null || client.world == null || client.options.hudHidden) return;
		Where w = where(client);
		if (w == null) {
			samples.clear();
			return;
		}
		float s = CoordsHud.scale(client);
		int screen = context.getScaledWindowWidth();
		List<String> rows = rows(client.textRenderer, line(client, w), (int) ((screen - 16) / s));
		int lh = (int) Math.ceil(10 * s);
		int widest = 0;
		for (String r : rows) widest = Math.max(widest, (int) Math.ceil(client.textRenderer.getWidth(r) * s));
		int x0 = screen / 2 - widest / 2;
		context.fill(x0 - 4, 2, x0 + widest + 4, 4 + rows.size() * lh, 0xA0000000);
		int y = 4;
		for (int i = 0; i < rows.size(); i++) {
			String r = rows.get(i);
			int rw = (int) Math.ceil(client.textRenderer.getWidth(r) * s);
			var m = context.getMatrices();
			m.pushMatrix();
			m.translate(screen / 2f - rw / 2f, y);
			m.scale(s, s);
			context.drawTextWithShadow(client.textRenderer, r, 0, 0, i == 0 ? 0xFFFFE08A : 0xFFE8E8E8);
			m.popMatrix();
			y += lh;
		}
	}

	/**
	 * The bar's text as rows that fit {@code max} wide: where to on the first, then the rest, broken between its parts
	 * (never in the middle of one) onto as many rows as it takes.
	 */
	static List<String> rows(TextRenderer font, String line, int max) {
		String[] parts = line.split(" · ");
		List<String> out = new ArrayList<>();
		out.add(parts[0]);
		StringBuilder row = new StringBuilder();
		for (int i = 1; i < parts.length; i++) {
			String next = row.length() == 0 ? parts[i] : row + " · " + parts[i];
			if (row.length() > 0 && font.getWidth(next) > max) {
				out.add(row.toString());
				row = new StringBuilder(parts[i]);
			} else {
				row = new StringBuilder(next);
			}
		}
		if (row.length() > 0) out.add(row.toString());
		return out;
	}

	static String line(MinecraftClient client, Where w) {
		long now = System.currentTimeMillis();
		if (!w.name().equals(lastName)) {
			samples.clear();
			lastName = w.name();
		}
		if (now - lastSample >= 1000) {
			lastSample = now;
			samples.addLast(new double[] {now, w.left(), client.player.getX(), client.player.getZ()});
			while (samples.size() > 20) samples.removeFirst();
		}
		String to = w.name() + (ModsPanel.streamSafe() ? "" : String.format(Locale.ROOT, " (%d, %d)", w.x(), w.z()));
		double closing = 0, moving = 0;
		if (samples.size() >= 4) {
			double[] first = samples.peekFirst(), last = samples.peekLast();
			double secs = (last[0] - first[0]) / 1000;
			if (secs > 0) {
				closing = Math.max(0, (first[1] - last[1]) / secs);
				moving = Math.hypot(last[2] - first[2], last[3] - first[3]) / secs;
			}
		}
		String eta = closing < 0.5 ? "" : " · about " + ShopScreen.duration((int) (w.left() / closing)) + " left";
		String speed = samples.size() < 4 ? "" : String.format(Locale.ROOT, " · %.1f blocks/s", moving);
		String how = w.flying() ? "flying" : HighwayRoam.active() && HighwayRoam.paused() ? "paused" : BaritoneBridge.isPathing() ? "walking" : "working out a path";
		return String.format(Locale.ROOT, "▶ %s · %,d blocks%s%s · %s · %s to stop", to, (int) w.left(), eta, speed, how,
				NaughtyOrNiceClient.INSTANCE.stopKeyName());
	}
}
