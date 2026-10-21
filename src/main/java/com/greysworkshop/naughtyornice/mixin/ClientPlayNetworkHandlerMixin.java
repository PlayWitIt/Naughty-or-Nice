package com.greysworkshop.naughtyornice.mixin;

import com.greysworkshop.naughtyornice.NaughtyOrNiceClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the server's damage, death and block-breaking packets to the watcher. Injected at TAIL: the handlers
 * hop to the game thread first, so by then we're on it.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
	@Inject(method = "onEntityDamage", at = @At("TAIL"))
	private void naughtyornice$damage(EntityDamageS2CPacket packet, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		if (client.world == null || mod == null) return;
		Entity target = client.world.getEntityById(packet.entityId());
		Entity attacker = client.world.getEntityById(packet.sourceCauseId());
		mod.watcher.onDamage(client, target, attacker);
	}

	@Inject(method = "onEntityStatus", at = @At("TAIL"))
	private void naughtyornice$status(EntityStatusS2CPacket packet, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		if (client.world == null || mod == null) return;
		byte status = packet.getStatus();
		if (status != EntityStatuses.PLAY_DEATH_SOUND_OR_ADD_PROJECTILE_HIT_PARTICLES && status != EntityStatuses.ADD_DEATH_PARTICLES) return;
		Entity entity = packet.getEntity(client.world);
		if (entity instanceof LivingEntity) mod.watcher.onDeath(client, entity);
	}

	@Inject(method = "onDeathMessage", at = @At("TAIL"))
	private void naughtyornice$myDeath(DeathMessageS2CPacket packet, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		if (client.world == null || mod == null) return;
		mod.watcher.onDeath(client, client.world.getEntityById(packet.playerId()));
	}

	@Inject(method = "onBlockBreakingProgress", at = @At("TAIL"))
	private void naughtyornice$breaking(BlockBreakingProgressS2CPacket packet, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		if (client.world == null || mod == null) return;
		mod.watcher.onBlockBreaking(client, client.world.getEntityById(packet.getEntityId()), packet.getPos());
	}
}
