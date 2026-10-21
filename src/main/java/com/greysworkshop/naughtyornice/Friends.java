package com.greysworkshop.naughtyornice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Friend circles: the kinds of friends you have ("Close friends", "Base mates", "Allies"...). Each friend is in
 * one. A circle can be trusted at your bases (Base Insurance won't alert about them) and can ping you when one of
 * them joins. Circles are the same on every server; who's in them is kept per server with the lists.
 */
public final class Friends {
	private Friends() {}

	/** Longest circle name. */
	public static final int NAME_MAX = 20;
	/** Most circles. */
	public static final int MAX = 8;

	public static final class Circle {
		/** Stays the same when renamed, so friends stay in it. */
		public String id = "";
		public String name = "";
		/** Index into the group colours. */
		public int color;
		/** Base Insurance treats them as friends: no alerts about them, not named as suspects. */
		public boolean trusted = true;
		/** Ping when one of them joins the server or comes into view. */
		public boolean joinAlert;

		public Circle() {}

		Circle(String id, String name, int color, boolean trusted, boolean joinAlert) {
			this.id = id;
			this.name = name;
			this.color = color;
			this.trusted = trusted;
			this.joinAlert = joinAlert;
		}
	}

	public static List<Circle> defaultCircles() {
		List<Circle> list = new ArrayList<>();
		list.add(new Circle("close", "Close friends", 2, true, true));
		list.add(new Circle("base", "Base mates", 0, true, false));
		list.add(new Circle("allies", "Allies", 3, false, false));
		return list;
	}

	public static List<Circle> circles() {
		return NNConfig.INSTANCE.circles;
	}

	public static Circle byId(String id) {
		for (Circle c : circles()) {
			if (c.id.equals(id)) return c;
		}
		return null;
	}

	public static Circle byName(String name) {
		for (Circle c : circles()) {
			if (c.name.equalsIgnoreCase(name.strip())) return c;
		}
		return null;
	}

	/** The circle a friend is in (the first circle if theirs is gone), or null if they aren't a friend. */
	public static Circle of(Profile p) {
		if (p == null || p.verdict != Profile.Verdict.FRIEND) return null;
		Circle c = byId(p.circle);
		return c != null ? c : circles().get(0);
	}

	/** A friend in a trusted circle. */
	public static boolean trusted(Profile p) {
		Circle c = of(p);
		return c != null && c.trusted;
	}

	public static int color(Profile p) {
		Circle c = of(p);
		return c == null ? 0 : c.color;
	}

	/** "Close friends", or "Friends" for someone who isn't in a circle. */
	public static String circleName(Profile p) {
		Circle c = of(p);
		return c == null ? "Friends" : c.name;
	}

	/** Makes a new circle; returns it, or null if the name is empty, taken, or there are too many. */
	public static Circle add(String name) {
		String clean = clean(name);
		if (clean.isEmpty() || byName(clean) != null || circles().size() >= MAX) return null;
		Circle c = new Circle(UUID.randomUUID().toString().substring(0, 8), clean, circles().size() % Ledger.GROUP_COLORS, true, false);
		circles().add(c);
		NNConfig.save();
		return c;
	}

	public static boolean rename(Circle c, String name) {
		String clean = clean(name);
		if (clean.isEmpty()) return false;
		Circle other = byName(clean);
		if (other != null && other != c) return false;
		c.name = clean;
		NNConfig.save();
		return true;
	}

	/** Removes a circle (never the last one); its friends move to the first circle. */
	public static boolean remove(Circle c, Ledger ledger) {
		if (circles().size() <= 1 || !circles().remove(c)) return false;
		for (Profile p : ledger.all()) {
			if (p.verdict == Profile.Verdict.FRIEND && p.circle.equals(c.id)) {
				p.circle = circles().get(0).id;
				ledger.markDirty();
			}
		}
		NNConfig.save();
		resync(ledger);
		return true;
	}

	/** Moves a circle up or down the list (the top one is where new friends go). */
	public static void move(Circle c, int by) {
		List<Circle> list = circles();
		int at = list.indexOf(c);
		int to = at + by;
		if (at < 0 || to < 0 || to >= list.size()) return;
		list.remove(at);
		list.add(to, c);
		NNConfig.save();
	}

	public static List<Profile> members(Ledger ledger, Circle c) {
		List<Profile> list = new ArrayList<>();
		for (Profile p : ledger.all()) {
			if (of(p) == c) list.add(p);
		}
		list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
		return list;
	}

	/** After a circle's trust changes: puts everyone right in Base Insurance's friends. */
	public static void resync(Ledger ledger) {
		for (Profile p : ledger.all()) {
			if (p.verdict == Profile.Verdict.FRIEND || p.verdict == Profile.Verdict.NICE || p.sharedFriend) BaseInsuranceBridge.syncFriend(p);
		}
	}

	static String clean(String name) {
		String s = name == null ? "" : name.strip().replaceAll("\\s+", " ");
		return s.length() > NAME_MAX ? s.substring(0, NAME_MAX).strip() : s;
	}

	/** Lower-case check for search. */
	public static boolean circleContains(Profile p, String q) {
		Circle c = of(p);
		return c != null && c.name.toLowerCase(Locale.ROOT).contains(q);
	}
}
