package com.greysworkshop.naughtyornice.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.SocialInteractionsManager;
import net.minecraft.client.session.ProfileKeys;
import net.minecraft.client.session.Session;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

/**
 * Switching accounts in game: the game keeps who you are in final fields set once at start, so these swap them for the
 * account you pick (see {@link com.greysworkshop.naughtyornice.Accounts#use}).
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
	@Mutable
	@Accessor("session")
	void naughtyornice$setSession(Session session);

	@Mutable
	@Accessor("userApiService")
	void naughtyornice$setUserApiService(UserApiService service);

	@Mutable
	@Accessor("profileKeys")
	void naughtyornice$setProfileKeys(ProfileKeys keys);

	@Mutable
	@Accessor("socialInteractionsManager")
	void naughtyornice$setSocialInteractionsManager(SocialInteractionsManager manager);

	@Mutable
	@Accessor("userPropertiesFuture")
	void naughtyornice$setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> future);

	@Mutable
	@Accessor("gameProfileFuture")
	void naughtyornice$setGameProfileFuture(CompletableFuture<ProfileResult> future);
}
