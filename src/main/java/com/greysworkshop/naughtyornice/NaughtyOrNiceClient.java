package com.greysworkshop.naughtyornice;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
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
	private Screen pendingScreen;
	private int saveTimer;
	private int tipTimer;

	@Override
	public void onInitializeClient() {
		INSTANCE = this;
		NNConfig.load();

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.naughtyornice.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, category));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> Commands.register(dispatcher));

		// These run while the game handles a packet: an error escaping them would disconnect you, so none can.
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) ->
				Safe.run("reading chat", () -> watcher.onChat(MinecraftClient.getInstance(), sender, signed, params, message)));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) Safe.run("reading a server message", () -> watcher.onGameMessage(MinecraftClient.getInstance(), message));
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> Safe.run("ticking", () -> tick(client)));

		// Disconnect fires on the network thread; do the work on the game thread.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			ledger.switchTo(null);
			watcher.reset();
		}));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			chatLogger.closeAll();
			ledger.save(true);
		});

		BaseInsuranceBridge.registerMapPins(this::mapPins);

		// Scripted development test (./gradlew runSelfTest); the class isn't shipped in the release jar.
		if (Boolean.getBoolean("naughtyornice.selftest")) {
			try {
				Class.forName("com.greysworkshop.naughtyornice.SelfTest").getMethod("start").invoke(null);
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
		String key = dimension + "/" + ledger.changes() + "/" + ledger.key();
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
		pins = list;
		pinsKey = key;
		return list;
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
		if (pendingScreen != null) {
			client.setScreen(pendingScreen);
			pendingScreen = null;
		}
		if (client.world == null || client.player == null) return;
		ledger.switchTo(Ledger.worldKey(client));

		while (openKey.wasPressed()) {
			if (client.currentScreen == null) client.setScreen(openScreen(client));
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
}
