package com.greysworkshop.naughtyornice;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Records conversations involving listed players. When someone on your Nice, Naughty or Look Into list says something, a
 * conversation opens on their profile, seeded with the chat from just before (so you can see what they were
 * answering). Everything said afterwards, by anyone, goes into it until they've been quiet for
 * {@link NNConfig#chatQuietSeconds}; then it's closed, trimmed, and appended to their text log.
 */
public final class ChatLogger {
	/** A conversation stops taking other people's lines past this size (theirs are always kept). */
	private static final int MAX_LINES = 600;
	/** Other people's lines after their last message are kept for this long once the conversation closes. */
	private static final long TAIL_MILLIS = 60_000;
	private static final DateTimeFormatter FILE_DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ROOT);
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

	private final Ledger ledger;
	/** Everything heard lately, for context when a conversation opens. */
	private final Deque<Profile.Line> recent = new ArrayDeque<>();
	private final Map<UUID, Profile.Conversation> open = new LinkedHashMap<>();
	/**
	 * Everything said on this server lately (up to {@link #FEED_MAX} lines, kept {@link NNConfig#chatHistoryDays} days,
	 * saved between sessions), for the Chat tab.
	 */
	private final Deque<Profile.Line> feed = new ArrayDeque<>();
	public static final int FEED_MAX = 3000;
	private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().create();
	private static final DateTimeFormatter DAY_FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
	/** The feed has lines not saved yet. */
	private boolean feedDirty;
	/** Lines waiting to go into today's all-chat text file. */
	private final List<Profile.Line> unwritten = new ArrayList<>();
	/** Goes up with every line added to the feed, so the Chat tab knows when to redo its layout. */
	private int feedVersion;

	public ChatLogger(Ledger ledger) {
		this.ledger = ledger;
	}

	private static boolean logs(Profile profile) {
		return NNConfig.INSTANCE.chatLog && profile != null && profile.verdict != Profile.Verdict.NONE;
	}

	public boolean isOpen(Profile profile) {
		return open.containsKey(profile.id);
	}

	/**
	 * A line of chat. {@code owner} is the other player it involves: the sender, or for a whisper you sent, who
	 * you sent it to (null if neither is someone we know).
	 */
	public void heard(Profile owner, String sender, String text, boolean me, boolean whisper, long now) {
		heard(owner, sender, text, me, whisper, null, now);
	}

	/** As above; {@code alert} is why the line set off an alert (your name, a base's coordinates), or null. */
	public void heard(Profile owner, String sender, String text, boolean me, boolean whisper, String alert, long now) {
		Profile.Line line = new Profile.Line();
		line.time = now;
		line.sender = sender;
		line.text = text;
		line.me = me;
		line.whisper = whisper;
		line.alert = alert;
		remember(line, now);
		toFeed(line);

		// Everyone's lines go into every open conversation; a private message only into the one it involves.
		for (Map.Entry<UUID, Profile.Conversation> entry : open.entrySet()) {
			Profile profile = ledger.get(entry.getKey());
			if (profile == null) continue;
			if (whisper && owner != profile) continue;
			add(profile, entry.getValue(), line);
		}
		if (logs(owner) && !open.containsKey(owner.id)) start(owner, now, now);
		ledger.markDirty();
	}

	/** A join/leave note for a player with an open conversation. */
	public void system(Profile profile, String text, long now) {
		Profile.Conversation conversation = profile == null ? null : open.get(profile.id);
		if (conversation == null) return;
		Profile.Line line = new Profile.Line();
		line.time = now;
		line.sender = profile.name;
		line.text = text;
		line.system = true;
		conversation.lines.add(line);
		ledger.markDirty();
	}

	/** A join or leave for the Chat tab, for anyone. */
	public void feedSystem(String name, String text, long now) {
		Profile.Line line = new Profile.Line();
		line.time = now;
		line.sender = name;
		line.text = text;
		line.system = true;
		toFeed(line);
	}

	private void toFeed(Profile.Line line) {
		feed.addLast(line);
		while (feed.size() > FEED_MAX) feed.removeFirst();
		feedVersion++;
		feedDirty = true;
		if (NNConfig.INSTANCE.chatAllFiles) unwritten.add(line);
	}

	/** A divider in the Chat tab ("you joined"): a system line with no sender. */
	public void feedMarker(String text, long now) {
		Profile.Line line = new Profile.Line();
		line.time = now;
		line.sender = "";
		line.text = text;
		line.system = true;
		toFeed(line);
	}

	/** Whether a line is a divider rather than something someone said or did. */
	public static boolean isMarker(Profile.Line line) {
		return line.system && line.sender.isEmpty();
	}

	// ---- Keeping the Chat tab between sessions ----

	/** config/naughtyornice/chatlogs/&lt;server&gt;/chat-tab.json */
	private Path feedFile() {
		return folder().resolve("chat-tab.json");
	}

	/** Loads the Chat tab's lines from last time (within the days kept), for the server just joined. */
	public void loadFeed() {
		feed.clear();
		unwritten.clear();
		feedDirty = false;
		feedVersion++;
		if (ledger.key() == null) return;
		Path file = feedFile();
		if (!Files.exists(file)) return;
		try (java.io.Reader reader = Files.newBufferedReader(file)) {
			List<Profile.Line> lines = GSON.fromJson(reader, new com.google.gson.reflect.TypeToken<List<Profile.Line>>() {}.getType());
			if (lines == null) return;
			long oldest = System.currentTimeMillis() - NNConfig.INSTANCE.chatHistoryDays * 86_400_000L;
			for (Profile.Line line : lines) {
				if (line == null || line.text == null || line.time < oldest) continue;
				if (line.sender == null) line.sender = "";
				feed.addLast(line);
			}
			while (feed.size() > FEED_MAX) feed.removeFirst();
		} catch (IOException | RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not read {}", file, e);
		}
	}

	/** Saves the Chat tab's lines, and adds new ones to today's all-chat file. */
	public void saveFeed(boolean force) {
		if (ledger.key() == null) return;
		writeAllChat();
		if (!feedDirty) return;
		Path file = feedFile();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(new ArrayList<>(feed), writer);
			}
			feedDirty = false;
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not write {}", file, e);
		}
	}

	/** config/naughtyornice/chatlogs/&lt;server&gt;/all-chat/2026-10-21.txt: everything said, a file a day. */
	private void writeAllChat() {
		if (unwritten.isEmpty()) return;
		ZoneId zone = ZoneId.systemDefault();
		Map<String, StringBuilder> byDay = new LinkedHashMap<>();
		for (Profile.Line line : unwritten) {
			String day = DAY_FILE.format(Instant.ofEpochMilli(line.time).atZone(zone));
			StringBuilder sb = byDay.computeIfAbsent(day, d -> new StringBuilder());
			sb.append('[').append(FILE_TIME.format(Instant.ofEpochMilli(line.time).atZone(zone))).append("] ");
			sb.append(isMarker(line) ? "--- " + line.text + " ---" : describe(line)).append(System.lineSeparator());
		}
		unwritten.clear();
		for (Map.Entry<String, StringBuilder> entry : byDay.entrySet()) {
			Path file = folder().resolve("all-chat").resolve(entry.getKey() + ".txt");
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				NaughtyOrNiceClient.LOGGER.error("Could not write {}", file, e);
			}
		}
	}

	/** The Chat tab's lines, oldest first. */
	public List<Profile.Line> feed() {
		return new ArrayList<>(feed);
	}

	public int feedVersion() {
		return feedVersion;
	}

	private void remember(Profile.Line line, long now) {
		recent.addLast(line);
		long keep = Math.max(NNConfig.INSTANCE.chatContextSeconds, NNConfig.INSTANCE.chatQuietSeconds) * 1000L;
		while (!recent.isEmpty() && (now - recent.peekFirst().time > keep || recent.size() > 300)) recent.removeFirst();
	}

	private static boolean said(Profile profile, Profile.Line line) {
		return !line.me && !line.system && line.sender.equalsIgnoreCase(profile.name);
	}

	private void add(Profile profile, Profile.Conversation conversation, Profile.Line heard) {
		Profile.Line line = heard.copy();
		line.them = said(profile, line);
		if (!line.them && conversation.lines.size() >= MAX_LINES) return;
		conversation.lines.add(line);
		if (line.them || (line.me && line.whisper)) conversation.lastSpoke = line.time;
	}

	/** Opens a conversation, seeded with what was said from {@code firstSpoke} minus the context window. */
	private void start(Profile profile, long firstSpoke, long now) {
		Profile.Conversation conversation = new Profile.Conversation();
		conversation.open = true;
		long from = firstSpoke - NNConfig.INSTANCE.chatContextSeconds * 1000L;
		for (Profile.Line line : recent) {
			if (line.time < from) continue;
			// Private messages belong only to the conversation with whoever sent or received them.
			if (line.whisper && !line.sender.equalsIgnoreCase(profile.name)) continue;
			add(profile, conversation, line);
		}
		if (conversation.lastSpoke == 0) conversation.lastSpoke = now;
		conversation.start = conversation.lines.isEmpty() ? now : conversation.lines.get(0).time;
		profile.conversations.add(conversation);
		open.put(profile.id, conversation);
	}

	/** Someone's list changed: start logging them (catching up on what they just said), or stop. */
	public void verdictChanged(Profile profile) {
		if (!logs(profile)) {
			Profile.Conversation conversation = open.remove(profile.id);
			if (conversation != null) close(profile, conversation);
			return;
		}
		if (open.containsKey(profile.id)) return;
		long now = System.currentTimeMillis();
		long quiet = NNConfig.INSTANCE.chatQuietSeconds * 1000L;
		long first = 0;
		for (Profile.Line line : recent) {
			if (said(profile, line) && now - line.time <= quiet) {
				first = line.time;
				break;
			}
		}
		if (first > 0) start(profile, first, now);
	}

	/** Closes conversations whose player has been quiet long enough. */
	public void tick(long now) {
		long quiet = NNConfig.INSTANCE.chatQuietSeconds * 1000L;
		Iterator<Map.Entry<UUID, Profile.Conversation>> it = open.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Profile.Conversation> entry = it.next();
			Profile profile = ledger.get(entry.getKey());
			if (profile == null) {
				it.remove();
				continue;
			}
			if (now - entry.getValue().lastSpoke < quiet) continue;
			it.remove();
			close(profile, entry.getValue());
		}
	}

	/** Closes everything (leaving a server, or the game closing). */
	public void closeAll() {
		saveFeed(true);
		for (Map.Entry<UUID, Profile.Conversation> entry : List.copyOf(open.entrySet())) {
			Profile profile = ledger.get(entry.getKey());
			if (profile != null) close(profile, entry.getValue());
		}
		open.clear();
		recent.clear();
		feed.clear();
		unwritten.clear();
		feedDirty = false;
		feedVersion++;
	}

	/** Forgets a player's open conversation without writing it anywhere (they were forgotten). */
	public void drop(Profile profile) {
		open.remove(profile.id);
	}

	private void close(Profile profile, Profile.Conversation conversation) {
		conversation.open = false;
		// Drop the chatter that carried on after they'd finished, beyond a short tail of replies.
		long cutoff = conversation.lastSpoke + TAIL_MILLIS;
		conversation.lines.removeIf(line -> line.time > cutoff && !line.them);
		if (conversation.lines.stream().noneMatch(line -> line.them || (line.me && line.whisper))) {
			profile.conversations.remove(conversation);
			return;
		}
		conversation.end = conversation.lines.get(conversation.lines.size() - 1).time;
		prune(profile);
		if (NNConfig.INSTANCE.chatFiles) writeFile(profile, conversation);
		ledger.markDirty();
	}

	/** Applies the keep limits to a player's in-game log. */
	public static void prune(Profile profile) {
		NNConfig config = NNConfig.INSTANCE;
		long oldest = System.currentTimeMillis() - config.chatKeepDays * 86_400_000L;
		profile.conversations.removeIf(c -> !c.open && c.end < oldest);
		while (profile.conversations.size() > config.chatKeepConversations) {
			Profile.Conversation first = profile.conversations.get(0);
			if (first.open) break;
			profile.conversations.remove(0);
		}
	}

	// ---- Text files ----

	/** config/naughtyornice/chatlogs/&lt;server&gt;/ */
	public Path folder() {
		String key = ledger.key() == null ? "unknown" : ledger.key();
		return NNConfig.dir().resolve("chatlogs").resolve(key.replaceAll("[^A-Za-z0-9._-]", "_"));
	}

	public Path file(Profile profile) {
		return folder().resolve(profile.name.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt");
	}

	private void writeFile(Profile profile, Profile.Conversation conversation) {
		Path file = file(profile);
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				writer.write(format(profile, conversation));
				writer.write(System.lineSeparator());
			}
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not write {}", file, e);
		}
	}

	/** A conversation as plain text, for the file and the Copy button. */
	public static String format(Profile profile, Profile.Conversation conversation) {
		ZoneId zone = ZoneId.systemDefault();
		String list = switch (profile.verdict) {
			case NICE -> "Nice list";
			case NAUGHTY -> "Naughty list";
			case LOOK -> "Look Into list";
			case FRIEND -> "Friend: " + Friends.circleName(profile);
			case NONE -> "not listed";
		};
		StringBuilder sb = new StringBuilder();
		sb.append("=== ").append(profile.name).append(" (").append(list).append(") · ")
				.append(FILE_DAY.format(Instant.ofEpochMilli(conversation.start).atZone(zone))).append(", ")
				.append(FILE_TIME.format(Instant.ofEpochMilli(conversation.start).atZone(zone))).append(" to ")
				.append(FILE_TIME.format(Instant.ofEpochMilli(conversation.open ? System.currentTimeMillis() : conversation.end).atZone(zone)))
				.append(" ===").append(System.lineSeparator());
		for (Profile.Line line : conversation.lines) {
			sb.append('[').append(FILE_TIME.format(Instant.ofEpochMilli(line.time).atZone(zone))).append("] ");
			sb.append(describe(line)).append(System.lineSeparator());
		}
		return sb.toString();
	}

	/**
	 * Chat tab lines as plain text, to copy and send in: each with its date and time, which list the speaker is on,
	 * and any alert it set off. {@code listOf} gives a speaker's list ("" for none).
	 */
	public static String formatFeed(String server, String shown, List<Profile.Line> lines, java.util.function.Function<Profile.Line, String> listOf) {
		ZoneId zone = ZoneId.systemDefault();
		String nl = System.lineSeparator();
		StringBuilder sb = new StringBuilder();
		sb.append("Naughty or Nice chat log · ").append(server).append(" · ").append(shown).append(nl);
		for (Profile.Line line : lines) {
			if (isMarker(line)) {
				sb.append("--- ").append(line.text).append(" ---").append(nl);
				continue;
			}
			var at = Instant.ofEpochMilli(line.time).atZone(zone);
			sb.append('[').append(FILE_DAY.format(at)).append(' ').append(FILE_TIME.format(at)).append("] ").append(describe(line));
			String list = listOf.apply(line);
			if (!list.isEmpty()) sb.append("   [").append(list).append(']');
			if (line.alert != null) sb.append("   (alert: ").append(line.alert).append(')');
			sb.append(nl);
		}
		return sb.toString();
	}

	/** "&lt;Name&gt; text", "Name whispers: text", "* Name left the game"... */
	public static String describe(Profile.Line line) {
		if (line.system) return "* " + line.sender + " " + line.text;
		String who = line.me ? "You" : line.sender;
		if (line.whisper) return line.me ? who + " whispered to " + line.sender + ": " + line.text : who + " whispered to you: " + line.text;
		return "<" + who + "> " + line.text;
	}

	/** Open conversations, for the self-test. */
	public List<UUID> openIds() {
		return new ArrayList<>(open.keySet());
	}
}
