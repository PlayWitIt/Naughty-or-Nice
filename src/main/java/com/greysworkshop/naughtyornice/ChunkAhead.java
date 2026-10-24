package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;

/**
 * Tells you once when the land changes up ahead as you travel: new chunks coming up while you're in old land, or old
 * chunks (someone has been there) coming up while you're in new land. It looks along the way you're actually moving,
 * and only counts an edge two chunks deep, so one odd chunk doesn't set it off. In place of a line always on screen.
 */
public final class ChunkAhead {
	private ChunkAhead() {}

	/** How far ahead it looks, in blocks, and how finely. */
	static final int NEAR = 16, FAR = 128, STEP = 8;

	/** What's ahead: the state of the first chunk that's different land, and how far to it. */
	public record Ahead(byte state, int blocks) {}

	private static double lastX = Double.NaN, lastZ;
	/** The last change told about (NEW or OLD), so it's said once; 0 when nothing's been said. */
	private static byte told;
	private static int quietSeconds;

	/**
	 * The edge ahead along (dirX, dirZ) from (x, z): the first chunk of the other kind of land (new when you're on old or
	 * unknown land, old when you're on new), with the chunk past it the same. Null when there's none in reach.
	 */
	static Ahead ahead(ChunkTrails trails, String dim, double x, double z, double dirX, double dirZ) {
		double len = Math.hypot(dirX, dirZ);
		if (len < 1e-6) return null;
		double ux = dirX / len, uz = dirZ / len;
		int hereX = (int) Math.floor(x) >> 4, hereZ = (int) Math.floor(z) >> 4;
		byte here = trails.state(dim, hereX, hereZ);
		byte want = here == ChunkTrails.NEW ? ChunkTrails.OLD : ChunkTrails.NEW;
		int lastCx = hereX, lastCz = hereZ;
		for (int d = NEAR; d <= FAR; d += STEP) {
			int cx = (int) Math.floor(x + ux * d) >> 4, cz = (int) Math.floor(z + uz * d) >> 4;
			if (cx == lastCx && cz == lastCz) continue;
			lastCx = cx;
			lastCz = cz;
			byte s = trails.state(dim, cx, cz);
			if (s == 0 || s != want) continue;
			// Two deep: the next chunk along must be the same.
			int nx = (int) Math.floor(x + ux * (d + 16)) >> 4, nz = (int) Math.floor(z + uz * (d + 16)) >> 4;
			if (trails.state(dim, nx, nz) != want) continue;
			return new Ahead(want, d);
		}
		return null;
	}

	/** Once a second. */
	public static void tick(MinecraftClient client, ChunkTrails trails) {
		if (client.player == null || client.world == null || !NNConfig.INSTANCE.chunkTrails || !NNConfig.INSTANCE.aheadAlerts) {
			lastX = Double.NaN;
			return;
		}
		double x = client.player.getX(), z = client.player.getZ();
		if (Double.isNaN(lastX)) {
			lastX = x;
			lastZ = z;
			return;
		}
		double dx = x - lastX, dz = z - lastZ;
		lastX = x;
		lastZ = z;
		// Standing about, or a teleport: nothing to say.
		if (Math.hypot(dx, dz) < 2 || Math.hypot(dx, dz) > 200) return;
		String dim = client.world.getRegistryKey().getValue().toString();
		byte here = trails.state(dim, (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
		// In the land that was told about: the next change is news again.
		if (told != 0 && here == told) told = 0;
		Ahead a = ahead(trails, dim, x, z, dx, dz);
		if (a == null) {
			if (told != 0 && ++quietSeconds >= 10) told = 0;
			return;
		}
		quietSeconds = 0;
		if (a.state() == told) return;
		told = a.state();
		String way = ChunkTrails.compass(dx, dz);
		boolean fresh = a.state() == ChunkTrails.NEW;
		String said = String.format(Locale.ROOT, fresh ? "New chunks ahead: %d blocks %s. Nobody has been past there." : "Old chunks ahead: %d blocks %s. Someone has been there.",
				a.blocks(), way);
		Text message = Text.literal(said).formatted(fresh ? Formatting.RED : Formatting.GREEN);
		client.player.sendMessage(Alerts.prefix().append(message), false);
		client.player.sendMessage(message, true);
		Alerts.play(fresh ? Alerts.Sound.NEW_AHEAD : Alerts.Sound.OLD_AHEAD);
	}

	public static void reset() {
		lastX = Double.NaN;
		told = 0;
		quietSeconds = 0;
	}
}
