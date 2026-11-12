package com.greysworkshop.naughtyornice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Signing in to Minecraft with a Microsoft account, the way the official launcher does, without a password ever passing
 * through the game: Microsoft's device code (you type a short code at microsoft.com/link in any browser), then Xbox
 * Live, XSTS, and Minecraft's own login, which gives the token the game uses to join servers.
 * <p>
 * Every call here blocks on the network: run them off the render thread.
 */
final class MicrosoftLogin {
	/** The Minecraft launcher's public Microsoft client, the one Xbox Live accepts for Minecraft. */
	private static final String CLIENT_ID = "00000000402b5328";
	private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	private static final long NO_XBOX_PROFILE = 2148916233L, CHILD_ACCOUNT = 2148916238L, XBOX_NOT_IN_COUNTRY = 2148916235L;

	/** What to show while you sign in: the code, where to type it, how often to ask, and when it stops working. */
	record DeviceCode(String userCode, String deviceCode, String url, int interval, long expiresAt) {
		long secondsLeft() {
			return Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000);
		}
	}

	/** A finished sign-in: who you are, the token for joining servers, and the Microsoft token for next time. */
	record Login(String name, String uuid, String minecraftToken, String refreshToken) {}

	private MicrosoftLogin() {}

	/** Asks Microsoft for a code to type at microsoft.com/link. */
	static DeviceCode requestCode() throws IOException, InterruptedException {
		JsonObject r = postForm("https://login.live.com/oauth20_connect.srf",
				Map.of("client_id", CLIENT_ID, "scope", SCOPE, "response_type", "device_code"));
		if (!r.has("user_code")) throw new IOException("Microsoft didn't give a code (" + reason(r) + ").");
		return new DeviceCode(
				r.get("user_code").getAsString(),
				r.get("device_code").getAsString(),
				string(r, "verification_uri", "https://www.microsoft.com/link"),
				r.has("interval") ? Math.max(1, r.get("interval").getAsInt()) : 5,
				System.currentTimeMillis() + (r.has("expires_in") ? r.get("expires_in").getAsLong() : 900) * 1000);
	}

	/**
	 * Waits for you to enter the code and sign in, then signs in to Minecraft. Progress goes to {@code status} in
	 * plain words; {@code cancelled} stops the wait.
	 */
	static Login awaitCode(DeviceCode code, Consumer<String> status, BooleanSupplier cancelled) throws IOException, InterruptedException {
		int interval = code.interval();
		while (true) {
			if (cancelled.getAsBoolean()) throw new InterruptedException("cancelled");
			if (code.secondsLeft() == 0) throw new IOException("The code ran out. Start again for a new one.");
			Thread.sleep(interval * 1000L);
			JsonObject r = postForm("https://login.live.com/oauth20_token.srf", Map.of(
					"client_id", CLIENT_ID,
					"grant_type", "urn:ietf:params:oauth:grant-type:device_code",
					"device_code", code.deviceCode()));
			if (r.has("access_token")) {
				status.accept("Signed in to Microsoft. Getting your Minecraft profile…");
				return minecraft(r.get("access_token").getAsString(), string(r, "refresh_token", null), status);
			}
			String error = string(r, "error", "");
			if (error.equals("slow_down")) interval += 5;
			else if (!error.equals("authorization_pending")) throw new IOException("Microsoft said: " + reason(r));
		}
	}

	/** Signs in again with a saved Microsoft token, no code needed. */
	static Login refresh(String refreshToken, Consumer<String> status) throws IOException, InterruptedException {
		JsonObject r = postForm("https://login.live.com/oauth20_token.srf", Map.of(
				"client_id", CLIENT_ID, "grant_type", "refresh_token", "refresh_token", refreshToken, "scope", SCOPE));
		if (!r.has("access_token")) {
			throw new IOException("Microsoft wants you to sign in to this account again (" + reason(r) + "). Remove it and add it again.");
		}
		return minecraft(r.get("access_token").getAsString(), string(r, "refresh_token", refreshToken), status);
	}

	/** Microsoft token to Minecraft: Xbox Live, XSTS for Minecraft's services, Minecraft's login, then the profile. */
	private static Login minecraft(String microsoftToken, String refreshToken, Consumer<String> status) throws IOException, InterruptedException {
		status.accept("Signing in to Xbox Live…");
		JsonObject xbox = xboxLive(microsoftToken);
		String userHash = xbox.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
		String xsts = xsts(xbox.get("Token").getAsString());

		status.accept("Signing in to Minecraft…");
		JsonObject login = new JsonObject();
		login.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xsts);
		JsonObject mc = postJson("https://api.minecraftservices.com/authentication/login_with_xbox", login);
		if (!mc.has("access_token")) throw new IOException("Minecraft didn't accept the sign-in (" + reason(mc) + ").");
		String token = mc.get("access_token").getAsString();

		HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile"))
				.timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).GET().build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 404) throw new IOException("This account doesn't own Minecraft: Java Edition.");
		if (response.statusCode() / 100 != 2) throw new IOException("Couldn't get the Minecraft profile (HTTP " + response.statusCode() + ").");
		JsonObject profile = JsonParser.parseString(response.body()).getAsJsonObject();
		return new Login(profile.get("name").getAsString(), dashed(profile.get("id").getAsString()), token, refreshToken);
	}

	/** Xbox Live takes this client's tokens as they are; some setups want "t=" or "d=" in front, so those are tried too. */
	private static JsonObject xboxLive(String microsoftToken) throws IOException, InterruptedException {
		for (String prefix : new String[] {"", "t=", "d="}) {
			JsonObject properties = new JsonObject();
			properties.addProperty("AuthMethod", "RPS");
			properties.addProperty("SiteName", "user.auth.xboxlive.com");
			properties.addProperty("RpsTicket", prefix + microsoftToken);
			JsonObject body = new JsonObject();
			body.add("Properties", properties);
			body.addProperty("RelyingParty", "http://auth.xboxlive.com");
			body.addProperty("TokenType", "JWT");
			JsonObject r = postJson("https://user.auth.xboxlive.com/user/authenticate", body);
			if (r.has("Token")) return r;
		}
		throw new IOException("Xbox Live didn't accept the sign-in.");
	}

	private static String xsts(String xboxToken) throws IOException, InterruptedException {
		JsonArray tokens = new JsonArray();
		tokens.add(xboxToken);
		JsonObject properties = new JsonObject();
		properties.addProperty("SandboxId", "RETAIL");
		properties.add("UserTokens", tokens);
		JsonObject body = new JsonObject();
		body.add("Properties", properties);
		body.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
		body.addProperty("TokenType", "JWT");
		JsonObject r = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", body);
		if (r.has("Token")) return r.get("Token").getAsString();
		long error = r.has("XErr") ? r.get("XErr").getAsLong() : 0;
		throw new IOException(error == NO_XBOX_PROFILE ? "This Microsoft account has no Xbox profile yet. Sign in once at minecraft.net to make one."
				: error == CHILD_ACCOUNT ? "This is a child account: an adult has to add it to a Microsoft family group first."
				: error == XBOX_NOT_IN_COUNTRY ? "Xbox Live isn't available in this account's country."
				: "Xbox didn't allow the Minecraft sign-in (" + error + ").");
	}

	// ---- HTTP ----

	private static JsonObject postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
		String body = form.entrySet().stream()
				.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
				.collect(Collectors.joining("&"));
		return send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body)));
	}

	private static JsonObject postJson(String url, JsonObject body) throws IOException, InterruptedException {
		return send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())));
	}

	/** The JSON answer, with the HTTP status added as "_status"; an empty object (plus status) if it wasn't JSON. */
	private static JsonObject send(HttpRequest.Builder request) throws IOException, InterruptedException {
		HttpResponse<String> response = HTTP.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
		JsonObject answer = new JsonObject();
		try {
			String text = response.body();
			JsonElement parsed = JsonParser.parseString(text == null || text.isBlank() ? "{}" : text);
			if (parsed.isJsonObject()) answer = parsed.getAsJsonObject();
		} catch (RuntimeException notJson) {
			// Leave it empty: the status says what happened.
		}
		answer.addProperty("_status", response.statusCode());
		return answer;
	}

	private static String reason(JsonObject r) {
		if (r.has("error_description")) return r.get("error_description").getAsString();
		if (r.has("errorMessage")) return r.get("errorMessage").getAsString();
		if (r.has("error")) return r.get("error").getAsString();
		return "HTTP " + (r.has("_status") ? r.get("_status").getAsInt() : "?");
	}

	private static String string(JsonObject r, String key, String fallback) {
		return r.has(key) && !r.get(key).isJsonNull() ? r.get(key).getAsString() : fallback;
	}

	private static String dashed(String id) {
		return id.contains("-") ? id : id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
	}
}
