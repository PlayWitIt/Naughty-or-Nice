package com.greysworkshop.naughtyornice;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for Google's and Microsoft's sign-in and file servers, for the panel test: it answers the way they're
 * documented to (PKCE checked, resumable uploads in pieces, OneDrive's upload and download addresses refusing a
 * sign-in header), keeps the files in memory, and counts what it was sent. Not shipped.
 */
final class SelfTestCloud {
	record Stored(String id, String name, String parent, byte[] data, long time) {}

	final Map<String, Stored> files = new ConcurrentHashMap<>();
	final AtomicInteger pieces = new AtomicInteger();
	/** Refresh tokens it has handed out and still honours. */
	final Map<String, String> refreshTokens = new ConcurrentHashMap<>();
	/** Set to make every request with a sign-in fail as signed out. */
	volatile boolean revoked;
	volatile String problem;

	private final HttpServer server;
	private final Map<String, String> challenges = new ConcurrentHashMap<>();
	private final Map<String, ByteArrayOutputStream> sessions = new ConcurrentHashMap<>();
	private final Map<String, String[]> sessionMeta = new ConcurrentHashMap<>();
	private final Map<String, String> accessTokens = new ConcurrentHashMap<>();
	private final AtomicInteger ids = new AtomicInteger();

	SelfTestCloud() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	String base() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	void stop() {
		server.stop(0);
	}

	/** A browser for the sign-in: opens the page and follows it back to the mod's local port, without waiting. */
	static void browse(String url) {
		HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
				.sendAsync(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
	}

	long count(String service) {
		return files.values().stream().filter(f -> f.id().startsWith(service)).count();
	}

	private void handle(HttpExchange x) throws IOException {
		try {
			route(x);
		} catch (RuntimeException e) {
			problem = e.toString();
			send(x, 500, "{}");
		}
	}

	private void route(HttpExchange x) throws IOException {
		String path = x.getRequestURI().getRawPath(), method = x.getRequestMethod();
		Map<String, String> q = query(x.getRequestURI().getRawQuery());
		byte[] body = x.getRequestBody().readAllBytes();
		String auth = x.getRequestHeaders().getFirst("Authorization");

		// Sign-in pages: straight back to the mod with a code, as if the player said yes.
		if (path.endsWith("/oauth2/v2/auth") || path.endsWith("/oauth2/v2.0/authorize")) {
			if (!"S256".equals(q.get("code_challenge_method")) || q.get("code_challenge") == null || !"test-client".equals(q.get("client_id"))) {
				problem = "sign-in page asked without PKCE or the right client: " + q;
			}
			String code = "code" + ids.incrementAndGet();
			challenges.put(code, q.getOrDefault("code_challenge", ""));
			redirect(x, q.get("redirect_uri") + "/?code=" + code + "&state=" + q.get("state"));
			return;
		}
		if (path.endsWith("/token")) {
			Map<String, String> form = query(new String(body, StandardCharsets.UTF_8));
			String service = path.contains("googleapis") ? "g" : "m";
			if ("authorization_code".equals(form.get("grant_type"))) {
				String expected = challenges.remove(form.get("code"));
				String got = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(form.getOrDefault("code_verifier", "")));
				if (expected == null || !expected.equals(got)) {
					send(x, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"PKCE check failed\"}");
					return;
				}
			} else if ("refresh_token".equals(form.get("grant_type"))) {
				if (revoked || !refreshTokens.containsKey(form.get("refresh_token"))) {
					send(x, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}");
					return;
				}
			} else {
				send(x, 400, "{\"error\":\"unsupported_grant_type\"}");
				return;
			}
			JsonObject t = new JsonObject();
			String access = "access" + ids.incrementAndGet();
			accessTokens.put(access, service);
			t.addProperty("access_token", access);
			t.addProperty("expires_in", 3600);
			// Google sends a refresh token only the first time; Microsoft every time.
			if (service.equals("m") || "authorization_code".equals(form.get("grant_type"))) {
				String refresh = "refresh" + ids.incrementAndGet();
				refreshTokens.put(refresh, service);
				t.addProperty("refresh_token", refresh);
			}
			send(x, 200, t.toString());
			return;
		}
		// Upload pieces: Google's need the sign-in, OneDrive's must not have it.
		if (path.startsWith("/session/")) {
			String id = path.substring("/session/".length());
			boolean onedrive = id.startsWith("m");
			if (onedrive == (auth != null) || (!onedrive && !signedIn(auth))) {
				send(x, 401, "{\"error\":\"wrong sign-in on an upload piece\"}");
				return;
			}
			String range = x.getRequestHeaders().getFirst("Content-Range");
			// "bytes 0-1023/4096"
			String[] r = range.substring(6).split("[-/]");
			long start = Long.parseLong(r[0]), end = Long.parseLong(r[1]), total = Long.parseLong(r[2]);
			ByteArrayOutputStream buf = sessions.get(id);
			if (buf == null || start != buf.size() || end - start + 1 != body.length) {
				send(x, 400, "{\"error\":\"piece out of order\"}");
				return;
			}
			buf.write(body);
			pieces.incrementAndGet();
			if (buf.size() < total) {
				if (onedrive) send(x, 202, "{}");
				else send(x, 308, "");
				return;
			}
			String[] meta = sessionMeta.remove(id);
			sessions.remove(id);
			String fileId = (onedrive ? "m" : "g") + ids.incrementAndGet();
			files.values().removeIf(f -> f.id().startsWith(onedrive ? "m" : "g") && f.name().equals(meta[0]) && onedrive);
			files.put(fileId, new Stored(fileId, meta[0], meta[1], buf.toByteArray(), System.currentTimeMillis() + ids.get()));
			send(x, 201, "{\"id\":\"" + fileId + "\"}");
			return;
		}
		if (path.startsWith("/blob/")) {
			if (auth != null) {
				send(x, 401, "{\"error\":\"a download address takes no sign-in\"}");
				return;
			}
			Stored f = files.get(path.substring("/blob/".length()));
			sendBytes(x, f == null ? 404 : 200, f == null ? new byte[0] : f.data());
			return;
		}
		if (!signedIn(auth)) {
			send(x, 401, "{\"error\":{\"code\":\"InvalidAuthenticationToken\",\"message\":\"Not signed in\"}}");
			return;
		}

		// Google Drive.
		if (path.startsWith("/www.googleapis.com/")) {
			String p = path.substring("/www.googleapis.com".length());
			if (p.equals("/drive/v3/about")) {
				send(x, 200, "{\"user\":{\"emailAddress\":\"player@gmail.test\"}}");
			} else if (p.equals("/upload/drive/v3/files") && method.equals("POST")) {
				JsonObject meta = com.google.gson.JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
				String parent = meta.getAsJsonArray("parents").get(0).getAsString();
				String id = "g-session" + ids.incrementAndGet();
				sessions.put(id, new ByteArrayOutputStream());
				sessionMeta.put(id, new String[] {meta.get("name").getAsString(), parent});
				x.getResponseHeaders().add("Location", base() + "/session/" + id);
				send(x, 200, "");
			} else if (p.equals("/drive/v3/files") && method.equals("POST")) {
				JsonObject meta = com.google.gson.JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
				String id = "gfolder" + ids.incrementAndGet();
				files.put(id, new Stored(id, meta.get("name").getAsString(), "", new byte[0], 0));
				send(x, 200, "{\"id\":\"" + id + "\"}");
			} else if (p.equals("/drive/v3/files") && method.equals("GET")) {
				String query = q.getOrDefault("q", "");
				JsonArray list = new JsonArray();
				for (Stored f : files.values()) {
					boolean folder = f.id().startsWith("gfolder");
					boolean match = query.contains("mimeType='application/vnd.google-apps.folder'") ? folder && query.contains("name='" + f.name() + "'")
							: !folder && f.id().startsWith("g") && query.startsWith("'" + f.parent() + "' in parents");
					if (!match) continue;
					JsonObject o = new JsonObject();
					o.addProperty("id", f.id());
					o.addProperty("name", f.name());
					o.addProperty("size", Long.toString(f.data().length));
					o.addProperty("createdTime", Instant.ofEpochMilli(f.time()).toString());
					list.add(o);
				}
				JsonObject r = new JsonObject();
				r.add("files", list);
				send(x, 200, r.toString());
			} else if (p.startsWith("/drive/v3/files/")) {
				String id = p.substring("/drive/v3/files/".length());
				Stored f = files.get(id);
				if (f == null) send(x, 404, "{\"error\":{\"message\":\"File not found\"}}");
				else if (method.equals("DELETE")) {
					files.remove(id);
					send(x, 204, "");
				} else if ("media".equals(q.get("alt"))) sendBytes(x, 200, f.data());
				else send(x, 200, "{\"id\":\"" + id + "\",\"trashed\":false}");
			} else {
				send(x, 404, "{\"error\":{\"message\":\"no such thing: " + p + "\"}}");
			}
			return;
		}

		// OneDrive (Microsoft Graph).
		if (path.startsWith("/graph.microsoft.com/v1.0")) {
			String p = URLDecoder.decode(path.substring("/graph.microsoft.com/v1.0".length()), StandardCharsets.UTF_8);
			if (p.equals("/me")) {
				send(x, 200, "{\"mail\":null,\"userPrincipalName\":\"player@outlook.test\"}");
			} else if (p.startsWith("/me/drive/special/approot:/") && p.endsWith(":/createUploadSession")) {
				String name = p.substring("/me/drive/special/approot:/".length(), p.length() - ":/createUploadSession".length());
				String id = "m-session" + ids.incrementAndGet();
				sessions.put(id, new ByteArrayOutputStream());
				sessionMeta.put(id, new String[] {name, "approot"});
				send(x, 200, "{\"uploadUrl\":\"" + base() + "/session/" + id + "\"}");
			} else if (p.equals("/me/drive/special/approot/children")) {
				JsonArray list = new JsonArray();
				for (Stored f : files.values()) {
					if (!f.id().startsWith("m")) continue;
					JsonObject o = new JsonObject();
					o.addProperty("id", f.id());
					o.addProperty("name", f.name());
					o.addProperty("size", f.data().length);
					o.addProperty("createdDateTime", Instant.ofEpochMilli(f.time()).toString());
					o.add("file", new JsonObject());
					list.add(o);
				}
				JsonObject r = new JsonObject();
				r.add("value", list);
				send(x, 200, r.toString());
			} else if (p.startsWith("/me/drive/items/")) {
				String rest = p.substring("/me/drive/items/".length());
				if (rest.endsWith("/content")) {
					String id = rest.substring(0, rest.length() - "/content".length());
					if (!files.containsKey(id)) send(x, 404, "{}");
					else redirect(x, base() + "/blob/" + id);
				} else if (method.equals("DELETE")) {
					files.remove(rest);
					send(x, 204, "");
				} else send(x, 404, "{}");
			} else {
				send(x, 404, "{\"error\":{\"message\":\"no such thing: " + p + "\"}}");
			}
			return;
		}
		send(x, 404, "{}");
	}

	private boolean signedIn(String auth) {
		return !revoked && auth != null && auth.startsWith("Bearer ") && accessTokens.containsKey(auth.substring(7));
	}

	/** Makes every access token run out, as an hour passing would. */
	void expireAccess() {
		accessTokens.clear();
	}

	private static Map<String, String> query(String raw) {
		Map<String, String> map = new LinkedHashMap<>();
		if (raw == null || raw.isEmpty()) return map;
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return map;
	}

	private static byte[] sha256(String s) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void redirect(HttpExchange x, String to) throws IOException {
		x.getResponseHeaders().add("Location", to);
		x.sendResponseHeaders(302, -1);
		x.close();
	}

	private static void send(HttpExchange x, int code, String body) throws IOException {
		sendBytes(x, code, body.getBytes(StandardCharsets.UTF_8));
	}

	private static void sendBytes(HttpExchange x, int code, byte[] body) throws IOException {
		if (body.length == 0 || code == 204 || code == 308) {
			x.sendResponseHeaders(code, -1);
		} else {
			x.getResponseHeaders().add("Content-Type", "application/json");
			x.sendResponseHeaders(code, body.length);
			x.getResponseBody().write(body);
		}
		x.close();
	}

	@SuppressWarnings("unused")
	private static List<String> names(List<Stored> list) {
		List<String> out = new ArrayList<>();
		for (Stored s : list) out.add(s.name());
		return out;
	}
}
