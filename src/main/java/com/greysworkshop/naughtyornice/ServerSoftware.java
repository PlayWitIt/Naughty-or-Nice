package com.greysworkshop.naughtyornice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a server runs, read from what it sends every player: its brand ("Paper", "Velocity -> Purpur"), the commands
 * it lets you use (plugins register theirs as "plugin:command" too), and the plugin channels it talks on. Only what
 * those show for certain; no guessing from behaviour. Plain Java, so ./gradlew testIntel can check it.
 */
public final class ServerSoftware {
	private ServerSoftware() {}

	/** What a plugin is for, when that matters to you as a player. */
	public enum Note {
		NONE(""), ANTICHEAT("Anticheat"), LOGGING("Logs every block placed and broken, so griefs can be looked up and rolled back"),
		WEB_MAP("A live web map of the world: anyone with the link may see bases"), CLAIMS("Land claims or protected regions"),
		PROXY("A proxy in front of more than one server"), MODERATION("Bans, mutes and reports"), BEDROCK("Lets Bedrock players join");

		public final String label;

		Note(String label) {
			this.label = label;
		}
	}

	/** One plugin (or mod) the server shows, and how it was told. */
	public record Plugin(String name, Note note, List<String> evidence) {}

	/** Known plugins by their command namespace or a command only they have: id to name and note. */
	private static final Map<String, Object[]> KNOWN = new LinkedHashMap<>();

	private static void known(String name, Note note, String... ids) {
		for (String id : ids) KNOWN.put(id, new Object[] {name, note});
	}

	static {
		known("EssentialsX", Note.NONE, "essentials", "ess", "essentialsx", "essentialschat", "essentialsspawn");
		known("LuckPerms", Note.NONE, "luckperms", "lp", "lpb", "lpv");
		known("CoreProtect", Note.LOGGING, "coreprotect", "co", "core");
		known("Prism", Note.LOGGING, "prism", "pr");
		known("LogBlock", Note.LOGGING, "logblock", "lb");
		known("Ledger", Note.LOGGING, "ledger");
		known("Dynmap", Note.WEB_MAP, "dynmap", "dmap");
		known("BlueMap", Note.WEB_MAP, "bluemap");
		known("squaremap", Note.WEB_MAP, "squaremap");
		known("Pl3xMap", Note.WEB_MAP, "pl3xmap");
		known("Grim", Note.ANTICHEAT, "grim", "grimac");
		known("Vulcan", Note.ANTICHEAT, "vulcan");
		known("Spartan", Note.ANTICHEAT, "spartan");
		known("Matrix", Note.ANTICHEAT, "matrix");
		known("NoCheatPlus", Note.ANTICHEAT, "nocheatplus", "ncp");
		known("Polar", Note.ANTICHEAT, "polar");
		known("Themis", Note.ANTICHEAT, "themis");
		known("AAC", Note.ANTICHEAT, "aac");
		known("Verus", Note.ANTICHEAT, "verus");
		known("Karhu", Note.ANTICHEAT, "karhu");
		known("Intave", Note.ANTICHEAT, "intave");
		known("WorldGuard", Note.CLAIMS, "worldguard", "wg", "rg", "region");
		known("GriefPrevention", Note.CLAIMS, "griefprevention", "abandonclaim", "trustlist", "claimslist");
		known("Lands", Note.CLAIMS, "lands");
		known("Towny", Note.CLAIMS, "towny", "townyadmin", "town", "nation");
		known("Factions", Note.CLAIMS, "factions", "f");
		known("PlotSquared", Note.CLAIMS, "plotsquared", "plot", "plots", "p2");
		known("Residence", Note.CLAIMS, "residence", "res");
		known("LiteBans", Note.MODERATION, "litebans", "checkban");
		known("AdvancedBan", Note.MODERATION, "advancedban");
		known("Geyser", Note.BEDROCK, "geyser");
		known("Floodgate", Note.BEDROCK, "floodgate");
		known("Velocity", Note.PROXY, "velocity", "velocityvanish");
		known("BungeeCord", Note.PROXY, "bungeecord", "bungee", "waterfall");
		known("WorldEdit", Note.NONE, "worldedit", "we", "/wand", "/pos1");
		known("FastAsyncWorldEdit", Note.NONE, "fastasyncworldedit", "fawe");
		known("ViaVersion", Note.NONE, "viaversion", "viaver", "vvbukkit", "vvvelocity");
		known("ViaBackwards", Note.NONE, "viabackwards");
		known("spark (lag profiler)", Note.NONE, "spark");
		known("Chunky", Note.NONE, "chunky");
		known("DiscordSRV", Note.NONE, "discordsrv");
		known("Multiverse", Note.NONE, "multiverse-core", "multiverse", "mv", "mvtp");
		known("Skript", Note.NONE, "skript", "sk");
		known("PlaceholderAPI", Note.NONE, "placeholderapi", "papi");
		known("TAB", Note.NONE, "tab");
		known("mcMMO", Note.NONE, "mcmmo", "mctop", "mcstats");
		known("Jobs", Note.NONE, "jobs");
		known("Vault", Note.NONE, "vault", "vault-info");
		known("ProtocolLib", Note.NONE, "protocollib");
		known("Votifier", Note.NONE, "votifier", "nuvotifier");
		known("CMI", Note.NONE, "cmi");
		known("Simple Voice Chat", Note.NONE, "voicechat");
		known("Citizens", Note.NONE, "citizens", "npc");
		known("ChestShop", Note.NONE, "chestshop");
		known("QuickShop", Note.NONE, "quickshop", "qs");
		known("AuthMe", Note.NONE, "authme");
		known("ChatControl", Note.NONE, "chatcontrol");
		known("Fabric API", Note.NONE, "fabric");
	}

	/** Ids too short or common to mean that plugin when they're a command on their own. */
	private static final java.util.Set<String> NAMESPACE_ONLY = java.util.Set.of("f", "p2", "res", "sk", "tab", "mv", "rg", "region", "we", "qs", "pr",
			"lb", "core", "npc", "town", "nation", "lands", "jobs", "vault", "discord", "packet", "fabric", "spark", "ledger", "matrix", "polar", "prism",
			"history", "plot", "plots", "verus", "aac", "ess");

	/** Namespaces the server software itself uses: not plugins. */
	private static final Map<String, String> CORE = Map.of("minecraft", "Vanilla", "bukkit", "Bukkit", "spigot", "Spigot", "paper", "Paper",
			"purpur", "Purpur", "folia", "Folia", "pufferfish", "Pufferfish", "minecraft-commands", "Vanilla");

	/**
	 * The plugins the command list shows: every "plugin:command" namespace (certain), and known commands only one
	 * plugin has (when the server hides the namespaces). Sorted by name.
	 */
	public static List<Plugin> plugins(Collection<String> commands) {
		Map<String, String[]> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		Map<String, List<String>> evidence = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (String raw : commands) {
			String c = raw.toLowerCase(Locale.ROOT);
			int colon = c.indexOf(':');
			String ns = colon > 0 ? c.substring(0, colon) : null;
			if (ns != null && CORE.containsKey(ns)) continue;
			Object[] k = KNOWN.get(ns != null ? ns : c);
			// Short or common words count only as namespaces: "/f" or "/tab" alone could be any plugin's.
			if (ns == null && (k == null || NAMESPACE_ONLY.contains(c))) continue;
			String name = k != null ? (String) k[0] : ns;
			String note = k != null ? ((Note) k[1]).name() : Note.NONE.name();
			byName.putIfAbsent(name, new String[] {name, note});
			List<String> ev = evidence.computeIfAbsent(name, x -> new ArrayList<>());
			if (ev.size() < 6 && !ev.contains("/" + c)) ev.add("/" + c);
		}
		List<Plugin> out = new ArrayList<>();
		for (var e : byName.entrySet()) out.add(new Plugin(e.getValue()[0], Note.valueOf(e.getValue()[1]), evidence.get(e.getKey())));
		return out;
	}

	/** The server software named in the command namespaces (Paper, Purpur, Bukkit...), most specific first. */
	public static List<String> coreFromCommands(Collection<String> commands) {
		List<String> out = new ArrayList<>();
		for (String key : List.of("folia", "purpur", "pufferfish", "paper", "spigot", "bukkit")) {
			String prefix = key + ":";
			if (commands.stream().anyMatch(c -> c.toLowerCase(Locale.ROOT).startsWith(prefix))) out.add(CORE.get(key));
		}
		return out;
	}

	/** The brand split into what's in front (a proxy) and what's behind it: "Velocity (git-abc) -> Paper" to [Velocity, Paper]. */
	public static String[] brand(String brand) {
		if (brand == null || brand.isBlank()) return new String[] {"", ""};
		String b = brand.strip();
		for (String sep : new String[] {" -> ", " <- ", "->", "<-"}) {
			int i = b.indexOf(sep);
			if (i > 0) {
				String left = clean(b.substring(0, i)), right = clean(b.substring(i + sep.length()));
				// BungeeCord writes "Bungee <- backend"; Velocity writes "Velocity -> backend" in some forks, "backend (Velocity)" in others.
				return new String[] {left, right};
			}
		}
		int paren = b.indexOf(" (");
		if (paren > 0 && b.toLowerCase(Locale.ROOT).matches(".*\\((velocity|bungeecord|waterfall)[^)]*\\)\\s*$")) {
			return new String[] {clean(b.substring(paren + 2, b.length() - 1)), clean(b.substring(0, paren))};
		}
		return new String[] {"", clean(b)};
	}

	private static String clean(String s) {
		return s.replaceAll("\\s*\\((git|build|version)[^)]*\\)", "").strip();
	}

	/** What a plugin channel (or its namespace) says the server has. */
	public static String channel(String id) {
		String c = id.toLowerCase(Locale.ROOT);
		String ns = c.contains(":") ? c.substring(0, c.indexOf(':')) : c;
		return switch (ns) {
			case "bungeecord" -> "A BungeeCord or Velocity proxy";
			case "velocity" -> "A Velocity proxy";
			case "worldedit" -> "WorldEdit (its selection outline)";
			case "voicechat" -> "Simple Voice Chat";
			case "plasmo" -> "Plasmo Voice";
			case "floodgate" -> "Geyser / Floodgate (Bedrock players)";
			case "luckperms" -> "LuckPerms";
			case "xaerominimap", "xaeroworldmap" -> "Xaero's map support";
			case "journeymap" -> "JourneyMap support";
			case "voxelmap", "worldinfo" -> "Minimap world id";
			case "lunar", "apollo" -> "Lunar Client support";
			case "labymod", "labymod3" -> "LabyMod support";
			case "badlion" -> "Badlion Client support";
			case "axiom" -> "Axiom (building tool)";
			case "noxesium" -> "Noxesium";
			case "fabric", "fabric-screen-handler-api-v1", "c" -> "Fabric";
			case "forge", "fml", "neoforge" -> "Forge / NeoForge";
			case "minecraft" -> "";
			default -> ns;
		};
	}
}
