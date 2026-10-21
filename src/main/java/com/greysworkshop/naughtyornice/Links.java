package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
 */
public final class Links {
	private static final Gson GSON = new GsonBuilder().create();
	/** Joins (or leaves) this close together count as together... */
	private static final long TOGETHER_MILLIS = 20_000;
	/** ...unless the server is busy: with more than this many others in the window, it's just coincidence. */
	private static final int BUSY = 3;
	/** One logging off and another logging on this soon after looks like switching accounts. */
	private static final long SWAP_MILLIS = 15_000;
	/** Players this close count as near each other. */
	public static final double NEAR_BLOCKS = 24;
	/** Pairs kept at most; the weakest and oldest go first. */
	private static final int MAX_PAIRS = 20_000;
	/** A link this strong is worth showing. */
	public static final int SHOW_AT = 4;

	/** What's been seen of one pair. */
	public static final class Link {
		public UUID a, b;
		public int joins;
		public int leaves;
		public int swaps;
		public int secondsNear;
		public long last;

		/** How strongly they seem to go together. */
		public int score() {
			return joins * 3 + leaves * 2 + swaps * 4 + Math.min(secondsNear / 60, 30);
		}

		public UUID other(UUID id) {
			return id.equals(a) ? b : a;
		}

		/** "Logged on together 4×, near each other 12 min", for the player page. */
		public String describe() {
			List<String> parts = new ArrayList<>();
			if (joins > 0) parts.add("logged on together " + joins + "×");
			if (leaves > 0) parts.add("logged off together " + leaves + "×");
			if (swaps > 0) parts.add("took turns " + swaps + "× (one off, the other on)");
			if (secondsNear >= 60) parts.add("near each other " + ShopScreen.duration(secondsNear));
			String text = String.join(", ", parts);
			return text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
		}

		/** Took turns but never seen online together: probably the same person. */
		public boolean looksLikeAlt() {
			return swaps >= 2 && joins == 0 && leaves == 0 && secondsNear == 0;
		}
	}

	private record Event(UUID id, long time) {}

	private final Map<String, Link> pairs = new HashMap<>();
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

	public void joined(UUID id, long now) {
		trim(joins, now);
		trim(leaves, now);
		if (joins.size() <= BUSY) {
			for (Event e : joins) {
				if (!e.id.equals(id)) changed(link(id, e.id), now).joins++;
			}
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
		if (leaves.size() <= BUSY) {
			for (Event e : leaves) {
				if (!e.id.equals(id)) changed(link(id, e.id), now).leaves++;
			}
		}
		leaves.addLast(new Event(id, now));
	}

	/** Called once a second with two players near each other. */
	public void near(UUID a, UUID b, long now) {
		Link link = link(a, b);
		link.secondsNear++;
		link.last = now;
		dirty = true;
		// Only redo the screen once a minute for this; it changes every second.
		if (link.secondsNear % 60 == 0) version++;
	}

	private static void trim(Deque<Event> events, long now) {
		while (!events.isEmpty() && now - events.peekFirst().time > Math.max(TOGETHER_MILLIS, SWAP_MILLIS)) events.removeFirst();
	}

	/** Everyone linked to a player strongly enough to show, strongest first. */
	public List<Link> of(UUID id) {
		List<Link> found = new ArrayList<>();
		for (Link link : pairs.values()) {
			if ((link.a.equals(id) || link.b.equals(id)) && link.score() >= SHOW_AT) found.add(link);
		}
		found.sort(Comparator.comparingInt(Link::score).reversed());
		return found;
	}

	/**
	 * Clusters of players who keep turning up together (each strongly linked to another in it), biggest first, for
	 * suggesting groups. Pairs already in a group together are left out.
	 */
	public List<List<UUID>> clusters(Ledger ledger, int minScore) {
		Map<UUID, Set<UUID>> graph = new HashMap<>();
		for (Link link : pairs.values()) {
			if (link.score() < minScore) continue;
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
		if (pairs.values().removeIf(l -> l.a.equals(id) || l.b.equals(id))) {
			dirty = true;
			version++;
		}
	}

	public void clear() {
		pairs.clear();
		joins.clear();
		leaves.clear();
		dirty = false;
		version++;
	}

	public void load(Path file) {
		clear();
		if (!Files.exists(file)) return;
		try (Reader reader = Files.newBufferedReader(file)) {
			List<Link> loaded = GSON.fromJson(reader, new TypeToken<List<Link>>() {}.getType());
			if (loaded == null) return;
			for (Link link : loaded) {
				if (link != null && link.a != null && link.b != null && !link.a.equals(link.b)) pairs.put(key(link.a, link.b), link);
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
			all.sort(Comparator.comparingInt(Link::score).thenComparingLong(l -> l.last));
			for (int i = 0; i < all.size() - MAX_PAIRS; i++) pairs.remove(key(all.get(i).a, all.get(i).b));
		}
		try {
			if (pairs.isEmpty() && !Files.exists(file)) return;
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(new ArrayList<>(pairs.values()), writer);
			}
			dirty = false;
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not write {}", file, e);
		}
	}
}
