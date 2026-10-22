package com.greysworkshop.naughtyornice;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns what the server sends into {@link EventLog} entries. Who did something is only written when the server says
 * so (the attacker of a hit, the shooter of a projectile, whoever damaged a crystal); when it doesn't (who placed a
 * crystal), the players close by are listed instead, never guessed.
 */
public final class EventHooks {
	private EventHooks() {}

	private static String name(Entity e) {
		if (e == null) return "";
		if (e instanceof PlayerEntity p) return p.getGameProfile().name();
		return e.getName().getString();
	}

	private static EventLog.Entry at(EventLog.Entry entry, MinecraftClient client, Vec3d pos) {
		if (client.world != null) entry.dim = client.world.getRegistryKey().getValue().toString();
		if (pos != null) {
			entry.x = pos.x;
			entry.y = pos.y;
			entry.z = pos.z;
		}
		return entry;
	}

	/** Something took damage: who, from whom (and with what), the kind of damage, health after. */
	public static void hit(MinecraftClient client, Entity target, Entity cause, Entity direct, String damageType) {
		if (target == null || !(target instanceof LivingEntity) && !(target instanceof EndCrystalEntity)) return;
		// Only fights that involve a player, on either side.
		if (!(target instanceof PlayerEntity) && !(cause instanceof PlayerEntity)) return;
		if (target instanceof EndCrystalEntity) {
			EventLog.add(at(EventLog.entry(EventLog.Type.CRYSTAL_SET_OFF, name(cause), "end crystal", cause == null ? "by " + damageType : "hit it"), client, target.getEntityPos()));
			return;
		}
		StringBuilder detail = new StringBuilder();
		if (cause instanceof LivingEntity living && !living.getMainHandStack().isEmpty()) detail.append("with ").append(living.getMainHandStack().getName().getString());
		if (direct != null && direct != cause) {
			if (!detail.isEmpty()) detail.append(" · ");
			detail.append("by ").append(direct.getType().getName().getString().toLowerCase(Locale.ROOT));
		}
		if (!detail.isEmpty()) detail.append(" · ");
		detail.append(damageType.replace('_', ' '));
		EventLog.Entry e = at(EventLog.entry(EventLog.Type.HIT, name(cause), name(target), detail.toString()), client, target.getEntityPos());
		if (target instanceof LivingEntity living) e.health = living.getHealth();
		EventLog.add(e);
	}

	/** A player swung their arm (attacking, using or breaking something). */
	public static void swing(MinecraftClient client, Entity entity, int animation) {
		if (!(entity instanceof PlayerEntity p) || (animation != 0 && animation != 3)) return;
		// What they're aiming at, if it's a player close enough to hit.
		String at = "";
		Vec3d eye = p.getEyePos(), look = p.getRotationVec(1);
		double best = 4.5;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == p) continue;
			Vec3d to = other.getBoundingBox().getCenter().subtract(eye);
			double d = to.length();
			if (d < best && to.normalize().dotProduct(look) > 0.85) {
				best = d;
				at = name(other);
			}
		}
		EventLog.add(at(EventLog.entry(EventLog.Type.SWING, name(p), at, animation == 3 ? "off hand" : "main hand"), client, p.getEntityPos()));
	}

	/** Something appeared: projectiles (and who fired them), end crystals, primed TNT. */
	public static void spawned(MinecraftClient client, Entity entity) {
		if (entity instanceof ProjectileEntity projectile) {
			Entity owner = projectile.getOwner();
			if (!(owner instanceof PlayerEntity)) return;
			EventLog.add(at(EventLog.entry(EventLog.Type.SHOT, name(owner), entity.getType().getName().getString(), ""), client, entity.getEntityPos()));
		} else if (entity instanceof EndCrystalEntity) {
			EventLog.add(at(EventLog.entry(EventLog.Type.CRYSTAL_PLACED, "", "end crystal", "players near: " + near(client, entity.getEntityPos(), 6)), client, entity.getEntityPos()));
		} else if (entity.getType() == net.minecraft.entity.EntityType.TNT) {
			EventLog.add(at(EventLog.entry(EventLog.Type.CRYSTAL_PLACED, "", "primed TNT", "players near: " + near(client, entity.getEntityPos(), 8)), client, entity.getEntityPos()));
		}
	}

	/** "Alex (2 blocks), Steve (5)", nearest first; "nobody" if none. */
	static String near(MinecraftClient client, Vec3d pos, double within) {
		List<PlayerEntity> list = new ArrayList<>();
		for (PlayerEntity p : client.world.getPlayers()) {
			if (p.getEntityPos().distanceTo(pos) <= within) list.add(p);
		}
		list.sort(java.util.Comparator.comparingDouble(p -> p.getEntityPos().distanceTo(pos)));
		List<String> parts = new ArrayList<>();
		for (PlayerEntity p : list) parts.add(name(p) + " (" + (int) Math.round(p.getEntityPos().distanceTo(pos)) + ")");
		return parts.isEmpty() ? "nobody" : String.join(", ", parts);
	}

	public static void explosion(MinecraftClient client, Vec3d center, float radius) {
		EventLog.add(at(EventLog.entry(EventLog.Type.EXPLOSION, "", "", String.format(Locale.ROOT, "power %.1f · players near: %s", radius, near(client, center, 12))),
				client, center));
	}

	/** A player's armour or held items changed. */
	public static void gear(MinecraftClient client, Entity entity, List<Pair<EquipmentSlot, ItemStack>> changes) {
		if (!(entity instanceof PlayerEntity p) || p == client.player) return;
		List<String> parts = new ArrayList<>();
		for (Pair<EquipmentSlot, ItemStack> c : changes) {
			String slot = c.getFirst().getName().replace('_', ' ');
			parts.add(slot + ": " + (c.getSecond().isEmpty() ? "nothing" : c.getSecond().getName().getString()));
		}
		if (!parts.isEmpty()) EventLog.add(at(EventLog.entry(EventLog.Type.GEAR, name(p), "", String.join(", ", parts)), client, p.getEntityPos()));
	}

	/** Once a second: where each player in view is, how they're doing and what they hold. */
	public static void positions(MinecraftClient client) {
		if (client.world == null) return;
		for (PlayerEntity p : client.world.getPlayers()) {
			if (p == client.player) continue;
			EventLog.Entry e = at(EventLog.entry(EventLog.Type.POSITION, name(p), "",
					p.getMainHandStack().isEmpty() ? "" : "holding " + p.getMainHandStack().getName().getString()), client, p.getEntityPos());
			e.health = p.getHealth();
			EventLog.add(e);
		}
	}
}
