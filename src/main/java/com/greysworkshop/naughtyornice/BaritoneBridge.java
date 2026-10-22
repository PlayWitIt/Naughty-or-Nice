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
