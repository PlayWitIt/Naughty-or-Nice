package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Makes Baritone's settings readable: each one's plain name, what it does (Baritone's own description of it, taken from
 * its source and shipped with this mod), the group it belongs in, and presets that set several at once.
 */
public final class BaritoneSettingsGuide {
	private BaritoneSettingsGuide() {}

	/** The groups the settings page offers, in order. */
	public enum Category {
		MOVEMENT("Moving"), BREAKING("Breaking & placing"), SAFETY("Safety"), MINING("Mining"), FOLLOW("Follow, farm & explore"), ELYTRA("Elytra"),
		BUILDING("Building"), LOOKS("What it shows"), CHAT("Chat & messages"), PATHING("Pathfinding (advanced)"), OTHER("Other");

		public final String label;

		Category(String label) {
			this.label = label;
		}
	}

	private static Map<String, String> docs;

	/** Baritone's own description of a setting ("" if it has none). */
	public static String description(String name) {
		if (docs == null) {
			docs = Map.of();
			try (InputStream in = BaritoneSettingsGuide.class.getResourceAsStream("/assets/naughtyornice/baritone_settings.json")) {
				if (in != null) {
					Map<String, String> read = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {}.getType());
					if (read != null) docs = read;
				}
			} catch (Exception e) {
				NaughtyOrNiceClient.LOGGER.warn("Couldn't read Baritone's setting descriptions", e);
			}
		}
		String d = docs.getOrDefault(name, "");
		String plain = PLAIN.get(name);
		return plain != null ? plain + (d.isEmpty() ? "" : " — " + d) : d;
	}

	/** Clearer names for the settings people reach for most. */
	private static final Map<String, String> NAMES = Map.ofEntries(
			Map.entry("allowBreak", "Break blocks in the way"),
			Map.entry("allowPlace", "Place blocks (bridge, pillar up)"),
			Map.entry("allowSprint", "Sprint"),
			Map.entry("allowParkour", "Parkour jumps"),
			Map.entry("allowParkourPlace", "Place a block to land a parkour jump"),
			Map.entry("allowParkourAscend", "Parkour jumps upwards"),
			Map.entry("allowDiagonalDescend", "Step down diagonally"),
			Map.entry("allowDiagonalAscend", "Step up diagonally"),
			Map.entry("allowDownward", "Dig straight down"),
			Map.entry("allowJumpAt256", "Jump at the build limit"),
			Map.entry("allowWaterBucketFall", "Water-bucket long falls"),
			Map.entry("allowVines", "Climb vines"),
			Map.entry("allowWalkOnBottomSlab", "Walk on bottom slabs"),
			Map.entry("allowInventory", "Rearrange my inventory for tools"),
			Map.entry("autoTool", "Switch to the best tool"),
			Map.entry("assumeWalkOnWater", "Assume I can walk on water (Jesus)"),
			Map.entry("assumeWalkOnLava", "Assume I can walk on lava"),
			Map.entry("assumeSafeWalk", "Assume safe-walk is on"),
			Map.entry("assumeStep", "Assume step-up is on"),
			Map.entry("avoidance", "Stay away from mobs and spawners"),
			Map.entry("mobAvoidanceRadius", "How far to stay from mobs"),
			Map.entry("mobSpawnerAvoidanceRadius", "How far to stay from spawners"),
			Map.entry("legitMine", "Only mine ores it can see (legit)"),
			Map.entry("legitMineIncludeDiagonals", "Legit mining: count ores seen diagonally"),
			Map.entry("legitMineYLevel", "Legit mining: which Y level to dig at"),
			Map.entry("mineScanDroppedItems", "Pick up the drops while mining"),
			Map.entry("exploreForBlocks", "Explore when none are known"),
			Map.entry("allowOnlyExposedOres", "Only mine ores next to air"),
			Map.entry("minYLevelWhileMining", "Lowest Y to mine at"),
			Map.entry("maxYLevelWhileMining", "Highest Y to mine at"),
			Map.entry("followRadius", "How close to follow"),
			Map.entry("followOffsetDistance", "Follow this far behind"),
			Map.entry("followOffsetDirection", "Follow from this angle"),
			Map.entry("replantCrops", "Replant crops after harvesting"),
			Map.entry("replantNetherWart", "Replant nether wart"),
			Map.entry("freeLook", "Let me look around while it walks"),
			Map.entry("antiCheatCompatibility", "Anticheat-friendly turning"),
			Map.entry("renderPath", "Show the path"),
			Map.entry("renderGoal", "Show the goal box"),
			Map.entry("renderSelectionBoxes", "Show what it's about to break or place"),
			Map.entry("renderCachedChunks", "Show cached chunks (heavy)"),
			Map.entry("chatControl", "Read # commands I type in chat"),
			Map.entry("prefixControl", "Use the # prefix in chat"),
			Map.entry("prefix", "Command prefix in chat"),
			Map.entry("chatDebug", "Debug messages in chat"),
			Map.entry("desktopNotifications", "Desktop notifications"),
			Map.entry("acceptableThrowawayItems", "Blocks it may place to bridge"),
			Map.entry("blocksToAvoid", "Blocks to walk around"),
			Map.entry("blocksToAvoidBreaking", "Blocks to try not to break"),
			Map.entry("blocksToDisallowBreaking", "Blocks it must never break"),
			Map.entry("maxFallHeightNoWater", "Highest drop without water"),
			Map.entry("maxFallHeightBucket", "Highest drop with a water bucket"),
			Map.entry("buildInLayers", "Build one layer at a time"),
			Map.entry("buildIgnoreBlocks", "Blocks to skip when building"),
			Map.entry("mapArtMode", "Map art mode (top layer only)"),
			Map.entry("elytraAutoJump", "Elytra: jump to take off by itself"),
			Map.entry("elytraFireworkSpeed", "Elytra: firework speed"),
			Map.entry("elytraMinimumDurability", "Elytra: land below this durability"),
			Map.entry("elytraMinFireworksBeforeLanding", "Elytra: land with this many fireworks left"),
			Map.entry("elytraAllowEmergencyLand", "Elytra: land when it must"),
			Map.entry("primaryTimeoutMS", "Time to plan a path (ms)"),
			Map.entry("failureTimeoutMS", "Time to keep trying a hard path (ms)"),
			Map.entry("pathingMaxChunkBorderFetch", "Chunks it reads past the edge"),
			Map.entry("axisHeight", "Highway height for #axis"));

	/** A plain sentence for settings whose own description is too technical to start with. */
	private static final Map<String, String> PLAIN = Map.ofEntries(
			Map.entry("legitMine", "Off: it knows where ores are from the chunks it has loaded, like x-ray. On: it only goes for ores it can see."),
			Map.entry("avoidance", "Keeps paths away from hostile mobs and spawners."),
			Map.entry("freeLook", "On: you can turn the camera while it walks."),
			Map.entry("allowInventory", "Lets it move items around your inventory to get the right tool or blocks."),
			Map.entry("acceptableThrowawayItems", "What it may place when it has to bridge or pillar up."));

	/** "Break blocks in the way" for allowBreak; for the rest, its name in words ("Min Y level while mining"). */
	public static String plainName(String name) {
		String n = NAMES.get(name);
		if (n != null) return n;
		String words = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
		StringBuilder out = new StringBuilder();
		for (String w : words.split(" ")) {
			if (w.isEmpty()) continue;
			String shown = w.equals("MS") ? "(ms)" : w.length() == 1 && Character.isUpperCase(w.charAt(0)) ? w
					: w.equals(w.toUpperCase(Locale.ROOT)) && w.length() > 1 ? w : w.toLowerCase(Locale.ROOT);
			if (out.isEmpty()) shown = Character.toUpperCase(shown.charAt(0)) + shown.substring(1);
			if (!out.isEmpty()) out.append(' ');
			out.append(shown);
		}
		return out.toString();
	}

	/** Which group a setting goes in, by its name. */
	public static Category category(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		if (n.startsWith("elytra")) return Category.ELYTRA;
		if (n.startsWith("render") || n.startsWith("color") || n.contains("fade") || n.contains("highlight") || n.startsWith("pathrender")
				|| n.contains("linewidth") || n.contains("selectionopacity") || n.contains("goalrender") || n.startsWith("cachedchunks")) return Category.LOOKS;
		if (n.startsWith("chat") || n.startsWith("prefix") || n.startsWith("log") || n.contains("notification") || n.startsWith("echo") || n.startsWith("censor")
				|| n.contains("baritoneprefix") || n.contains("messagetag") || n.startsWith("toast") || n.equals("verbosecommandexceptions")) return Category.CHAT;
		if (n.startsWith("mine") || n.startsWith("legitmine") || n.contains("whilemining") || n.equals("exploreforblocks") || n.contains("exposedores")
				|| n.startsWith("maxcachedworldscan") || n.equals("mineGoalUpdateInterval".toLowerCase(Locale.ROOT)) || n.equals("forceinternalmining")
				|| n.equals("internalminingairexception") || n.equals("mineondroppeditems")) return Category.MINING;
		if (n.startsWith("follow") || n.startsWith("farm") || n.startsWith("replant") || n.startsWith("explore") || n.equals("worldexploringchunkoffset")
				|| n.equals("exploremaintainy") || n.equals("exploreChunkSetMinimumSize".toLowerCase(Locale.ROOT))) return Category.FOLLOW;
		if (n.startsWith("build") || n.startsWith("schematic") || n.startsWith("mapart") || n.startsWith("layer") || n.contains("layer") || n.startsWith("okifair")
				|| n.startsWith("okifwater") || n.equals("skipfailedlayers") || n.startsWith("incorrectsize") || n.startsWith("startatlayer")
				|| n.equals("notificationonbuildfinished") || n.contains("schematic")) return Category.BUILDING;
		if (n.contains("avoid") || n.contains("fallheight") || n.contains("waterbucket") || n.equals("assumesafewalk") || n.contains("lava")
				|| n.contains("spawner") || n.contains("mobavoid") || n.equals("avoidupdatingfallingblocks") || n.startsWith("disallow")) return Category.SAFETY;
		if (n.equals("allowbreak") || n.equals("allowplace") || n.contains("throwaway") || n.contains("breaking") || n.contains("autotool") || n.contains("tool")
				|| n.contains("placement") || n.startsWith("blockbreak") || n.startsWith("blockplace") || n.equals("allowinventory") || n.contains("inventory")
				|| n.equals("allowplaceinfluid") || n.contains("blockreach") || n.equals("allowbreakanyway") || n.contains("walkwhilebreaking")) return Category.BREAKING;
		if (n.startsWith("allow") || n.startsWith("assume") || n.contains("sprint") || n.contains("parkour") || n.contains("jump") || n.contains("ladder")
				|| n.contains("vine") || n.contains("swim") || n.contains("diagonal") || n.contains("overshoot") || n.equals("freelook")
				|| n.equals("anticheatcompatibility") || n.contains("look") || n.contains("sneak") || n.contains("walk")) return Category.MOVEMENT;
		if (n.contains("cost") || n.contains("path") || n.contains("timeout") || n.contains("plan") || n.contains("heuristic") || n.contains("backtrack")
				|| n.contains("cutoff") || n.contains("improvement") || n.contains("chunk") || n.contains("cache") || n.contains("axis") || n.contains("goal")
				|| n.contains("movement") || n.contains("tick") || n.contains("simplify") || n.contains("repropagation")) return Category.PATHING;
		return Category.OTHER;
	}

	/** A preset: a name, what it's for, and the settings it sets (as Baritone writes them). */
	public record Preset(String name, String why, Map<String, String> values) {}

	public static final List<Preset> PRESETS = List.of(
			new Preset("Careful", "No parkour, no long drops, stays away from mobs: slower, but it rarely dies or falls.", ordered(
					"allowParkour", "false", "allowParkourPlace", "false", "allowParkourAscend", "false", "allowDiagonalDescend", "false",
					"allowWaterBucketFall", "false", "maxFallHeightNoWater", "3", "avoidance", "true", "allowSprint", "true")),
			new Preset("Fast", "Sprints, jumps gaps and cuts corners: quickest, a bit riskier.", ordered(
					"allowSprint", "true", "allowParkour", "true", "allowParkourAscend", "true", "allowDiagonalDescend", "true", "allowDiagonalAscend", "true",
					"overshootTraverse", "true", "sprintAscends", "true")),
			new Preset("Legit-looking", "Mines only what it can see and turns like a player, for servers that watch for bots.", ordered(
					"legitMine", "true", "freeLook", "false", "antiCheatCompatibility", "true", "allowParkour", "false")));

	private static Map<String, String> ordered(String... kv) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
		return m;
	}
}
