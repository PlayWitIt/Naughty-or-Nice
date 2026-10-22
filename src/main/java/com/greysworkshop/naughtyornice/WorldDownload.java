package com.greysworkshop.naughtyornice;

import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PaletteProvider;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.ReadableContainer;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.storage.RegionFile;
import net.minecraft.world.storage.StorageKey;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Saves the land you load into a singleplayer world, only once you've said yes. From then until you stop, every chunk
 * you load in that dimension is saved (each download is one dimension, kept in the world's overworld so the world is
 * plain vanilla and opens without warnings; changing dimension finishes it and offers a new one): its blocks, biomes and the block data the server sends (signs, banners,
 * beds...). Chest contents aren't sent to players unless you open the chest, so they can't be saved. Everything you
 * didn't load is empty (a void world), and you open it in creative.
 * <p>
 * Chunks are put together on the game thread (they're read from the live world) and written to region files on a
 * thread of their own, with the game's own region-file code, so the world opens like any other.
 */
public final class WorldDownload {
	private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Naughty or Nice world download");
		t.setDaemon(true);
		return t;
	});

	private static Path folder;
	private static String name;
	private static final Map<String, RegionFile> regions = new HashMap<>();
	private static final Map<String, java.util.Set<Long>> saved = new HashMap<>();
	private static int chunks;
	private static boolean active;
	/** The dimension being downloaded. */
	private static String dimension;

	private WorldDownload() {}

	public static boolean active() {
		return active;
	}

	public static int chunks() {
		return chunks;
	}

	public static String name() {
		return name;
	}

	/** Asks first: a chat line with [Download] and what it does. Nothing is saved until it's pressed. */
	public static void offer(String why) {
		Alerts.chat(Text.literal(why + " Save the land you load into a singleplayer world? ").formatted(Formatting.GOLD)
				.append(Text.literal("[Download]").styled(st -> st.withColor(Formatting.GREEN).withUnderline(true)
						.withClickEvent(new ClickEvent.RunCommand("/nn download start")))));
	}

	/** Starts saving (you said yes), with everything loaded right now. Returns why it couldn't, or null. */
	public static String start(MinecraftClient client) {
		if (client.world == null || client.player == null) return "Join a world first";
		if (active) return "Already downloading into \"" + name + "\"";
		String server = Sharing.placeName(NaughtyOrNiceClient.INSTANCE.ledger.key()).replaceAll("[^A-Za-z0-9 ._-]", "_");
		dimension = client.world.getRegistryKey().getValue().toString();
		name = "NN " + server + " " + ShopScreen.dimensionName(dimension) + " " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm", Locale.ROOT));
		folder = client.runDirectory.toPath().resolve("saves").resolve(name);
		try {
			Files.createDirectories(folder);
			writeLevel(client);
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not start a world download", e);
			return "Couldn't make the world folder: " + e.getMessage();
		}
		active = true;
		chunks = 0;
		saved.clear();
		// Everything already loaded around you.
		int view = client.options.getClampedViewDistance();
		int cx = client.player.getBlockX() >> 4, cz = client.player.getBlockZ() >> 4;
		for (int x = cx - view; x <= cx + view; x++) {
			for (int z = cz - view; z <= cz + view; z++) {
				WorldChunk chunk = client.world.getChunkManager().getWorldChunk(x, z);
				if (chunk != null) save(client, chunk);
			}
		}
		Alerts.chat(Text.literal("Downloading into the world \"" + name + "\": every chunk you load is saved until you stop. ").formatted(Formatting.GREEN)
				.append(Text.literal("[Stop download]").styled(st -> st.withColor(Formatting.RED).withUnderline(true)
						.withClickEvent(new ClickEvent.RunCommand("/nn download stop")))));
		return null;
	}

	public static void stop(MinecraftClient client) {
		if (!active) return;
		// The latest state of everything still loaded, then close the files.
		if (client.world != null && client.player != null && client.world.getRegistryKey().getValue().toString().equals(dimension)) {
			int view = client.options.getClampedViewDistance();
			int cx = client.player.getBlockX() >> 4, cz = client.player.getBlockZ() >> 4;
			for (int x = cx - view; x <= cx + view; x++) {
				for (int z = cz - view; z <= cz + view; z++) save(client, client.world.getChunkManager().getWorldChunk(x, z));
			}
		}
		active = false;
		int count = chunks;
		String world = name;
		WRITER.submit(() -> {
			for (RegionFile r : regions.values()) {
				try {
					r.close();
				} catch (IOException e) {
					NaughtyOrNiceClient.LOGGER.error("Could not close a region file", e);
				}
			}
			regions.clear();
		});
		Alerts.chat(Text.literal("Download finished: " + count + " chunks in the singleplayer world \"" + world + "\".").formatted(Formatting.GREEN));
	}

	/** A chunk arrived (or is about to be unloaded): save it, if downloading. */
	public static void chunk(MinecraftClient client, WorldChunk chunk) {
		if (!active || chunk == null || client.world == null) return;
		if (!client.world.getRegistryKey().getValue().toString().equals(dimension)) {
			// Another dimension: this download is finished; a new one is offered for where you are now.
			stop(client);
			offer("You changed dimension, so that download is finished.");
			return;
		}
		save(client, chunk);
	}

	private static void save(MinecraftClient client, WorldChunk chunk) {
		if (chunk == null || chunk.isEmpty() && chunk.getBlockEntities().isEmpty()) return;
		String dim = client.world.getRegistryKey().getValue().toString();
		NbtCompound nbt;
		try {
			nbt = serialize(client, chunk);
		} catch (RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.warn("Could not save chunk {}", chunk.getPos(), e);
			return;
		}
		if (saved.computeIfAbsent(dim, d -> new java.util.HashSet<>()).add(chunk.getPos().toLong())) chunks++;
		ChunkPos pos = chunk.getPos();
		// Whatever dimension it was, it goes in the downloaded world's overworld (see the class comment).
		Path dir = folder.resolve("region");
		WRITER.submit(() -> {
			try {
				Files.createDirectories(dir);
				String file = "r." + (pos.x >> 5) + "." + (pos.z >> 5) + ".mca";
				RegionFile region = regions.get(dir + "/" + file);
				if (region == null) {
					region = new RegionFile(new StorageKey(name, net.minecraft.world.World.OVERWORLD, "chunk"), dir.resolve(file), dir, false);
					regions.put(dir + "/" + file, region);
				}
				try (DataOutputStream out = region.getChunkOutputStream(pos)) {
					NbtIo.writeCompound(nbt, out);
				}
			} catch (IOException e) {
				NaughtyOrNiceClient.LOGGER.error("Could not write chunk {}", pos, e);
			}
		});
	}

	/** A chunk as the game saves it: sections (blocks, biomes), block data, its place. */
	static NbtCompound serialize(MinecraftClient client, WorldChunk chunk) {
		Registry<Biome> biomes = client.world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
		Codec<PalettedContainer<BlockState>> blockCodec = PalettedContainer.createPalettedContainerCodec(BlockState.CODEC,
				PaletteProvider.forBlockStates(Block.STATE_IDS), Blocks.AIR.getDefaultState());
		Codec<ReadableContainer<RegistryEntry<Biome>>> biomeCodec = PalettedContainer.createReadableContainerCodec(biomes.getEntryCodec(),
				PaletteProvider.forBiomes(biomes.getIndexedEntries()), biomes.getOrThrow(BiomeKeys.PLAINS));
		NbtCompound nbt = new NbtCompound();
		nbt.putInt("DataVersion", SharedConstants.getGameVersion().dataVersion().id());
		nbt.putInt("xPos", chunk.getPos().x);
		nbt.putInt("zPos", chunk.getPos().z);
		nbt.putInt("yPos", chunk.getBottomSectionCoord());
		nbt.putString("Status", "minecraft:full");
		nbt.putLong("LastUpdate", 0);
		nbt.putLong("InhabitedTime", 0);
		nbt.putBoolean("isLightOn", false);
		NbtList sections = new NbtList();
		ChunkSection[] array = chunk.getSectionArray();
		for (int i = 0; i < array.length; i++) {
			ChunkSection section = array[i];
			NbtCompound s = new NbtCompound();
			s.putByte("Y", (byte) (chunk.getBottomSectionCoord() + i));
			s.put("block_states", blockCodec.encodeStart(NbtOps.INSTANCE, section.getBlockStateContainer()).getOrThrow());
			s.put("biomes", biomeCodec.encodeStart(NbtOps.INSTANCE, section.getBiomeContainer()).getOrThrow());
			sections.add(s);
		}
		nbt.put("sections", sections);
		NbtList blockEntities = new NbtList();
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			try {
				blockEntities.add(be.createNbtWithIdentifyingData(client.world.getRegistryManager()));
			} catch (RuntimeException e) {
				// One odd block entity shouldn't lose the chunk.
			}
		}
		nbt.put("block_entities", blockEntities);
		nbt.put("block_ticks", new NbtList());
		nbt.put("fluid_ticks", new NbtList());
		nbt.put("PostProcessing", new NbtList());
		nbt.put("structures", new NbtCompound());
		return nbt;
	}

	/**
	 * level.dat for a creative void world, in this game version's own layout (the same fields a world made by this
	 * version has), so it opens straight away like any other world. You start where the download started, in that
	 * dimension; time, weather and mob spawning are stopped so nothing changes what was saved.
	 */
	private static void writeLevel(MinecraftClient client) throws IOException {
		int dataVersion = SharedConstants.getGameVersion().dataVersion().id();
		NbtCompound data = new NbtCompound();
		data.putInt("DataVersion", dataVersion);
		data.putInt("version", 19133);
		NbtCompound version = new NbtCompound();
		version.putInt("Id", dataVersion);
		version.putString("Name", SharedConstants.getGameVersion().name());
		version.putString("Series", "main");
		version.putBoolean("Snapshot", false);
		data.put("Version", version);
		data.putString("LevelName", name);
		data.putInt("GameType", 1);
		data.putBoolean("hardcore", false);
		data.putByte("Difficulty", (byte) 0);
		data.putBoolean("DifficultyLocked", false);
		data.putBoolean("allowCommands", true);
		data.putBoolean("initialized", true);
		data.putLong("Time", 6000);
		data.putLong("DayTime", 6000);
		data.putLong("LastPlayed", System.currentTimeMillis());
		data.putBoolean("raining", false);
		data.putBoolean("thundering", false);
		data.putInt("rainTime", 0);
		data.putInt("thunderTime", 0);
		data.putInt("clearWeatherTime", 0);
		data.putInt("WanderingTraderSpawnChance", 25);
		data.putInt("WanderingTraderSpawnDelay", 24000);
		NbtCompound spawn = new NbtCompound();
		spawn.putIntArray("pos", new int[] {client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ()});
		spawn.putString("dimension", "minecraft:overworld");
		spawn.putFloat("yaw", 0);
		spawn.putFloat("pitch", 0);
		data.put("spawn", spawn);
		NbtCompound rules = new NbtCompound();
		for (String r : new String[] {"advance_time", "advance_weather", "spawn_mobs", "spawn_monsters", "spawn_phantoms", "spawn_patrols",
				"spawn_wandering_traders", "spawn_wardens", "mob_griefing", "fire_damage"}) {
			rules.putBoolean("minecraft:" + r, false);
		}
		rules.putInt("minecraft:fire_spread_radius_around_player", 0);
		rules.putInt("minecraft:random_tick_speed", 0);
		data.put("game_rules", rules);
		NbtCompound gen = new NbtCompound();
		gen.putLong("seed", 0);
		gen.putBoolean("generate_features", false);
		gen.putBoolean("bonus_chest", false);
		// A void overworld (superflat with no layers: a standard, non-experimental world type), and the nether and the
		// end exactly as vanilla makes them, so the game doesn't call the world experimental.
		NbtCompound dims = new NbtCompound();
		NbtCompound over = new NbtCompound();
		over.putString("type", "minecraft:overworld");
		NbtCompound flat = new NbtCompound();
		flat.putString("type", "minecraft:flat");
		NbtCompound settings = new NbtCompound();
		settings.putString("biome", "minecraft:the_void");
		settings.putBoolean("lakes", false);
		settings.putBoolean("features", false);
		settings.put("layers", new NbtList());
		settings.put("structure_overrides", new NbtList());
		flat.put("settings", settings);
		over.put("generator", flat);
		dims.put("minecraft:overworld", over);
		for (String[] d : new String[][] {{"minecraft:the_nether", "minecraft:nether", "minecraft:multi_noise"}, {"minecraft:the_end", "minecraft:end", "minecraft:the_end"}}) {
			NbtCompound dim = new NbtCompound();
			dim.putString("type", d[0]);
			NbtCompound generator = new NbtCompound();
			generator.putString("type", "minecraft:noise");
			generator.putString("settings", d[1]);
			NbtCompound source = new NbtCompound();
			source.putString("type", d[2]);
			if (d[2].equals("minecraft:multi_noise")) source.putString("preset", "minecraft:nether");
			generator.put("biome_source", source);
			dim.put("generator", generator);
			dims.put(d[0], dim);
		}
		gen.put("dimensions", dims);
		data.put("WorldGenSettings", gen);
		NbtCompound dragon = new NbtCompound();
		dragon.putBoolean("NeedsStateScanning", false);
		dragon.putBoolean("DragonKilled", true);
		dragon.putBoolean("PreviouslyKilled", true);
		dragon.put("Gateways", new NbtList());
		data.put("DragonFight", dragon);
		NbtCompound packs = new NbtCompound();
		NbtList enabled = new NbtList();
		enabled.add(NbtString.of("vanilla"));
		packs.put("Enabled", enabled);
		packs.put("Disabled", new NbtList());
		data.put("DataPacks", packs);
		data.putBoolean("WasModded", true);
		NbtList brands = new NbtList();
		brands.add(NbtString.of("naughtyornice"));
		data.put("ServerBrands", brands);
		data.put("CustomBossEvents", new NbtCompound());
		data.put("ScheduledEvents", new NbtList());
		NbtCompound root = new NbtCompound();
		root.put("Data", data);
		NbtIo.writeCompressed(root, folder.resolve("level.dat"));
	}

	/** Waits until everything queued has been written (for the self-test, before opening the world). */
	static void flush() {
		try {
			WRITER.submit(() -> {}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (java.util.concurrent.ExecutionException e) {
			NaughtyOrNiceClient.LOGGER.error("Download writing failed", e.getCause());
		}
	}

	/** For the self-test: the world folder and the dimension folders' chunk count so far. */
	static Path folder() {
		return folder;
	}
}
