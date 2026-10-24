package com.greysworkshop.naughtyornice;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LazyEntityReference;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches what other players do around you and writes it into their profiles: hitting or killing you, your pets
 * or other players, breaking blocks in your bases (with Base Insurance), friendly or rude chat, and peaceful time
 * spent nearby. Also warns when a Naughty-list player shows up.
 */
public final class Watcher {
	private static final long KILL_WINDOW = 5000;
	private static final long SAME_KILL = 8000;

	private final Ledger ledger;
	private final ChatLogger chatLogger;
	private final Diagnostics diagnostics;

	/** Last player to hurt each entity, by entity id, for working out who killed it. */
	private final Map<Integer, Hurt> lastHurt = new HashMap<>();
	/** Recent kills (victim, killer), so the death packet and the death message don't count twice. */
	private final Map<String, Long> recentKills = new HashMap<>();
	private final Map<UUID, Long> lastVisible = new HashMap<>();
	private final Map<String, Long> lastBaseVisit = new HashMap<>();
	private Set<UUID> online = new HashSet<>();
	private boolean onlineKnown;
	private int tick;
	/** Seconds since joining this server, for the join summary. */
	private int secondsOn;
	/** Recent alerts (sender and reason), so one message isn't alerted twice. */
	private final Map<String, Long> recentAlerts = new HashMap<>();

	private record Hurt(UUID attacker, String attackerName, long time) {}

	/** Where a player in view was a second ago, and their speed over the two seconds before. */
	private record Moved(String dimension, double x, double y, double z, long time, double speed1, double speed2) {}

	/** Wall-clock time of the last once-a-second pass, so time adds up right even if the game stalls. */
	private long lastSecond;
	/** Each online player's current session. */
	private final Map<UUID, PlayerStats.Session> sessions = new HashMap<>();
	private final Map<UUID, Moved> moved = new HashMap<>();
	/** When each player last came into view. */
	private final Map<UUID, Long> arrivals = new HashMap<>();

	public Watcher(Ledger ledger, ChatLogger chatLogger, Diagnostics diagnostics) {
		this.ledger = ledger;
		this.chatLogger = chatLogger;
		this.diagnostics = diagnostics;
	}

	public void reset() {
		lastHurt.clear();
		recentKills.clear();
		TotemCounter.clear();
		LogoutSpots.clear();
		lastVisible.clear();
		lastBaseVisit.clear();
		online = new HashSet<>();
		onlineKnown = false;
		secondsOn = 0;
		recentAlerts.clear();
		lastSecond = 0;
		sessions.clear();
		moved.clear();
		arrivals.clear();
		witherSummoners.clear();
		recentWithers.clear();
	}

	// ---- Recording ----

	private Profile profile(PlayerEntity player) {
		return ledger.getOrCreate(player.getUuid(), player.getGameProfile().name());
	}

	/** Records an action and saves. Hostile acts toward you reset their peaceful time. */
	public Profile.Action record(Profile profile, Profile.Kind kind, String detail) {
		Profile.Action action = profile.record(kind, detail, NNConfig.INSTANCE.maxActions);
		profile.lastSeen = System.currentTimeMillis();
		if (kind == Profile.Kind.HIT_YOU || kind == Profile.Kind.KILLED_YOU || kind == Profile.Kind.HURT_PET
				|| kind == Profile.Kind.KILLED_PET || kind == Profile.Kind.BROKE_IN_BASE) {
			profile.peacefulSeconds = 0;
			profile.lastHostile = profile.lastSeen;
		}
		ledger.markDirty();
		return action;
	}

	private static boolean isMe(MinecraftClient client, Entity entity) {
		return client.player != null && entity != null && entity.getUuid().equals(client.player.getUuid());
	}

	/** A player other than you, or null. */
	private static PlayerEntity otherPlayer(MinecraftClient client, Entity entity) {
		return entity instanceof PlayerEntity player && !isMe(client, player) ? player : null;
	}

	private static boolean myPet(MinecraftClient client, Entity entity) {
		if (!(entity instanceof Tameable tameable) || client.player == null) return false;
		LazyEntityReference<LivingEntity> owner = tameable.getOwnerReference();
		return owner != null && client.player.getUuid().equals(owner.getUuid());
	}

	// ---- Packet hooks (called on the game thread) ----

	/** An entity took damage. {@code attacker} is whoever is responsible (the shooter, for arrows). */
	public void onDamage(MinecraftClient client, Entity target, Entity attacker) {
		PlayerEntity player = otherPlayer(client, attacker);
		if (player == null || target == null || target == attacker) return;
		Profile profile = profile(player);
		lastHurt.put(target.getId(), new Hurt(player.getUuid(), profile.name, System.currentTimeMillis()));
		if (isMe(client, target)) {
			record(profile, Profile.Kind.HIT_YOU, "");
		} else if (myPet(client, target)) {
			record(profile, Profile.Kind.HURT_PET, target.getName().getString());
		} else if (target instanceof PlayerEntity victim) {
			record(profile, Profile.Kind.HIT_PLAYER, victim.getGameProfile().name());
		}
	}

	/** A living entity died in view (including you). */
	public void onDeath(MinecraftClient client, Entity victim) {
		if (victim == null) return;
		if (victim instanceof PlayerEntity p) TotemCounter.died(p.getUuid());
		Hurt hurt = lastHurt.remove(victim.getId());
		if (hurt == null || System.currentTimeMillis() - hurt.time > KILL_WINDOW) return;
		if (isMe(client, victim)) {
			killedMe(hurt.attacker, hurt.attackerName);
		} else if (myPet(client, victim)) {
			Profile profile = ledger.get(hurt.attacker);
			if (profile != null) record(profile, Profile.Kind.KILLED_PET, victim.getName().getString());
		} else if (victim instanceof PlayerEntity player) {
			killedPlayer(hurt.attacker, hurt.attackerName, player.getGameProfile().name());
		}
	}

	/** Someone (maybe you) broke or is breaking a block. */
	public void onBlockBreaking(MinecraftClient client, Entity breaker, BlockPos pos) {
		PlayerEntity player = otherPlayer(client, breaker);
		if (player == null || client.world == null) return;
		String base = BaseInsuranceBridge.baseAt(client.world.getRegistryKey().getValue().toString(), pos);
		if (base != null) record(profile(player), Profile.Kind.BROKE_IN_BASE, base);
	}

	private void killedMe(UUID killer, String killerName) {
		if (seenKill("me", killerName)) return;
		Profile profile = ledger.getOrCreate(killer, killerName);
		record(profile, Profile.Kind.KILLED_YOU, "");
		PhoneAlerts.send(profile.name + " killed you.");
		EvidenceShots.take(MinecraftClient.getInstance(), profile, "Killed you");
		if (NNConfig.INSTANCE.autoNaughtyKillers && profile.verdict == Profile.Verdict.NONE) {
			ledger.setVerdict(profile, Profile.Verdict.NAUGHTY, true, "killed you");
			Alerts.chat(Text.literal(profile.name).formatted(Formatting.RED)
					.append(Text.literal(" killed you, so they're on your Naughty list now. ").formatted(Formatting.GRAY))
					.append(Text.literal("[Undo]").styled(s -> s.withColor(Formatting.AQUA).withUnderline(true)
							.withClickEvent(new ClickEvent.RunCommand("/nn unsort " + profile.name)))));
		}
	}

	private void killedPlayer(UUID killer, String killerName, String victim) {
		if (seenKill(victim, killerName)) return;
		Profile profile = ledger.getOrCreate(killer, killerName);
		record(profile, Profile.Kind.KILLED_PLAYER, victim);
		PlayerStats.bump(profile.stats.victims, victim, 1);
	}

	/**
	 * Someone died, by the server's death message (sent once per death, to everyone): counts towards their deaths,
	 * and who killed them with what. {@code killer} is null when no player did it.
	 */
	private void died(MinecraftClient client, String victim, String killer, String weapon, String message, String cause) {
		if (victim != null) {
			// Where it happened, if the victim was in view.
			EventLog.Entry death = EventLog.entry(EventLog.Type.DEATH, killer == null ? "" : killer, victim,
					(weapon == null || weapon.isBlank() ? "" : "with " + weapon.replaceAll("^\\[(.*)]$", "$1") + " · ") + message);
			death.dim = client.world.getRegistryKey().getValue().toString();
			PlayerEntity seen = null;
			for (PlayerEntity p : client.world.getPlayers()) {
				if (p.getGameProfile().name().equalsIgnoreCase(victim)) seen = p;
			}
			if (seen != null) {
				death.x = seen.getX();
				death.y = seen.getY();
				death.z = seen.getZ();
			}
			EventLog.add(death);
		}
		if (victim == null || victim.equalsIgnoreCase(client.player.getGameProfile().name())) return;
		UUID id = playerId(client, victim);
		TotemCounter.died(id);
		long now = System.currentTimeMillis();
		if (id != null) {
			PlayerStats st = ledger.getOrCreate(id, victim).stats;
			st.deaths++;
			st.lastDeath = message.length() > 120 ? message.substring(0, 120) + "…" : message;
			st.lastDeathTime = now;
			PlayerStats.bump(st.deathCauses, PlayerStats.deathCause(cause, killer != null), 1);
			if (killer != null) {
				st.deathsToPlayers++;
				PlayerStats.bump(st.killedBy, killer, 1);
			}
			ledger.markDirty();
		}
		Profile k = killer == null ? null : ledger.byName(killer);
		if (k != null && weapon != null && !weapon.isBlank()) {
			// The game shows the item as "[Diamond Sword]".
			PlayerStats.bump(k.stats.weapons, weapon.replaceAll("^\\[(.*)]$", "$1"), 1);
			ledger.markDirty();
		}
	}

	/** Another player used up a Totem of Undying in view. */
	public void onTotem(MinecraftClient client, Entity entity) {
		if (!(entity instanceof PlayerEntity player)) return;
		if (!isMe(client, player)) {
			PlayerStats st = profile(player).stats;
			st.totemPops++;
			st.lastTotemPop = System.currentTimeMillis();
			ledger.markDirty();
			TotemCounter.popped(player.getUuid());
		}
		// Yours too, for the death recap.
		EventLog.Entry e = EventLog.entry(EventLog.Type.TOTEM, player.getGameProfile().name(), "", "");
		e.dim = client.world.getRegistryKey().getValue().toString();
		e.x = player.getX();
		e.y = player.getY();
		e.z = player.getZ();
		EventHooks.afterHealthArrives(e, player);
	}

	private boolean seenKill(String victim, String killer) {
		long now = System.currentTimeMillis();
		recentKills.values().removeIf(t -> now - t > SAME_KILL);
		String key = victim.toLowerCase(Locale.ROOT) + ">" + killer.toLowerCase(Locale.ROOT);
		return recentKills.put(key, now) != null;
	}

	// ---- Chat ----

	private static final String NAME = "([.*]?[A-Za-z0-9_]{1,16})";
	private static final String TAGS = "(?:\\[[^\\]]{1,32}\\]\\s*)*";
	/** "&lt;name&gt; hi", "&lt;[VIP] name&gt; hi", "[G] &lt;name&gt; hi". */
	private static final Pattern ANGLED = Pattern.compile("^" + TAGS + "<(?:[^>]*\\s)?" + NAME + "> (.+)$");
	/** "name: hi", "[VIP] name » hi", "Rank name > hi". */
	private static final Pattern SEPARATED = Pattern.compile("^" + TAGS + "(?:[^\\s:»>|\\[\\]]{1,16}\\s)?" + NAME + "\\s?(?::|»|>>|->|\\|)\\s(.+)$");
	private static final Pattern[] WHISPER_IN = {
			Pattern.compile("^" + NAME + " whispers(?: to you)?: (.+)$"),
			Pattern.compile("^[\\[(]" + NAME + " (?:->|→) (?:me|you)[\\])]:? (.+)$"),
			Pattern.compile("^From " + NAME + ":? (.+)$"),
	};
	private static final Pattern[] WHISPER_OUT = {
			Pattern.compile("^You whisper to " + NAME + ": (.+)$"),
			Pattern.compile("^[\\[(](?:me|you) (?:->|→) " + NAME + "[\\])]:? (.+)$"),
			Pattern.compile("^To " + NAME + ":? (.+)$"),
	};

	/** Signed player chat (including /msg and /me). */
	public void onChat(MinecraftClient client, GameProfile sender, SignedMessage signed, MessageType.Parameters params, Text message) {
		if (client.player == null) return;
		String text = signed != null ? signed.getSignedContent() : bodyOf(message);
		boolean incoming = params != null && (params.type().matchesKey(MessageType.MSG_COMMAND_INCOMING) || params.type().matchesKey(MessageType.TEAM_MSG_COMMAND_INCOMING));
		boolean outgoing = params != null && (params.type().matchesKey(MessageType.MSG_COMMAND_OUTGOING) || params.type().matchesKey(MessageType.TEAM_MSG_COMMAND_OUTGOING));
		if (outgoing) {
			String target = params.targetName().map(Text::getString).orElse("");
			diagnostics.understood(Diagnostics.Kind.WHISPER, message.getString(), "you whispered to " + target + ": " + text);
			chatLine(client, null, client.player.getGameProfile().name(), text, true, target);
			return;
		}
		String name = sender != null ? sender.name() : params != null ? params.name().getString() : "?";
		diagnostics.understood(incoming ? Diagnostics.Kind.WHISPER : Diagnostics.Kind.CHAT, message.getString(),
				name + (incoming ? " whispered: " : " said: ") + text);
		chatLine(client, sender == null ? null : sender.id(), name, text, incoming, null);
	}

	/** The message part of a decorated chat line ("chat.type.text" with name and message), or the whole line. */
	private static String bodyOf(Text message) {
		if (message.getContent() instanceof TranslatableTextContent t && t.getArgs().length >= 2) return argString(t.getArgs()[1]);
		String s = message.getString();
		Matcher m = ANGLED.matcher(s);
		return m.matches() ? m.group(2) : s;
	}

	/**
	 * System messages: death messages, join/leave, and chat or whispers on servers that send them as plain text
	 * ("&lt;name&gt; text", "[Rank] name » text", "name whispers: text"...).
	 */
	public void onGameMessage(MinecraftClient client, Text message) {
		if (client.player == null) return;
		String raw = message.getString();
		// Messages from this mod and its companions, shown only to you.
		if (raw.startsWith("[Naughty/Nice] ") || raw.contains("[Base Insurance] ") || raw.contains("[Hoard Eye] ")) return;
		if (message.getContent() instanceof TranslatableTextContent t) {
			String key = t.getKey();
			Object[] args = t.getArgs();
			if (key.startsWith("death.")) {
				String victim = args.length >= 1 ? playerArg(args[0]) : null;
				String killer = args.length >= 2 ? playerArg(args[1]) : null;
				UUID killerId = killer == null ? null : playerId(client, killer);
				boolean byPlayer = victim != null && killerId != null && !killer.equalsIgnoreCase(victim);
				// "X was slain by Y using [Z]": the weapon is the last argument.
				String weapon = byPlayer && key.endsWith(".item") && args.length >= 3 ? argString(args[2]) : null;
				died(client, victim, byPlayer ? killer : null, weapon, raw, key);
				if (byPlayer) {
					diagnostics.understood(Diagnostics.Kind.KILL, raw, killer + " killed " + victim);
					if (victim.equalsIgnoreCase(client.player.getGameProfile().name())) killedMe(killerId, killer);
					else killedPlayer(killerId, killer, victim);
					return;
				}
				diagnostics.understood(Diagnostics.Kind.DEATH, raw, (victim != null ? victim : args.length > 0 ? argString(args[0]) : "someone") + " died (no player killer)");
				return;
			}
			if ((key.startsWith("multiplayer.player.joined") || key.equals("multiplayer.player.left")) && args.length >= 1) {
				boolean joined = key.startsWith("multiplayer.player.joined");
				diagnostics.understood(Diagnostics.Kind.JOIN_LEAVE, raw, argString(args[0]) + (joined ? " joined" : " left"));
				chatLogger.system(ledger.byName(argString(args[0])), joined ? "joined the game" : "left the game", System.currentTimeMillis());
				return;
			}
			if (key.startsWith("chat.type.team.") && args.length >= 3) {
				String who = argString(args[1]);
				diagnostics.understood(Diagnostics.Kind.CHAT, raw, who + " said (team): " + argString(args[2]));
				chatLine(client, null, who, argString(args[2]), false, null);
				return;
			}
			if (args.length >= 2) {
				String who = argString(args[0]);
				String body = argString(args[1]);
				switch (key) {
					case "chat.type.text", "chat.type.announcement", "chat.type.emote" -> {
						diagnostics.understood(Diagnostics.Kind.CHAT, raw, who + " said: " + body);
						chatLine(client, null, who, body, false, null);
						return;
					}
					case "commands.message.display.incoming" -> {
						diagnostics.understood(Diagnostics.Kind.WHISPER, raw, who + " whispered: " + body);
						chatLine(client, null, who, body, true, null);
						return;
					}
					case "commands.message.display.outgoing" -> {
						diagnostics.understood(Diagnostics.Kind.WHISPER, raw, "you whispered to " + who + ": " + body);
						chatLine(client, null, client.player.getGameProfile().name(), body, true, who);
						return;
					}
					default -> {}
				}
			}
			if (key.startsWith("chat.type.advancement.") && args.length >= 2) {
				String who = playerArg(args[0]);
				UUID id = who == null ? null : playerId(client, who);
				String what = argString(args[1]).replaceAll("^\\[(.*)]$", "$1");
				diagnostics.understood(Diagnostics.Kind.IGNORED, raw, who + " made an advancement: " + what);
				if (id != null && !id.equals(client.player.getUuid())) {
					Profile p = ledger.getOrCreate(id, who);
					PlayerStats.note(p.stats.advancements, what, 80);
					EventLog.add(EventLog.entry(EventLog.Type.ADVANCEMENT, p.name, "", what));
					if (hasKey(args[1], "advancements.nether.summon_wither.title") || what.equalsIgnoreCase("Withering Heights")) summonedWither(p, System.currentTimeMillis());
					ledger.markDirty();
				}
				return;
			}
			if (key.startsWith("chat.type.advancement") || key.startsWith("commands.") || key.startsWith("multiplayer.") || key.startsWith("sleep.")) {
				diagnostics.understood(Diagnostics.Kind.IGNORED, raw, "game message (" + key + ")");
				return;
			}
		}

		String line = raw;
		String me = client.player.getGameProfile().name();
		for (Pattern p : WHISPER_OUT) {
			Matcher m = p.matcher(line);
			if (m.matches()) {
				diagnostics.understood(Diagnostics.Kind.WHISPER, raw, "you whispered to " + m.group(1) + ": " + m.group(2));
				chatLine(client, null, me, m.group(2), true, m.group(1));
				return;
			}
		}
		for (Pattern p : WHISPER_IN) {
			Matcher m = p.matcher(line);
			if (m.matches() && playerId(client, m.group(1)) != null) {
				diagnostics.understood(Diagnostics.Kind.WHISPER, raw, m.group(1) + " whispered: " + m.group(2));
				chatLine(client, null, m.group(1), m.group(2), true, null);
				return;
			}
		}
		Matcher m = ANGLED.matcher(line);
		if (m.matches()) {
			diagnostics.understood(Diagnostics.Kind.CHAT, raw, m.group(1) + " said: " + m.group(2));
			chatLine(client, null, m.group(1), m.group(2), false, null);
			return;
		}
		m = SEPARATED.matcher(line);
		// Looser format: only trust it when the name is a real player.
		if (m.matches() && (playerId(client, m.group(1)) != null || m.group(1).equalsIgnoreCase(me))) {
			diagnostics.understood(Diagnostics.Kind.CHAT, raw, m.group(1) + " said: " + m.group(2));
			chatLine(client, null, m.group(1), m.group(2), false, null);
			return;
		}
		if (plainDeath(client, line)) return;
		m = PLAIN_JOIN.matcher(line);
		if (m.matches() && playerId(client, m.group(1)) != null) {
			boolean joined = !m.group(2).toLowerCase(Locale.ROOT).matches(".*\\b(left|quit|disconnected|logged out|logged off)\\b.*");
			diagnostics.understood(Diagnostics.Kind.JOIN_LEAVE, raw, m.group(1) + (joined ? " joined" : " left"));
			chatLogger.system(ledger.byName(m.group(1)), joined ? "joined the game" : "left the game", System.currentTimeMillis());
			return;
		}
		offerShareLink(null, raw);
		// Decoration ("---- ----"), and the welcome text servers (and mods like JEI) send as you join: no player in it.
		if (DECORATION.matcher(raw).matches()) {
			diagnostics.understood(Diagnostics.Kind.IGNORED, raw, "decoration");
			return;
		}
		if (client.player.age < 400 && !namesPlayer(client, raw)) {
			diagnostics.understood(Diagnostics.Kind.IGNORED, raw, "a notice as you joined");
			return;
		}
		diagnostics.notUnderstood(raw, onlineNames(client));
	}

	/** A line of only symbols and spaces. */
	private static final Pattern DECORATION = Pattern.compile("^[\\p{Punct}\\p{So}\\s]+$");

	/** Whether a line has a player's name in it. */
	private boolean namesPlayer(MinecraftClient client, String line) {
		Matcher w = WORD.matcher(line);
		while (w.find()) {
			if (playerId(client, w.group()) != null || w.group().equalsIgnoreCase(client.player.getGameProfile().name())) return true;
		}
		return false;
	}

	/** Share links already offered, so a repeated message doesn't offer one twice. */
	private final Set<String> offeredLinks = new HashSet<>();

	boolean offered(String link) {
		return offeredLinks.contains(link);
	}

	/** A link you made: never offered back to you when your own message shows up. */
	public void ownLink(String link) {
		offeredLinks.add(link);
	}

	/** Someone sent a share link: a button to look at it (nothing is imported until you say so). */
	void offerShareLink(String from, String text) {
		String link = CloudShare.find(text);
		if (link == null || !NNConfig.INSTANCE.openSharedLinks || !offeredLinks.add(link)) return;
		Profile sender = from == null ? null : ledger.byName(from);
		if (sender != null && sender.verdict == Profile.Verdict.NAUGHTY) return;
		Alerts.chat(Text.literal((from == null ? "Someone" : from) + " shared Naughty or Nice lists. ").formatted(Formatting.GRAY)
				.append(Text.literal("[Look at them]").styled(st -> st.withColor(Formatting.AQUA).withUnderline(true)
						.withClickEvent(new ClickEvent.RunCommand("/nn open " + link)))));
	}

	/** "Steve left the game", "Steve joined", "Steve has quit", as some servers word them in plain text. */
	private static final Pattern PLAIN_JOIN = Pattern.compile("^(?:\\W+\\s*)?" + "([A-Za-z0-9_]{1,16})" + " (?:has )?(joined|left|quit|logged (?:in|out|on|off)|disconnected|connected)(?: the (?:game|server))?[.!]?$");
	/** Words death messages use, worded by the game or by server plugins. */
	private static final Pattern DEATH_WORDS = Pattern.compile("\\b(slain|killed|shot|blown up|fireballed|pummeled|pummelled|squashed|impaled|stung|skewered|"
			+ "obliterated|destroyed|murdered|finished off|pricked|burnt|burned|struck|knocked|doomed|fell|drowned|withered|frozen|poked|squished|"
			+ "died|blew up|was killed|went off|tried to swim|walked into|experienced kinetic|suffocated|starved|exploded|eliminated|clapped|ended|"
			+ "popped|crystalled|crystaled|wrecked)\\b");

	/** Joke death messages plugins use, like constantiam.net's "Steve became epstein'd". */
	private static final Pattern BECAME_D = Pattern.compile("^became\\s+[a-z]+'d\\b");

	/**
	 * A death message the server sent as plain text (plugins often reword them): it starts with a player's name and
	 * uses a death word. If another player is named after it, they're the killer. Returns whether it was one.
	 */
	private boolean plainDeath(MinecraftClient client, String line) {
		Matcher words = WORD.matcher(line);
		if (!words.find() || words.start() > 4) return false;
		String victim = words.group();
		if (playerId(client, victim) == null && !victim.equalsIgnoreCase(client.player.getGameProfile().name())) return false;
		String rest = line.substring(words.end());
		// The death wording comes straight after the name ("Steve was slain by", "Steve fell from"), within a few words.
		String[] next = rest.strip().toLowerCase(Locale.ROOT).split("\\s+", 5);
		String head = String.join(" ", java.util.Arrays.copyOf(next, Math.min(next.length, 4)));
		if (!DEATH_WORDS.matcher(head).find() && !BECAME_D.matcher(head).find()) return false;
		String killer = null;
		UUID killerId = null;
		while (words.find()) {
			UUID id = playerId(client, words.group());
			if (id != null && !words.group().equalsIgnoreCase(victim)) {
				killer = words.group();
				killerId = id;
				break;
			}
		}
		died(client, victim, killer, null, line, rest);
		if (killerId == null) {
			diagnostics.understood(Diagnostics.Kind.DEATH, line, victim + " died (no player killer)");
			return true;
		}
		diagnostics.understood(Diagnostics.Kind.KILL, line, killer + " killed " + victim);
		if (victim.equalsIgnoreCase(client.player.getGameProfile().name())) killedMe(killerId, killer);
		else killedPlayer(killerId, killer, victim);
		return true;
	}

	private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]{3,16}");

	/** Lower-case names of everyone online, to blank out of lines the chat check couldn't read. */
	private static Set<String> onlineNames(MinecraftClient client) {
		Set<String> names = new HashSet<>();
		if (client.getNetworkHandler() != null) {
			for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) names.add(entry.getProfile().name().toLowerCase(Locale.ROOT));
		}
		if (client.player != null) names.add(client.player.getGameProfile().name().toLowerCase(Locale.ROOT));
		return names;
	}

	/**
	 * One line of chat from anyone. {@code whisperTarget} is who you whispered, for private messages you sent.
	 * Feeds the player's recent chat and rating, and the chat logger.
	 */
	private void chatLine(MinecraftClient client, UUID senderId, String sender, String text, boolean whisper, String whisperTarget) {
		long now = System.currentTimeMillis();
		EventLog.add(EventLog.entry(whisper ? EventLog.Type.WHISPER : EventLog.Type.CHAT, sender, whisperTarget == null ? "" : whisperTarget, text));
		boolean me = (senderId != null && senderId.equals(client.player.getUuid()))
				|| sender.equalsIgnoreCase(client.player.getGameProfile().name());
		if (me) {
			Profile target = null;
			if (whisperTarget != null) {
				UUID id = playerId(client, whisperTarget);
				target = id == null ? null : ledger.getOrCreate(id, whisperTarget);
				if (target != null) target.stats.whispersFromYou++;
			}
			chatLogger.heard(target, whisperTarget != null ? (target != null ? target.name : whisperTarget) : sender, text, true, whisper, now);
			return;
		}
		UUID id = senderId != null ? senderId : playerId(client, sender);
		Profile profile = id == null ? null : ledger.getOrCreate(id, sender);
		offerShareLink(profile != null ? profile.name : sender, text);
		if (profile != null) {
			PlayerStats st = profile.stats;
			st.messages++;
			if (whisper) st.whispersToYou++;
			st.words += text.isBlank() ? 0 : text.strip().split("\\s+").length;
			if (st.firstMessage == 0) st.firstMessage = now;
			st.lastMessage = now;
			chatAnalytics(client, profile, text);
			said(profile, text);
		}
		String alert = alertFor(client, text, whisper);
		String name = profile != null ? profile.name : sender;
		// You asked to hear whenever they talk (unless the line already sets off an alert of its own).
		if (profile != null && profile.notifyChat && alert == null) {
			Alerts.ping(Text.literal(name).formatted(verdictFormat(profile), Formatting.BOLD)
					.append(Text.literal(whisper ? " whispered: " : ": ").formatted(Formatting.GRAY))
					.append(Text.literal(text.length() > 100 ? text.substring(0, 100) + "…" : text).formatted(Formatting.WHITE)), Alerts.Sound.WATCHED_TALKS);
		}
		chatLogger.heard(profile, name, text, false, whisper, alert, now);
		if (alert != null && fresh(name + "/" + alert, now)) {
			Alerts.ping(Text.literal(name).formatted(profile != null && profile.verdict == Profile.Verdict.NAUGHTY ? Formatting.RED : Formatting.YELLOW, Formatting.BOLD)
					.append(Text.literal(" " + alert + ": ").formatted(Formatting.YELLOW))
					.append(Text.literal(text.length() > 80 ? text.substring(0, 80) + "…" : text).formatted(Formatting.WHITE)), soundFor(alert));
		}
	}

	/** Each kind of chat alert has its own sound (see {@link Alerts.Sound}), from what {@link #alertFor} said it was. */
	static Alerts.Sound soundFor(String alert) {
		if (alert.startsWith("threatened")) return Alerts.Sound.THREAT;
		if (alert.equals("said your name")) return Alerts.Sound.NAME;
		if (alert.startsWith("said ")) return Alerts.Sound.WORD;
		if (alert.startsWith("named your base") || alert.startsWith("posted coordinates near your base")) return Alerts.Sound.BASE;
		if (alert.startsWith("talked about")) return Alerts.Sound.TOPIC;
		if (alert.startsWith("posted coordinates")) return Alerts.Sound.COORDS;
		return Alerts.Sound.WORD;
	}

	private static final Set<String> COMMON = Set.of("that", "this", "with", "have", "just", "what", "your", "from", "they", "were", "will",
			"there", "their", "would", "about", "like", "dont", "it's", "i'm", "know", "when", "then", "them", "some", "been", "here",
			"want", "need", "where", "only", "also", "much", "very", "yeah", "even", "into", "than", "more", "make", "good", "does", "cant",
			"can't", "don't", "because", "still", "should", "could", "really", "think", "going", "gonna", "well", "back", "time", "anyone",
			"who's", "that's", "didn't", "doesn't", "isn't", "you're", "what's", "thats", "lmao");
	private static final Pattern CHAT_WORD = Pattern.compile("[a-z][a-z']{3,15}");
	private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://\\S+|www\\.\\S+|discord\\.gg/\\S+|discord\\.com/invite/\\S+)");

	/** What a line says about them: who they name, their words, caps, questions, and coordinates or links they post. */
	private void chatAnalytics(MinecraftClient client, Profile profile, String text) {
		PlayerStats st = profile.stats;
		st.chatHours[java.time.LocalTime.now().getHour()]++;
		String lower = text.toLowerCase(Locale.ROOT);
		Set<String> online = onlineNames(client);
		String me = client.player.getGameProfile().name().toLowerCase(Locale.ROOT);
		Set<String> named = new HashSet<>();
		Matcher w = WORD.matcher(text);
		while (w.find()) {
			String n = w.group().toLowerCase(Locale.ROOT);
			if (!online.contains(n) || n.equals(profile.name.toLowerCase(Locale.ROOT)) || !named.add(n)) continue;
			if (n.equals(me)) {
				st.mentionsOfYou++;
				continue;
			}
			Profile other = ledger.byName(n);
			PlayerStats.bump(st.mentions, other != null ? other.name : w.group(), 1);
			if (other != null) PlayerStats.bump(other.stats.mentionedBy, profile.name, 1);
		}
		Matcher cw = CHAT_WORD.matcher(lower);
		while (cw.find()) {
			String word = cw.group();
			if (!COMMON.contains(word) && !online.contains(word)) PlayerStats.bump(st.topWords, word, 1, 25);
		}
		long letters = text.chars().filter(Character::isLetter).count();
		if (letters >= 5 && text.chars().filter(Character::isUpperCase).count() >= letters * 0.8) st.capsMessages++;
		if (text.strip().endsWith("?")) st.questions++;
		st.placesFrom(text, System.currentTimeMillis());
		for (ChatIntel.Topic t : ChatIntel.topics(text, online)) PlayerStats.bump(st.topics, t.name(), 1, 20);
		Matcher url = URL.matcher(text);
		if (url.find()) PlayerStats.note(st.linksPosted, url.group(), 15);
	}

	private boolean fresh(String key, long now) {
		recentAlerts.values().removeIf(t -> now - t > 30_000);
		return recentAlerts.put(key, now) == null;
	}

	/**
	 * Why a line someone else said is worth an alert, or null, most urgent first: a threat aimed at you (or your
	 * base), one of your alert words (your name by default), the name of one of your bases, coordinates near one, a topic
	 * you asked to hear about, or (if you asked) any coordinates at all. A whisper to you is always aimed at you.
	 */
	String alertFor(MinecraftClient client, String text, boolean whisper) {
		NNConfig config = NNConfig.INSTANCE;
		String lower = text.toLowerCase(Locale.ROOT);
		String me = client.player == null ? "" : client.player.getGameProfile().name();
		List<BaseInsuranceBridge.BaseArea> bases = BaseInsuranceBridge.bases();
		String namedBase = null;
		for (BaseInsuranceBridge.BaseArea base : bases) {
			String n = base.name().toLowerCase(Locale.ROOT);
			if (n.length() >= 3 && Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(n) + "(?![a-z0-9_])").matcher(lower).find()) namedBase = base.name();
		}
		java.util.Set<ChatIntel.Topic> topics = ChatIntel.topics(text, onlineNames(client));
		boolean aimedAtMe = whisper || namedBase != null || Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(me.toLowerCase(Locale.ROOT)) + "(?![a-z0-9_])").matcher(lower).find();
		if (config.alertThreats && topics.contains(ChatIntel.Topic.THREAT) && aimedAtMe) {
			return namedBase != null ? "threatened your base \"" + namedBase + "\"" : "threatened you";
		}
		if (whisper) return null;
		for (String word : config.alertWords) {
			String w = word.replace("{me}", me).strip().toLowerCase(Locale.ROOT);
			if (w.length() < 2) continue;
			if (Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(w) + "(?![a-z0-9_])").matcher(lower).find()) {
				return word.equals("{me}") ? "said your name" : "said \"" + word.strip() + "\"";
			}
		}
		if (config.alertBaseNames && namedBase != null) return "named your base \"" + namedBase + "\"";
		List<ChatIntel.Coord> coords = ChatIntel.coords(text);
		if (config.alertBaseCoords) {
			for (ChatIntel.Coord c : coords) {
				for (BaseInsuranceBridge.BaseArea base : bases) {
					double[] at = inDimension(c, base.dimension());
					if (at != null && base.distance(at[0], at[1]) <= 300) return "posted coordinates near your base \"" + base.name() + "\"";
				}
			}
		}
		for (ChatIntel.Topic t : topics) {
			if (config.topicPings.contains(t.name())) return "talked about " + t.label.toLowerCase(Locale.ROOT);
		}
		if (config.pingAllCoords && !coords.isEmpty()) return "posted coordinates (" + coords.get(0).text() + ")";
		return null;
	}

	/**
	 * Where a written place is in a dimension, as x and z: as written if it's that dimension (or they didn't say),
	 * scaled 8 to 1 between the overworld and the nether, or null for the end against anything else.
	 */
	static double[] inDimension(ChatIntel.Coord c, String dimension) {
		if (c.dimension() == null || c.dimension().equals(dimension)) return new double[] {c.x(), c.z()};
		boolean toNether = dimension.endsWith("the_nether") && c.dimension().endsWith("overworld");
		boolean toOverworld = dimension.endsWith("overworld") && c.dimension().endsWith("the_nether");
		if (toNether) return new double[] {c.x() / 8.0, c.z() / 8.0};
		if (toOverworld) return new double[] {c.x() * 8.0, c.z() * 8.0};
		return null;
	}

	// ---- World events and who caused them ----

	/** Recent "Withering Heights" advancements, waiting for the wither's spawn to be heard. */
	private final List<Object[]> witherSummoners = new ArrayList<>();
	/** Recent wither spawns heard, waiting to see who summoned them. */
	private final List<ServerActivity.WorldEvent> recentWithers = new ArrayList<>();
	/**
	 * A wither's spawn is heard when it finishes charging, about 11 seconds (220 ticks, longer on a laggy server)
	 * after the summoner gets "Withering Heights". Only a match with one advancement and one spawn in this window
	 * says who it was.
	 */
	static final long WITHER_MIN = 5_000, WITHER_MAX = 40_000;

	/** A sound the server sent to everyone in the dimension, with where it happened. */
	public void onWorldEvent(MinecraftClient client, int id, BlockPos pos, boolean global) {
		if (!global || client.world == null) return;
		String kind = switch (id) {
			case net.minecraft.world.WorldEvents.WITHER_SPAWNS -> "wither";
			case net.minecraft.world.WorldEvents.END_PORTAL_OPENED -> "end_portal";
			case net.minecraft.world.WorldEvents.ENDER_DRAGON_DIES -> "dragon";
			default -> null;
		};
		if (kind == null) return;
		worldEvent(kind, client.world.getRegistryKey().getValue().toString(), pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis());
	}

	void worldEvent(String kind, String dimension, int x, int y, int z, long now) {
		ServerActivity.WorldEvent e = new ServerActivity.WorldEvent();
		e.time = now;
		e.kind = kind;
		e.dimension = dimension;
		e.x = x;
		e.y = y;
		e.z = z;
		ledger.activity.event(e);
		if (kind.equals("wither")) {
			recentWithers.add(e);
			matchWithers(now);
		}
		ledger.markDirty();
	}

	/** Someone got "Withering Heights": they just summoned a wither. */
	void summonedWither(Profile profile, long now) {
		witherSummoners.add(new Object[] {profile, now});
		matchWithers(now);
	}

	/** Pairs a wither spawn with its summoner, only when there's exactly one of each in the window. */
	private void matchWithers(long now) {
		witherSummoners.removeIf(w -> now - (long) w[1] > WITHER_MAX + 5_000);
		recentWithers.removeIf(e -> now - e.time > WITHER_MAX + 5_000);
		for (ServerActivity.WorldEvent e : List.copyOf(recentWithers)) {
			List<Object[]> who = new ArrayList<>();
			for (Object[] w : witherSummoners) {
				long gap = e.time - (long) w[1];
				if (gap >= WITHER_MIN && gap <= WITHER_MAX) who.add(w);
			}
			long others = recentWithers.stream().filter(o -> o != e && Math.abs(o.time - e.time) <= WITHER_MAX).count();
			if (who.size() != 1 || others > 0) continue;
			Profile p = (Profile) who.get(0)[0];
			e.who = p.name;
			p.stats.addPlace(e.x, e.y, e.z, e.dimension, "wither", "Summoned a wither here (its spawn was heard server-wide)", e.time);
			recentWithers.remove(e);
			witherSummoners.remove(who.get(0));
			ledger.markDirty();
		}
	}

	private void said(Profile profile, String body) {
		profile.recentChat.add(body.length() > 120 ? body.substring(0, 120) + "..." : body);
		while (profile.recentChat.size() > 10) profile.recentChat.remove(0);
		profile.lastSeen = System.currentTimeMillis();
		ledger.markDirty();
		if (!NNConfig.INSTANCE.watchChat) return;
		String quote = body.length() > 40 ? body.substring(0, 40) + "..." : body;
		switch (ChatIntel.tone(body, onlineNames(MinecraftClient.getInstance()))) {
			case RUDE -> record(profile, Profile.Kind.RUDE, quote);
			case ABUSE -> record(profile, Profile.Kind.ABUSE_TALK, body.length() > 120 ? body.substring(0, 120) + "..." : body);
			case KIND -> record(profile, Profile.Kind.KIND_WORDS, quote);
			case NONE -> {}
		}
	}

	private static String argString(Object arg) {
		return arg instanceof Text text ? text.getString() : String.valueOf(arg);
	}

	/** Whether a message argument is, or contains, the game text with this translation key. */
	static boolean hasKey(Object arg, String key) {
		if (!(arg instanceof Text text)) return false;
		if (text.getContent() instanceof TranslatableTextContent t) {
			if (t.getKey().equals(key)) return true;
			for (Object a : t.getArgs()) {
				if (hasKey(a, key)) return true;
			}
		}
		for (Text sibling : text.getSiblings()) {
			if (hasKey(sibling, key)) return true;
		}
		return false;
	}

	private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	/**
	 * The player named by a message argument, or null if it's some other entity (a mob, a named pet). The game marks
	 * a player's name with the name itself as its insertion (shift-click text), and other entities with their UUID,
	 * so ranks and team prefixes around the name ("[VIP] Steve") don't get in the way.
	 */
	static String playerArg(Object arg) {
		if (!(arg instanceof Text text)) return String.valueOf(arg);
		String insertion = text.visit((style, part) -> java.util.Optional.ofNullable(style.getInsertion()), net.minecraft.text.Style.EMPTY).orElse(null);
		if (insertion != null) return PLAIN_NAME.matcher(insertion).matches() ? insertion : null;
		String plain = text.getString().strip();
		if (PLAIN_NAME.matcher(plain).matches()) return plain;
		// No insertion (a plugin rebuilt it): the last thing in it that could be a name.
		Matcher m = WORD.matcher(plain);
		String last = plain;
		while (m.find()) last = m.group();
		return last;
	}

	/** A player's UUID from the tab list (or the lists, for someone who just left), by name. */
	private UUID playerId(MinecraftClient client, String name) {
		if (client.getNetworkHandler() != null) {
			PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(name);
			if (entry != null) return entry.getProfile().id();
		}
		Profile known = ledger.byName(name);
		return known == null ? null : known.id;
	}

	// ---- Every tick ----

	public void tick(MinecraftClient client) {
		ClientWorld world = client.world;
		if (world == null || client.player == null || client.getNetworkHandler() == null) return;
		if (++tick % 20 != 0) return;
		long now = System.currentTimeMillis();
		// Time actually passed since the last pass: a second, unless the game stalled. At most 3s, so a long
		// freeze or a sleeping computer isn't counted as time on.
		long dt = lastSecond == 0 ? 1000 : Math.max(0, Math.min(3000, now - lastSecond));
		lastSecond = now;
		ledger.activity.addMyTime(dt);
		if (NNConfig.INSTANCE.eventLog) EventHooks.positions(client);
		chatLogger.tick(now);
		NNConfig config = NNConfig.INSTANCE;
		String dimension = world.getRegistryKey().getValue().toString();
		if (secondsOn == 0) ledger.activity.myJoins++;
		if (secondsOn++ == 0) chatLogger.feedMarker("You joined " + Sharing.placeName(ledger.key()), now);
		Disconnects.snapshot(client, ledger);
		if (secondsOn == 4) Disconnects.tellIfNew();

		List<PlayerEntity> visible = new ArrayList<>();
		for (PlayerEntity player : world.getPlayers()) {
			if (isMe(client, player)) continue;
			visible.add(player);
			Profile profile = profile(player);
			profile.lastSeen = now;
			double distance = player.distanceTo(client.player);

			// Came into view (and wasn't just here).
			Long seenBefore = lastVisible.put(player.getUuid(), now);
			boolean arrived = seenBefore == null || now - seenBefore > 60_000;
			String base = BaseInsuranceBridge.baseAt(dimension, player.getBlockPos());
			if (config.recordSightings) sighted(profile, player, dimension, base, arrived, now);
			inView(profile, player, dimension, distance, arrived, dt, now);
			if (arrived) arrivals.put(player.getUuid(), now);
			String alertGroup = ledger.alertGroupOf(profile);
			if (arrived && profile.verdict == Profile.Verdict.NAUGHTY && config.warnNaughtyNearby) {
				Alerts.warn(Text.literal("Naughty-list player ").formatted(Formatting.RED)
						.append(Text.literal(profile.name).formatted(Formatting.RED, Formatting.BOLD))
						.append(groupTag(profile))
						.append(Text.literal(" is nearby (" + (int) distance + " blocks " + direction(client, player) + ")").formatted(Formatting.RED)), Alerts.Sound.NAUGHTY_NEAR);
			} else if (arrived && alertGroup != null) {
				Alerts.groupAlert(Text.literal(alertGroup + ": ").formatted(Formatting.AQUA)
						.append(Text.literal(profile.name).formatted(Formatting.AQUA, Formatting.BOLD))
						.append(Text.literal(" is nearby (" + (int) distance + " blocks " + direction(client, player) + ")").formatted(Formatting.AQUA)),
						ledger.groupColor(alertGroup));
			} else if (arrived && profile.verdict == Profile.Verdict.LOOK && config.noteLookInto) {
				Alerts.chat(Text.literal("Look Into: ").formatted(Formatting.GOLD)
						.append(Text.literal(profile.name).formatted(Formatting.GOLD, Formatting.BOLD))
						.append(Text.literal(" is nearby (" + (int) distance + " blocks " + direction(client, player) + ")").formatted(Formatting.GOLD)));
			} else if (arrived && friendAlert(profile) != null) {
				Friends.Circle circle = friendAlert(profile);
				Alerts.groupAlert(circleTag(circle).append(Text.literal(profile.name).formatted(Formatting.BOLD))
						.append(Text.literal(" is nearby (" + (int) distance + " blocks " + direction(client, player) + ")")), circle.color);
			}

			if (distance <= config.nearRadius) {
				profile.secondsNear++;
				if (++profile.peacefulSeconds >= 600) {
					profile.peacefulSeconds = 0;
					record(profile, Profile.Kind.PEACEFUL, "");
				}
			}

			if (base != null) {
				String key = player.getUuid() + "@" + base;
				Long last = lastBaseVisit.put(key, now);
				if (last == null || now - last > 600_000) {
					record(profile, Profile.Kind.VISITED_BASE, base);
					if (profile.verdict == Profile.Verdict.NAUGHTY || profile.verdict == Profile.Verdict.LOOK || alertGroup != null) {
						EvidenceShots.take(client, profile, "In your base " + base);
					}
				}
			}
		}
		lastVisible.values().removeIf(t -> now - t > 600_000);
		moved.values().removeIf(m -> now - m.time() > 5_000);
		arrivals.values().removeIf(t -> now - t > 60_000);
		if (config.trackLinks) nearEachOther(visible, now);

		// Joins: who's on the tab list now that wasn't before.
		Set<UUID> current = new HashSet<>();
		boolean countHour = config.trackHours && secondsOn % 60 == 0;
		// Once a minute: how many others are on, for the Activity tab.
		if (config.trackHours && secondsOn % 60 == 0 && secondsOn > 0) {
			ledger.activity.sample(client.getNetworkHandler().getPlayerList().size() - 1, java.time.LocalDateTime.now());
		}
		int hour = java.time.LocalTime.now().getHour();
		for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
			UUID id = entry.getProfile().id();
			current.add(id);
			boolean me = id.equals(client.player.getUuid());
			// Everyone on the server is remembered, so they're still listed once they log off.
			if (config.rememberEveryone && !me) ledger.getOrCreate(id, entry.getProfile().name()).lastSeen = now;
			if (!me) onTabList(entry, ledger.get(id), countHour, dt, now);
			if (countHour && !me) {
				Profile p = ledger.get(id);
				if (p != null) {
					if (p.hours == null) p.hours = new int[24];
					p.hours[hour]++;
				}
			}
			if (!onlineKnown || online.contains(id) || me) continue;
			LogoutSpots.back(id);
			chatLogger.feedSystem(entry.getProfile().name(), "joined", now);
			EventLog.add(EventLog.entry(EventLog.Type.JOIN, entry.getProfile().name(), "", ""));
			if (config.trackLinks) ledger.links.joined(id, now);
			Profile profile = ledger.get(id);
			if (profile == null) continue;
			profile.stats.logins++;
			profile.stats.lastLogin = now;
			profile.stats.clock(true, java.time.LocalTime.now());
			if (secondsOn <= 120) profile.stats.joinedAfterYou++;
			String alertGroup = ledger.alertGroupOf(profile);
			if (profile.notifyJoin && !(profile.verdict == Profile.Verdict.NAUGHTY && config.warnNaughtyJoin)) {
				// You asked for this player in particular: always said, with a ping.
				Alerts.ping(Text.literal(profile.name).formatted(verdictFormat(profile), Formatting.BOLD)
						.append(groupTag(profile))
						.append(Text.literal(" logged in").formatted(Formatting.GRAY)), Alerts.Sound.WATCHED_JOINED);
			} else if (profile.verdict == Profile.Verdict.NAUGHTY && config.warnNaughtyJoin) {
				Alerts.warn(Text.literal("Naughty-list player ").formatted(Formatting.RED)
						.append(Text.literal(profile.name).formatted(Formatting.RED, Formatting.BOLD))
						.append(groupTag(profile))
						.append(Text.literal(" joined the server").formatted(Formatting.RED)), Alerts.Sound.NAUGHTY_JOINED);
			} else if (alertGroup != null) {
				int on = 0;
				List<Profile> members = ledger.members(alertGroup);
				for (Profile m : members) {
					if (m == profile || online.contains(m.id) || current.contains(m.id)) on++;
				}
				Alerts.groupAlert(Text.literal(alertGroup + ": ").formatted(Formatting.AQUA)
						.append(Text.literal(profile.name).formatted(Formatting.AQUA, Formatting.BOLD))
						.append(Text.literal(" joined the server (" + on + " of " + members.size() + " online)").formatted(Formatting.AQUA)),
						ledger.groupColor(alertGroup));
			} else if (profile.verdict == Profile.Verdict.LOOK && config.noteLookInto) {
				Alerts.chat(Text.literal("Look Into: ").formatted(Formatting.GOLD)
						.append(Text.literal(profile.name).formatted(Formatting.GOLD, Formatting.BOLD))
						.append(Text.literal(" joined the server").formatted(Formatting.GOLD)));
			} else if (friendAlert(profile) != null) {
				Friends.Circle circle = friendAlert(profile);
				Alerts.groupAlert(circleTag(circle).append(Text.literal(profile.name).formatted(Formatting.BOLD))
						.append(Text.literal(" joined the server")), circle.color);
			}
		}
		if (onlineKnown) {
			for (UUID id : online) {
				if (current.contains(id) || id.equals(client.player.getUuid())) continue;
				if (config.trackLinks) ledger.links.left(id, now);
				Profile gone = ledger.get(id);
				PlayerStats.Session session = sessions.remove(id);
				if (gone != null) {
					if (session != null) gone.stats.endSession(session, now);
					PlayerStats st = gone.stats;
					st.logouts++;
					st.lastLogout = now;
					st.clock(false, java.time.LocalTime.now());
					if (secondsOn <= 60) st.leftAfterYouJoined++;
					Long arrived = arrivals.get(id);
					if (arrived != null && now - arrived <= 30_000) st.leftWhenSeen++;
					if (st.lastDeathTime > 0 && now - st.lastDeathTime <= 60_000) st.leftAfterDying++;
					// In view as they went: where they logged out is certain (often at or near their base).
					Profile.Sighting sg = gone.lastSighting();
					if (sg != null && now - sg.until <= 3_000) {
						st.addPlace(sg.lastX, sg.lastY, sg.lastZ, sg.dimension, "logout", "Logged out here while you could see them", now);
						LogoutSpots.add(id, gone.name, sg.dimension, sg.lastX, sg.lastY, sg.lastZ, now);
					}
					chatLogger.feedSystem(gone.name, "left", now);
					EventLog.add(EventLog.entry(EventLog.Type.LEAVE, gone.name, "", ""));
				}
				if (gone != null && gone.notifyJoin) {
					Alerts.chat(Text.literal(gone.name).formatted(verdictFormat(gone), Formatting.BOLD)
							.append(groupTag(gone))
							.append(Text.literal(" logged out").formatted(Formatting.GRAY)));
				}
			}
		}
		if (config.trackLinks && countHour) ledger.links.minute(current);
		online = current;
		onlineKnown = true;
		// Wait a few seconds after joining for the tab list to fill in.
		if (secondsOn == 5 && config.joinSummary) joinSummary(client);
	}

	/**
	 * Once a second for everyone on the tab list: their time online and current session, and once a minute their
	 * ping and game mode. Their skin is read when their session starts and once a minute after.
	 */
	private void onTabList(PlayerListEntry entry, Profile profile, boolean minute, long dt, long now) {
		if (profile == null) return;
		PlayerStats st = profile.stats;
		if (st.myMsAtStart < 0) st.myMsAtStart = ledger.activity.myMs;
		st.onlineMs += dt;
		st.lastOnline = now;
		PlayerStats.Session session = sessions.get(profile.id);
		// None yet, or one belonging to a profile that's since been forgotten.
		boolean started = session == null || !st.sessions.contains(session);
		if (started) {
			PlayerStats.Session latest = st.sessions.isEmpty() ? null : st.sessions.get(st.sessions.size() - 1);
			if (latest != null && !latest.endSeen && now - latest.end <= 5_000) {
				// Still going: the lists were just loaded again (or you were gone only a moment).
				session = latest;
			} else {
				// Only a login you saw if they weren't on already when you got here.
				boolean seenLogin = session == null && onlineKnown && !online.contains(profile.id);
				int keep = profile.verdict == Profile.Verdict.NONE && profile.groups.isEmpty() ? 10 : 50;
				session = st.startSession(now, seenLogin, keep);
			}
			sessions.put(profile.id, session);
		}
		st.continueSession(session, now);
		if (minute) {
			st.onlineMinute(java.time.LocalDateTime.now());
			st.ping(entry.getLatency());
			if (entry.getGameMode() != null) PlayerStats.bump(st.gameModes, entry.getGameMode().asString(), 1);
		}
		if ((started || minute) && st.readSkin(entry.getProfile(), now)) {
			record(profile, Profile.Kind.LISTED, "Changed their skin");
		}
		if (started || minute) identity(entry, profile);
		// Saved with the next save after a minute's worth, not every second.
		if (started || minute) ledger.markDirty();
	}

	/** What the tab list says about who they are: the server's name for them (ranks), and their chat signing. */
	private void identity(PlayerListEntry entry, Profile profile) {
		PlayerStats st = profile.stats;
		Text display = entry.getDisplayName();
		String shown = display == null ? "" : display.getString().strip();
		if (shown.equals(profile.name)) shown = "";
		if (!shown.equals(st.tabName)) {
			if (!st.tabName.isEmpty()) {
				st.pastTabNames.remove(st.tabName);
				st.pastTabNames.add(st.tabName);
				while (st.pastTabNames.size() > 6) st.pastTabNames.remove(0);
			}
			st.tabName = shown;
		}
		net.minecraft.network.encryption.PublicPlayerSession session = entry.getSession();
		st.signedKnown = true;
		st.signedChat = session != null;
		if (session != null && session.publicKeyData() != null) {
			long key = session.publicKeyData().data().expiresAt().toEpochMilli();
			if (st.chatKey != 0 && key != st.chatKey) st.chatKeyChanges++;
			st.chatKey = key;
		}
	}

	/** Once a second for each player in view: time, distance from you, how they move, what they ride and hold. */
	private void inView(Profile profile, PlayerEntity player, String dimension, double distance, boolean arrived, long dt, long now) {
		PlayerStats st = profile.stats;
		if (arrived) st.timesSeen++;
		st.inViewMs += dt;
		st.lastInView = now;
		if (st.closest < 0 || distance < st.closest) st.closest = distance;
		PlayerStats.bump(st.dimensions, dimension, 1);
		if (player.isGliding()) st.glidingMs += dt;
		Entity vehicle = player.getVehicle();
		if (vehicle != null) PlayerStats.bump(st.vehicles, vehicle.getType().getName().getString(), 1);
		ItemStack main = player.getMainHandStack();
		if (!main.isEmpty()) PlayerStats.bump(st.held, main.getName().getString(), 1);
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack stack = player.getEquippedStack(slot);
			if (stack.isEmpty()) continue;
			String path = Registries.ITEM.getId(stack.getItem()).getPath();
			int cut = path.lastIndexOf('_');
			if (cut > 0) st.armour(path.substring(0, cut));
		}
		// How far and fast they go. A pearl or a teleport is one big jump, so top speed is the slowest of three
		// seconds in a row, and a second over 150 blocks isn't counted as travel.
		Moved last = moved.get(player.getUuid());
		double speed = 0;
		if (last != null && !arrived && last.dimension().equals(dimension) && now - last.time() >= 500) {
			double d = Math.sqrt(player.squaredDistanceTo(last.x(), last.y(), last.z()));
			speed = d / ((now - last.time()) / 1000.0);
			if (speed <= 150) {
				st.travelled += d;
				Profile.Sighting sg = profile.lastSighting();
				if (sg != null && sg.dimension.equals(dimension)) {
					double secs = (now - last.time()) / 1000.0;
					sg.vx = (float) ((player.getX() - last.x()) / secs);
					sg.vz = (float) ((player.getZ() - last.z()) / secs);
				}
				double sustained = Math.min(speed, Math.min(last.speed1(), last.speed2()));
				if (sustained > st.topSpeed) st.topSpeed = sustained;
			} else {
				speed = 0;
			}
		}
		moved.put(player.getUuid(), new Moved(dimension, player.getX(), player.getY(), player.getZ(), now, speed, last == null ? 0 : last.speed1()));
	}

	/** Their list's colour, for chat notes. */
	private static Formatting verdictFormat(Profile p) {
		return switch (p.verdict) {
			case NAUGHTY -> Formatting.RED;
			case LOOK -> Formatting.GOLD;
			case NICE -> Formatting.GREEN;
			case FRIEND -> Formatting.AQUA;
			case NONE -> Formatting.WHITE;
		};
	}

	/** The friend's circle, if it pings when they join or come near; null otherwise. */
	private static Friends.Circle friendAlert(Profile profile) {
		Friends.Circle circle = Friends.of(profile);
		return circle != null && circle.joinAlert ? circle : null;
	}

	/** "★ Close friends: " in the circle's colour; what's appended after it takes the same colour. */
	private static net.minecraft.text.MutableText circleTag(Friends.Circle circle) {
		int color = Marks.GROUP_TEXT[circle.color % Marks.GROUP_TEXT.length];
		return Text.empty().styled(st -> st.withColor(color)).append(Text.literal("★ " + circle.name + ": "));
	}

	/** " [SCG]" in the group's colour, or nothing, for warnings. */
	private Text groupTag(Profile profile) {
		if (profile.groups.isEmpty()) return Text.empty();
		String g = profile.groups.get(0);
		int color = Marks.GROUP_TEXT[ledger.groupColor(g)];
		return Text.literal(" [" + g + "]").styled(s -> s.withColor(color));
	}

	/** Starts or continues a sighting: where they came into view, where they were last, and what they had. */
	private void sighted(Profile profile, PlayerEntity player, String dimension, String base, boolean arrived, long now) {
		Profile.Sighting last = profile.lastSighting();
		BlockPos pos = player.getBlockPos();
		if (arrived || last == null || now - last.until > 61_000 || !last.dimension.equals(dimension)) {
			Profile.Sighting sighting = new Profile.Sighting();
			sighting.time = now;
			sighting.dimension = dimension;
			sighting.x = sighting.lastX = pos.getX();
			sighting.y = sighting.lastY = pos.getY();
			sighting.z = sighting.lastZ = pos.getZ();
			last = sighting;
			profile.sightings.add(sighting);
			int max = profile.verdict == Profile.Verdict.NONE && profile.groups.isEmpty() ? Math.min(10, NNConfig.INSTANCE.maxSightings) : NNConfig.INSTANCE.maxSightings;
			while (profile.sightings.size() > max) profile.sightings.remove(0);
			ledger.markDirty();
		}
		last.until = now;
		last.lastX = pos.getX();
		last.lastY = pos.getY();
		last.lastZ = pos.getZ();
		if (base != null) last.base = base;
		String gear = gear(player);
		if (!gear.isEmpty()) last.gear = gear;
	}

	/** "Netherite armour, elytra · holding End Crystal · Totem of Undying in off hand", from what you can see on them. */
	static String gear(PlayerEntity player) {
		Map<String, Integer> materials = new java.util.LinkedHashMap<>();
		boolean elytra = false;
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack stack = player.getEquippedStack(slot);
			if (stack.isEmpty()) continue;
			String path = Registries.ITEM.getId(stack.getItem()).getPath();
			if (path.equals("elytra")) {
				elytra = true;
				continue;
			}
			int cut = path.lastIndexOf('_');
			if (cut <= 0) continue;
			String material = path.substring(0, cut);
			materials.merge(material, 1, Integer::sum);
		}
		List<String> parts = new ArrayList<>();
		if (!materials.isEmpty()) {
			Map.Entry<String, Integer> top = materials.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
			String material = switch (top.getKey()) {
				case "turtle" -> "Turtle";
				case "chainmail" -> "Chainmail";
				case "golden" -> "Gold";
				default -> Character.toUpperCase(top.getKey().charAt(0)) + top.getKey().substring(1).replace('_', ' ');
			};
			int pieces = materials.values().stream().mapToInt(Integer::intValue).sum();
			parts.add(material + " armour" + (pieces < 4 ? " (" + pieces + " piece" + (pieces == 1 ? "" : "s") + ")" : ""));
		}
		if (elytra) parts.add("elytra");
		String worn = String.join(", ", parts);
		List<String> held = new ArrayList<>();
		ItemStack main = player.getMainHandStack();
		if (!main.isEmpty()) held.add("holding " + main.getName().getString());
		ItemStack off = player.getOffHandStack();
		if (!off.isEmpty()) held.add(off.getName().getString() + " in off hand");
		List<String> all = new ArrayList<>();
		if (!worn.isEmpty()) all.add(Character.toUpperCase(worn.charAt(0)) + worn.substring(1));
		all.addAll(held);
		return String.join(" · ", all);
	}

	/** Pairs of players near each other this second, for spotting crews. The nearest 40 to you are checked. */
	private void nearEachOther(List<PlayerEntity> visible, long now) {
		List<PlayerEntity> list = visible;
		if (list.size() > 40) {
			MinecraftClient client = MinecraftClient.getInstance();
			list = new ArrayList<>(visible);
			list.sort(java.util.Comparator.comparingDouble(p -> p.squaredDistanceTo(client.player)));
			list = list.subList(0, 40);
		}
		double near = Links.NEAR_BLOCKS * Links.NEAR_BLOCKS;
		// How many others are close to each: in a crowd (spawn, a highway), being near someone means nothing.
		int[] around = new int[list.size()];
		boolean[][] close = new boolean[list.size()][list.size()];
		for (int i = 0; i < list.size(); i++) {
			ledger.links.seen(list.get(i).getUuid());
			for (int j = i + 1; j < list.size(); j++) {
				if (list.get(i).squaredDistanceTo(list.get(j)) <= near) {
					close[i][j] = true;
					around[i]++;
					around[j]++;
				}
			}
		}
		for (int i = 0; i < list.size(); i++) {
			for (int j = i + 1; j < list.size(); j++) {
				if (close[i][j] && around[i] <= Links.CROWD && around[j] <= Links.CROWD) {
					ledger.links.near(list.get(i).getUuid(), list.get(j).getUuid(), now);
				}
			}
		}
	}

	/** Who's online from your lists, said once when you join. */
	void joinSummary(MinecraftClient client) {
		Map<Profile.Verdict, List<String>> on = new java.util.EnumMap<>(Profile.Verdict.class);
		Map<String, Integer> groupsOn = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		boolean anyListed = false;
		for (Profile p : ledger.all()) {
			if (p.verdict != Profile.Verdict.NONE || !p.groups.isEmpty()) anyListed = true;
			if (!online.contains(p.id) || p.id.equals(client.player.getUuid())) continue;
			if (p.verdict != Profile.Verdict.NONE) on.computeIfAbsent(p.verdict, v -> new ArrayList<>()).add(p.name);
			for (String g : p.groups) groupsOn.merge(g, 1, Integer::sum);
		}
		if (!anyListed) return;
		net.minecraft.text.MutableText text = Text.empty();
		if (on.isEmpty()) {
			text.append(Text.literal("Nobody from your lists is online.").formatted(Formatting.GRAY));
		} else {
			text.append(Text.literal("Online now: ").formatted(Formatting.GRAY));
			boolean first = true;
			for (Profile.Verdict v : new Profile.Verdict[] {Profile.Verdict.NAUGHTY, Profile.Verdict.LOOK, Profile.Verdict.FRIEND, Profile.Verdict.NICE}) {
				List<String> names = on.get(v);
				if (names == null) continue;
				names.sort(String.CASE_INSENSITIVE_ORDER);
				if (!first) text.append(Text.literal(" · ").formatted(Formatting.DARK_GRAY));
				first = false;
				Formatting color = switch (v) {
					case NAUGHTY -> Formatting.RED;
					case LOOK -> Formatting.GOLD;
					case FRIEND -> Formatting.AQUA;
					default -> Formatting.GREEN;
				};
				String word = switch (v) {
					case NAUGHTY -> "Naughty";
					case LOOK -> "Look Into";
					case FRIEND -> names.size() == 1 ? "Friend" : "Friends";
					default -> "Nice";
				};
				String mark = switch (v) {
					case NAUGHTY -> "✖ ";
					case LOOK -> "? ";
					case FRIEND -> "★ ";
					default -> "✔ ";
				};
				String shown = names.size() > 4 ? String.join(", ", names.subList(0, 4)) + " +" + (names.size() - 4) : String.join(", ", names);
				text.append(Text.literal(mark + names.size() + " " + word).formatted(color, Formatting.BOLD))
						.append(Text.literal(v == Profile.Verdict.NICE && names.size() > 4 ? "" : " (" + shown + ")").formatted(color));
			}
		}
		if (!groupsOn.isEmpty()) {
			List<String> parts = new ArrayList<>();
			groupsOn.forEach((g, n) -> parts.add(g + " " + n));
			text.append(Text.literal(" · Groups on: " + String.join(", ", parts)).formatted(Formatting.AQUA));
		}
		text.append(Text.literal(" [Open]").styled(st -> st.withColor(Formatting.AQUA).withUnderline(true)
				.withClickEvent(new ClickEvent.RunCommand("/nn"))));
		Alerts.chat(text);
	}

	private static String direction(MinecraftClient client, Entity target) {
		double dx = target.getX() - client.player.getX();
		double dz = target.getZ() - client.player.getZ();
		String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		double angle = Math.toDegrees(Math.atan2(-dx, dz));
		return names[(int) Math.floorMod(Math.round(angle / 45), 8)];
	}
}
