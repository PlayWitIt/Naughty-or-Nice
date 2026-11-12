package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.greysworkshop.naughtyornice.mixin.MinecraftClientAccessor;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.network.SocialInteractionsManager;
import net.minecraft.client.session.ProfileKeys;
import net.minecraft.client.session.Session;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Your Minecraft accounts, and switching between them in game: no Essential, no Meteor, no restarting the launcher.
 * <p>
 * Microsoft accounts are added once with a code (see {@link MicrosoftLogin}); only Microsoft's refresh token is kept,
 * in this computer's config folder, readable by you only, and it's only ever sent back to Microsoft. The account the
 * launcher started the game with is always listed too, so you can go back to it. Offline names are for single player.
 * On a server, switching leaves, switches, and joins the same server again as the new account.
 */
public final class Accounts {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public enum Kind { MICROSOFT, OFFLINE, LAUNCHER }

	/** One account. Saved ones are in accounts.json; the launcher's is only known for this run. */
	public static final class Account {
		public String name;
		public String uuid;
		/** Microsoft refresh token; null for offline names. */
		public String refresh;
		public boolean offline;
		/** When you last switched to it (ms), for sorting and "used 2 h ago". */
		public long lastUsed;
		transient boolean launcher;

		public Kind kind() {
			return launcher ? Kind.LAUNCHER : offline ? Kind.OFFLINE : Kind.MICROSOFT;
		}

		public UUID id() {
			try {
				return UUID.fromString(uuid);
			} catch (RuntimeException e) {
				return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
			}
		}

		public SkinTextures skin() {
			return AccountSkins.of(id());
		}

		/** Whether this is who the game is playing as now. */
		public boolean current() {
			UUID now = MinecraftClient.getInstance().getSession().getUuidOrNull();
			return now != null ? now.equals(id()) : MinecraftClient.getInstance().getSession().getUsername().equalsIgnoreCase(name);
		}
	}

	private static List<Account> saved;
	/** The account the launcher started the game with, and its token (good for this run only). */
	private static Account launcher;
	private static String launcherToken;

	private Accounts() {}

	// ---- The list ----

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("playwit").resolve("accounts.json");
	}

	/** The saved accounts (Microsoft and offline), in the order they're kept. */
	public static synchronized List<Account> all() {
		if (saved == null) {
			saved = new ArrayList<>();
			try {
				if (Files.exists(file())) {
					List<Account> read = GSON.fromJson(Files.readString(file()), new TypeToken<List<Account>>() {}.getType());
					if (read != null) read.stream().filter(a -> a != null && a.name != null && a.uuid != null).forEach(saved::add);
				}
			} catch (IOException | RuntimeException e) {
				NaughtyOrNiceClient.LOGGER.error("Could not read the saved accounts", e);
			}
		}
		return saved;
	}

	/** What the account screen lists: the launcher's account (unless it's saved too), then the saved ones, last used first. */
	public static synchronized List<Account> listed() {
		List<Account> list = new ArrayList<>(all());
		list.sort(Comparator.comparingLong((Account a) -> a.lastUsed).reversed());
		if (launcher != null && list.stream().noneMatch(a -> a.id().equals(launcher.id()))) list.add(0, launcher);
		return list;
	}

	/** Who you're playing as now, as an account (saved, the launcher's, or one made up from the session). */
	public static Account currentAccount() {
		for (Account a : listed()) if (a.current()) return a;
		Session session = MinecraftClient.getInstance().getSession();
		Account a = new Account();
		a.name = session.getUsername();
		a.uuid = String.valueOf(session.getUuidOrNull());
		a.offline = session.getUuidOrNull() == null || AccountSkins.offlineId(session.getUuidOrNull());
		a.launcher = !a.offline;
		return a;
	}

	static synchronized void save() {
		try {
			Path f = file();
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling("accounts.json.tmp");
			Files.writeString(tmp, GSON.toJson(all()));
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			ownerOnly(f);
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not save the accounts", e);
		}
	}

	/** The file holds sign-in tokens: readable and writable by you only, where the file system allows it. */
	private static void ownerOnly(Path f) {
		try {
			f.toFile().setReadable(false, false);
			f.toFile().setReadable(true, true);
			f.toFile().setWritable(false, false);
			f.toFile().setWritable(true, true);
		} catch (SecurityException ignored) {
			// Not allowed to change it here: it stays as the folder makes it.
		}
	}

	private static synchronized void remember(Account account) {
		all().removeIf(a -> a.id().equals(account.id()) || a.name.equalsIgnoreCase(account.name) && a.offline == account.offline);
		all().add(0, account);
		save();
	}

	public static synchronized void forget(Account account) {
		all().remove(account);
		save();
	}

	/** Adds (or updates) a Microsoft account from a finished sign-in. */
	static Account rememberLogin(MicrosoftLogin.Login login) {
		Account a = new Account();
		a.name = login.name();
		a.uuid = login.uuid();
		a.refresh = login.refreshToken();
		remember(a);
		return a;
	}

	/** An offline name: single player and offline-mode servers only. */
	public static Account offline(String name) {
		Account a = new Account();
		a.name = name;
		a.offline = true;
		a.uuid = offlineUuid(name).toString();
		remember(a);
		return a;
	}

	public static UUID offlineUuid(String name) {
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
	}

	// ---- Switching ----

	/** Where you are decides what switching does. */
	public enum Where { MENUS, SERVER, SINGLE_PLAYER }

	public static Where where() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) return Where.MENUS;
		return client.isInSingleplayer() ? Where.SINGLE_PLAYER : Where.SERVER;
	}

	/**
	 * Switches to an account. Microsoft accounts sign in first (off the render thread), so a failed sign-in never
	 * leaves you anywhere. Then: from the menus, it just switches; on a server, it leaves, switches and joins the same
	 * server again; in single player, it saves and quits to the title screen, then switches. {@code progress} and
	 * {@code done} (null when it worked, else the reason) are called on the render thread.
	 */
	public static void switchTo(Account account, Consumer<String> progress, Consumer<String> done) {
		MinecraftClient client = MinecraftClient.getInstance();
		CompletableFuture.runAsync(() -> {
			String token;
			try {
				token = token(account, s -> client.execute(() -> progress.accept(s)));
			} catch (IOException | InterruptedException | RuntimeException e) {
				NaughtyOrNiceClient.LOGGER.warn("Could not sign in as {}: {}", account.name, e.getMessage());
				client.execute(() -> done.accept(e.getMessage() == null ? e.toString() : e.getMessage()));
				return;
			}
			client.execute(() -> {
				try {
					switch (where()) {
						case MENUS -> apply(client, account, token);
						case SERVER -> rejoinAs(client, account, token);
						case SINGLE_PLAYER -> {
							client.disconnectWithSavingScreen();
							apply(client, account, token);
							client.setScreen(new TitleScreen());
						}
					}
					done.accept(null);
				} catch (RuntimeException e) {
					NaughtyOrNiceClient.LOGGER.error("Could not switch account", e);
					done.accept("Couldn't switch: " + e.getMessage());
				}
			});
		});
	}

	/** The token the game joins servers with: signed in again for Microsoft accounts, kept for the launcher's. */
	private static String token(Account account, Consumer<String> progress) throws IOException, InterruptedException {
		switch (account.kind()) {
			case OFFLINE -> {
				return "0";
			}
			case LAUNCHER -> {
				return launcherToken;
			}
			default -> {
				progress.accept("Signing in as " + account.name + "…");
				MicrosoftLogin.Login login = MicrosoftLogin.refresh(account.refresh, progress);
				// Names change; the id doesn't.
				account.name = login.name();
				account.uuid = login.uuid();
				account.refresh = login.refreshToken();
				save();
				return login.minecraftToken();
			}
		}
	}

	/** Leaves the server, switches, and joins it again as the new account. */
	private static void rejoinAs(MinecraftClient client, Account account, String token) {
		ServerInfo server = client.getCurrentServerEntry();
		client.disconnectWithProgressScreen();
		apply(client, account, token);
		Screen back = new MultiplayerScreen(new TitleScreen());
		if (server == null) {
			client.setScreen(back);
			return;
		}
		ConnectScreen.connect(back, client, ServerAddress.parse(server.address), server, false, null);
	}

	/** For the self-test: switch to an offline account right away. */
	static void applyNow(Account account) {
		apply(MinecraftClient.getInstance(), account, "0");
	}

	/** Puts the account in place: who you are, and everything the game made from it (chat signing, blocks, skin). */
	private static void apply(MinecraftClient client, Account account, String token) {
		UUID uuid = account.id();
		Session session = new Session(account.name, uuid, token, Optional.empty(), Optional.empty());
		UserApiService services = account.offline ? UserApiService.OFFLINE : userServices(client, token);
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
		}, Util.getIoWorkerExecutor()));
		game.naughtyornice$setGameProfileFuture(CompletableFuture.supplyAsync(
				() -> client.getApiServices().sessionService().fetchProfile(uuid, true), Util.getIoWorkerExecutor()));
		if (!account.launcher) {
			account.lastUsed = System.currentTimeMillis();
			if (all().contains(account)) save();
		}
		NaughtyOrNiceClient.LOGGER.info("Switched account to {}", account.name);
	}

	private static UserApiService userServices(MinecraftClient client, String token) {
		try {
			return new YggdrasilAuthenticationService(client.getNetworkProxy()).createUserApiService(token);
		} catch (RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.warn("Couldn't reach Mojang's user services; chat signing may be off", e);
			return UserApiService.OFFLINE;
		}
	}

	// ---- On the menus ----

	/** Remembers the launcher's account (so you can switch back to it), and puts your profile chip on the menus. */
	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			rememberLauncher(client);
			if (screen instanceof TitleScreen || screen instanceof MultiplayerScreen || screen instanceof GameMenuScreen) {
				Screens.getButtons(screen).add(new AccountChip(screen, width));
			}
		});
	}

	private static synchronized void rememberLauncher(MinecraftClient client) {
		if (launcher != null) return;
		Session session = client.getSession();
		UUID id = session.getUuidOrNull();
		String token = session.getAccessToken();
		// Only a real sign-in: an offline or development session has no token worth keeping.
		if (id == null || AccountSkins.offlineId(id) || token == null || token.length() < 20) return;
		launcher = new Account();
		launcher.name = session.getUsername();
		launcher.uuid = id.toString();
		launcher.launcher = true;
		launcherToken = token;
	}
}
