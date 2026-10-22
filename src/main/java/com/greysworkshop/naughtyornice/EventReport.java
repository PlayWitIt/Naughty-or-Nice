package com.greysworkshop.naughtyornice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a recording says happened: when it ran, who took part, who started the fighting (the first hit one player
 * landed on another: the server says who damaged whom, so this is certain), every kill and its weapon, and a line per
 * player (kills, deaths, hits dealt and taken, totems popped, crystals set off, shots, chat).
 */
public final class EventReport {
	public final List<EventLog.Entry> entries;
	public final long start, end;
	/** The first hit between two players, and the swings just before it; null if no players fought. */
	public final EventLog.Entry firstHit;
	public final List<EventLog.Entry> kills = new ArrayList<>();
	public final Map<String, Player> players = new LinkedHashMap<>();

	public static final class Player {
		public final String name;
		public int kills, deaths, hitsDealt, hitsTaken, totems, crystals, shots, chat;
		public long firstSeen = Long.MAX_VALUE, lastSeen;
		/** Who they hit most, and who hit them most. */
		public final Map<String, Integer> hit = new HashMap<>(), hitBy = new HashMap<>();
		/** Weapons they hit with. */
		public final Map<String, Integer> weapons = new HashMap<>();

		Player(String name) {
			this.name = name;
		}
	}

	public EventReport(List<EventLog.Entry> entries, Set<String> knownPlayers) {
		this.entries = entries;
		start = entries.isEmpty() ? 0 : entries.get(0).t;
		end = entries.isEmpty() ? 0 : entries.get(entries.size() - 1).t;
		EventLog.Entry first = null;
		for (EventLog.Entry e : entries) {
			boolean actorIsPlayer = !e.actor.isEmpty() && knownPlayers.contains(e.actor.toLowerCase(java.util.Locale.ROOT));
			boolean targetIsPlayer = !e.target.isEmpty() && knownPlayers.contains(e.target.toLowerCase(java.util.Locale.ROOT));
			Player a = actorIsPlayer ? player(e.actor) : null, b = targetIsPlayer ? player(e.target) : null;
			for (Player p : new Player[] {a, b}) {
				if (p == null) continue;
				p.firstSeen = Math.min(p.firstSeen, e.t);
				p.lastSeen = Math.max(p.lastSeen, e.t);
			}
			switch (e.type) {
				case HIT -> {
					if (a != null) {
						a.hitsDealt++;
						if (b != null) PlayerStats.bump(a.hit, b.name, 1);
						String weapon = weaponOf(e.detail);
						if (!weapon.isEmpty()) PlayerStats.bump(a.weapons, weapon, 1);
					}
					if (b != null) {
						b.hitsTaken++;
						if (a != null) PlayerStats.bump(b.hitBy, a.name, 1);
					}
					if (first == null && a != null && b != null && !a.name.equalsIgnoreCase(b.name)) first = e;
				}
				case DEATH -> {
					if (b != null) b.deaths++;
					if (a != null) {
						a.kills++;
						kills.add(e);
					} else if (b != null) {
						kills.add(e);
					}
				}
				case TOTEM -> {
					if (a != null) a.totems++;
				}
				case CRYSTAL_SET_OFF -> {
					if (a != null) a.crystals++;
				}
				case SHOT -> {
					if (a != null) a.shots++;
				}
				case CHAT, WHISPER -> {
					if (a != null) a.chat++;
				}
				default -> {}
			}
		}
		firstHit = first;
	}

	private Player player(String name) {
		return players.computeIfAbsent(name.toLowerCase(java.util.Locale.ROOT), k -> new Player(name));
	}

	/** The weapon written in a hit's detail ("with Diamond Sword · player attack"), or "". */
	static String weaponOf(String detail) {
		int i = detail.indexOf("with ");
		if (i < 0) return "";
		int end = detail.indexOf(" · ", i);
		return (end < 0 ? detail.substring(i + 5) : detail.substring(i + 5, end)).strip();
	}

	/** The swings (by either player) in the 5 seconds before the first hit, oldest first. */
	public List<EventLog.Entry> swingsBeforeFirstHit() {
		List<EventLog.Entry> list = new ArrayList<>();
		if (firstHit == null) return list;
		for (EventLog.Entry e : entries) {
			if (e.type != EventLog.Type.SWING || e.t > firstHit.t || firstHit.t - e.t > 5000) continue;
			if (e.actor.equalsIgnoreCase(firstHit.actor) || e.actor.equalsIgnoreCase(firstHit.target)) list.add(e);
		}
		return list;
	}

	/** Players who did anything, most kills first, then most hits. */
	public List<Player> ranked() {
		List<Player> list = new ArrayList<>(players.values());
		list.sort(Comparator.comparingInt((Player p) -> -p.kills).thenComparingInt(p -> -p.hitsDealt).thenComparing(p -> p.name.toLowerCase(java.util.Locale.ROOT)));
		return list;
	}
}
