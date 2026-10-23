package com.greysworkshop.naughtyornice;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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

	/** One of Baritone's commands: its names (the first is the one to type), what it does, and how it's used. */
	public record Command(List<String> names, String shortDesc, List<String> longDesc) {
		public String name() {
			return names.get(0);
		}
	}

	/** Every command Baritone has (not the hidden ones), by name; empty without Baritone. */
	public static List<Command> commands() {
		if (!present()) return List.of();
		List<Command> list = new ArrayList<>();
		try {
			Object manager = Class.forName("baritone.api.IBaritone").getMethod("getCommandManager").invoke(baritone());
			Object registry = Class.forName("baritone.api.command.manager.ICommandManager").getMethod("getRegistry").invoke(manager);
			Collection<?> entries = (Collection<?>) registry.getClass().getField("entries").get(registry);
			Class<?> command = Class.forName("baritone.api.command.ICommand");
			for (Object c : entries) {
				if (Boolean.TRUE.equals(command.getMethod("hiddenFromHelp").invoke(c))) continue;
				@SuppressWarnings("unchecked") List<String> names = (List<String>) command.getMethod("getNames").invoke(c);
				@SuppressWarnings("unchecked") List<String> lines = (List<String>) command.getMethod("getLongDesc").invoke(c);
				if (names == null || names.isEmpty()) continue;
				list.add(new Command(List.copyOf(names), String.valueOf(command.getMethod("getShortDesc").invoke(c)), lines == null ? List.of() : List.copyOf(lines)));
			}
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.warn("Couldn't list Baritone's commands", e);
		}
		list.sort(Comparator.comparing(Command::name));
		return list;
	}

	/** What Baritone suggests next for a command line typed so far (as Tab does in chat), e.g. "mine dia" gives diamond_ore. */
	public static List<String> suggest(String line) {
		if (!present()) return List.of();
		try {
			Object manager = Class.forName("baritone.api.IBaritone").getMethod("getCommandManager").invoke(baritone());
			Stream<?> s = (Stream<?>) Class.forName("baritone.api.command.manager.ICommandManager").getMethod("tabComplete", String.class).invoke(manager, line);
			return s.limit(200).map(String::valueOf).map(x -> x.startsWith("minecraft:") ? x.substring(10) : x).distinct().toList();
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return List.of();
		}
	}

	/** One Baritone setting: its name, value and default (as Baritone writes them), and whether it's on/off. */
	public record Setting(String name, String value, String defaultValue, boolean bool) {
		public boolean changed() {
			return !value.equals(defaultValue);
		}
	}

	/** Every Baritone setting you can set in game, by name. */
	public static List<Setting> settings() {
		if (!present()) return List.of();
		List<Setting> list = new ArrayList<>();
		try {
			Object settings = Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
			List<?> all = (List<?>) settings.getClass().getField("allSettings").get(settings);
			Class<?> setting = Class.forName("baritone.api.Settings$Setting");
			Class<?> util = Class.forName("baritone.api.utils.SettingsUtil");
			var valueOf = util.getMethod("settingValueToString", setting);
			var defaultOf = util.getMethod("settingDefaultToString", setting);
			for (Object s : all) {
				if (Boolean.TRUE.equals(setting.getMethod("isJavaOnly").invoke(s))) continue;
				Object value = setting.getField("value").get(s);
				list.add(new Setting(String.valueOf(setting.getMethod("getName").invoke(s)), String.valueOf(valueOf.invoke(null, s)),
						String.valueOf(defaultOf.invoke(null, s)), value instanceof Boolean));
			}
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.warn("Couldn't list Baritone's settings", e);
		}
		list.sort(Comparator.comparing(Setting::name, String.CASE_INSENSITIVE_ORDER));
		return list;
	}

	/** Puts a Baritone setting back to its default. */
	public static void reset(String name) {
		if (!present()) return;
		try {
			Object s = setting(name);
			s.getClass().getMethod("reset").invoke(s);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.warn("Couldn't reset Baritone's {}", name, e);
		}
	}

	// ---- What Baritone says back ----

	/** Something Baritone said (in chat, or as a toast): its words, and whether it said them in red (an error). */
	public record Reply(String text, boolean error) {}

	private static final java.util.Deque<Reply> replies = new java.util.ArrayDeque<>();
	private static int replyCount;
	private static boolean hooked;

	/**
	 * Listens to what Baritone says: everything it writes goes through its "logger" setting, which is wrapped here (still
	 * passing everything on), so the Baritone screen can show its answer to a command.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static synchronized void listen() {
		if (hooked || !present()) return;
		try {
			Object s = setting("logger");
			Field f = s.getClass().getField("value");
			java.util.function.Consumer<Object> original = (java.util.function.Consumer<Object>) f.get(s);
			f.set(s, (java.util.function.Consumer) message -> {
				heard(message);
				original.accept(message);
			});
			hooked = true;
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.warn("Couldn't listen to Baritone", e);
		}
	}

	private static synchronized void heard(Object message) {
		if (!(message instanceof net.minecraft.text.Text text)) return;
		boolean[] red = {false};
		text.visit((style, part) -> {
			if (style.getColor() != null && style.getColor().equals(net.minecraft.text.TextColor.fromFormatting(net.minecraft.util.Formatting.RED))) red[0] = true;
			return java.util.Optional.empty();
		}, net.minecraft.text.Style.EMPTY);
		String words = text.getString().replaceFirst("^\\[Baritone\\]\\s*", "").strip();
		if (words.isEmpty()) return;
		replies.addLast(new Reply(words, red[0]));
		while (replies.size() > 50) replies.removeFirst();
		replyCount++;
	}

	/** How many things Baritone has said so far: pass it to {@link #repliesSince} later. */
	public static synchronized int replyCount() {
		return replyCount;
	}

	/** What Baritone has said since {@code count}, oldest first. */
	public static synchronized List<Reply> repliesSince(int count) {
		int n = Math.min(replies.size(), replyCount - count);
		if (n <= 0) return List.of();
		List<Reply> all = new ArrayList<>(replies);
		return List.copyOf(all.subList(all.size() - n, all.size()));
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
