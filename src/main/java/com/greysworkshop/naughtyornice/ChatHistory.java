package com.greysworkshop.naughtyornice;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Everything a player has said, read back from the all-chat files (a file a day, everything said on the server), so a
 * player's page shows all of it, not just what was logged after they went on a list. Read in the background; each
 * day's file is read again only when it has changed (today's grows as you play).
 */
public final class ChatHistory {
	private static final Pattern CHAT = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)\\] <([A-Za-z0-9_]{1,16})> (.*)$");
	private static final Pattern WHISPER_IN = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)\\] ([A-Za-z0-9_]{1,16}) whispered to you: (.*)$");
	private static final Pattern WHISPER_OUT = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)\\] You whispered to ([A-Za-z0-9_]{1,16}): (.*)$");

	/** One day's file as last read for one player: its size and time, and their lines in it. */
	private record Read(long size, long modified, List<Profile.Line> lines) {}

	/** What's been read, per player (lower-case names, joined) and file. */
	private final Map<String, Map<Path, Read>> reads = new HashMap<>();
	/** The last full answer per player, shown while a fresher one is read. */
	private final Map<String, List<Profile.Line>> last = new HashMap<>();
	private final Map<String, CompletableFuture<List<Profile.Line>>> reading = new HashMap<>();
	private final Map<String, Long> askedAt = new HashMap<>();

	/**
	 * What they've said (and whispered to you, and you to them), oldest first, or null while it's first being read.
	 * Asks again for changes at most every few seconds.
	 */
	public synchronized List<Profile.Line> said(Path allChat, Set<String> names) {
		String key = String.join("|", names.stream().sorted().toList());
		CompletableFuture<List<Profile.Line>> f = reading.get(key);
		if (f != null && f.isDone()) {
			reading.remove(key);
			List<Profile.Line> got = f.getNow(null);
			if (got != null) last.put(key, got);
		}
		long now = System.currentTimeMillis();
		if (!reading.containsKey(key) && now - askedAt.getOrDefault(key, 0L) > 5000) {
			askedAt.put(key, now);
			Map<Path, Read> files = reads.computeIfAbsent(key, k -> new HashMap<>());
			reading.put(key, CompletableFuture.supplyAsync(() -> read(allChat, names, files)));
		}
		return last.get(key);
	}

	private static List<Profile.Line> read(Path allChat, Set<String> names, Map<Path, Read> files) {
		List<Path> days = new ArrayList<>();
		if (Files.isDirectory(allChat)) {
			try (Stream<Path> s = Files.list(allChat)) {
				s.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d\\d-\\d\\d\\.txt")).sorted().forEach(days::add);
			} catch (IOException e) {
				NaughtyOrNiceClient.LOGGER.warn("Could not list {}", allChat, e);
			}
		}
		List<Profile.Line> out = new ArrayList<>();
		for (Path day : days) {
			try {
				long size = Files.size(day), modified = Files.getLastModifiedTime(day).toMillis();
				Read r;
				synchronized (files) {
					r = files.get(day);
				}
				if (r == null || r.size != size || r.modified != modified) {
					r = new Read(size, modified, readDay(day, names));
					synchronized (files) {
						files.put(day, r);
					}
				}
				out.addAll(r.lines);
			} catch (IOException e) {
				NaughtyOrNiceClient.LOGGER.warn("Could not read {}", day, e);
			}
		}
		return out;
	}

	static List<Profile.Line> readDay(Path day, Set<String> names) throws IOException {
		LocalDate date = LocalDate.parse(day.getFileName().toString().substring(0, 10));
		ZoneId zone = ZoneId.systemDefault();
		List<Profile.Line> lines = new ArrayList<>();
		try (BufferedReader reader = Files.newBufferedReader(day, StandardCharsets.UTF_8)) {
			String l;
			while ((l = reader.readLine()) != null) {
				Profile.Line line = parse(l, names);
				if (line == null) continue;
				line.time = date.atTime(LocalTime.parse(l.substring(1, 9))).atZone(zone).toInstant().toEpochMilli();
				lines.add(line);
			}
		} catch (RuntimeException e) {
			// A line the mod didn't write (edited by hand): skip the rest of this file rather than show it wrong.
			NaughtyOrNiceClient.LOGGER.warn("Could not read {}", day, e);
		}
		return lines;
	}

	/** A line of the all-chat file, if it's said by (or whispered between you and) one of {@code names}. Time is left 0. */
	static Profile.Line parse(String l, Set<String> names) {
		Matcher m = CHAT.matcher(l);
		Profile.Line line = new Profile.Line();
		if (m.matches() && names.contains(m.group(2).toLowerCase(Locale.ROOT))) {
			line.sender = m.group(2);
			line.text = m.group(3);
			line.them = true;
			return line;
		}
		m = WHISPER_IN.matcher(l);
		if (m.matches() && names.contains(m.group(2).toLowerCase(Locale.ROOT))) {
			line.sender = m.group(2);
			line.text = m.group(3);
			line.whisper = true;
			line.them = true;
			return line;
		}
		m = WHISPER_OUT.matcher(l);
		if (m.matches() && names.contains(m.group(2).toLowerCase(Locale.ROOT))) {
			line.sender = m.group(2);
			line.text = m.group(3);
			line.whisper = true;
			line.me = true;
			return line;
		}
		return null;
	}
}
