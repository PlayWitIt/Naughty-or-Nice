package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Private messages, one conversation per player, kept per server between sessions (the DMs tab). Every whisper you
 * get or send goes in, however it was sent (the DMs box, /msg, /r, another mod). The first time a server's
 * conversations are opened, whispers already in its chat logs are read in too.
 *
 * Sending from the DMs box puts the line in at once as "sending"; the server's echo ("You whisper to …") confirms it,
 * and an error from the server ("No player was found") marks it not sent.
 */
public final class DirectMessages {
	public static final int SENDING = 1, FAILED = 2;
	/** Lines kept per conversation (oldest go first). */
	static final int MAX_PER_THREAD = 5000;
	/** A sent line waits this long for the server's echo, then counts as sent anyway (some servers echo in a way we don't read). */
	static final long ECHO_WAIT = 6000;

	public static final class Message {
		public long time;
		public boolean me;
		public String text = "";
		/** 0 sent or received, {@link #SENDING}, {@link #FAILED}. */
		public int state;
		/** Why it wasn't sent (the server's words), when {@link #FAILED}. */
		public String error;
		/** When the server's echo confirmed it (0 if it hasn't); a second echo of the same line soon after is the same line. */
		transient long confirmed;
	}

	public static final class Thread {
		/** Their name, as the server last wrote it. */
		public String name = "";
		public List<Message> messages = new ArrayList<>();
		/** Everything up to this time has been read. */
		public long readUpTo;

		public long last() {
			return messages.isEmpty() ? 0 : messages.get(messages.size() - 1).time;
		}

		public int unread() {
			int n = 0;
			for (int i = messages.size() - 1; i >= 0 && messages.get(i).time > readUpTo; i--) if (!messages.get(i).me) n++;
			return n;
		}
	}

	private static final class Saved {
		/** Whispers in the chat logs have been read in. */
		boolean imported;
		List<Thread> threads = new ArrayList<>();
	}

	private static final Gson GSON = new GsonBuilder().create();
	/** Server error replies to a /msg that didn't go through. */
	private static final Pattern NOT_SENT = Pattern.compile("(?i)(no player (was )?found|player not found|not online|isn'?t online|is offline|"
			+ "could not find|couldn'?t find|unknown player|can'?t (message|whisper)|cannot (message|whisper)|is ignoring you|has you ignored|"
			+ "doesn'?t accept|has (private )?messages (disabled|off)|not accepting)");

	private final Ledger ledger;
	/** Lower-case name → conversation. */
	private final Map<String, Thread> threads = new LinkedHashMap<>();
	private boolean imported;
	private boolean dirty;
	private int version;
	private String loadedFor;
	private CompletableFuture<List<String[]>> importing;
	/** The conversation open on screen right now (its lines count as read as they come). */
	private String viewing;

	public DirectMessages(Ledger ledger) {
		this.ledger = ledger;
	}

	private static String key(String name) {
		return name.strip().toLowerCase(Locale.ROOT);
	}

	/** config/naughtyornice/chatlogs/&lt;server&gt;/messages.json */
	private Path file() {
		return NaughtyOrNiceClient.INSTANCE.chatLogger.folder().resolve("messages.json");
	}

	// ---- Loading and saving ----

	/** The server just switched (or the game just started): its conversations, or none. */
	public void load() {
		threads.clear();
		imported = false;
		dirty = false;
		importing = null;
		viewing = null;
		version++;
		loadedFor = ledger.key();
		if (loadedFor == null) return;
		Path file = file();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				Saved saved = GSON.fromJson(reader, Saved.class);
				if (saved != null) {
					imported = saved.imported;
					if (saved.threads != null) {
						for (Thread t : saved.threads) {
							if (t == null || t.name == null || t.name.isBlank()) continue;
							if (t.messages == null) t.messages = new ArrayList<>();
							t.messages.removeIf(m -> m == null || m.text == null);
							// Still "sending" from last time: the game closed before the echo; it went.
							for (Message m : t.messages) if (m.state == SENDING) m.state = 0;
							threads.put(key(t.name), t);
						}
					}
				}
			} catch (IOException | RuntimeException e) {
				NaughtyOrNiceClient.LOGGER.error("Could not read {}", file, e);
			}
		}
		if (!imported) startImport();
	}

	public void save(boolean force) {
		if (ledger.key() == null || !ledger.key().equals(loadedFor)) return;
		if (!dirty && !force) return;
		if (threads.isEmpty() && !imported) return;
		Saved saved = new Saved();
		saved.imported = imported;
		saved.threads = new ArrayList<>(threads.values().stream().filter(t -> !t.messages.isEmpty()).toList());
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling("messages.json.tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(saved, writer);
			}
			Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not write {}", file, e);
		}
	}

	// ---- Reading in whispers from before ----

	private static final Pattern FILE_IN = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)\\] ([.*]?[A-Za-z0-9_]{1,16}) whispered to you: (.*)$");
	private static final Pattern FILE_OUT = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)\\] You whispered to ([.*]?[A-Za-z0-9_]{1,16}): (.*)$");

	/** Whispers in the all-chat files and the Chat tab's saved lines, read in the background, then merged in. */
	private void startImport() {
		Path allChat = NaughtyOrNiceClient.INSTANCE.chatLogger.folder().resolve("all-chat");
		List<Profile.Line> feed = NaughtyOrNiceClient.INSTANCE.chatLogger.feed();
		String forKey = loadedFor;
		importing = CompletableFuture.supplyAsync(() -> {
			List<String[]> found = new ArrayList<>();
			if (Files.isDirectory(allChat)) {
				ZoneId zone = ZoneId.systemDefault();
				try (Stream<Path> files = Files.list(allChat)) {
					for (Path f : files.sorted().toList()) {
						String name = f.getFileName().toString();
						if (!name.matches("\\d{4}-\\d\\d-\\d\\d\\.txt")) continue;
						LocalDate day = LocalDate.parse(name.substring(0, 10));
						for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
							Matcher in = FILE_IN.matcher(l), out = FILE_OUT.matcher(l);
							Matcher m = in.matches() ? in : out.matches() ? out : null;
							if (m == null) continue;
							long time = day.atTime(LocalTime.parse(m.group(1))).atZone(zone).toInstant().toEpochMilli();
							found.add(new String[] {m.group(2), m == out ? "1" : "0", m.group(3), Long.toString(time)});
						}
					}
				} catch (IOException | RuntimeException e) {
					NaughtyOrNiceClient.LOGGER.warn("Could not read old whispers from {}", allChat, e);
				}
			}
			for (Profile.Line line : feed) {
				if (line.whisper && !line.system && line.sender != null && !line.sender.isEmpty()) {
					found.add(new String[] {line.sender, line.me ? "1" : "0", line.text, Long.toString(line.time)});
				}
			}
			return found;
		});
		importing.thenAccept(found -> MinecraftClient.getInstance().execute(() -> {
			if (!java.util.Objects.equals(forKey, loadedFor) || importing == null) return;
			importing = null;
			merge(found);
		}));
	}

	/** Adds whispers from before, skipping any already in (same person, same second, same words). */
	void merge(List<String[]> found) {
		Set<String> have = new HashSet<>();
		for (Map.Entry<String, Thread> e : threads.entrySet()) {
			for (Message m : e.getValue().messages) have.add(e.getKey() + "|" + m.time / 1000 + "|" + m.me + "|" + m.text);
		}
		int added = 0;
		Set<Thread> touched = new HashSet<>();
		for (String[] f : found) {
			String k = key(f[0]);
			boolean me = f[1].equals("1");
			long time = Long.parseLong(f[3]);
			if (!have.add(k + "|" + time / 1000 + "|" + me + "|" + f[2])) continue;
			Thread t = threads.computeIfAbsent(k, x -> {
				Thread n = new Thread();
				n.name = f[0];
				return n;
			});
			Message m = new Message();
			m.time = time;
			m.me = me;
			m.text = f[2];
			t.messages.add(m);
			// Old ones aren't news.
			t.readUpTo = Math.max(t.readUpTo, time);
			touched.add(t);
			added++;
		}
		for (Thread t : touched) {
			t.messages.sort(Comparator.comparingLong(m -> m.time));
			while (t.messages.size() > MAX_PER_THREAD) t.messages.remove(0);
		}
		imported = true;
		dirty = true;
		version++;
		if (added > 0) NaughtyOrNiceClient.LOGGER.info("Read in {} old whispers for the DMs tab", added);
	}

	/** Reads the chat logs' whispers in again (the self-test). */
	void testImport() {
		startImport();
	}

	public boolean importing() {
		return importing != null;
	}

	// ---- Lines coming in ----

	/**
	 * A whisper, from them or from you (any way it was sent). {@code partner} is the other player. Your own line
	 * confirms one sent from the DMs box rather than adding it twice.
	 */
	public void heard(String partner, boolean me, String text, long now) {
		if (partner == null || partner.isBlank() || ledger.key() == null) return;
		Thread t = threads.get(key(partner));
		if (t == null) {
			t = new Thread();
			threads.put(key(partner), t);
		}
		t.name = partner;
		if (me) {
			// The server's echo of one we sent: the oldest still waiting.
			for (Message m : t.messages) {
				if (m.me && m.state == SENDING) {
					m.state = 0;
					m.text = text;
					m.confirmed = now;
					changed();
					return;
				}
			}
			// The same line echoed twice (a whisper to yourself comes back both ways).
			for (int i = t.messages.size() - 1; i >= 0 && i >= t.messages.size() - 4; i--) {
				Message m = t.messages.get(i);
				if (m.me && m.text.equals(text) && m.confirmed > 0 && now - m.confirmed < 3000) return;
			}
		}
		Message m = new Message();
		m.time = now;
		m.me = me;
		m.text = text;
		if (me) m.confirmed = now;
		t.messages.add(m);
		while (t.messages.size() > MAX_PER_THREAD) t.messages.remove(0);
		// Yours are read; theirs are read if you're looking at them.
		if (me || key(partner).equals(viewing)) t.readUpTo = Math.max(t.readUpTo, now);
		changed();
		if (!me) replyHint(t.name);
	}

	/** In game with nothing open: the bar above the hotbar says how to answer. */
	private static void replyHint(String name) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!NNConfig.INSTANCE.dmReplyHint || client.currentScreen != null || client.inGameHud == null) return;
		String key = NaughtyOrNiceClient.INSTANCE.messagesKeyName();
		if (key.isBlank() || key.equalsIgnoreCase("Not Bound")) return;
		client.inGameHud.setOverlayMessage(Text.literal("✉ " + name).formatted(net.minecraft.util.Formatting.LIGHT_PURPLE)
				.append(Text.literal("  ·  " + key + " to reply").formatted(net.minecraft.util.Formatting.GRAY)), false);
	}

	/** A line sent from the DMs box, waiting for the server's echo. */
	public Message sending(String partner, String text, long now) {
		Thread t = threads.computeIfAbsent(key(partner), k -> new Thread());
		t.name = partner;
		Message m = new Message();
		m.time = now;
		m.me = true;
		m.text = text;
		m.state = SENDING;
		t.messages.add(m);
		t.readUpTo = Math.max(t.readUpTo, now);
		changed();
		return m;
	}

	/** It never left (the send guard held it back): out of the conversation again. */
	public void unsend(String partner, Message m) {
		Thread t = threads.get(key(partner));
		if (t != null && t.messages.remove(m)) changed();
	}

	/** A message from the server: if a line was just sent and this says it couldn't be, that line is marked not sent. */
	public void serverSaid(Text message) {
		String raw = message.getString();
		boolean notFound = message.getContent() instanceof TranslatableTextContent t
				&& (t.getKey().startsWith("argument.entity.notfound") || t.getKey().equals("argument.player.unknown"));
		if (!notFound && !NOT_SENT.matcher(raw).find()) return;
		long now = System.currentTimeMillis();
		Message newest = null;
		for (Thread t : threads.values()) {
			for (int i = t.messages.size() - 1; i >= 0 && i >= t.messages.size() - 6; i--) {
				Message m = t.messages.get(i);
				if (m.me && m.state == SENDING && now - m.time < ECHO_WAIT && (newest == null || m.time < newest.time)) newest = m;
			}
		}
		if (newest == null) return;
		newest.state = FAILED;
		newest.error = raw.length() > 120 ? raw.substring(0, 120) + "…" : raw;
		changed();
	}

	/** Lines still waiting for an echo after a while: the server took them (nothing said otherwise). */
	public void tick(long now) {
		for (Thread t : threads.values()) {
			for (int i = t.messages.size() - 1; i >= 0 && i >= t.messages.size() - 10; i--) {
				Message m = t.messages.get(i);
				if (m.state == SENDING && now - m.time > ECHO_WAIT) {
					m.state = 0;
					changed();
				}
			}
		}
	}

	private void changed() {
		dirty = true;
		version++;
	}

	// ---- For the DMs tab ----

	public int version() {
		return version;
	}

	/** Conversations, the latest first. */
	public List<Thread> threads() {
		List<Thread> list = new ArrayList<>(threads.values());
		list.sort(Comparator.comparingLong(Thread::last).reversed());
		return list;
	}

	public Thread thread(String name) {
		return name == null ? null : threads.get(key(name));
	}

	/** Opens (or starts) a conversation with them, for the DMs tab. */
	public Thread start(String name) {
		Thread t = threads.computeIfAbsent(key(name), k -> {
			Thread n = new Thread();
			n.name = name;
			return n;
		});
		return t;
	}

	/** On screen now (null when not): its new lines count as read. */
	public void viewing(String name) {
		viewing = name == null ? null : key(name);
		if (viewing != null) markRead(name);
	}

	public void markRead(String name) {
		Thread t = thread(name);
		if (t != null && t.last() > t.readUpTo) {
			t.readUpTo = t.last();
			changed();
		}
	}

	public int unread() {
		int n = 0;
		for (Thread t : threads.values()) n += t.unread();
		return n;
	}

	/** Who to open when you press the DMs key: the newest unread conversation, else the newest. */
	public String latest() {
		Thread best = null;
		for (Thread t : threads()) {
			if (t.messages.isEmpty()) continue;
			if (t.unread() > 0) return t.name;
			if (best == null) best = t;
		}
		return best == null ? null : best.name;
	}

	public void delete(String name) {
		if (threads.remove(key(name)) != null) changed();
	}

	/** Messages split so each fits in one command: "/msg name " plus the text stays within the game's 256 letters. */
	public static List<String> split(String text, int room) {
		List<String> parts = new ArrayList<>();
		String rest = text.strip();
		room = Math.max(20, room);
		while (rest.length() > room) {
			int cut = rest.lastIndexOf(' ', room);
			if (cut < room / 2) cut = room;
			parts.add(rest.substring(0, cut).strip());
			rest = rest.substring(cut).strip();
		}
		if (!rest.isEmpty()) parts.add(rest);
		return parts;
	}

	/** A conversation as text, to copy. */
	public static String format(String server, Thread t) {
		StringBuilder sb = new StringBuilder("Private messages with ").append(t.name).append(" on ").append(server).append(System.lineSeparator());
		for (Message m : t.messages) {
			Profile.Line line = new Profile.Line();
			line.time = m.time;
			line.text = m.text;
			line.me = m.me;
			line.whisper = true;
			line.sender = t.name;
			sb.append(ChatLogger.formatLine(line)).append(m.state == FAILED ? "  (not sent)" : "").append(System.lineSeparator());
		}
		return sb.toString();
	}
}
