package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Messages to the player, prefixed so it's clear which mod they came from, and a sound of its own for each kind, so
 * you can tell by ear what happened without looking: low and urgent for danger, rising for good news, a bell per
 * group. /nn sounds plays every one with its name.
 */
public final class Alerts {
	private Alerts() {}

	/** One note of a sound: what plays, how high, and how many ticks after the first. */
	private record Note(Object event, float pitch, int after) {}

	private static Note n(Object event, float pitch, int after) {
		return new Note(event, pitch, after);
	}

	/** Every kind of notification, each with its own sound. */
	public enum Sound {
		NAUGHTY_NEAR("A Naughty-list player is near you", n(SoundEvents.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0), n(SoundEvents.BLOCK_NOTE_BLOCK_BASS, 0.5f, 3),
				n(SoundEvents.BLOCK_NOTE_BLOCK_BASS, 0.6f, 6)),
		NAUGHTY_JOINED("A Naughty-list player joined", n(SoundEvents.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.75f, 0), n(SoundEvents.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.6f, 5)),
		THREAT("Someone threatened you or your base", n(SoundEvents.BLOCK_NOTE_BLOCK_COW_BELL, 0.6f, 0), n(SoundEvents.BLOCK_NOTE_BLOCK_COW_BELL, 0.5f, 4)),
		BASE("Your base named, or coordinates near it", n(SoundEvents.BLOCK_NOTE_BLOCK_GUITAR, 0.9f, 0), n(SoundEvents.BLOCK_NOTE_BLOCK_GUITAR, 0.7f, 3),
				n(SoundEvents.BLOCK_NOTE_BLOCK_GUITAR, 0.9f, 6)),
		NAME("Someone said your name", n(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.5f, 0), n(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 2.0f, 3)),
		WORD("A word you watch for", n(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.2f, 0)),
		TOPIC("A topic you watch for", n(SoundEvents.BLOCK_NOTE_BLOCK_HARP, 1.4f, 0), n(SoundEvents.BLOCK_NOTE_BLOCK_HARP, 1.2f, 3)),
		COORDS("Someone posted coordinates", n(SoundEvents.BLOCK_NOTE_BLOCK_HAT, 1.0f, 0), n(SoundEvents.BLOCK_NOTE_BLOCK_HAT, 1.4f, 2),
				n(SoundEvents.BLOCK_NOTE_BLOCK_HAT, 1.0f, 4)),
		WATCHED_TALKS("A player you follow said something", n(SoundEvents.BLOCK_NOTE_BLOCK_FLUTE, 1.3f, 0)),
		WATCHED_JOINED("A player you follow logged in", n(SoundEvents.BLOCK_NOTE_BLOCK_XYLOPHONE, 1.0f, 0), n(SoundEvents.BLOCK_NOTE_BLOCK_XYLOPHONE, 1.5f, 3)),
		STRANGER("Trip paused: a stranger showed up", n(SoundEvents.BLOCK_NOTE_BLOCK_BIT, 1.0f, 0), n(SoundEvents.BLOCK_NOTE_BLOCK_BIT, 0.75f, 3),
				n(SoundEvents.BLOCK_NOTE_BLOCK_BIT, 1.0f, 6)),
		LOW_HEALTH("Trip stopped: your health is low", n(SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM, 0.6f, 0), n(SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM, 0.6f, 3),
				n(SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM, 0.6f, 6)),
		TRAIL("A chunk trail was found", n(SoundEvents.BLOCK_NOTE_BLOCK_BANJO, 1.0f, 0), n(SoundEvents.BLOCK_NOTE_BLOCK_BANJO, 1.25f, 3)),
		NEW_AHEAD("New chunks ahead", n(SoundEvents.BLOCK_NOTE_BLOCK_CHIME, 1.2f, 0)),
		OLD_AHEAD("Old chunks ahead (someone was there)", n(SoundEvents.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE, 0.9f, 0), n(SoundEvents.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE, 1.2f, 3)),
		ARRIVED("You arrived", n(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0), n(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.3f, 3)),
		GROUP("A group or circle you watch (its own pitch)", n(SoundEvents.BLOCK_NOTE_BLOCK_BELL, 1.0f, 0));

		public final String label;
		private final Note[] notes;

		Sound(String label, Note... notes) {
			this.label = label;
			this.notes = notes;
		}
	}

	/** For the self-test: how each sound goes (instrument, pitch and timing of every note), to check no two are alike. */
	static String signature(Sound sound) {
		StringBuilder sb = new StringBuilder();
		for (Note n : sound.notes) sb.append(n.event() instanceof net.minecraft.registry.entry.RegistryEntry<?> e ? e.getIdAsString() : String.valueOf(n.event()))
				.append('@').append(n.pitch()).append('+').append(n.after()).append(' ');
		return sb.toString();
	}

	/** For the self-test: notes still waiting to play. */
	static int pending() {
		return queue.size();
	}

	/** Notes still to play: ticks to wait, then what to do. */
	private static final java.util.List<Object[]> queue = new java.util.ArrayList<>();

	/** Plays a notification's sound (when Warning sounds is on), its later notes on the following ticks. */
	public static void play(Sound sound) {
		play(sound, 1f);
	}

	private static void play(Sound sound, float pitchTimes) {
		if (!NNConfig.INSTANCE.warnSounds || muted(sound)) return;
		hear(sound, pitchTimes);
	}

	private static void hear(Sound sound, float pitchTimes) {
		for (Note note : sound.notes) later(note.after(), () -> note(note, pitchTimes));
	}

	/** Whether you turned this one's sound off (Settings → Notifications). */
	public static boolean muted(Sound sound) {
		return NNConfig.INSTANCE.mutedSounds.contains(sound.name());
	}

	public static void mute(Sound sound, boolean muted) {
		NNConfig.INSTANCE.mutedSounds.remove(sound.name());
		if (muted) NNConfig.INSTANCE.mutedSounds.add(sound.name());
		NNConfig.save();
	}

	/** The ▶ in Settings: plays it now, whatever the switches say, so you know which is which. */
	public static void preview(Sound sound) {
		hear(sound, 1f);
	}

	@SuppressWarnings("unchecked")
	private static void note(Note note, float pitchTimes) {
		MinecraftClient client = MinecraftClient.getInstance();
		float pitch = note.pitch() * pitchTimes;
		if (note.event() instanceof net.minecraft.registry.entry.RegistryEntry<?> entry) {
			client.getSoundManager().play(PositionedSoundInstance.ui((net.minecraft.registry.entry.RegistryEntry<net.minecraft.sound.SoundEvent>) entry, pitch));
		} else if (note.event() instanceof net.minecraft.sound.SoundEvent event) {
			client.getSoundManager().play(PositionedSoundInstance.ui(event, pitch));
		}
	}

	private static void later(int ticks, Runnable run) {
		if (ticks <= 0) run.run();
		else queue.add(new Object[] {ticks, run});
	}

	/** Each tick: the notes due. */
	public static void tick() {
		if (queue.isEmpty()) return;
		for (Object[] q : java.util.List.copyOf(queue)) {
			int left = (int) q[0] - 1;
			if (left <= 0) {
				queue.remove(q);
				((Runnable) q[1]).run();
			} else {
				q[0] = left;
			}
		}
	}

	/** /nn sounds: every notification's sound, one after another, each named in chat as it plays. */
	public static void demo() {
		int at = 0;
		for (Sound sound : Sound.values()) {
			later(at, () -> {
				chat(Text.literal("♪ " + sound.label + (muted(sound) ? " (its sound is off)" : "")).formatted(Formatting.GRAY));
				hear(sound, 1f);
			});
			at += 30;
		}
	}

	public static MutableText prefix() {
		return Text.literal("[").formatted(Formatting.DARK_GRAY)
				.append(Text.literal("Naughty").formatted(Formatting.RED))
				.append(Text.literal("/").formatted(Formatting.DARK_GRAY))
				.append(Text.literal("Nice").formatted(Formatting.GREEN))
				.append(Text.literal("] ").formatted(Formatting.DARK_GRAY));
	}

	public static void chat(Text message) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) client.player.sendMessage(prefix().append(message), false);
	}

	/** Each group's alert is a bell at its own pitch, so you can tell by ear which group it is. */
	private static final float[] GROUP_PITCH = {0.7f, 0.8f, 0.9f, 1.0f, 1.12f, 1.26f, 1.33f, 1.5f, 1.68f, 1.88f};

	/** An alert about a group you're watching: chat, the action bar, and the group's bell. */
	public static void groupAlert(Text message, int colorIndex) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;
		client.player.sendMessage(prefix().append(message), false);
		client.player.sendMessage(message, true);
		PhoneAlerts.send(message.getString());
		play(Sound.GROUP, GROUP_PITCH[Math.floorMod(colorIndex, GROUP_PITCH.length)]);
	}

	/** Something you asked to hear about: chat, and its sound. */
	public static void ping(Text message, Sound sound) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;
		client.player.sendMessage(prefix().append(message), false);
		PhoneAlerts.send(message.getString());
		play(sound);
	}

	/** A warning: chat, the action bar, and its sound. */
	public static void warn(Text message, Sound sound) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;
		client.player.sendMessage(prefix().append(message), false);
		client.player.sendMessage(message, true);
		PhoneAlerts.send(message.getString());
		play(sound);
	}
}
