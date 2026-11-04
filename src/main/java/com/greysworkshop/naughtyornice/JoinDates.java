package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When did each player first join this server? Minecraft doesn't say, but many servers have a command for it
 * (Constantiam's is {@code /joindate <player>}; others use {@code /seen}, {@code /playtime}, a Discord bot, ...). This
 * runs whatever command you set (Settings &rarr; Your data), one player at a time so the server never kicks you for
 * spam, and reads the date out of the reply. The reply format is read generically, so it isn't tied to one server:
 * ISO dates, "14 March 2021", "Mar 14, 2021", "03/14/2021", and relative times like "joined 2 years ago" are all
 * understood, and the date is pinned to the player the reply is about.
 */
public final class JoinDates {
	private JoinDates() {}

	/** How often a queued command is sent (ms): slow enough that no server treats it as spam. */
	private static final long EVERY = 2500;
	/** A reply counts as answering the last asked player for this long after asking. */
	private static final long ANSWER_WINDOW = 10_000;

	/** Players still to ask, oldest first. */
	private static final Deque<UUID> queue = new ArrayDeque<>();
	private static long lastSent;
	/** The player the last command asked about, and when, so an unnamed reply can still be pinned to them. */
	private static UUID awaiting;
	private static long awaitingSince;
	private static int done, total;
	/** What the last fetch is doing, for the settings screen. */
	public static volatile String status;

	// ---- Sending the command for each player ----

	/** Queues a join-date lookup for everyone on this server who hasn't got one yet. Needs you to be on the server. */
	public static void fetchAll(MinecraftClient client, Ledger ledger) {
		if (client.getNetworkHandler() == null) {
			status = "Join the server first: the command is sent to it.";
			return;
		}
		queue.clear();
		for (Profile p : ledger.all()) {
			if (p.joinDate == 0 && !p.placeholder && p.name != null && p.name.matches("[A-Za-z0-9_]{1,16}")) queue.add(p.id);
		}
		done = 0;
		total = queue.size();
		status = total == 0 ? "Every player already has a join date." : "Looking up " + total + (total == 1 ? " player…" : " players…");
	}

	/** Whether a lookup is under way. */
	public static boolean running() {
		return !queue.isEmpty() || awaiting != null;
	}

	public static String progress() {
		return status;
	}

	/** Each tick: sends the next command when enough time has passed, and forgets a reply that never came. */
	public static void tick(MinecraftClient client, Ledger ledger) {
		long now = System.currentTimeMillis();
		if (awaiting != null && now - awaitingSince > ANSWER_WINDOW) awaiting = null;
		if (queue.isEmpty() || client.getNetworkHandler() == null || client.player == null) {
			if (queue.isEmpty() && awaiting == null && total > 0 && status != null && status.startsWith("Looking up")) {
				status = "Done: read " + done + " of " + total + " join dates.";
				total = 0;
			}
			return;
		}
		if (awaiting != null && now - awaitingSince < EVERY) return;
		if (now - lastSent < EVERY) return;
		UUID id = queue.peek();
		Profile p = ledger.get(id);
		if (p == null || p.joinDate != 0) {
			queue.poll();
			return;
		}
		String command = NNConfig.INSTANCE.joinDateCommand.replace("{player}", p.name).trim();
		if (command.isEmpty()) {
			status = "Set a command first (Settings → Your data).";
			queue.clear();
			return;
		}
		if (command.startsWith("/")) client.getNetworkHandler().sendChatCommand(command.substring(1));
		else client.getNetworkHandler().sendChatMessage(command);
		queue.poll();
		awaiting = id;
		awaitingSince = now;
		lastSent = now;
		status = "Looking up join dates… " + (done) + "/" + total;
	}

	// ---- Reading the date out of a reply ----

	/** A server message: if it carries a join date, pins it to the player it's about. Returns true if one was read. */
	public static boolean onMessage(MinecraftClient client, Ledger ledger, String line) {
		if (line == null || line.isBlank()) return false;
		boolean asked = awaiting != null && System.currentTimeMillis() - awaitingSince <= ANSWER_WINDOW;
		// Only read a date from a reply we asked for, or from a line that's clearly about joining. Otherwise ordinary
		// chat that happens to mention a date (e.g. "see you 2021-03-14") must never set anyone's join date.
		if (!asked && !JOIN_WORDS.matcher(line).find()) return false;
		long when = parseDate(line, System.currentTimeMillis());
		if (when == 0) return false;
		// Who is it about? A listed player named in the line wins; otherwise the player we just asked about.
		Profile who = playerIn(ledger, line);
		if (who == null && asked) who = ledger.get(awaiting);
		if (who == null) return false;
		who.joinDate = when;
		who.joinDateText = line.length() > 200 ? line.substring(0, 200) : line;
		if (awaiting != null && who.id.equals(awaiting)) {
			awaiting = null;
			done++;
		}
		return true;
	}

	/** A listed player whose name appears as a whole word in the line, or null. */
	private static Profile playerIn(Ledger ledger, String line) {
		String lower = line.toLowerCase(Locale.ROOT);
		Profile best = null;
		for (Profile p : ledger.all()) {
			if (p.name == null || p.name.length() < 3) continue;
			String name = p.name.toLowerCase(Locale.ROOT);
			int at = lower.indexOf(name);
			if (at < 0) continue;
			boolean leftEdge = at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1)) && lower.charAt(at - 1) != '_';
			int end = at + name.length();
			boolean rightEdge = end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end)) && lower.charAt(end) != '_';
			if (leftEdge && rightEdge && (best == null || p.name.length() > best.name.length())) best = p;
		}
		return best;
	}

	private static final String[] MONTHS = {"january", "february", "march", "april", "may", "june", "july", "august",
			"september", "october", "november", "december"};

	private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");
	private static final Pattern SLASH_YMD = Pattern.compile("\\b(\\d{4})/(\\d{1,2})/(\\d{1,2})\\b");
	private static final Pattern MONTH_DMY = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+([A-Za-z]{3,9})\\.?,?\\s+(\\d{4})\\b");
	private static final Pattern MONTH_MDY = Pattern.compile("\\b([A-Za-z]{3,9})\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b");
	private static final Pattern NUMERIC = Pattern.compile("\\b(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})\\b");
	private static final Pattern RELATIVE = Pattern.compile("(\\d+)\\s*(year|yr|month|week|day|hour|minute|min)s?\\s+ago");
	/** Words that mark a line as being about when someone joined, so an unasked line can be trusted. */
	private static final Pattern JOIN_WORDS = Pattern.compile("(?i)\\b(join|joined|first\\s*(seen|join|login|played)|member\\s*since|playing\\s*since|registered|first\\s*time|since)\\b");

	/**
	 * The join date in a line as epoch milliseconds (UTC midnight for a date, or {@code now} minus the span for a
	 * relative time), or 0 if there's no date. Tries the clear formats first (ISO, month names) before the ambiguous
	 * numeric one, and relative times last.
	 */
	public static long parseDate(String line, long now) {
		Matcher m;
		if ((m = ISO.matcher(line)).find()) {
			long t = ymd(num(m, 1), num(m, 2), num(m, 3));
			if (t != 0) return t;
		}
		if ((m = SLASH_YMD.matcher(line)).find()) {
			long t = ymd(num(m, 1), num(m, 2), num(m, 3));
			if (t != 0) return t;
		}
		if ((m = MONTH_DMY.matcher(line)).find()) {
			int mon = month(m.group(2));
			if (mon > 0) {
				long t = ymd(num(m, 3), mon, num(m, 1));
				if (t != 0) return t;
			}
		}
		if ((m = MONTH_MDY.matcher(line)).find()) {
			int mon = month(m.group(1));
			if (mon > 0) {
				long t = ymd(num(m, 3), mon, num(m, 2));
				if (t != 0) return t;
			}
		}
		if ((m = NUMERIC.matcher(line)).find()) {
			int a = num(m, 1), b = num(m, 2), y = num(m, 3);
			if (y < 100) y += y < 70 ? 2000 : 1900;
			// Day vs month: whichever can't be a month is the day; otherwise assume month/day/year.
			int mon, day;
			if (a > 12 && b <= 12) {
				day = a;
				mon = b;
			} else {
				mon = a;
				day = b;
			}
			long t = ymd(y, mon, day);
			if (t != 0) return t;
		}
		if ((m = RELATIVE.matcher(line.toLowerCase(Locale.ROOT))).find()) {
			long n = Long.parseLong(m.group(1));
			long days = switch (m.group(2)) {
				case "year", "yr" -> Math.round(n * 365.25);
				case "month" -> Math.round(n * 30.44);
				case "week" -> n * 7;
				case "day" -> n;
				default -> 0; // hours/minutes: same day
			};
			return now - days * 86_400_000L;
		}
		return 0;
	}

	private static int num(Matcher m, int g) {
		try {
			return Integer.parseInt(m.group(g));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static int month(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		for (int i = 0; i < MONTHS.length; i++) {
			if (MONTHS[i].startsWith(n) || n.startsWith(MONTHS[i].substring(0, 3))) return i + 1;
		}
		return 0;
	}

	/** Midnight UTC of a valid year-month-day as epoch ms, or 0 if the date isn't real or sensible. */
	private static long ymd(int y, int mon, int day) {
		if (y < 2009 || y > 2100 || mon < 1 || mon > 12 || day < 1) return 0; // Minecraft began in 2009.
		if (day > YearMonth.of(y, mon).lengthOfMonth()) return 0;
		return LocalDate.of(y, mon, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
	}

	/** For the self-test: clears the queue and the awaited player. */
	static void reset() {
		queue.clear();
		awaiting = null;
		done = 0;
		total = 0;
		status = null;
	}

	/** For the self-test: pretend the last command asked about {@code id}. */
	static void testAwait(UUID id) {
		awaiting = id;
		awaitingSince = System.currentTimeMillis();
	}
}
