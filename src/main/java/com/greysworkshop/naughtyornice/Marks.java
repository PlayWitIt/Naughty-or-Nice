package com.greysworkshop.naughtyornice;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

/**
 * What's shown in front of listed players' names on nametags and the tab list: a green tick (Nice), a red cross
 * (Naughty) or a gold question mark (Look Into), then their groups as tags.
 */
public final class Marks {
	private Marks() {}

	/** Group colours for text over the world: bright enough to read on nametags. Same order as the screen's palette. */
	static final int[] GROUP_TEXT = {0x5BC0FF, 0xFF9F43, 0xB388FF, 0x4DD0A1, 0xFF6FA8, 0xFFD54F, 0x80DEEA, 0xC5E1A5, 0xFF8A65, 0xB0BEC5};

	public static Text decorate(UUID id, Text name) {
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		if (mod == null || id == null || name == null) return name;
		Profile profile = mod.ledger.get(id);
		if (profile == null) return name;
		boolean tags = NNConfig.INSTANCE.groupTags && !profile.groups.isEmpty();
		if (profile.verdict == Profile.Verdict.NONE && !tags) return name;
		MutableText out = switch (profile.verdict) {
			case NICE -> Text.literal("✔ ").formatted(Formatting.GREEN);
			case NAUGHTY -> Text.literal("✖ ").formatted(Formatting.RED);
			case LOOK -> Text.literal("? ").formatted(Formatting.GOLD, Formatting.BOLD);
			case NONE -> Text.empty();
		};
		if (tags) {
			// "[SCG] " in the group's colour; just the first group, with a + if there are more.
			String group = profile.groups.get(0);
			if (group.length() > 12) group = group.substring(0, 11) + "…";
			int color = GROUP_TEXT[mod.ledger.groupColor(profile.groups.get(0))];
			out.append(Text.literal("[" + group + (profile.groups.size() > 1 ? "+" : "") + "] ").styled(s -> s.withColor(color).withBold(false)));
		}
		return out.append(name);
	}
}
