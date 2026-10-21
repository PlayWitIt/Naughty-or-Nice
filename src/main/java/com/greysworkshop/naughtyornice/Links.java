package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who's seen with whom, to spot crews and alts: players who log on or off together, spend time near each other, or
 * take turns (one logs off and the other logs on straight after, as alts do). Kept per server next to the lists.
 *
 * <p>On a busy server people log on seconds apart and stand near each other at spawn all the time, so raw counts
 * mean little. A pair only counts as linked when what they share is a big part of what the less active of the two
 * does: most of their logins, or a good part of the time they're seen. Crowds and mass logins (a restart) aren't
 * counted at all.
 */
public final class Links {
	private static final Gson GSON = new GsonBuilder().create();
	/** Joins (or leaves) this close together count as together... */
	private static final long TOGETHER_MILLIS = 10_000;
	/** ...unless others came or went in that time too: then it's just a busy moment. */
	private static final int BUSY = 1;
	/** One logging off and another logging on this soon after looks like switching accounts. */
	private static final long SWAP_MILLIS = 15_000;
	/** Players this close count as near each other. */
	public static final double NEAR_BLOCKS = 24;
	/** More players than this around either of a pair is a crowd (spawn, a highway): being near means nothing there. */
	public static final int CROWD = 4;
	/** Pairs kept at most; the weakest and oldest go first. */
	private static final int MAX_PAIRS = 20_000;
	/** Saved data from before this way of counting is set aside, not mixed in. */
	private static final int FORMAT = 2;

	/** What's been seen of one player, to judge their pairs against. */
	public static final class Stats {
		public int joins;
		public int leaves;
		/** Seconds they've been in sight. */
		public int secondsSeen;
	}

	/** What's been seen of one pair. */
	public static final class Link {
		public UUID a, b;
		public int joins;
		public int leaves;
		public int swaps;
		public int secondsNear;
		/** Minutes both were online at once (watched for pairs that took turns, to rule out alts). */
		public int minutesBothOn;
		public long last;

		public UUID other(UUID id) {
			return id.equals(a) ? b : a;
		}
	}

	private record Event(UUID id, long time) {}

	private final Map<String, Link> pairs = new HashMap<>();
	private final Map<UUID, Stats> players = new HashMap<>();
	private final Deque<Event> joins = new ArrayDeque<>();
	private final Deque<Event> leaves = new ArrayDeque<>();
	private boolean dirty;
	private int version;

	private static String key(UUID a, UUID b) {
		return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
	}

	private Link link(UUID a, UUID b) {
		return pairs.computeIfAbsent(key(a, b), k -> {
			Link link = new Link();
			link.a = a.compareTo(b) < 0 ? a : b;
			link.b = a.compareTo(b) < 0 ? b : a;
			return link;
		});
	}

	public Link get(UUID a, UUID b) {
		return pairs.get(key(a, b));
	}

	public Stats stats(UUID id) {
		return players.computeIfAbsent(id, k -> new Stats());
	}

	private Link changed(Link link, long now) {
		link.last = now;
		dirty = true;
		version++;
		return link;
	}

	/** Goes up whenever a link changes, so the screen can redo what it shows. */
	public int version() {
		return version;
	}

	// ---- Recording ----

	public void joined(UUID id, long now) {
		trim(joins, now);
		trim(leaves, now);
		stats(id).joins++;
		dirty = true;
		List<Event> recent = joins.stream().filter(e -> now - e.time <= TOGETHER_MILLIS && !e.id.equals(id)).toList();
		if (!recent.isEmpty() && recent.size() <= BUSY) {
			for (Event e : recent) changed(link(id, e.id), now).joins++;
		}
		// Someone left just before, and nobody else came or went: maybe the same person on another account.
		List<Event> recentLeaves = leaves.stream().filter(e -> now - e.time <= SWAP_MILLIS && !e.id.equals(id)).toList();
		if (recentLeaves.size() == 1 && joins.stream().noneMatch(e -> now - e.time <= SWAP_MILLIS)) {
			changed(link(id, recentLeaves.get(0).id), now).swaps++;
		}
		joins.addLast(new Event(id, now));
	}

	public void left(UUID id, long now) {
		trim(leaves, now);
		stats(id).leaves++;
		dirty = true;
		List<Event> recent = leaves.stream().filter(e -> now - e.time <= TOGETHER_MILLIS && !e.id.equals(id)).toList();
		if (!recent.isEmpty() && recent.size() <= BUSY) {
			for (Event e : recent) changed(link(id, e.id), now).leaves++;
		}
		leaves.addLast(new Event(id, now));
	}

	/** Called once a second for each player in sight. */
	public void seen(UUID id) {
		stats(id).secondsSeen++;
		dirty = true;
	}

	/** Called once a second with two players near each other, neither of them in a crowd. */
	public void near(UUID a, UUID b, long now) {
		Link link = link(a, b);
		link.secondsNear++;
		link.last = now;
		dirty = true;
		// Only redo the screen once a minute for this; it changes every second.
		if (link.secondsNear % 60 == 0) version++;
	}

	/** Once a minute, with who's online: pairs that took turns but are both on now aren't one person. */
	public void minute(Set<UUID> online) {
		for (Link link : pairs.values()) {
			if (link.swaps > 0 && online.contains(link.a) && online.contains(link.b)) {
				link.minutesBothOn++;
				dirty = true;
			}
		}
	}

	private static void trim(Deque<Event> events, long now) {
		while (!events.isEmpty() && now - events.peekFirst().time > Math.max(TOGETHER_MILLIS, SWAP_MILLIS)) events.removeFirst();
	}

	// ---- Judging ----

	/** Of the less active player's logins and logouts, the share they made with the other one (0 to 1). */
	public double loginShare(Link link) {
		int together = link.joins + link.leaves;
		if (together == 0) return 0;
		Stats sa = players.get(link.a), sb = players.get(link.b);
		int fewest = Math.min(sa == null ? 0 : sa.joins + sa.leaves, sb == null ? 0 : sb.joins + sb.leaves);
		return Math.min(1, together / (double) Math.max(fewest, together));
	}

	/** Of the time the less-seen player was in sight, the share they spent near the other (0 to 1). */
	public double nearShare(Link link) {
		if (link.secondsNear == 0) return 0;
		Stats sa = players.get(link.a), sb = players.get(link.b);
		int fewest = Math.min(sa == null ? 0 : sa.secondsSeen, sb == null ? 0 : sb.secondsSeen);
		return Math.min(1, link.secondsNear / (double) Math.max(fewest, link.secondsNear));
	}

	/** Took turns over and over and were never on at the same time or seen together: probably one person. */
	public boolean looksLikeAlt(Link link) {
		if (link.swaps < 3 || link.minutesBothOn > 0 || link.secondsNear > 0 || link.joins + link.leaves > 0) return false;
		Stats sa = players.get(link.a), sb = players.get(link.b);
		int fewest = Math.min(sa == null ? 0 : sa.joins, sb == null ? 0 : sb.joins);
		return link.swaps >= Math.max(3, fewest / 2);
	}

	/**
	 * Whether a pair really goes together: they logged on or off together at least 3 times and that's half or more
	 * of what the less active one did, or they spent 5 minutes or more near each other (away from crowds) and
	 * that's a quarter or more of the time the less-seen one was in sight; or they look like one person's accounts.
	 */
	public boolean strong(Link link) {
		boolean logins = link.joins + link.leaves >= 3 && loginShare(link) >= 0.5;
		boolean near = link.secondsNear >= 300 && nearShare(link) >= 0.25;
		return logins || near || looksLikeAlt(link);
	}

	/** How sure, 0 to 100, for putting the surest first. */
	public int confidence(Link link) {
		double c = Math.max(loginShare(link) * Math.min(1, (link.joins + link.leaves) / 6.0), nearShare(link) * Math.min(1, link.secondsNear / 1800.0));
		if (looksLikeAlt(link)) c = Math.max(c, 0.6);
		return (int) Math.round(c * 100);
	}

	/** "Logged on or off together 4 of 5 times · near each other 12 min (40% of the time seen)", for the player page. */
	public String describe(Link link, UUID from) {
		List<String> parts = new ArrayList<>();
		int together = link.joins + link.leaves;
		if (together > 0) {
			Stats sa = players.get(link.a), sb = players.get(link.b);
			int fewest = Math.min(sa == null ? together : sa.joins + sa.leaves, sb == null ? together : sb.joins + sb.leaves);
			parts.add("logged on or off together " + together + " of " + Math.max(fewest, together) + " times");
		}
		if (link.secondsNear >= 60) parts.add("near each other " + ShopScreen.duration(link.secondsNear) + " (" + Math.round(nearShare(link) * 100) + "% of the time seen)");
		if (link.swaps > 0) parts.add("took turns " + link.swaps + "× (one off, the other on)" + (link.minutesBothOn > 0 ? ", but both on at once too" : ""));
		String text = String.join(" · ", parts);
		return text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	/** Everyone strongly linked to a player, surest first. */
	public List<Link> of(UUID id) {
		List<Link> found = new ArrayList<>();
		for (Link link : pairs.values()) {
			if ((link.a.equals(id) || link.b.equals(id)) && strong(link)) found.add(link);
		}
		found.sort(Comparator.comparingInt(this::confidence).reversed());
		return found;
	}

	/**
	 * Clusters of players who keep turning up together (each strongly linked to another in it), biggest first, for
	 * suggesting groups. Pairs already in a group together, and likely alts, are left out.
	 */
	public List<List<UUID>> clusters(Ledger ledger) {
		Map<UUID, Set<UUID>> graph = new HashMap<>();
		for (Link link : pairs.values()) {
			if (!strong(link) || looksLikeAlt(link)) continue;
			Profile pa = ledger.get(link.a), pb = ledger.get(link.b);
			if (pa == null || pb == null || sharesGroup(pa, pb)) continue;
			graph.computeIfAbsent(link.a, k -> new HashSet<>()).add(link.b);
			graph.computeIfAbsent(link.b, k -> new HashSet<>()).add(link.a);
		}
		List<List<UUID>> clusters = new ArrayList<>();
		Set<UUID> done = new HashSet<>();
		for (UUID start : graph.keySet()) {
			if (!done.add(start)) continue;
			List<UUID> cluster = new ArrayList<>();
			Deque<UUID> todo = new ArrayDeque<>(List.of(start));
			while (!todo.isEmpty()) {
				UUID id = todo.poll();
				cluster.add(id);
				for (UUID next : graph.getOrDefault(id, Set.of())) {
					if (done.add(next)) todo.add(next);
				}
			}
			if (cluster.size() >= 2) clusters.add(cluster);
		}
		clusters.sort(Comparator.comparingInt((List<UUID> c) -> c.size()).reversed());
		return clusters;
	}

	private static boolean sharesGroup(Profile a, Profile b) {
		for (String g : a.groups) {
			if (b.inGroup(g)) return true;
		}
		return false;
	}

	/** Forgets everything about a player (they were forgotten). */
	public void forget(UUID id) {
		boolean removed = pairs.values().removeIf(l -> l.a.equals(id) || l.b.equals(id));
		if (players.remove(id) != null || removed) {
			dirty = true;
			version++;
		}
	}

	public void clear() {
		pairs.clear();
		players.clear();
		joins.clear();
		leaves.clear();
		dirty = false;
		version++;
	}

	// ---- Saving ----

	private static final class Saved {
		int format = FORMAT;
		Map<UUID, Stats> players = new HashMap<>();
		List<Link> pairs = new ArrayList<>();
	}

	public void load(Path file) {
		clear();
		if (!Files.exists(file)) return;
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonElement json = JsonParser.parseReader(reader);
			if (!json.isJsonObject()) {
				// Counted the old way (every coincidence): set aside rather than mixed in with fairer numbers.
				reader.close();
				Files.move(file, file.resolveSibling(file.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
				NaughtyOrNiceClient.LOGGER.info("Set aside old Seen-with data in {}.old; counting afresh", file.getFileName());
				return;
			}
			Saved saved = GSON.fromJson(json, Saved.class);
			if (saved == null) return;
			if (saved.players != null) {
				saved.players.forEach((id, stats) -> {
					if (id != null && stats != null) players.put(id, stats);
				});
			}
			if (saved.pairs != null) {
				for (Link link : saved.pairs) {
					if (link != null && link.a != null && link.b != null && !link.a.equals(link.b)) pairs.put(key(link.a, link.b), link);
				}
			}
		} catch (IOException | RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not read {}", file, e);
		}
	}

	public void save(Path file, boolean force) {
		if (!dirty && !force) return;
		if (pairs.size() > MAX_PAIRS) {
			// Drop the weakest, oldest pairs.
			List<Link> all = new ArrayList<>(pairs.values());
			all.sort(Comparator.comparingInt(this::confidence).thenComparingLong(l -> l.last));
			for (int i = 0; i < all.size() - MAX_PAIRS; i++) pairs.remove(key(all.get(i).a, all.get(i).b));
		}
		try {
			if (pairs.isEmpty() && players.isEmpty() && !Files.exists(file)) return;
			Files.createDirectories(file.getParent());
			Saved saved = new Saved();
			saved.players = players;
			saved.pairs = new ArrayList<>(pairs.values());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(saved, writer);
			}
			dirty = false;
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not write {}", file, e);
		}
	}

	/** Every pair, for tests. */
	Collection<Link> all() {
		return pairs.values();
	}
}
