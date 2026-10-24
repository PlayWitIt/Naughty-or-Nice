package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Travels a highway with Baritone while chunk trails are read, and stops to ask when something turns up: a trail
 * near the highway, or someone who isn't a friend coming into view. Either a distance along the highway, or to
 * coordinates: along the highway you pick (or the best one, through spawn if that's quicker) to the point nearest
 * them, then off it the rest of the way if you want. It walks the highway a short step at a time, along its middle,
 * so Baritone never cuts through the walls. Damaged highways are got through with Baritone's own options: breaking
 * through what's in the way, placing blocks you carry to bridge gaps and missing floor, parkour jumps, and (only with
 * Fire Resistance) walking across lava; Baritone's own settings are put back when the roam ends. What you choose is
 * saved in the settings and stays until you change it.
 */
public final class HighwayRoam {
	/** A highway: which way along x and z (each -1, 0 or 1). */
	public record Axis(String name, int dx, int dz) {}

	public static final Axis[] AXES = {
			new Axis("+X", 1, 0), new Axis("-X", -1, 0), new Axis("+Z", 0, 1), new Axis("-Z", 0, -1),
			new Axis("+X+Z", 1, 1), new Axis("+X-Z", 1, -1), new Axis("-X+Z", -1, 1), new Axis("-X-Z", -1, -1)};

	/** The highway name saved for "whichever is quickest" (going to coordinates only). */
	public static final String BEST = "Best";

	/** How far ahead along the highway each step is: short enough that the straight way is along it, not through the walls. */
	static final int STEP = SmartTravel.STEP;

	private static boolean active, paused;
	private static List<HighwayRoute.Leg> legs = List.of();
	private static int leg, highwayY, stepX, stepZ, targetX, targetZ;
	/** Players already warned about this trip, and trails already stopped for. */
	private static final Set<UUID> warned = new HashSet<>();
	private static final Set<ChunkTrails.Trail> stoppedFor = new HashSet<>();
	private static int idleSeconds;
	/** Baritone's own settings the roam changes for its length (put back after, see {@link BaritoneBridge#override}). */
	private static final String[] CHANGED = {"allowBreak", "allowPlace", "allowParkour", "allowParkourPlace", "assumeWalkOnLava"};

	private HighwayRoam() {}

	public static boolean active() {
		return active;
	}

	/** The highway picked, or null for {@link #BEST}. */
	public static Axis axis() {
		String name = NNConfig.INSTANCE.roamAxis;
		for (Axis a : AXES) if (a.name().equals(name)) return a;
		return BEST.equals(name) ? null : AXES[0];
	}

	public static void pick(Axis a) {
		NNConfig.INSTANCE.roamAxis = a == null ? BEST : a.name();
		NNConfig.save();
	}

	public static String target() {
		return targetX + ", " + targetZ;
	}

	static int[] targetXZ() {
		return new int[] {targetX, targetZ};
	}

	static boolean flying() {
		return active && NNConfig.INSTANCE.roamElytra;
	}

	/** Blocks still to go along the roam's route. */
	static double remaining(double px, double pz) {
		if (!legs.isEmpty() && leg < legs.size() && !NNConfig.INSTANCE.roamElytra) return HighwayRoute.remaining(legs, leg, px, pz);
		return Math.hypot(targetX - px, targetZ - pz);
	}

	static boolean paused() {
		return paused;
	}

	/** The legs of the roam under way (for the screen and the self-test). */
	static List<HighwayRoute.Leg> legs() {
		return legs;
	}

	/** Where to go to in this dimension, from the coordinates typed: divided by 8 when they're overworld ones and you're in the nether. */
	static int[] destination(MinecraftClient client) {
		NNConfig c = NNConfig.INSTANCE;
		if (c.roamX == null || c.roamZ == null) return null;
		boolean nether = client.world.getRegistryKey().getValue().toString().endsWith("the_nether");
		if (c.roamOverworld && nether) return new int[] {Math.floorDiv(c.roamX, 8), Math.floorDiv(c.roamZ, 8)};
		return new int[] {c.roamX, c.roamZ};
	}

	/**
	 * The legs from (px, pz): a distance along {@code a}, or to (dx, dz) by {@code a} (or the best highway when it's
	 * null), getting on it first if you're off it, and ending on the highway or at the spot. Empty when there's nothing
	 * to do. Plain sums, so the self-test can check them anywhere.
	 */
	static List<HighwayRoute.Leg> plan(Axis a, double px, double pz, int[] dest, int distance, boolean goOn) {
		List<HighwayRoute.Leg> out = new ArrayList<>();
		if (dest == null) {
			if (a == null) return out;
			double t = HighwayRoute.along(a, px, pz);
			int[] on = HighwayRoute.point(a, t);
			if (HighwayRoute.off(a, px, pz) > HighwayRoute.ON) out.add(new HighwayRoute.Leg(null, 0, 0, on[0], on[1]));
			int[] end = HighwayRoute.point(a, t + distance);
			out.add(new HighwayRoute.Leg(a, t, t + distance, end[0], end[1]));
			return out;
		}
		if (a == null) {
			out.addAll(HighwayRoute.plan(px, pz, dest[0], dest[1]));
			if (out.isEmpty()) out.add(new HighwayRoute.Leg(null, 0, 0, dest[0], dest[1]));
		} else {
			double from = HighwayRoute.along(a, px, pz), to = HighwayRoute.along(a, dest[0], dest[1]);
			int[] on = HighwayRoute.point(a, from);
			if (HighwayRoute.off(a, px, pz) > HighwayRoute.ON) out.add(new HighwayRoute.Leg(null, 0, 0, on[0], on[1]));
			if (Math.abs(to - from) >= 16) {
				int[] end = HighwayRoute.point(a, to);
				out.add(new HighwayRoute.Leg(a, from, to, end[0], end[1]));
			}
			out.add(new HighwayRoute.Leg(null, 0, 0, dest[0], dest[1]));
		}
		// Stopping on the highway: drop the last bit off it (unless that's all there is).
		if (!goOn && out.size() > 1 && !out.getLast().onHighway()) out.removeLast();
		return out;
	}

	/** Starts the roam from where you are. Returns why it couldn't, or null. */
	public static String start(MinecraftClient client) {
		if (!BaritoneBridge.present()) return "Baritone isn't installed";
		if (client.player == null || client.world == null) return "Join a world first";
		NNConfig c = NNConfig.INSTANCE;
		boolean nether = client.world.getRegistryKey().getValue().toString().endsWith("the_nether");
		if (c.roamElytra && !nether) return "Baritone only flies elytra in the nether";
		int[] dest = c.roamToCoords ? destination(client) : null;
		if (c.roamToCoords && dest == null) return "Type the x and z to go to (or paste them)";
		Axis a = axis();
		if (!c.roamToCoords && a == null) return "Pick a highway to roam (Best is for going to coordinates)";
		double px = client.player.getX(), pz = client.player.getZ();
		List<HighwayRoute.Leg> route = plan(a, px, pz, dest, c.roamDistance, c.roamGoOn);
		if (route.isEmpty()) return "You're already there";
		HighwayRoute.Leg last = route.getLast();
		targetX = last.x();
		targetZ = last.z();
		// How to get through a damaged highway, remembering how Baritone had them.
		BaritoneBridge.override("allowBreak", c.roamBreak);
		BaritoneBridge.override("allowPlace", c.roamBridge);
		BaritoneBridge.override("allowParkour", c.roamParkour);
		BaritoneBridge.override("allowParkourPlace", c.roamParkour && c.roamBridge);
		BaritoneBridge.override("assumeWalkOnLava", c.roamLava);
		// A trip under way ends first, so its stop doesn't cancel what the roam sets off.
		SmartTravel.stop();
		legs = route;
		leg = 0;
		boolean ok = c.roamElytra ? BaritoneBridge.run("goal " + targetX + " " + targetZ) && BaritoneBridge.run("elytra") : startLeg(client);
		if (!ok) {
			restore();
			legs = List.of();
			return "Baritone didn't accept the trip (see its message in chat)";
		}
		active = true;
		paused = false;
		warned.clear();
		stoppedFor.clear();
		idleSeconds = 0;
		Alerts.chat(Text.literal("Roaming to " + target() + (c.roamElytra ? " by elytra" : ": " + HighwayRoute.describe(route, px, pz)) + " ("
				+ NaughtyOrNiceClient.INSTANCE.stopKeyName() + " stops). ").formatted(Formatting.GRAY).append(stopButton()));
		return null;
	}

	/** The roam in words, for the screen: where it ends up and the way there. */
	public static String describe(MinecraftClient client) {
		if (client.player == null || client.world == null) return "";
		NNConfig c = NNConfig.INSTANCE;
		int[] dest = c.roamToCoords ? destination(client) : null;
		if (c.roamToCoords && dest == null) return "";
		Axis a = axis();
		if (!c.roamToCoords && a == null) return "";
		double px = client.player.getX(), pz = client.player.getZ();
		List<HighwayRoute.Leg> route = plan(a, px, pz, dest, c.roamDistance, c.roamGoOn);
		if (route.isEmpty()) return "You're there already.";
		String way = HighwayRoute.describe(route, px, pz);
		String note = "";
		if (dest != null && a != null && dest[0] * a.dx() + dest[1] * a.dz() < 0) note = " The " + a.name() + " highway runs away from there: Best picks the right one.";
		else if (dest != null && a != null && HighwayRoute.off(a, dest[0], dest[1]) > 2000)
			note = String.format(Locale.ROOT, " The %s highway only gets within %,d blocks of there.", a.name(), (int) HighwayRoute.off(a, dest[0], dest[1]));
		HighwayRoute.Leg last = route.getLast();
		return "To " + last.x() + ", " + last.z() + ": " + way + "." + note;
	}

	private static void restore() {
		for (String name : CHANGED) BaritoneBridge.release(name);
		BaritoneBridge.showGoal(true);
	}

	/** Sets Baritone off on the current leg. */
	private static boolean startLeg(MinecraftClient client) {
		HighwayRoute.Leg l = legs.get(leg);
		if (l.onHighway()) {
			Integer h = NNConfig.INSTANCE.roamHeight;
			highwayY = h != null ? h : client.player.getBlockY();
			return step(client, l);
		}
		// Getting on the highway is an in-between goal; the last leg's end is where you're going.
		BaritoneBridge.showGoal(leg == legs.size() - 1);
		return BaritoneBridge.run("goto " + l.x() + " " + l.z());
	}

	/** The next step along the highway: the middle of it, up to {@link #STEP} blocks on, at the highway's height. */
	private static boolean step(MinecraftClient client, HighwayRoute.Leg l) {
		double now = HighwayRoute.along(l.axis(), client.player.getX(), client.player.getZ());
		double next = now + Math.signum(l.to() - now) * Math.min(STEP, Math.abs(l.to() - now));
		int[] p = HighwayRoute.point(l.axis(), next);
		stepX = p[0];
		stepZ = p[1];
		// The box only when this step is the end of the whole roam.
		BaritoneBridge.showGoal(leg == legs.size() - 1 && Math.abs(l.to() - next) < 1);
		return BaritoneBridge.run("goto " + stepX + " " + highwayY + " " + stepZ);
	}

	public static void stop(String why) {
		if (!active) return;
		active = false;
		paused = false;
		legs = List.of();
		BaritoneBridge.run("stop");
		restore();
		if (why != null) Alerts.chat(Text.literal("Stopped roaming: " + why + ".").formatted(Formatting.GRAY));
	}

	private static Text stopButton() {
		return Text.literal("[Stop]").styled(st -> st.withColor(Formatting.RED).withUnderline(true).withClickEvent(new ClickEvent.RunCommand("/nn roam stop")));
	}

	private static void pause() {
		paused = true;
		BaritoneBridge.run("pause");
	}

	/** Once a second while roaming: strangers, the next step or leg, arrival. */
	public static void tick(MinecraftClient client) {
		if (!active || client.player == null || client.world == null) return;
		NNConfig c = NNConfig.INSTANCE;
		if (c.roamStopStrangers) {
			for (PlayerEntity p : client.world.getPlayers()) {
				if (p == client.player || warned.contains(p.getUuid())) continue;
				Profile profile = NaughtyOrNiceClient.INSTANCE.ledger.get(p.getUuid());
				boolean friendly = profile != null && (profile.verdict == Profile.Verdict.FRIEND || profile.verdict == Profile.Verdict.NICE);
				if (friendly) continue;
				warned.add(p.getUuid());
				pause();
				Alerts.warn(Text.literal("Paused: " + p.getGameProfile().name() + " is " + (int) p.distanceTo(client.player) + " blocks away. ").formatted(Formatting.RED)
						.append(resumeButton()).append(Text.literal(" ")).append(stopButton()), Alerts.Sound.STRANGER);
			}
		}
		double px = client.player.getX(), pz = client.player.getZ();
		if (c.roamElytra || legs.isEmpty()) {
			if (Math.hypot(targetX - px, targetZ - pz) < 20) {
				Alerts.play(Alerts.Sound.ARRIVED);
				stop("reached " + target());
			}
			return;
		}
		HighwayRoute.Leg l = legs.get(leg);
		boolean last = leg == legs.size() - 1;
		boolean done = l.onHighway() ? Math.abs(l.to() - HighwayRoute.along(l.axis(), px, pz)) < 16
				: Math.hypot(l.x() - px, l.z() - pz) < (last ? 8 : HighwayRoute.ON);
		if (done && last) {
			Alerts.play(Alerts.Sound.ARRIVED);
			stop("reached " + target());
			return;
		}
		if (paused) return;
		if (done) {
			leg++;
			startLeg(client);
			idleSeconds = 0;
			return;
		}
		if (l.onHighway() && Math.hypot(stepX - px, stepZ - pz) < 24) step(client, l);
		// Baritone gave up on a step (or finished it early): set it off again after a moment.
		idleSeconds = BaritoneBridge.isPathing() ? 0 : idleSeconds + 1;
		if (idleSeconds >= 4) {
			idleSeconds = 0;
			if (l.onHighway()) step(client, l);
			else startLeg(client);
		}
	}

	private static Text resumeButton() {
		return Text.literal("[Keep going]").styled(st -> st.withColor(Formatting.GREEN).withUnderline(true).withClickEvent(new ClickEvent.RunCommand("/nn roam resume")));
	}

	/** A new trail turned up: if it's near the highway, pause and ask what to do. */
	public static void trailFound(MinecraftClient client, ChunkTrails.Trail t) {
		if (!active || !NNConfig.INSTANCE.roamStopTrails || client.player == null || !stoppedFor.add(t)) return;
		pause();
		int[] end = t.awayEnd();
		String runs = ChunkTrails.compass(t.x2 - t.x1, t.z2 - t.z1) + "–" + ChunkTrails.compass(t.x1 - t.x2, t.z1 - t.z2);
		Alerts.ping(Text.literal(String.format(Locale.ROOT, "Paused: a chunk trail %,d blocks long runs %s off the highway. ", t.length(), runs)).formatted(Formatting.GOLD)
				.append(Text.literal("[Follow it]").styled(st -> st.withColor(Formatting.AQUA).withUnderline(true)
						.withClickEvent(new ClickEvent.RunCommand("/nn roam follow " + end[0] + " " + end[1]))))
				.append(Text.literal(" ")).append(resumeButton()).append(Text.literal(" ")).append(stopButton())
				.append(WorldDownload.active() ? Text.empty() : Text.literal(" [Download this area]").styled(st -> st.withColor(Formatting.GREEN).withUnderline(true)
						.withClickEvent(new ClickEvent.RunCommand("/nn download start")))), Alerts.Sound.TRAIL);
	}

	/** Leaves the highway to follow a trail to its far end. */
	public static void follow(int x, int z) {
		active = false;
		paused = false;
		legs = List.of();
		restore();
		BaritoneBridge.run("goto " + x + " " + z);
		Alerts.chat(Text.literal("Following the trail to " + x + ", " + z + ".").formatted(Formatting.GRAY));
	}

	public static void resume() {
		paused = false;
		idleSeconds = 0;
		BaritoneBridge.run("resume");
	}
}
