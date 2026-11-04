package com.greysworkshop.naughtyornice;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Development only: a stand-in for Discord's API, for the self-test. Two channels: {@link #CHANNEL} with a relay's
 * chat in every shape relays post it, and {@link #NO_WORDS} whose messages come without their words (a bot without the
 * Message Content intent). The first request is told to wait (a rate limit). Tokens: {@link #BOT} as a bot, or
 * {@link #USER} as an account; anything else is refused.
 */
final class SelfTestDiscord {
	static final String CHANNEL = "1100000000000000001", NO_WORDS = "1100000000000000002";
	/** Constantiam's Discord: general (people talking), the relay, announcements (can't be read); and another Discord. */
	static final String GUILD = "1000000000000000000", OTHER_GUILD = "1000000000000000005", GENERAL = "1100000000000000003",
			ANNOUNCEMENTS = "1100000000000000004", OTHER_CHAT = "1100000000000000006";
	static final String BOT = "test-bot-token", USER = "test-user-token";
	private HttpServer server;
	final List<JsonObject> messages = new ArrayList<>();
	final List<String> auths = new ArrayList<>();
	volatile int requests, waits;
	private volatile boolean waited;
	final Map<String, Integer> calls = new ConcurrentHashMap<>();

	String start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/v10/channels/", this::handle);
		server.createContext("/api/v10/users/", this::handle);
		server.createContext("/api/v10/guilds/", this::handle);
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v10";
	}

	void stop() {
		if (server != null) server.stop(0);
	}

	/** A message at {@code time}: {@code n} keeps ids apart for messages at the same moment. */
	static JsonObject message(long time, int n, String author, boolean webhook, String content) {
		JsonObject m = new JsonObject();
		m.addProperty("id", Long.toUnsignedString(((time - 1420070400000L) << 22) + n));
		m.addProperty("timestamp", java.time.Instant.ofEpochMilli(time).toString());
		m.addProperty("content", content);
		JsonObject a = new JsonObject();
		a.addProperty("username", author);
		a.addProperty("bot", true);
		m.add("author", a);
		if (webhook) m.addProperty("webhook_id", "1200000000000000009");
		return m;
	}

	static JsonObject embed(long time, int n, String authorLine, String description) {
		JsonObject m = message(time, n, "Relay", false, "");
		JsonArray embeds = new JsonArray();
		JsonObject e = new JsonObject();
		JsonObject a = new JsonObject();
		a.addProperty("name", authorLine);
		e.add("author", a);
		if (description != null) e.addProperty("description", description);
		embeds.add(e);
		m.add("embeds", embeds);
		return m;
	}

	private void handle(HttpExchange x) throws IOException {
		requests++;
		String auth = x.getRequestHeaders().getFirst("Authorization");
		auths.add(auth);
		String path = x.getRequestURI().getPath();
		String query = x.getRequestURI().getQuery() == null ? "" : x.getRequestURI().getQuery();
		calls.merge(path, 1, Integer::sum);
		if (!("Bot " + BOT).equals(auth) && !USER.equals(auth)) {
			send(x, 401, "{\"message\": \"401: Unauthorized\", \"code\": 0}");
			return;
		}
		// The first time: wait a moment (Discord's rate limit).
		if (!waited) {
			waited = true;
			waits++;
			send(x, 429, "{\"message\": \"You are being rate limited.\", \"retry_after\": 0.3, \"global\": false}");
			return;
		}
		if (path.equals("/api/v10/users/@me/guilds")) {
			send(x, 200, "[{\"id\": \"" + GUILD + "\", \"name\": \"Constantiam\"}, {\"id\": \"" + OTHER_GUILD + "\", \"name\": \"Some Other Server\"}]");
			return;
		}
		if (path.equals("/api/v10/guilds/" + GUILD + "/channels")) {
			send(x, 200, "[" + ch("900", "Text Channels", 4, 0) + "," + ch(GENERAL, "general", 0, 1) + "," + ch(ANNOUNCEMENTS, "announcements", 5, 2) + ","
					+ ch("901", "Voice", 2, 3) + "," + ch(CHANNEL, "in-game-chat", 0, 4) + "]");
			return;
		}
		if (path.equals("/api/v10/guilds/" + OTHER_GUILD + "/channels")) {
			send(x, 200, "[" + ch(OTHER_CHAT, "server-chat", 0, 0) + "]");
			return;
		}
		String[] parts = path.split("/");
		String channel = parts.length > 4 ? parts[4] : "";
		if (channel.equals(ANNOUNCEMENTS)) {
			send(x, 403, "{\"message\": \"Missing Access\", \"code\": 50001}");
			return;
		}
		if (channel.equals(GENERAL) && path.endsWith("/messages")) {
			JsonArray people = new JsonArray();
			long now = System.currentTimeMillis();
			String[] said = {"anyone on tonight?", "lol", "the queue is so long", "gg", "check pins for the rules"};
			for (int i = 0; i < said.length; i++) people.add(message(now - 600_000L + i * 1000L, i, "person" + i, false, said[i]));
			send(x, 200, people.toString());
			return;
		}
		boolean list = path.endsWith("/messages");
		if (!channel.equals(CHANNEL) && !channel.equals(NO_WORDS)) {
			send(x, 404, "{\"message\": \"Unknown Channel\", \"code\": 10003}");
			return;
		}
		if (!list) {
			send(x, 200, "{\"id\": \"" + channel + "\", \"name\": \"in-game-chat\"}");
			return;
		}
		long after = 0;
		int limit = 50;
		for (String kv : query.split("&")) {
			if (kv.startsWith("after=")) after = Long.parseUnsignedLong(kv.substring(6));
			if (kv.startsWith("limit=")) limit = Integer.parseInt(kv.substring(6));
		}
		List<JsonObject> source;
		if (channel.equals(NO_WORDS)) {
			source = new ArrayList<>();
			long now = System.currentTimeMillis();
			for (int i = 0; i < 10; i++) source.add(message(now - 3_600_000L + i * 1000L, i, "Relay", false, ""));
		} else {
			source = messages;
		}
		// Up to {@code limit} after the cursor, nearest first, sent newest first (as Discord does).
		List<JsonObject> page = new ArrayList<>();
		List<JsonObject> sorted = new ArrayList<>(source);
		sorted.sort(java.util.Comparator.comparing(o -> Long.parseUnsignedLong(o.get("id").getAsString())));
		for (JsonObject m : sorted) {
			if (Long.compareUnsigned(Long.parseUnsignedLong(m.get("id").getAsString()), after) > 0 && page.size() < limit) page.add(m);
		}
		java.util.Collections.reverse(page);
		JsonArray out = new JsonArray();
		page.forEach(out::add);
		send(x, 200, out.toString());
	}

	private static String ch(String id, String name, int type, int position) {
		return "{\"id\": \"" + id + "\", \"name\": \"" + name + "\", \"type\": " + type + ", \"position\": " + position + "}";
	}

	private static void send(HttpExchange x, int code, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		x.getResponseHeaders().add("Content-Type", "application/json");
		x.sendResponseHeaders(code, bytes.length);
		try (var out = x.getResponseBody()) {
			out.write(bytes);
		}
	}
}
