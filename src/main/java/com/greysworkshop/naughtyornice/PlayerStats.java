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

	// ---- Identity (tab list) ----
	/** The server's own name for them in the tab list ("[VIP] Steve"), if it isn't just their name. */
	public String tabName = "";
	public List<String> pastTabNames = new ArrayList<>();
	/** The account a skin came from, when it isn't theirs (skin plugins on offline-mode servers name it). */
	public String skinOwner = "";
	/** Whether their chat is signed (vanilla); off means a chat-reports mod, an offline account or a modified client. */
	public boolean signedChat;
	public boolean signedKnown;
	/** Their chat key's expiry, to notice when it changes (a new key: their game restarted, or the key renewed). */
	public long chatKey;
	public int chatKeyChanges;

	// ---- Habits (tab list) ----
	/** Days they were online (days since 1970, local time), oldest first. */
	public List<Integer> days = new ArrayList<>();
	/** Minutes online on each day of the week, Monday first. */
	public int[] weekdays = new int[7];
	/** Logins and logouts seen, as points on a 24-hour clock face, for the usual time of day. */
	public double loginSin, loginCos, logoutSin, logoutCos;
	/** Logins within 2 minutes of you joining, logouts within a minute of you joining. */
	public int joinedAfterYou;
	public int leftAfterYouJoined;
	/** Logouts within 30 seconds of coming into your view. */
	public int leftWhenSeen;
	/** Logouts within a minute of dying. */
	public int leftAfterDying;
	/** How they died, by kind ("Fall", "Explosion", "Elytra crash"...). */
	public Map<String, Integer> deathCauses = new HashMap<>();
	/** Advancements they made, as the server announced them. */
	public List<Note> advancements = new ArrayList<>();
	public long pingSquares;

	// ---- Chat ----
	public int messages;
	public int whispersToYou;
	public int whispersFromYou;
	public long words;
	public long firstMessage;
	public long lastMessage;
	/** Messages in each hour of the day. */
	public int[] chatHours = new int[24];
	/** Players they name in chat, and players who name them. */
	public Map<String, Integer> mentions = new HashMap<>();
	public Map<String, Integer> mentionedBy = new HashMap<>();
	/** Times they said your name. */
	public int mentionsOfYou;
	/** Words they use most (4+ letters, not everyday ones). */
	public Map<String, Integer> topWords = new HashMap<>();
	public int capsMessages;
	public int questions;
	/** 1.8.0 kept the lines themselves; they're read again with the stricter parser into {@link #places} when loaded. */
	public List<Note> coordsPosted = new ArrayList<>();
	/** Links they posted (Discord invites...), newest last. */
	public List<Note> linksPosted = new ArrayList<>();
	/** Places they gave away: coordinates they wrote, or where they summoned a wither. Newest last. */
	public List<Place> places = new ArrayList<>();
	/** What they talk about ({@link ChatIntel.Topic} names), counted. */
	public Map<String, Integer> topics = new HashMap<>();

	/** A place a player gave away, and how we know. */
	public static final class Place {
		public long time;
		public int x, z;
		/** {@link Integer#MIN_VALUE} if they gave no height. */
		public int y = Integer.MIN_VALUE;
		/** "minecraft:the_nether"..., or "" if they didn't say. */
		public String dimension = "";
		/** How we know: "said" (they wrote it), "highway" (a distance along one), "wither" (they summoned one there). */
		public String how = "said";
		/** The line they wrote, or what happened. */
		public String text = "";

		public boolean hasY() {
			return y != Integer.MIN_VALUE;
		}

		public String coords() {
			return hasY() ? x + ", " + y + ", " + z : x + ", " + z;
		}
	}

	/** Notes a place, unless it's the same one they gave in the last 10 minutes. */
	public boolean addPlace(int x, int y, int z, String dimension, String how, String text, long time) {
		for (int i = places.size() - 1; i >= 0 && i >= places.size() - 10; i--) {
			Place p = places.get(i);
			if (p.x == x && p.z == z && time - p.time < 600_000) return false;
		}
		Place p = new Place();
		p.time = time;
		p.x = x;
		p.y = y;
		p.z = z;
		p.dimension = dimension == null ? "" : dimension;
		p.how = how;
		p.text = text.length() > 160 ? text.substring(0, 160) + "…" : text;
		places.add(p);
		while (places.size() > 40) places.remove(0);
		return true;
	}

	/** Every place in a line they wrote. Returns how many were new. */
	public int placesFrom(String text, long time) {
		int added = 0;
		for (ChatIntel.Coord c : ChatIntel.coords(text)) {
			String how = c.kind() == ChatIntel.CoordKind.HIGHWAY ? "highway" : "said";
			if (addPlace(c.x(), c.y(), c.z(), c.dimension(), how, text, time)) added++;
		}
		return added;
	}

	/** Something with a time: an advancement, a line they posted. */
	public static final class Note {
		public long time;
		public String text = "";

		public Note() {}

		Note(long time, String text) {
			this.time = time;
			this.text = text;
		}
	}

	static void note(List<Note> list, String text, int keep) {
		list.add(new Note(System.currentTimeMillis(), text.length() > 120 ? text.substring(0, 120) + "…" : text));
		while (list.size() > keep) list.remove(0);
	}

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
		bump(map, key, by, MAP_KEYS);
	}

	static void bump(Map<String, Integer> map, String key, int by, int keys) {
		if (key == null || key.isEmpty()) return;
		map.merge(key, by, Integer::sum);
		if (map.size() > keys) {
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
		pingSquares += (long) ms * ms;
		pingSamples++;
		pingMin = pingMin == 0 ? ms : Math.min(pingMin, ms);
		pingMax = Math.max(pingMax, ms);
		lastPing = ms;
	}

	/** Average ping, or -1 if never measured. */
	public int averagePing() {
		return pingSamples == 0 ? -1 : (int) (pingSum / pingSamples);
	}

	/** How much their ping wanders (standard deviation, ms), or -1 without enough samples. */
	public int pingSpread() {
		if (pingSamples < 5) return -1;
		double mean = pingSum / (double) pingSamples;
		return (int) Math.round(Math.sqrt(Math.max(0, pingSquares / (double) pingSamples - mean * mean)));
	}

	/** Notes a minute online today. */
	void onlineMinute(java.time.LocalDateTime now) {
		int day = (int) now.toLocalDate().toEpochDay();
		if (days.isEmpty() || days.get(days.size() - 1) != day) {
			days.add(day);
			while (days.size() > 400) days.remove(0);
		}
		weekdays[now.getDayOfWeek().getValue() - 1]++;
	}

	/** Days online in the last {@code n} days, today included. */
	public int daysInLast(int n) {
		int today = (int) java.time.LocalDate.now().toEpochDay();
		int count = 0;
		for (int d : days) {
			if (d > today - n) count++;
		}
		return count;
	}

	/** Most days online in a row, and the run going on now (0 if not online today or yesterday). */
	public int[] streaks() {
		int best = 0, run = 0, prev = Integer.MIN_VALUE;
		for (int d : days) {
			run = d == prev + 1 ? run + 1 : 1;
			best = Math.max(best, run);
			prev = d;
		}
		int today = (int) java.time.LocalDate.now().toEpochDay();
		int current = !days.isEmpty() && days.get(days.size() - 1) >= today - 1 ? run : 0;
		return new int[] {best, current};
	}

	/** A login or logout at this time of day, on the clock face. */
	void clock(boolean login, java.time.LocalTime time) {
		double a = (time.getHour() * 60 + time.getMinute()) / 1440.0 * 2 * Math.PI;
		if (login) {
			loginSin += Math.sin(a);
			loginCos += Math.cos(a);
		} else {
			logoutSin += Math.sin(a);
			logoutCos += Math.cos(a);
		}
	}

	/**
	 * Their usual time to log in (or out) as "19:40", or null: needs 3 or more, close enough together to have a usual
	 * time at all (the average clock-face point is at least 0.5 from the middle).
	 */
	public String usualTime(boolean login) {
		int n = login ? logins : logouts;
		double sin = login ? loginSin : logoutSin, cos = login ? loginCos : logoutCos;
		if (n < 3 || Math.hypot(sin, cos) / n < 0.5) return null;
		double a = Math.atan2(sin, cos);
		if (a < 0) a += 2 * Math.PI;
		int minutes = (int) Math.round(a / (2 * Math.PI) * 1440) % 1440;
		return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
	}

	/** What kind of death a death message is, from its translation key or (for plugin wording) its text. */
	static String deathCause(String keyOrText, boolean byPlayer) {
		String k = keyOrText.toLowerCase(java.util.Locale.ROOT);
		if (k.contains("flyintowall") || k.contains("kinetic")) return "Elytra crash";
		if (k.contains("explosion") || k.contains("blown up") || k.contains("blew up") || k.contains("crystal") || k.contains("badrespawn")
				|| k.contains("intentional game design")) return "Explosion";
		if (byPlayer) return "Killed by a player";
		if (k.contains("outofworld") || k.contains("outsideborder") || k.contains("void") || k.contains("world border")) return "Void or border";
		if (k.contains("fall") || k.contains("fell") || k.contains("hit the ground")) return "Fall";
		if (k.contains("lava") || k.contains("fire") || k.contains("burn") || k.contains("hotfloor") || k.contains("flames")) return "Fire or lava";
		if (k.contains("drown")) return "Drowned";
		if (k.contains("wither")) return "Wither";
		if (k.contains("starve")) return "Starved";
		if (k.contains("inwall") || k.contains("suffocat") || k.contains("cramming") || k.contains("squashed")) return "Suffocated";
		if (k.contains("mob") || k.contains("arrow") || k.contains("sting") || k.contains("trident") || k.contains("slain")) return "Mob";
		return "Other";
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
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			String owner = root.has("profileName") ? root.get("profileName").getAsString() : "";
			skinOwner = owner.equalsIgnoreCase(profile.name()) ? "" : owner;
			JsonObject t = root.getAsJsonObject("textures");
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

	/**
	 * What kind of account a UUID belongs to: Mojang gives Java accounts random (version 4) ids, offline-mode servers
	 * make version 3 ids from the name, Geyser/Floodgate gives Bedrock players ids starting with zeros, and NPC
	 * plugins use version 2.
	 */
	public static String accountType(java.util.UUID id) {
		if (id.getMostSignificantBits() == 0) return "Bedrock (joins through Geyser)";
		return switch (id.version()) {
			case 4 -> "Java, Mojang account";
			case 3 -> "Offline-mode (no Mojang login)";
			case 2 -> "NPC or bot";
			default -> "Unusual id (version " + id.version() + ")";
		};
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
		if (tabName == null) tabName = "";
		if (pastTabNames == null) pastTabNames = new ArrayList<>();
		if (skinOwner == null) skinOwner = "";
		if (days == null) days = new ArrayList<>();
		if (weekdays == null || weekdays.length != 7) weekdays = new int[7];
		if (deathCauses == null) deathCauses = new HashMap<>();
		if (advancements == null) advancements = new ArrayList<>();
		if (chatHours == null || chatHours.length != 24) chatHours = new int[24];
		if (mentions == null) mentions = new HashMap<>();
		if (mentionedBy == null) mentionedBy = new HashMap<>();
		if (topWords == null) topWords = new HashMap<>();
		if (coordsPosted == null) coordsPosted = new ArrayList<>();
		if (places == null) places = new ArrayList<>();
		places.removeIf(pl -> pl == null);
		for (Place pl : places) {
			if (pl.dimension == null) pl.dimension = "";
			if (pl.how == null) pl.how = "said";
			if (pl.text == null) pl.text = "";
		}
		if (topics == null) topics = new HashMap<>();
		// 1.8.0 noted any line with two 3-digit numbers; only the ones that really are places carry over.
		for (Note n : coordsPosted) {
			if (n != null && n.text != null) placesFrom(n.text, n.time);
		}
		coordsPosted.clear();
		if (linksPosted == null) linksPosted = new ArrayList<>();
	}
}
