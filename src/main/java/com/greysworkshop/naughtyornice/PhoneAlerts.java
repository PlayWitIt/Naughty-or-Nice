package com.greysworkshop.naughtyornice;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Alerts to your phone: warnings and pings are also posted to a Discord webhook you set (a channel in your own server,
 * so Discord on your phone notifies you), by default only while the game isn't the window you're using. Base
 * Insurance's alerts come here too, through the object share. Nothing is sent unless you've put in a webhook link.
 */
public final class PhoneAlerts {
	/** Where Base Insurance (or any PlayWit mod) hands over an alert to send: a Consumer&lt;String&gt;. */
	public static final String SHARE_KEY = "playwit:phone_alert";
	private static final Pattern WEBHOOK = Pattern.compile("^https://(?:(?:canary|ptb)\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[A-Za-z0-9_-]+$");
	/** The same message isn't sent twice within this long, and at most one message goes this often. */
	private static final long SAME_MS = 60_000, GAP_MS = 2_000;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final Map<String, Long> sent = new HashMap<>();
	private static long lastSent;
	private static StringBuilder waiting = new StringBuilder();

	private PhoneAlerts() {}

	public static void register() {
		FabricLoader.getInstance().getObjectShare().put(SHARE_KEY, (Consumer<String>) text -> MinecraftClient.getInstance().execute(() -> send(text)));
	}

	public static boolean validUrl(String url) {
		return url != null && WEBHOOK.matcher(url.strip()).matches();
	}

	/** Sends {@code text} if a webhook is set, and you're away (unless set to always). */
	public static void send(String text) {
		NNConfig c = NNConfig.INSTANCE;
		MinecraftClient client = MinecraftClient.getInstance();
		if (!NNConfig.on() || !validUrl(c.phoneWebhook) || text == null || text.isBlank()) return;
		if (c.phoneOnlyAway && client.isWindowFocused()) return;
		long now = System.currentTimeMillis();
		sent.values().removeIf(t -> now - t > SAME_MS);
		if (sent.containsKey(text)) return;
		sent.put(text, now);
		if (!waiting.isEmpty()) waiting.append('\n');
		waiting.append(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))).append("  ").append(text);
		flush(false);
	}

	/** Called every second: sends what's been waiting for the gap between messages. */
	public static void flush(boolean force) {
		long now = System.currentTimeMillis();
		if (waiting.isEmpty() || !force && now - lastSent < GAP_MS) return;
		String where = NaughtyOrNiceClient.INSTANCE == null || NaughtyOrNiceClient.INSTANCE.ledger.key() == null ? ""
				: Sharing.placeName(NaughtyOrNiceClient.INSTANCE.ledger.key());
		post(NNConfig.INSTANCE.phoneWebhook.strip(), (where.isEmpty() ? "" : "**" + where + "**\n") + waiting, null);
		waiting = new StringBuilder();
		lastSent = now;
	}

	/** A test message, with what happened handed back on the game thread. */
	public static void test(String url, Consumer<String> result) {
		if (!validUrl(url)) {
			result.accept("That isn't a Discord webhook link (it starts https://discord.com/api/webhooks/).");
			return;
		}
		post(url.strip(), "Naughty or Nice is connected. Alerts will come here" + (NNConfig.INSTANCE.phoneOnlyAway ? " while you're away from the game." : "."), result);
	}

	private static void post(String url, String content, Consumer<String> result) {
		JsonObject body = new JsonObject();
		body.addProperty("username", "PlayWit mods");
		body.addProperty("content", content.length() > 1900 ? content.substring(0, 1900) + "…" : content);
		// Chat text in an alert can't ping anyone (no @everyone from someone's message).
		JsonObject mentions = new JsonObject();
		mentions.add("parse", new JsonArray());
		body.add("allowed_mentions", mentions);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
			String outcome = error != null ? "Couldn't reach Discord: " + error.getMessage()
					: response.statusCode() / 100 == 2 ? "Sent. Check the channel."
					: response.statusCode() == 404 ? "Discord says that webhook doesn't exist (deleted?)."
					: "Discord answered " + response.statusCode() + ".";
			if (error != null || response.statusCode() / 100 != 2) NaughtyOrNiceClient.LOGGER.warn("Phone alert: {}", outcome);
			if (result != null) MinecraftClient.getInstance().execute(() -> result.accept(outcome));
		});
	}
}
