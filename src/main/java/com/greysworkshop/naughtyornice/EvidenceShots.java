package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Screenshots taken as evidence when someone does something worth proof (kills you, comes into your base), saved
 * to screenshots/naughtyornice and listed on their page.
 */
public final class EvidenceShots {
	public static final String FOLDER = "naughtyornice";
	/** At most one screenshot per player and reason this often. */
	private static final long COOLDOWN = 120_000;
	/** Most screenshots listed per player (the files stay). */
	private static final int MAX_PER_PLAYER = 20;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);
	private static final Map<String, Long> LAST = new HashMap<>();

	private EvidenceShots() {}

	/** Takes a screenshot for {@code profile} unless one was just taken for the same reason. Returns whether it did. */
	public static boolean take(MinecraftClient client, Profile profile, String reason) {
		if (!NNConfig.INSTANCE.evidenceShots || client.getFramebuffer() == null) return false;
		long now = System.currentTimeMillis();
		String key = profile.id + "/" + reason;
		Long last = LAST.get(key);
		if (last != null && now - last < COOLDOWN) return false;
		LAST.put(key, now);
		String name = profile.name.replaceAll("[^A-Za-z0-9_]", "_") + "_" + reason.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
				+ "_" + LocalDateTime.now().format(STAMP) + ".png";
		File dir = new File(new File(client.runDirectory, ScreenshotRecorder.SCREENSHOTS_DIRECTORY), FOLDER);
		if (!dir.isDirectory() && !dir.mkdirs()) return false;
		ScreenshotRecorder.saveScreenshot(client.runDirectory, FOLDER + "/" + name, client.getFramebuffer(), 1, message -> {});
		Profile.Evidence evidence = new Profile.Evidence();
		evidence.time = now;
		evidence.reason = reason;
		evidence.file = ScreenshotRecorder.SCREENSHOTS_DIRECTORY + "/" + FOLDER + "/" + name;
		profile.evidence.add(evidence);
		while (profile.evidence.size() > MAX_PER_PLAYER) profile.evidence.remove(0);
		return true;
	}

	public static File file(MinecraftClient client, Profile.Evidence evidence) {
		return new File(client.runDirectory, evidence.file);
	}

	public static File folder(MinecraftClient client) {
		return new File(new File(client.runDirectory, ScreenshotRecorder.SCREENSHOTS_DIRECTORY), FOLDER);
	}
}
