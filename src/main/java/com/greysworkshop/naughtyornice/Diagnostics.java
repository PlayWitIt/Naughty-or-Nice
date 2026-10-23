package com.greysworkshop.naughtyornice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * How well the mod understands this server's chat: counts of what it recognised, a few examples of each, and every
 * kind of line it couldn't make sense of. Servers word things differently; this is how to find out what was missed
 * and send it in so the mod can learn it.
 */
public final class Diagnostics {
	/** What a chat line was read as. */
	public enum Kind {
		CHAT("Chat"), WHISPER("Private messages"), KILL("Kills"), DEATH("Other deaths"), JOIN_LEAVE("Joins and leaves"),
		IGNORED("Server messages (fine to ignore)"), UNKNOWN("Not understood");

		public final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	/** One kind of line not understood, with how often it came and a few real examples. */
	public static final class Shape {
		public final String shape;
		public int count;
		public final List<String> examples = new ArrayList<>();
		public long last;

		Shape(String shape) {
			this.shape = shape;
		}
	}

	/** A line that was understood, and what was read from it. */
	public record Sample(Kind kind, String raw, String reading, long time) {}

	private static final int MAX_SHAPES = 300;
	private static final int MAX_SAMPLES = 12;
	private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

	private final Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
	private final Map<String, Shape> unknown = new LinkedHashMap<>();
	private final Map<Kind, Deque<Sample>> samples = new EnumMap<>(Kind.class);
	private int version;

	public void understood(Kind kind, String raw, String reading) {
		counts.merge(kind, 1, Integer::sum);
		Deque<Sample> list = samples.computeIfAbsent(kind, k -> new ArrayDeque<>());
		list.addLast(new Sample(kind, raw, reading, System.currentTimeMillis()));
		while (list.size() > MAX_SAMPLES) list.removeFirst();
		version++;
	}

	/** A line nothing matched. {@code names} are player names to blank out, so lines of the same kind group together. */
	public void notUnderstood(String raw, Iterable<String> names) {
		if (raw == null || raw.isBlank()) return;
		counts.merge(Kind.UNKNOWN, 1, Integer::sum);
		String shape = shape(raw, names);
		Shape s = unknown.get(shape);
		if (s == null) {
			if (unknown.size() >= MAX_SHAPES) {
				// Make room: drop the rarest.
				String rarest = unknown.values().stream().min(Comparator.comparingInt((Shape x) -> x.count).thenComparingLong(x -> x.last))
						.map(x -> x.shape).orElse(null);
				if (rarest != null) unknown.remove(rarest);
			}
			s = new Shape(shape);
			unknown.put(shape, s);
		}
		s.count++;
		s.last = System.currentTimeMillis();
		if (s.examples.size() < 3 && !s.examples.contains(raw)) s.examples.add(raw.length() > 300 ? raw.substring(0, 300) + "…" : raw);
		version++;
	}

	/** The line with player names as {name} and numbers as #. */
	static String shape(String raw, Iterable<String> names) {
		String s = raw.length() > 300 ? raw.substring(0, 300) : raw;
		for (String name : names) {
			if (name.length() >= 3) s = s.replaceAll("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])", "{name}");
		}
		return NUMBER.matcher(s).replaceAll("#");
	}

	public int count(Kind kind) {
		return counts.getOrDefault(kind, 0);
	}

	public int total() {
		int n = 0;
		for (int c : counts.values()) n += c;
		return n;
	}

	/** Kinds of line not understood, most frequent first. */
	public List<Shape> unknown() {
		List<Shape> list = new ArrayList<>(unknown.values());
		list.sort(Comparator.comparingInt((Shape s) -> s.count).reversed());
		return list;
	}

	public List<Sample> samples(Kind kind) {
		Deque<Sample> list = samples.get(kind);
		return list == null ? List.of() : new ArrayList<>(list);
	}

	public int version() {
		return version;
	}

	public void clear() {
		counts.clear();
		unknown.clear();
		samples.clear();
		version++;
	}

	/** A plain-text report to copy or save and send in. */
	public String report(String server) {
		StringBuilder sb = new StringBuilder();
		String nl = System.lineSeparator();
		sb.append("Naughty or Nice chat check · ").append(Sharing.placeName(server)).append(" · ").append(LocalDateTime.now().format(STAMP)).append(nl);
		sb.append("Lines seen: ").append(total()).append(nl);
		for (Kind kind : Kind.values()) sb.append("  ").append(kind.label).append(": ").append(count(kind)).append(nl);
		sb.append(nl).append("== Not understood (most common first) ==").append(nl);
		List<Shape> shapes = unknown();
		if (shapes.isEmpty()) sb.append("(none)").append(nl);
		for (Shape s : shapes) {
			sb.append(s.count).append("×  ").append(s.shape).append(nl);
			for (String example : s.examples) sb.append("      e.g. ").append(example).append(nl);
		}
		sb.append(nl).append("== Understood (latest few of each, to check they were read right) ==").append(nl);
		for (Kind kind : Kind.values()) {
			if (kind == Kind.UNKNOWN) continue;
			List<Sample> list = samples(kind);
			if (list.isEmpty()) continue;
			sb.append("-- ").append(kind.label).append(nl);
			for (Sample sample : list) sb.append("  ").append(sample.raw()).append(nl).append("      read as: ").append(sample.reading()).append(nl);
		}
		sb.append(nl).append("== Chunk reading on this server ==").append(nl);
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		if (mod != null && mod.ledger != null) sb.append(chunkReading(mod.ledger.chunkTrails.reading, nl));
		sb.append(nl).append("== Disconnects (newest first) ==").append(nl).append(Disconnects.report());
		sb.append(nl).append("== Errors the mod caught and skipped ==").append(nl);
		List<Safe.Caught> caught = Safe.caught();
		if (caught.isEmpty()) sb.append("(none)").append(nl);
		for (Safe.Caught c : caught) sb.append(c.count).append("×  ").append(c.where).append(": ").append(c.what).append(nl);
		return sb.toString();
	}

	/** The two chunk readings compared, and what that says about the palette reading on this server. */
	static String chunkReading(ChunkTrails.Reading r, String nl) {
		StringBuilder sb = new StringBuilder();
		sb.append("By palette: ").append(r.paletteNew).append(" new, ").append(r.paletteOld).append(" old, ").append(r.paletteUndecided).append(" undecided").append(nl);
		sb.append("By liquid: ").append(r.liquidNew).append(" new, ").append(r.liquidOld).append(" old").append(nl);
		int both = r.agreeNew + r.agreeOld + r.disagree;
		sb.append("Read both ways: ").append(both).append(" (agree: ").append(r.agreeNew).append(" new, ").append(r.agreeOld).append(" old; disagree: ")
				.append(r.disagree).append(")").append(nl);
		for (String d : r.disagreements) sb.append("  ").append(d).append(nl);
		if (r.disagree > 0) {
			sb.append("They disagree, so the palette reading may not work on this server: send this report in.").append(nl);
		} else if (both >= 20) {
			sb.append("They agree on every chunk both could read: the palette reading works here.").append(nl);
		} else {
			sb.append("Too few chunks read both ways to tell yet (it needs land with water or lava, away from you).").append(nl);
		}
		return sb.toString();
	}

	/** Saves the report to config/naughtyornice/chat-check; returns the file. */
	public Path save(String server) throws IOException {
		Path dir = NNConfig.dir().resolve("chat-check");
		Files.createDirectories(dir);
		String name = ("chat-check-" + Sharing.placeName(server) + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)))
				.replaceAll("[^A-Za-z0-9._-]", "_");
		Path file = dir.resolve(name + ".txt");
		Files.writeString(file, report(server));
		return file;
	}
}
