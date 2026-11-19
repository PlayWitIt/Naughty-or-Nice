package com.greysworkshop.naughtyornice;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything the Baritone screen lets you pick by name and picture instead of typing an id: every block (with its
 * item's icon and its real name), ore families (stone and deepslate together, so "Diamonds" finds both), groups to
 * browse (ores, valuables, wood, stone, nether, end, plants), what's actually in the land loaded around you, and every
 * kind of mob to follow. Modded blocks are listed too, by their own names.
 */
public final class BlockCatalog {
	private BlockCatalog() {}

	/** Something to pick: one block, or a family of them (all the ore's variants). */
	public record Entry(String key, String name, ItemStack icon, List<Block> blocks, boolean family) {
		/** The ids Baritone is given, e.g. "diamond_ore deepslate_diamond_ore". */
		public List<String> ids() {
			List<String> out = new ArrayList<>();
			for (Block b : blocks) out.add(id(b));
			return out;
		}

		/** How many of these blocks are in the land loaded around you (0 if not counted yet). */
		public int near() {
			int n = 0;
			for (Block b : blocks) n += nearCount(b);
			return n;
		}
	}

	/** The groups to browse, in order; Favourites, Recent and Near you are filled in from what you use and what's around. */
	public enum Group {
		FAVOURITES("★ Favourites"), RECENT("Recent"), NEAR("Near you"), ORES("Ores"), VALUABLE("Valuable"), WOOD("Wood"), STONE("Stone & earth"),
		NETHER("Nether"), END("End"), PLANTS("Plants & farming"), BUILDING("Building"), ALL("Everything");

		public final String label;

		Group(String label) {
			this.label = label;
		}
	}

	private static List<Entry> entries;
	private static Map<String, Entry> byKey;

	/** "diamond_ore", or "mod:thing" for blocks that aren't Minecraft's own. */
	public static String id(Block b) {
		Identifier id = Registries.BLOCK.getId(b);
		return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
	}

	public static String name(Block b) {
		return b.getName().getString();
	}

	/** Every block you could ask for (ones with an item to show; not air), and the families, by key. */
	public static synchronized List<Entry> all() {
		if (entries != null) return entries;
		List<Entry> list = new ArrayList<>();
		Map<String, List<Block>> families = new LinkedHashMap<>();
		for (Block b : Registries.BLOCK) {
			if (b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR) continue;
			Item item = b.asItem();
			if (item == Items.AIR) continue;
			String id = id(b);
			list.add(new Entry(id, name(b), new ItemStack(item), List.of(b), false));
			String path = Registries.BLOCK.getId(b).getPath();
			// An ore and its deepslate twin are one family (modded ores too, by the same naming).
			if (path.endsWith("_ore") && !path.startsWith("nether_")) {
				String base = path.startsWith("deepslate_") ? path.substring("deepslate_".length()) : path;
				families.computeIfAbsent(Registries.BLOCK.getId(b).getNamespace() + ":" + base, k -> new ArrayList<>()).add(b);
			}
		}
		List<Entry> fams = new ArrayList<>();
		for (var e : families.entrySet()) {
			if (e.getValue().size() < 2) continue;
			// The stone one first: its icon and its name stand for the family.
			e.getValue().sort(Comparator.comparing(b -> Registries.BLOCK.getId(b).getPath().startsWith("deepslate_") ? 1 : 0));
			Block first = e.getValue().get(0);
			String nice = name(first).replaceFirst("(?i)\\s*ore$", "");
			fams.add(new Entry("family:" + e.getKey(), nice + " (all ore)", new ItemStack(first.asItem()), List.copyOf(e.getValue()), true));
		}
		// Every log or stem: for "get me wood".
		List<Block> logs = new ArrayList<>();
		for (Block b : Registries.BLOCK) {
			String p = Registries.BLOCK.getId(b).getPath();
			if ((p.endsWith("_log") || p.endsWith("_stem")) && !p.startsWith("stripped_") && b.asItem() != Items.AIR) logs.add(b);
		}
		if (logs.size() > 1) fams.add(new Entry("family:logs", "Any log (every wood)", new ItemStack(Items.OAK_LOG), List.copyOf(logs), true));
		fams.sort(Comparator.comparing(Entry::name));
		list.sort(Comparator.comparing(Entry::name));
		List<Entry> out = new ArrayList<>(fams);
		out.addAll(list);
		entries = List.copyOf(out);
		byKey = new HashMap<>();
		for (Entry e : entries) byKey.put(e.key(), e);
		return entries;
	}

	public static Entry get(String key) {
		all();
		Entry e = byKey.get(key);
		if (e != null) return e;
		// A bare id from Baritone or a setting ("minecraft:stone", "stone").
		Identifier id = Identifier.tryParse(key.contains(":") ? key : "minecraft:" + key);
		if (id != null && Registries.BLOCK.containsId(id)) return byKey.get(id(Registries.BLOCK.get(id)));
		return null;
	}

	/** Whether Baritone can't mine it at all, and why (null if it can). */
	public static String cantMine(Entry e) {
		for (Block b : e.blocks()) {
			if (b.getHardness() < 0) return name(b) + " can't be broken";
		}
		return null;
	}

	/** Whether an entry belongs in a group (Favourites, Recent and Near you are decided by the screen). */
	public static boolean in(Entry e, Group g) {
		if (e.blocks().isEmpty()) return g == Group.ALL;
		String p = e.family() ? e.key().substring(e.key().indexOf(':') + 1) : Registries.BLOCK.getId(e.blocks().get(0)).getPath();
		return switch (g) {
			case ALL -> true;
			case FAVOURITES, RECENT -> false;
			case NEAR -> e.near() > 0;
			case ORES -> p.endsWith("_ore") || p.contains("ore") && e.family() || p.equals("ancient_debris") || p.contains("raw_") && p.endsWith("_block")
					|| p.equals("budding_amethyst") || p.equals("amethyst_cluster");
			case VALUABLE -> p.equals("spawner") || p.equals("trial_spawner") || p.equals("vault") || p.endsWith("chest") || p.equals("barrel") || p.endsWith("shulker_box")
					|| p.equals("beacon") || p.equals("ancient_debris") || p.equals("obsidian") || p.equals("crying_obsidian") || p.equals("respawn_anchor")
					|| p.equals("enchanting_table") || p.endsWith("anvil") || p.equals("end_portal_frame") || p.equals("dragon_egg") || p.equals("budding_amethyst")
					|| p.equals("diamond_block") || p.equals("netherite_block") || p.equals("emerald_block") || p.equals("gold_block") || p.equals("iron_block")
					|| p.equals("lapis_block") || p.equals("redstone_block") || p.equals("conduit") || p.equals("sponge") || p.equals("wet_sponge")
					|| p.equals("ender_chest") || p.equals("hopper") || p.equals("brewing_stand") || p.equals("lodestone") || p.equals("heavy_core");
			case WOOD -> p.equals("logs") || p.endsWith("_log") || p.endsWith("_wood") || p.endsWith("_planks") || p.endsWith("_stem") || p.endsWith("_hyphae")
					|| p.endsWith("_leaves") || p.endsWith("_sapling") || p.equals("bamboo_block");
			case STONE -> List.of("stone", "deepslate", "cobblestone", "cobbled_deepslate", "granite", "diorite", "andesite", "tuff", "calcite", "dirt",
					"grass_block", "gravel", "sand", "red_sand", "clay", "sandstone", "red_sandstone", "terracotta", "mud", "podzol", "mycelium", "coarse_dirt",
					"rooted_dirt", "snow_block", "ice", "packed_ice", "blue_ice", "dripstone_block", "moss_block", "smooth_basalt", "mossy_cobblestone",
					"obsidian", "bedrock", "pale_moss_block", "dirt_path").contains(p) || p.endsWith("_terracotta") && !p.contains("glazed");
			case NETHER -> p.contains("nether") || p.contains("crimson") || p.contains("warped") || p.contains("soul_") || p.contains("basalt")
					|| p.contains("blackstone") || p.equals("glowstone") || p.equals("magma_block") || p.equals("shroomlight") || p.equals("ancient_debris")
					|| p.equals("quartz") || p.equals("crying_obsidian") || p.equals("respawn_anchor");
			case END -> p.contains("end_stone") || p.contains("purpur") || p.contains("chorus") || p.equals("end_rod") || p.equals("dragon_egg")
					|| p.equals("end_portal_frame") || p.contains("shulker_box");
			case PLANTS -> List.of("wheat", "carrots", "potatoes", "beetroots", "melon", "pumpkin", "sugar_cane", "cactus", "bamboo", "kelp", "sweet_berry_bush",
					"cocoa", "nether_wart", "hay_block", "farmland", "torchflower", "pitcher_plant", "glow_lichen", "vine", "lily_pad", "sea_pickle",
					"brown_mushroom", "red_mushroom", "brown_mushroom_block", "red_mushroom_block", "mushroom_stem", "dried_kelp_block", "moss_carpet",
					"short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "seagrass", "azalea", "flowering_azalea", "spore_blossom", "big_dripleaf",
					"small_dripleaf", "hanging_roots", "cave_vines", "twisting_vines", "weeping_vines", "carved_pumpkin", "jack_o_lantern").contains(p)
					|| p.endsWith("_tulip") || List.of("dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
					"lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony").contains(p) || p.endsWith("_sapling") || p.endsWith("_leaves");
			case BUILDING -> p.endsWith("_bricks") || p.endsWith("_stairs") || p.endsWith("_slab") || p.endsWith("_wall") || p.endsWith("_fence")
					|| p.endsWith("_glass") || p.equals("glass") || p.endsWith("_wool") || p.endsWith("_concrete") || p.endsWith("_concrete_powder")
					|| p.endsWith("_planks") || p.startsWith("polished_") || p.startsWith("smooth_") || p.startsWith("chiseled_") || p.startsWith("cut_")
					|| p.endsWith("_glazed_terracotta") || p.endsWith("_door") || p.endsWith("_trapdoor") || p.endsWith("_pane") || p.equals("bricks")
					|| p.equals("scaffolding") || p.endsWith("torch") || p.endsWith("lantern") || p.equals("ladder") || p.endsWith("_carpet");
		};
	}

	/**
	 * How well an entry matches what's typed: 0 not at all, higher is better. Its real name, its id, and the
	 * family's name all count, and words can be in any order ("ore diamond").
	 */
	public static int score(Entry e, String query) {
		String q = query.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
		if (q.isEmpty()) return 1;
		String name = e.name().toLowerCase(Locale.ROOT), key = e.key().toLowerCase(Locale.ROOT).replace('_', ' ');
		int best = 0;
		if (name.equals(q)) best = 1000;
		else if (name.startsWith(q)) best = 800;
		else if (key.startsWith(q) || key.contains(":" + q)) best = 700;
		else if (name.contains(" " + q) ) best = 600;
		else if (name.contains(q) || key.contains(q)) best = 400;
		else {
			// Every word found somewhere.
			boolean all = true;
			for (String w : q.split("\\s+")) {
				if (!name.contains(w) && !key.contains(w)) {
					all = false;
					break;
				}
			}
			if (all) best = 300;
			// "diamonds", "logs": plural for the thing.
			else if (q.endsWith("s") && q.length() > 3 && (name.contains(q.substring(0, q.length() - 1)) || key.contains(q.substring(0, q.length() - 1)))) best = 350;
		}
		if (best == 0) return 0;
		// Families first (they're what people mean), then the shorter name.
		if (e.family()) best += 120;
		return best - Math.min(80, name.length());
	}

	/** Entries matching what's typed (or in a group, when nothing is), best first. */
	public static List<Entry> search(String query, Group group, Set<String> keysForGroup) {
		return search(all(), query, group, keysForGroup);
	}

	public static List<Entry> search(List<Entry> source, String query, Group group, Set<String> keysForGroup) {
		List<Entry> out = new ArrayList<>();
		boolean typed = query != null && !query.isBlank();
		Map<Entry, Integer> scores = new HashMap<>();
		for (Entry e : source) {
			if (!typed) {
				boolean in = switch (group) {
					case FAVOURITES, RECENT -> keysForGroup.contains(e.key());
					default -> in(e, group);
				};
				if (!in) continue;
				out.add(e);
				continue;
			}
			int s = score(e, query);
			if (s <= 0) continue;
			scores.put(e, s);
			out.add(e);
		}
		if (typed) out.sort(Comparator.comparing((Entry e) -> -scores.get(e)).thenComparing(Entry::name));
		else if (group == Group.NEAR) out.sort(Comparator.comparing((Entry e) -> -e.near()).thenComparing(Entry::name));
		else if (group == Group.RECENT || group == Group.FAVOURITES) {
			List<String> order = new ArrayList<>(keysForGroup);
			out.sort(Comparator.comparing(e -> order.indexOf(e.key())));
		}
		return out;
	}

	private static List<Entry> items;

	/** Every item, for settings that take items (what it may place to bridge). */
	public static synchronized List<Entry> items() {
		if (items != null) return items;
		List<Entry> list = new ArrayList<>();
		for (Item i : Registries.ITEM) {
			if (i == Items.AIR) continue;
			Identifier id = Registries.ITEM.getId(i);
			list.add(new Entry("item:" + id, new ItemStack(i).getName().getString(), new ItemStack(i), List.of(), false));
		}
		list.sort(Comparator.comparing(Entry::name));
		items = List.copyOf(list);
		return items;
	}

	/** The item an "item:..." entry stands for, or null. */
	public static Item item(Entry e) {
		if (!e.key().startsWith("item:")) return null;
		Identifier id = Identifier.tryParse(e.key().substring(5));
		return id != null && Registries.ITEM.containsId(id) ? Registries.ITEM.get(id) : null;
	}

	/** The entry for an item ("item:minecraft:dirt"). */
	public static Entry itemEntry(Item i) {
		String key = "item:" + Registries.ITEM.getId(i);
		for (Entry e : items()) if (e.key().equals(key)) return e;
		return null;
	}

	// ---- What's in the land around you ----

	private static volatile Map<Block, Integer> near = Map.of();
	private static volatile long nearAt;
	private static final AtomicBoolean counting = new AtomicBoolean();

	public static int nearCount(Block b) {
		return near.getOrDefault(b, 0);
	}

	/** When the land around you was last counted (0: never). */
	public static long nearAt() {
		return nearAt;
	}

	/**
	 * Counts every block in the land loaded around you (up to 5 chunks out), off the game thread, at most every 10
	 * seconds. On servers that hide ores (anti-xray) the counts are what the server sent, which can be made up.
	 */
	public static void countNear(MinecraftClient client) {
		if (client.world == null || client.player == null || System.currentTimeMillis() - nearAt < 10_000 || !counting.compareAndSet(false, true)) return;
		var world = client.world;
		int pcx = client.player.getChunkPos().x, pcz = client.player.getChunkPos().z;
		int r = Math.min(5, client.options.getClampedViewDistance());
		List<ChunkSection> sections = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				WorldChunk c = world.getChunkManager().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
				if (c == null) continue;
				for (ChunkSection s : c.getSectionArray()) if (s != null && !s.isEmpty()) sections.add(s);
			}
		}
		Thread t = new Thread(() -> {
			Map<Block, Integer> counts = new HashMap<>();
			try {
				for (ChunkSection s : sections) {
					s.getBlockStateContainer().count((BlockState state, int n) -> counts.merge(state.getBlock(), n, Integer::sum));
				}
				near = counts;
			} catch (RuntimeException e) {
				// The land changed under us: try again next time.
			} finally {
				nearAt = System.currentTimeMillis();
				counting.set(false);
			}
		}, "Naughty or Nice block count");
		t.setDaemon(true);
		t.start();
	}

	// ---- What mining gets you ----

	/**
	 * The items mining these blocks gives, for counting what you've got: the block itself (Silk Touch), and for ores
	 * what they drop (raw iron, diamonds, coal, lapis, quartz, nuggets...).
	 */
	public static Set<Item> drops(List<Block> blocks) {
		Set<Item> out = new LinkedHashSet<>();
		for (Block b : blocks) {
			Identifier id = Registries.BLOCK.getId(b);
			out.add(b.asItem());
			String p = id.getPath();
			if (!p.endsWith("_ore")) continue;
			String base = p.replaceFirst("^deepslate_", "").replaceFirst("^nether_", "").replaceFirst("_ore$", "");
			for (String guess : new String[] {base, "raw_" + base, base + "_nugget", base.equals("lapis") ? "lapis_lazuli" : base, base + "_ingot"}) {
				Identifier g = Identifier.of(id.getNamespace(), guess);
				if (Registries.ITEM.containsId(g)) out.add(Registries.ITEM.get(g));
			}
			if (base.equals("quartz") && Registries.ITEM.containsId(Identifier.ofVanilla("quartz"))) out.add(Items.QUARTZ);
		}
		out.remove(Items.AIR);
		return out;
	}

	// ---- Mobs ----

	/** A kind of mob (or other entity) to follow: its id for Baritone, its name, and its spawn egg for an icon. */
	public record Mob(String id, String name, ItemStack icon, SpawnGroup group) {}

	private static List<Mob> mobs;

	/** Every living kind of entity, by name: the ones with spawn eggs first. */
	public static synchronized List<Mob> mobs() {
		if (mobs != null) return mobs;
		List<Mob> list = new ArrayList<>();
		for (EntityType<?> type : Registries.ENTITY_TYPE) {
			if (type == EntityType.PLAYER) continue;
			SpawnEggItem egg = SpawnEggItem.forEntity(type);
			ItemStack icon = egg != null ? new ItemStack(egg) : ItemStack.EMPTY;
			// Only things that live: mobs (and the few without eggs, like the iron golem's kin), not arrows or boats.
			if (egg == null && type.getSpawnGroup() == SpawnGroup.MISC) continue;
			Identifier id = Registries.ENTITY_TYPE.getId(type);
			list.add(new Mob(id.getNamespace().equals("minecraft") ? id.getPath() : id.toString(), type.getName().getString(), icon, type.getSpawnGroup()));
		}
		list.sort(Comparator.comparing(Mob::name));
		mobs = List.copyOf(list);
		return mobs;
	}
}
