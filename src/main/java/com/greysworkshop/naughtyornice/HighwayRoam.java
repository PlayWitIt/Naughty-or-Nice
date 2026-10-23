package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Travels a highway with Baritone while chunk trails are read, and stops to ask when something turns up: a trail
 * near the highway, or someone who isn't a friend coming into view. Damaged highways are got through with
 * Baritone's own options: breaking through what's in the way, placing blocks you carry to bridge gaps and missing
 * floor (or going round the side), parkour jumps, and (only with Fire Resistance) walking across lava.
 */
public final class HighwayRoam {
	/** A highway: which way along x and z (each -1, 0 or 1). */
	public record Axis(String name, int dx, int dz) {}

	public static final Axis[] AXES = {
			new Axis("+X", 1, 0), new Axis("-X", -1, 0), new Axis("+Z", 0, 1), new Axis("-Z", 0, -1),
			new Axis("+X+Z", 1, 1), new Axis("+X-Z", 1, -1), new Axis("-X+Z", -1, 1), new Axis("-X-Z", -1, -1)};

	// What you chose in the Baritone screen (kept while the game runs).
	public static Axis axis = AXES[0];
	public static boolean elytra;
	/** The highway's height; Integer.MIN_VALUE for "where I am". */
	public static int height = Integer.MIN_VALUE;
	public static int distance = 50_000;
	public static boolean breakThrough = true, bridgeGaps = true, parkour = true, stopAtTrails = true, stopForStrangers = true;
	/** Walk across lava: only with Fire Resistance on. */
	public static boolean walkOnLava;

	private static boolean active;
	private static int targetX, targetY, targetZ;
	/** Players already warned about this trip, and trails already stopped for. */
	private static final Set<UUID> warned = new HashSet<>();
	private static final Set<ChunkTrails.Trail> stoppedFor = new HashSet<>();
	private static int idleSeconds;

	private HighwayRoam() {}

	public static boolean active() {
		return active;
	}

	public static String target() {
		return targetX + ", " + targetY + ", " + targetZ;
	}

	/** Starts travelling the chosen highway from where you are. Returns why it couldn't, or null. */
	public static String start(MinecraftClient client) {
		if (!BaritoneBridge.present()) return "Baritone isn't installed";
		if (client.player == null || client.world == null) return "Join a world first";
		if (SmartTravel.riding(client)) return SmartTravel.RIDING;
		boolean nether = client.world.getRegistryKey().getValue().toString().endsWith("the_nether");
		if (elytra && !nether) return "Baritone only flies elytra in the nether";
		double px = client.player.getX(), pz = client.player.getZ();
		// The point on the highway "distance" further along it than you are.
		if (axis.dx() != 0 && axis.dz() != 0) {
			double t = Math.max(Math.abs(px), Math.abs(pz)) + distance;
			targetX = (int) (axis.dx() * t);
			targetZ = (int) (axis.dz() * t);
		} else if (axis.dx() != 0) {
			targetX = (int) (px + axis.dx() * distance);
			targetZ = 0;
		} else {
			targetX = 0;
			targetZ = (int) (pz + axis.dz() * distance);
		}
		targetY = height == Integer.MIN_VALUE ? client.player.getBlockY() : height;
		// How to get through a damaged highway.
		BaritoneBridge.setBool("allowBreak", breakThrough);
		BaritoneBridge.setBool("allowPlace", bridgeGaps);
		BaritoneBridge.setBool("allowParkour", parkour);
		BaritoneBridge.setBool("allowParkourPlace", parkour && bridgeGaps);
		BaritoneBridge.setBool("assumeWalkOnLava", walkOnLava);
		boolean ok;
		if (elytra) {
			ok = BaritoneBridge.run("goal " + targetX + " " + targetZ) && BaritoneBridge.run("elytra");
		} else {
			ok = BaritoneBridge.run("goto " + targetX + " " + targetY + " " + targetZ);
		}
		if (!ok) return "Baritone didn't accept the trip (see its message in chat)";
		active = true;
		warned.clear();
		stoppedFor.clear();
		idleSeconds = 0;
		Alerts.chat(Text.literal("Roaming the " + axis.name() + " highway towards " + target() + (elytra ? " by elytra" : "") + " (" + NaughtyOrNiceClient.INSTANCE.stopKeyName() + " stops). ").formatted(Formatting.GRAY)
				.append(stopButton()));
		return null;
	}

	public static void stop(String why) {
		if (!active) return;
		active = false;
		BaritoneBridge.run("stop");
		if (why != null) Alerts.chat(Text.literal("Stopped roaming: " + why + ".").formatted(Formatting.GRAY));
	}

	private static Text stopButton() {
		return Text.literal("[Stop]").styled(st -> st.withColor(Formatting.RED).withUnderline(true).withClickEvent(new ClickEvent.RunCommand("/nn roam stop")));
	}

	/** Once a second while roaming: strangers, arrival. */
	public static void tick(MinecraftClient client) {
		if (!active || client.player == null || client.world == null) return;
		if (stopForStrangers) {
			for (PlayerEntity p : client.world.getPlayers()) {
				if (p == client.player || warned.contains(p.getUuid())) continue;
				Profile profile = NaughtyOrNiceClient.INSTANCE.ledger.get(p.getUuid());
				boolean friendly = profile != null && (profile.verdict == Profile.Verdict.FRIEND || profile.verdict == Profile.Verdict.NICE);
				if (friendly) continue;
				warned.add(p.getUuid());
				BaritoneBridge.run("pause");
				Alerts.warn(Text.literal("Paused: " + p.getGameProfile().name() + " is " + (int) p.distanceTo(client.player) + " blocks away. ").formatted(Formatting.RED)
						.append(resumeButton()).append(Text.literal(" ")).append(stopButton()));
			}
		}
		double left = Math.hypot(targetX - client.player.getX(), targetZ - client.player.getZ());
		if (left < 20) {
			stop("reached " + target());
			return;
		}
		idleSeconds = BaritoneBridge.isPathing() ? 0 : idleSeconds + 1;
	}

	private static Text resumeButton() {
		return Text.literal("[Keep going]").styled(st -> st.withColor(Formatting.GREEN).withUnderline(true).withClickEvent(new ClickEvent.RunCommand("/nn roam resume")));
	}

	/** A new trail turned up: if it's near the highway, pause and ask what to do. */
	public static void trailFound(MinecraftClient client, ChunkTrails.Trail t) {
		if (!active || !stopAtTrails || client.player == null || !stoppedFor.add(t)) return;
		BaritoneBridge.run("pause");
		int[] end = t.awayEnd();
		String runs = ChunkTrails.compass(t.x2 - t.x1, t.z2 - t.z1) + "–" + ChunkTrails.compass(t.x1 - t.x2, t.z1 - t.z2);
		Alerts.ping(Text.literal(String.format(Locale.ROOT, "Paused: a chunk trail %,d blocks long runs %s off the highway. ", t.length(), runs)).formatted(Formatting.GOLD)
				.append(Text.literal("[Follow it]").styled(st -> st.withColor(Formatting.AQUA).withUnderline(true)
						.withClickEvent(new ClickEvent.RunCommand("/nn roam follow " + end[0] + " " + end[1]))))
				.append(Text.literal(" ")).append(resumeButton()).append(Text.literal(" ")).append(stopButton())
				.append(WorldDownload.active() ? Text.empty() : Text.literal(" [Download this area]").styled(st -> st.withColor(Formatting.GREEN).withUnderline(true)
						.withClickEvent(new ClickEvent.RunCommand("/nn download start")))));
	}

	/** Leaves the highway to follow a trail to its far end. */
	public static void follow(int x, int z) {
		active = false;
		BaritoneBridge.run("goto " + x + " " + z);
		Alerts.chat(Text.literal("Following the trail to " + x + ", " + z + ".").formatted(Formatting.GRAY));
	}

	public static void resume() {
		BaritoneBridge.run("resume");
	}
}
