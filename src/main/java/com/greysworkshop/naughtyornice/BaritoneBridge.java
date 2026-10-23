package com.greysworkshop.naughtyornice;

import java.lang.reflect.Field;

/**
 * Talks to Baritone (when it's installed) through its API, not chat, so nothing is ever sent to the server by
 * mistake. Baritone is optional: everything here does nothing (and says so) when it isn't there.
 */
public final class BaritoneBridge {
	private static Boolean present;

	private BaritoneBridge() {}

	/** Whether Baritone (on its own, or inside a client that bundles it) is installed. */
	public static boolean present() {
		if (present == null) {
			try {
				Class.forName("baritone.api.BaritoneAPI");
				present = true;
			} catch (ClassNotFoundException | LinkageError e) {
				present = false;
			}
		}
		return present;
	}

	private static Object baritone() throws ReflectiveOperationException {
		Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
		return Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
	}

	/** Runs a Baritone command (without the "#"), e.g. "goto 100 64 200". Returns whether Baritone took it. */
	public static boolean run(String command) {
		if (!present()) return false;
		try {
			Object b = baritone();
			Object manager = Class.forName("baritone.api.IBaritone").getMethod("getCommandManager").invoke(b);
			Object ok = Class.forName("baritone.api.command.manager.ICommandManager").getMethod("execute", String.class).invoke(manager, command);
			return Boolean.TRUE.equals(ok);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.warn("Baritone command failed: {}", command, e);
			return false;
		}
	}

	/** Whether one of Baritone's processes is running, by its getter, e.g. "getFollowProcess". */
	public static boolean processActive(String getter) {
		if (!present()) return false;
		try {
			Object process = Class.forName("baritone.api.IBaritone").getMethod(getter).invoke(baritone());
			return Boolean.TRUE.equals(Class.forName("baritone.api.process.IBaritoneProcess").getMethod("isActive").invoke(process));
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return false;
		}
	}

	/** For the self-test: what Baritone's own block scanner finds of {@code block} around you, as the farm uses it. */
	static Object scan(net.minecraft.block.Block block) {
		try {
			Object b = baritone();
			Object ctx = Class.forName("baritone.api.IBaritone").getMethod("getPlayerContext").invoke(b);
			Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
			Object scanner = Class.forName("baritone.api.IBaritoneProvider").getMethod("getWorldScanner").invoke(provider);
			return Class.forName("baritone.api.cache.IWorldScanner").getMethod("scanChunkRadius", Class.forName("baritone.api.utils.IPlayerContext"),
					java.util.List.class, int.class, int.class, int.class).invoke(scanner, ctx, java.util.List.of(block), 256, 10, 10);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return "unreadable: " + e;
		}
	}

	private static Object pathing() throws ReflectiveOperationException {
		return Class.forName("baritone.api.IBaritone").getMethod("getPathingBehavior").invoke(baritone());
	}

	/** Whether Baritone is moving along a path right now. */
	public static boolean isPathing() {
		if (!present()) return false;
		try {
			return Boolean.TRUE.equals(Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("isPathing").invoke(pathing()));
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return false;
		}
	}

	/** Baritone's goal, in its own words ("GoalBlock{x=1,y=2,z=3}"), or "" if it has none. */
	public static String goal() {
		if (!present()) return "";
		try {
			Object goal = Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("getGoal").invoke(pathing());
			return goal == null ? "" : goal.toString();
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return "";
		}
	}

	private static Object setting(String name) throws ReflectiveOperationException {
		Object settings = Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
		Field f = settings.getClass().getField(name);
		return f.get(settings);
	}

	/**
	 * What Baritone catches in chat ("#" unless changed), or null when it won't catch anything there (no Baritone, or
	 * its chat control is off), so a message starting with it really would be sent.
	 */
	public static String chatPrefix() {
		if (!present() || !Boolean.TRUE.equals(bool("prefixControl"))) return null;
		try {
			Object s = setting("prefix");
			Object v = s.getClass().getField("value").get(s);
			return v instanceof String p && !p.isEmpty() ? p : null;
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return null;
		}
	}

	/** A true/false Baritone setting's value, or null if there's no such setting (or no Baritone). */
	public static Boolean bool(String name) {
		if (!present()) return null;
		try {
			Object s = setting(name);
			Object v = s.getClass().getField("value").get(s);
			return v instanceof Boolean b ? b : null;
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return null;
		}
	}

	/** Sets a true/false Baritone setting directly (no chat). Returns whether it worked. */
	public static boolean setBool(String name, boolean value) {
		if (!present()) return false;
		try {
			Object s = setting(name);
			Field f = s.getClass().getField("value");
			if (!(f.get(s) instanceof Boolean)) return false;
			f.set(s, value);
			return true;
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return false;
		}
	}
}
