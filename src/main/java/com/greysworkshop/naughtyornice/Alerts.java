package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Messages to the player, prefixed so it's clear which mod they came from. */
public final class Alerts {
	private Alerts() {}

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
		if (NNConfig.INSTANCE.warnSounds) {
			float pitch = GROUP_PITCH[Math.floorMod(colorIndex, GROUP_PITCH.length)];
			client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_BELL, pitch));
		}
	}

	/** Something said in chat you asked to hear about: chat and a soft ping. */
	public static void ping(Text message) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;
		client.player.sendMessage(prefix().append(message), false);
		if (NNConfig.INSTANCE.warnSounds) {
			client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.5f));
		}
	}

	/** A warning: chat, the action bar, and a sound. */
	public static void warn(Text message) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;
		client.player.sendMessage(prefix().append(message), false);
		client.player.sendMessage(message, true);
		if (NNConfig.INSTANCE.warnSounds) {
			client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_BASS, 0.6f));
		}
	}
}
