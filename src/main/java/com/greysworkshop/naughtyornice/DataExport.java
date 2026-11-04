package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything Naughty or Nice knows about one server, as one JSON file, so it can be handed to someone (or an AI) for
 * analysis. It's the stored data itself, serialized with the same Gson that saves it, so nothing is rounded, guessed
 * or left out: every player with their verdict, groups, notes, first/last seen, times near you, sightings, all-time
 * stats (online time, sessions, logins, kills and deaths, ping, skins, chat counts, words, mentions, places and links
 * they posted, topics, death causes), the whole chat feed, who's been seen together, and how busy the server is when.
 * <p>
 * Times are Unix epoch milliseconds (UTC) unless the field name ends in "Iso". "firstSeen" is the first time this
 * client saw the player, which is not the same as the player's account join date on the server: that only exists here
 * if the server offers a command for it and it was captured (left out until then, never invented).
 */
public final class DataExport {
	private DataExport() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm").withZone(ZoneId.systemDefault());

	/** The complete export for the server whose lists are loaded, as pretty JSON. */
	public static String json(Ledger ledger, ChatLogger chat) {
		JsonObject root = new JsonObject();

		JsonObject meta = new JsonObject();
		meta.addProperty("mod", "Naughty or Nice " + UpdateCheck.current());
		meta.addProperty("server", Sharing.placeName(ledger.key()));
		meta.addProperty("serverKey", ledger.key());
		long now = System.currentTimeMillis();
		meta.addProperty("exportedAtMs", now);
		meta.addProperty("exportedAtIso", Instant.ofEpochMilli(now).toString());
		meta.addProperty("about", "Complete Naughty or Nice data for this server, for analysis. Times are Unix epoch "
				+ "milliseconds (UTC) unless the field name ends in \"Iso\". \"firstSeen\" is when this client first saw the "
				+ "player (not their account's join date on the server, which is only here if the server offers a command "
				+ "for it). Verdict is one of NICE, NAUGHTY, LOOK, FRIEND or NONE.");
		root.add("export", meta);

		root.add("summary", summary(ledger, chat, now));
		root.add("players", GSON.toJsonTree(new ArrayList<>(ledger.all())));
		root.add("chat", GSON.toJsonTree(chat.feed()));
		root.add("seenTogether", GSON.toJsonTree(ledger.links));
		root.add("serverActivity", GSON.toJsonTree(ledger.activity));
		root.add("groups", groups(ledger));
		return GSON.toJson(root);
	}

	/** Writes the export to config/naughtyornice/exports/&lt;server&gt;_&lt;when&gt;.json and returns the file. */
	public static Path write(Ledger ledger, ChatLogger chat) throws IOException {
		Path dir = NNConfig.dir().resolve("exports");
		Files.createDirectories(dir);
		String safe = Sharing.placeName(ledger.key()).replaceAll("[^A-Za-z0-9._-]", "_");
		if (safe.isBlank()) safe = "server";
		Path file = dir.resolve(safe + "_" + STAMP.format(Instant.now()) + ".json");
		Files.writeString(file, json(ledger, chat), StandardCharsets.UTF_8);
		return file;
	}

	/** Counts worth seeing at a glance; the players and chat below are the source of truth. */
	private static JsonObject summary(Ledger ledger, ChatLogger chat, long now) {
		JsonObject s = new JsonObject();
		List<Profile> all = new ArrayList<>(ledger.all());
		s.addProperty("players", all.size());

		JsonObject byVerdict = new JsonObject();
		for (Profile.Verdict v : Profile.Verdict.values()) {
			byVerdict.addProperty(v.name(), (int) all.stream().filter(p -> p.verdict == v).count());
		}
		s.add("playersByVerdict", byVerdict);

		long firstSeen = all.stream().mapToLong(p -> p.firstSeen).filter(t -> t > 0).min().orElse(0);
		long lastSeen = all.stream().mapToLong(p -> p.lastSeen).max().orElse(0);
		if (firstSeen > 0) s.addProperty("earliestFirstSeenIso", Instant.ofEpochMilli(firstSeen).toString());
		if (lastSeen > 0) s.addProperty("latestSeenIso", Instant.ofEpochMilli(lastSeen).toString());

		List<Profile.Line> feed = chat.feed();
		s.addProperty("chatLines", feed.size());
		if (!feed.isEmpty()) {
			long from = feed.stream().mapToLong(l -> l.time).filter(t -> t > 0).min().orElse(0);
			long to = feed.stream().mapToLong(l -> l.time).max().orElse(0);
			if (from > 0) s.addProperty("chatFromIso", Instant.ofEpochMilli(from).toString());
			if (to > 0) s.addProperty("chatToIso", Instant.ofEpochMilli(to).toString());
		}

		// The busiest chatters, by messages counted.
		JsonArray talkers = new JsonArray();
		all.stream().filter(p -> p.stats.messages > 0)
				.sorted(Comparator.comparingInt((Profile p) -> p.stats.messages).reversed())
				.limit(10).forEach(p -> {
					JsonObject o = new JsonObject();
					o.addProperty("name", p.name);
					o.addProperty("messages", p.stats.messages);
					o.addProperty("words", p.stats.words);
					talkers.add(o);
				});
		s.add("mostMessages", talkers);
		return s;
	}

	private static JsonObject groups(Ledger ledger) {
		JsonObject groups = new JsonObject();
		for (Map.Entry<String, List<Profile>> e : ledger.groups().entrySet()) {
			JsonArray names = new JsonArray();
			for (Profile p : e.getValue()) names.add(p.name);
			groups.add(e.getKey(), names);
		}
		return groups;
	}

	/** A rough size in human terms, for the button that warns before copying a very big export to the clipboard. */
	static String sizeWord(int chars) {
		if (chars < 1000) return chars + " characters";
		if (chars < 1_000_000) return String.format(Locale.ROOT, "%.0f KB", chars / 1024.0);
		return String.format(Locale.ROOT, "%.1f MB", chars / 1_048_576.0);
	}
}
