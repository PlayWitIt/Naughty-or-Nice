package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything that happens around you, as a log you can go back over: chat, joins and leaves, deaths (killer, weapon,
 * cause), every hit (who, on whom, with what, health after), arm swings, totem pops, shots (arrows, tridents, pearls,
 * potions, fireworks, with who fired them), end crystals (who set one off), explosions, gear changes, and where
 * everyone in view was, every second.
 * <p>
 * Always on as a "black box": the last 30 minutes are kept, so after something happens you can still save it. A
 * recording (started by you) writes straight to a file as it goes, one line per entry (JSON Lines), so it survives a
 * crash and opens in anything. Saved per server in config/naughtyornice/recordings/&lt;server&gt;/.
 */
public final class EventLog {
	/** Unknown coordinates are NaN, which plain JSON doesn't allow; written as NaN and read back the same. */
	private static final Gson GSON = new com.google.gson.GsonBuilder().serializeSpecialFloatingPointValues().create();
	/** How long the black box keeps, and its most entries. */
	public static final long BLACK_BOX_MS = 30 * 60_000L;
	private static final int BLACK_BOX_MAX = 400_000;

	public enum Type {
		CHAT, WHISPER, JOIN, LEAVE, DEATH, HIT, SWING, TOTEM, SHOT, CRYSTAL_PLACED, CRYSTAL_SET_OFF, EXPLOSION, GEAR, POSITION, ADVANCEMENT, NOTE
	}

	/** One thing that happened. Coordinates are NaN when not known (it happened out of view). */
	public static final class Entry {
		public long t;
		public Type type;
		/** Who did it (a player's name, or "" for no one/unknown). */
		public String actor = "";
		/** Who or what it was done to (a player's name, or a kind of mob or block). */
		public String target = "";
		public String dim = "";
		public double x = Double.NaN, y = Double.NaN, z = Double.NaN;
		/** The rest, in words: what was said, the weapon, the damage type, the item... */
		public String detail = "";
		/** The target's health afterwards, as the server shows it (some servers hide it); -1 if not known. */
		public float health = -1;

		public boolean located() {
			return !Double.isNaN(x);
		}
	}

	private static final ArrayDeque<Entry> blackBox = new ArrayDeque<>();
	private static Writer recording;
	private static Path recordingFile;
	private static String recordingName;
	private static long recordingSince;
	private static int recordedCount;
	private static long lastFlush;

	private EventLog() {}

	public static Entry entry(Type type, String actor, String target, String detail) {
		Entry e = new Entry();
		e.t = System.currentTimeMillis();
		e.type = type;
		e.actor = actor == null ? "" : actor;
		e.target = target == null ? "" : target;
		e.detail = detail == null ? "" : detail;
		return e;
	}

	/** Keeps an entry: in the black box, and in the recording if one is running. */
	public static void add(Entry e) {
		if (!NNConfig.INSTANCE.eventLog || !NNConfig.on()) return;
		blackBox.addLast(e);
		while (blackBox.size() > BLACK_BOX_MAX || !blackBox.isEmpty() && e.t - blackBox.peekFirst().t > BLACK_BOX_MS) blackBox.removeFirst();
		if (recording != null) {
			try {
				recording.write(GSON.toJson(e));
				recording.write('\n');
				recordedCount++;
				if (e.t - lastFlush > 1000) {
					recording.flush();
					lastFlush = e.t;
				}
			} catch (IOException | RuntimeException ex) {
				// Never the game: just this recording stops.
				NaughtyOrNiceClient.LOGGER.error("Could not write to the recording", ex);
				recording = null;
			}
		}
	}

	public static Path folder() {
		Ledger ledger = NaughtyOrNiceClient.INSTANCE.ledger;
		String server = ledger.key() == null ? "unknown" : ledger.key().replaceAll("[^A-Za-z0-9._-]", "_");
		return NNConfig.dir().resolve("recordings").resolve(server);
	}

	private static String fileName(String name) {
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss", Locale.ROOT));
		String clean = name == null ? "" : name.strip().replaceAll("[^A-Za-z0-9 ._-]", "_");
		return stamp + (clean.isEmpty() ? "" : " " + clean) + ".jsonl";
	}

	public static boolean recording() {
		return recording != null;
	}

	public static String recordingName() {
		return recordingName;
	}

	public static long recordingSince() {
		return recordingSince;
	}

	public static int recordedCount() {
		return recordedCount;
	}

	/** Starts writing everything to a new file (with the last minute of the black box first, for context). */
	public static String startRecording(String name) {
		if (recording != null) return "Already recording \"" + recordingName + "\"";
		try {
			Files.createDirectories(folder());
			recordingFile = folder().resolve(fileName(name));
			recording = new BufferedWriter(Files.newBufferedWriter(recordingFile));
			recordingName = name == null || name.isBlank() ? "Recording" : name.strip();
			recordingSince = System.currentTimeMillis();
			recordedCount = 0;
			Writer w = recording;
			for (Entry e : blackBox) {
				if (recordingSince - e.t <= 60_000) {
					w.write(GSON.toJson(e));
					w.write('\n');
					recordedCount++;
				}
			}
			add(entry(Type.NOTE, "", "", "Recording started: " + recordingName));
			return null;
		} catch (IOException e) {
			recording = null;
			return "Couldn't start recording: " + e.getMessage();
		}
	}

	public static Path stopRecording() {
		if (recording == null) return null;
		Path file = recordingFile;
		try {
			add(entry(Type.NOTE, "", "", "Recording stopped"));
			recording.close();
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not close the recording", e);
		}
		recording = null;
		return file;
	}

	/** Saves the last {@code minutes} of the black box to a file. Returns it, or null if there was nothing. */
	public static Path saveLast(int minutes, String name) throws IOException {
		long since = System.currentTimeMillis() - minutes * 60_000L;
		List<Entry> list = new ArrayList<>();
		for (Entry e : blackBox) {
			if (e.t >= since) list.add(e);
		}
		if (list.isEmpty()) return null;
		Files.createDirectories(folder());
		Path file = folder().resolve(fileName(name == null || name.isBlank() ? "Last " + minutes + " minutes" : name));
		try (Writer w = new BufferedWriter(Files.newBufferedWriter(file))) {
			for (Entry e : list) {
				w.write(GSON.toJson(e));
				w.write('\n');
			}
		}
		return file;
	}

	/** Every saved recording for this server, newest first. */
	public static List<Path> recordings() {
		List<Path> list = new ArrayList<>();
		if (!Files.isDirectory(folder())) return list;
		try (var files = Files.list(folder())) {
			files.filter(f -> f.getFileName().toString().endsWith(".jsonl")).forEach(list::add);
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.warn("Could not list recordings", e);
		}
		list.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
		return list;
	}

	/** A recording's entries, in order. Lines that can't be read (a crash mid-line) are skipped. */
	public static List<Entry> load(Path file) throws IOException {
		List<Entry> list = new ArrayList<>();
		try (BufferedReader r = Files.newBufferedReader(file)) {
			String line;
			while ((line = r.readLine()) != null) {
				try {
					Entry e = GSON.fromJson(line, Entry.class);
					if (e != null && e.type != null) {
						if (e.actor == null) e.actor = "";
						if (e.target == null) e.target = "";
						if (e.detail == null) e.detail = "";
						if (e.dim == null) e.dim = "";
						list.add(e);
					}
				} catch (RuntimeException ignored) {
					// A half-written last line.
				}
			}
		}
		return list;
	}

	/** The black box's entries from {@code since} on, oldest first (cheap: reads back from the newest). */
	public static List<Entry> since(long since) {
		List<Entry> list = new ArrayList<>();
		var it = blackBox.descendingIterator();
		while (it.hasNext()) {
			Entry e = it.next();
			if (e.t < since) break;
			list.add(e);
		}
		java.util.Collections.reverse(list);
		return list;
	}

	/** The black box as it is now (the last 30 minutes). */
	public static List<Entry> blackBox() {
		return new ArrayList<>(blackBox);
	}

	/** Writes entries as CSV (opens in any spreadsheet). */
	public static void exportCsv(List<Entry> entries, Path file) throws IOException {
		DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
		try (Writer w = new BufferedWriter(Files.newBufferedWriter(file))) {
			w.write("time,type,actor,target,dimension,x,y,z,health,detail\n");
			for (Entry e : entries) {
				String when = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(e.t), java.time.ZoneId.systemDefault()).format(time);
				w.write(String.join(",", when, e.type.name(), csv(e.actor), csv(e.target), csv(e.dim),
						e.located() ? String.format(Locale.ROOT, "%.1f", e.x) : "", e.located() ? String.format(Locale.ROOT, "%.1f", e.y) : "",
						e.located() ? String.format(Locale.ROOT, "%.1f", e.z) : "", e.health >= 0 ? String.format(Locale.ROOT, "%.1f", e.health) : "",
						csv(e.detail)));
				w.write('\n');
			}
		}
	}

	private static String csv(String s) {
		if (s == null) return "";
		return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\"" : s;
	}
}
