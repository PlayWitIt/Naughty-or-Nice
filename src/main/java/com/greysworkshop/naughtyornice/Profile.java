package com.greysworkshop.naughtyornice;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Everything known about one player: which list they're on, and what they've been seen doing. */
public final class Profile {
	/**
	 * Which list they're on. LOOK is "Look Into": not sure yet, keep an eye on them. FRIEND is someone you actually
	 * know (in one of your friend circles), which is more than just behaving well.
	 */
	public enum Verdict { NONE, NICE, NAUGHTY, LOOK, FRIEND }

	/** Something a player did. Points push their rating up (nice) or down (naughty). */
	public enum Kind {
		KILLED_YOU(-10, "Killed you", 0, 0),
		HIT_YOU(-2, "Hit you", 20, -8),
		HURT_PET(-4, "Hurt your pet", 20, -8),
		KILLED_PET(-6, "Killed your pet", 0, 0),
		BROKE_IN_BASE(-5, "Broke blocks in your base", 120, -10),
		KILLED_PLAYER(-3, "Killed a player", 0, 0),
		HIT_PLAYER(-1, "Attacked a player", 30, -3),
		RUDE(-1, "Trash talk", 600, -1),
		VISITED_BASE(0, "Visited your base", 600, 0),
		SEEN(0, "First seen", 0, 0),
		KIND_WORDS(1, "Said something nice", 600, 1),
		PEACEFUL(1, "Spent time near you without trouble", 3600, 3),
		LISTED(0, "", 0, 0);

		public final int points;
		public final String label;
		/** Repeats within this many seconds are folded into one entry. 0 = never folded. */
		public final int foldSeconds;
		/** Most points (absolute) one folded entry can add up to; 0 = just {@link #points}. */
		public final int foldCap;

		Kind(int points, String label, int foldSeconds, int foldCap) {
			this.points = points;
			this.label = label;
			this.foldSeconds = foldSeconds;
			this.foldCap = foldCap;
		}
	}

	public static final class Action {
		public Kind kind;
		/** Who or where, e.g. the victim's name or the base name. */
		public String detail = "";
		public int count = 1;
		public int points;
		public long time;

		public Action() {}

		Action(Kind kind, String detail, long time) {
			this.kind = kind;
			this.detail = detail == null ? "" : detail;
			this.points = kind.points;
			this.time = time;
		}

		public String describe() {
			String base = kind == Kind.LISTED ? detail : kind.label;
			String times = count > 1 ? " ×" + count : "";
			return switch (kind) {
				case LISTED -> base;
				case KILLED_PLAYER -> "Killed " + detail;
				case HIT_PLAYER -> "Attacked " + detail + times;
				case HURT_PET, KILLED_PET -> base + (detail.isEmpty() ? "" : " (" + detail + ")") + times;
				case BROKE_IN_BASE, VISITED_BASE -> base + (detail.isEmpty() ? "" : " \"" + detail + "\"") + times;
				case KIND_WORDS, RUDE -> base + (detail.isEmpty() ? "" : ": \"" + detail + "\"");
				case PEACEFUL -> "Spent " + count * 10 + " minutes near you without trouble";
				default -> base + times;
			};
		}
	}

	public UUID id;
	public String name = "";
	public Verdict verdict = Verdict.NONE;
	/** For friends: the id of their friend circle (see {@link Friends}); empty or unknown means the first circle. */
	public String circle = "";
	/** Old single-line note; moved into {@link #details} when the lists load. */
	public String note = "";
	/** Things written down about them, oldest first: yours and ones shared by friends. */
	public List<Detail> details = new ArrayList<>();
	/** Groups you've put them in (people teamed or associated with each other), by name. */
	public List<String> groups = new ArrayList<>();
	public long firstSeen;
	public long lastSeen;
	/** When the verdict was last changed. */
	public long listedAt;
	public int secondsNear;
	public List<Action> actions = new ArrayList<>();
	public List<String> recentChat = new ArrayList<>();
	/** Put on the Naughty list automatically (e.g. for killing you), not by hand. */
	public boolean autoListed;
	/** We added them to Base Insurance's friends, so taking them off the Nice list takes them off there too. */
	public boolean sharedFriend;
	/** Added by name before we'd seen them, so {@link #id} is a guess until they turn up. */
	public boolean placeholder;
	/** Logged chat conversations they took part in, oldest first. */
	public List<Conversation> conversations = new ArrayList<>();
	/** Names they went by before, oldest first (lists are kept by account, so a rename doesn't get them off). */
	public List<String> pastNames = new ArrayList<>();
	/** Times they came into view, where, and what they had on, oldest first. */
	public List<Sighting> sightings = new ArrayList<>();
	/** Minutes seen online in each hour of the day (local time), while you were on too; null until seen online. */
	public int[] hours;
	/** Screenshots taken when they did something (killed you, came into your base), oldest first. */
	public List<Evidence> evidence = new ArrayList<>();
	/** Which list friends who shared their lists have them on. */
	public List<FriendView> friendViews = new ArrayList<>();

	/** One time they came into view: where and when it started, where they were last, and their gear. */
	public static final class Sighting {
		public long time;
		/** When they were last in view during this sighting. */
		public long until;
		public String dimension = "";
		public int x, y, z;
		/** Where they were when last in view. */
		public int lastX, lastY, lastZ;
		/** "Netherite armour, elytra, holding an end crystal". */
		public String gear = "";
		/** The Base Insurance base they were in, if any. */
		public String base = "";
	}

	/** A screenshot taken as evidence. */
	public static final class Evidence {
		public long time;
		/** What happened ("Killed you"). */
		public String reason = "";
		/** The file, relative to the game folder. */
		public String file = "";
	}

	/** Which list a friend has them on, as of the last time that friend shared. */
	public static final class FriendView {
		public String from = "";
		public Verdict verdict = Verdict.NONE;
		public long time;
	}

	/** One thing written down about a player. */
	public static final class Detail {
		public String text = "";
		public long time;
		/** Who it came from: empty for you, otherwise the friend who shared it. */
		public String from = "";
		/** Written by the friend's mod from what it saw (e.g. "Killed Alex"), not typed by them. */
		public boolean seen;

		public Detail() {}

		public Detail(String text, String from, boolean seen, long time) {
			this.text = text;
			this.from = from == null ? "" : from;
			this.seen = seen;
			this.time = time;
		}

		public boolean mine() {
			return from.isEmpty();
		}
	}

	/** One line of chat, as heard. */
	public static final class Line {
		public long time;
		public String sender = "";
		public String text = "";
		/** Said by this profile's player. */
		public boolean them;
		/** Said by you. */
		public boolean me;
		/** A private message rather than public chat. */
		public boolean whisper;
		/** Not chat: a join/leave note. */
		public boolean system;
		/** Why it set off an alert (it said your name, posted coordinates near your base), or null. */
		public String alert;

		public Line() {}

		public Line copy() {
			Line line = new Line();
			line.time = time;
			line.sender = sender;
			line.text = text;
			line.them = them;
			line.me = me;
			line.whisper = whisper;
			line.system = system;
			line.alert = alert;
			return line;
		}
	}

	/**
	 * A stretch of chat around this player: some lines of context from before they spoke, then everything said
	 * until they'd been quiet for a while.
	 */
	public static final class Conversation {
		public long start;
		public long end;
		/** When they last said something in it. */
		public long lastSpoke;
		/** Still being recorded. */
		public boolean open;
		/** The friend who shared it; empty if you recorded it. */
		public String sharedBy = "";
		public List<Line> lines = new ArrayList<>();

		public int theirLines() {
			int n = 0;
			for (Line line : lines) {
				if (line.them) n++;
			}
			return n;
		}
	}

	/** Total minutes they've been seen online. */
	public int minutesOnline() {
		int n = 0;
		if (hours != null) {
			for (int h : hours) n += h;
		}
		return n;
	}

	/**
	 * When they're usually on, as "6pm to 11pm", from the busiest stretch of hours; null if there isn't enough to go on.
	 */
	public String usuallyOn() {
		int total = minutesOnline();
		if (total < 60) return null;
		// The shortest run of hours (wrapping past midnight) that holds most (85%) of their time.
		int bestStart = 0, bestLength = 24;
		for (int start = 0; start < 24; start++) {
			int sum = 0;
			for (int length = 1; length <= 24; length++) {
				sum += hours[(start + length - 1) % 24];
				if (sum * 100 >= total * 85) {
					if (length < bestLength) {
						bestLength = length;
						bestStart = start;
					}
					break;
				}
			}
		}
		if (bestLength >= 20) return "at all hours";
		return hourName(bestStart) + " to " + hourName((bestStart + bestLength) % 24);
	}

	static String hourName(int hour) {
		if (hour == 0) return "midnight";
		if (hour == 12) return "noon";
		return hour < 12 ? hour + "am" : (hour - 12) + "pm";
	}

	/** Their newest sighting, or null. */
	public Sighting lastSighting() {
		return sightings.isEmpty() ? null : sightings.get(sightings.size() - 1);
	}

	/** What a friend thinks of them, or null if that friend hasn't shared them. */
	public FriendView friendView(String friend) {
		for (FriendView view : friendViews) {
			if (view.from.equalsIgnoreCase(friend)) return view;
		}
		return null;
	}

	/** Seconds near you since they last did something hostile to you. */
	public transient int peacefulSeconds;
	public transient long lastHostile;

	public Profile() {}

	public Profile(UUID id, String name) {
		this.id = id;
		this.name = name;
		this.firstSeen = System.currentTimeMillis();
		this.lastSeen = firstSeen;
	}

	public int score() {
		int score = 0;
		for (Action action : actions) score += action.points;
		return score;
	}

	/** 1 to 5 stars from their behaviour. */
	public int stars() {
		int score = score();
		if (score <= -10) return 1;
		if (score <= -3) return 2;
		if (score < 3) return 3;
		if (score < 10) return 4;
		return 5;
	}

	/** Number of things they've done that count for or against them. */
	public int ratedActions() {
		int n = 0;
		for (Action action : actions) {
			if (action.points != 0) n += action.count;
		}
		return n;
	}

	/** What their behaviour suggests, for players not on a list yet. */
	public Verdict suggestion() {
		int score = score();
		if (score <= -3) return Verdict.NAUGHTY;
		if (score >= 3) return Verdict.NICE;
		return Verdict.NONE;
	}

	/** Records an action, folding it into the previous one of the same kind if it's recent. */
	public Action record(Kind kind, String detail, int maxActions) {
		long now = System.currentTimeMillis();
		String d = detail == null ? "" : detail;
		if (kind.foldSeconds > 0) {
			for (int i = actions.size() - 1; i >= 0 && i >= actions.size() - 8; i--) {
				Action last = actions.get(i);
				if (last.kind != kind || now - last.time > kind.foldSeconds * 1000L) continue;
				if (!last.detail.equals(d) && kind != Kind.KIND_WORDS && kind != Kind.RUDE) continue;
				last.count++;
				last.time = now;
				int cap = kind.foldCap == 0 ? kind.points : kind.foldCap;
				last.points = cap < 0 ? Math.max(cap, last.points + kind.points) : Math.min(cap, last.points + kind.points);
				actions.remove(i);
				actions.add(last);
				return last;
			}
		}
		Action action = new Action(kind, d, now);
		actions.add(action);
		while (actions.size() > maxActions) actions.remove(0);
		return action;
	}

	/** Adds something you've written about them, unless it's already there. Returns it, or null if it was a repeat. */
	public Detail addDetail(String text, String from, boolean seen) {
		String clean = text == null ? "" : text.strip();
		if (clean.isEmpty()) return null;
		for (Detail detail : details) {
			if (detail.text.equalsIgnoreCase(clean)) return null;
		}
		Detail detail = new Detail(clean, from, seen, System.currentTimeMillis());
		details.add(detail);
		return detail;
	}

	/** The newest detail someone wrote (or, failing that, the newest at all), for their card; null if there are none. */
	public Detail latestDetail() {
		for (int i = details.size() - 1; i >= 0; i--) {
			if (!details.get(i).seen) return details.get(i);
		}
		return details.isEmpty() ? null : details.get(details.size() - 1);
	}

	/** Whether they used to be called {@code q} or something containing it (lower case). */
	public boolean pastNamesContain(String q) {
		for (String n : pastNames) {
			if (n.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
		}
		return false;
	}

	public boolean inGroup(String group) {
		for (String g : groups) {
			if (g.equalsIgnoreCase(group)) return true;
		}
		return false;
	}

	/** Whether any group name contains {@code q} (lower case). */
	public boolean groupsContain(String q) {
		for (String g : groups) {
			if (g.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
		}
		return false;
	}

	/** Whether any detail contains {@code q} (lower case). */
	public boolean detailsContain(String q) {
		for (Detail detail : details) {
			if (detail.text.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
		}
		return false;
	}

	public int count(Kind kind) {
		int n = 0;
		for (Action action : actions) {
			if (action.kind == kind) n += action.count;
		}
		return n;
	}

	/** Whether there's anything to them beyond having been seen: on a list, noted, did something, or said something. */
	public boolean notable() {
		if (verdict != Verdict.NONE || !details.isEmpty() || !groups.isEmpty() || !conversations.isEmpty() || !evidence.isEmpty()
				|| !friendViews.isEmpty() || !pastNames.isEmpty()) return true;
		for (Action action : actions) {
			if (action.kind != Kind.SEEN && action.kind != Kind.LISTED) return true;
		}
		return false;
	}

	/** Whether to save them: anyone notable, and (if remembering everyone) anyone actually seen. */
	public boolean worthKeeping() {
		if (notable()) return true;
		return NNConfig.INSTANCE.rememberEveryone && !placeholder && lastSeen > 0;
	}
}
