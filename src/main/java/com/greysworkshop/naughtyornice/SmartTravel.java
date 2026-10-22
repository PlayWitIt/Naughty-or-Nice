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

import java.util.ArrayDeque;
import java.util.HashSet;
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
	/** Recent (time, distance left), for the speed you're making. */
	private static final ArrayDeque<double[]> progress = new ArrayDeque<>();
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
		boolean ok = fly ? BaritoneBridge.run("goal " + plan.x() + " " + plan.z()) && BaritoneBridge.run("elytra")
				: BaritoneBridge.run("goto " + plan.x() + " " + (plan.y() == null ? "" : plan.y() + " ") + plan.z());
		if (!ok) return "Baritone didn't take it (see its message in chat)";
		HighwayRoam.stop(null);
		trip = new Trip(d.name(), plan.x(), plan.z(), fly, System.currentTimeMillis());
		progress.clear();
		warned.clear();
		lowHealthWarned = false;
		String how = fly ? "flying" : "walking";
		return "Going to " + d.name() + ", " + how + String.format(Locale.ROOT, ", %,d blocks", (int) distance) + (plan.note() == null ? "" : " (" + plan.note() + ")");
	}

	public static void stop() {
		if (trip == null) return;
		trip = null;
		BaritoneBridge.run("stop");
	}

	/** Once a second: progress, arrival, strangers, health. */
	public static void tick(MinecraftClient client) {
		if (trip == null || client.player == null || client.world == null) return;
		long now = System.currentTimeMillis();
		double left = Math.hypot(trip.x() - client.player.getX(), trip.z() - client.player.getZ());
		progress.addLast(new double[] {now, left});
		while (progress.size() > 20) progress.removeFirst();
		if (left < 8) {
			Alerts.chat(Text.literal("Arrived at " + trip.name() + ".").formatted(Formatting.GREEN));
			trip = null;
			return;
		}
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
								.withClickEvent(new ClickEvent.RunCommand("/nn trip stop")))));
			}
		}
		int stopAt = NNConfig.INSTANCE.tripStopHealth;
		if (stopAt > 0 && client.player.getHealth() <= stopAt && !lowHealthWarned) {
			lowHealthWarned = true;
			Alerts.warn(Text.literal("Stopped: your health is down to " + (int) Math.ceil(client.player.getHealth() / 2) + " hearts.").formatted(Formatting.RED));
			stop();
		}
	}

	/** Blocks a second you're actually closing in at, over the last 20 seconds; 0 if not enough to say. */
	private static double speed() {
		if (progress.size() < 5) return 0;
		double[] first = progress.peekFirst(), last = progress.peekLast();
		double secs = (last[0] - first[0]) / 1000;
		return secs <= 0 ? 0 : Math.max(0, (first[1] - last[1]) / secs);
	}

	/** The line at the top of the screen while going somewhere. */
	public static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (trip == null || client.player == null || client.options.hudHidden) return;
		double left = Math.hypot(trip.x() - client.player.getX(), trip.z() - client.player.getZ());
		double speed = speed();
		String eta = speed < 0.5 ? "" : " · about " + ShopScreen.duration((int) (left / speed));
		String stopKey = NaughtyOrNiceClient.INSTANCE.stopKeyName();
		String line = String.format(Locale.ROOT, "▶ %s · %,d blocks%s · %s · %s to stop", trip.name(), (int) left, eta,
				trip.elytra() ? "flying" : BaritoneBridge.isPathing() ? "walking" : "paused or working out a path", stopKey);
		int w = client.textRenderer.getWidth(line);
		int x = context.getScaledWindowWidth() / 2 - w / 2;
		context.fill(x - 4, 3, x + w + 4, 15, 0xA0000000);
		context.drawTextWithShadow(client.textRenderer, line, x, 5, 0xFFFFE08A);
	}
}
