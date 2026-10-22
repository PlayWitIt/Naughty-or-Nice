package com.greysworkshop.naughtyornice;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;

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
				panel.testPick("containerpeek");
				check("Container Peek picked", panel.testPicked().equals("containerpeek"));
			}
			case 6 -> shot(client, "cp");
			case 7 -> settings(client, NaughtyOrNiceClient.MOD_ID);
			case 8 -> back(client, "nn_settings");
			case 9 -> settings(client, "baseinsurance");
			case 10 -> back(client, "bi_settings");
			case 11 -> settings(client, "containerpeek");
			case 12 -> back(client, "cp_settings");
			case 13 -> {
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
