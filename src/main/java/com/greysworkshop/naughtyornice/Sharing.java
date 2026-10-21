package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Sharing lists with a friend who has the mod: packing them into a share code (to paste in Discord) or a
 * .nnshare file, reading a friend's back, previewing what an import would change, and applying it with undo.
 */
public final class Sharing {
	public static final String CODE_PREFIX = "NN1:";
	public static final String EXTENSION = ".nnshare";
	/** Discord's limit for one message (without Nitro). */
	public static final int DISCORD_LIMIT = 2000;
	private static final String FORMAT = "naughtyornice-share";
	private static final Gson GSON = new GsonBuilder().create();
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
	private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT);

	private Sharing() {}

	/** How much to send. */
	public enum Scope {
		LISTS("Your Friends and lists"),
		NOTED("Everyone with details or history"),
		EVERYONE("Everyone you've seen");

		public final String label;

		Scope(String label) {
			this.label = label;
		}
	}

	/** What gets sent. */
	public static final class Pack {
		public String format = FORMAT;
		public int version = 1;
		/** The sender's player name. */
		public String from = "";
		public UUID fromId;
		/** Where the lists are from, e.g. "server:constantiam.net". */
		public String server = "";
		public long created;
		public List<Player> players = new ArrayList<>();
	}

	public static final class Player {
		public UUID id;
		public String name = "";
		/** Friends go out as Nice (so older versions read them sensibly) with {@link #friend} set. */
		public Profile.Verdict verdict = Profile.Verdict.NONE;
		/** The sender's friend. Their friends aren't yours, so for you they go on the Nice list. */
		public boolean friend;
		public long listedAt;
		/** The sender's trust score and stars for them. */
		public int score;
		public int stars = 3;
		public List<Profile.Detail> details = new ArrayList<>();
		/** What the sender's mod saw them do, in the sender's words ("Killed Alex ×2"). */
		public List<String> seen = new ArrayList<>();
		/** The sender's groups for them (teams, associates). */
		public List<String> groups = new ArrayList<>();
		/** Names they went by before. */
		public List<String> pastNames = new ArrayList<>();
		public List<Profile.Conversation> conversations = new ArrayList<>();

		/** How the sender has them: Friend, or their list. */
		public Profile.Verdict view() {
			return friend ? Profile.Verdict.FRIEND : verdict;
		}
	}

	// ---- Sending ----

	/** The players a scope covers, leaving out you. */
	public static List<Profile> choose(Ledger ledger, Scope scope, UUID me) {
		List<Profile> chosen = new ArrayList<>();
		for (Profile p : ledger.all()) {
			if (p.id.equals(me)) continue;
			boolean in = switch (scope) {
				case LISTS -> p.verdict != Profile.Verdict.NONE;
				case NOTED -> p.notable();
				case EVERYONE -> true;
			};
			if (in) chosen.add(p);
		}
		return chosen;
	}

	public static Pack pack(Collection<Profile> profiles, boolean chat, String from, UUID fromId, String server) {
		Pack pack = new Pack();
		pack.from = from;
		pack.fromId = fromId;
		pack.server = server == null ? "" : server;
		pack.created = System.currentTimeMillis();
		for (Profile p : profiles) {
			Player sp = new Player();
			sp.id = p.id;
			sp.name = p.name;
			sp.friend = p.verdict == Profile.Verdict.FRIEND;
			sp.verdict = sp.friend ? Profile.Verdict.NICE : p.verdict;
			sp.listedAt = p.listedAt;
			sp.score = p.score();
			sp.stars = p.stars();
			sp.groups = new ArrayList<>(p.groups);
			sp.pastNames = new ArrayList<>(p.pastNames);
			for (Profile.Detail d : p.details) {
				// Your own details go out under your name; ones from other friends keep theirs.
				sp.details.add(new Profile.Detail(d.text, d.mine() ? from : d.from, d.seen, d.time));
			}
			for (Profile.Action a : p.actions) {
				if (a.points != 0 && a.kind != Profile.Kind.LISTED) sp.seen.add(seenText(a, from));
			}
			if (chat) {
				for (Profile.Conversation c : p.conversations) {
					if (c.open) continue;
					Profile.Conversation copy = GSON.fromJson(GSON.toJson(c), Profile.Conversation.class);
					if (copy.sharedBy.isEmpty()) copy.sharedBy = from;
					sp.conversations.add(copy);
				}
			}
			pack.players.add(sp);
		}
		return pack;
	}

	/** An action as the sender would describe it to a friend: "Killed you" becomes "Killed Alex". */
	static String seenText(Profile.Action a, String owner) {
		return switch (a.kind) {
			case KIND_WORDS, RUDE -> a.kind.label + (a.detail.isEmpty() ? "" : ": \"" + a.detail + "\"");
			default -> a.describe().replaceAll("\\byour\\b", owner + "'s").replaceAll("\\byou\\b", owner);
		};
	}

	/** The pack as JSON, for share links. */
	static String json(Pack pack) {
		return GSON.toJson(pack);
	}

	/** A share code: "NN1:" and the pack, compressed. One line, safe to paste anywhere. */
	public static String code(Pack pack) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
			gzip.write(GSON.toJson(pack).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
	}

	public static Path folder() {
		return NNConfig.dir().resolve("shared");
	}

	/** Saves the pack as a readable file in the shared folder; returns where. */
	public static Path save(Pack pack) throws IOException {
		Files.createDirectories(folder());
		String server = pack.server.replaceFirst("^(server|local):", "");
		String name = ("naughty-nice-" + pack.from + "-" + server + "-" + LocalDateTime.now().format(FILE_DATE))
				.replaceAll("[^A-Za-z0-9._-]", "_");
		Path file = folder().resolve(name + EXTENSION);
		Files.writeString(file, PRETTY.toJson(pack));
		return file;
	}

	/** Share files in the shared folder, newest first. */
	public static List<Path> files() {
		if (!Files.isDirectory(folder())) return List.of();
		try (Stream<Path> list = Files.list(folder())) {
			return list.filter(f -> f.getFileName().toString().endsWith(EXTENSION))
					.sorted(Comparator.comparingLong((Path f) -> f.toFile().lastModified()).reversed())
					.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	// ---- Receiving ----

	/** Reads a pack from a share file. Throws with a message for the player if it isn't one. */
	public static Pack readFile(Path file) {
		try {
			if (Files.size(file) > 50_000_000L) throw new IllegalArgumentException("That file is too big to be a share file.");
			return read(Files.readString(file));
		} catch (IOException e) {
			throw new IllegalArgumentException("Couldn't open " + file.getFileName() + ".");
		}
	}

	/**
	 * Reads a pack from pasted text: a share code (found anywhere in it, so a whole copied Discord message works)
	 * or a share file's contents. Throws with a message for the player if there isn't one.
	 */
	public static Pack read(String text) {
		if (text == null || text.isBlank()) throw new IllegalArgumentException("Nothing to read. Copy a share code first.");
		String json;
		int at = text.indexOf(CODE_PREFIX);
		if (at >= 0) {
			int end = at + CODE_PREFIX.length();
			StringBuilder data = new StringBuilder();
			// The code may have been wrapped onto several lines by a chat app.
			for (int i = end; i < text.length(); i++) {
				char c = text.charAt(i);
				if (Character.isLetterOrDigit(c) || c == '-' || c == '_') data.append(c);
				else if (!Character.isWhitespace(c)) break;
			}
			try {
				byte[] packed = Base64.getUrlDecoder().decode(data.toString());
				try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
					json = new String(gzip.readNBytes(100_000_000), StandardCharsets.UTF_8);
				}
			} catch (IOException | IllegalArgumentException e) {
				throw new IllegalArgumentException("That share code is cut off or broken. Ask for it again, or use a share file.");
			}
		} else if (text.strip().startsWith("{")) {
			json = text;
		} else {
			throw new IllegalArgumentException("That isn't a Naughty or Nice share code. Codes start with " + CODE_PREFIX);
		}
		Pack pack;
		try {
			pack = GSON.fromJson(json, Pack.class);
		} catch (JsonParseException e) {
			throw new IllegalArgumentException("That share is damaged and can't be read.");
		}
		if (pack == null || !FORMAT.equals(pack.format) || pack.players == null) {
			throw new IllegalArgumentException("That isn't a Naughty or Nice share.");
		}
		if (pack.version > 1) throw new IllegalArgumentException("That share is from a newer version of Naughty or Nice. Update the mod to open it.");
		if (pack.from == null) pack.from = "";
		if (pack.server == null) pack.server = "";
		pack.players.removeIf(p -> p == null || p.id == null || p.name == null || p.name.isBlank());
		for (Player p : pack.players) {
			if (p.verdict == null) p.verdict = Profile.Verdict.NONE;
			if (p.verdict == Profile.Verdict.FRIEND) {
				p.verdict = Profile.Verdict.NICE;
				p.friend = true;
			}
			if (p.details == null) p.details = new ArrayList<>();
			p.details.removeIf(d -> d == null || d.text == null || d.text.isBlank());
			for (Profile.Detail d : p.details) {
				if (d.from == null) d.from = "";
			}
			if (p.groups == null) p.groups = new ArrayList<>();
			p.groups.replaceAll(Ledger::cleanGroupName);
			p.groups.removeIf(String::isEmpty);
			if (p.pastNames == null) p.pastNames = new ArrayList<>();
			p.pastNames.removeIf(n -> n == null || !n.matches("[A-Za-z0-9_]{1,16}"));
			if (p.seen == null) p.seen = new ArrayList<>();
			p.seen.removeIf(s -> s == null || s.isBlank());
			if (p.conversations == null) p.conversations = new ArrayList<>();
			p.conversations.removeIf(c -> c == null || c.lines == null || c.lines.isEmpty());
			for (Profile.Conversation c : p.conversations) {
				c.open = false;
				if (c.sharedBy == null) c.sharedBy = pack.from;
				c.lines.removeIf(line -> line == null || line.text == null);
			}
		}
		return pack;
	}

	/** What an import does to one player. */
	public enum Outcome {
		/** Someone you don't know yet. */
		NEW,
		/** You know them; their lists or details add something. */
		UPDATE,
		/** You've put them on the other list. */
		CONFLICT,
		/** Nothing to add. */
		SAME,
		/** It's you. */
		YOU
	}

	public static final class Row {
		public final Player in;
		public final Profile mine;
		public Outcome outcome;
		/** The list they'll be on after the import. */
		public Profile.Verdict result;
		public int newDetails;
		public int newChats;
		public int newGroups;
		/** Details this friend sent before that they've since changed or taken back. */
		public int goneDetails;
		/** This friend has them on a different list than they did last time (or it's the first time). */
		public boolean viewChanged;

		Row(Player in, Profile mine) {
			this.in = in;
			this.mine = mine;
		}

		/** Whether importing changes anything for this player. */
		public boolean changes() {
			return outcome != Outcome.SAME && outcome != Outcome.YOU
					&& (mine == null ? result != Profile.Verdict.NONE || newDetails > 0 || newChats > 0 || newGroups > 0
					: result != mine.verdict || newDetails > 0 || newChats > 0 || newGroups > 0 || goneDetails > 0 || viewChanged);
		}
	}

	/**
	 * Works out what importing would do, without changing anything.
	 *
	 * @param takeLists  put players on the sender's lists where you haven't picked a list yourself
	 * @param theirsWins where you disagree, use the sender's list instead of yours
	 */
	public static List<Row> plan(Pack pack, Ledger ledger, UUID me, String myName, boolean takeLists, boolean theirsWins) {
		List<Row> rows = new ArrayList<>();
		for (Player in : pack.players) {
			Profile mine = ledger.get(in.id);
			if (mine == null) {
				Profile byName = ledger.byName(in.name);
				if (byName != null && byName.placeholder) mine = byName;
			}
			Row row = new Row(in, mine);
			rows.add(row);
			if (in.id.equals(me)) {
				row.outcome = Outcome.YOU;
				row.result = in.verdict;
				continue;
			}
			Profile.Verdict current = mine == null ? Profile.Verdict.NONE : mine.verdict;
			// Your friends stay your friends whatever the sender thinks; a friend's view of them is still noted.
			boolean disagree = current != Profile.Verdict.NONE && current != Profile.Verdict.FRIEND && in.verdict != Profile.Verdict.NONE && in.verdict != current;
			if (current == Profile.Verdict.NONE) row.result = takeLists ? in.verdict : Profile.Verdict.NONE;
			else if (disagree) row.result = takeLists && theirsWins ? in.verdict : current;
			else row.result = current;

			Set<String> have = new HashSet<>();
			if (mine != null) {
				for (Profile.Detail d : mine.details) have.add(d.text.toLowerCase(Locale.ROOT));
			}
			for (Profile.Detail d : in.details) {
				if (have.add(d.text.strip().toLowerCase(Locale.ROOT))) row.newDetails++;
			}
			String sender = pack.from.isEmpty() ? "a friend" : pack.from;
			if (mine != null) {
				// Following a friend: what they wrote before and have since changed or removed goes.
				Set<String> sent = new HashSet<>();
				for (Profile.Detail d : in.details) sent.add(d.text.strip().toLowerCase(Locale.ROOT));
				for (Profile.Detail d : mine.details) {
					if (!d.seen && d.from.equalsIgnoreCase(sender) && !sent.contains(d.text.strip().toLowerCase(Locale.ROOT))) row.goneDetails++;
				}
				Profile.FriendView view = mine.friendView(sender);
				row.viewChanged = view == null ? in.verdict != Profile.Verdict.NONE : view.verdict != in.view();
			}
			for (String s : in.seen) {
				if (have.add(s.strip().toLowerCase(Locale.ROOT))) row.newDetails++;
			}
			for (String g : in.groups) {
				if (mine == null || !mine.inGroup(g)) row.newGroups++;
			}
			Set<Long> starts = new HashSet<>();
			if (mine != null) {
				for (Profile.Conversation c : mine.conversations) starts.add(c.start);
			}
			for (Profile.Conversation c : in.conversations) {
				if (starts.add(c.start)) row.newChats++;
			}

			if (mine == null) row.outcome = Outcome.NEW;
			else if (disagree) row.outcome = Outcome.CONFLICT;
			else row.outcome = row.result != current || row.newDetails > 0 || row.newChats > 0 || row.newGroups > 0 || row.goneDetails > 0
					|| row.viewChanged ? Outcome.UPDATE : Outcome.SAME;
		}
		return rows;
	}

	/** Imports the planned rows. Returns how to undo it all. */
	public static Runnable apply(Pack pack, List<Row> rows, Ledger ledger, String myName) {
		String sender = pack.from.isEmpty() ? "a friend" : pack.from;
		List<Runnable> undo = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Row row : rows) {
			if (!row.changes()) continue;
			Player in = row.in;
			Profile p = row.mine;
			if (p == null) {
				p = new Profile(in.id, in.name);
				p.lastSeen = 0;
				ledger.add(p);
				Profile added = p;
				undo.add(() -> ledger.forget(added));
			} else {
				Profile kept = p;
				Profile.Verdict verdict = p.verdict;
				long listedAt = p.listedAt;
				boolean auto = p.autoListed;
				List<Profile.Detail> details = new ArrayList<>(p.details);
				List<Profile.Action> actions = new ArrayList<>(p.actions);
				List<Profile.Conversation> conversations = new ArrayList<>(p.conversations);
				List<String> groups = new ArrayList<>(p.groups);
				List<Profile.FriendView> views = new ArrayList<>();
				for (Profile.FriendView v : p.friendViews) {
					Profile.FriendView copy = new Profile.FriendView();
					copy.from = v.from;
					copy.verdict = v.verdict;
					copy.time = v.time;
					views.add(copy);
				}
				List<String> pastNames = new ArrayList<>(p.pastNames);
				undo.add(() -> {
					kept.friendViews = views;
					kept.pastNames = pastNames;
					kept.groups = groups;
					ledger.groupsChanged();
					kept.verdict = verdict;
					kept.listedAt = listedAt;
					kept.autoListed = auto;
					kept.details = details;
					kept.actions = actions;
					kept.conversations = conversations;
					BaseInsuranceBridge.syncFriend(kept);
					ledger.markDirty();
				});
			}
			if (row.result != p.verdict) ledger.setVerdict(p, row.result, false, "from " + sender + "'s lists");

			// What their mod saw replaces what it saw last time they shared, so counts don't pile up; what they
			// wrote and have since changed or taken back goes too.
			Set<String> sent = new HashSet<>();
			for (Profile.Detail d : in.details) sent.add(d.text.strip().toLowerCase(Locale.ROOT));
			p.details.removeIf(d -> d.from.equalsIgnoreCase(sender) && (d.seen || !sent.contains(d.text.strip().toLowerCase(Locale.ROOT))));
			Profile.FriendView view = p.friendView(sender);
			if (view == null) {
				view = new Profile.FriendView();
				view.from = sender;
				p.friendViews.add(view);
			}
			view.verdict = in.view();
			view.time = pack.created > 0 ? pack.created : now;
			for (String past : in.pastNames) {
				if (!past.equalsIgnoreCase(p.name) && p.pastNames.stream().noneMatch(past::equalsIgnoreCase)) p.pastNames.add(past);
			}
			for (Profile.Detail d : in.details) {
				String from = d.from.isEmpty() ? sender : d.from.equalsIgnoreCase(myName) ? "" : d.from;
				Profile.Detail added = p.addDetail(d.text, from, d.seen);
				if (added != null && d.time > 0) added.time = d.time;
			}
			for (String s : in.seen) {
				Profile.Detail added = p.addDetail(s, sender, true);
				if (added != null) added.time = now;
			}
			for (String g : in.groups) ledger.addToGroup(p, g);
			Set<Long> starts = new HashSet<>();
			for (Profile.Conversation c : p.conversations) starts.add(c.start);
			List<Profile.Conversation> merged = new ArrayList<>(p.conversations);
			for (Profile.Conversation c : in.conversations) {
				if (starts.add(c.start)) merged.add(c);
			}
			merged.sort(Comparator.comparingLong(c -> c.start));
			p.conversations = merged;
			ledger.markDirty();
		}
		ledger.save(false);
		return () -> {
			for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).run();
			ledger.save(false);
		};
	}

	/** "constantiam.net" from "server:constantiam.net", or the world name for singleplayer. */
	public static String placeName(String key) {
		if (key == null || key.isEmpty()) return "an unknown server";
		return key.replaceFirst("^server:", "").replaceFirst("^local:", "singleplayer: ");
	}
}
