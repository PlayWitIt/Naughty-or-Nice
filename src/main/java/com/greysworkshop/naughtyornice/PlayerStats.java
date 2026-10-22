package com.greysworkshop.naughtyornice;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lifetime numbers about one player, from everything the client gets to see: the tab list (online time, sessions,
 * ping, game mode, skin), the player in view (time, distance, speed, flying, gear, totems), death messages and chat.
 * Unlike the activity list and sightings these are never trimmed, so totals stay right however long you play.
 * Times are wall-clock milliseconds, measured only while you're on the server too.
 */
public final class PlayerStats {
	/** Times they've done each thing (by {@link Profile.Kind} name), all-time. */
	public Map<String, Integer> tally = new HashMap<>();

	// ---- Online (tab list) ----
	public long onlineMs;
	public long lastOnline;
	/** Logins and logouts you were there to see (not counting them being on already when you joined). */
	public int logins;
	public int logouts;
	public long lastLogin;
	public long lastLogout;
	/** Your own time on this server ({@link ServerActivity#myMs}) when these numbers started, for "share of your time". */
	public long myMsAtStart = -1;
	/** The latest sessions. */
	public List<Session> sessions = new ArrayList<>();
	/** Sessions you saw them log in and out of, and their total length. */
	public int wholeSessions;
	public long wholeSessionsMs;
	/** Longest time online in one go (if you missed the start or end, at least this long). */
	public long longestSessionMs;
	public long pingSum;
	public int pingSamples;
	public int pingMin;
	public int pingMax;
	public int lastPing;
	/** Minutes seen in each game mode ("survival", "creative", "spectator"...). */
	public Map<String, Integer> gameModes = new HashMap<>();

	// ---- Skin ----
	/** The skin's texture hash; empty for the default skins or a server that doesn't send skins. */
	public String skin = "";
	public String model = "";
	public String cape = "";
	/** Skins they had before, oldest first. */
	public List<String> pastSkins = new ArrayList<>();
	public long skinChanged;

	// ---- In view ----
	/** Times they came into view, all-time (sightings only keep the latest few). */
	public int timesSeen;
	public long inViewMs;
	public long lastInView;
	/** Nearest they've been to you, in blocks; -1 until seen. */
	public double closest = -1;
	public double travelled;
	/** Fastest seen, in blocks a second, over a whole second (so one jumpy packet doesn't count). */
	public double topSpeed;
	public long glidingMs;
	/** Seconds in view in each dimension. */
	public Map<String, Integer> dimensions = new HashMap<>();
	/** Seconds seen riding each kind of vehicle. */
	public Map<String, Integer> vehicles = new HashMap<>();
	/** Seconds seen holding each item (main hand), the most-held few. */
	public Map<String, Integer> held = new HashMap<>();
	/** The best armour material seen on them ("netherite"). */
	public String bestArmour = "";
	public int totemPops;
	public long lastTotemPop;

	// ---- Deaths and kills (death messages) ----
	public int deaths;
	public int deathsToPlayers;
	public Map<String, Integer> killedBy = new HashMap<>();
	public Map<String, Integer> victims = new HashMap<>();
	/** What they killed people with, as death messages name it. */
	public Map<String, Integer> weapons = new HashMap<>();
	public String lastDeath = "";
	public long lastDeathTime;

	// ---- Chat ----
	public int messages;
	public int whispersToYou;
	public int whispersFromYou;
	public long words;
	public long firstMessage;
	public long lastMessage;

	/** One stretch of being online, as seen from the tab list. */
	public static final class Session {
		public long start;
		public long end;
		/** You saw them log in (rather than they were on already when you joined). */
		public boolean startSeen;
		/** You saw them log out (rather than you left first). */
		public boolean endSeen;

		public long length() {
			return Math.max(0, end - start);
		}
	}

	/** Armour materials, worst to best. */
	private static final List<String> ARMOUR = List.of("leather", "golden", "chainmail", "turtle", "copper", "iron", "diamond", "netherite");
	/** Most different weapons, killers, items... kept per player. */
	private static final int MAP_KEYS = 12;

	static void bump(Map<String, Integer> map, String key, int by) {
		if (key == null || key.isEmpty()) return;
		map.merge(key, by, Integer::sum);
		if (map.size() > MAP_KEYS) {
			String smallest = null;
			for (Map.Entry<String, Integer> e : map.entrySet()) {
				if (!e.getKey().equals(key) && (smallest == null || e.getValue() < map.get(smallest))) smallest = e.getKey();
			}
			map.remove(smallest);
		}
	}

	/** The key with the biggest number, or null. */
	public static String top(Map<String, Integer> map) {
		String best = null;
		for (Map.Entry<String, Integer> e : map.entrySet()) {
			if (best == null || e.getValue() > map.get(best)) best = e.getKey();
		}
		return best;
	}

	public static int total(Map<String, Integer> map) {
		int n = 0;
		for (int v : map.values()) n += v;
		return n;
	}

	public int count(Profile.Kind kind) {
		return tally.getOrDefault(kind.name(), 0);
	}

	void armour(String material) {
		int seen = ARMOUR.indexOf(material);
		if (seen >= 0 && seen > ARMOUR.indexOf(bestArmour)) bestArmour = material;
	}

	public void ping(int ms) {
		if (ms <= 0) return;
		pingSum += ms;
		pingSamples++;
		pingMin = pingMin == 0 ? ms : Math.min(pingMin, ms);
		pingMax = Math.max(pingMax, ms);
		lastPing = ms;
	}

	/** Average ping, or -1 if never measured. */
	public int averagePing() {
		return pingSamples == 0 ? -1 : (int) (pingSum / pingSamples);
	}

	/** Average length of sessions you saw both ends of (the only ones whose length is really known), or -1. */
	public long averageSession() {
		return wholeSessions == 0 ? -1 : wholeSessionsMs / wholeSessions;
	}

	/** Starts a session: {@code seen} if you saw them log in. Only the latest few are kept; the totals are all-time. */
	Session startSession(long now, boolean seen, int keep) {
		Session s = new Session();
		s.start = s.end = now;
		s.startSeen = seen;
		sessions.add(s);
		while (sessions.size() > keep) sessions.remove(0);
		return s;
	}

	/** They're still on: the session runs to now. */
	void continueSession(Session s, long now) {
		s.end = now;
		longestSessionMs = Math.max(longestSessionMs, s.length());
	}

	/** They logged out while you watched. */
	void endSession(Session s, long now) {
		continueSession(s, now);
		s.endSeen = true;
		if (s.startSeen) {
			wholeSessions++;
			wholeSessionsMs += s.length();
		}
	}

	/** Kills (of anyone, you included) per death, or -1 if they've never died in a death message you saw. */
	public double killsPerDeath(Profile p) {
		int kills = p.count(Profile.Kind.KILLED_PLAYER) + p.count(Profile.Kind.KILLED_YOU);
		return deaths == 0 ? -1 : kills / (double) deaths;
	}

	/** Reads the skin and cape out of the textures the server sent; returns whether the skin changed. */
	public boolean readSkin(GameProfile profile, long now) {
		Property textures = null;
		for (Property p : profile.properties().get("textures")) textures = p;
		if (textures == null) return false;
		String newSkin = "", newModel = "", newCape = "";
		try {
			String json = new String(Base64.getDecoder().decode(textures.value()), StandardCharsets.UTF_8);
			JsonObject t = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures");
			if (t != null && t.has("SKIN")) {
				JsonObject s = t.getAsJsonObject("SKIN");
				newSkin = hash(s.get("url"));
				JsonObject meta = s.has("metadata") ? s.getAsJsonObject("metadata") : null;
				newModel = meta != null && meta.has("model") ? meta.get("model").getAsString() : "classic";
			}
			if (t != null && t.has("CAPE")) newCape = hash(t.getAsJsonObject("CAPE").get("url"));
		} catch (RuntimeException e) {
			return false;
		}
		cape = newCape;
		model = newModel;
		if (newSkin.equals(skin)) return false;
		boolean changed = !skin.isEmpty();
		if (changed) {
			pastSkins.remove(skin);
			pastSkins.add(skin);
			while (pastSkins.size() > 8) pastSkins.remove(0);
			skinChanged = now;
		}
		skin = newSkin;
		return changed;
	}

	private static String hash(JsonElement url) {
		if (url == null || url.isJsonNull()) return "";
		String u = url.getAsString();
		return u.substring(u.lastIndexOf('/') + 1);
	}

	/** Fills in anything missing after loading from a file. */
	void fixUp(Profile p) {
		if (tally == null) tally = new HashMap<>();
		// Lists from before 1.7.0 only had the activity list to count from.
		if (tally.isEmpty()) {
			for (Profile.Action a : p.actions) tally.merge(a.kind.name(), a.count, Integer::sum);
		}
		if (sessions == null) sessions = new ArrayList<>();
		sessions.removeIf(s -> s == null);
		if (gameModes == null) gameModes = new HashMap<>();
		if (skin == null) skin = "";
		if (model == null) model = "";
		if (cape == null) cape = "";
		if (pastSkins == null) pastSkins = new ArrayList<>();
		if (dimensions == null) dimensions = new HashMap<>();
		if (vehicles == null) vehicles = new HashMap<>();
		if (held == null) held = new HashMap<>();
		if (bestArmour == null) bestArmour = "";
		if (killedBy == null) killedBy = new HashMap<>();
		if (victims == null) victims = new HashMap<>();
		if (weapons == null) weapons = new HashMap<>();
		if (lastDeath == null) lastDeath = "";
	}
}
