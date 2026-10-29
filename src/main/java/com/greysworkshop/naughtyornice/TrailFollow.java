package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Follows old chunks: Baritone is walked (or flown, in the nether) from one old chunk to the next, always onward, so
 * it keeps to the path someone left through fresh land and turns with it. Each step is the old chunk a few chunks
 * ahead that best keeps the way it's heading and has old land on past it. It stops to ask where that can't be
 * decided: a fork (two ways on), a wide old area (a base or a town?), or the end (only new chunks ahead). The same
 * stops for strangers and low health as a trip; the trip bar shows it; Z stops it.
 */
public final class TrailFollow {
	private TrailFollow() {}

	/** What to do next: step to a chunk, ask at a fork (with the other way), stop in a wide old area, at the end, or look further. */
	public enum Kind { STEP, FORK, WIDE, END, PROBE }

	public record Decision(Kind kind, int cx, int cz, int altX, int altZ) {}

	/** How far ahead a step looks, in chunks; flying looks further. */
	static final int NEAR = 2, FAR = 6, FAR_FLYING = 10;
	/** Half the width of the way ahead, in degrees; wider when nothing's found in the narrow one. */
	static final double CONE = 55, WIDE_CONE = 85;
	/** Old chunks in the 7 by 7 around you that make a wide old area. */
	static final int WIDE_AREA = 30;

	private static boolean active, paused, flying;
	private static String dim = "";
	/** The way it's heading (a unit vector), and the chunk it's walking to. */
	private static double hx, hz;
	private static int targetX, targetZ;
	private static final Set<Long> visited = new HashSet<>();
	private static final Set<UUID> warned = new HashSet<>();
	private static Decision fork;
	private static int probes, idle;
	private static double followed, lastX, lastZ;
	/** Where a wide old area was last told about, so it's said once per area. */
	private static double wideX = Double.NaN, wideZ;
	private static boolean lowHealthWarned;

	public static boolean active() {
		return active;
	}

	static boolean paused() {
		return paused;
	}

	static boolean flying() {
		return active && flying;
	}

	static int[] target() {
		return new int[] {targetX, targetZ};
	}

	/** Blocks followed so far. */
	static double followed() {
		return followed;
	}

	static String heading() {
		return ChunkTrails.compass(hx, hz);
	}

	// ---- Deciding (plain sums, so the self-test can check them anywhere) ----

	/**
	 * The next step from (px, pz) heading (dx, dz): the best old chunk ahead, a fork when two ways on are about as good,
	 * a wide old area when you're in one (unless {@code wideOk}), the end when only new chunks are ahead, or a probe
	 * (straight on, to load more) when what's ahead isn't known yet.
	 */
	static Decision decide(ChunkTrails trails, String dim, double px, double pz, double dx, double dz, Set<Long> visited, boolean flying, boolean wideOk,
			java.util.function.BiPredicate<Integer, Integer> loaded) {
		double len = Math.hypot(dx, dz);
		if (len < 1e-6) return new Decision(Kind.END, 0, 0, 0, 0);
		double ux = dx / len, uz = dz / len;
		int pcx = (int) Math.floor(px) >> 4, pcz = (int) Math.floor(pz) >> 4;
		if (!wideOk && trails.state(dim, pcx, pcz) == ChunkTrails.OLD && oldAround(trails, dim, pcx, pcz, 3) >= WIDE_AREA) {
			return new Decision(Kind.WIDE, pcx, pcz, 0, 0);
		}
		for (double cone : new double[] {CONE, WIDE_CONE}) {
			List<double[]> found = candidates(trails, dim, px, pz, ux, uz, visited, flying ? FAR_FLYING : FAR, cone);
			if (found.isEmpty()) continue;
			found.sort((a, b) -> Double.compare(b[2], a[2]));
			double[] best = found.get(0);
			// A fork: another way on, well apart, nearly as good, with old land past it too.
			for (double[] other : found) {
				if (other == best) continue;
				double apart = Math.abs(angle(best[3], best[4], other[3], other[4]));
				if (apart > 45 && other[2] >= best[2] * 0.75 && best[5] >= 2 && other[5] >= 2
						&& goesOn(trails, dim, (int) best[0], (int) best[1], best[3], best[4]) && goesOn(trails, dim, (int) other[0], (int) other[1], other[3], other[4])) {
					return new Decision(Kind.FORK, (int) best[0], (int) best[1], (int) other[0], (int) other[1]);
				}
			}
			return new Decision(Kind.STEP, (int) best[0], (int) best[1], 0, 0);
		}
		// Nothing old ahead: unknown land ahead is worth a look; known new land is the end.
		for (int k = 1; k <= 3; k++) {
			int cx = (int) Math.floor(px + ux * k * 16) >> 4, cz = (int) Math.floor(pz + uz * k * 16) >> 4;
			if (trails.state(dim, cx, cz) == 0 && !loaded.test(cx, cz)) {
				int sx = (int) Math.floor(px + ux * 48) >> 4, sz = (int) Math.floor(pz + uz * 48) >> 4;
				return new Decision(Kind.PROBE, sx, sz, 0, 0);
			}
		}
		return new Decision(Kind.END, pcx, pcz, 0, 0);
	}

	/** Old, unvisited chunks in the cone ahead: {cx, cz, score, dirX, dirZ, old neighbours}. */
	private static List<double[]> candidates(ChunkTrails trails, String dim, double px, double pz, double ux, double uz, Set<Long> visited, int far, double cone) {
		List<double[]> out = new ArrayList<>();
		int pcx = (int) Math.floor(px) >> 4, pcz = (int) Math.floor(pz) >> 4;
		double minCos = Math.cos(Math.toRadians(cone));
		double ideal = (NEAR + far) / 2.0 * 16;
		for (int i = -far; i <= far; i++) {
			for (int j = -far; j <= far; j++) {
				int cx = pcx + i, cz = pcz + j;
				double vx = cx * 16 + 8 - px, vz = cz * 16 + 8 - pz;
				double dist = Math.hypot(vx, vz);
				if (dist < NEAR * 16 || dist > far * 16 + 8) continue;
				double cos = (vx * ux + vz * uz) / dist;
				if (cos < minCos) continue;
				if (trails.state(dim, cx, cz) != ChunkTrails.OLD || visited.contains(ChunkPos.toLong(cx, cz))) continue;
				int n = oldAround(trails, dim, cx, cz, 1) - 1;
				double score = cos * 2 + Math.min(n, 4) * 0.5 - Math.abs(dist - ideal) / ideal * 0.8;
				out.add(new double[] {cx, cz, score, vx / dist, vz / dist, n});
			}
		}
		return out;
	}

	/** Whether there's more old land past a chunk, the way it lies from you. */
	private static boolean goesOn(ChunkTrails trails, String dim, int cx, int cz, double dirX, double dirZ) {
		for (int k = 1; k <= 3; k++) {
			for (int s = -1; s <= 1; s++) {
				int x = (int) Math.round(cx + dirX * k - dirZ * s), z = (int) Math.round(cz + dirZ * k + dirX * s);
				if (trails.state(dim, x, z) == ChunkTrails.OLD) return true;
			}
		}
		return false;
	}

	/** Old chunks in the square of radius {@code r} round a chunk (itself included). */
	static int oldAround(ChunkTrails trails, String dim, int cx, int cz, int r) {
		int n = 0;
		for (int i = -r; i <= r; i++) {
			for (int j = -r; j <= r; j++) if (trails.state(dim, cx + i, cz + j) == ChunkTrails.OLD) n++;
		}
		return n;
	}

	/** The angle between two directions, in degrees (-180 to 180). */
	private static double angle(double ax, double az, double bx, double bz) {
		return Math.toDegrees(Math.atan2(ax * bz - az * bx, ax * bx + az * bz));
	}

	// ---- Following ----

	/**
	 * Starts following from where you are: away from spawn, or the way you're facing. Returns why it can't, or null.
	 */
	public static String start(MinecraftClient client, boolean awayFromSpawn) {
		if (!BaritoneBridge.present()) return "Baritone isn't installed";
		if (client.player == null || client.world == null) return "Join a world first";
		NNConfig c = NNConfig.INSTANCE;
		if (!c.chunkTrails) return "Chunk trails are off (Settings → Chunk trails): there's nothing to follow without them";
		flying = c.trailFollowElytra;
		boolean nether = client.world.getRegistryKey().getValue().toString().endsWith("the_nether");
		if (flying && !nether) return "Baritone only flies elytra in the nether";
		double px = client.player.getX(), pz = client.player.getZ();
		double yaw = Math.toRadians(client.player.getYaw());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);
		if (awayFromSpawn && Math.hypot(px, pz) > 64) {
			hx = px / Math.hypot(px, pz);
			hz = pz / Math.hypot(px, pz);
		} else {
			hx = fx;
			hz = fz;
		}
		// Already on a trail with a known far end: head for that end.
		ChunkTrails trails = NaughtyOrNiceClient.INSTANCE.ledger.chunkTrails;
		dim = client.world.getRegistryKey().getValue().toString();
		ChunkTrails.Trail t = trails.nearest(dim, px, pz, 200);
		if (awayFromSpawn && t != null && t.away != 0) {
			int[] end = t.awayEnd();
			double d = Math.hypot(end[0] - px, end[1] - pz);
			if (d > 32) {
				hx = (end[0] - px) / d;
				hz = (end[1] - pz) / d;
			}
		}
		SmartTravel.stop();
		HighwayRoam.stop(null);
		visited.clear();
		warned.clear();
		fork = null;
		probes = 0;
		idle = 0;
		followed = 0;
		lastX = px;
		lastZ = pz;
		wideX = Double.NaN;
		lowHealthWarned = false;
		active = true;
		paused = false;
		Decision d = decide(client);
		if (d.kind() == Kind.END) {
			active = false;
			return "No old chunks ahead to follow. Face along a trail (or get on one) and try again";
		}
		act(client, d);
		if (!active) return "Baritone didn't take it (see its message in chat)";
		Alerts.chat(Text.literal("Following old chunks, heading " + heading() + " (" + NaughtyOrNiceClient.INSTANCE.stopKeyName() + " stops). ").formatted(Formatting.GRAY)
				.append(button("[Stop]", Formatting.RED, "/nn trail stop")));
		return null;
	}

	private static Decision decide(MinecraftClient client) {
		ChunkTrails trails = NaughtyOrNiceClient.INSTANCE.ledger.chunkTrails;
		boolean wideOk = !NNConfig.INSTANCE.trailFollowStopWide
				|| !Double.isNaN(wideX) && Math.hypot(client.player.getX() - wideX, client.player.getZ() - wideZ) < 160;
		return decide(trails, dim, client.player.getX(), client.player.getZ(), hx, hz, visited, flying, wideOk,
				(x, z) -> client.world.getChunkManager().isChunkLoaded(x, z));
	}

	/** Does what was decided. */
	private static void act(MinecraftClient client, Decision d) {
		double px = client.player.getX(), pz = client.player.getZ();
		switch (d.kind()) {
			case STEP -> {
				probes = 0;
				walkTo(client, d.cx(), d.cz());
			}
			case PROBE -> {
				if (++probes > 3) {
					end(client);
					return;
				}
				walkTo(client, d.cx(), d.cz());
			}
			case FORK -> {
				fork = d;
				pause();
				String a = ChunkTrails.compass(d.cx() * 16 + 8 - px, d.cz() * 16 + 8 - pz), b = ChunkTrails.compass(d.altX() * 16 + 8 - px, d.altZ() * 16 + 8 - pz);
				Alerts.warn(Text.literal("The old chunks fork here: one way " + a + ", one way " + b + ". ").formatted(Formatting.GOLD)
						.append(button("[Go " + a + "]", Formatting.AQUA, "/nn trail take 1")).append(Text.literal(" "))
						.append(button("[Go " + b + "]", Formatting.AQUA, "/nn trail take 2")).append(Text.literal(" "))
						.append(button("[Stop]", Formatting.RED, "/nn trail stop")), Alerts.Sound.TRAIL_FORK);
			}
			case WIDE -> {
				wideX = px;
				wideZ = pz;
				pause();
				Alerts.warn(Text.literal("A wide old area here (a base or a town?), " + oldAround(NaughtyOrNiceClient.INSTANCE.ledger.chunkTrails, dim, d.cx(), d.cz(), 3)
						+ " of the 49 chunks round you are old. ").formatted(Formatting.GOLD)
						.append(button("[Keep following]", Formatting.GREEN, "/nn trail resume")).append(Text.literal(" "))
						.append(button("[Stop]", Formatting.RED, "/nn trail stop"))
						.append(WorldDownload.active() ? Text.empty() : Text.literal(" ").append(button("[Download this area]", Formatting.GREEN, "/nn download start"))),
						Alerts.Sound.TRAIL_FORK);
			}
			case END -> end(client);
		}
	}

	private static void walkTo(MinecraftClient client, int cx, int cz) {
		targetX = cx * 16 + 8;
		targetZ = cz * 16 + 8;
		visited.add(ChunkPos.toLong(cx, cz));
		// Every chunk it passes is visited too, so it never turns back along its own way.
		visited.add(ChunkPos.toLong((int) Math.floor(client.player.getX()) >> 4, (int) Math.floor(client.player.getZ()) >> 4));
		double d = Math.hypot(targetX - client.player.getX(), targetZ - client.player.getZ());
		if (d > 1) {
			hx = hx * 0.5 + (targetX - client.player.getX()) / d * 0.5;
			hz = hz * 0.5 + (targetZ - client.player.getZ()) / d * 0.5;
			double l = Math.hypot(hx, hz);
			hx /= l;
			hz /= l;
		}
		BaritoneBridge.showGoal(false);
		boolean ok = flying ? BaritoneBridge.run("goal " + targetX + " " + targetZ) && BaritoneBridge.run("elytra") : BaritoneBridge.run("goto " + targetX + " " + targetZ);
		if (!ok) {
			active = false;
			BaritoneBridge.showGoal(true);
		}
		idle = 0;
	}

	private static void end(MinecraftClient client) {
		stop(null);
		Alerts.warn(Text.literal(String.format(Locale.ROOT, "The old chunks run out here: only new land ahead (%,d blocks followed). ", (int) followed)).formatted(Formatting.GOLD)
				.append(button("[Look the way I'm facing]", Formatting.AQUA, "/nn trail follow facing")), Alerts.Sound.TRAIL_END);
	}

	private static void pause() {
		paused = true;
		BaritoneBridge.run("stop");
	}

	/** Takes one way at a fork: 1 the first offered, 2 the other. */
	public static void take(MinecraftClient client, int which) {
		if (!active || fork == null || client.player == null) return;
		int cx = which == 2 ? fork.altX() : fork.cx(), cz = which == 2 ? fork.altZ() : fork.cz();
		// The way not taken isn't wanted again.
		int ox = which == 2 ? fork.cx() : fork.altX(), oz = which == 2 ? fork.cz() : fork.altZ();
		visited.add(ChunkPos.toLong(ox, oz));
		double d = Math.hypot(cx * 16 + 8 - client.player.getX(), cz * 16 + 8 - client.player.getZ());
		hx = (cx * 16 + 8 - client.player.getX()) / d;
		hz = (cz * 16 + 8 - client.player.getZ()) / d;
		fork = null;
		paused = false;
		walkTo(client, cx, cz);
	}

	public static void resume(MinecraftClient client) {
		if (!active || client.player == null) return;
		paused = false;
		fork = null;
		idle = 0;
		act(client, decide(client));
	}

	public static void stop(String why) {
		if (!active) return;
		active = false;
		paused = false;
		fork = null;
		BaritoneBridge.run("stop");
		BaritoneBridge.showGoal(true);
		if (why != null) Alerts.chat(Text.literal(String.format(Locale.ROOT, "Stopped following old chunks: %s (%,d blocks followed).", why, (int) followed)).formatted(Formatting.GRAY));
	}

	/** Once a second while following: strangers, health, how far it's come, and the next step when this one's done. */
	public static void tick(MinecraftClient client) {
		if (!active || client.player == null || client.world == null) return;
		if (!client.world.getRegistryKey().getValue().toString().equals(dim)) {
			stop("you changed dimension");
			return;
		}
		double px = client.player.getX(), pz = client.player.getZ();
		followed += Math.hypot(px - lastX, pz - lastZ);
		lastX = px;
		lastZ = pz;
		NNConfig c = NNConfig.INSTANCE;
		if (c.tripStopForStrangers) {
			for (PlayerEntity p : client.world.getPlayers()) {
				if (p == client.player || !warned.add(p.getUuid())) continue;
				Profile profile = NaughtyOrNiceClient.INSTANCE.ledger.get(p.getUuid());
				if (profile != null && (profile.verdict == Profile.Verdict.FRIEND || profile.verdict == Profile.Verdict.NICE)) continue;
				pause();
				Alerts.warn(Text.literal("Paused: " + p.getGameProfile().name() + " is " + Alerts.blocks((int) p.distanceTo(client.player)) + " away. ").formatted(Formatting.RED)
						.append(button("[Keep following]", Formatting.GREEN, "/nn trail resume")).append(Text.literal(" "))
						.append(button("[Stop]", Formatting.RED, "/nn trail stop")), Alerts.Sound.STRANGER);
			}
		}
		int stopAt = c.tripStopHealth;
		if (stopAt > 0 && client.player.getHealth() <= stopAt && !lowHealthWarned) {
			lowHealthWarned = true;
			stop(null);
			Alerts.warn(Text.literal("Stopped following: your health is down to " + (int) Math.ceil(client.player.getHealth() / 2) + " hearts.").formatted(Formatting.RED),
					Alerts.Sound.LOW_HEALTH);
			return;
		}
		if (paused) return;
		boolean near = Math.hypot(targetX - px, targetZ - pz) < (flying ? 48 : 12);
		idle = BaritoneBridge.isPathing() ? 0 : idle + 1;
		// Close to this step (look on while still moving, so it doesn't stop and start), or Baritone gave up on it.
		if (near || idle >= 3) act(client, decide(client));
	}

	private static Text button(String label, Formatting color, String command) {
		return Text.literal(label).styled(st -> st.withColor(color).withUnderline(true).withClickEvent(new ClickEvent.RunCommand(command)));
	}
}
