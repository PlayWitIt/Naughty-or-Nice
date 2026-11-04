package com.greysworkshop.naughtyornice;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;

import java.util.List;

/**
 * Development test of PlayWit Hub (the mods panel) on the title screen (./gradlew runPanelTest), with Base Insurance and Container
 * Peek installed beside this mod: the button is there once, every mod is listed, switches flip and save, a mod's
 * main switch shows it as off, and each mod's full settings open and come back to the panel. Not shipped.
 */
public final class SelfTestPanel {
	private static int tick = -1, step, passed, failed;
	private static TitleScreen title;
	private static ModsPanel panel;
	private static boolean before;

	private SelfTestPanel() {}

	public static void start() {
		ClientTickEvents.END_CLIENT_TICK.register(SelfTestPanel::tick);
	}

	private static void tick(MinecraftClient client) {
		if (tick < 0) {
			if (!(client.currentScreen instanceof TitleScreen t)) return;
			title = t;
			tick = 0;
		}
		// The first step waits for the title screen to finish fading in.
		if (++tick < 100 || tick % 20 != 0) return;
		try {
			step(client, step++);
		} catch (RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.error("[PANELTEST] crashed", e);
			failed++;
			finish(client);
		}
	}

	private static void step(MinecraftClient client, int n) {
		switch (n) {
			case 0 -> {
				// With Mod Menu (it's in the development game) the hub is linked from each mod's settings instead.
				boolean modMenu = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("modmenu");
				long buttons = Screens.getButtons(title).stream().filter(b -> b.getMessage().getString().equals(ModsPanel.NAME)).count();
				long old = Screens.getButtons(title).stream().filter(b -> b.getMessage().getString().equals("Mods panel")).count();
				check((modMenu ? "no" : "one") + " PlayWit Hub button on the title screen with" + (modMenu ? "" : "out") + " Mod Menu (" + buttons + ")",
						buttons == (modMenu ? 0 : 1) && old == 0);
				shot(client, "title");
			}
			case 1 -> {
				panel = ModsPanel.open(title);
				client.setScreen(panel);
				check("all three mods listed (" + panel.testMods() + ")", panel.testMods() == 3);
			}
			case 2 -> {
				panel.testPick(NaughtyOrNiceClient.MOD_ID);
				before = NNConfig.INSTANCE.eventLog;
				check("flip Event recorder", panel.testFlip("Event recorder"));
				check("Event recorder switched", NNConfig.INSTANCE.eventLog != before);
				shot(client, "nn");
				panel.testFlip("Event recorder");
				check("and back", NNConfig.INSTANCE.eventLog == before);
			}
			case 3 -> {
				panel.testPick("baseinsurance");
				check("Base Insurance picked", panel.testPicked().equals("baseinsurance"));
				check("Base Insurance turned off", panel.testFlip("enabled"));
			}
			case 4 -> shot(client, "bi_off");
			case 5 -> {
				check("Base Insurance turned back on", panel.testFlip("enabled"));
				panel.testPick("hoardeye");
				check("Hoard Eye picked", panel.testPicked().equals("hoardeye"));
			}
			case 6 -> shot(client, "cp");
			case 7 -> settings(client, NaughtyOrNiceClient.MOD_ID);
			case 8 -> back(client, "nn_settings");
			case 9 -> settings(client, "baseinsurance");
			case 10 -> back(client, "bi_settings");
			case 11 -> settings(client, "hoardeye");
			case 12 -> back(client, "cp_settings");
			case 13 -> {
				// Profiles: saved switches come back when applied.
				ModsPanel.saveProfiles(new java.util.ArrayList<>());
				ModsPanel.Profile p = new ModsPanel.Profile();
				p.name = "Test";
				p.servers.add("Play.Example.net:25565");
				p.switches = ModsPanel.snapshot();
				ModsPanel.saveProfiles(new java.util.ArrayList<>(List.of(p)));
				check("every mod's switches are in a profile (" + p.switches.size() + ")", p.switches.keySet().stream().map(k -> k.split("/")[0]).distinct().count() == 3);
				boolean was = NNConfig.INSTANCE.eventLog, bi = ((java.util.function.BooleanSupplier) () -> p.switches.get("baseinsurance/baseinsurance.config.enabled")).getAsBoolean();
				NNConfig.INSTANCE.eventLog = !was;
				int changed = ModsPanel.apply(ModsPanel.loadProfiles().get(0));
				check("applying a profile sets the switches back (" + changed + " changed)", changed == 1 && NNConfig.INSTANCE.eventLog == was && bi);
				check("server addresses match however they're written", ModsPanel.server("Play.Example.net:25565").equals(ModsPanel.server("play.example.net")));
				panel.testPick("profiles");
			}
			case 14 -> shot(client, "profiles");
			case 15 -> {
				// Backups: everything the mods keep goes in the zip; a restore waits for the next start.
				try {
					java.nio.file.Path zip = PanelData.newBackupFile("test");
					int files = PanelData.backUp(List.of("naughtyornice", "baseinsurance", "hoardeye-config.json", "playwit/profiles.json"), zip);
					java.util.Set<String> names = new java.util.HashSet<>();
					try (var z = new java.util.zip.ZipFile(zip.toFile())) {
						z.stream().forEach(e -> names.add(e.getName()));
					}
					check("a backup has every mod's files (" + files + ")", names.contains("naughtyornice/settings.json") && names.contains("playwit/profiles.json")
							&& names.contains("hoardeye-config.json"));
					PanelData.restoreAtNextStart(zip, List.of("naughtyornice"));
					check("a restore waits for the next start", PanelData.restorePendingFor(zip));
					PanelData.cancelRestore();
					check("and can be cancelled", !PanelData.restorePendingFor(zip));
					java.nio.file.Files.delete(zip);
				} catch (java.io.IOException e) {
					check("backup: " + e, false);
				}
				ModsPanel.saveProfiles(new java.util.ArrayList<>());
				panel.testPick("backups");
			}
			case 16 -> shot(client, "backups");
			case 17 -> cloud(client, 0);
			case 18 -> cloud(client, 1);
			case 19 -> cloud(client, 2);
			case 20 -> cloud(client, 3);
			case 21 -> cloud(client, 4);
			case 22 -> cloud(client, 5);
			case 23 -> cloud(client, 6);
			case 24 -> cloud(client, 7);
			case 25 -> cloud(client, 8);
			case 26 -> cloud(client, 9);
			case 27 -> cloud(client, 10);
			case 28 -> cloud(client, 11);
			case 29 -> {
				boolean reduced = client.options.getReducedDebugInfo().getValue();
				ModsPanel.setStreamSafe(true);
				check("stream-safe hides coordinates", ModsPanel.streamSafe() && ModsPanel.hide("12, 64, -40").equals("hidden"));
				check("and the coordinates in F3", client.options.getReducedDebugInfo().getValue());
				ModsPanel.setStreamSafe(false);
				check("off again shows them, and F3 is as it was", !ModsPanel.streamSafe() && ModsPanel.hide("12, 64, -40").equals("12, 64, -40")
						&& client.options.getReducedDebugInfo().getValue() == reduced);
			}
			case 30 -> {
				panel.close();
				check("Esc goes back to the title screen", client.currentScreen == title);
			}
			// Each mod's settings as Mod Menu opens them link to the hub, and the hub comes back to them.
			case 31 -> {
				client.setScreen(new ModMenuIntegration().getModConfigScreenFactory().create(title));
				check("Naughty or Nice settings open from Mod Menu", client.currentScreen instanceof ShopScreen);
			}
			case 32 -> {
				shot(client, "nn_modmenu");
				Screen settings = client.currentScreen;
				check("Naughty or Nice settings link to the hub", settings instanceof ShopScreen s && s.testClick("hub") && ModsPanel.isHub(client.currentScreen));
				hubBack(client, settings, "nn");
			}
			case 33 -> fromModMenu(client, "baseinsurance");
			case 34 -> shot(client, "bi_modmenu");
			case 35 -> hubFromSettings(client, "baseinsurance");
			case 36 -> fromModMenu(client, "hoardeye");
			case 37 -> shot(client, "cp_modmenu");
			case 38 -> hubFromSettings(client, "hoardeye");
			case 39 -> {
				client.currentScreen.close();
				check("and Done goes back to the title screen", client.currentScreen == title);
			}
			default -> finish(client);
		}
	}

	// ---- Backups off this computer: a folder, Google Drive and OneDrive (against a stand-in server) ----

	private static SelfTestCloud cloud;
	private static java.nio.file.Path syncFolder, lastBackup;
	private static final PanelCloud.Service G = PanelCloud.Service.GOOGLE, M = PanelCloud.Service.ONEDRIVE;

	private static void cloud(MinecraftClient client, int n) {
		try {
			cloudStep(client, n);
		} catch (java.io.IOException e) {
			check("cloud step " + n + ": " + e.getMessage(), false);
		}
		if (cloud != null && cloud.problem != null) {
			check("the stand-in server saw nothing wrong (" + cloud.problem + ")", false);
			cloud.problem = null;
		}
	}

	private static long zips(PanelCloud.Service s) {
		return cloud.files.values().stream().filter(f -> f.id().startsWith(s == G ? "g" : "m") && f.name().endsWith(".zip")).count();
	}

	private static long folderZips() throws java.io.IOException {
		try (var files = java.nio.file.Files.list(syncFolder)) {
			return files.filter(f -> f.getFileName().toString().endsWith(".zip")).count();
		}
	}

	private static void cloudStep(MinecraftClient client, int n) throws java.io.IOException {
		switch (n) {
			case 0 -> {
				cloud = new SelfTestCloud();
				System.setProperty("playwit.cloud.base", cloud.base());
				System.setProperty("playwit.cloud.client", "test-client");
				PanelCloud.browser = SelfTestCloud::browse;
				for (PanelCloud.Service s : PanelCloud.Service.values()) PanelCloud.disconnect(s);
				syncFolder = client.runDirectory.toPath().resolve("test-sync-folder");
				if (java.nio.file.Files.exists(syncFolder)) {
					try (var walk = java.nio.file.Files.walk(syncFolder)) {
						for (var f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(f);
					}
				}
				PanelBackups.setFolder(syncFolder);
				PanelBackups.settings().lastAuto = 0;
				check("with a folder set and nothing backed up today, a backup is due", PanelBackups.due());
				PanelCloud.connect(G);
				check("Google Drive: signing in through the browser connects it (" + PanelCloud.account(G) + ")",
						PanelCloud.connected(G) && PanelCloud.account(G).equals("player@gmail.test"));
				PanelCloud.connect(M);
				check("OneDrive: the same (" + PanelCloud.account(M) + ")", PanelCloud.connected(M) && PanelCloud.account(M).equals("player@outlook.test"));
				String saved = java.nio.file.Files.readString(PanelData.dir().resolve("cloud.json"));
				check("staying signed in is kept in config/playwit/cloud.json, which backups leave out",
						saved.contains("refresh") && ModsPanel.allDataPaths().stream().noneMatch(p -> p.equals("playwit") || p.startsWith("playwit/cloud")));
			}
			case 1 -> {
				PanelBackups.run(ModsPanel.allDataPaths(), true, false);
				lastBackup = PanelData.list().get(0);
				byte[] local = java.nio.file.Files.readAllBytes(lastBackup);
				check("a daily backup goes without a problem (" + PanelBackups.status + ")", !PanelBackups.failed && lastBackup.getFileName().toString().endsWith(" auto.zip"));
				check("…a copy in the folder", folderZips() == 1
						&& java.util.Arrays.equals(local, java.nio.file.Files.readAllBytes(syncFolder.resolve(lastBackup.getFileName()))));
				boolean g = cloud.files.values().stream().anyMatch(f -> f.id().startsWith("g") && f.name().equals(lastBackup.getFileName().toString()) && java.util.Arrays.equals(f.data(), local));
				boolean m = cloud.files.values().stream().anyMatch(f -> f.id().startsWith("m") && f.name().equals(lastBackup.getFileName().toString()) && java.util.Arrays.equals(f.data(), local));
				check("…in Google Drive, in a PlayWit backups folder, byte for byte", g && cloud.files.values().stream().anyMatch(f -> f.name().equals("PlayWit backups")));
				check("…and in OneDrive's app folder, byte for byte", m);
				check("…and none is due again until tomorrow", !PanelBackups.due());
			}
			case 2 -> {
				// A big backup (recordings) goes up in pieces.
				java.nio.file.Path big = client.runDirectory.toPath().resolve("2000-01-01 00-00-00 big.zip");
				byte[] data = new byte[11 * 1024 * 1024 + 12345];
				new java.util.Random(7).nextBytes(data);
				java.nio.file.Files.write(big, data);
				int before = cloud.pieces.get();
				PanelCloud.upload(G, big);
				PanelCloud.upload(M, big);
				boolean same = cloud.files.values().stream().filter(f -> f.name().equals("2000-01-01 00-00-00 big.zip")).allMatch(f -> java.util.Arrays.equals(f.data(), data))
						&& cloud.files.values().stream().filter(f -> f.name().equals("2000-01-01 00-00-00 big.zip")).count() == 2;
				check("an 11 MB backup goes up in pieces to both, whole (" + (cloud.pieces.get() - before) + " pieces)", same && cloud.pieces.get() - before == 4);
				java.nio.file.Files.delete(big);
			}
			case 3 -> {
				List<PanelCloud.Remote> g = PanelCloud.list(G), m = PanelCloud.list(M);
				check("the backups there are listed, newest first (" + g.size() + " and " + m.size() + ")", g.size() == 2 && m.size() == 2
						&& g.get(0).name().equals(lastBackup.getFileName().toString()) && m.get(0).name().equals(lastBackup.getFileName().toString()));
				java.nio.file.Path dl = client.runDirectory.toPath().resolve("downloaded.zip");
				PanelCloud.download(G, g.get(0), dl);
				boolean fromG = java.util.Arrays.equals(java.nio.file.Files.readAllBytes(dl), java.nio.file.Files.readAllBytes(lastBackup));
				PanelCloud.download(M, m.get(0), dl);
				boolean fromM = java.util.Arrays.equals(java.nio.file.Files.readAllBytes(dl), java.nio.file.Files.readAllBytes(lastBackup));
				check("downloading one gives the same file, from Google Drive and from OneDrive", fromG && fromM);
				check("…and it's taken for a PlayWit backup; a stray file isn't", PanelBackups.isBackup(dl, ModsPanel.allDataPaths())
						&& !PanelBackups.isBackup(PanelData.dir().resolve("cloud.json"), ModsPanel.allDataPaths()));
				java.nio.file.Files.delete(dl);
			}
			case 4 -> {
				// Only the newest ten automatic ones are kept; the big one (not automatic) stays.
				java.nio.file.Path tmp = client.runDirectory.toPath().resolve("tmp");
				java.nio.file.Files.createDirectories(tmp);
				for (int i = 10; i < 22; i++) {
					java.nio.file.Path f = tmp.resolve("2001-01-" + i + " 00-00-00 auto.zip");
					java.nio.file.Files.write(f, new byte[] {1, 2, 3});
					PanelCloud.upload(G, f);
					java.nio.file.Files.delete(f);
				}
				PanelCloud.prune(G, PanelBackups.KEEP);
				List<PanelCloud.Remote> g = PanelCloud.list(G);
				long autos = g.stream().filter(r -> r.name().endsWith(" auto.zip")).count();
				check("only the newest " + PanelBackups.KEEP + " automatic backups are kept (" + autos + "), today's among them, the big one too",
						autos == PanelBackups.KEEP && g.stream().anyMatch(r -> r.name().equals(lastBackup.getFileName().toString()))
								&& g.stream().anyMatch(r -> r.name().contains("big")));
			}
			case 5 -> {
				// An hour on (or a restart): the saved sign-in gets a new access token by itself.
				cloud.expireAccess();
				PanelCloud.forgetAccess();
				check("after an hour, it stays signed in by itself (Google Drive, OneDrive)", !PanelCloud.list(G).isEmpty() && !PanelCloud.list(M).isEmpty());
				panel.testPick("backups");
				panel.testShowRemote(M);
				panel.testScroll(110);
			}
			case 6 -> shot(client, "backups_cloud");
			// The download happens in the background, then the restore is set up on the game's thread: checked a second later.
			case 7 -> panel.testRestoreFromCloud(M, PanelCloud.list(M).get(0));
			case 8 -> {
				java.nio.file.Path there = PanelData.backups().resolve(lastBackup.getFileName());
				check("Restore on a backup in OneDrive downloads it and restores it at the next start", PanelData.restorePendingFor(there));
				PanelData.cancelRestore();
			}
			case 9 -> {
				// Signed out elsewhere (or access taken back): it says so, and shows as not connected.
				cloud.revoked = true;
				cloud.expireAccess();
				PanelCloud.forgetAccess();
				String said = "";
				try {
					PanelCloud.list(G);
				} catch (java.io.IOException e) {
					said = e.getMessage();
				}
				check("signed out by Google: it says to connect again (" + said + "), and shows as not connected", said.contains("connect it again") && !PanelCloud.connected(G));
				cloud.revoked = false;
			}
			case 10 -> {
				// Quitting: this computer and the folder only (no waiting on uploads as the game closes).
				long folderBefore = folderZips(), mBefore = zips(M);
				PanelBackups.run(ModsPanel.allDataPaths(), true, true);
				check("when you quit, a backup goes to the folder but no upload holds the game up", folderZips() == folderBefore + 1 && zips(M) == mBefore);
			}
			case 11 -> {
				for (PanelCloud.Service s : PanelCloud.Service.values()) PanelCloud.disconnect(s);
				PanelBackups.setFolder(null);
				System.clearProperty("playwit.cloud.base");
				System.clearProperty("playwit.cloud.client");
				cloud.stop();
				check("with no folder and nothing connected, no backup is due", !PanelBackups.due());
				for (java.nio.file.Path f : PanelData.list()) if (f.getFileName().toString().contains("auto")) java.nio.file.Files.delete(f);
			}
			default -> {}
		}
	}

	private static void settings(MinecraftClient client, String id) {
		panel.testPick(id);
		panel.testOpenSettings();
		check(id + " settings open", client.currentScreen != panel && client.currentScreen != null);
	}

	private static void fromModMenu(MinecraftClient client, String id) {
		client.setScreen(ModsPanel.testSettings(id, title));
		check(id + " settings open from Mod Menu", client.currentScreen != null && client.currentScreen != title);
	}

	private static void hubFromSettings(MinecraftClient client, String id) {
		Screen settings = client.currentScreen;
		var link = Screens.getButtons(settings).stream().filter(b -> b.getMessage().getString().equals(ModsPanel.NAME)).findFirst().orElse(null);
		check(id + " settings link to the hub", link != null);
		if (link == null) return;
		((net.minecraft.client.gui.widget.PressableWidget) link).onPress(null);
		check(id + "'s link opens the hub", ModsPanel.isHub(client.currentScreen));
		hubBack(client, settings, id);
	}

	private static void hubBack(MinecraftClient client, Screen settings, String id) {
		if (!ModsPanel.isHub(client.currentScreen)) return;
		// The hub drawn by whichever mod's copy is newest, not necessarily this one's.
		Screen hub = client.currentScreen;
		check(id + ": it's the same hub the other buttons open (" + hub.getClass().getName() + ")", hub.getClass() == ModsPanel.testOwnerClass());
		hub.close();
		check(id + ": closing the hub goes back to the settings", client.currentScreen == settings);
	}

	private static void back(MinecraftClient client, String name) {
		shot(client, name);
		Screen s = client.currentScreen;
		check(name + " opened from the hub has no link back to it", Screens.getButtons(s).stream().noneMatch(b -> b.getMessage().getString().equals(ModsPanel.NAME))
				&& !(s instanceof ShopScreen shop && shop.testClick("hub")));
		s.close();
		check(name + " closes back to the panel", client.currentScreen == panel);
	}

	private static void finish(MinecraftClient client) {
		NaughtyOrNiceClient.LOGGER.info("[PANELTEST] DONE: {} passed, {} failed", passed, failed);
		client.scheduleStop();
	}

	private static void shot(MinecraftClient client, String name) {
		ScreenshotRecorder.saveScreenshot(client.runDirectory, "panel_" + name + ".png", client.getFramebuffer(), 1, message -> {});
	}

	private static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		NaughtyOrNiceClient.LOGGER.info("[PANELTEST] {} {}", ok ? "PASS" : "FAIL", what);
	}
}
