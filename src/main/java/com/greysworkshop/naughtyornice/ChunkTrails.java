package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Chunk trails: which land is old (someone was there before) and which is fresh (just generated for you), and the
 * paths other players left through fresh land.
 * <p>
 * How a chunk is told apart: when land is generated for the first time, its water and lava start to flow only after
 * it has been sent to you, so the server sends flowing-liquid updates for it. Land that already existed arrives with its
 * liquids already flowed. Chunks without any liquid can't be told either way and are left unknown, never guessed.
 * Chunks you generated yourself are remembered (per server), so your own old paths are never taken for someone else's.
 * <p>
 * A trail is a long, narrow strip of old land with fresh land on both sides: someone travelled through there. It's
 * only called one when that shape is clear.
 */
public final class ChunkTrails {
	public static final byte NEW = 1, OLD = 2;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Predicate<BlockState> FLOWING = state -> {
		FluidState fluid = state.getFluidState();
		return !fluid.isEmpty() && !fluid.isStill();
	};
	/** How far around you trails are looked for, in chunks. */
	public static final int RADIUS = 48;
	/**
	 * Old chunks this close (in chunks) are one patch, bridging the chunks between them that have no liquid and so
	 * can't be told. It only joins pieces; whether a patch is a trail is still down to its shape.
	 */
	private static final int LINK = 3;

	/** What each chunk is, per dimension, while you're on this server. */
	private final Map<String, Long2ByteOpenHashMap> states = new HashMap<>();
	/** Chunks you've seen generated (they were fresh when you got there), per dimension. Saved. */
	private final Map<String, LongOpenHashSet> mine = new HashMap<>();
	/** Loaded chunks that arrived with no flowing liquid: the only ones a flowing update can show to be fresh. */
	private final LongOpenHashSet pristine = new LongOpenHashSet();
	/** Trails found, saved per server. */
	public List<Trail> trails = new ArrayList<>();
	private boolean dirty, mineDirty;
	private int version;
	private long lastMineSave;

	/** A path someone left through fresh land. Ends are in block coordinates of its dimension. */
	public static final class Trail {
		public String dimension = "";
		public int x1, z1, x2, z2;
		/** Old chunks seen along it, and how wide it is (chunks). */
		public int chunks, width;
		public long firstSeen, lastSeen;
		/** Which end leads away from spawn (1 or 2), or 0 if neither clearly does. */
		public int away;

		public int length() {
			return (int) Math.hypot(x2 - x1, z2 - z1);
		}

		/** The end that leads away from spawn, or the second end. */
		public int[] awayEnd() {
			return away == 1 ? new int[] {x1, z1} : new int[] {x2, z2};
		}
	}

	public int version() {
		return version;
	}

	// ---- Reading chunks ----

	private Long2ByteOpenHashMap states(String dimension) {
		return states.computeIfAbsent(dimension, d -> new Long2ByteOpenHashMap());
	}

	private LongOpenHashSet mine(String dimension) {
		return mine.computeIfAbsent(dimension, d -> new LongOpenHashSet());
	}

	/** A chunk arrived. Flowing liquid already in it means it's old land (unless you generated it yourself). */
	public void chunkLoaded(String dimension, WorldChunk chunk) {
		long key = chunk.getPos().toLong();
		if (hasFlowing(chunk)) {
			pristine.remove(key);
			if (!mine(dimension).contains(key) && states(dimension).get(key) != NEW && states(dimension).put(key, OLD) != OLD) {
				mineDirty = true;
				version++;
			}
		} else {
			pristine.add(key);
		}
	}

	static boolean hasFlowing(WorldChunk chunk) {
		for (ChunkSection section : chunk.getSectionArray()) {
			// The palette lists every kind of block in the section, so this needn't look at each block.
			if (section != null && !section.isEmpty() && section.getBlockStateContainer().hasAny(FLOWING)) return true;
		}
		return false;
	}

	public void chunkUnloaded(int x, int z) {
		pristine.remove(ChunkPos.toLong(x, z));
	}

	/**
	 * A block changed. Liquid starting to flow in a chunk that arrived with none flowing, away from any player (so
	 * nobody poured or freed it), means the chunk was just generated: fresh land.
	 */
	public void blockChanged(MinecraftClient client, String dimension, BlockPos pos, BlockState state) {
		if (!FLOWING.test(state)) return;
		long key = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
		if (!pristine.contains(key) || client.world == null) return;
		for (PlayerEntity player : client.world.getPlayers()) {
			if (Math.abs(player.getBlockX() - pos.getX()) <= 24 && Math.abs(player.getBlockZ() - pos.getZ()) <= 24) return;
		}
		pristine.remove(key);
		states(dimension).put(key, NEW);
		if (mine(dimension).add(key)) mineDirty = true;
		version++;
	}

	/** For testing: says what a chunk is. */
	void set(String dimension, int x, int z, byte state) {
		states(dimension).put(ChunkPos.toLong(x, z), state);
		if (state == NEW) mine(dimension).add(ChunkPos.toLong(x, z));
		mineDirty = true;
		version++;
	}

	/** For testing: forgets that you generated a chunk. */
	void forgetMine(String dimension, int x, int z) {
		mine(dimension).remove(ChunkPos.toLong(x, z));
	}

	/** What a chunk is: NEW (fresh, or yours), OLD, or 0 (unknown). */
	public byte state(String dimension, int x, int z) {
		long key = ChunkPos.toLong(x, z);
		if (mine(dimension).contains(key)) return NEW;
		return states(dimension).get(key);
	}

	public interface ChunkVisitor {
		void visit(int x, int z, byte state);
	}

	/** Every chunk known in a dimension: yours count as fresh. */
	public void forEachChunk(String dimension, ChunkVisitor visitor) {
		LongOpenHashSet own = mine(dimension);
		for (it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry e : states(dimension).long2ByteEntrySet()) {
			long k = e.getLongKey();
			if (!own.contains(k)) visitor.visit(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k), e.getByteValue());
		}
		for (it.unimi.dsi.fastutil.longs.LongIterator it = own.iterator(); it.hasNext(); ) {
			long k = it.nextLong();
			visitor.visit(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k), NEW);
		}
	}

	/** Forgets what's far away (it's kept in the trails found), so memory stays small. */
	public void trim(String dimension, int cx, int cz) {
		Long2ByteOpenHashMap map = states(dimension);
		if (map.size() < 1_500_000) return;
		map.keySet().removeIf(k -> Math.abs(ChunkPos.getPackedX(k) - cx) > 1500 || Math.abs(ChunkPos.getPackedZ(k) - cz) > 1500);
	}

	// ---- Finding trails ----

	/**
	 * Looks for trails around a chunk: patches of old land (old chunks no more than {@link #LINK} apart), each measured
	 * along its longest direction. A patch is a trail when it's at least 8 chunks long, at least twice as long as it is
	 * wide, and there's fresh land on both sides of it. Returns the trails found (also kept and merged into
	 * {@link #trails}), and the centres of old patches in fresh land that aren't (yet) clearly trails.
	 */
	public List<Trail> analyse(String dimension, int cx, int cz, long now, List<int[]> patchesOut) {
		int size = RADIUS * 2 + 1;
		byte[][] grid = new byte[size][size];
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) grid[i][j] = state(dimension, cx - RADIUS + i, cz - RADIUS + j);
		}
		boolean[][] seen = new boolean[size][size];
		List<Trail> found = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				if (grid[i][j] != OLD || seen[i][j]) continue;
				List<int[]> patch = flood(grid, seen, i, j);
				if (patch.size() < 5) continue;
				Trail t = measure(grid, patch, cx - RADIUS, cz - RADIUS, dimension, now, patchesOut);
				if (t != null) found.add(t);
			}
		}
		for (Trail t : found) remember(t);
		return found;
	}

	private static List<int[]> flood(byte[][] grid, boolean[][] seen, int i0, int j0) {
		List<int[]> patch = new ArrayList<>();
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		queue.add(new int[] {i0, j0});
		seen[i0][j0] = true;
		while (!queue.isEmpty()) {
			int[] c = queue.poll();
			patch.add(c);
			for (int di = -LINK; di <= LINK; di++) {
				for (int dj = -LINK; dj <= LINK; dj++) {
					int i = c[0] + di, j = c[1] + dj;
					if (i < 0 || j < 0 || i >= grid.length || j >= grid.length || seen[i][j] || grid[i][j] != OLD) continue;
					seen[i][j] = true;
					queue.add(new int[] {i, j});
				}
			}
		}
		return patch;
	}

	/** The patch's long axis, length and width; a trail if it's long, narrow and has fresh land both sides. */
	private Trail measure(byte[][] grid, List<int[]> patch, int x0, int z0, String dimension, long now, List<int[]> patchesOut) {
		double mx = 0, mz = 0;
		for (int[] c : patch) {
			mx += c[0];
			mz += c[1];
		}
		mx /= patch.size();
		mz /= patch.size();
		double sxx = 0, szz = 0, sxz = 0;
		for (int[] c : patch) {
			double dx = c[0] - mx, dz = c[1] - mz;
			sxx += dx * dx;
			szz += dz * dz;
			sxz += dx * dz;
		}
		double angle = 0.5 * Math.atan2(2 * sxz, sxx - szz);
		double ux = Math.cos(angle), uz = Math.sin(angle);
		double minA = Double.MAX_VALUE, maxA = -Double.MAX_VALUE, minB = Double.MAX_VALUE, maxB = -Double.MAX_VALUE;
		for (int[] c : patch) {
			double a = (c[0] - mx) * ux + (c[1] - mz) * uz, b = -(c[0] - mx) * uz + (c[1] - mz) * ux;
			minA = Math.min(minA, a);
			maxA = Math.max(maxA, a);
			minB = Math.min(minB, b);
			maxB = Math.max(maxB, b);
		}
		double length = maxA - minA + 1, width = maxB - minB + 1;
		// Fresh land beside it, along its length: on one side, the other, and around it at all.
		int left = 0, right = 0, freshNear = 0;
		int reach = (int) Math.ceil(Math.max(length, width) / 2) + 8;
		int ci = (int) Math.round(mx), cj = (int) Math.round(mz);
		for (int i = Math.max(0, ci - reach); i <= Math.min(grid.length - 1, ci + reach); i++) {
			for (int j = Math.max(0, cj - reach); j <= Math.min(grid.length - 1, cj + reach); j++) {
				if (grid[i][j] != NEW) continue;
				double a = (i - mx) * ux + (j - mz) * uz, b = -(i - mx) * uz + (j - mz) * ux;
				if (Math.abs(b) <= width / 2 + 10 && Math.abs(a) <= length / 2 + 4) freshNear++;
				if (a < minA - 1 || a > maxA + 1) continue;
				if (b > maxB + 0.5 && b <= maxB + 10) left++;
				if (b < minB - 0.5 && b >= minB - 10) right++;
			}
		}
		// Old land with no fresh land around it is just explored country, not anyone's path.
		if (freshNear < patch.size() / 2) return null;
		if (!(length >= 8 && length >= 2 * width && left >= 3 && right >= 3)) {
			if (patchesOut != null) patchesOut.add(new int[] {(int) Math.round((x0 + mx) * 16 + 8), (int) Math.round((z0 + mz) * 16 + 8), patch.size()});
			return null;
		}
		Trail t = new Trail();
		t.dimension = dimension;
		t.x1 = (int) Math.round((x0 + mx + ux * minA) * 16 + 8);
		t.z1 = (int) Math.round((z0 + mz + uz * minA) * 16 + 8);
		t.x2 = (int) Math.round((x0 + mx + ux * maxA) * 16 + 8);
		t.z2 = (int) Math.round((z0 + mz + uz * maxA) * 16 + 8);
		t.chunks = patch.size();
		t.width = (int) Math.round(width);
		t.firstSeen = t.lastSeen = now;
		t.away = awayEnd(t);
		return t;
	}

	/** The end clearly further from spawn (by a tenth of the trail's length or more), or 0. */
	static int awayEnd(Trail t) {
		double d1 = Math.hypot(t.x1, t.z1), d2 = Math.hypot(t.x2, t.z2);
		if (Math.abs(d1 - d2) < t.length() * 0.1) return 0;
		return d1 > d2 ? 1 : 2;
	}

	/** Keeps a trail, joining it to one already found if it's the same path (same way, overlapping). */
	private void remember(Trail t) {
		for (Trail old : trails) {
			if (!old.dimension.equals(t.dimension) || !samePath(old, t)) continue;
			// Join them: the two ends furthest apart along the shared direction.
			int[][] ends = {{old.x1, old.z1}, {old.x2, old.z2}, {t.x1, t.z1}, {t.x2, t.z2}};
			double best = -1;
			for (int a = 0; a < 4; a++) {
				for (int b = a + 1; b < 4; b++) {
					double d = Math.hypot(ends[a][0] - ends[b][0], ends[a][1] - ends[b][1]);
					if (d > best) {
						best = d;
						old.x1 = ends[a][0];
						old.z1 = ends[a][1];
						old.x2 = ends[b][0];
						old.z2 = ends[b][1];
					}
				}
			}
			old.chunks = Math.max(old.chunks, t.chunks);
			old.width = Math.max(old.width, t.width);
			old.lastSeen = t.lastSeen;
			old.away = awayEnd(old);
			dirty = true;
			version++;
			return;
		}
		trails.add(t);
		while (trails.size() > 300) trails.remove(0);
		dirty = true;
		version++;
		newTrails.add(t);
	}

	/** Trails found for the first time since last asked (for a chat note). */
	private final List<Trail> newTrails = new ArrayList<>();

	public List<Trail> takeNew() {
		List<Trail> list = new ArrayList<>(newTrails);
		newTrails.clear();
		return list;
	}

	/** Two trails run the same way (within about 25°) and one comes within 80 blocks of the other's line. */
	static boolean samePath(Trail a, Trail b) {
		double ax = a.x2 - a.x1, az = a.z2 - a.z1, bx = b.x2 - b.x1, bz = b.z2 - b.z1;
		double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
		if (la < 1 || lb < 1) return false;
		if (Math.abs((ax * bx + az * bz) / (la * lb)) < 0.9) return false;
		return segmentDistance(a, b.x1, b.z1) <= 80 || segmentDistance(a, b.x2, b.z2) <= 80 || segmentDistance(b, a.x1, a.z1) <= 80
				|| segmentDistance(b, a.x2, a.z2) <= 80;
	}

	/** Distance from a point to a trail's line between its ends. */
	public static double segmentDistance(Trail t, double x, double z) {
		double dx = t.x2 - t.x1, dz = t.z2 - t.z1;
		double len2 = dx * dx + dz * dz;
		double k = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - t.x1) * dx + (z - t.z1) * dz) / len2));
		return Math.hypot(x - (t.x1 + k * dx), z - (t.z1 + k * dz));
	}

	/** The trail nearest a point in a dimension, within {@code max} blocks, or null. */
	public Trail nearest(String dimension, double x, double z, double max) {
		Trail best = null;
		double bestD = max;
		for (Trail t : trails) {
			if (!t.dimension.equals(dimension)) continue;
			double d = segmentDistance(t, x, z);
			if (d < bestD) {
				bestD = d;
				best = t;
			}
		}
		return best;
	}

	// ---- Directions, in words ----

	static final String[] COMPASS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

	/** Compass direction of a step (dx, dz), the game's way: north is -z. */
	public static String compass(double dx, double dz) {
		return COMPASS[(int) Math.floorMod(Math.round(Math.toDegrees(Math.atan2(-dx, dz)) / 45), 8)];
	}

	/** How far to turn to face (dx, dz) from a yaw: positive is right. Within -180..180. */
	public static double turn(double yaw, double dx, double dz) {
		double target = Math.toDegrees(Math.atan2(-dx, dz));
		double d = (target - yaw) % 360;
		if (d > 180) d -= 360;
		if (d < -180) d += 360;
		return d;
	}

	/** "ahead", "ahead-right", "right", "behind-right"..., for a turn. */
	public static String side(double turn) {
		double a = Math.abs(turn);
		String s = turn > 0 ? "right" : "left";
		if (a < 22.5) return "ahead";
		if (a < 67.5) return "ahead-" + s;
		if (a < 112.5) return "to your " + s;
		if (a < 157.5) return "behind-" + s;
		return "behind you";
	}

	/**
	 * What to tell you about the nearest trail: where it is, which way it runs, which end leads away from spawn, and
	 * how to turn to follow it. Two lines, or null if there's no trail within reach.
	 */
	public String[] guidance(String dimension, double x, double z, double yaw) {
		Trail t = nearest(dimension, x, z, RADIUS * 16 * 1.5);
		if (t == null) return null;
		return describe(t, x, z, yaw);
	}

	/** Old and fresh chunks within {@code r} chunks: {old, fresh}. */
	public int[] around(String dimension, int cx, int cz, int r) {
		int old = 0, fresh = 0;
		for (int i = -r; i <= r; i++) {
			for (int j = -r; j <= r; j++) {
				byte s = state(dimension, cx + i, cz + j);
				if (s == OLD) old++;
				else if (s == NEW) fresh++;
			}
		}
		return new int[] {old, fresh};
	}

	private String[] describe(Trail t, double x, double z, double yaw) {
		double d = segmentDistance(t, x, z);
		String runs = compass(t.x2 - t.x1, t.z2 - t.z1) + "–" + compass(t.x1 - t.x2, t.z1 - t.z2);
		String first = String.format(Locale.ROOT, "Trail: %,d blocks long, runs %s", t.length(), runs);
		int[] end = t.awayEnd();
		double dx = end[0] - x, dz = end[1] - z;
		String second;
		if (d <= Math.max(48, t.width * 8)) {
			// On it: which way to go.
			double turn = turn(yaw, dx, dz);
			String way = t.away == 0 ? "Follow it either way" : "Away from spawn: " + compass(dx, dz);
			second = way + (Math.abs(turn) < 15 ? ", straight on" : String.format(Locale.ROOT, ", turn %.0f° %s", Math.abs(turn), turn > 0 ? "right" : "left"));
		} else {
			// Off it: where it is.
			double k = closestK(t, x, z);
			double px = t.x1 + k * (t.x2 - t.x1), pz = t.z1 + k * (t.z2 - t.z1);
			double turn = turn(yaw, px - x, pz - z);
			second = String.format(Locale.ROOT, "%,d blocks %s (%s)", (int) d, side(turn), compass(px - x, pz - z));
		}
		return new String[] {first, second};
	}

	private static double closestK(Trail t, double x, double z) {
		double dx = t.x2 - t.x1, dz = t.z2 - t.z1;
		double len2 = dx * dx + dz * dz;
		return len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - t.x1) * dx + (z - t.z1) * dz) / len2));
	}

	// ---- Saving, per server ----

	/** The chunk file's format: chunks you generated, then old chunks, per dimension. */
	private static final int FORMAT = 0x4E4E4302;
	private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Naughty or Nice chunk saving");
		t.setDaemon(true);
		return t;
	});
	private java.util.concurrent.Future<?> pendingWrite;

	private void waitForWrite() {
		if (pendingWrite == null) return;
		try {
			pendingWrite.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (java.util.concurrent.ExecutionException e) {
			NaughtyOrNiceClient.LOGGER.error("Saving chunks failed", e.getCause());
		}
		pendingWrite = null;
	}

	private static void writeChunks(Path file, Map<String, long[][]> data) {
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(temp)))) {
				out.writeInt(FORMAT);
				out.writeInt(data.size());
				for (Map.Entry<String, long[][]> e : data.entrySet()) {
					out.writeUTF(e.getKey());
					for (long[] list : e.getValue()) {
						out.writeInt(list.length);
						for (long k : list) out.writeLong(k);
					}
				}
			}
			Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not save {}", file, e);
		}
	}

	public void clear() {
		states.clear();
		mine.clear();
		pristine.clear();
		trails = new ArrayList<>();
		newTrails.clear();
		dirty = mineDirty = false;
		version++;
	}

	public void load(Path trailsFile, Path mineFile) {
		waitForWrite();
		clear();
		if (Files.exists(trailsFile)) {
			try (Reader reader = Files.newBufferedReader(trailsFile)) {
				List<Trail> loaded = GSON.fromJson(reader, new TypeToken<List<Trail>>() {}.getType());
				if (loaded != null) {
					loaded.removeIf(t -> t == null || t.dimension == null);
					trails = new ArrayList<>(loaded);
				}
			} catch (IOException | RuntimeException e) {
				NaughtyOrNiceClient.LOGGER.error("Could not read {}", trailsFile, e);
			}
		}
		if (Files.exists(mineFile)) {
			try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(mineFile)))) {
				int first = in.readInt();
				boolean withOld = first == FORMAT;
				int dims = withOld ? in.readInt() : first;
				for (int d = 0; d < dims; d++) {
					String dim = in.readUTF();
					int n = in.readInt();
					LongOpenHashSet set = mine(dim);
					for (int i = 0; i < n; i++) set.add(in.readLong());
					if (!withOld) continue;
					int o = in.readInt();
					Long2ByteOpenHashMap map = states(dim);
					for (int i = 0; i < o; i++) map.put(in.readLong(), OLD);
				}
			} catch (IOException | RuntimeException e) {
				NaughtyOrNiceClient.LOGGER.error("Could not read {}", mineFile, e);
			}
		}
		version++;
	}

	/** Saves the trails, and (at most every 5 minutes, or when forced) the chunks you generated. */
	public void save(Path trailsFile, Path mineFile, boolean force) {
		try {
			if (dirty || force && !trails.isEmpty()) {
				Files.createDirectories(trailsFile.getParent());
				try (Writer writer = Files.newBufferedWriter(trailsFile)) {
					GSON.toJson(trails, writer);
				}
				dirty = false;
			}
			long now = System.currentTimeMillis();
			boolean anything = !mine.isEmpty() || !states.isEmpty();
			if (force && anything || mineDirty && now - lastMineSave > 300_000) {
				// Copied here (quick), written in the background (the slow part), into a temporary file first.
				Map<String, long[][]> copy = new HashMap<>();
				for (String dim : mine.keySet()) copy.put(dim, new long[][] {mine.get(dim).toLongArray(), new long[0]});
				for (Map.Entry<String, Long2ByteOpenHashMap> e : states.entrySet()) {
					LongOpenHashSet old = new LongOpenHashSet();
					e.getValue().long2ByteEntrySet().forEach(en -> {
						if (en.getByteValue() == OLD) old.add(en.getLongKey());
					});
					copy.computeIfAbsent(e.getKey(), d -> new long[][] {new long[0], new long[0]})[1] = old.toLongArray();
				}
				Runnable write = () -> writeChunks(mineFile, copy);
				if (force) {
					waitForWrite();
					write.run();
				} else {
					pendingWrite = WRITER.submit(write);
				}
				mineDirty = false;
				lastMineSave = now;
			}
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not save chunk trails", e);
		}
	}
}
