package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.greysworkshop.naughtyornice.mixin.PlayerListHudAccessor;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryLoader;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.ServerLinks;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.dimension.DimensionType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Everything a player can know about the server they're on, for the Server tab: what it runs (brand, version,
 * plugins from its commands, plugin channels, data packs from its registries), how it's running (ticks per second
 * from its clock, your ping), the world's settings it sends (difficulty, border, distances, the few game rules that
 * reach players), what's on its tab list and sidebar, and what changed since you were last on. Each row says
 * whether the server told it outright or it was seen; what servers never send is said too, not guessed.
 */
public final class ServerDetails {
	private ServerDetails() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

	/** How sure a row is: the server said so, it was seen happening, or it can't be known from here. */
	public enum Sure { TOLD, SEEN, UNKNOWN }

	public record Row(String label, String value, String note, Sure sure) {}

	public record Section(String title, List<Row> rows) {}

	// ---- From the join packet ----
	private static boolean joined;
	private static long joinedAt;
	private static int viewDistance, simulationDistance, seaLevel;
	private static boolean secureChat;

	// ---- From the server's clock: [game time in ticks, wall ms when it came] ----
	private static final Deque<long[]> clock = new ArrayDeque<>();
	private static Boolean dayCycle;
	private static long lastClock;
	/** The slowest minute since you joined, in ticks per second; -1 until there's been a minute. */
	private static double worstMinute = -1;

	/** What was known when you were last on this server, and what's known now, to say what changed. */
	static final class Visit {
		String brand = "";
		String version = "";
		List<String> plugins = new ArrayList<>();
		List<String> channels = new ArrayList<>();
		int commands;
		long at;
	}

	private static Visit previous;
	private static String previousKey;
	private static Visit current;
	private static long lastSnapshot;

	public static void onJoin(GameJoinS2CPacket p) {
		joined = true;
		joinedAt = System.currentTimeMillis();
		viewDistance = p.viewDistance();
		simulationDistance = p.simulationDistance();
		seaLevel = p.commonPlayerSpawnInfo().seaLevel();
		secureChat = p.enforcesSecureChat();
		clock.clear();
		dayCycle = null;
		lastClock = 0;
		worstMinute = -1;
		current = null;
		lastSnapshot = 0;
	}

	public static void onTime(WorldTimeUpdateS2CPacket p) {
		long now = System.currentTimeMillis();
		dayCycle = p.tickDayTime();
		lastClock = now;
		clock.addLast(new long[] {p.time(), now});
		while (clock.size() > 2 && now - clock.peekFirst()[1] > 5 * 60_000) clock.removeFirst();
		double minute = tps(60_000);
		if (minute >= 0 && now - joinedAt > 90_000) worstMinute = worstMinute < 0 ? minute : Math.min(worstMinute, minute);
	}

	/** Ticks per second over the last {@code windowMs}, from the server's own clock; -1 without a few seconds of it. */
	public static double tps(long windowMs) {
		if (clock.size() < 2) return -1;
		long[] last = clock.peekLast();
		long[] first = null;
		for (long[] c : clock) {
			if (last[1] - c[1] <= windowMs) {
				first = c;
				break;
			}
		}
		if (first == null || last[1] - first[1] < 4_000) return -1;
		double t = (last[0] - first[0]) * 1000.0 / (last[1] - first[1]);
		return Math.max(0, Math.min(20, t));
	}

	/** Seconds since the server's clock last came; it comes once a second while it's running. */
	public static long sinceClock() {
		return lastClock == 0 ? -1 : (System.currentTimeMillis() - lastClock) / 1000;
	}

	/** Once a minute: what the server runs, to compare with your next visit. */
	public static void tick(MinecraftClient client, Ledger ledger) {
		if (client.getNetworkHandler() == null || ledger.key() == null) return;
		if (!ledger.key().equals(previousKey)) {
			previousKey = ledger.key();
			previous = load(ledger.sideFile(".server.json"));
		}
		long now = System.currentTimeMillis();
		if (now - lastSnapshot < 60_000) return;
		lastSnapshot = now;
		current = snapshot(client.getNetworkHandler());
	}

	/** On leaving: what this visit saw becomes "last time". */
	public static void leave(Ledger ledger) {
		if (current == null || ledger.key() == null) return;
		Path file = ledger.sideFile(".server.json");
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(current));
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not write {}", file, e);
		}
		previousKey = null;
		current = null;
		joined = false;
	}

	private static Visit load(Path file) {
		if (!Files.exists(file)) return null;
		try {
			Visit v = GSON.fromJson(Files.readString(file), Visit.class);
			if (v == null) return null;
			if (v.plugins == null) v.plugins = new ArrayList<>();
			if (v.channels == null) v.channels = new ArrayList<>();
			if (v.brand == null) v.brand = "";
			if (v.version == null) v.version = "";
			return v;
		} catch (IOException | RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.warn("Could not read {}", file, e);
			return null;
		}
	}

	private static Visit snapshot(ClientPlayNetworkHandler h) {
		Visit v = new Visit();
		v.brand = h.getBrand() == null ? "" : h.getBrand();
		ServerInfo info = h.getServerInfo();
		v.version = info == null || info.version == null ? "" : info.version.getString();
		List<String> commands = commands(h);
		v.commands = commands.size();
		for (ServerSoftware.Plugin p : ServerSoftware.plugins(commands)) v.plugins.add(p.name());
		v.channels.addAll(channels());
		v.at = System.currentTimeMillis();
		return v;
	}

	/** The commands the server lets you use, by name, as it sent them; not the ones your own mods add on your side. */
	public static List<String> commands(ClientPlayNetworkHandler h) {
		Set<String> mine = new java.util.HashSet<>();
		var local = net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.getActiveDispatcher();
		if (local != null) for (CommandNode<?> node : local.getRoot().getChildren()) mine.add(node.getName());
		List<String> out = new ArrayList<>();
		for (CommandNode<?> node : h.getCommandDispatcher().getRoot().getChildren()) if (!mine.contains(node.getName())) out.add(node.getName());
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	/** The plugin channels the server said it listens on. */
	public static List<String> channels() {
		try {
			Set<Identifier> ids = ClientPlayNetworking.getSendable();
			List<String> out = new ArrayList<>();
			for (Identifier id : ids) out.add(id.toString());
			out.sort(null);
			return out;
		} catch (IllegalStateException e) {
			return List.of();
		}
	}

	/** What changed since your last visit, as lines; empty when nothing did or there was no last visit. */
	public static List<String> changes() {
		if (previous == null || current == null) return List.of();
		List<String> out = new ArrayList<>();
		if (!previous.brand.equals(current.brand) && !previous.brand.isEmpty()) out.add("Software: " + previous.brand + " → " + current.brand);
		if (!previous.version.equals(current.version) && !previous.version.isEmpty()) out.add("Version: " + previous.version + " → " + current.version);
		for (String p : current.plugins) if (!previous.plugins.contains(p)) out.add("New plugin: " + p);
		for (String p : previous.plugins) if (!current.plugins.contains(p)) out.add("Gone: " + p);
		for (String c : current.channels) if (!previous.channels.contains(c)) out.add("New channel: " + c);
		if (previous.commands > 0 && Math.abs(previous.commands - current.commands) >= 3) {
			out.add("Commands you can use: " + previous.commands + " → " + current.commands);
		}
		return out;
	}

	public static long lastVisit() {
		return previous == null ? 0 : previous.at;
	}

	/** Every section of the Server tab, read now. */
	public static List<Section> read(MinecraftClient client, NaughtyOrNiceClient mod) {
		List<Section> out = new ArrayList<>();
		ClientPlayNetworkHandler h = client.getNetworkHandler();
		if (h == null || client.world == null || client.player == null) return out;
		boolean hide = ModsPanel.streamSafe();
		ServerInfo info = h.getServerInfo();
		List<String> commands = commands(h);

		// ---- The server ----
		List<Row> s = new ArrayList<>();
		if (info != null) {
			s.add(new Row("Address", hide ? "hidden (stream-safe)" : info.address, info.name.isBlank() || info.name.equals(info.address) ? "" : "\"" + info.name + "\" in your list", Sure.TOLD));
			if (info.label != null && !info.label.getString().isBlank()) s.add(new Row("Message of the day", oneLine(info.label.getString()), "", Sure.TOLD));
			if (info.version != null) s.add(new Row("Version", info.version.getString(), "protocol " + info.protocolVersion
					+ (info.protocolVersion != net.minecraft.SharedConstants.getProtocolVersion() ? " (yours is " + net.minecraft.SharedConstants.getProtocolVersion() + ": ViaVersion translates)" : ""), Sure.TOLD));
		} else {
			s.add(new Row("Address", "this world (singleplayer or LAN)", "", Sure.TOLD));
		}
		String brand = h.getBrand();
		String[] split = ServerSoftware.brand(brand);
		s.add(new Row("Software", split[1].isEmpty() ? "not said" : split[1], brand == null ? "" : "brand \"" + brand + "\"", brand == null ? Sure.UNKNOWN : Sure.TOLD));
		List<String> core = ServerSoftware.coreFromCommands(commands);
		if (!core.isEmpty()) s.add(new Row("Built on", String.join(", ", core), "from its command names", Sure.SEEN));
		if (!split[0].isEmpty()) s.add(new Row("Proxy in front", split[0], "players may be on other servers behind it", Sure.TOLD));
		int listed = h.getListedPlayerListEntries().size();
		String max = info != null && info.players != null ? " of " + info.players.max() + " slots" : "";
		s.add(new Row("Players", listed + " on the tab list" + max, info != null && info.players != null ? info.players.online() + " when you connected" : "", Sure.TOLD));
		if (joined) s.add(new Row("You've been on", duration((System.currentTimeMillis() - joinedAt) / 1000), lastVisit() > 0 ? "last visit " + ago(lastVisit()) : "first visit seen", Sure.TOLD));
		if (info != null) s.add(new Row("Server resource pack", switch (info.getResourcePackPolicy()) {
			case ENABLED -> "accepted automatically";
			case DISABLED -> "declined";
			case PROMPT -> "asks you";
		}, "your setting for this server", Sure.TOLD));
		s.add(new Row("Secure chat", secureChat ? "enforced" : "not enforced", secureChat ? "messages are signed by your account" : "the server can change or fake chat lines", Sure.TOLD));
		ServerLinks links = h.getServerLinks();
		if (links != null && !links.isEmpty()) {
			for (ServerLinks.Entry e : links.entries()) s.add(new Row("Link: " + e.getText().getString(), e.link().toString(), "", Sure.TOLD));
		}
		out.add(new Section("The server", s));

		// ---- How it's running ----
		List<Row> perf = new ArrayList<>();
		double t10 = tps(12_000), t60 = tps(60_000);
		perf.add(new Row("Ticks per second", t10 < 0 ? "measuring…" : String.format(Locale.ROOT, "%.1f now, %.1f over a minute", t10, t60 < 0 ? t10 : t60),
				t10 < 0 ? "from the server's clock, a few seconds after joining" : lag(t10), t10 < 0 ? Sure.UNKNOWN : Sure.SEEN));
		if (worstMinute >= 0) perf.add(new Row("Slowest minute", String.format(Locale.ROOT, "%.1f TPS", worstMinute), "since you joined", Sure.SEEN));
		long since = sinceClock();
		if (since >= 3) perf.add(new Row("Server stalled", since + " s with no tick", "it may be lagging, frozen or restarting", Sure.SEEN));
		PlayerListEntry me = h.getPlayerListEntry(client.player.getUuid());
		if (me != null) perf.add(new Row("Your ping", me.getLatency() + " ms", "as the server reports it on the tab list", Sure.TOLD));
		List<Integer> pings = new ArrayList<>();
		for (PlayerListEntry e : h.getListedPlayerListEntries()) if (e.getLatency() > 0 && e != me) pings.add(e.getLatency());
		if (!pings.isEmpty()) {
			pings.sort(null);
			perf.add(new Row("Everyone's ping", "median " + pings.get(pings.size() / 2) + " ms", "highest " + pings.getLast() + " ms, " + pings.size() + " players", Sure.TOLD));
		}
		perf.add(new Row("Loaded around you", client.world.getChunkManager().getLoadedChunkCount() + " chunks, " + client.world.getRegularEntityCount() + " entities", "", Sure.SEEN));
		out.add(new Section("How it's running", perf));

		// ---- The world ----
		List<Row> w = new ArrayList<>();
		var props = client.world.getLevelProperties();
		w.add(new Row("Difficulty", props.getDifficulty().getName() + (props.isDifficultyLocked() ? " (locked)" : ""), props.isHardcore() ? "Hardcore: one life" : "", Sure.TOLD));
		w.add(new Row("This dimension", client.world.getRegistryKey().getValue().toString(), "", Sure.TOLD));
		List<String> dims = new ArrayList<>();
		for (var k : h.getWorldKeys()) dims.add(k.getValue().toString().replace("minecraft:", ""));
		dims.sort(null);
		w.add(new Row("Dimensions", String.join(", ", dims), dims.size() > 3 ? "more than the three vanilla ones" : "", Sure.TOLD));
		DimensionType dim = client.world.getDimension();
		int top = dim.minY() + dim.height();
		boolean vanillaHeight = client.world.getRegistryKey() == World.OVERWORLD ? dim.minY() == -64 && top == 320 : dim.minY() == 0 && top == 256;
		w.add(new Row("Build height", dim.minY() + " to " + (top - 1), vanillaHeight ? "vanilla" : "changed from vanilla", Sure.TOLD));
		if (seaLevel != 0) w.add(new Row("Sea level", String.valueOf(seaLevel), "", Sure.TOLD));
		long day = props.getTimeOfDay() / 24000, tod = Math.floorMod(props.getTimeOfDay(), 24000);
		w.add(new Row("Time", "day " + day + ", " + clockTime(tod), "world age " + duration(props.getTime() / 20), Sure.TOLD));
		w.add(new Row("Weather", props.isThundering() ? "thunder" : props.isRaining() ? "rain" : "clear", "", Sure.TOLD));
		var spawn = props.getSpawnPoint();
		if (spawn != null) w.add(new Row("World spawn", hide ? "hidden (stream-safe)" : spawn.getPos().getX() + " " + spawn.getPos().getY() + " " + spawn.getPos().getZ(),
				spawn.getDimension().getValue().getPath(), Sure.TOLD));
		WorldBorder border = client.world.getWorldBorder();
		double size = border.getSize();
		String borderText = size >= 59_000_000 ? "vanilla, ±29,999,984" : String.format(Locale.ROOT, "%,.0f blocks across", size);
		String borderNote = hide ? "" : String.format(Locale.ROOT, "centre %.0f %.0f, you're %,.0f from its edge", border.getCenterX(), border.getCenterZ(),
				border.getDistanceInsideBorder(client.player.getX(), client.player.getZ()));
		if (border.getSizeLerpTarget() != size) borderNote = (border.getSizeLerpTarget() < size ? "shrinking" : "growing") + " to " + String.format(Locale.ROOT, "%,.0f", border.getSizeLerpTarget());
		w.add(new Row("World border", borderText, borderNote, Sure.TOLD));
		w.add(new Row("View distance", viewDistance + " chunks (server)", "yours is " + client.options.getViewDistance().getValue()
				+ "; you see the smaller", joined ? Sure.TOLD : Sure.UNKNOWN));
		w.add(new Row("Simulation distance", simulationDistance + " chunks", "how far from players crops grow and mobs move", joined ? Sure.TOLD : Sure.UNKNOWN));
		Set<Identifier> flags = new TreeSet<>(FeatureFlags.FEATURE_MANAGER.toId(h.getEnabledFeatures()));
		flags.remove(Identifier.ofVanilla("vanilla"));
		w.add(new Row("Experiments", flags.isEmpty() ? "none" : String.join(", ", flags.stream().map(Identifier::getPath).toList()), "", Sure.TOLD));
		out.add(new Section("The world", w));

		// ---- Game rules ----
		List<Row> g = new ArrayList<>();
		g.add(new Row("Day and night", dayCycle == null ? "not told yet" : dayCycle ? "the sun moves (doDaylightCycle on)" : "time stands still (doDaylightCycle off)", "",
				dayCycle == null ? Sure.UNKNOWN : Sure.TOLD));
		g.add(new Row("Death screen", client.player.showsDeathScreen() ? "shown (doImmediateRespawn off)" : "skipped (doImmediateRespawn on)", "", Sure.TOLD));
		g.add(new Row("F3 details", client.player.hasReducedDebugInfo() ? "reduced (reducedDebugInfo on)" : "full", "", Sure.TOLD));
		g.add(new Row("Crafting", client.player.isLimitedCraftingEnabled() ? "only unlocked recipes (doLimitedCrafting on)" : "any recipe", "", Sure.TOLD));
		int kills = mod.diagnostics.count(Diagnostics.Kind.KILL), deaths = mod.diagnostics.count(Diagnostics.Kind.DEATH);
		g.add(new Row("Death messages", kills + deaths > 0 ? "on: " + (kills + deaths) + " seen" : "none seen yet", "showDeathMessages", kills + deaths > 0 ? Sure.SEEN : Sure.UNKNOWN));
		g.add(new Row("PvP", kills > 0 ? "on: " + kills + " player kills seen" : "no player kill seen yet", "", kills > 0 ? Sure.SEEN : Sure.UNKNOWN));
		g.add(new Row("Everything else", "not sent to players", "keepInventory, mobGriefing, fire spread, spawn radius, natural regeneration and the rest "
				+ "stay on the server; only /gamerule (for operators) shows them", Sure.UNKNOWN));
		out.add(new Section("Game rules", g));

		// ---- You ----
		List<Row> you = new ArrayList<>();
		you.add(new Row("Game mode", client.interactionManager == null ? "?" : client.interactionManager.getCurrentGameMode().asString(), "", Sure.TOLD));
		int level = client.player.getPermissions() instanceof LeveledPermissionPredicate l ? l.getLevel().ordinal() : 0;
		you.add(new Row("Operator level", level == 0 ? "0 (a normal player)" : level + " of 4", "", Sure.TOLD));
		you.add(new Row("Commands you can use", String.valueOf(commands.size()), "servers send only the ones you're allowed", Sure.TOLD));
		out.add(new Section("You", you));

		// ---- Plugins ----
		List<Row> pl = new ArrayList<>();
		for (ServerSoftware.Plugin p : ServerSoftware.plugins(commands)) {
			pl.add(new Row(p.name(), p.note().label, String.join(" ", p.evidence()), Sure.SEEN));
		}
		if (pl.isEmpty()) pl.add(new Row("None shown", "", "Servers can hide plugin commands, and you only see the ones you may use", Sure.UNKNOWN));
		out.add(new Section("Plugins its commands show", pl));

		// ---- Channels ----
		List<Row> ch = new ArrayList<>();
		Map<String, List<String>> byWhat = new TreeMap<>();
		for (String c : channels()) {
			String what = ServerSoftware.channel(c);
			if (!what.isEmpty()) byWhat.computeIfAbsent(what, k -> new ArrayList<>()).add(c);
		}
		for (var e : byWhat.entrySet()) ch.add(new Row(e.getKey(), "", String.join(" ", e.getValue()), Sure.TOLD));
		if (ch.isEmpty()) ch.add(new Row("None", "", "the server talks on no plugin channels you also have", Sure.UNKNOWN));
		out.add(new Section("Plugin channels", ch));

		// ---- Custom content from data packs ----
		List<Row> dp = new ArrayList<>();
		Map<String, Set<String>> namespaces = new TreeMap<>();
		for (RegistryLoader.Entry<?> entry : RegistryLoader.SYNCED_REGISTRIES) {
			Registry<?> reg = h.getRegistryManager().getOptional(entry.key()).orElse(null);
			if (reg == null) continue;
			for (Identifier id : reg.getIds()) {
				if (!id.getNamespace().equals("minecraft")) namespaces.computeIfAbsent(id.getNamespace(), k -> new TreeSet<>()).add(entry.key().getValue().getPath());
			}
		}
		for (var e : namespaces.entrySet()) dp.add(new Row(e.getKey(), "", "adds " + String.join(", ", e.getValue()), Sure.TOLD));
		if (dp.isEmpty()) dp.add(new Row("None", "", "no biomes, enchantments, damage types or the like beyond vanilla", Sure.TOLD));
		out.add(new Section("Data packs (custom content)", dp));

		// ---- Tab list and sidebar ----
		List<Row> tl = new ArrayList<>();
		PlayerListHudAccessor tab = (PlayerListHudAccessor) client.inGameHud.getPlayerListHud();
		if (tab.naughtyornice$header() != null) for (String line : lines(tab.naughtyornice$header())) tl.add(new Row("Tab list top", line, "", Sure.TOLD));
		if (tab.naughtyornice$footer() != null) for (String line : lines(tab.naughtyornice$footer())) tl.add(new Row("Tab list bottom", line, "", Sure.TOLD));
		ScoreboardObjective side = h.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
		if (side != null) {
			tl.add(new Row("Sidebar", side.getDisplayName().getString(), "", Sure.TOLD));
			List<ScoreboardEntry> entries = new ArrayList<>(h.getScoreboard().getScoreboardEntries(side));
			entries.removeIf(ScoreboardEntry::hidden);
			entries.sort(Comparator.comparingInt(ScoreboardEntry::value).reversed());
			for (ScoreboardEntry e : entries.subList(0, Math.min(15, entries.size()))) {
				String line = oneLine(Team.decorateName(h.getScoreboard().getScoreHolderTeam(e.owner()), e.name()).getString());
				if (!line.isBlank()) tl.add(new Row("", line, "", Sure.TOLD));
			}
		}
		if (tl.isEmpty()) tl.add(new Row("Nothing", "", "no tab list header or footer, no sidebar", Sure.TOLD));
		out.add(new Section("Tab list and sidebar", tl));

		// ---- Changes ----
		List<Row> changed = new ArrayList<>();
		for (String c : changes()) changed.add(new Row(c, "", "", Sure.SEEN));
		if (!changed.isEmpty()) out.add(0, new Section("Changed since your last visit (" + ago(lastVisit()) + ")", changed));
		return out;
	}

	/** Every section as plain text, to copy or save. */
	public static String report(MinecraftClient client, NaughtyOrNiceClient mod) {
		StringBuilder sb = new StringBuilder();
		String nl = System.lineSeparator();
		sb.append("Naughty or Nice server report · ").append(Sharing.placeName(mod.ledger.key())).append(" · ").append(LocalDateTime.now().format(STAMP)).append(nl);
		for (Section sec : read(client, mod)) {
			sb.append(nl).append("== ").append(sec.title()).append(" ==").append(nl);
			for (Row r : sec.rows()) {
				sb.append(r.label().isEmpty() ? "  " : r.label() + ": ").append(r.value());
				if (!r.note().isEmpty()) sb.append(r.value().isEmpty() ? "" : "  (").append(r.note()).append(r.value().isEmpty() ? "" : ")");
				if (r.sure() == Sure.SEEN) sb.append("  [seen]");
				sb.append(nl);
			}
		}
		ClientPlayNetworkHandler h = client.getNetworkHandler();
		if (h != null) sb.append(nl).append("== Every command you can use ==").append(nl).append(String.join(" ", commands(h))).append(nl);
		return sb.toString();
	}

	/** Saves the report to config/naughtyornice/server-reports; returns the file. */
	public static Path save(MinecraftClient client, NaughtyOrNiceClient mod) throws IOException {
		Path dir = NNConfig.dir().resolve("server-reports");
		Files.createDirectories(dir);
		String name = ("server-" + Sharing.placeName(mod.ledger.key()) + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)))
				.replaceAll("[^A-Za-z0-9._-]", "_");
		Path file = dir.resolve(name + ".txt");
		Files.writeString(file, report(client, mod));
		return file;
	}

	static String lag(double tps) {
		return tps >= 19.5 ? "running smoothly" : tps >= 17 ? "a little behind" : tps >= 12 ? "lagging: things move slowly" : "lagging badly";
	}

	private static List<String> lines(Text text) {
		List<String> out = new ArrayList<>();
		for (String l : text.getString().split("\n")) if (!l.isBlank()) out.add(l.strip());
		return out;
	}

	private static String oneLine(String s) {
		return s.replace('\n', ' ').replaceAll("\\s+", " ").strip();
	}

	private static String clockTime(long tod) {
		// Tick 0 is 6 in the morning.
		long minutes = (tod * 1440 / 24000 + 360) % 1440;
		return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
	}

	static String duration(long seconds) {
		if (seconds < 60) return seconds + " s";
		if (seconds < 3600) return seconds / 60 + " min";
		if (seconds < 86400) return seconds / 3600 + " h " + seconds % 3600 / 60 + " min";
		return String.format(Locale.ROOT, "%,d days", seconds / 86400);
	}

	private static String ago(long at) {
		return duration((System.currentTimeMillis() - at) / 1000) + " ago";
	}
}
