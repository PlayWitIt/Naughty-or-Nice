package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat while you're away, from the server's public Discord, where a relay channel shows the in-game chat. Nothing runs
 * while you're offline: Discord keeps the channel's history, so each time you join the server this catches up on
 * everything said since last time, and files it like chat you saw yourself, at the time it was said (Chat tab, player
 * pages, places, Intel). Lines from while you were online are skipped (you saw those live). No pings or sounds for old
 * lines, just one line in chat saying what came in.
 * <p>
 * It reads the channel with a bot (the way Discord allows; the server's staff add it, read-only) or, if you choose,
 * with your own account (against Discord's rules: the account can be banned). The token is kept in its own file next
 * to the config, outside what backups and shares copy, and is only ever sent to Discord.
 */
public final class DiscordFeed {
	private DiscordFeed() {}

	/** Discord's API (the self-test points this at a stand-in). */
	static String apiBase = "https://discord.com/api/v10";
	private static final long DISCORD_EPOCH = 1420070400000L;
	/** The first catch-up goes back this far. */
	static final int FIRST_DAYS = 3;
	/** At most this many messages in one catch-up (the oldest are skipped past). */
	static final int MAX_MESSAGES = 20_000;
	/** Lines this close to a time you were online count as seen live. */
	private static final long SLACK = 5_000;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	/** What a relay message was. */
	public enum Kind { CHAT, JOIN, LEAVE, DEATH, UNKNOWN }

	public record Message(String id, long time, Kind kind, String name, String text, String raw) {}

	/** Kept between games (no secrets in it). */
	static final class State {
		String lastId = "";
		/** When you were on the server: {start, end} in ms. */
		List<long[]> sessions = new ArrayList<>();
		String lastResult = "";
		long lastRun;
	}

	private static State state;
	private static String sessionKey;
	private static long sessionStart;
	private static volatile boolean running;
	/** What the last catch-up did, for the settings. */
	public static volatile String status;

	// ---- The token: in its own file, never in the config, backups or shares ----

	static Path tokenFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("naughtyornice-discord.token");
	}

	public static String token() {
		try {
			if (!Files.exists(tokenFile())) return "";
			return cleanToken(Files.readString(tokenFile(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			return "";
		}
	}

	static String cleanToken(String t) {
		String s = t == null ? "" : t.strip().replace("\"", "");
		if (s.regionMatches(true, 0, "Bot ", 0, 4)) s = s.substring(4).strip();
		return s;
	}

	public static void setToken(String token) {
		String t = cleanToken(token);
		try {
			if (t.isEmpty()) {
				Files.deleteIfExists(tokenFile());
				return;
			}
			Files.createDirectories(tokenFile().getParent());
			Files.writeString(tokenFile(), t, StandardCharsets.UTF_8);
			try {
				Files.setPosixFilePermissions(tokenFile(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
			} catch (UnsupportedOperationException ignored) {
				// Windows: the file is in your own user folder anyway.
			}
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.warn("Couldn't keep the Discord token: {}", e.toString());
		}
	}

	/** The channel's number, from its link (https://discord.com/channels/server/channel) or the bare number; "" if none. */
	public static String channelId(String linkOrId) {
		if (linkOrId == null) return "";
		Matcher m = Pattern.compile("(\\d{15,22})").matcher(linkOrId);
		String last = "";
		while (m.find()) last = m.group(1);
		return last;
	}

	static long snowflakeTime(String id) {
		try {
			return (Long.parseUnsignedLong(id) >>> 22) + DISCORD_EPOCH;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	static String snowflakeAt(long ms) {
		return Long.toUnsignedString((Math.max(ms, DISCORD_EPOCH) - DISCORD_EPOCH) << 22);
	}

	/** A catch-up is under way. */
	static boolean running() {
		return running;
	}

	public static boolean ready() {
		NNConfig c = NNConfig.INSTANCE;
		return c.discordOn && !channelId(c.discordChannel).isEmpty() && !token().isEmpty() && !c.discordServer.isEmpty();
	}

	// ---- State ----

	private static Path stateFile() {
		return NNConfig.dir().resolve("discord.json");
	}

	static synchronized State state() {
		if (state != null) return state;
		try {
			if (Files.exists(stateFile())) state = GSON.fromJson(Files.readString(stateFile()), State.class);
		} catch (IOException | RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.warn("Couldn't read the Discord catch-up state: {}", e.toString());
		}
		if (state == null) state = new State();
		if (state.sessions == null) state.sessions = new ArrayList<>();
		if (state.lastId == null) state.lastId = "";
		return state;
	}

	static synchronized void saveState() {
		try {
			Files.createDirectories(stateFile().getParent());
			Files.writeString(stateFile(), GSON.toJson(state()));
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.warn("Couldn't save the Discord catch-up state: {}", e.toString());
		}
	}

	/** Forgets where it got to (a new channel): the next catch-up starts {@link #FIRST_DAYS} back. */
	public static synchronized void reset() {
		state().lastId = "";
		saveState();
	}

	// ---- Joining and leaving: when you're on, chat is seen live ----

	/** Every tick: notices you joining or leaving a server; joining the one the channel is for catches up. */
	public static void tick(MinecraftClient client, Ledger ledger) {
		String key = client.world == null || client.player == null || client.getNetworkHandler() == null ? null : Ledger.worldKey(client);
		if (Objects.equals(key, sessionKey)) return;
		long now = System.currentTimeMillis();
		if (sessionKey != null && sessionKey.equals(NNConfig.INSTANCE.discordServer)) {
			synchronized (DiscordFeed.class) {
				state().sessions.add(new long[] {sessionStart, now});
				while (state().sessions.size() > 200) state().sessions.remove(0);
			}
			saveState();
		}
		sessionKey = key;
		sessionStart = now;
		if (key != null && key.equals(NNConfig.INSTANCE.discordServer) && ready()) catchUp(client, ledger, false);
	}

	// ---- Catching up ----

	/** Fetches what's new in the channel and files it (on the game thread). {@code manual}: from the Catch up button. */
	public static void catchUp(MinecraftClient client, Ledger ledger, boolean manual) {
		NNConfig c = NNConfig.INSTANCE;
		catchUpUntil(client, ledger, manual, sessionKey != null && sessionKey.equals(c.discordServer) ? sessionStart : System.currentTimeMillis());
	}

	/** As {@link #catchUp}, taking lines said before {@code until} (when you joined this time). */
	static void catchUpUntil(MinecraftClient client, Ledger ledger, boolean manual, long until) {
		if (running) return;
		NNConfig c = NNConfig.INSTANCE;
		String channel = channelId(c.discordChannel), token = token();
		if (channel.isEmpty() || token.isEmpty()) {
			status = channel.isEmpty() ? "Paste the relay channel's link first." : "Paste a token first.";
			return;
		}
		running = true;
		status = "Catching up from Discord…";
		String after;
		List<long[]> sessions;
		synchronized (DiscordFeed.class) {
			after = state().lastId.isEmpty() ? snowflakeAt(System.currentTimeMillis() - FIRST_DAYS * 86_400_000L) : state().lastId;
			sessions = new ArrayList<>(state().sessions);
		}
		boolean bot = !c.discordOwnAccount;
		CompletableFuture.supplyAsync(() -> fetch(channel, token, bot, after, until)).whenComplete((result, error) -> client.execute(() -> {
			running = false;
			if (error != null) {
				status = "Couldn't catch up: " + error.getMessage();
				return;
			}
			if (result.problem != null) {
				status = result.problem;
				if (manual) Alerts.chat(Text.literal("Discord: " + result.problem).formatted(Formatting.YELLOW));
				return;
			}
			apply(client, ledger, result, sessions, until, manual);
		}));
	}

	/** One catch-up's messages (oldest first), the newest id seen, or why it couldn't. */
	static final class Fetched {
		final List<Message> messages = new ArrayList<>();
		String newestId = "";
		String problem;
		int emptyContent;
		int requests;
	}

	/** Pages through the channel after {@code after}, up to {@code until} (ms) or {@link #MAX_MESSAGES}. Blocks. */
	static Fetched fetch(String channel, String token, boolean bot, String after, long until) {
		Fetched out = new Fetched();
		String cursor = after;
		while (out.messages.size() < MAX_MESSAGES) {
			JsonArray page;
			try {
				page = get(apiBase + "/channels/" + channel + "/messages?limit=100&after=" + cursor, token, bot, out);
			} catch (IOException e) {
				out.problem = e.getMessage();
				return out;
			}
			if (page == null) return out;
			List<JsonObject> rows = new ArrayList<>();
			for (JsonElement e : page) if (e.isJsonObject()) rows.add(e.getAsJsonObject());
			rows.sort(Comparator.comparing(o -> Long.parseUnsignedLong(o.get("id").getAsString())));
			boolean pastUntil = false;
			for (JsonObject row : rows) {
				Message m = parse(row);
				cursor = m.id();
				out.newestId = m.id();
				if (m.time() >= until - SLACK) {
					pastUntil = true;
					continue;
				}
				if (m.kind() == Kind.UNKNOWN && m.raw().isEmpty()) out.emptyContent++;
				out.messages.add(m);
			}
			if (rows.size() < 100 || pastUntil) break;
		}
		// The bot sees messages but not their words: Discord's Message Content switch is off for it.
		if (bot && out.messages.size() >= 5 && out.emptyContent * 2 > out.messages.size()) {
			out.problem = "Discord sent the messages without their words: turn on Message Content Intent for your bot "
					+ "(discord.com/developers → your app → Bot), then Catch up now.";
		}
		return out;
	}

	/** A GET to Discord, waiting out rate limits. Null on a problem it notes in {@code out}. */
	private static JsonArray get(String url, String token, boolean bot, Fetched out) throws IOException {
		for (int attempt = 0; attempt < 6; attempt++) {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
					.header("Authorization", bot ? "Bot " + token : token)
					.header("User-Agent", "DiscordBot (https://github.com/PlayWitIt/Naughty-or-Nice, " + UpdateCheck.current() + ")")
					.GET().build();
			HttpResponse<String> response;
			try {
				out.requests++;
				response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted");
			} catch (IOException e) {
				throw new IOException("Couldn't reach Discord (" + e.getMessage() + ").");
			}
			int code = response.statusCode();
			if (code == 429) {
				double wait = 1;
				try {
					wait = JsonParser.parseString(response.body()).getAsJsonObject().get("retry_after").getAsDouble();
				} catch (RuntimeException ignored) {
					// Wait a second.
				}
				try {
					Thread.sleep((long) (Math.min(60, Math.max(0.2, wait)) * 1000));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("interrupted");
				}
				continue;
			}
			if (code == 401) {
				out.problem = "Discord refused the token. Check it in Settings → Discord.";
				return null;
			}
			if (code == 403) {
				out.problem = bot ? "The bot can't read that channel: it isn't in that Discord server yet, or it's missing Read Message History."
						: "Your account can't read that channel.";
				return null;
			}
			if (code == 404) {
				out.problem = "No such channel: check the link in Settings → Discord.";
				return null;
			}
			if (code / 100 != 2) throw new IOException("Discord said " + code + ". Try again in a bit.");
			try {
				return JsonParser.parseString(response.body()).getAsJsonArray();
			} catch (RuntimeException e) {
				throw new IOException("Discord gave an odd answer.");
			}
		}
		throw new IOException("Discord kept saying wait. Try again in a minute.");
	}

	/** Checks the token and channel without filing anything: the channel's name, or why not. Blocks. */
	static String check(String channel, String token, boolean bot) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/channels/" + channel)).timeout(Duration.ofSeconds(15))
				.header("Authorization", bot ? "Bot " + token : token)
				.header("User-Agent", "DiscordBot (https://github.com/PlayWitIt/Naughty-or-Nice, " + UpdateCheck.current() + ")").GET().build();
		try {
			HttpResponse<String> r = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() == 200) return "Connected: #" + JsonParser.parseString(r.body()).getAsJsonObject().get("name").getAsString();
			if (r.statusCode() == 401) return "Discord refused the token.";
			if (r.statusCode() == 403) return bot ? "The bot can't see that channel yet (not added to that server?)." : "Your account can't see that channel.";
			if (r.statusCode() == 404) return "No such channel: check the link.";
			return "Discord said " + r.statusCode() + ".";
		} catch (IOException e) {
			return "Couldn't reach Discord (" + e.getMessage() + ").";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "Interrupted.";
		}
	}

	public static void checkAsync(java.util.function.Consumer<String> done) {
		NNConfig c = NNConfig.INSTANCE;
		String channel = channelId(c.discordChannel), token = token();
		if (channel.isEmpty()) {
			done.accept("Paste the relay channel's link first.");
			return;
		}
		if (token.isEmpty()) {
			done.accept("Paste a token first.");
			return;
		}
		boolean bot = !c.discordOwnAccount;
		CompletableFuture.supplyAsync(() -> check(channel, token, bot)).thenAccept(r -> MinecraftClient.getInstance().execute(() -> done.accept(r)));
	}

	// ---- Finding the relay channel ----

	/** A channel the token can read, and how many of its last messages read as relayed game chat. */
	public record Pick(String guildId, String guildName, String channelId, String channelName, int relayLines, int sampled) {
		public String link() {
			return "https://discord.com/channels/" + guildId + "/" + channelId;
		}
	}

	/** What {@link #findChannels} found: the likeliest relay first, or why nothing. */
	public static final class Found {
		public final List<Pick> picks = new ArrayList<>();
		public String problem;
		/** The Discord servers looked in. */
		public final List<String> guilds = new ArrayList<>();
	}

	/** How many channels' recent messages are read to tell which is the relay. */
	static final int SAMPLE_CHANNELS = 8, SAMPLE_MESSAGES = 25;
	/** Words in relay channels' names. */
	private static final Pattern RELAY_NAME = Pattern.compile("relay|in-?game|game-?chat|server-?chat|mc-?chat|bridge|minecraft|live-?chat|chat-?log");

	/** The word to look for in Discord servers' names: from the server address ("play.constantiam.net": constantiam). */
	static String serverWord(String key) {
		String a = key == null ? "" : key.replaceFirst("^server:", "").replaceFirst(":\\d+$", "").toLowerCase(Locale.ROOT);
		if (key == null || key.startsWith("local:") || a.isEmpty()) return "constantiam";
		String best = "";
		for (String part : a.split("\\.")) {
			if (part.matches("play|mc|www|join|server|net|com|org|gg|io|co|uk|eu|us|me|xyz") || part.matches("\\d+")) continue;
			if (part.length() > best.length()) best = part;
		}
		return best.isEmpty() ? "constantiam" : best;
	}

	private static int nameScore(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		return RELAY_NAME.matcher(n).find() ? 5 : n.contains("chat") ? 2 : n.contains("log") ? 1 : 0;
	}

	/**
	 * The text channels the token can read in the Discord server named like {@code word} (every one it's in if none is),
	 * the likeliest chat relay first: the channels with relay-like names have their last messages read, and the one
	 * where most read as game chat comes first. Reads only. Blocks.
	 */
	static Found findChannels(String token, boolean bot, String word) {
		Found found = new Found();
		Fetched out = new Fetched();
		try {
			JsonArray guilds = get(apiBase + "/users/@me/guilds", token, bot, out);
			if (guilds == null) {
				found.problem = out.problem;
				return found;
			}
			List<JsonObject> chosen = new ArrayList<>(), all = new ArrayList<>();
			String w = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
			for (JsonElement e : guilds) {
				if (!e.isJsonObject()) continue;
				JsonObject g = e.getAsJsonObject();
				all.add(g);
				if (!w.isEmpty() && g.get("name").getAsString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").contains(w)) chosen.add(g);
			}
			if (all.isEmpty()) {
				found.problem = bot ? "The bot isn't in any Discord server yet: send the server's staff the request." : "Your account isn't in any Discord server.";
				return found;
			}
			if (chosen.isEmpty()) chosen = all.subList(0, Math.min(5, all.size()));
			// Every text channel, the relay-like names first.
			record Candidate(JsonObject guild, JsonObject channel, int score, int position) {}
			List<Candidate> candidates = new ArrayList<>();
			for (JsonObject g : chosen) {
				found.guilds.add(g.get("name").getAsString());
				out.problem = null;
				JsonArray channels = get(apiBase + "/guilds/" + g.get("id").getAsString() + "/channels", token, bot, out);
				if (channels == null) continue;
				for (JsonElement e : channels) {
					if (!e.isJsonObject()) continue;
					JsonObject ch = e.getAsJsonObject();
					int type = ch.has("type") ? ch.get("type").getAsInt() : -1;
					if (type != 0 && type != 5) continue;
					String name = ch.has("name") ? ch.get("name").getAsString() : "";
					candidates.add(new Candidate(g, ch, nameScore(name), ch.has("position") ? ch.get("position").getAsInt() : 0));
				}
			}
			candidates.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparingInt(Candidate::position));
			for (int i = 0; i < candidates.size(); i++) {
				Candidate c = candidates.get(i);
				int relay = 0, sampled = 0;
				if (i < SAMPLE_CHANNELS) {
					out.problem = null;
					JsonArray page = get(apiBase + "/channels/" + c.channel().get("id").getAsString() + "/messages?limit=" + SAMPLE_MESSAGES, token, bot, out);
					if (page == null) continue; // Can't read it.
					for (JsonElement e : page) {
						if (!e.isJsonObject()) continue;
						sampled++;
						Kind k = parse(e.getAsJsonObject()).kind();
						if (k != Kind.UNKNOWN) relay++;
					}
				}
				found.picks.add(new Pick(c.guild().get("id").getAsString(), c.guild().get("name").getAsString(), c.channel().get("id").getAsString(),
						c.channel().has("name") ? c.channel().get("name").getAsString() : "?", relay, sampled));
			}
			// Most relay lines first; ties keep the name order.
			List<Pick> sorted = new ArrayList<>(found.picks);
			sorted.sort(Comparator.comparingInt((Pick p) -> p.sampled() == 0 ? 0 : p.relayLines() * 100 / p.sampled()).reversed());
			found.picks.clear();
			found.picks.addAll(sorted);
			if (found.picks.isEmpty()) found.problem = (bot ? "The bot" : "Your account") + " can't read any text channel in " + String.join(", ", found.guilds) + ".";
		} catch (IOException e) {
			found.problem = e.getMessage();
		}
		return found;
	}

	/** {@link #findChannels} for the server you're on (Constantiam if none), off the game thread. */
	public static void findAsync(MinecraftClient client, java.util.function.Consumer<Found> done) {
		NNConfig c = NNConfig.INSTANCE;
		String token = token();
		if (token.isEmpty()) {
			Found f = new Found();
			f.problem = "Paste a token first.";
			done.accept(f);
			return;
		}
		String key = !c.discordServer.isEmpty() ? c.discordServer : client.world != null && client.getNetworkHandler() != null ? Ledger.worldKey(client) : null;
		boolean bot = !c.discordOwnAccount;
		CompletableFuture.supplyAsync(() -> findChannels(token, bot, serverWord(key))).thenAccept(f -> client.execute(() -> done.accept(f)));
	}

	// ---- Filing what came in ----

	/** What one catch-up filed, for the chat line and the settings. */
	static final class Counts {
		int chat, joins, deaths, skipped, unknown, alerts, nameAlerts;
		final List<String> unread = new ArrayList<>();
	}

	static Counts lastCounts;

	private static void apply(MinecraftClient client, Ledger ledger, Fetched result, List<long[]> sessions, long until, boolean manual) {
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		Counts n = new Counts();
		for (Message m : result.messages) {
			if (seenLive(m.time(), sessions)) {
				n.skipped++;
				continue;
			}
			switch (m.kind()) {
				case UNKNOWN -> {
					n.unknown++;
					if (n.unread.size() < 5 && !m.raw().isEmpty()) n.unread.add(m.raw().length() > 80 ? m.raw().substring(0, 80) + "…" : m.raw());
				}
				case CHAT -> {
					n.chat++;
					String alert = mod.watcher.fromDiscord(client, m);
					if (alert != null) {
						n.alerts++;
						if (alert.equals("said your name")) n.nameAlerts++;
					}
				}
				case JOIN, LEAVE -> {
					n.joins++;
					mod.watcher.fromDiscord(client, m);
				}
				case DEATH -> {
					n.deaths++;
					mod.watcher.fromDiscord(client, m);
				}
			}
		}
		synchronized (DiscordFeed.class) {
			if (!result.newestId.isEmpty()) state().lastId = result.newestId;
			long oldest = snowflakeTime(state().lastId) - 7 * 86_400_000L;
			state().sessions.removeIf(s -> s[1] < oldest);
			state().lastRun = System.currentTimeMillis();
		}
		lastCounts = n;
		int filed = n.chat + n.joins + n.deaths;
		status = filed == 0 ? "Up to date: nothing new while you were away."
				: String.format(Locale.ROOT, "Caught up: %,d chat lines, %,d joins/leaves, %,d deaths while you were away%s.", n.chat, n.joins, n.deaths,
						n.unknown > 0 ? String.format(Locale.ROOT, " (%,d lines not understood)", n.unknown) : "");
		state().lastResult = status;
		saveState();
		ledger.markDirty();
		if (filed > 0 || manual) {
			var line = Text.literal(filed == 0 ? "Discord: nothing new while you were away." : String.format(Locale.ROOT, "Caught up from Discord: %,d lines while you were away", filed))
					.formatted(Formatting.GRAY);
			if (n.alerts > 0) line.append(Text.literal(String.format(Locale.ROOT, " (%d set off alerts%s)", n.alerts,
					n.nameAlerts > 0 ? ", " + n.nameAlerts + " said your name" : "")).formatted(Formatting.YELLOW));
			if (filed > 0) line.append(Text.literal(". ")).append(Text.literal("[Show]").styled(s -> s.withColor(Formatting.AQUA).withUnderline(true)
					.withClickEvent(new ClickEvent.RunCommand("/nn chat"))));
			Alerts.chat(line);
		}
	}

	/** Said while you were on the server (so you saw it live), or after you joined this time. */
	static boolean seenLive(long time, List<long[]> sessions) {
		for (long[] s : sessions) if (time >= s[0] - SLACK && time <= s[1] + SLACK) return true;
		return false;
	}

	// ---- Reading what the relay posts ----

	static final String NAME = "[A-Za-z0-9_]{1,16}";
	private static final Pattern ANGLE = Pattern.compile("^<(" + NAME + ")>\\s+(.+)$");
	private static final Pattern SEPARATED = Pattern.compile("^(?:\\[[^\\]]{1,24}\\]\\s*)?(" + NAME + ")\\s*(?::|»|>>|→|\\|)\\s+(.+)$");
	private static final Pattern JOIN_LEAVE = Pattern.compile("^(?:\\[[^\\]]{1,24}\\]\\s*)?(" + NAME
			+ ") (?:has )?(joined|left|quit|logged (?:in|out|on|off)|disconnected|connected)(?: the (?:game|server))?[.!]?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern DEATH = Pattern.compile("^(" + NAME + ") (?:was |died|drowned|blew up|fell |burned|went up|went off|tried to swim|"
			+ "hit the ground|starved|suffocated|withered|froze|experienced kinetic|walked into|discovered the floor|didn't want to live|"
			+ "left the confines|was squashed|was pricked|was struck|was killed|was slain|was shot|was blown|was fireballed|was impaled|"
			+ "was stung|was poked|was squished|was obliterated|was doomed|was skewered|was frozen|was roasted)(.*)$");

	/** Discord's formatting taken off: bold/italic marks, escapes, mentions, custom emoji, quotes. */
	static String clean(String s) {
		if (s == null) return "";
		// Emoji are dropped (custom ones, and :shortcodes: some relays write), so ":outbox_tray: Steve left" reads as Steve leaving.
		String t = s.replaceAll("<a?:\\w+:\\d+>", "").replaceAll(":(?=[a-z0-9_+\\-]*[a-z])[a-z0-9_+\\-]{2,32}:", "").replaceAll("<[@#&!]*\\d+>", "")
				.replaceAll("(?m)^>\\s?", "").replace("**", "").replace("__", "").replace("~~", "").replace("`", "")
				.replaceAll("\\\\([*_~`|>\\\\<])", "$1");
		return t.replaceAll("\\s+", " ").strip();
	}

	/** A relay line, read: chat (who said what), a join or leave, a death, or unknown. */
	static Message parse(JsonObject m) {
		String id = m.get("id").getAsString();
		long time = snowflakeTime(id);
		try {
			if (m.has("timestamp") && !m.get("timestamp").isJsonNull()) time = OffsetDateTime.parse(m.get("timestamp").getAsString()).toInstant().toEpochMilli();
		} catch (RuntimeException ignored) {
			// The id says when, to the millisecond.
		}
		String content = clean(m.has("content") && !m.get("content").isJsonNull() ? m.get("content").getAsString() : "");
		JsonObject author = m.has("author") && m.get("author").isJsonObject() ? m.getAsJsonObject("author") : new JsonObject();
		String authorName = clean(author.has("username") ? author.get("username").getAsString() : "");
		boolean webhook = m.has("webhook_id") && !m.get("webhook_id").isJsonNull();
		// A relay that posts each line as the player (webhooks named after them, maybe with a rank in brackets).
		String asPlayer = authorName.replaceFirst("^\\[[^\\]]{1,24}\\]\\s*", "");
		if (webhook && asPlayer.matches(NAME) && !content.isEmpty()) {
			Message system = fromText(id, time, content);
			if (system != null && system.kind() != Kind.CHAT && system.name().equalsIgnoreCase(asPlayer)) return system;
			return new Message(id, time, Kind.CHAT, asPlayer, content, content);
		}
		Message fromContent = content.isEmpty() ? null : fromText(id, time, content);
		if (fromContent != null) return fromContent;
		// Embeds: joins and deaths are often the embed's author line; chat can be author + description.
		if (m.has("embeds") && m.get("embeds").isJsonArray()) {
			for (JsonElement e : m.getAsJsonArray("embeds")) {
				if (!e.isJsonObject()) continue;
				JsonObject embed = e.getAsJsonObject();
				String eAuthor = embed.has("author") && embed.get("author").isJsonObject() && embed.getAsJsonObject("author").has("name")
						? clean(embed.getAsJsonObject("author").get("name").getAsString()) : "";
				String title = embed.has("title") ? clean(embed.get("title").getAsString()) : "";
				String description = embed.has("description") ? clean(embed.get("description").getAsString()) : "";
				for (String part : new String[] {eAuthor, title}) {
					Message sys = part.isEmpty() ? null : fromText(id, time, part);
					if (sys != null && sys.kind() != Kind.CHAT) return sys;
				}
				String who = eAuthor.replaceFirst("^\\[[^\\]]{1,24}\\]\\s*", "");
				if (who.matches(NAME) && !description.isEmpty()) return new Message(id, time, Kind.CHAT, who, description, description);
				Message fromDescription = description.isEmpty() ? null : fromText(id, time, description);
				if (fromDescription != null) return fromDescription;
			}
		}
		return new Message(id, time, Kind.UNKNOWN, "", content, content);
	}

	/** One line of relay text, read; null if it isn't any of the shapes relays use. */
	static Message fromText(String id, long time, String raw) {
		String t = raw.replaceFirst("^[^A-Za-z0-9<\\[]+", "").strip();
		Matcher m = ANGLE.matcher(t);
		if (m.matches()) return new Message(id, time, Kind.CHAT, m.group(1), m.group(2).strip(), raw);
		m = JOIN_LEAVE.matcher(t);
		if (m.matches()) {
			String verb = m.group(2).toLowerCase(Locale.ROOT);
			boolean left = verb.matches("left|quit|logged (?:out|off)|disconnected");
			return new Message(id, time, left ? Kind.LEAVE : Kind.JOIN, m.group(1), left ? "left the game" : "joined the game", raw);
		}
		m = DEATH.matcher(t);
		if (m.matches()) return new Message(id, time, Kind.DEATH, m.group(1), t, raw);
		m = SEPARATED.matcher(t);
		if (m.matches()) return new Message(id, time, Kind.CHAT, m.group(1), m.group(2).strip(), raw);
		return null;
	}
}
