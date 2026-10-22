package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stops you giving your own location away by accident. Before a chat message or a whisper leaves, it's read the same
 * strict way other players' chat is: coordinates in it, the name of one of your bases, or a number that's your own
 * current x or z. If any are there, it's held back, with [Send anyway] and [Edit].
 */
public final class CoordGuard {
	/** Commands that send what you type to other players. */
	private static final Set<String> TALK = Set.of("msg", "tell", "w", "whisper", "r", "reply", "m", "pm", "dm", "me", "say", "teammsg", "tm", "mail",
			"ac", "pc", "partychat", "gc", "cc");
	private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?\\d{3,8}(?![\\w.])");

	/** The message (or command, with a leading "/") held back, until sent anyway. */
	private static String held;
	/** Let this exact one through once (you pressed Send anyway). */
	private static String allowOnce;

	private CoordGuard() {}

	public static boolean allowChat(String message) {
		// Baritone reads "#goto 1200 -3400" itself and never sends it, when it's installed.
		if (message.startsWith("#") && BaritoneBridge.present()) return true;
		return allow(message, message);
	}

	public static boolean allowCommand(String command) {
		String[] parts = command.split(" ", 2);
		if (parts.length < 2 || !TALK.contains(parts[0].toLowerCase(Locale.ROOT))) return true;
		return allow("/" + command, parts[1]);
	}

	private static boolean allow(String whole, String said) {
		if (!NNConfig.INSTANCE.guardMyCoords) return true;
		if (whole.equals(allowOnce)) {
			allowOnce = null;
			return true;
		}
		List<String> why = reasons(said);
		if (why.isEmpty()) return true;
		held = whole;
		Alerts.chat(Text.literal("Held your message: it gives away " + String.join(" and ", why) + ". ").formatted(Formatting.GOLD)
				.append(Text.literal("[Send anyway]").styled(s -> s.withColor(Formatting.RED).withUnderline(true)
						.withClickEvent(new ClickEvent.RunCommand("/nn sendanyway"))))
				.append(Text.literal(" "))
				.append(Text.literal("[Edit]").styled(s -> s.withColor(Formatting.AQUA).withUnderline(true)
						.withClickEvent(new ClickEvent.SuggestCommand(whole)))));
		return false;
	}

	/** What a message would give away; empty if nothing. */
	static List<String> reasons(String said) {
		List<String> why = new ArrayList<>();
		List<ChatIntel.Coord> coords = ChatIntel.coords(said);
		if (!coords.isEmpty()) why.add("coordinates (" + coords.get(0).text() + ")");
		String lower = said.toLowerCase(Locale.ROOT);
		for (BaseInsuranceBridge.BaseArea base : BaseInsuranceBridge.bases()) {
			String n = base.name().toLowerCase(Locale.ROOT);
			if (n.length() >= 3 && Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(n) + "(?![a-z0-9_])").matcher(lower).find()) {
				why.add("your base's name (" + base.name() + ")");
				break;
			}
		}
		// "im at 4500": one number that's where you are right now.
		MinecraftClient client = MinecraftClient.getInstance();
		if (coords.isEmpty() && client.player != null) {
			Matcher m = NUMBER.matcher(said);
			while (m.find()) {
				int n = Integer.parseInt(m.group());
				if (Math.abs(n) >= 500 && (Math.abs(n - client.player.getBlockX()) <= 64 || Math.abs(n - client.player.getBlockZ()) <= 64)) {
					why.add("your own " + (Math.abs(n - client.player.getBlockX()) <= 64 ? "x" : "z") + " (" + n + ")");
					break;
				}
			}
		}
		return why;
	}

	/** You pressed Send anyway: sends the held message, once. */
	public static boolean sendHeld() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (held == null || client.getNetworkHandler() == null) return false;
		String message = held;
		held = null;
		allowOnce = message;
		if (message.startsWith("/")) client.getNetworkHandler().sendChatCommand(message.substring(1));
		else client.getNetworkHandler().sendChatMessage(message);
		return true;
	}
}
