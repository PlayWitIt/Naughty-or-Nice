package com.greysworkshop.naughtyornice;

import java.util.ArrayList;
import java.util.List;

/**
 * The way to somewhere in the nether by its highways, when that's quicker than going straight there. The highways run
 * from 0, 0 along the eight ways in {@link HighwayRoam#AXES}. A route gets onto the best one (or two, through spawn),
 * rides it to the point nearest the place, and goes the rest of the way off it. Off-highway blocks count {@link #OFF}
 * times as much as highway ones (walls, digging, lava, mobs), so a highway wins whenever it's the sensible way.
 */
public final class HighwayRoute {
	/** How much slower a block off the highway is than one on it. */
	static final double OFF = 4;
	/** Closer than this to a highway's middle is on it. */
	static final double ON = 8;
	/** Places this close are just gone to. */
	static final double NEAR = 300;

	/** One part of a route: off-road to (x, z), or along a highway from its distance {@code from} to {@code to}. */
	public record Leg(HighwayRoam.Axis axis, double from, double to, int x, int z) {
		public boolean onHighway() {
			return axis != null;
		}
	}

	private HighwayRoute() {}

	/** Unit vector of a highway. */
	static double[] unit(HighwayRoam.Axis a) {
		double len = Math.hypot(a.dx(), a.dz());
		return new double[] {a.dx() / len, a.dz() / len};
	}

	/** How far along a highway the point nearest (x, z) is (0 at spawn, never behind it). */
	static double along(HighwayRoam.Axis a, double x, double z) {
		double[] u = unit(a);
		return Math.max(0, x * u[0] + z * u[1]);
	}

	/** The block in the middle of a highway at that distance along it. */
	static int[] point(HighwayRoam.Axis a, double t) {
		double[] u = unit(a);
		if (a.dx() != 0 && a.dz() != 0) {
			// Diagonals are where |x| == |z|.
			long k = Math.round(t * u[0] * a.dx());
			return new int[] {(int) (a.dx() * k), (int) (a.dz() * k)};
		}
		return new int[] {(int) Math.round(t * u[0]), (int) Math.round(t * u[1])};
	}

	static double off(HighwayRoam.Axis a, double x, double z) {
		int[] p = point(a, along(a, x, z));
		return Math.hypot(x - p[0], z - p[1]);
	}

	/**
	 * The legs from (px, pz) to (dx, dz) by highway, or an empty list when going straight there is as good: it's near,
	 * or no highway helps. Every leg ends where the next begins; the last ends at the place.
	 */
	public static List<Leg> plan(double px, double pz, int dx, int dz) {
		double straight = Math.hypot(dx - px, dz - pz);
		if (straight < NEAR) return List.of();
		double best = OFF * straight;
		HighwayRoam.Axis in = null, out = null;
		for (HighwayRoam.Axis a : HighwayRoam.AXES) {
			for (HighwayRoam.Axis b : HighwayRoam.AXES) {
				double tA = along(a, px, pz), tB = along(b, dx, dz);
				// The same highway: from where you get on to where you get off; two: in to spawn, then out again.
				double ride = a == b ? Math.abs(tB - tA) : tA + tB;
				double cost = OFF * off(a, px, pz) + ride + OFF * off(b, dx, dz);
				if (cost < best - 1) {
					best = cost;
					in = a;
					out = b;
				}
			}
		}
		if (in == null) return List.of();
		List<Leg> legs = new ArrayList<>();
		double tIn = along(in, px, pz), tOut = along(out, dx, dz);
		int[] on = point(in, tIn);
		if (off(in, px, pz) > ON) legs.add(new Leg(null, 0, 0, on[0], on[1]));
		if (in == out) {
			int[] end = point(in, tOut);
			legs.add(new Leg(in, tIn, tOut, end[0], end[1]));
		} else {
			legs.add(new Leg(in, tIn, 0, 0, 0));
			int[] end = point(out, tOut);
			legs.add(new Leg(out, 0, tOut, end[0], end[1]));
		}
		legs.add(new Leg(null, 0, 0, dx, dz));
		return legs;
	}

	/** The route in words: "along the -X-Z highway for 12,300 blocks, then 840 off it". */
	public static String describe(List<Leg> legs, double px, double pz) {
		StringBuilder sb = new StringBuilder();
		double x = px, z = pz;
		for (Leg leg : legs) {
			if (sb.length() > 0) sb.append(", then ");
			int blocks = (int) (leg.onHighway() ? Math.abs(leg.to() - leg.from()) : Math.hypot(leg.x() - x, leg.z() - z));
			if (leg.onHighway()) sb.append("along the ").append(leg.axis().name()).append(" highway for ").append(String.format(java.util.Locale.ROOT, "%,d", blocks));
			else sb.append(String.format(java.util.Locale.ROOT, "%,d", blocks)).append(sb.length() == 0 ? " to get on the highway" : " off it");
			x = leg.x();
			z = leg.z();
		}
		return sb.toString();
	}

	/** Blocks still to go on a route from (px, pz), on leg {@code leg}: the rest of this leg, then every leg after it. */
	public static double remaining(List<Leg> legs, int leg, double px, double pz) {
		double sum = 0, x = px, z = pz;
		for (int i = leg; i < legs.size(); i++) {
			Leg l = legs.get(i);
			if (l.onHighway()) {
				double from = i == leg ? along(l.axis(), x, z) : l.from();
				sum += Math.abs(l.to() - from);
			} else {
				sum += Math.hypot(l.x() - x, l.z() - z);
			}
			x = l.x();
			z = l.z();
		}
		return sum;
	}
}
