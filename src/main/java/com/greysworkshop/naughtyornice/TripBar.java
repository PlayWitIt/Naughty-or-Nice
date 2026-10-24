package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bar at the top of the screen whenever Baritone is taking you somewhere (a trip to a place, a highway roam, or
 * anything you set it walking to): where to, how far is left (along the highways when the route is by them), about
 * how long that is at the speed you're closing in, how fast you're going, and how. Off in Settings (Trip bar).
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
		String line = line(client, w);
		int width = client.textRenderer.getWidth(line);
		int x = context.getScaledWindowWidth() / 2 - width / 2;
		context.fill(x - 4, 3, x + width + 4, 15, 0xA0000000);
		context.drawTextWithShadow(client.textRenderer, line, x, 5, 0xFFFFE08A);
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
