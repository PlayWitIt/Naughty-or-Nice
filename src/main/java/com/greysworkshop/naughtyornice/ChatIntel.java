package com.greysworkshop.naughtyornice;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one chat line for what it gives away: coordinates (written any of the ways players write them), what it's
 * about (stashes, griefing, duping, threats...), and whether it's friendly or trash talk. Built to be sure rather
 * than eager: a line only counts when its wording leaves no real doubt, so ordinary talk ("I have 1000 diamonds and
 * 200 gold", "taking out the trash", "welcome to hell") never does. Plain Java, no game classes, so it can be tested
 * on its own.
 */
public final class ChatIntel {
	private ChatIntel() {}

	// ---- Coordinates ----

	public enum CoordKind {
		/** "x: 1200 z: -400", "X/Z 1200 -400". */
		LABELED,
		/** "1200 64 -400": three numbers, the middle one a height. */
		XYZ,
		/** "1200 -400" with something in the line saying it's a place. */
		PAIR,
		/** "-x 20k", "+x+z highway 5000": a distance along a highway. */
		HIGHWAY
	}

	/** A place someone wrote down. {@code y} is {@link Integer#MIN_VALUE} when they didn't give a height. */
	public record Coord(int x, int y, int z, String dimension, CoordKind kind, int start, int end) {
		public boolean hasY() {
			return y != Integer.MIN_VALUE;
		}

		/** "1200, 64, -400" or "1200, -400". */
		public String text() {
			return hasY() ? x + ", " + y + ", " + z : x + ", " + z;
		}
	}

	/** Bigger than this isn't a place in a Minecraft world. */
	private static final double WORLD = 30_000_000;

	/** A number as players write them: 1200, -3400, +500, 12k, 1.5k, -12.5k. */
	private static final String NUM = "([+-]?(?:\\d+(?:\\.\\d+)?\\s?k|\\d{1,3}(?:,\\d{3})+(?![\\d,])|\\d{1,8}(?:\\.\\d+)?))";
	private static final String SEP = "\\s*[,;/|]\\s*|\\s+";
	private static final Pattern NUMBER = Pattern.compile("(?<![\\w.+-])" + NUM + "(?![\\w.])");
	private static final Pattern LABELED = Pattern.compile("(?<![a-z0-9_])x\\s*[:=]?\\s*" + NUM + "(?:\\s*[,;/]?\\s*y\\s*[:=]?\\s*" + NUM + ")?\\s*[,;/]?\\s*z\\s*[:=]?\\s*" + NUM + "(?![\\w.])");
	private static final Pattern LABEL_FIRST = Pattern.compile("(?<![a-z0-9_])(?:x\\s*/?\\s*y\\s*/?\\s*z|x\\s*/?\\s*z|coords?|cords?|coordinates)\\s*(?:are|is|=|:)?\\s*:?\\s*" + NUM
			+ "(?:" + SEP + ")" + NUM + "(?:(?:" + SEP + ")" + NUM + ")?(?![\\w.])");
	private static final Pattern HIGHWAY = Pattern.compile("(?<![\\w+-])([+-])\\s?([xz])(?:\\s?([+-])\\s?([xz]))?\\s+(?:(?:highway|hwy|hw)\\s+)?(?:(?:at|@)\\s*)?"
			+ "(\\d+(?:\\.\\d+)?\\s?k|\\d{3,8})(?![\\w.])");
	/** Words in a line that say its numbers are a place. */
	private static final Pattern PLACE_WORDS = Pattern.compile("\\b(coords?|cords?|coordinates|location|located|base|stash|spawn|portal|highway|hwy|nether|overworld|"
			+ "ow|village|outpost|mansion|monument|stronghold|meet|tp|tpa|waypoint|xz|is at|im at|i'm at|we're at|were at|"
			+ "go to|come to|head to|heading to|travel to)\\b");
	/** "at 1200 -400", "@ 1200 -400": a place word right before the numbers. */
	private static final Pattern AT_BEFORE = Pattern.compile("(?:\\bat|@|\\bto|\\bnear|\\baround)\\s*$");
	/** After the numbers, these say they're amounts, not a place. */
	private static final Pattern UNIT_AFTER = Pattern.compile("^\\s*(?:x\\b|%|\\$|€|£|ms\\b|fps\\b|tps\\b|ping\\b|hp\\b|xp\\b|lvls?\\b|levels?\\b|dmg\\b|iq\\b|"
			+ "blocks?\\s+(?:away|out|long|tall|wide|deep|high)|diamonds?|dias?|netherite|gold|iron|emeralds?|items?|stacks?|shulkers?|dubs?|chests?|totems?|"
			+ "crystals?|gapples?|pearls?|xp|bottles?|kills?|deaths?|players?|people|members|views|subs|followers|hours?|hrs?|h\\b|mins?|minutes?|seconds?|secs?|"
			+ "days?|weeks?|months?|years?|yrs?|times|ticks?|chunks?|bucks|dollars?|usd|eur|euros?|pounds?|coins?|points?|pts|votes?|messages?|msgs?|"
			+ "ppl|mb|gb|kb|ram|k/d|kd|fov|render|rd|per\\b|each\\b|a piece|apiece|for\\b)");
	/** Nothing but these around the numbers means the message is just the numbers ("1200 -3400 lol"). */
	private static final Pattern FILLER = Pattern.compile("[^a-z0-9]*(?:(?:lol|lmao|rn|pls|plz|please|ok|okay|here|bro|ty|thx|hurry|quick|asap)[^a-z0-9]*)*");
	private static final Pattern MONEY_BEFORE = Pattern.compile("[$€£]\\s*$");
	private static final Pattern NETHER = Pattern.compile("\\b(nether|neth|the_nether|in the nether)\\b");
	private static final Pattern OVERWORLD = Pattern.compile("\\b(overworld|over world|ow)\\b");
	private static final Pattern END = Pattern.compile("\\b(the end|end city|end cities|end portal|outer end|end island)\\b");

	/** Lower case, with the dashes and spaces chat clients swap in made plain. */
	static String normal(String text) {
		return text.toLowerCase(Locale.ROOT).replace('\u2212', '-').replace('\u2013', '-').replace('\u2014', '-').replace('\u00a0', ' ');
	}

	/** Every place written in the line, surest kinds first, none counted twice. */
	public static List<Coord> coords(String text) {
		String s = normal(text);
		List<Coord> found = new ArrayList<>();
		String dim = dimension(s);

		Matcher m = LABELED.matcher(s);
		while (m.find()) {
			Double x = value(m.group(1)), z = value(m.group(3));
			Double y = m.group(2) == null ? null : value(m.group(2));
			if (x == null || z == null) continue;
			add(found, x, y, z, dim, CoordKind.LABELED, m.start(), m.end());
		}
		m = LABEL_FIRST.matcher(s);
		while (m.find()) {
			if (overlaps(found, m.start(), m.end())) continue;
			Double a = value(m.group(1)), b = value(m.group(2));
			Double c = m.group(3) == null ? null : value(m.group(3));
			if (a == null || b == null) continue;
			if (c != null) {
				if (plausibleY(b)) add(found, a, b, c, dim, CoordKind.LABELED, m.start(), m.end());
			} else {
				add(found, a, null, b, dim, CoordKind.LABELED, m.start(), m.end());
			}
		}
		m = HIGHWAY.matcher(s);
		while (m.find()) {
			if (overlaps(found, m.start(), m.end())) continue;
			Double d = value(m.group(5));
			if (d == null || d < 100) continue;
			double x = 0, z = 0;
			for (int g = 1; g <= 3; g += 2) {
				if (m.group(g) == null) continue;
				double v = m.group(g).equals("-") ? -d : d;
				if (m.group(g + 1).equals("x")) x = v;
				else z = v;
			}
			add(found, x, null, z, dim, CoordKind.HIGHWAY, m.start(), m.end());
		}

		// Bare numbers: runs of numbers with nothing but separators between them.
		List<int[]> spans = new ArrayList<>();
		List<String> raw = new ArrayList<>();
		m = NUMBER.matcher(s);
		while (m.find()) {
			spans.add(new int[] {m.start(), m.end()});
			raw.add(m.group(1));
		}
		boolean placeWords = PLACE_WORDS.matcher(s).find() || dim != null;
		int i = 0;
		while (i < spans.size()) {
			int j = i;
			while (j + 1 < spans.size() && separatorOnly(s.substring(spans.get(j)[1], spans.get(j + 1)[0]))) j++;
			int run = j - i + 1;
			int start = spans.get(i)[0], end = spans.get(j)[1];
			if (!overlaps(found, start, end) && (run == 2 || run == 3) && !amountAround(s, start, end)) {
				Double a = value(raw.get(i)), b = value(raw.get(i + 1));
				Double c = run == 3 ? value(raw.get(i + 2)) : null;
				String before = s.substring(0, start);
				boolean alone = FILLER.matcher(before).matches() && FILLER.matcher(s.substring(end)).matches();
				if (run == 3 && a != null && b != null && c != null && plausibleY(b) && sized(a, c) && !date(s, spans, i)
						&& (placeWords || AT_BEFORE.matcher(before).find() || alone && Math.max(Math.abs(a), Math.abs(c)) >= 1000
								|| a < 0 || c < 0 || s.substring(start, end).contains("/") || !integral(a) || !integral(c))) {
					add(found, a, b, c, dim, CoordKind.XYZ, start, end);
				} else if (run == 2 && a != null && b != null && sized(a, b) && !notAPlace(a, b)
						&& (placeWords || AT_BEFORE.matcher(before).find() || alone && (Math.max(Math.abs(a), Math.abs(b)) >= 1000 || a < 0 || b < 0))) {
					add(found, a, null, b, dim, CoordKind.PAIR, start, end);
				}
			}
			i = j + 1;
		}
		found.sort(java.util.Comparator.comparingInt(Coord::start));
		return found;
	}

	private static void add(List<Coord> found, double x, Double y, double z, String dim, CoordKind kind, int start, int end) {
		if (Math.abs(x) > WORLD || Math.abs(z) > WORLD) return;
		// "0 0" is spawn, but a pair of tiny numbers is rarely a place someone's giving away.
		if (kind == CoordKind.PAIR && Math.abs(x) < 100 && Math.abs(z) < 100) return;
		found.add(new Coord((int) Math.round(x), y == null ? Integer.MIN_VALUE : (int) Math.round(y), (int) Math.round(z), dim, kind, start, end));
	}

	private static boolean overlaps(List<Coord> found, int start, int end) {
		for (Coord c : found) {
			if (start < c.end() && end > c.start()) return true;
		}
		return false;
	}

	private static boolean separatorOnly(String between) {
		return between.matches("\\s*[,;/|]\\s*|\\s+");
	}

	/** A world coordinate at least 100 out (or a "k" number), both of them. */
	private static boolean sized(double a, double b) {
		return Math.abs(a) >= 100 || Math.abs(b) >= 100;
	}

	static boolean integral(double v) {
		return v == Math.rint(v);
	}

	private static boolean plausibleY(double y) {
		return y >= -64 && y <= 320;
	}

	/** Pairs that are something else: two years, a screen size. */
	private static boolean notAPlace(double a, double b) {
		if (a >= 1900 && a <= 2100 && b >= 1900 && b <= 2100) return true;
		int[][] screens = {{1920, 1080}, {2560, 1440}, {3840, 2160}, {1280, 720}, {1366, 768}, {1600, 900}, {1440, 900}, {1680, 1050}, {2560, 1080}, {3440, 1440}};
		for (int[] r : screens) {
			if (a == r[0] && b == r[1]) return true;
		}
		return false;
	}

	/** "10/21/2026", "21/10/26": a date, not a place. */
	private static boolean date(String s, List<int[]> spans, int i) {
		String between = s.substring(spans.get(i)[1], spans.get(i + 1)[0]);
		if (!between.contains("/") && !between.contains("-")) return false;
		String last = s.substring(spans.get(i + 2)[0], spans.get(i + 2)[1]);
		return last.matches("(19|20)?\\d{2}");
	}

	/** Money before the numbers, or a unit after them ("1000 200 diamonds", "$500 600"). */
	private static boolean amountAround(String s, int start, int end) {
		return MONEY_BEFORE.matcher(s.substring(0, start)).find() || UNIT_AFTER.matcher(s.substring(end)).find();
	}

	/** A number's value ("12k" is 12000), or null if it isn't one. */
	static Double value(String n) {
		if (n == null) return null;
		String t = n.replace(" ", "").replace(",", "");
		double mult = 1;
		if (t.endsWith("k")) {
			mult = 1000;
			t = t.substring(0, t.length() - 1);
		}
		try {
			return Double.parseDouble(t) * mult;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** The dimension the line names: "minecraft:the_nether", "minecraft:overworld", "minecraft:the_end", or null. */
	static String dimension(String s) {
		boolean nether = NETHER.matcher(s).find(), over = OVERWORLD.matcher(s).find(), end = END.matcher(s).find();
		int n = (nether ? 1 : 0) + (over ? 1 : 0) + (end ? 1 : 0);
		// Both named ("1000 2000 in the nether, so 8000 16000 in the ow"): not sure which goes with which.
		if (n != 1) return null;
		return nether ? "minecraft:the_nether" : over ? "minecraft:overworld" : "minecraft:the_end";
	}

	// ---- Topics ----

	public enum Topic {
		THREAT("Threats", "threatens to grief, find, raid or kill someone"),
		STASH("Stashes & bases", "stashes, base hunting, finding bases"),
		GRIEF("Griefing", "griefing, lavacasting, withering builds"),
		DUPE("Duping", "dupes and duping"),
		HACKS("Hacks & exploits", "cheat clients, x-ray, freecam, exploits"),
		TRADE("Trading", "buying, selling, trading"),
		MEETUP("Meeting up", "meeting up, asking where someone is"),
		PVP("PvP", "fights, 1v1s, crystal PvP"),
		ALTS("Alts", "talks about alt accounts");

		public final String label;
		public final String about;

		Topic(String label, String about) {
			this.label = label;
			this.about = about;
		}
	}

	/** Stands for any known player's name in a pattern; filled in per line from the names given. */
	private static final String NAME = "@name@";
	private static final String WILL = "(?:i'?m|im|i am|we'?re|we are|i'?ll|ill|we'?ll|i will|we will|gonna|going to|about to|bout to|finna|gna|imma|ima)";
	private static final Pattern[][] TOPICS = {
			// THREAT
			{Pattern.compile("\\b" + WILL + "\\s+(?:(?:go(?:ing)?\\s+to|gonna|come\\s+and|come)\\s+)?(?:grief|raid|wither|destroy|lavacast|nuke|blow up|find|hunt|kill|dox|"
					+ "burn|wreck)\\s+(?:you|u|ya|ur|your|him|his|her|them|their)\\b"),
					Pattern.compile("\\bcoming for (?:you|u|ya|ur|your|him|them)\\b"),
					Pattern.compile("\\b(?:found|know where|i know where|we know where|got) (?:your|ur|his|her|their) (?:base|stash|house|build|coords|cords|home)\\b"),
					Pattern.compile("\\b(?:watch|sleep with one eye) (?:your back|out)\\b.*\\b(?:base|stash)\\b"),
					Pattern.compile("\\b(?:your|ur) (?:base|stash) (?:is|will be) (?:gone|dead|done|next|mine|ours)\\b")},
			// STASH
			{Pattern.compile("\\bstash(?:es|ed)?\\b"), Pattern.compile("\\bbase ?hunt(?:s|ing|er|ers|ed)?\\b"),
					Pattern.compile("\\bfound (?:a|an|another|the|your|ur|his|her|their|some|someone'?s|[a-z0-9_]{3,16}'s) (?:base|stash|build|spawner base|kit shop)\\b"),
					Pattern.compile("\\bwhere(?:'s| is|s) (?:your|ur|his|her|their|[a-z0-9_]{3,16}'s) (?:base|stash|house)\\b"),
					Pattern.compile("\\b(?:chunk ?trail|trailing|new ?chunks|stash ?finder|stash ?hunt(?:ing)?)\\b")},
			// GRIEF
			{Pattern.compile("\\bgrief(?:s|ed|ing|er|ers)?\\b"), Pattern.compile("\\blava ?cast(?:s|ed|ing)?\\b"),
					Pattern.compile("\\b(?:withered|wither(?:ing)?|blew up|blown up|blowing up|nuked|tnt(?:'d|ed)?|burned down|burnt down) (?:the|their|your|ur|his|her|my|a|an|"
							+ "[a-z0-9_]{3,16}'s) (?:base|build|stash|house|highway|hwy|portal|farm|spawn)\\b")},
			// DUPE
			{Pattern.compile("\\bdup(?:e|es|ed|ing|er|ers)\\b"), Pattern.compile("\\bduplication (?:glitch|exploit)\\b")},
			// HACKS
			{Pattern.compile("\\b(?:rusherhack|rusher hack|wurst|salhack|aristois|baritone|killaura|kill aura|autocrystal|auto crystal|crystal ?aura|elytra ?fly|"
					+ "packet ?fly|nocom|seed ?crack(?:er|ing|ed)?|x-?ray(?:ing|ed)?|freecam|free cam|esp hack|anti-?cheat bypass|bypass(?:es|ed)? the anti-?cheat)\\b"),
					Pattern.compile("\\b(?:meteor|future|impact|lambda|konas|phobos|kami|inertia|thunderhack|prestige|3arthh4ck) client\\b"),
					Pattern.compile("\\b(?:hacked client|cheat client|hack client|using hacks|uses hacks|hacking|hacker)\\b")},
			// TRADE
			{Pattern.compile("\\b(?:wts|wtb|wtt|lf trade)\\b"),
					Pattern.compile("\\b(?:selling|buying|trading|sell|buy|trade|sells|buys)\\b.*\\b(?:dubs?|shulkers?|stacks?|kits?|totems?|crystals?|gapples?|enchanted golden apples?|"
							+ "netherite|elytras?|diamonds?|dias?|beacons?|mending|books?|maps?|map ?art|spawners?|tridents?|heads?|armou?r|tools|pickaxes?|picks?|swords?)\\b"),
					Pattern.compile("\\bfor sale\\b"), Pattern.compile("\\b(?:anyone|anybody|who) (?:want|wants|wanna) to (?:buy|sell|trade)\\b"),
					Pattern.compile("\\b(?:price|paying|pay you|i'?ll pay)\\b.*\\b(?:dubs?|shulkers?|stacks?|kits?|totems?|crystals?|gapples?|netherite|elytras?|"
							+ "diamonds?|dias?)\\b")},
			// MEETUP
			{Pattern.compile("\\bmeet (?:me|up|you|u|ya|at)\\b"), Pattern.compile("\\bwhere (?:are|r) (?:you|u|ya)\\b(?!\\s+(?:from|going with|getting))"),
					Pattern.compile("\\bcome (?:to|over to) (?:my|me|spawn|the|our)\\b"), Pattern.compile("\\b(?:tpa|tp) (?:to )?(?:me|you|u)\\b"),
					Pattern.compile("\\bwhat are (?:your|ur) (?:coords|cords|coordinates)\\b")},
			// PVP
			{Pattern.compile("\\b(?:[1-5]v[1-5])\\b"), Pattern.compile("\\bcrystal ?pvp\\b"), Pattern.compile("\\b(?:fight me|come fight|duel me|wanna fight|want to fight|lets fight|let's fight)\\b"),
					Pattern.compile("\\b(?:pvp|pvping|pvp'?d)\\b")},
			// ALTS
			{Pattern.compile("\\b(?:my|his|her|their|ur|your|an|another|[a-z0-9_]{3,16}'s) alts?\\b"), Pattern.compile("\\balt accounts?\\b"),
					Pattern.compile("\\bon (?:my|his|her|their) (?:main|alt)\\b"), Pattern.compile("\\b(?:is|are) (?:an |my |his |her |their )?alts? of\\b")},
	};

	/** Threats that name a player ("gonna grief Steve's base", "coming for Steve"): only for names that are real players. */
	private static final String[] NAMED_THREATS = {
			"\\b" + WILL + "\\s+(?:(?:go(?:ing)?\\s+to|gonna|come\\s+and|come)\\s+)?(?:grief|raid|wither|destroy|lavacast|nuke|blow up|find|hunt|kill|dox|burn|wreck)\\s+"
					+ NAME + "(?:'?s)?\\b",
			"\\bcoming for " + NAME + "\\b",
			"\\b(?:found|know where|got) " + NAME + "'?s (?:base|stash|house|build|coords|cords|home)\\b"};

	/** What the line is about; empty if nothing in particular. {@code names} are known players' names, lower case. */
	public static Set<Topic> topics(String text, Set<String> names) {
		String s = normal(text);
		Set<Topic> found = EnumSet.noneOf(Topic.class);
		if (mentionsThreatTo(s, names)) found.add(Topic.THREAT);
		Topic[] all = Topic.values();
		for (int t = 0; t < all.length; t++) {
			for (Pattern p : TOPICS[t]) {
				if (p.matcher(s).find()) {
					found.add(all[t]);
					break;
				}
			}
		}
		// "Found your base" is a threat, not just stash talk; and a threat to grief is about griefing too.
		if (found.contains(Topic.THREAT) && Pattern.compile("\\b(?:grief|wither|lavacast|nuke|blow up|destroy|burn)\\b").matcher(s).find()) found.add(Topic.GRIEF);
		return found;
	}

	private static boolean mentionsThreatTo(String s, Set<String> names) {
		Matcher w = Pattern.compile("[a-z0-9_]{3,16}").matcher(s);
		while (w.find()) {
			if (!names.contains(w.group())) continue;
			String quoted = Pattern.quote(w.group());
			for (String t : NAMED_THREATS) {
				if (Pattern.compile(t.replace(NAME, quoted)).matcher(s).find()) return true;
			}
		}
		return false;
	}

	// ---- Tone ----

	public enum Tone { NONE, KIND, RUDE }

	private static final Pattern RUDE_STRONG = Pattern.compile("\\b(?:kys|kill (?:your ?self|urself|yourself)|stfu|shut (?:the fuck |tf )?up|fuck (?:you|u|off|ur)|"
			+ "f(?:u|ck) off|cry about it|skill issue|cope harder|cope and seethe|seethe|get good|get gud|retard(?:ed)?|ratio'?d|l bozo|"
			+ "nobody asked|no one asked|who asked)\\b");
	private static final String INSULT = "(?:trash|noob|n00b|nub|bozo|idiot|clown|loser|bad|garbage|dogshit|shit|braindead|dumb|stupid|ugly|fat|cringe|pathetic|"
			+ "worthless|moron|dumbass|dipshit|bitch|cunt|fag(?:got)?|irrelevant|washed)(?:e?s)?";
	private static final String SOFTENERS = "(?:a\\s+|an\\s+|such\\s+a\\s+|so\\s+|really\\s+|actually\\s+|literally\\s+|fucking\\s+|fkn\\s+|a\\s+fucking\\s+)?";
	/** "you're trash", "u r a noob", "you are so bad". */
	private static final Pattern RUDE_DIRECTED = Pattern.compile("\\b(?:you|u|youre|you'?re|ya|y'?all|you guys|u guys)\\s+(?:all\\s+)?(?:are\\s+|r\\s+)?(?:all\\s+)?"
			+ SOFTENERS + INSULT + "\\b");
	/** "your trash" / "ur trash" (for "you're") only when the insult ends the sentence, so "take your trash out" isn't one. */
	private static final Pattern RUDE_YOUR = Pattern.compile("\\b(?:your|ur)\\s+" + SOFTENERS + INSULT + "(?=\\s*(?:$|[!.?,]|lol|lmao|kid|bro|man|dude|ngl|tbh|fr|asf|af))");
	private static final Pattern RUDE_ALONE = Pattern.compile("^(?:gg\\s+)?(?:ez+|easy|too easy|so easy|noob|nub|bozo|clown|loser|idiot|moron|trash|"
			+ "l|ratio|cope|cry)(?:\\s+[a-z0-9_]{3,16})?[\\s!.?]*$");
	private static final Pattern NAMED_INSULT = Pattern.compile("^([a-z0-9_]{3,16})\\s+(?:is\\s+)?(?:a\\s+|so\\s+)?(?:ez+|noob|nub|bozo|clown|loser|idiot|moron|trash|garbage)[\\s!.]*$");
	private static final Pattern KIND = Pattern.compile("\\b(?:thanks|thank you|thank u|thx|tysm|tyvm|ty|np|no problem|no worries|you'?re welcome|ur welcome|yw|"
			+ "ggs?|good game|wp|well played|gl|good luck|have fun|here you go|have this|take this|gift for you|appreciate (?:it|you|u))\\b");
	private static final Pattern NOT_KIND = Pattern.compile("\\bno (?:thanks|thank you|ty)\\b|\\bthanks for nothing\\b|\\bgg ez\\b");

	/** Friendly, trash talk, or neither. Trash talk wins when a line has both ("gg ez"). {@code names}: known players, lower case. */
	public static Tone tone(String text, Set<String> names) {
		String s = normal(text).strip();
		Matcher named = NAMED_INSULT.matcher(s);
		if (RUDE_STRONG.matcher(s).find() || RUDE_DIRECTED.matcher(s).find() || RUDE_YOUR.matcher(s).find() || RUDE_ALONE.matcher(s).matches()
				|| named.matches() && names.contains(named.group(1)) || s.matches(".*\\bgg\\s+ez+\\b.*")) return Tone.RUDE;
		if (KIND.matcher(s).find() && !NOT_KIND.matcher(s).find()) return Tone.KIND;
		return Tone.NONE;
	}
}
