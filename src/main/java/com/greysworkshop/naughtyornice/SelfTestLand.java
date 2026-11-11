package com.greysworkshop.naughtyornice;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Development only: proves land is shown right as you'd see it, through the real game: chunks the server sends, read
 * by the mod as they arrive, drawn as the squares in the world (X) and on the radar. Nothing is set by hand.
 * <p>
 * First game: fly into land nobody has loaded (it must show red, new), fly far away so the server saves and unloads
 * it, come back (it must not be red: blue, yours), then quit. Second game, started fresh: come back to the same land
 * again (still blue, never red), then fly into land the server generated and saved while you were far away, which the
 * mod never saw being made, as if another player had made it (it must show green, someone was here).
 * ./gradlew runLandTest twice: the first run sets up and quits, the second finishes and reports.
 */
public final class SelfTestLand {
	private static final String WORLD = "nn-land-proof";
	private static final int R = 4;
	private static boolean started;
	private static int tick;
	private static int ax, az, bx, bz;
	private static boolean second;
	private static final List<Long> madeHere = new ArrayList<>();
	private static int passed, failed;

	private SelfTestLand() {}

	private static Path stateFile(MinecraftClient client) {
		return client.runDirectory.toPath().resolve("nn-land-proof.txt");
	}

	public static void start() {
		log("Land test armed");
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (started || !(screen instanceof TitleScreen)) return;
			started = true;
			client.options.pauseOnLostFocus = false;
			Path state = stateFile(client);
			second = Files.exists(state);
			client.send(() -> {
				if (second) {
					try {
						String[] p = Files.readString(state).trim().split(" ");
						ax = Integer.parseInt(p[0]);
						az = Integer.parseInt(p[1]);
						bx = Integer.parseInt(p[2]);
						bz = Integer.parseInt(p[3]);
						for (int i = 4; i < p.length; i++) madeHere.add(Long.parseLong(p[i]));
					} catch (IOException | RuntimeException e) {
						log("FAIL couldn't read what the first game left (" + e + ")");
						client.scheduleStop();
						return;
					}
					log("Second game: back to the land made in the first one");
					client.createIntegratedServerLoader().start(WORLD, () -> {});
				} else {
					// A new world every first run, so the land is new to everyone, and nothing the mod remembers of it.
					try {
						try (var lists = Files.list(NNConfig.dir().resolve("lists"))) {
							for (Path f : lists.filter(f -> f.getFileName().toString().contains(WORLD)).toList()) Files.delete(f);
						} catch (java.nio.file.NoSuchFileException ignored) {
							// No lists yet.
						}
						Path saves = client.runDirectory.toPath().resolve("saves").resolve(WORLD);
						if (Files.exists(saves)) {
							try (var walk = Files.walk(saves)) {
								walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
							}
						}
					} catch (IOException e) {
						log("Couldn't clear the old test world: " + e);
					}
					LevelInfo info = new LevelInfo(WORLD, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true,
							new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
					client.createIntegratedServerLoader().createAndStart(WORLD, info, new GeneratorOptions(20261102L, true, false),
							WorldPresets::createDemoOptions, new TitleScreen());
				}
			});
		});
		ClientTickEvents.END_CLIENT_TICK.register(SelfTestLand::tick);
	}

	private static void command(MinecraftClient client, String command) {
		IntegratedServer server = client.getServer();
		server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), command));
	}

	private static void tp(MinecraftClient client, int x, int z) {
		command(client, "execute in minecraft:overworld run tp @a " + x + " 150 " + z + " 0 70");
	}

	private static String dim(MinecraftClient client) {
		return client.world.getRegistryKey().getValue().toString();
	}

	/** What the mod shows for the chunks around a chunk: {new, yours, someone was here, unknown}. */
	private static int[] around(MinecraftClient client, int cx, int cz) {
		int[] n = new int[4];
		ChunkTrails t = NaughtyOrNiceClient.INSTANCE.ledger.chunkTrails;
		for (int i = -R; i <= R; i++) {
			for (int j = -R; j <= R; j++) {
				byte s = t.shown(dim(client), cx + i, cz + j);
				n[s == ChunkTrails.NEW ? 0 : s == ChunkTrails.YOURS ? 1 : s == ChunkTrails.OLD ? 2 : 3]++;
			}
		}
		return n;
	}

	/** What the mod shows for the chunks made in the first game: {new, yours, someone was here, unknown}. */
	private static int[] madeHereNow(MinecraftClient client) {
		int[] n = new int[4];
		ChunkTrails t = NaughtyOrNiceClient.INSTANCE.ledger.chunkTrails;
		for (long k : madeHere) {
			byte s = t.shown(dim(client), net.minecraft.util.math.ChunkPos.getPackedX(k), net.minecraft.util.math.ChunkPos.getPackedZ(k));
			n[s == ChunkTrails.NEW ? 0 : s == ChunkTrails.YOURS ? 1 : s == ChunkTrails.OLD ? 2 : 3]++;
		}
		return n;
	}

	private static String counts(int[] n) {
		return "new (red) " + n[0] + ", yours (blue) " + n[1] + ", someone was here (green) " + n[2] + ", can't tell " + n[3];
	}

	private static String drawn() {
		int[] k = ChunkOverlay.colourCounts();
		return "X squares drawn: red " + k[0] + ", blue " + k[1] + ", green " + k[2] + ", yellow " + k[3]
				+ "; radar: " + TrailRadar.lastOld + " old, " + TrailRadar.lastNew + " new";
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		tick++;
		client.player.getAbilities().flying = true;
		client.player.setVelocity(0, 0, 0);
		if (tick == 20) {
			NNConfig c = NNConfig.INSTANCE;
			c.enabled = true;
			c.chunkTrails = true;
			c.chunkOverlay = true;
			c.trailRadar = true;
			client.options.getViewDistance().setValue(8);
			command(client, "gamemode creative @a");
			command(client, "time set noon");
			command(client, "gamerule doDaylightCycle false");
			command(client, "weather clear 100000");
		}
		if (second) secondGame(client);
		else firstGame(client);
	}

	private static void firstGame(MinecraftClient client) {
		switch (tick) {
			case 40 -> {
				// Mostly sea in this seed: the hard case, where much of it can't be told on the way in. (The world is new
				// every first run, so nobody has loaded it.)
				ax = 486_888;
				az = -200_008;
				bx = ax + 20_000;
				bz = az;
				log("First game. Your land: " + ax + ", " + az + ". Someone else's land: " + bx + ", " + bz);
				// Someone else's land: generated and saved by the server now, while you're nowhere near it.
				command(client, "forceload add " + (bx - R * 16) + " " + (bz - R * 16) + " " + (bx + R * 16) + " " + (bz + R * 16));
				tp(client, ax, az);
			}
			case 1240 -> {
				int[] n = around(client, ax >> 4, az >> 4);
				for (int i = -R; i <= R; i++) {
					for (int j = -R; j <= R; j++) {
						if (NaughtyOrNiceClient.INSTANCE.ledger.chunkTrails.shown(dim(client), (ax >> 4) + i, (az >> 4) + j) == ChunkTrails.NEW) {
							madeHere.add(net.minecraft.util.math.ChunkPos.toLong((ax >> 4) + i, (az >> 4) + j));
						}
					}
				}
				log("Arriving in land nobody has loaded: " + counts(n) + "; " + drawn());
				check("1. land being made as you arrive shows red (new) where it can be told, never blue or green", n[0] >= 1 && n[1] == 0 && n[2] == 0);
				screenshot(client, "land_1_new_red");
				// Let the server forget someone else's land (it saves it as it unloads).
				command(client, "forceload remove all");
			}
			case 1300 -> {
				log("Flying 40,000 blocks away, so your land is saved and unloaded");
				tp(client, ax + 40_000, az);
			}
			case 2500 -> {
				log("Coming back to your land, same game");
				tp(client, ax, az);
			}
			case 3700 -> {
				int[] n = madeHereNow(client);
				log("Back in the land you made (" + madeHere.size() + " chunks it showed red): " + counts(n) + "; " + drawn());
				check("2. coming back to it in the same game: none of it red, it shows blue (yours)",
						n[0] == 0 && n[2] == 0 && n[1] >= madeHere.size() * 9 / 10);
				screenshot(client, "land_2_back_same_game_blue");
				try {
					StringBuilder sb = new StringBuilder(ax + " " + az + " " + bx + " " + bz);
					for (long k : madeHere) sb.append(' ').append(k);
					Files.writeString(stateFile(client), sb.toString());
				} catch (IOException e) {
					log("FAIL couldn't note the land for the second game: " + e);
				}
				log("First game done: " + passed + " passed, " + failed + " failed. Quitting; run it again for the second game.");
				client.scheduleStop();
			}
			default -> {}
		}
	}

	private static void secondGame(MinecraftClient client) {
		switch (tick) {
			case 40 -> {
				log("Second game started fresh. Back to your land: " + ax + ", " + az);
				tp(client, ax, az);
			}
			case 1240 -> {
				int[] n = madeHereNow(client);
				log("Back in your land after restarting the game: " + counts(n) + "; " + drawn());
				check("3. coming back after restarting the game: none of it red, it shows blue (yours)",
						n[0] == 0 && n[2] == 0 && n[1] >= madeHere.size() * 9 / 10);
				greenAround(client);
				int[] k = ChunkOverlay.colourCounts();
				check("3b. ...and no square anywhere around your own land is red or green (" + k[0] + " red, " + k[2] + " green)", k[0] == 0 && k[2] == 0);
				screenshot(client, "land_3_back_after_restart_blue");
				log("To land the server made and saved while you were far away (someone else's): " + bx + ", " + bz);
				tp(client, bx, bz);
			}
			case 2440 -> {
				int[] n = around(client, bx >> 4, bz >> 4);
				log("In someone else's land: " + counts(n) + "; " + drawn());
				check("4. land someone else made shows green (someone was here), none red or blue", n[2] >= 30 && n[0] == 0 && n[1] == 0);
				screenshot(client, "land_4_someone_else_green");
				try {
					Files.deleteIfExists(stateFile(client));
				} catch (IOException ignored) {
					// Next run would just start over anyway.
				}
				log("DONE: " + passed + " passed, " + failed + " failed (this game; the first game's are above in its own log)");
				client.scheduleStop();
			}
			default -> {}
		}
	}

	/** Every chunk around you the mod reads as someone else's, with how far it is from land you made. */
	private static void greenAround(MinecraftClient client) {
		ChunkTrails t = NaughtyOrNiceClient.INSTANCE.ledger.chunkTrails;
		int pcx = client.player.getBlockX() >> 4, pcz = client.player.getBlockZ() >> 4;
		for (int i = -12; i <= 12; i++) {
			for (int j = -12; j <= 12; j++) {
				int x = pcx + i, z = pcz + j;
				if (t.states(dim(client)).get(net.minecraft.util.math.ChunkPos.toLong(x, z)) != ChunkTrails.OLD) continue;
				int nearest = Integer.MAX_VALUE;
				for (int a = -6; a <= 6; a++) {
					for (int b = -6; b <= 6; b++) {
						if (t.mineHas(dim(client), x + a, z + b)) nearest = Math.min(nearest, Math.max(Math.abs(a), Math.abs(b)));
					}
				}
				log("  read as old: chunk " + x + ", " + z + " (" + Math.max(Math.abs(i), Math.abs(j)) + " from you), nearest land you made "
						+ (nearest == Integer.MAX_VALUE ? "over 6" : nearest) + " chunks away; shown as " + t.shown(dim(client), x, z));
			}
		}
	}

	private static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		log((ok ? "PASS " : "FAIL ") + what);
	}

	private static void screenshot(MinecraftClient client, String name) {
		ScreenshotRecorder.saveScreenshot(client.runDirectory, "nn_" + name + ".png", client.getFramebuffer(), 1, message -> {});
		log("Screenshot " + name);
	}

	private static void log(String message) {
		NaughtyOrNiceClient.LOGGER.info("[LAND] {}", message);
	}
}
