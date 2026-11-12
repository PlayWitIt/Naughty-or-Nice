package com.greysworkshop.naughtyornice;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Real skins for accounts, outside any world (where the player list would normally have them): each one is looked up
 * from Mojang once, in the background, and kept. Until it arrives, and for offline names, it's the default skin.
 */
final class AccountSkins {
	private static final long RETRY_AFTER_MS = 60_000;

	private record Lookup(CompletableFuture<Supplier<SkinTextures>> skin, long started) {
		boolean failed() {
			return skin.isDone() && skin.getNow(null) == null;
		}
	}

	private static final Map<UUID, Lookup> LOOKUPS = new ConcurrentHashMap<>();

	private AccountSkins() {}

	/** Offline names (and the name-based ids the game makes for them) have no skin of their own. */
	static boolean offlineId(UUID id) {
		return id.version() == 3;
	}

	static SkinTextures of(UUID id) {
		if (offlineId(id)) return DefaultSkinHelper.getSkinTextures(id);
		Lookup lookup = LOOKUPS.get(id);
		if (lookup == null || (lookup.failed() && System.currentTimeMillis() - lookup.started() > RETRY_AFTER_MS)) {
			lookup = new Lookup(fetch(id), System.currentTimeMillis());
			LOOKUPS.put(id, lookup);
		}
		Supplier<SkinTextures> skin = lookup.skin().getNow(null);
		return skin != null ? skin.get() : DefaultSkinHelper.getSkinTextures(id);
	}

	/** Whether the real skin is in (or there's none to wait for). */
	static boolean ready(UUID id) {
		Lookup lookup = LOOKUPS.get(id);
		return offlineId(id) || (lookup != null && lookup.skin().getNow(null) != null);
	}

	private static CompletableFuture<Supplier<SkinTextures>> fetch(UUID id) {
		MinecraftClient client = MinecraftClient.getInstance();
		return CompletableFuture.supplyAsync(() -> {
			// Signed, so the game trusts the textures: it only shows signed skins from a secure lookup.
			ProfileResult result = client.getApiServices().sessionService().fetchProfile(id, true);
			return result == null ? null : result.profile();
		}, Util.getIoWorkerExecutor()).thenApplyAsync((GameProfile profile) ->
				profile == null ? null : client.getSkinProvider().supplySkinTextures(profile, true), client
		).exceptionally(e -> {
			NaughtyOrNiceClient.LOGGER.warn("Couldn't look up the skin for {}: {}", id, e.toString());
			return null;
		});
	}
}
