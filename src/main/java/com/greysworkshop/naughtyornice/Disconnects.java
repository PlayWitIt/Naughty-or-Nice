package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Why you got disconnected: the reason shown, who was around, and (the useful part) what pulled the plug. The
 * game is watched at the moment the connection is closed, so it can tell the server kicking you from you leaving,
 * the connection dropping, an error, or another mod disconnecting you on purpose (auto-log features do this when
 * someone they don't know as a friend comes near). Kept in config/naughtyornice/disconnects.json.
 */
public final class Disconnects {
	private Disconnects() {}

	/** What closed the connection. */
	public enum Kind {
		/** The server sent a disconnect: a kick, ban, restart, or the server's anti-cheat. */
		SERVER,
		/** You pressed Disconnect or quit. */
		YOU,
		/** Another mod closed the connection itself. */
		MOD,
		/** The game hit an error handling something the server sent. */
		ERROR,
		/** The connection dropped or timed out. */
		CONNECTION,
		UNKNOWN
	}

	public static final class Nearby {
		public String name = "";
		/** "Friend (Close friends)", "Nice", "Naughty", "Look Into" or "". */
		public String list = "";
		public int distance;
	}

	public static final class Entry {
		public long time;
		public String server = "";
		/** What the disconnect screen said. */
		public String reason = "";
		public Kind kind = Kind.UNKNOWN;
		/** The mod that did it (its name), for MOD and ERROR. */
		public String culprit = "";
		/** The code that did it, or the error, for anyone who wants to dig in. */
		public String detail = "";
		/** Players in sight just before, nearest first. */
		public List<Nearby> nearby = new ArrayList<>();
		/** The last few lines of chat. */
		public List<String> chat = new ArrayList<>();
		/** Already told about it in chat. */
		public boolean told;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX = 40;
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ROOT);

	private static List<Entry> entries;
	/** Worked out when the connection was closed (maybe on the network thread), finished on the game thread. */
	private static volatile Entry pending;
	/** Who was in sight as of the last second, and where. */
	private static volatile List<Nearby> lastNearby = List.of();
	private static volatile String lastServer = "";
	private static int version;

	private static Path file() {
		return NNConfig.dir().resolve("disconnects.json");
	}

	public static synchronized List<Entry> all() {
		if (entries == null) {
			entries = new ArrayList<>();
			if (Files.exists(file())) {
				try (Reader reader = Files.newBufferedReader(file())) {
					List<Entry> loaded = GSON.fromJson(reader, new TypeToken<List<Entry>>() {}.getType());
					if (loaded != null) {
						loaded.removeIf(e -> e == null || e.reason == null);
						for (Entry e : loaded) {
							if (e.kind == null) e.kind = Kind.UNKNOWN;
							if (e.nearby == null) e.nearby = new ArrayList<>();
							if (e.chat == null) e.chat = new ArrayList<>();
							if (e.culprit == null) e.culprit = "";
							if (e.detail == null) e.detail = "";
							if (e.server == null) e.server = "";
						}
						entries.addAll(loaded);
					}
				} catch (IOException | RuntimeException e) {
					NaughtyOrNiceClient.LOGGER.error("Could not read {}", file(), e);
				}
			}
		}
		return entries;
	}

	public static int version() {
		return version;
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			try (Writer writer = Files.newBufferedWriter(file())) {
				GSON.toJson(all(), writer);
			}
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not write {}", file(), e);
		}
	}

	public static synchronized void clear() {
		all().clear();
		version++;
		save();
	}

	// ---- While playing ----

	/** Once a second: who's in sight, so it's known who was around when the connection went. */
	public static void snapshot(MinecraftClient client, Ledger ledger) {
		if (client.world == null || client.player == null) return;
		List<Nearby> list = new ArrayList<>();
		for (PlayerEntity player : client.world.getPlayers()) {
			if (player == client.player) continue;
			Nearby n = new Nearby();
			n.name = player.getGameProfile().name();
			n.distance = (int) player.distanceTo(client.player);
			n.list = listName(ledger.get(player.getUuid()));
			list.add(n);
		}
		list.sort(Comparator.comparingInt(n -> n.distance));
		if (list.size() > 12) list = new ArrayList<>(list.subList(0, 12));
		lastNearby = list;
		lastServer = ledger.key() == null ? "" : ledger.key();
	}

	static String listName(Profile p) {
		if (p == null) return "";
		return switch (p.verdict) {
			case FRIEND -> "Friend (" + Friends.circleName(p) + ")";
			case NICE -> "Nice";
			case NAUGHTY -> "Naughty";
			case LOOK -> "Look Into";
			case NONE -> "";
		};
	}

	// ---- The moment of disconnecting (hooks) ----

	/** The client's connection is being closed: work out by whom from the code that's doing it. */
	public static void closing(ClientConnection connection, DisconnectionInfo info) {
		if (!NNConfig.INSTANCE.recordDisconnects) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getNetworkHandler() == null || client.getNetworkHandler().getConnection() != connection) return;
		Entry found = pending;
		// The first close is the real one; the game closes again on the way out.
		if (found != null && System.currentTimeMillis() - found.time < 5000) return;
		Entry e = new Entry();
		e.time = System.currentTimeMillis();
		e.reason = info == null || info.reason() == null ? "" : info.reason().getString();
		StackTraceElement[] stack = new Throwable().getStackTrace();
		classify(e, stack);
		pending = e;
	}

	/** The game hit an error with something the server sent, and is about to disconnect because of it. */
	public static void packetError(Throwable error) {
		if (!NNConfig.INSTANCE.recordDisconnects || error == null) return;
		Entry e = new Entry();
		e.time = System.currentTimeMillis();
		e.kind = Kind.ERROR;
		Throwable root = error;
		while (root.getCause() != null && root.getCause() != root) root = root.getCause();
		e.detail = root.getClass().getName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
		for (StackTraceElement frame : root.getStackTrace()) {
			String mod = modOf(frame);
			if (mod != null) {
				e.culprit = mod;
				e.detail += " at " + frame;
				break;
			}
		}
		pending = e;
	}

	/** The connection is gone: record it, with what was worked out when it closed. */
	public static void disconnected(DisconnectionInfo info) {
		if (!NNConfig.INSTANCE.recordDisconnects) return;
		Entry e = pending;
		pending = null;
		if (e == null || System.currentTimeMillis() - e.time > 10_000) {
			e = new Entry();
			e.time = System.currentTimeMillis();
			e.kind = Kind.UNKNOWN;
		}
		String reason = info == null || info.reason() == null ? "" : info.reason().getString();
		if (!reason.isEmpty()) e.reason = reason;
		if (e.kind == Kind.UNKNOWN || e.kind == Kind.CONNECTION) {
			String lower = e.reason.toLowerCase(Locale.ROOT);
			if (lower.contains("timed out") || lower.contains("connection reset") || lower.contains("connection lost")) e.kind = Kind.CONNECTION;
		}
		e.server = lastServer;
		e.nearby = new ArrayList<>(lastNearby);
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		if (mod != null) {
			List<Profile.Line> feed = mod.chatLogger.feed();
			for (int i = Math.max(0, feed.size() - 5); i < feed.size(); i++) {
				Profile.Line line = feed.get(i);
				if (!ChatLogger.isMarker(line)) e.chat.add((line.system ? "" : "<" + line.sender + "> ") + line.text);
			}
		}
		// One of this mod's own hooks failing just then would show up here (they're caught, so it shouldn't matter).
		Safe.Caught caught = Safe.near(e.time, 3000);
		if (caught != null && e.kind == Kind.ERROR && e.culprit.isEmpty()) e.culprit = "Naughty or Nice (" + caught.where + ")";
		synchronized (Disconnects.class) {
			all().add(e);
			while (all().size() > MAX) all().remove(0);
			version++;
		}
		save();
		NaughtyOrNiceClient.LOGGER.info("Disconnected: {}", line(e));
	}

	/** Frames that belong to the game, Java, the network library, Fabric or the mixin system. */
	private static boolean vanilla(String className) {
		return className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.") || className.startsWith("sun.")
				|| className.startsWith("net.minecraft.") || className.startsWith("com.mojang.") || className.startsWith("io.netty.")
				|| className.startsWith("net.fabricmc.") || className.startsWith("org.spongepowered.") || className.startsWith("com.llamalad7.")
				|| className.startsWith("com.google.") || className.startsWith("it.unimi.") || className.startsWith("org.lwjgl.")
				|| className.startsWith("com.greysworkshop.naughtyornice.Disconnects");
	}

	/** "handler$zzb000$meteor-client$onTick": a mod's mixin code inside a game class. */
	private static final Pattern MIXIN_METHOD = Pattern.compile("\\$[a-z]{3}\\d{3}\\$([a-z0-9_.-]+)\\$");

	/** Which mod a stack frame is from (its name), or null if it's the game's own code. */
	static String modOf(StackTraceElement frame) {
		String className = frame.getClassName();
		Matcher m = MIXIN_METHOD.matcher(frame.getMethodName());
		if (m.find()) {
			String id = m.group(1);
			if (!id.equals(NaughtyOrNiceClient.MOD_ID) && !id.startsWith("fabric") && !id.equals("minecraft")) {
				return FabricLoader.getInstance().getModContainer(id).map(c -> c.getMetadata().getName()).orElse(id);
			}
			return null;
		}
		if (vanilla(className)) return null;
		return modOfClass(className);
	}

	/** The mod whose jar holds a class, by name; the package if it can't be told. */
	static String modOfClass(String className) {
		try {
			Class<?> type = Class.forName(className, false, Disconnects.class.getClassLoader());
			URL where = type.getProtectionDomain() == null || type.getProtectionDomain().getCodeSource() == null ? null
					: type.getProtectionDomain().getCodeSource().getLocation();
			if (where != null) {
				Path path = Paths.get(where.toURI()).toAbsolutePath().normalize();
				for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
					try {
						for (Path origin : mod.getOrigin().getPaths()) {
							Path o = origin.toAbsolutePath().normalize();
							if (o.equals(path) || path.startsWith(o) || o.startsWith(path)) return mod.getMetadata().getName();
						}
					} catch (UnsupportedOperationException ignored) {
						// Nested jars have no paths of their own.
					}
				}
				// A jar that isn't a mod: its file name says most; a folder says nothing, so use the package.
				if (Files.isRegularFile(path)) return path.getFileName().toString();
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError | java.net.URISyntaxException ignored) {
			// Fall back to the package.
		}
		int dot = className.lastIndexOf('.');
		return dot > 0 ? className.substring(0, dot) : className;
	}

	/** Sorts out who closed the connection from the code doing it. */
	static void classify(Entry e, StackTraceElement[] stack) {
		// Skip past the closing itself, to whoever asked for it.
		int start = 0;
		for (int i = 0; i < stack.length; i++) {
			if (stack[i].getClassName().equals(ClientConnection.class.getName())) start = i + 1;
		}
		StringBuilder trail = new StringBuilder();
		for (int i = start; i < stack.length && i < start + 6; i++) {
			if (!trail.isEmpty()) trail.append(" < ");
			trail.append(shortFrame(stack[i]));
		}
		e.detail = trail.toString();
		boolean serverSaid = false;
		for (int i = start; i < stack.length; i++) {
			StackTraceElement frame = stack[i];
			String c = frame.getClassName();
			String m = frame.getMethodName();
			// Past here it's the game's main loop calling into everything; only what's above counts.
			if (c.contains("NetworkThreadUtils") || c.endsWith("ThreadExecutor")
					|| (c.endsWith("MinecraftClient") && (m.equals("tick") || m.equals("render") || m.equals("run")))) break;
			String mod = modOf(frame);
			if (mod != null) {
				// Auto-log features often pretend the server sent a disconnect, so a mod asking counts first.
				e.kind = Kind.MOD;
				e.culprit = mod;
				e.detail = shortFrame(frame) + " · " + e.detail;
				return;
			}
			if (c.endsWith("ClientCommonNetworkHandler") && m.equals("onDisconnect")) serverSaid = true;
			if (c.endsWith("GameMenuScreen") || (c.endsWith("MinecraftClient") && (m.equals("stop") || m.equals("scheduleStop")))) {
				e.kind = Kind.YOU;
				return;
			}
			if (c.startsWith("io.netty.")) {
				e.kind = Kind.CONNECTION;
				return;
			}
		}
		e.kind = serverSaid ? Kind.SERVER : Kind.UNKNOWN;
	}

	private static String shortFrame(StackTraceElement frame) {
		String c = frame.getClassName();
		return c.substring(c.lastIndexOf('.') + 1) + "." + frame.getMethodName();
	}

	// ---- Telling the player ----

	/** A player near you whose name is in the reason (auto-log features name who set them off). */
	public static Nearby namedInReason(Entry e) {
		String lower = e.reason.toLowerCase(Locale.ROOT);
		for (Nearby n : e.nearby) {
			if (n.name.length() >= 3 && Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(n.name.toLowerCase(Locale.ROOT)) + "(?![a-z0-9_])").matcher(lower).find()) return n;
		}
		return null;
	}

	/** What happened, in plain words. */
	public static String cause(Entry e) {
		Nearby named = namedInReason(e);
		String lower = e.reason.toLowerCase(Locale.ROOT);
		return switch (e.kind) {
			case MOD -> e.culprit + " disconnected you on purpose"
					+ (named != null ? ", because " + named.name + " came near (" + named.distance + " blocks)" : "")
					+ ". That's its auto-disconnect (AutoLog) setting, which has its own friends list: add them there, or turn it off.";
			case ERROR -> "The game hit an error with something the server sent" + (e.culprit.isEmpty() ? "" : ", in " + e.culprit) + ".";
			case SERVER -> lower.contains("chat") && lower.contains("valid")
					? "The server rejected chat signing (Minecraft's secure chat). A mod like No Chat Reports usually fixes this."
					: "The server disconnected you (a kick, a restart, or its anti-cheat).";
			case YOU -> "You left the game.";
			case CONNECTION -> "The connection dropped or timed out (internet or server lag).";
			case UNKNOWN -> named != null ? named.name + " is named in the reason; another mod probably disconnected you because they came near." : "Not sure what did it.";
		};
	}

	/** One line for logs and copying. */
	public static String line(Entry e) {
		StringBuilder sb = new StringBuilder();
		sb.append(WHEN.format(Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()))).append(" · ").append(Sharing.placeName(e.server));
		sb.append(" · \"").append(e.reason).append("\" · ").append(cause(e));
		if (!e.nearby.isEmpty()) sb.append(" · Nearby: ").append(nearbyText(e, 6));
		return sb.toString();
	}

	public static String nearbyText(Entry e, int max) {
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < e.nearby.size() && i < max; i++) {
			Nearby n = e.nearby.get(i);
			parts.add(n.name + " (" + (n.list.isEmpty() ? "" : n.list + ", ") + n.distance + " blocks)");
		}
		if (e.nearby.size() > max) parts.add("+" + (e.nearby.size() - max) + " more");
		return String.join(", ", parts);
	}

	/** Everything, for the report and the Copy button. */
	public static String report() {
		StringBuilder sb = new StringBuilder();
		String nl = System.lineSeparator();
		List<Entry> list = new ArrayList<>(all());
		if (list.isEmpty()) return "(no disconnects recorded)" + nl;
		for (int i = list.size() - 1; i >= 0 && i >= list.size() - 10; i--) {
			Entry e = list.get(i);
			sb.append(line(e)).append(nl);
			sb.append("    what: ").append(e.kind).append(e.culprit.isEmpty() ? "" : " by " + e.culprit).append(nl);
			if (!e.detail.isEmpty()) sb.append("    code: ").append(e.detail).append(nl);
			for (String c : e.chat) sb.append("    chat: ").append(c).append(nl);
		}
		return sb.toString();
	}

	/** Back on a server after being disconnected by something other than you: say what it was. */
	public static void tellIfNew() {
		Entry e;
		synchronized (Disconnects.class) {
			List<Entry> list = all();
			e = list.isEmpty() ? null : list.get(list.size() - 1);
			if (e == null || e.told) return;
			e.told = true;
		}
		save();
		if (e.kind == Kind.YOU || System.currentTimeMillis() - e.time > 6 * 3600_000L) return;
		Formatting color = e.kind == Kind.MOD || e.kind == Kind.ERROR ? Formatting.GOLD : Formatting.GRAY;
		Text message = Text.literal("Last disconnect (" + ShopScreen.ago(e.time) + "): ").formatted(Formatting.GRAY)
				.append(Text.literal("\"" + (e.reason.length() > 80 ? e.reason.substring(0, 80) + "…" : e.reason) + "\" ").formatted(Formatting.WHITE))
				.append(Text.literal(cause(e)).formatted(color))
				.append(Text.literal(e.nearby.isEmpty() ? "" : " Nearby: " + nearbyText(e, 3) + ".").formatted(Formatting.GRAY))
				.append(Text.literal(" [Details]").styled(s -> s.withColor(Formatting.AQUA).withUnderline(true)
						.withClickEvent(new ClickEvent.RunCommand("/nn disconnects"))));
		Alerts.chat(message);
	}
}
