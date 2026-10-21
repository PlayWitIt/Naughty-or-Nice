package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Runs the mod's hooks so a mistake in them can never take the game down with it. Much of what the mod watches
 * runs while the game handles a packet from the server, and an error there makes the game drop the connection
 * ("Network Protocol Error"). Errors are caught, logged once each, and listed in the chat check report.
 */
public final class Safe {
	private Safe() {}

	/** One kind of error caught: where, what, how often, and when last. */
	public static final class Caught {
		public final String where;
		public final String what;
		public int count;
		public long last;

		Caught(String where, String what) {
			this.where = where;
			this.what = what;
		}
	}

	private static final List<Caught> CAUGHT = new ArrayList<>();
	private static final Set<String> LOGGED = new HashSet<>();
	private static boolean told;

	public static void run(String where, Runnable work) {
		try {
			work.run();
		} catch (RuntimeException | LinkageError | StackOverflowError e) {
			caught(where, e);
		}
	}

	public static <T> T get(String where, Supplier<T> work, T fallback) {
		try {
			return work.get();
		} catch (RuntimeException | LinkageError | StackOverflowError e) {
			caught(where, e);
			return fallback;
		}
	}

	private static synchronized void caught(String where, Throwable e) {
		String what = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
		String key = where + "|" + what;
		Caught entry = null;
		for (Caught c : CAUGHT) {
			if (c.where.equals(where) && c.what.equals(what)) entry = c;
		}
		if (entry == null) {
			entry = new Caught(where, what);
			CAUGHT.add(entry);
			if (CAUGHT.size() > 50) CAUGHT.remove(0);
		}
		entry.count++;
		entry.last = System.currentTimeMillis();
		if (LOGGED.add(key)) NaughtyOrNiceClient.LOGGER.error("Naughty or Nice caught an error in {} (skipped it, play goes on)", where, e);
		if (!told) {
			told = true;
			MinecraftClient client = MinecraftClient.getInstance();
			// Say so once, on the game thread.
			client.execute(() -> Alerts.chat(Text.literal("Hit a problem (" + where + ") and skipped it; nothing else was affected. "
					+ "It's listed in the chat check report (/nn check) if you want to send it in.").formatted(Formatting.GRAY)));
		}
	}

	/** Errors caught so far, oldest first. */
	public static synchronized List<Caught> caught() {
		return new ArrayList<>(CAUGHT);
	}

	/** The newest error caught within {@code millis} of {@code time}, or null. */
	public static synchronized Caught near(long time, long millis) {
		Caught best = null;
		for (Caught c : CAUGHT) {
			if (Math.abs(time - c.last) <= millis && (best == null || c.last > best.last)) best = c;
		}
		return best;
	}
}
