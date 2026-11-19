package com.greysworkshop.naughtyornice;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A mining job started from the Baritone screen: what it's after, how many you wanted, and what you've picked up
 * since it started (counted from your inventory, so it's what you really got). When you asked for an amount, it
 * stops Baritone once you have it, whatever the blocks drop (diamonds from diamond ore, raw iron from iron ore).
 */
public final class MineJob {
	private MineJob() {}

	private static List<BlockCatalog.Entry> targets = List.of();
	private static int amount;
	private static long startedAt;
	private static Map<Item, Integer> before = Map.of();
	private static Set<Item> counted = Set.of();
	private static boolean active;
	/** What happened when it last ended, for the screen ("Got 64 Diamond: stopped"). */
	private static String endedWith;

	/** Starts mining these; amount 0 means everything Baritone can reach. Returns null, or why it couldn't. */
	public static String start(MinecraftClient client, List<BlockCatalog.Entry> what, int howMany) {
		if (what.isEmpty()) return "Pick something to mine first";
		if (!BaritoneBridge.present()) return "Baritone isn't installed";
		List<String> ids = new ArrayList<>();
		List<Block> blocks = new ArrayList<>();
		for (BlockCatalog.Entry e : what) {
			String why = BlockCatalog.cantMine(e);
			if (why != null) return why;
			for (String id : e.ids()) if (!ids.contains(id)) ids.add(id);
			blocks.addAll(e.blocks());
		}
		if (!BaritoneBridge.run("mine " + String.join(" ", ids))) return "Baritone didn't take that (see its message in chat)";
		targets = List.copyOf(what);
		amount = Math.max(0, howMany);
		startedAt = System.currentTimeMillis();
		counted = BlockCatalog.drops(blocks);
		before = inventory(client);
		active = true;
		endedWith = null;
		return null;
	}

	public static boolean active() {
		return active;
	}

	public static List<BlockCatalog.Entry> targets() {
		return targets;
	}

	public static int amount() {
		return amount;
	}

	public static long startedAt() {
		return startedAt;
	}

	public static String endedWith() {
		return endedWith;
	}

	/** What you've picked up since it started, of the things it's after, most first. */
	public static Map<Item, Integer> gained(MinecraftClient client) {
		Map<Item, Integer> now = inventory(client), out = new LinkedHashMap<>();
		for (Item i : counted) {
			int d = now.getOrDefault(i, 0) - before.getOrDefault(i, 0);
			if (d > 0) out.put(i, d);
		}
		List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(out.entrySet());
		sorted.sort((a, b) -> b.getValue() - a.getValue());
		Map<Item, Integer> result = new LinkedHashMap<>();
		for (var e : sorted) result.put(e.getKey(), e.getValue());
		return result;
	}

	/** The most of any one thing it's after you've picked up (what the amount is counted against). */
	public static int got(MinecraftClient client) {
		int best = 0;
		for (int v : gained(client).values()) best = Math.max(best, v);
		return best;
	}

	/** Each tick: stops when you have the amount you asked for, and notices when Baritone stops by itself. */
	public static void tick(MinecraftClient client) {
		if (!active || client.player == null) return;
		if (amount > 0 && got(client) >= amount) {
			BaritoneBridge.run("stop");
			active = false;
			endedWith = "Got " + amount + " " + what() + ": stopped";
			Alerts.play(Alerts.Sound.ARRIVED);
			client.player.sendMessage(net.minecraft.text.Text.literal("§a[NN] §f" + endedWith), true);
			return;
		}
		// A moment to get going, then: Baritone gave up, or something else stopped it.
		if (System.currentTimeMillis() - startedAt > 3000 && !BaritoneBridge.processActive("getMineProcess")) {
			active = false;
			int got = got(client);
			endedWith = got > 0 ? "Mining stopped with " + got + " " + what() : "Mining stopped (none found nearby, or see Baritone's message in chat)";
		}
	}

	/** Ends it when you press Stop (so it isn't reported as given up). */
	public static void stopped() {
		active = false;
	}

	/** "Diamond", "Diamond and Iron", or "3 kinds of block". */
	public static String what() {
		if (targets.isEmpty()) return "";
		if (targets.size() == 1) return plain(targets.get(0));
		if (targets.size() == 2) return plain(targets.get(0)) + " and " + plain(targets.get(1));
		return targets.size() + " kinds of block";
	}

	private static String plain(BlockCatalog.Entry e) {
		return e.family() ? e.name().replace(" (all ore)", "").replace(" (every wood)", "") : e.name();
	}

	private static Map<Item, Integer> inventory(MinecraftClient client) {
		Map<Item, Integer> out = new HashMap<>();
		if (client.player == null) return out;
		var inv = client.player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		return out;
	}
}
