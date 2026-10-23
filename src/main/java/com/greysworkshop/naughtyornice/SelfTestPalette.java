package com.greysworkshop.naughtyornice;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.IdListPalette;
import net.minecraft.world.chunk.Palette;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Development only: does a chunk section's block palette show whether the server just generated it or loaded it
 * from disk? Flies into land nobody has seen (overworld and nether), records every chunk as it arrives, leaves so the
 * server saves and unloads it all, comes back and records the same chunks again. Writes per-section categories and how
 * candidate rules sort the two groups. ./gradlew runPaletteProbe
 */
public final class SelfTestPalette {
	private static final String WORLD = "nn-palette-probe";
	private static boolean started;
	private static int tick;
	/** Which group arriving chunks go in ("" while not recording). */
	private static String phase = "";
	private static final Map<String, Map<Long, int[]>> groups = new LinkedHashMap<>();
	private static int baseX, baseZ;
	/** What the mod itself (ChunkTrails.paletteVerdict) made of each chunk. */
	private static final Map<String, Map<Long, Byte>> verdicts = new LinkedHashMap<>();

	private SelfTestPalette() {}

	public static void start() {
		log("Palette probe armed");
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (started || !(screen instanceof TitleScreen)) return;
			started = true;
			client.options.pauseOnLostFocus = false;
			client.send(() -> {
				if (client.getLevelStorage().levelExists(WORLD)) {
					client.createIntegratedServerLoader().start(WORLD, () -> {});
				} else {
					LevelInfo info = new LevelInfo(WORLD, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true,
							new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
					client.createIntegratedServerLoader().createAndStart(WORLD, info, new GeneratorOptions(20261023L, true, false),
							WorldPresets::createDemoOptions, new TitleScreen());
				}
			});
		});
		ChunkTrails.arrivalProbe = chunk -> {
			if (phase.isEmpty()) return;
			groups.computeIfAbsent(phase, p -> new LinkedHashMap<>()).put(chunk.getPos().toLong(), signature(chunk));
			verdicts.computeIfAbsent(phase, p -> new LinkedHashMap<>()).put(chunk.getPos().toLong(), ChunkTrails.paletteVerdict(chunk));
		};
		ClientTickEvents.END_CLIENT_TICK.register(SelfTestPalette::tick);
	}

	// Section categories.
	static final int MISMATCH = 0, STRICT_OLD = 1, LOOSE_OLD = 2, AIR_UNUSED = 3, AIR_CLEAN = 4, SKIPPED = 5, EMPTY = 6, CATEGORIES = 7;
	static final String[] NAMES = {"p0!=first", "strict-old", "loose-old", "air+unused", "air-clean", "skipped", "empty"};

	/** Counts of a chunk's sections in each category. */
	static int[] signature(WorldChunk chunk) {
		int[] counts = new int[CATEGORIES];
		for (ChunkSection section : chunk.getSectionArray()) {
			counts[category(section)]++;
		}
		return counts;
	}

	static int category(ChunkSection section) {
		if (section == null || section.isEmpty()) return EMPTY;
		PalettedContainer<BlockState> c = section.getBlockStateContainer();
		Palette<BlockState> pal = c.data.palette;
		if (pal instanceof IdListPalette) return SKIPPED;
		int n = pal.getSize();
		if (n < 2) return SKIPPED;
		BlockState first = c.get(0, 0, 0);
		if (pal.get(0) != first) return MISMATCH;
		// The palette's order against the order blocks first appear in (y, then z, then x), and unused entries.
		List<BlockState> seen = new ArrayList<>();
		Set<BlockState> set = new HashSet<>();
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState s = c.get(x, y, z);
					if (set.add(s)) seen.add(s);
				}
			}
		}
		boolean ordered = true;
		for (int i = 0; i < seen.size() && i < n; i++) {
			if (pal.get(i) != seen.get(i)) {
				ordered = false;
				break;
			}
		}
		boolean unused = seen.size() < n;
		if (!first.isAir()) return ordered && !unused ? STRICT_OLD : LOOSE_OLD;
		return unused ? AIR_UNUSED : AIR_CLEAN;
	}

	private static void command(MinecraftClient client, String command) {
		IntegratedServer server = client.getServer();
		server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), command));
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		tick++;
		// Hover where it's put (creative flight), so nothing falls or wanders.
		client.player.getAbilities().flying = true;
		client.player.setVelocity(0, 0, 0);
		// 20 ticks a second; generous waits so the server has generated, saved and unloaded what it needs to.
		// Phases: fresh (90 s), away while it all unloads and saves (40 s), back (60 s); overworld, then nether.
		if (tick % 200 == 0) log("tick " + tick + ", phase \"" + phase + "\": " + (phase.isEmpty() || !groups.containsKey(phase) ? 0 : groups.get(phase).size())
				+ " chunks so far, in " + client.world.getRegistryKey().getValue());
		switch (tick) {
			case 40 -> {
				client.options.getViewDistance().setValue(8);
				baseX = 200_000 + (int) (System.currentTimeMillis() / 1000 % 4000) * 100;
				baseZ = -150_000;
				command(client, "gamemode creative @a");
				log("Overworld fresh land at " + baseX + ", " + baseZ);
				phase = "overworld fresh";
				command(client, "execute in minecraft:overworld run tp @a " + baseX + " 150 " + baseZ);
			}
			case 1840 -> {
				phase = "";
				command(client, "execute in minecraft:overworld run tp @a " + (baseX + 30_000) + " 150 " + baseZ);
			}
			case 2640 -> {
				phase = "overworld reloaded";
				command(client, "execute in minecraft:overworld run tp @a " + baseX + " 150 " + baseZ);
			}
			case 3840 -> {
				phase = "nether fresh";
				command(client, "execute in minecraft:the_nether run tp @a " + baseX / 8 + " 64 " + baseZ / 8);
			}
			case 5040 -> {
				phase = "";
				command(client, "execute in minecraft:the_nether run tp @a " + (baseX / 8 + 30_000) + " 64 " + baseZ / 8);
			}
			case 6440 -> {
				phase = "nether reloaded";
				command(client, "execute in minecraft:the_nether run tp @a " + baseX / 8 + " 64 " + baseZ / 8);
			}
			case 7640 -> {
				phase = "";
				report();
				client.scheduleStop();
			}
			default -> {}
		}
	}

	private static void report() {
		for (Map.Entry<String, Map<Long, int[]>> g : groups.entrySet()) {
			Map<Long, int[]> chunks = g.getValue();
			int[] total = new int[CATEGORIES];
			for (int[] c : chunks.values()) for (int i = 0; i < CATEGORIES; i++) total[i] += c[i];
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < CATEGORIES; i++) sb.append(NAMES[i]).append('=').append(total[i]).append(' ');
			log("== " + g.getKey() + ": " + chunks.size() + " chunks; sections " + sb);
			// The patterns chunks show (counts of mismatch / strict-old / loose-old / air+unused / air-clean).
			Map<String, Integer> patterns = new TreeMap<>();
			for (int[] c : chunks.values()) patterns.merge(c[0] + "/" + c[1] + "/" + c[2] + "/" + c[3] + "/" + c[4], 1, Integer::sum);
			log("   patterns (mismatch/strict-old/loose-old/air+unused/air-clean -> chunks): " + patterns);
			// Candidate rule: old when some section is strict-old and none mismatch; fresh when some mismatch or has unused air.
			int old = 0, fresh = 0, unknown = 0;
			for (int[] c : chunks.values()) {
				if (c[STRICT_OLD] > 0 && c[MISMATCH] == 0 && c[AIR_UNUSED] == 0) old++;
				else if ((c[MISMATCH] > 0 || c[AIR_UNUSED] > 0) && c[STRICT_OLD] == 0) fresh++;
				else unknown++;
			}
			log("   rule A: old " + old + ", fresh " + fresh + ", undecided " + unknown);
			log("   rule B: " + ruleB(chunks.values()));
			// Same chunks in both groups: how each one changed.
			if (g.getKey().endsWith("reloaded")) {
				Map<Long, int[]> before = groups.get(g.getKey().replace("reloaded", "fresh"));
				int both = 0;
				for (Long k : chunks.keySet()) if (before != null && before.containsKey(k)) both++;
				log("   chunks seen both times: " + both);
				if (before != null) {
					List<int[]> pairedBefore = new ArrayList<>(), pairedAfter = new ArrayList<>(), notBefore = new ArrayList<>();
					Map<String, Integer> pairs = new TreeMap<>();
					for (Map.Entry<Long, int[]> e : chunks.entrySet()) {
						int[] b = before.get(e.getKey());
						if (b == null) {
							notBefore.add(e.getValue());
							continue;
						}
						pairedBefore.add(b);
						pairedAfter.add(e.getValue());
						pairs.merge(b[0] + "/" + b[1] + "/" + b[2] + "/" + b[3] + "/" + b[4] + " -> " + e.getValue()[0] + "/" + e.getValue()[1] + "/"
								+ e.getValue()[2] + "/" + e.getValue()[3] + "/" + e.getValue()[4], 1, Integer::sum);
					}
					log("   SAME CHUNKS when fresh, rule B: " + ruleB(pairedBefore));
					Map<Long, Byte> vb = verdicts.get(g.getKey().replace("reloaded", "fresh")), va = verdicts.get(g.getKey());
					int[] tally = new int[6];
					for (Long k : chunks.keySet()) {
						if (!before.containsKey(k)) continue;
						tally[vb.get(k)]++;
						tally[3 + va.get(k)]++;
					}
					log("   THE MOD'S OWN VERDICT, same chunks: when fresh new " + tally[ChunkTrails.NEW] + ", old " + tally[ChunkTrails.OLD] + ", undecided " + tally[0]
							+ "; when reloaded new " + tally[3 + ChunkTrails.NEW] + ", old " + tally[3 + ChunkTrails.OLD] + ", undecided " + tally[3]);
					log("   SAME CHUNKS when reloaded, rule B: " + ruleB(pairedAfter));
					log("   chunks first seen on the way back (likely generated late), rule B: " + ruleB(notBefore));
					log("   pairs: " + pairs);
				}
			}
		}
	}

	/** Rule B: fresh when 3+ sections were never saved and at most 1 looks saved; old when 2+ look saved and at most 1 doesn't. */
	static String ruleB(java.util.Collection<int[]> chunks) {
		int old = 0, fresh = 0, unknown = 0;
		for (int[] c : chunks) {
			if (c[MISMATCH] >= 3 && c[STRICT_OLD] <= 1) fresh++;
			else if (c[STRICT_OLD] >= 2 && c[MISMATCH] <= 1) old++;
			else unknown++;
		}
		return "old " + old + ", fresh " + fresh + ", undecided " + unknown + " (of " + chunks.size() + ")";
	}

	private static void log(String message) {
		NaughtyOrNiceClient.LOGGER.info("[PROBE] {}", message);
	}
}
