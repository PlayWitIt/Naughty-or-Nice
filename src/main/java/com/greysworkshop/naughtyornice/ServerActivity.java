package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Collection;

/**
 * How busy a server is at each hour of the week, from your own time on it: once a minute while you're on, how many
 * others are on the tab list. Kept per server next to the lists. Until there's enough of that, the players' own
 * online hours give an estimate by hour of day.
 */
public final class ServerActivity {
	private static final Gson GSON = new GsonBuilder().create();
	/** 7 days (Monday first) × 24 hours. */
	public static final int CELLS = 168;
	/** Minutes of your time an hour needs before its average is shown as measured. */
	public static final int ENOUGH = 10;

	/** Minutes you were on, per hour of the week. */
	public int[] minutes = new int[CELLS];
	/** Players online (not counting you), added up over those minutes. */
	public long[] playerMinutes = new long[CELLS];
	/** Most players seen at once in each hour of the week. */
	public int[] peak = new int[CELLS];
	public long since;
	public long last;
	/** Your own time on this server, in milliseconds (wall clock), since 1.7.0. */
	public long myMs;

	private transient boolean dirty;
	private transient int version;

	public static int cell(DayOfWeek day, int hour) {
		return (day.getValue() - 1) * 24 + hour;
	}

	/** One minute of being on: {@code others} players online besides you. */
	public void sample(int others, LocalDateTime now) {
		int c = cell(now.getDayOfWeek(), now.getHour());
		minutes[c]++;
		playerMinutes[c] += Math.max(0, others);
		peak[c] = Math.max(peak[c], others);
		long t = System.currentTimeMillis();
		if (since == 0) since = t;
		last = t;
		dirty = true;
		version++;
	}

	/** Another stretch of you being on. */
	public void addMyTime(long ms) {
		myMs += ms;
		dirty = true;
	}

	public int version() {
		return version;
	}

	/** Average players online in one hour of the week, or -1 without enough of your time in it. */
	public double average(int cell) {
		return minutes[cell] < ENOUGH ? -1 : playerMinutes[cell] / (double) minutes[cell];
	}

	/** Average by hour of day, all days together; -1 where there isn't enough. */
	public double[] byHour() {
		double[] out = new double[24];
		for (int h = 0; h < 24; h++) {
			long m = 0, p = 0;
			for (int d = 0; d < 7; d++) {
				m += minutes[d * 24 + h];
				p += playerMinutes[d * 24 + h];
			}
			out[h] = m < ENOUGH ? -1 : p / (double) m;
		}
		return out;
	}

	/** Your minutes in each hour of the day, all days together. */
	public int[] minutesByHour() {
		int[] out = new int[24];
		for (int c = 0; c < CELLS; c++) out[c % 24] += minutes[c];
		return out;
	}

	public int peakByHour(int hour) {
		int max = 0;
		for (int d = 0; d < 7; d++) max = Math.max(max, peak[d * 24 + hour]);
		return max;
	}

	public int totalMinutes() {
		int n = 0;
		for (int m : minutes) n += m;
		return n;
	}

	/**
	 * An estimate by hour of day from the players' own online hours, for before there's much measured: in each
	 * hour, all the player-minutes seen divided by the most any one player had there (about as long as you were
	 * on then, since someone usually stays on the whole time). -1 where it can't be told.
	 */
	public static double[] estimate(Collection<Profile> profiles) {
		long[] sum = new long[24];
		int[] most = new int[24];
		for (Profile p : profiles) {
			if (p.hours == null) continue;
			for (int h = 0; h < 24; h++) {
				sum[h] += p.hours[h];
				most[h] = Math.max(most[h], p.hours[h]);
			}
		}
		double[] out = new double[24];
		for (int h = 0; h < 24; h++) out[h] = most[h] < 20 ? -1 : sum[h] / (double) most[h];
		return out;
	}

	/** The run of {@code length} hours (wrapping past midnight) with the most (or least) players, by its first hour; -1 without data. */
	public static int window(double[] byHour, int length, boolean busiest) {
		int best = -1;
		double bestSum = 0;
		for (int start = 0; start < 24; start++) {
			double sum = 0;
			boolean ok = true;
			for (int i = 0; i < length; i++) {
				double v = byHour[(start + i) % 24];
				if (v < 0) {
					ok = false;
					break;
				}
				sum += v;
			}
			if (!ok) continue;
			if (best < 0 || (busiest ? sum > bestSum : sum < bestSum)) {
				best = start;
				bestSum = sum;
			}
		}
		return best;
	}

	/** Average over a window found by {@link #window}. */
	public static double windowAverage(double[] byHour, int start, int length) {
		double sum = 0;
		for (int i = 0; i < length; i++) sum += byHour[(start + i) % 24];
		return sum / length;
	}

	public static ServerActivity load(Path file) {
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				ServerActivity loaded = GSON.fromJson(reader, ServerActivity.class);
				if (loaded != null && loaded.minutes != null && loaded.minutes.length == CELLS && loaded.playerMinutes != null
						&& loaded.playerMinutes.length == CELLS && loaded.peak != null && loaded.peak.length == CELLS) return loaded;
			} catch (IOException | RuntimeException e) {
				NaughtyOrNiceClient.LOGGER.error("Could not read {}", file, e);
			}
		}
		return new ServerActivity();
	}

	public void save(Path file, boolean force) {
		if (!dirty && !force) return;
		if (since == 0) return;
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(this, writer);
			}
			dirty = false;
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not write {}", file, e);
		}
	}
}
