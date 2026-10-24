package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Going somewhere with Baritone, the smart way: you pick where, it works out how. A place in the other dimension is
 * turned into the matching spot in yours (8 to 1); it flies by elytra only when that will work (the nether, far
 * enough, an elytra on and fireworks with you), and walks otherwise. While going, a line at the top of the screen
 * says where to, how far, about how long (from the speed you're actually making) and how; and it stops to ask when
 * someone who isn't a friend shows up, or stops if your health gets low.
 */
public final class SmartTravel {
	/** A trip under way. */
	private record Trip(String name, int x, int z, boolean elytra, long started) {}

	private static Trip trip;
	/** A trip by the nether highways: its legs, which one it's on, the highway's height, and the step being walked to. */
	private static List<HighwayRoute.Leg> legs = List.of();
	private static int leg;
	private static int highwayY, stepX, stepZ;
	/** How far ahead along the highway each step is: short enough that the straight way is along it, not through the walls. */
	static final int STEP = 120;
	private static final Set<UUID> warned = new HashSet<>();
	private static boolean lowHealthWarned;

	private SmartTravel() {}

	public static boolean active() {
		return trip != null;
	}

	/** Where to go, in words, if it's somewhere else in your dimension: x, z (and y), and a note when it was converted. */
	public record Plan(int x, Integer y, int z, String note) {}

	/** Works out where in your dimension a place is. Null if it can't be reached from here (the end and back). */
	public static Plan plan(MinecraftClient client, Destinations.Destination d) {
		String here = client.world.getRegistryKey().getValue().toString();
		Integer y = d.hasY() ? d.y() : null;
		if (d.dimension().isEmpty() || d.dimension().equals(here)) return new Plan(d.x(), y, d.z(), null);
		boolean toNether = here.endsWith("the_nether") && d.dimension().endsWith("overworld");
		boolean toOverworld = here.endsWith("overworld") && d.dimension().endsWith("the_nether");
		if (toNether) {
			int x = Math.floorDiv(d.x(), 8), z = Math.floorDiv(d.z(), 8);
			return new Plan(x, null, z, "it's in the overworld, so to the matching spot here: " + x + ", " + z);
		}
		if (toOverworld) {
			int x = d.x() * 8, z = d.z() * 8;
			return new Plan(x, null, z, "it's in the nether, so to the matching spot here: " + x + ", " + z);
		}
		return null;
	}

	/** Whether flying is sensible: the nether, at least 1,000 blocks, an elytra on, fireworks in the inventory. */
	static boolean canFly(MinecraftClient client, double distance) {
		PlayerEntity p = client.player;
		if (!client.world.getRegistryKey().getValue().toString().endsWith("the_nether") || distance < 1000) return false;
		if (!p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) return false;
		for (int i = 0; i < p.getInventory().size(); i++) {
			if (p.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) return true;
		}
		return false;
	}

	/** Sets off. Returns what it's doing (for the screen), or why it can't. */
	public static String go(MinecraftClient client, Destinations.Destination d) {
		if (!BaritoneBridge.present()) return "Baritone isn't installed";
		if (client.player == null || client.world == null) return "Join a world first";
		Plan plan = plan(client, d);
		if (plan == null) return "That's in another dimension that can't be reached from here (the end)";
		double distance = Math.hypot(plan.x() - client.player.getX(), plan.z() - client.player.getZ());
		boolean fly = canFly(client, distance);
		if (fly) BaritoneBridge.setBool("elytraTermsAccepted", true);
		// Walking in the nether: by the highways, when that's quicker than straight there.
		boolean nether = client.world.getRegistryKey().getValue().toString().endsWith("the_nether");
		List<HighwayRoute.Leg> route = !fly && (nether || testRouteEverywhere) && NNConfig.INSTANCE.tripUseHighways
				? HighwayRoute.plan(client.player.getX(), client.player.getZ(), plan.x(), plan.z()) : List.of();
		// A roam under way ends first, so its stop doesn't cancel this trip's first goal.
		HighwayRoam.stop(null);
		finalY = plan.y();
		legs = route;
		leg = 0;
		if (route.isEmpty()) BaritoneBridge.showGoal(true);
		boolean ok = fly ? BaritoneBridge.run("goal " + plan.x() + " " + plan.z()) && BaritoneBridge.run("elytra")
				: !route.isEmpty() ? startLeg(client)
				: BaritoneBridge.run("goto " + plan.x() + " " + (plan.y() == null ? "" : plan.y() + " ") + plan.z());
		if (!ok) {
			legs = List.of();
			return "Baritone didn't take it (see its message in chat)";
		}
		trip = new Trip(d.name(), plan.x(), plan.z(), fly, System.currentTimeMillis());
		warned.clear();
		lowHealthWarned = false;
		String how = fly ? "flying" : !route.isEmpty() ? HighwayRoute.describe(route, client.player.getX(), client.player.getZ()) : "walking";
		return "Going to " + d.name() + ", " + how + String.format(Locale.ROOT, ", %,d blocks", (int) distance) + (plan.note() == null ? "" : " (" + plan.note() + ")");
	}

	/** The place's height, if known, for the last leg. */
	private static Integer finalY;

	/** Sets Baritone off on the current leg. */
	private static boolean startLeg(MinecraftClient client) {
		HighwayRoute.Leg l = legs.get(leg);
		if (l.onHighway()) {
			highwayY = client.player.getBlockY();
			return step(client, l);
		}
		boolean last = leg == legs.size() - 1;
		// Baritone's goal box marks only where the trip ends, not where it gets on a highway.
		BaritoneBridge.showGoal(last);
		return BaritoneBridge.run("goto " + l.x() + " " + (last && finalY != null ? finalY + " " : "") + l.z());
	}

	/** The next step along the highway: the middle of it, up to {@link #STEP} blocks on, at the height you're riding it. */
	private static boolean step(MinecraftClient client, HighwayRoute.Leg l) {
		double now = HighwayRoute.along(l.axis(), client.player.getX(), client.player.getZ());
		double next = now + Math.signum(l.to() - now) * Math.min(STEP, Math.abs(l.to() - now));
		int[] p = HighwayRoute.point(l.axis(), next);
		stepX = p[0];
		stepZ = p[1];
		BaritoneBridge.showGoal(false);
		return BaritoneBridge.run("goto " + stepX + " " + highwayY + " " + stepZ);
	}

	/** For a trip by highway, once a second: the next step, or the next leg when this one's done. */
	private static void legs(MinecraftClient client) {
		if (legs.isEmpty() || leg >= legs.size()) return;
		HighwayRoute.Leg l = legs.get(leg);
		double px = client.player.getX(), pz = client.player.getZ();
		boolean done;
		if (l.onHighway()) {
			done = Math.abs(l.to() - HighwayRoute.along(l.axis(), px, pz)) < 16;
			if (!done && Math.hypot(stepX - px, stepZ - pz) < 24) step(client, l);
		} else {
			done = leg < legs.size() - 1 && Math.hypot(l.x() - px, l.z() - pz) < HighwayRoute.ON;
		}
		if (done && leg < legs.size() - 1) {
			leg++;
			startLeg(client);
		}
	}

	/** For the self-test, whose flat world has no nether: route by highway in the overworld too. */
	static boolean testRouteEverywhere;

	/** For the self-test: the legs of the trip under way. */
	static List<HighwayRoute.Leg> testLegs() {
		return legs;
	}

	public static void stop() {
		boolean had = trip != null || !legs.isEmpty();
		legs = List.of();
		if (had) BaritoneBridge.showGoal(true);
		if (trip == null) return;
		trip = null;
		BaritoneBridge.run("stop");
	}

	/** Once a second: progress, arrival, strangers, health. */
	public static void tick(MinecraftClient client) {
		if (trip == null || client.player == null || client.world == null) return;
		long now = System.currentTimeMillis();
		double left = Math.hypot(trip.x() - client.player.getX(), trip.z() - client.player.getZ());
		if (left < 8) {
			Alerts.chat(Text.literal("Arrived at " + trip.name() + ".").formatted(Formatting.GREEN));
			Alerts.play(Alerts.Sound.ARRIVED);
			trip = null;
			legs = List.of();
			BaritoneBridge.showGoal(true);
			return;
		}
		legs(client);
		if (NNConfig.INSTANCE.tripStopForStrangers) {
			for (PlayerEntity p : client.world.getPlayers()) {
				if (p == client.player || !warned.add(p.getUuid())) continue;
				Profile profile = NaughtyOrNiceClient.INSTANCE.ledger.get(p.getUuid());
				if (profile != null && (profile.verdict == Profile.Verdict.FRIEND || profile.verdict == Profile.Verdict.NICE)) continue;
				BaritoneBridge.run("pause");
				Alerts.warn(Text.literal("Paused: " + p.getGameProfile().name() + " is " + (int) p.distanceTo(client.player) + " blocks away. ").formatted(Formatting.RED)
						.append(Text.literal("[Keep going]").styled(st -> st.withColor(Formatting.GREEN).withUnderline(true)
								.withClickEvent(new ClickEvent.RunCommand("/nn roam resume"))))
						.append(Text.literal(" "))
						.append(Text.literal("[Stop]").styled(st -> st.withColor(Formatting.RED).withUnderline(true)
								.withClickEvent(new ClickEvent.RunCommand("/nn trip stop")))), Alerts.Sound.STRANGER);
			}
		}
		int stopAt = NNConfig.INSTANCE.tripStopHealth;
		if (stopAt > 0 && client.player.getHealth() <= stopAt && !lowHealthWarned) {
			lowHealthWarned = true;
			Alerts.warn(Text.literal("Stopped: your health is down to " + (int) Math.ceil(client.player.getHealth() / 2) + " hearts.").formatted(Formatting.RED), Alerts.Sound.LOW_HEALTH);
			stop();
		}
	}

	/** The trip under way, for the trip bar: its name, where it ends, and whether it's by elytra; null when there's none. */
	static String tripName() {
		return trip == null ? null : trip.name();
	}

	static int[] tripTarget() {
		return trip == null ? null : new int[] {trip.x(), trip.z()};
	}

	static boolean flying() {
		return trip != null && trip.elytra();
	}

	/** Blocks still to go: along the highways when the trip is by them, straight there otherwise. */
	static double remaining(double px, double pz) {
		if (trip == null) return 0;
		if (!legs.isEmpty() && leg < legs.size()) return HighwayRoute.remaining(legs, leg, px, pz);
		return Math.hypot(trip.x() - px, trip.z() - pz);
	}
}
