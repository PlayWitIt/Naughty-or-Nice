package com.greysworkshop.naughtyornice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Share links: the lists are compressed, locked with a fresh random key, and put on a paste site; the short code
 * that comes back ("NN2:b" + where + "." + key) is all a friend needs. The key never leaves the code, so the paste
 * site only ever holds scrambled bytes. bytebin.lucko.me (the paste site LuckPerms and spark use) first, paste.rs
 * if that's down.
 */
public final class CloudShare {
	private CloudShare() {}

	public static final String PREFIX = "NN2:";
	/** "NN2:" + host letter + paste id + "." + 22 characters of key. */
	private static final Pattern CODE = Pattern.compile("NN2:([bp])([A-Za-z0-9]{3,40})\\.([A-Za-z0-9_-]{22})");
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Duration TIMEOUT = Duration.ofSeconds(20);
	/** Most a share link can hold, after compressing. */
	private static final int MAX_BYTES = 8_000_000;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	private static String userAgent() {
		String version = FabricLoader.getInstance().getModContainer(NaughtyOrNiceClient.MOD_ID)
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
		return "NaughtyOrNice/" + version + " (Minecraft mod by PlayWit Creations; github.com/PlayWitIt/Naughty-or-Nice)";
	}

	/** A share link found anywhere in some text (a chat line, a pasted message), or null. */
	public static String find(String text) {
		if (text == null) return null;
		Matcher m = CODE.matcher(text);
		return m.find() ? m.group() : null;
	}

	/** Uploads a pack; completes with the share link, or fails with a message for the player. */
	public static CompletableFuture<String> upload(Sharing.Pack pack) {
		String json = Sharing.json(pack);
		return CompletableFuture.supplyAsync(() -> {
			byte[] key = new byte[16];
			RANDOM.nextBytes(key);
			String body = Base64.getUrlEncoder().withoutPadding().encodeToString(lock(gzip(json.getBytes(StandardCharsets.UTF_8)), key));
			if (body.length() > MAX_BYTES) throw new IllegalArgumentException("That's too much to share as a link. Share fewer players, or leave out chat logs.");
			String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
			IOException last = null;
			for (char host : new char[] {'b', 'p'}) {
				try {
					return PREFIX + host + put(host, body) + "." + secret;
				} catch (IOException e) {
					NaughtyOrNiceClient.LOGGER.warn("Share upload to {} failed: {}", host == 'b' ? "bytebin" : "paste.rs", e.toString());
					last = e;
				}
			}
			throw new IllegalStateException("Couldn't reach the share sites (" + (last == null ? "no answer" : last.getMessage())
					+ "). Use Copy code or Save as file instead.");
		});
	}

	/** Downloads and opens a share link; completes with the pack, or fails with a message for the player. */
	public static CompletableFuture<Sharing.Pack> download(String code) {
		Matcher m = CODE.matcher(code == null ? "" : code);
		if (!m.find()) return CompletableFuture.failedFuture(new IllegalArgumentException("That isn't a share link."));
		char host = m.group(1).charAt(0);
		String id = m.group(2);
		byte[] key = Base64.getUrlDecoder().decode(m.group(3));
		return CompletableFuture.supplyAsync(() -> {
			String body;
			try {
				body = get(host, id);
			} catch (IOException e) {
				throw new IllegalStateException(e.getMessage());
			}
			byte[] packed;
			try {
				packed = unlock(Base64.getUrlDecoder().decode(body.strip()), key);
			} catch (IllegalArgumentException | GeneralSecurityException e) {
				throw new IllegalArgumentException("That share link is damaged, or it's been cut short. Ask for a new one.");
			}
			return Sharing.read(Sharing.CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(packed));
		});
	}

	// ---- The paste sites ----

	private static String put(char host, String body) throws IOException {
		HttpRequest.Builder request = HttpRequest.newBuilder().timeout(TIMEOUT).header("User-Agent", userAgent())
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (host == 'b') request.uri(URI.create("https://bytebin.lucko.me/post")).header("Content-Type", "text/plain");
		else request.uri(URI.create("https://paste.rs/")).header("Content-Type", "text/plain");
		HttpResponse<String> response = send(request.build());
		if (response.statusCode() / 100 != 2) throw new IOException("the share site said " + response.statusCode());
		String id;
		if (host == 'b') {
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			id = json.has("key") ? json.get("key").getAsString() : "";
		} else {
			// paste.rs answers with the paste's address; 206 means it was cut short.
			if (response.statusCode() == 206) throw new IOException("the share was too big for paste.rs");
			String url = response.body().strip();
			id = url.substring(url.lastIndexOf('/') + 1);
		}
		if (!id.matches("[A-Za-z0-9]{3,40}")) throw new IOException("the share site gave an odd answer");
		return id;
	}

	private static String get(char host, String id) throws IOException {
		String url = host == 'b' ? "https://bytebin.lucko.me/" + id : "https://paste.rs/" + id;
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", userAgent()).GET().build();
		HttpResponse<String> response = send(request);
		if (response.statusCode() == 404) throw new IOException("That share link has expired or was never finished. Ask for a new one.");
		if (response.statusCode() / 100 != 2) throw new IOException("The share site said " + response.statusCode() + ". Try again in a bit.");
		return response.body();
	}

	private static HttpResponse<String> send(HttpRequest request) throws IOException {
		try {
			return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		} catch (IOException e) {
			throw new IOException("can't reach " + request.uri().getHost() + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"));
		}
	}

	// ---- Locking ----

	private static byte[] gzip(byte[] data) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
			gzip.write(data);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return bytes.toByteArray();
	}

	/** AES-GCM: 12 bytes of nonce, then the locked data (which can't be changed without it showing). */
	static byte[] lock(byte[] data, byte[] key) {
		try {
			byte[] nonce = new byte[12];
			RANDOM.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
			byte[] locked = cipher.doFinal(data);
			byte[] out = new byte[nonce.length + locked.length];
			System.arraycopy(nonce, 0, out, 0, nonce.length);
			System.arraycopy(locked, 0, out, nonce.length, locked.length);
			return out;
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	static byte[] unlock(byte[] data, byte[] key) throws GeneralSecurityException {
		if (data.length < 12 + 16) throw new GeneralSecurityException("too short");
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, data, 0, 12));
		return cipher.doFinal(data, 12, data.length - 12);
	}
}
