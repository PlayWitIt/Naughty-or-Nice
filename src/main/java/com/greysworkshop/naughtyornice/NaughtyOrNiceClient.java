package com.greysworkshop.naughtyornice;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class NaughtyOrNiceClient implements ClientModInitializer {
	public static final String MOD_ID = "naughtyornice";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	/** Save the lists at most this often (ticks) while playing. */
	private static final int SAVE_INTERVAL = 600;

	public static NaughtyOrNiceClient INSTANCE;

	public final Ledger ledger = new Ledger();
	public final ChatLogger chatLogger = new ChatLogger(ledger);
	public final Diagnostics diagnostics = new Diagnostics();
	public final Watcher watcher = new Watcher(ledger, chatLogger, diagnostics);

	private KeyBinding openKey;
	private KeyBinding radarKey;
	private KeyBinding chunksKey;
	private KeyBinding baritoneKey;
	private KeyBinding stopKey;
	private int roamTimer;
	private int trailTimer;
	private int walkTimer;
	private Screen pendingScreen;
	private int saveTimer;
	private int tipTimer;

	@Override
	public void onInitializeClient() {
		INSTANCE = this;
		NNConfig.load();

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.naughtyornice.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, category));
		radarKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.naughtyornice.radar", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
		baritoneKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.naughtyornice.baritone", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, category));
		stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.naughtyornice.stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, category));
		chunksKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.naughtyornice.chunks", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> Safe.run("drawing chunks in the world", () -> ChunkOverlay.render(context)));
		HudElementRegistry.addLast(Identifier.of(MOD_ID, "trip"), (context, tickCounter) -> Safe.run("drawing the trip line", () -> SmartTravel.render(context, tickCounter)));
		HudElementRegistry.addLast(Identifier.of(MOD_ID, "logout_spots"), (context, tickCounter) -> Safe.run("drawing logout spots", () -> LogoutSpots.render(context, tickCounter)));
		HudElementRegistry.addLast(Identifier.of(MOD_ID, "coords"), (context, tickCounter) -> Safe.run("drawing your coordinates", () -> CoordsHud.render(context, tickCounter)));
		HudElementRegistry.addLast(Identifier.of(MOD_ID, "chunk_here"), (context, tickCounter) -> Safe.run("drawing the chunk line", () -> ChunkOverlay.renderHud(context, tickCounter)));
		HudElementRegistry.addLast(Identifier.of(MOD_ID, "trail_radar"), (context, tickCounter) -> Safe.run("drawing the trail radar", () -> TrailRadar.render(context, tickCounter)));

		DeathRecap.register();
		PhoneAlerts.register();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> Commands.register(dispatcher));

		// These run while the game handles a packet: an error escaping them would disconnect you, so none can.
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> {
			if (NNConfig.on()) Safe.run("reading chat", () -> watcher.onChat(MinecraftClient.getInstance(), sender, signed, params, message));
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay && NNConfig.on()) Safe.run("reading a server message", () -> watcher.onGameMessage(MinecraftClient.getInstance(), message));
		});

		// Your own messages: held back if they'd give your location away (see CoordGuard).
		ClientSendMessageEvents.ALLOW_CHAT.register(message -> !NNConfig.on() || Safe.get("checking your message", () -> CoordGuard.allowChat(message), true));
		ClientSendMessageEvents.ALLOW_COMMAND.register(command -> !NNConfig.on() || Safe.get("checking your message", () -> CoordGuard.allowCommand(command), true));

		ClientTickEvents.END_CLIENT_TICK.register(client -> Safe.run("ticking", () -> tick(client)));

		// Disconnect fires on the network thread; do the work on the game thread.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			HighwayRoam.stop(null);
			SmartTravel.stop();
			EventHooks.flush(true);
			ledger.switchTo(null);
			watcher.reset();
		}));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			chatLogger.closeAll();
			ledger.save(true);
		});

		BaseInsuranceBridge.registerMapPins(this::mapPins);
		ModsPanel.install(panelEntry());

		// Scripted development tests (./gradlew runSelfTest, runPanelTest); the classes aren't shipped in the release jar.
		if (Boolean.getBoolean("naughtyornice.selftest") || Boolean.getBoolean("naughtyornice.paneltest") || Boolean.getBoolean("naughtyornice.paletteprobe")) {
			try {
				Class.forName(Boolean.getBoolean("naughtyornice.paneltest") ? "com.greysworkshop.naughtyornice.SelfTestPanel"
						: Boolean.getBoolean("naughtyornice.paletteprobe") ? "com.greysworkshop.naughtyornice.SelfTestPalette"
						: "com.greysworkshop.naughtyornice.SelfTest").getMethod("start").invoke(null);
			} catch (ReflectiveOperationException e) {
				LOGGER.error("Self-test not available", e);
			}
		}
	}

	private String pinsKey;
	private List<Map<String, Object>> pins = List.of();

	/**
	 * Pins for Base Insurance's map: where players on your lists (or in a group) were last seen in a dimension, in
	 * the last 30 days. Worked out again only when the lists change.
	 */
	private List<Map<String, Object>> mapPins(String dimension) {
		String key = dimension + "/" + ledger.changes() + "/" + ledger.activity.version() + "/" + ledger.chunkTrails.version() + "/" + ledger.key();
		if (key.equals(pinsKey)) return pins;
		List<Map<String, Object>> list = new ArrayList<>();
		long oldest = System.currentTimeMillis() - 30L * 86_400_000L;
		for (Profile p : ledger.all()) {
			if (p.verdict == Profile.Verdict.NONE && p.groups.isEmpty()) continue;
			Profile.Sighting last = null;
			for (int i = p.sightings.size() - 1; i >= 0; i--) {
				if (p.sightings.get(i).dimension.equals(dimension)) {
					last = p.sightings.get(i);
					break;
				}
			}
			if (last == null || last.until < oldest) continue;
			int color = switch (p.verdict) {
				case NAUGHTY -> 0xFFFF5A4A;
				case LOOK -> 0xFFFFC14D;
				case NICE -> 0xFF4CD39B;
				case FRIEND -> 0xFF000000 | Marks.GROUP_TEXT[Friends.color(p) % Marks.GROUP_TEXT.length];
				case NONE -> 0xFF000000 | Marks.GROUP_TEXT[ledger.groupColor(p.groups.get(0))];
			};
			Map<String, Object> pin = new HashMap<>();
			pin.put("x", last.lastX);
			pin.put("z", last.lastZ);
			pin.put("color", color);
			pin.put("label", (p.verdict == Profile.Verdict.FRIEND ? "★ " : "") + p.name + (p.groups.isEmpty() ? "" : " [" + p.groups.get(0) + "]"));
			pin.put("detail", "Seen " + ShopScreen.ago(last.until) + (last.gear.isEmpty() ? "" : " · " + last.gear));
			list.add(pin);
		}
		// Places players gave away in this dimension, and world events here (last 30 days, newest 150).
		List<Object[]> placed = new ArrayList<>();
		for (Profile p : ledger.all()) {
			for (PlayerStats.Place pl : p.stats.places) {
				if (pl.dimension.equals(dimension) && pl.time >= oldest) placed.add(new Object[] {pl.time, p.name + ": " + ShopScreen.howLabel(pl.how).toLowerCase(java.util.Locale.ROOT), pl.x, pl.z, pl.text});
			}
		}
		for (ServerActivity.WorldEvent e : ledger.activity.events) {
			if (!e.dimension.equals(dimension) || e.time < oldest || !e.who.isEmpty()) continue;
			String what = switch (e.kind) {
				case "wither" -> "A wither spawned";
				case "end_portal" -> "End portal lit";
				default -> "The dragon died";
			};
			placed.add(new Object[] {e.time, what, e.x, e.z, "Heard server-wide"});
		}
		for (ChunkTrails.Trail t : ledger.chunkTrails.trails) {
			if (!t.dimension.equals(dimension) || t.lastSeen < oldest) continue;
			int[] end = t.awayEnd();
			placed.add(new Object[] {t.lastSeen, t.away == 0 ? "Chunk trail end" : "Chunk trail: far end", end[0], end[1],
					String.format(java.util.Locale.ROOT, "%,d blocks long, from %d, %d", t.length(), t.away == 1 ? t.x2 : t.x1, t.away == 1 ? t.z2 : t.z1)});
		}
		placed.sort((a1, b1) -> Long.compare((long) b1[0], (long) a1[0]));
		for (Object[] o : placed.subList(0, Math.min(150, placed.size()))) {
			Map<String, Object> pin = new HashMap<>();
			pin.put("x", o[2]);
			pin.put("z", o[3]);
			pin.put("color", 0xFFFFA41C);
			pin.put("label", o[1]);
			pin.put("detail", ShopScreen.ago((long) o[0]) + " · " + o[4]);
			list.add(pin);
		}
		pins = list;
		pinsKey = key;
		return list;
	}

	/** Every two seconds: trails around you, and a chat note for each new one. */
	private void lookForTrails(MinecraftClient client) {
		if (!NNConfig.INSTANCE.trailRadar && !NNConfig.INSTANCE.seenRadarTip) {
			// Finding trails happens out of sight: say once where to see it.
			NNConfig.INSTANCE.seenRadarTip = true;
			NNConfig.save();
			Alerts.chat(Text.literal("Chunk trails are on. Press ").formatted(Formatting.GRAY)
					.append(Text.keybind("key.naughtyornice.radar").formatted(Formatting.YELLOW))
					.append(Text.literal(" for the radar (new land red, old land green, trails yellow), or ").formatted(Formatting.GRAY))
					.append(Text.literal("[show it]").styled(st -> st.withColor(Formatting.AQUA).withUnderline(true)
							.withClickEvent(new ClickEvent.RunCommand("/nn radar")))));
		}
		String dim = client.world.getRegistryKey().getValue().toString();
		int cx = client.player.getBlockX() >> 4, cz = client.player.getBlockZ() >> 4;
		ChunkTrails trails = ledger.chunkTrails;
		List<int[]> patches = new ArrayList<>();
		trails.analyse(dim, cx, cz, System.currentTimeMillis(), patches);
		patches.sort(java.util.Comparator.comparingDouble(p -> Math.hypot(p[0] - client.player.getX(), p[1] - client.player.getZ())));
		TrailRadar.patches = patches;
		trails.trim(dim, cx, cz);
		for (ChunkTrails.Trail t : trails.takeNew()) {
			HighwayRoam.trailFound(client, t);
			if (!NNConfig.INSTANCE.trailAlerts) continue;
			String[] say = trails.guidance(dim, client.player.getX(), client.player.getZ(), client.player.getYaw());
			int[] end = t.awayEnd();
			Alerts.chat(Text.literal("Chunk trail found: ").formatted(Formatting.GOLD)
					.append(Text.literal(say == null ? "" : say[0] + ". " + say[1] + ". ").formatted(Formatting.YELLOW))
					.append(Text.literal(t.away == 0 || ModsPanel.streamSafe() ? "" : "The far end (away from spawn) is at " + end[0] + ", " + end[1] + ". ").formatted(Formatting.GRAY))
					.append(Text.literal(NNConfig.INSTANCE.trailRadar ? "" : "[Show radar]").styled(st -> st.withColor(Formatting.AQUA).withUnderline(true)
							.withClickEvent(new ClickEvent.RunCommand("/nn radar")))));
		}
	}

	/** The stop key as it's bound right now ("Z"), for telling people. */
	public String stopKeyName() {
		return stopKey == null ? "Z" : stopKey.getBoundKeyLocalizedText().getString();
	}

	/** The UUID offline-mode servers give a name; our best guess for someone added before we've seen them. */
	public static UUID offlineId(String name) {
		return Uuids.getOfflinePlayerUuid(name);
	}

	/** Opens a screen on the next tick, so it isn't immediately closed by the chat screen a command came from. */
	public void openLater(Screen screen) {
		pendingScreen = screen;
	}

	/** The player you're looking at (roughly), up to 96 blocks away, or null. */
	public static PlayerEntity lookedAt(MinecraftClient client) {
		if (client.player == null || client.world == null) return null;
		Vec3d eye = client.player.getEyePos();
		Vec3d look = client.player.getRotationVec(1.0f);
		PlayerEntity best = null;
		double bestAngle = Math.toRadians(6);
		for (PlayerEntity player : client.world.getPlayers()) {
			if (player == client.player) continue;
			Vec3d to = player.getBoundingBox().getCenter().subtract(eye);
			double distance = to.length();
			if (distance > 96 || distance < 0.01) continue;
			// Wider cone up close, so a player right in front of you is easy to pick.
			double allowed = Math.max(bestAngle, Math.atan2(1.2, distance));
			double angle = Math.acos(Math.max(-1, Math.min(1, to.normalize().dotProduct(look))));
			if (angle < allowed && (best == null || angle < bestAngle)) {
				best = player;
				bestAngle = angle;
			}
		}
		return best;
	}

	/** Opens the store, on the page of the player you're looking at if there is one. */
	public Screen openScreen(MinecraftClient client) {
		PlayerEntity target = lookedAt(client);
		if (target != null) {
			Profile profile = ledger.getOrCreate(target.getUuid(), target.getGameProfile().name());
			return new ShopScreen(null, profile);
		}
		return new ShopScreen();
	}

	private void tick(MinecraftClient client) {
		UpdateCheck.tick(client);
		PhoneAlerts.flush(false);
		if (pendingScreen != null) {
			client.setScreen(pendingScreen);
			pendingScreen = null;
		}
		if (client.world == null || client.player == null) return;
		ledger.switchTo(Ledger.worldKey(client));

		while (openKey.wasPressed()) {
			if (client.currentScreen == null) client.setScreen(openScreen(client));
		}
		while (stopKey.wasPressed()) {
			// One key stops everything: a trip, a highway roam, and whatever Baritone is doing.
			boolean was = SmartTravel.active() || HighwayRoam.active() || BaritoneBridge.isPathing();
			SmartTravel.stop();
			HighwayRoam.stop(null);
			BaritoneBridge.run("stop");
			if (BaritoneBridge.present()) client.inGameHud.setOverlayMessage(Text.literal(was ? "Stopped" : "Nothing to stop").formatted(Formatting.YELLOW), false);
		}
		while (baritoneKey.wasPressed()) {
			if (client.currentScreen == null) client.setScreen(new BaritoneScreen());
		}
		EventHooks.flush(false);
		if (++roamTimer >= 20) {
			roamTimer = 0;
			HighwayRoam.tick(client);
			SmartTravel.tick(client);
		}
		// Off (the Mods panel's first switch): the keys above still work, nothing else runs.
		if (!NNConfig.on()) return;
		while (radarKey.wasPressed()) {
			NNConfig.INSTANCE.trailRadar = !NNConfig.INSTANCE.trailRadar;
			if (NNConfig.INSTANCE.trailRadar) NNConfig.INSTANCE.chunkTrails = true;
			NNConfig.save();
			client.inGameHud.setOverlayMessage(Text.literal("Chunk trail radar " + (NNConfig.INSTANCE.trailRadar ? "on" : "off")), false);
		}
		while (chunksKey.wasPressed()) {
			NNConfig.INSTANCE.chunkOverlay = !NNConfig.INSTANCE.chunkOverlay;
			if (NNConfig.INSTANCE.chunkOverlay) NNConfig.INSTANCE.chunkTrails = true;
			NNConfig.save();
			client.inGameHud.setOverlayMessage(Text.literal("Chunks in the world " + (NNConfig.INSTANCE.chunkOverlay ? "on" : "off")), false);
		}
		// Every half second: land around you that's still unknown is remembered, so it's never read as old later.
		if (NNConfig.INSTANCE.chunkTrails && client.world != null && client.player != null && ++walkTimer >= 10) {
			walkTimer = 0;
			int r = client.world.getSimulationDistance() + 1;
			var chunks = client.world.getChunkManager();
			ledger.chunkTrails.walk(client.world.getRegistryKey().getValue().toString(), client.player.getBlockX() >> 4, client.player.getBlockZ() >> 4, r,
					k -> chunks.isChunkLoaded(net.minecraft.util.math.ChunkPos.getPackedX(k), net.minecraft.util.math.ChunkPos.getPackedZ(k)));
		}
		if (NNConfig.INSTANCE.chunkTrails && ++trailTimer >= 40) {
			trailTimer = 0;
			lookForTrails(client);
		}

		if (!NNConfig.INSTANCE.seenTip && ++tipTimer == 300) {
			NNConfig.INSTANCE.seenTip = true;
			NNConfig.save();
			Alerts.chat(Text.literal("Press ").formatted(Formatting.GRAY)
					.append(Text.keybind("key.naughtyornice.open").formatted(Formatting.YELLOW))
					.append(Text.literal(" to open your Nice and Naughty lists (look at a player first to open theirs), or ").formatted(Formatting.GRAY))
					.append(Text.literal("[click here]").styled(s -> s.withColor(Formatting.AQUA).withUnderline(true)
							.withClickEvent(new ClickEvent.RunCommand("/nn")))));
		}

		watcher.tick(client);
		if (++saveTimer >= SAVE_INTERVAL) {
			saveTimer = 0;
			ledger.save(false);
		}
	}

	/** This mod's part of the mods panel (Right Shift on the title screen): the switches worth flipping before joining. */
	private static java.util.Map<String, Object> panelEntry() {
		NNConfig c = NNConfig.INSTANCE;
		String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?").replaceAll("\\+.*", "");
		java.util.List<java.util.Map<String, Object>> switches = java.util.List.of(
				master(ModsPanel.toggle("Mod enabled", "Turns all of Naughty or Nice on or off. Your lists, notes and recordings are kept either way.",
						() -> c.enabled, v -> c.enabled = v)),
				ModsPanel.section("Watching"),
				ModsPanel.toggle("Remember everyone you see", "Players you see nearby or on the tab list stay in your lists after they log off.",
						() -> c.rememberEveryone, v -> c.rememberEveryone = v),
				ModsPanel.toggle("Read the chat", "Friendly words and trash talk count for or against players, and coordinates and topics go on the Intel tab.",
						() -> c.watchChat, v -> c.watchChat = v),
				ModsPanel.toggle("Event recorder", "Keeps the last 30 minutes of everything around you (hits, deaths, shots, crystals), so a fight can be saved afterwards.",
						() -> c.eventLog, v -> c.eventLog = v),
				ModsPanel.toggle("Note sightings", "Where and when players come into view, and what they're wearing.", () -> c.recordSightings, v -> c.recordSightings = v),
				ModsPanel.toggle("Evidence screenshots", "A screenshot when someone kills you or a naughty player comes into one of your bases.",
						() -> c.evidenceShots, v -> c.evidenceShots = v),
				ModsPanel.section("Your safety"),
				ModsPanel.toggle("Guard my coordinates", "Holds your own message back when it gives away coordinates, a base name or your own x or z.",
						() -> c.guardMyCoords, v -> c.guardMyCoords = v),
				ModsPanel.toggle("Ask before sending any message", "Every chat message or whisper waits for you to press [Send] first.",
						() -> c.askBeforeSending, v -> c.askBeforeSending = v),
				ModsPanel.toggle("Warn when a naughty player joins", "When someone on your Naughty list joins the server.", () -> c.warnNaughtyJoin, v -> c.warnNaughtyJoin = v),
				ModsPanel.toggle("Warn when a naughty player is near", "When someone on your Naughty list comes into view.", () -> c.warnNaughtyNearby, v -> c.warnNaughtyNearby = v),
				ModsPanel.toggle("Alert on threats against you", "A ping when someone threatens you or your base in chat.", () -> c.alertThreats, v -> c.alertThreats = v),
				ModsPanel.toggle("Warning sound", "Play a low note with warnings.", () -> c.warnSounds, v -> c.warnSounds = v),
				ModsPanel.section("On screen"),
				ModsPanel.toggle("My coordinates", "x y z, facing, and the nether/overworld match in the bottom left, so you don't need F3. "
						+ "Hidden in stream-safe mode.", () -> c.showCoords, v -> c.showCoords = v),
				ModsPanel.toggle("Marks on nametags", "Green ✔, gold ? or red ✖ before listed players' names above their heads.", () -> c.nametagMarks, v -> c.nametagMarks = v),
				ModsPanel.toggle("Marks in the tab list", "The same marks in the player list (Tab).", () -> c.tabListMarks, v -> c.tabListMarks = v),
				ModsPanel.toggle("Who's on when you join", "A summary of who's online from your lists when you join a server.", () -> c.joinSummary, v -> c.joinSummary = v),
				ModsPanel.toggle("Totems popped on names", "\"Steve [3 pops]\": totems a player has popped in front of you since they last died.",
						() -> c.totemCounter, v -> c.totemCounter = v),
				ModsPanel.toggle("Logout spots", "A marker where a player logged out while you could see them, until they come back.",
						() -> c.logoutSpots, v -> c.logoutSpots = v),
				ModsPanel.toggle("Death recap", "On the death screen: who hit you in your last 30 seconds, with what, and the totems you popped.",
						() -> c.deathRecap, v -> c.deathRecap = v),
				ModsPanel.section("Chunk trails"),
				ModsPanel.toggle("Find chunk trails", "Tells old land from fresh land by how its water and lava flow, and finds the paths players left.",
						() -> c.chunkTrails, v -> {
							c.chunkTrails = v;
							// The radar is what you see: on with it, so turning trails on is never "nothing happened".
							if (v) c.trailRadar = true;
						}),
				ModsPanel.toggle("Chunk trail radar", "The small turning map on the right (J shows or hides it in game).", () -> c.trailRadar, v -> {
					c.trailRadar = v;
					if (v) c.chunkTrails = true;
				}),
				ModsPanel.toggle("Note new chunk trails in chat", "Where a new trail is, which way it runs, and its far end.", () -> c.trailAlerts, v -> c.trailAlerts = v),
				ModsPanel.section("Other"),
				ModsPanel.toggle("Offer to open shared links", "When a friend sends you a Naughty or Nice share link in chat.", () -> c.openSharedLinks, v -> c.openSharedLinks = v),
				ModsPanel.toggle("Tell me about updates", "Once per game session, a note in chat when a newer version is out.", () -> c.checkUpdates, v -> c.checkUpdates = v));
		return java.util.Map.of("id", MOD_ID, "name", "Naughty or Nice", "version", version, "icon", Identifier.of(MOD_ID, "icon.png"),
				"settings", (java.util.function.Function<net.minecraft.client.gui.screen.Screen, net.minecraft.client.gui.screen.Screen>)
						parent -> new ShopScreen(ShopScreen.Tab.SETTINGS, null).withParent(parent),
				"save", (Runnable) NNConfig::save, "data", java.util.List.of("naughtyornice"), "switches", switches);
	}

	private static java.util.Map<String, Object> master(java.util.Map<String, Object> toggle) {
		java.util.Map<String, Object> m = new java.util.HashMap<>(toggle);
		m.put("master", true);
		return m;
	}
}
