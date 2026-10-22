package com.greysworkshop.naughtyornice;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;

import java.util.List;

/**
 * Development test of the mods panel on the title screen (./gradlew runPanelTest), with Base Insurance and Container
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
				long buttons = Screens.getButtons(title).stream().filter(b -> b.getMessage().getString().equals("Mods panel")).count();
				check("one Mods panel button on the title screen (" + buttons + ")", buttons == 1);
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
			case 17 -> {
				boolean reduced = client.options.getReducedDebugInfo().getValue();
				ModsPanel.setStreamSafe(true);
				check("stream-safe hides coordinates", ModsPanel.streamSafe() && ModsPanel.hide("12, 64, -40").equals("hidden"));
				check("and the coordinates in F3", client.options.getReducedDebugInfo().getValue());
				ModsPanel.setStreamSafe(false);
				check("off again shows them, and F3 is as it was", !ModsPanel.streamSafe() && ModsPanel.hide("12, 64, -40").equals("12, 64, -40")
						&& client.options.getReducedDebugInfo().getValue() == reduced);
			}
			case 18 -> {
				panel.close();
				check("Esc goes back to the title screen", client.currentScreen == title);
			}
			default -> finish(client);
		}
	}

	private static void settings(MinecraftClient client, String id) {
		panel.testPick(id);
		panel.testOpenSettings();
		check(id + " settings open", client.currentScreen != panel && client.currentScreen != null);
	}

	private static void back(MinecraftClient client, String name) {
		shot(client, name);
		Screen s = client.currentScreen;
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
