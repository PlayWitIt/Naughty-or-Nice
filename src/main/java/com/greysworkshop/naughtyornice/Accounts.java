package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.greysworkshop.naughtyornice.mixin.MinecraftClientAccessor;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.SocialInteractionsManager;
import net.minecraft.client.session.ProfileKeys;
import net.minecraft.client.session.Session;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Your Minecraft accounts, and switching between them without leaving the game (no Essential, no Meteor).
 * <p>
 * Signing in uses Microsoft's device code: you're shown a short code to type at microsoft.com/link, in any browser, so
 * your password never passes through the game. Then the same steps the launcher takes: Microsoft, Xbox Live, XSTS, and
 * Minecraft's own login, which gives the token the game uses to join servers. Only the Microsoft refresh token is kept,
 * in this computer's config folder, so an account can be used again without signing in; it never leaves this computer
 * except to Microsoft to get a fresh token. Offline accounts (a name only) are for single player and offline servers.
 */
public final class Accounts {
	/** The Minecraft launcher's public Microsoft client, the one that can sign in to Xbox Live for Minecraft. */
	private static final String CLIENT_ID = "00000000402b5328";
	private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** One saved account. */
	public static final class Account {
		public String name;
		public String uuid;
		/** Microsoft refresh token; null for an offline account. */
		public String refresh;
		public boolean offline;

		public boolean microsoft() {
			return !offline;
		}
	}

	private static List<Account> accounts;

	private Accounts() {}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("playwit").resolve("accounts.json");
	}

	public static synchronized List<Account> all() {
		if (accounts == null) {
			accounts = new ArrayList<>();
			try {
				if (Files.exists(file())) {
					List<Account> read = GSON.fromJson(Files.readString(file()), new TypeToken<List<Account>>() {}.getType());
					if (read != null) read.stream().filter(a -> a != null && a.name != null).forEach(accounts::add);
				}
			} catch (IOException | RuntimeException e) {
				NaughtyOrNiceClient.LOGGER.error("Could not read the saved accounts", e);
			}
		}
		return accounts;
	}

	static synchronized void save() {
		try {
			Path f = file();
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling("accounts.json.tmp");
			Files.writeString(tmp, GSON.toJson(all()));
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			try {
				// Keep it readable by you only, where the file system allows it.
				f.toFile().setReadable(false, false);
				f.toFile().setReadable(true, true);
				f.toFile().setWritable(false, false);
				f.toFile().setWritable(true, true);
			} catch (SecurityException ignored) {
			}
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not save the accounts", e);
		}
	}

	static synchronized void remember(Account account) {
		all().removeIf(a -> a.name.equalsIgnoreCase(account.name) || (a.uuid != null && a.uuid.equals(account.uuid)));
		all().add(0, account);
		save();
	}

	public static synchronized void forget(Account account) {
		all().remove(account);
		save();
	}

	/** Whether this is the account the game is using now. */
	public static boolean current(Account account) {
		Session s = MinecraftClient.getInstance().getSession();
		return s.getUsername().equalsIgnoreCase(account.name);
	}

	// ---- Signing in with Microsoft ----

	/** What to show while waiting for you to sign in: the code, where to type it, and for how long it works. */
	public record DeviceCode(String userCode, String deviceCode, String url, int interval, long expiresAt) {}

	/** Asks Microsoft for a code to type at microsoft.com/link. */
	public static DeviceCode startSignIn() throws IOException, InterruptedException {
		JsonObject r = post("https://login.live.com/oauth20_connect.srf",
				Map.of("client_id", CLIENT_ID, "scope", SCOPE, "response_type", "device_code"));
		if (!r.has("user_code")) throw new IOException("Microsoft didn't give a code (" + error(r) + ")");
		return new DeviceCode(r.get("user_code").getAsString(), r.get("device_code").getAsString(),
				r.has("verification_uri") ? r.get("verification_uri").getAsString() : "https://www.microsoft.com/link",
				r.has("interval") ? Math.max(1, r.get("interval").getAsInt()) : 5,
				System.currentTimeMillis() + (r.has("expires_in") ? r.get("expires_in").getAsLong() : 900) * 1000);
	}

	/**
	 * Waits for you to finish signing in with the code, then signs in to Minecraft. Reports progress in plain words to
	 * {@code status}; returns the account (saved), or throws with a reason. {@code cancelled} stops it early.
	 */
	public static Account finishSignIn(DeviceCode code, Consumer<String> status, java.util.function.BooleanSupplier cancelled)
			throws IOException, InterruptedException {
		String access = null, refresh = null;
		int interval = code.interval();
		while (access == null) {
			if (cancelled.getAsBoolean()) throw new InterruptedException("cancelled");
			if (System.currentTimeMillis() > code.expiresAt()) throw new IOException("The code ran out. Try again for a new one.");
			Thread.sleep(interval * 1000L);
			JsonObject r = post("https://login.live.com/oauth20_token.srf", Map.of("client_id", CLIENT_ID,
					"grant_type", "urn:ietf:params:oauth:grant-type:device_code", "device_code", code.deviceCode()));
			if (r.has("access_token")) {
				access = r.get("access_token").getAsString();
				refresh = r.has("refresh_token") ? r.get("refresh_token").getAsString() : null;
			} else {
				String err = r.has("error") ? r.get("error").getAsString() : "";
				if (err.equals("slow_down")) interval += 5;
				else if (!err.equals("authorization_pending")) throw new IOException("Microsoft said: " + error(r));
			}
		}
		status.accept("Signed in to Microsoft. Getting your Minecraft profile…");
		Account account = minecraft(access, status);
		account.refresh = refresh;
		remember(account);
		return account;
	}

	/** A fresh Minecraft token for a saved account, from its refresh token. Updates the saved refresh token. */
	private static String refresh(Account account, Consumer<String> status) throws IOException, InterruptedException {
		status.accept("Signing in as " + account.name + "…");
		JsonObject r = post("https://login.live.com/oauth20_token.srf",
				Map.of("client_id", CLIENT_ID, "grant_type", "refresh_token", "refresh_token", account.refresh, "scope", SCOPE));
		if (!r.has("access_token")) throw new IOException("Microsoft wants you to sign in again (" + error(r) + "). Remove it and add it again.");
		if (r.has("refresh_token")) {
			account.refresh = r.get("refresh_token").getAsString();
			save();
		}
		Account fresh = minecraft(r.get("access_token").getAsString(), status);
		account.name = fresh.name;
		account.uuid = fresh.uuid;
		save();
		return fresh.refresh; // the Minecraft token is carried here, see minecraft()
	}

	/**
	 * Microsoft token to Minecraft: Xbox Live, then XSTS for Minecraft's services, then Minecraft's login and profile.
	 * Returns an unsaved account with its name and UUID; its {@code refresh} field briefly carries the Minecraft token.
	 */
	private static Account minecraft(String msAccess, Consumer<String> status) throws IOException, InterruptedException {
		// Xbox Live. Tokens from this client go in as they are; some setups want a "t=" or "d=" in front, so try those too.
		JsonObject xbl = null;
		for (String prefix : new String[] {"", "t=", "d="}) {
			JsonObject props = new JsonObject();
			props.addProperty("AuthMethod", "RPS");
			props.addProperty("SiteName", "user.auth.xboxlive.com");
			props.addProperty("RpsTicket", prefix + msAccess);
			JsonObject body = new JsonObject();
			body.add("Properties", props);
			body.addProperty("RelyingParty", "http://auth.xboxlive.com");
			body.addProperty("TokenType", "JWT");
			JsonObject r = postJson("https://user.auth.xboxlive.com/user/authenticate", body, null);
			if (r.has("Token")) {
				xbl = r;
				break;
			}
		}
		if (xbl == null) throw new IOException("Xbox Live didn't accept the sign-in.");
		String uhs = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();

		JsonObject xp = new JsonObject();
		xp.addProperty("SandboxId", "RETAIL");
		JsonArray tokens = new JsonArray();
		tokens.add(xbl.get("Token").getAsString());
		xp.add("UserTokens", tokens);
		JsonObject xb = new JsonObject();
		xb.add("Properties", xp);
		xb.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
		xb.addProperty("TokenType", "JWT");
		JsonObject xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xb, null);
		if (!xsts.has("Token")) {
			long xerr = xsts.has("XErr") ? xsts.get("XErr").getAsLong() : 0;
			throw new IOException(xerr == 2148916233L ? "This Microsoft account has no Xbox profile. Sign in once at minecraft.net to make one."
					: xerr == 2148916238L ? "This is a child account: an adult has to add it to a family group first."
					: xerr == 2148916235L ? "Xbox Live isn't available in this account's country."
					: "Xbox didn't allow Minecraft sign-in (" + xerr + ").");
		}

		status.accept("Signing in to Minecraft…");
		JsonObject lb = new JsonObject();
		lb.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xsts.get("Token").getAsString());
		JsonObject mc = postJson("https://api.minecraftservices.com/authentication/login_with_xbox", lb, null);
		if (!mc.has("access_token")) throw new IOException("Minecraft didn't accept the sign-in (" + error(mc) + ").");
		String token = mc.get("access_token").getAsString();

		HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile"))
				.timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).GET().build();
		HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() == 404) throw new IOException("This account doesn't own Minecraft: Java Edition.");
		if (res.statusCode() / 100 != 2) throw new IOException("Couldn't get the Minecraft profile (" + res.statusCode() + ").");
		JsonObject profile = JsonParser.parseString(res.body()).getAsJsonObject();
		Account a = new Account();
		a.name = profile.get("name").getAsString();
		a.uuid = dashed(profile.get("id").getAsString());
		a.refresh = token;
		return a;
	}

	/** An offline account: a name only, for single player and offline servers. */
	public static Account offline(String name) {
		Account a = new Account();
		a.name = name;
		a.offline = true;
		a.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
		remember(a);
		return a;
	}

	// ---- Switching ----

	/**
	 * Signs in as a saved account (off the render thread) and switches the game to it. {@code done} gets null when
	 * it worked, or the reason it didn't; both on the render thread.
	 */
	public static void use(Account account, Consumer<String> status, Consumer<String> done) {
		MinecraftClient client = MinecraftClient.getInstance();
		CompletableFuture.runAsync(() -> {
			try {
				String token = account.offline ? "0" : refresh(account, status);
				client.execute(() -> {
					try {
						apply(client, account, token);
						done.accept(null);
					} catch (RuntimeException e) {
						NaughtyOrNiceClient.LOGGER.error("Could not switch account", e);
						done.accept("Couldn't switch: " + e.getMessage());
					}
				});
			} catch (IOException | InterruptedException | RuntimeException e) {
				NaughtyOrNiceClient.LOGGER.warn("Could not sign in as {}: {}", account.name, e.getMessage());
				client.execute(() -> done.accept(e.getMessage() == null ? e.toString() : e.getMessage()));
			}
		});
	}

	/** For the self-test: switch to an offline account right away. */
	static void applyNow(Account account) {
		apply(MinecraftClient.getInstance(), account, "0");
	}

	/** Puts the account in place: who you are, and everything the game made from it (chat keys, blocks, skins). */
	private static void apply(MinecraftClient client, Account account, String token) {
		UUID uuid = UUID.fromString(account.uuid);
		Session session = new Session(account.name, uuid, token, Optional.empty(), Optional.empty());
		UserApiService api;
		if (account.offline) {
			api = UserApiService.OFFLINE;
		} else {
			try {
				api = new YggdrasilAuthenticationService(client.getNetworkProxy()).createUserApiService(token);
			} catch (RuntimeException e) {
				NaughtyOrNiceClient.LOGGER.warn("Couldn't reach Mojang's user services; chat signing may be off", e);
				api = UserApiService.OFFLINE;
			}
		}
		UserApiService services = api;
		MinecraftClientAccessor game = (MinecraftClientAccessor) client;
		game.naughtyornice$setSession(session);
		game.naughtyornice$setUserApiService(services);
		game.naughtyornice$setProfileKeys(account.offline ? ProfileKeys.MISSING : ProfileKeys.create(services, session, client.runDirectory.toPath()));
		game.naughtyornice$setSocialInteractionsManager(new SocialInteractionsManager(client, services));
		game.naughtyornice$setUserPropertiesFuture(CompletableFuture.supplyAsync(() -> {
			try {
				return services.fetchProperties();
			} catch (Exception e) {
				return UserApiService.OFFLINE_PROPERTIES;
			}
		}, net.minecraft.util.Util.getIoWorkerExecutor()));
		game.naughtyornice$setGameProfileFuture(CompletableFuture.supplyAsync(
				() -> client.getApiServices().sessionService().fetchProfile(uuid, true), net.minecraft.util.Util.getIoWorkerExecutor()));
		NaughtyOrNiceClient.LOGGER.info("Switched account to {}", account.name);
	}

	/** An Accounts button on the title and multiplayer screens, showing who you're playing as. */
	public static void register() {
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			boolean title = screen instanceof net.minecraft.client.gui.screen.TitleScreen;
			if (!title && !(screen instanceof net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen)) return;
			String label = "Account: " + client.getSession().getUsername();
			int bw = Math.max(90, client.textRenderer.getWidth(label) + 16);
			net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).add(net.minecraft.client.gui.widget.ButtonWidget
					.builder(net.minecraft.text.Text.literal(label), b -> client.setScreen(new AccountsScreen(screen)))
					.dimensions(w - bw - 4, 4, bw, 20)
					.tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(net.minecraft.text.Text.literal("Accounts: switch who you play as")))
					.build());
		});
	}

	// ---- HTTP ----

	private static JsonObject post(String url, Map<String, String> form) throws IOException, InterruptedException {
		String body = form.entrySet().stream()
				.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
				.collect(Collectors.joining("&"));
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
		return parse(HTTP.send(req, HttpResponse.BodyHandlers.ofString()));
	}

	private static JsonObject postJson(String url, JsonObject body, String bearer) throws IOException, InterruptedException {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/json").header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
		if (bearer != null) b.header("Authorization", "Bearer " + bearer);
		return parse(HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()));
	}

	private static JsonObject parse(HttpResponse<String> res) {
		try {
			var el = JsonParser.parseString(res.body() == null || res.body().isBlank() ? "{}" : res.body());
			JsonObject o = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
			o.addProperty("_status", res.statusCode());
			return o;
		} catch (RuntimeException e) {
			JsonObject o = new JsonObject();
			o.addProperty("_status", res.statusCode());
			return o;
		}
	}

	private static String error(JsonObject r) {
		if (r.has("error_description")) return r.get("error_description").getAsString();
		if (r.has("errorMessage")) return r.get("errorMessage").getAsString();
		if (r.has("error")) return r.get("error").toString();
		return "HTTP " + (r.has("_status") ? r.get("_status").getAsInt() : "?");
	}

	private static String dashed(String id) {
		if (id.contains("-")) return id;
		return id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
	}
}
