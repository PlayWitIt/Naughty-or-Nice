package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Nice and Naughty lists for one server (or singleplayer world), plus what's been seen of everyone.
 * Saved to config/naughtyornice/lists/&lt;server&gt;.json. Players who are just passing by and haven't done
 * anything are kept in memory only.
 */
public final class Ledger {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Map<UUID, Profile> profiles = new LinkedHashMap<>();
	/** Lower-case name to profile, for fast lookups with thousands of remembered players. */
	private final Map<String, Profile> names = new HashMap<>();
	private String key;
	private boolean dirty;
	private int changes;
	/** Colour (an index into the screen's group palette) for each group, by lower-case name. */
	private Map<String, Integer> groupColors = new HashMap<>();
	/** Group names with their members, rebuilt when a group changes. */
	private Map<String, List<Profile>> groupCache;
	/** Groups to warn about when a member joins or comes near, by lower-case name. */
	private java.util.Set<String> alertGroups = new java.util.HashSet<>();
	/** Who's been seen together, for spotting crews and alts. */
	public final Links links = new Links();
	/** How busy this server is at each hour of the week. */
	public ServerActivity activity = new ServerActivity();
	/** Old and fresh land, and the trails through it. */
	public final ChunkTrails chunkTrails = new ChunkTrails();

	/**
	 * The server (by address) or singleplayer world. The address is tidied up, so "Constantiam.net",
	 * "constantiam.net:25565" and "constantiam.net." share one set of lists.
	 */
	public static String worldKey(MinecraftClient client) {
		ServerInfo server = client.getCurrentServerEntry();
		if (server != null) return "server:" + normalizeAddress(server.address);
		if (client.getServer() != null) return "local:" + client.getServer().getSaveProperties().getLevelName();
		return null;
	}

	public String key() {
		return key;
	}

	static String normalizeAddress(String address) {
		String a = address.strip().toLowerCase(Locale.ROOT);
		if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
		while (a.endsWith(".")) a = a.substring(0, a.length() - 1);
		return a;
	}

	/** A file or folder name as older versions spelled it, tidied up the same way. */
	private static String normalizeStem(String stem) {
		String n = stem.toLowerCase(Locale.ROOT);
		if (n.endsWith("_25565")) n = n.substring(0, n.length() - 6);
		while (n.endsWith(".")) n = n.substring(0, n.length() - 1);
		return n;
	}

	private static String safe(String key) {
		return key.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static final String[] SIDE_FILES = {".groups.json", ".links.json", ".alerts.json", ".activity.json", ".trails.json", ".chunks.bin"};

	/**
	 * Older versions kept lists per address exactly as typed, so the same server could be split across several
	 * files (joined once as "Constantiam.net", once as "constantiam.net:25565"). Brings them together under this
	 * server's name: the biggest is moved into place, the others are merged in after loading and kept as .merged.
	 */
	private List<Path> migrate() {
		List<Path> others = new ArrayList<>();
		if (key == null || !key.startsWith("server:")) return others;
		Path dir = NNConfig.dir().resolve("lists");
		if (!Files.isDirectory(dir)) return others;
		String target = safe(key);
		List<Path> legacy = new ArrayList<>();
		try (var files = Files.list(dir)) {
			files.forEach(f -> {
				String name = f.getFileName().toString();
				if (!name.endsWith(".json") || java.util.Arrays.stream(SIDE_FILES).anyMatch(name::endsWith)) return;
				String stem = name.substring(0, name.length() - 5);
				if (!stem.equals(target) && normalizeStem(stem).equals(target)) legacy.add(f);
			});
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.warn("Could not look for older lists", e);
			return others;
		}
		if (legacy.isEmpty()) return others;
		legacy.sort(java.util.Comparator.comparingLong((Path f) -> f.toFile().length()).reversed());
		try {
			if (!Files.exists(file())) {
				Path main = legacy.remove(0);
				String stem = main.getFileName().toString().replaceFirst("\\.json$", "");
				Files.move(main, file());
				for (String side : SIDE_FILES) {
					Path from = dir.resolve(stem + side);
					if (Files.exists(from) && !Files.exists(dir.resolve(target + side))) Files.move(from, dir.resolve(target + side));
				}
				Path chat = NNConfig.dir().resolve("chatlogs");
				if (Files.isDirectory(chat.resolve(stem)) && !Files.exists(chat.resolve(target))) Files.move(chat.resolve(stem), chat.resolve(target));
				NaughtyOrNiceClient.LOGGER.info("Moved lists {} to {} (same server, address spelled differently)", main, file());
			}
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.warn("Could not move older lists", e);
		}
		others.addAll(legacy);
		return others;
	}

	/** Adds the players from another list file for this server: anyone missing, and what's missing from anyone known. */
	private void mergeFrom(Path file) {
		try (Reader reader = Files.newBufferedReader(file)) {
			List<Profile> loaded = GSON.fromJson(reader, new TypeToken<List<Profile>>() {}.getType());
			if (loaded != null) {
				for (Profile other : loaded) {
					if (other == null || other.id == null || other.name == null) continue;
					Profile mine = profiles.get(other.id);
					if (mine == null) {
						fixUp(other);
						profiles.put(other.id, other);
						index(other);
						continue;
					}
					fixUp(other);
					if (mine.verdict == Profile.Verdict.NONE && other.verdict != Profile.Verdict.NONE) {
						mine.verdict = other.verdict;
						mine.listedAt = other.listedAt;
						mine.autoListed = other.autoListed;
					}
					for (Profile.Detail d : other.details) {
						Profile.Detail added = mine.addDetail(d.text, d.from, d.seen);
						if (added != null) added.time = d.time;
					}
					for (String g : other.groups) {
						if (!mine.inGroup(g)) mine.groups.add(g);
					}
					java.util.Set<Long> starts = new java.util.HashSet<>();
					for (Profile.Conversation c : mine.conversations) starts.add(c.start);
					for (Profile.Conversation c : other.conversations) {
						if (starts.add(c.start)) mine.conversations.add(c);
					}
					mine.conversations.sort(java.util.Comparator.comparingLong(c -> c.start));
					other.stats.tally.forEach((k, v) -> mine.stats.tally.merge(k, v, Integer::sum));
					mine.actions.addAll(other.actions);
					mine.actions.sort(java.util.Comparator.comparingLong(a -> a.time));
					while (mine.actions.size() > NNConfig.INSTANCE.maxActions) mine.actions.remove(0);
					mine.sightings.addAll(other.sightings);
					mine.sightings.sort(java.util.Comparator.comparingLong(sg -> sg.time));
					mine.secondsNear += other.secondsNear;
					mine.firstSeen = mine.firstSeen <= 0 ? other.firstSeen : other.firstSeen <= 0 ? mine.firstSeen : Math.min(mine.firstSeen, other.firstSeen);
					mine.lastSeen = Math.max(mine.lastSeen, other.lastSeen);
				}
			}
			Files.move(file, file.resolveSibling(file.getFileName() + ".merged"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			NaughtyOrNiceClient.LOGGER.info("Merged lists from {}", file);
			touch();
		} catch (IOException | RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not merge {}", file, e);
		}
	}

	/** Switches to another server's lists, saving the current ones first. */
	public void switchTo(String newKey) {
		if (newKey == null ? key == null : newKey.equals(key)) return;
		// Finish any conversations being recorded, so they're saved with the lists they belong to.
		if (NaughtyOrNiceClient.INSTANCE != null) NaughtyOrNiceClient.INSTANCE.chatLogger.closeAll();
		save(true);
		profiles.clear();
		names.clear();
		groupColors = new HashMap<>();
		alertGroups = new java.util.HashSet<>();
		groupCache = null;
		links.clear();
		activity = new ServerActivity();
		chunkTrails.clear();
		key = newKey;
		if (key != null) {
			load();
			links.load(linksFile());
			activity = ServerActivity.load(activityFile());
			chunkTrails.load(sideFile(".trails.json"), sideFile(".chunks.bin"));
		}
		if (NaughtyOrNiceClient.INSTANCE != null) NaughtyOrNiceClient.INSTANCE.chatLogger.loadFeed();
	}

	public Profile get(UUID id) {
		return profiles.get(id);
	}

	public Profile getOrCreate(UUID id, String name) {
		Profile profile = profiles.get(id);
		if (profile == null && name != null) {
			// Someone added by name before we knew their real UUID.
			Profile guess = byName(name);
			if (guess != null && guess.placeholder) {
				profiles.remove(guess.id);
				guess.id = id;
				guess.placeholder = false;
				guess.lastSeen = System.currentTimeMillis();
				profiles.put(id, guess);
				touch();
				profile = guess;
			}
		}
		if (profile == null) {
			profile = new Profile(id, name);
			profile.record(Profile.Kind.SEEN, "", NNConfig.INSTANCE.maxActions);
			profiles.put(id, profile);
			index(profile);
			touch();
		} else if (name != null && !name.isEmpty() && !name.equals(profile.name)) {
			names.remove(profile.name.toLowerCase(Locale.ROOT), profile);
			// A new name on the same account: remember the old one (not just a change of capitals).
			String old = profile.name;
			if (!name.equalsIgnoreCase(old) && !old.isEmpty() && !old.equals("?")) {
				profile.pastNames.removeIf(n -> n.equalsIgnoreCase(name));
				if (profile.pastNames.stream().noneMatch(n -> n.equalsIgnoreCase(old))) profile.pastNames.add(old);
				profile.record(Profile.Kind.LISTED, "Changed their name from " + old + " to " + name, NNConfig.INSTANCE.maxActions);
			}
			profile.name = name;
			index(profile);
			touch();
		}
		return profile;
	}

	/** A profile for a name typed into the search box: the online player if they're on, otherwise a placeholder. */
	public Profile addByName(MinecraftClient client, String name) {
		if (client.getNetworkHandler() != null) {
			PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(name);
			if (entry != null) return getOrCreate(entry.getProfile().id(), entry.getProfile().name());
		}
		Profile known = byName(name);
		if (known != null) return known;
		Profile profile = new Profile(NaughtyOrNiceClient.offlineId(name), name);
		profile.placeholder = true;
		profile.lastSeen = 0;
		profiles.put(profile.id, profile);
		index(profile);
		touch();
		return profile;
	}

	public Profile byName(String name) {
		return name == null ? null : names.get(name.toLowerCase(Locale.ROOT));
	}

	/** Someone who used to go by this name, or null. */
	public Profile byPastName(String name) {
		if (name == null) return null;
		for (Profile p : profiles.values()) {
			for (String past : p.pastNames) {
				if (past.equalsIgnoreCase(name)) return p;
			}
		}
		return null;
	}

	private void index(Profile profile) {
		names.put(profile.name.toLowerCase(Locale.ROOT), profile);
	}

	public Collection<Profile> all() {
		return profiles.values();
	}

	public List<Profile> onList(Profile.Verdict verdict) {
		List<Profile> result = new ArrayList<>();
		for (Profile profile : profiles.values()) {
			if (profile.verdict == verdict) result.add(profile);
		}
		return result;
	}

	public void setVerdict(Profile profile, Profile.Verdict verdict, boolean automatic, String reason) {
		if (profile.verdict == verdict) return;
		profile.verdict = verdict;
		profile.listedAt = System.currentTimeMillis();
		profile.autoListed = automatic;
		String text = switch (verdict) {
			case NICE -> "Added to your Nice list";
			case NAUGHTY -> "Added to your Naughty list";
			case LOOK -> "Added to your Look Into list";
			case FRIEND -> "Added to your Friends (" + Friends.circleName(withCircle(profile)) + ")";
			case NONE -> "Taken off your lists";
		};
		if (reason != null) text += " (" + reason + ")";
		profile.record(Profile.Kind.LISTED, text, NNConfig.INSTANCE.maxActions);
		BaseInsuranceBridge.syncFriend(profile);
		if (NaughtyOrNiceClient.INSTANCE != null) NaughtyOrNiceClient.INSTANCE.chatLogger.verdictChanged(profile);
		touch();
	}

	/** A new friend goes in the first circle unless they already have one. */
	private static Profile withCircle(Profile profile) {
		if (Friends.byId(profile.circle) == null) profile.circle = Friends.circles().get(0).id;
		return profile;
	}

	/** Makes them a friend in this circle (or moves them to it). */
	public void setCircle(Profile profile, Friends.Circle circle) {
		if (profile.verdict == Profile.Verdict.FRIEND && profile.circle.equals(circle.id)) return;
		profile.circle = circle.id;
		if (profile.verdict != Profile.Verdict.FRIEND) {
			setVerdict(profile, Profile.Verdict.FRIEND, false, null);
		} else {
			profile.record(Profile.Kind.LISTED, "Moved to " + circle.name, NNConfig.INSTANCE.maxActions);
			BaseInsuranceBridge.syncFriend(profile);
			touch();
		}
	}

	public void forget(Profile profile) {
		if (NaughtyOrNiceClient.INSTANCE != null) NaughtyOrNiceClient.INSTANCE.chatLogger.drop(profile);
		profiles.remove(profile.id);
		names.remove(profile.name.toLowerCase(Locale.ROOT), profile);
		profile.verdict = Profile.Verdict.NONE;
		BaseInsuranceBridge.syncFriend(profile);
		if (!profile.groups.isEmpty()) groupsChanged();
		links.forget(profile.id);
		touch();
	}

	/** Adds a player we've never seen, e.g. from a friend's shared lists. */
	public void add(Profile profile) {
		profiles.put(profile.id, profile);
		index(profile);
		if (!profile.groups.isEmpty()) groupsChanged();
		touch();
	}

	// ---- Groups ----

	/** Longest group name. */
	public static final int GROUP_NAME_MAX = 24;
	/** How many colours the screen has for groups. */
	public static final int GROUP_COLORS = 10;

	/** A group name as typed, tidied up: trimmed, single spaces, at most {@link #GROUP_NAME_MAX} long. Empty if unusable. */
	public static String cleanGroupName(String name) {
		if (name == null) return "";
		String clean = name.strip().replaceAll("\\s+", " ");
		return clean.length() > GROUP_NAME_MAX ? clean.substring(0, GROUP_NAME_MAX).strip() : clean;
	}

	/** Call after changing anyone's groups directly. */
	public void groupsChanged() {
		groupCache = null;
		touch();
	}

	/** Every group, by name (A to Z), with its members. */
	public Map<String, List<Profile>> groups() {
		if (groupCache != null) return groupCache;
		Map<String, String> spelling = new HashMap<>();
		Map<String, List<Profile>> found = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (Profile p : profiles.values()) {
			for (String g : p.groups) {
				String name = spelling.computeIfAbsent(g.toLowerCase(Locale.ROOT), k -> g);
				found.computeIfAbsent(name, k -> new ArrayList<>()).add(p);
			}
		}
		for (List<Profile> members : found.values()) members.sort(java.util.Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
		for (String name : found.keySet()) groupColor(name);
		groupCache = found;
		return found;
	}

	/** The name a group is known by (its first spelling), or null if there's no such group. */
	public String groupName(String name) {
		for (String g : groups().keySet()) {
			if (g.equalsIgnoreCase(name)) return g;
		}
		return null;
	}

	public List<Profile> members(String group) {
		List<Profile> members = groups().get(group);
		return members == null ? List.of() : members;
	}

	/** The palette index for a group: picked when it's first seen, the least-used colour so groups look different. */
	public int groupColor(String group) {
		String k = group.toLowerCase(Locale.ROOT);
		Integer color = groupColors.get(k);
		if (color != null) return color;
		int[] used = new int[GROUP_COLORS];
		for (int c : groupColors.values()) used[Math.floorMod(c, GROUP_COLORS)]++;
		int best = Math.floorMod(k.hashCode(), GROUP_COLORS);
		for (int i = 0; i < GROUP_COLORS; i++) {
			if (used[i] < used[best]) best = i;
		}
		groupColors.put(k, best);
		touch();
		return best;
	}

	/** Gives a group the next colour along. */
	public void cycleGroupColor(String group) {
		groupColors.put(group.toLowerCase(Locale.ROOT), (groupColor(group) + 1) % GROUP_COLORS);
		touch();
	}

	/** Puts someone in a group (made if it's new). Returns false if they were already in it or the name is unusable. */
	public boolean addToGroup(Profile profile, String group) {
		String clean = cleanGroupName(group);
		if (clean.isEmpty() || profile.inGroup(clean)) return false;
		String existing = groupName(clean);
		profile.groups.add(existing != null ? existing : clean);
		groupsChanged();
		return true;
	}

	public boolean removeFromGroup(Profile profile, String group) {
		boolean removed = profile.groups.removeIf(g -> g.equalsIgnoreCase(group));
		if (removed) groupsChanged();
		return removed;
	}

	/** Renames a group for all its members. Returns false if the new name is unusable or taken by another group. */
	public boolean renameGroup(String from, String to) {
		String clean = cleanGroupName(to);
		if (clean.isEmpty()) return false;
		String taken = groupName(clean);
		if (taken != null && !taken.equalsIgnoreCase(from)) return false;
		for (Profile p : profiles.values()) {
			for (int i = 0; i < p.groups.size(); i++) {
				if (p.groups.get(i).equalsIgnoreCase(from)) p.groups.set(i, clean);
			}
		}
		Integer color = groupColors.remove(from.toLowerCase(Locale.ROOT));
		if (color != null) groupColors.put(clean.toLowerCase(Locale.ROOT), color);
		if (alertGroups.remove(from.toLowerCase(Locale.ROOT))) alertGroups.add(clean.toLowerCase(Locale.ROOT));
		groupsChanged();
		return true;
	}

	/** Whether to warn when someone from this group joins or comes near. */
	public boolean alertsFor(String group) {
		return alertGroups.contains(group.toLowerCase(Locale.ROOT));
	}

	public void setAlerts(String group, boolean on) {
		if (on) alertGroups.add(group.toLowerCase(Locale.ROOT));
		else alertGroups.remove(group.toLowerCase(Locale.ROOT));
		touch();
	}

	/** The first of a player's groups that has alerts on, or null. */
	public String alertGroupOf(Profile p) {
		for (String g : p.groups) {
			if (alertsFor(g)) return g;
		}
		return null;
	}

	/** Breaks a group up (its members stay). Returns how to undo it. */
	public Runnable deleteGroup(String group) {
		List<Profile> members = new ArrayList<>(members(group));
		for (Profile p : members) p.groups.removeIf(g -> g.equalsIgnoreCase(group));
		groupsChanged();
		return () -> {
			for (Profile p : members) {
				if (!p.inGroup(group)) p.groups.add(group);
			}
			groupsChanged();
		};
	}

	public void markDirty() {
		touch();
	}

	private void touch() {
		dirty = true;
		changes++;
	}

	/** Goes up with every change, so the screen knows when to redo layouts that depend on the lists. */
	public int changes() {
		return changes;
	}

	private Path file() {
		String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
		return NNConfig.dir().resolve("lists").resolve(safe + ".json");
	}

	private void load() {
		waitForWrites();
		List<Path> others = migrate();
		loadLists();
		for (Path other : others) mergeFrom(other);
		if (!others.isEmpty()) groupCache = null;
	}

	private void loadLists() {
		Path file = file();
		if (!Files.exists(file)) {
			loadSideFiles();
			return;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			List<Profile> loaded = GSON.fromJson(reader, new TypeToken<List<Profile>>() {}.getType());
			if (loaded == null) return;
			for (Profile profile : loaded) {
				if (profile == null || profile.id == null) continue;
				fixUp(profile);
				if (forgettable(profile)) continue;
				profiles.put(profile.id, profile);
				index(profile);
			}
		} catch (IOException | RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not read {}", file, e);
		}
		loadSideFiles();
	}

	/** Fills in anything missing from a profile read from a file (older versions, or a hand-edited file). */
	private void fixUp(Profile profile) {
		if (profile.circle == null) profile.circle = "";
		if (profile.verdict == null) profile.verdict = Profile.Verdict.NONE;
		if (profile.actions == null) profile.actions = new ArrayList<>();
		profile.actions.removeIf(a -> a == null || a.kind == null);
		if (profile.recentChat == null) profile.recentChat = new ArrayList<>();
		if (profile.conversations == null) profile.conversations = new ArrayList<>();
		profile.conversations.removeIf(c -> c == null || c.lines == null || c.lines.isEmpty());
		for (Profile.Conversation c : profile.conversations) {
			// Still open when the game last closed (e.g. it crashed): finish it where it stopped.
			if (c.open) {
				c.open = false;
				c.end = c.lines.get(c.lines.size() - 1).time;
			}
		}
		ChatLogger.prune(profile);
		if (profile.verdict == null) profile.verdict = Profile.Verdict.NONE;
		if (profile.details == null) profile.details = new ArrayList<>();
		profile.details.removeIf(d -> d == null || d.text == null || d.text.isBlank());
		for (Profile.Detail d : profile.details) {
			if (d.from == null) d.from = "";
		}
		for (Profile.Conversation c : profile.conversations) {
			if (c.sharedBy == null) c.sharedBy = "";
		}
		// Notes from before details existed become details.
		if (profile.note != null && !profile.note.isBlank()) {
			for (String part : profile.note.split(";")) {
				Profile.Detail d = profile.addDetail(part, "", false);
				if (d != null) d.time = profile.listedAt > 0 ? profile.listedAt : profile.firstSeen;
			}
			touch();
		}
		profile.note = "";
		if (profile.groups == null) profile.groups = new ArrayList<>();
		List<String> groups = new ArrayList<>();
		for (String g : profile.groups) {
			String clean = cleanGroupName(g);
			if (!clean.isEmpty() && groups.stream().noneMatch(clean::equalsIgnoreCase)) groups.add(clean);
		}
		profile.groups = groups;
		if (profile.name == null) profile.name = "?";
		if (profile.pastNames == null) profile.pastNames = new ArrayList<>();
		profile.pastNames.removeIf(n -> n == null || n.isBlank());
		if (profile.sightings == null) profile.sightings = new ArrayList<>();
		profile.sightings.removeIf(sg -> sg == null);
		for (Profile.Sighting sg : profile.sightings) {
			if (sg.dimension == null) sg.dimension = "";
			if (sg.gear == null) sg.gear = "";
			if (sg.base == null) sg.base = "";
		}
		if (profile.hours != null && profile.hours.length != 24) profile.hours = null;
		if (profile.evidence == null) profile.evidence = new ArrayList<>();
		profile.evidence.removeIf(ev -> ev == null || ev.file == null || ev.file.isBlank());
		if (profile.friendViews == null) profile.friendViews = new ArrayList<>();
		profile.friendViews.removeIf(v -> v == null || v.from == null || v.from.isBlank());
		if (profile.stats == null) profile.stats = new PlayerStats();
		profile.stats.fixUp(profile);
		for (Profile.FriendView v : profile.friendViews) {
			if (v.verdict == null) v.verdict = Profile.Verdict.NONE;
		}
	}

	/** A file next to the lists for this server, e.g. lists/&lt;server&gt;.trails.json. */
	private Path sideFile(String suffix) {
		return NNConfig.dir().resolve("lists").resolve(key.replaceAll("[^A-Za-z0-9._-]", "_") + suffix);
	}

	/** Group colours and alerts, next to the lists. */
	private void loadSideFiles() {
		Path colors = groupsFile();
		if (Files.exists(colors)) {
			try (Reader reader = Files.newBufferedReader(colors)) {
				Map<String, Integer> loaded = GSON.fromJson(reader, new TypeToken<Map<String, Integer>>() {}.getType());
				if (loaded != null) loaded.forEach((k, v) -> {
					if (k != null && v != null) groupColors.put(k.toLowerCase(Locale.ROOT), Math.floorMod(v, GROUP_COLORS));
				});
			} catch (IOException | RuntimeException e) {
				NaughtyOrNiceClient.LOGGER.error("Could not read {}", colors, e);
			}
		}
		Path alerts = alertsFile();
		if (Files.exists(alerts)) {
			try (Reader reader = Files.newBufferedReader(alerts)) {
				List<String> loaded = GSON.fromJson(reader, new TypeToken<List<String>>() {}.getType());
				if (loaded != null) loaded.forEach(g -> {
					if (g != null && !g.isBlank()) alertGroups.add(g.toLowerCase(Locale.ROOT));
				});
			} catch (IOException | RuntimeException e) {
				NaughtyOrNiceClient.LOGGER.error("Could not read {}", alerts, e);
			}
		}
		groupCache = null;
	}

	/** Groups with join alerts on: config/naughtyornice/lists/&lt;server&gt;.alerts.json. */
	private Path alertsFile() {
		String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
		return NNConfig.dir().resolve("lists").resolve(safe + ".alerts.json");
	}

	/** How busy the server is when: config/naughtyornice/lists/&lt;server&gt;.activity.json. */
	private Path activityFile() {
		String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
		return NNConfig.dir().resolve("lists").resolve(safe + ".activity.json");
	}

	/** Who's been seen together: config/naughtyornice/lists/&lt;server&gt;.links.json. */
	private Path linksFile() {
		String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
		return NNConfig.dir().resolve("lists").resolve(safe + ".links.json");
	}

	/** Group colours, next to the lists: config/naughtyornice/lists/&lt;server&gt;.groups.json. */
	private Path groupsFile() {
		String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
		return NNConfig.dir().resolve("lists").resolve(safe + ".groups.json");
	}

	/** Unremarkable players who haven't been seen in a long time (see {@link NNConfig#forgetAfterDays}). */
	private static boolean forgettable(Profile profile) {
		int days = NNConfig.INSTANCE.forgetAfterDays;
		if (profile.notable() || days <= 0) return false;
		return System.currentTimeMillis() - profile.lastSeen > days * 86_400_000L;
	}

	private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Naughty or Nice saving");
		t.setDaemon(true);
		return t;
	});
	private java.util.concurrent.Future<?> pending;

	/** Waits for a background save to finish, so the file is complete before it's read or written again. */
	private void waitForWrites() {
		if (pending == null) return;
		try {
			pending.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (java.util.concurrent.ExecutionException e) {
			NaughtyOrNiceClient.LOGGER.error("Saving the lists failed", e.getCause());
		}
		pending = null;
	}

	private static void writeAtomically(Path file, String json) {
		try {
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, json);
			try {
				Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not write {}", file, e);
		}
	}

	/** Writes the lists if anything changed (or always, with {@code force}). */
	public void save(boolean force) {
		if (key == null) return;
		links.save(linksFile(), force);
		activity.save(activityFile(), force);
		chunkTrails.save(sideFile(".trails.json"), sideFile(".chunks.bin"), force);
		if (NaughtyOrNiceClient.INSTANCE != null) NaughtyOrNiceClient.INSTANCE.chatLogger.saveFeed(force);
		if (!dirty && !force) return;
		List<Profile> keep = new ArrayList<>();
		for (Profile profile : profiles.values()) {
			if (profile.worthKeeping() && !forgettable(profile)) keep.add(profile);
		}
		Path file = file();
		try {
			if (keep.isEmpty() && !Files.exists(file)) return;
			Files.createDirectories(file.getParent());
			// The snapshot is taken here, on the game thread; writing it out happens in the background (or right away
			// when the game is closing or switching servers), into a temporary file that then replaces the real one,
			// so a crash mid-write can't leave half a file.
			String json = GSON.toJson(keep);
			Runnable write = () -> writeAtomically(file, json);
			if (force) {
				waitForWrites();
				write.run();
			} else {
				pending = WRITER.submit(write);
			}
			// Only the colours of groups that still exist.
			Map<String, Integer> colors = new java.util.TreeMap<>();
			for (String g : groups().keySet()) colors.put(g.toLowerCase(Locale.ROOT), groupColor(g));
			if (!colors.isEmpty() || Files.exists(groupsFile())) {
				try (Writer writer = Files.newBufferedWriter(groupsFile())) {
					GSON.toJson(colors, writer);
				}
			}
			List<String> alerts = new ArrayList<>();
			for (String g : groups().keySet()) {
				if (alertsFor(g)) alerts.add(g.toLowerCase(Locale.ROOT));
			}
			if (!alerts.isEmpty() || Files.exists(alertsFile())) {
				try (Writer writer = Files.newBufferedWriter(alertsFile())) {
					GSON.toJson(alerts, writer);
				}
			}
			dirty = false;
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not write {}", file, e);
		}
	}
}
