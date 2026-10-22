package com.greysworkshop.naughtyornice;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * On the death screen: what hit you in your last 30 seconds, from the event recorder. Who hit you and how often, with
 * what, the totems you popped, and the last few hits with the health you had left after each. A button saves the
 * fight as a recording.
 */
public final class DeathRecap {
	static final long WINDOW_MS = 30_000;

	/** One source of damage: a player, or a kind of damage ("fall", "lava"). */
	record Source(String name, boolean player, int hits, Map<String, Integer> with) {}

	record Recap(int hits, int totems, List<Source> sources, List<EventLog.Entry> last) {}

	/** Opens the recap straight away even where it needs the button; for the self-test's screenshot. */
	static boolean testOpen;

	private DeathRecap() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof DeathScreen) || !NNConfig.on() || !NNConfig.INSTANCE.deathRecap || client.player == null) return;
			long died = System.currentTimeMillis();
			String me = client.player.getGameProfile().name();
			ButtonWidget save = ButtonWidget.builder(Text.literal("Save this fight"), b -> {
				try {
					Path file = EventLog.saveLast(2, "Death");
					b.setMessage(Text.literal(file == null ? "Nothing to save" : "Saved to Events"));
				} catch (IOException e) {
					b.setMessage(Text.literal("Couldn't save"));
				}
				b.active = false;
			}).dimensions(w / 2 - 100, h / 4 + 120, 200, 20).build();
			save.active = NNConfig.INSTANCE.eventLog;
			// Beside the buttons if there's room; otherwise a button shows it over the title.
			boolean side = sideWidth(w) >= 130;
			boolean[] shown = {side || testOpen};
			if (!side) {
				save.setDimensions(98, 20);
				save.setX(w / 2 + 2);
				Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("What hit you"), b -> {
					shown[0] = !shown[0];
					b.setMessage(Text.literal(shown[0] ? "Hide" : "What hit you"));
				}).dimensions(w / 2 - 100, h / 4 + 120, 98, 20).build());
			}
			Screens.getButtons(screen).add(save);
			Recap[] recap = {null};
			long[] built = {0};
			ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, delta) -> Safe.run("drawing the death recap", () -> {
				// Built again each second: the death message can arrive just after the screen.
				if (System.currentTimeMillis() - built[0] > 1000) {
					EventHooks.flush(true);
					recap[0] = build(me, died);
					built[0] = System.currentTimeMillis();
				}
				if (!shown[0]) return;
				if (side) draw(context, client.textRenderer, recap[0], died, 8, 8, sideWidth(s.width), s.height - 16);
				else {
					int pw = Math.min(320, s.width - 16);
					draw(context, client.textRenderer, recap[0], died, (s.width - pw) / 2, 4, pw, s.height / 4 + 66 - 4);
				}
			}));
		});
	}

	static Recap build(String me, long died) {
		int hits = 0, totems = 0;
		Map<String, Source> sources = new LinkedHashMap<>();
		List<EventLog.Entry> last = new ArrayList<>();
		for (EventLog.Entry e : EventLog.since(died - WINDOW_MS)) {
			if (e.t > died + 2000) break;
			if (e.type == EventLog.Type.TOTEM && e.actor.equalsIgnoreCase(me)) {
				totems++;
				last.add(e);
			} else if (e.type == EventLog.Type.HIT && e.target.equalsIgnoreCase(me)) {
				hits++;
				last.add(e);
				boolean player = !e.actor.isEmpty();
				String key = player ? e.actor : damageType(e.detail);
				Source src = sources.computeIfAbsent(key, k -> new Source(k, player, 0, new LinkedHashMap<>()));
				Map<String, Integer> with = src.with;
				String how = how(e.detail);
				if (player && !how.isEmpty()) with.merge(how, 1, Integer::sum);
				sources.put(key, new Source(src.name, src.player, src.hits + 1, with));
			}
		}
		List<Source> list = new ArrayList<>(sources.values());
		list.sort((a, b) -> Integer.compare(b.hits, a.hits));
		return new Recap(hits, totems, list, last.subList(Math.max(0, last.size() - 5), last.size()).reversed());
	}

	/** The kind of damage: the last part of a hit's detail ("with Diamond Sword · player attack" gives "player attack"). */
	static String damageType(String detail) {
		int i = detail.lastIndexOf(" · ");
		return i < 0 ? detail : detail.substring(i + 3);
	}

	/** What it was done with: the held item, else the projectile, else the kind of damage. */
	static String how(String detail) {
		for (String part : detail.split(" · ")) {
			if (part.startsWith("with ")) return part.substring(5);
		}
		for (String part : detail.split(" · ")) {
			if (part.startsWith("by ")) return part.substring(3);
		}
		return damageType(detail);
	}

	private static int sideWidth(int screenWidth) {
		return Math.min(220, screenWidth / 2 - 112);
	}

	private static void draw(DrawContext context, TextRenderer font, Recap recap, long died, int x, int y, int w, int maxH) {
		if (recap == null) return;
		List<Object[]> lines = new ArrayList<>();
		lines.add(new Object[] {"What hit you (last 30 s)", 0xFFFFFFFF});
		if (!NNConfig.INSTANCE.eventLog) {
			lines.add(new Object[] {"Turn on the event recorder (N, Settings) to see this.", 0xFFB8C0C8});
		} else if (recap.hits == 0 && recap.totems == 0) {
			lines.add(new Object[] {"Nothing the game showed hit you in the last 30 seconds.", 0xFFB8C0C8});
		} else {
			lines.add(new Object[] {recap.hits + (recap.hits == 1 ? " hit" : " hits") + (recap.totems == 0 ? "" : " · " + recap.totems
					+ (recap.totems == 1 ? " totem popped" : " totems popped")), 0xFFB8C0C8});
			lines.add(null);
			for (Source s : recap.sources.subList(0, Math.min(4, recap.sources.size()))) {
				lines.add(new Object[] {s.name + ": " + s.hits + (s.hits == 1 ? " hit" : " hits"), s.player ? 0xFFFF7A6E : 0xFFFFC14D});
				if (!s.with.isEmpty()) {
					List<String> parts = new ArrayList<>();
					s.with.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
							.forEach(en -> parts.add(en.getKey() + (en.getValue() > 1 ? " ×" + en.getValue() : "")));
					lines.add(new Object[] {"  " + String.join(", ", parts), 0xFFB8C0C8});
				}
			}
			lines.add(null);
			lines.add(new Object[] {"Last moments", 0xFFFFFFFF});
			for (EventLog.Entry e : recap.last) {
				String when = String.format(Locale.ROOT, "%.1fs", Math.max(0, died - e.t) / 1000.0);
				String what = e.type == EventLog.Type.TOTEM ? "totem popped"
						: (e.actor.isEmpty() ? damageType(e.detail) : e.actor + " · " + how(e.detail));
				String left = e.health >= 0 ? String.format(Locale.ROOT, " · %.1f♥", e.health / 2) : "";
				lines.add(new Object[] {when + "  " + what + left, e.type == EventLog.Type.TOTEM ? 0xFFFFD814 : 0xFFE7E9EC});
			}
		}
		List<Object[]> wrapped = new ArrayList<>();
		for (Object[] l : lines) {
			if (l == null) {
				wrapped.add(null);
				continue;
			}
			for (var part : font.wrapLines(Text.literal((String) l[0]), w - 12)) wrapped.add(new Object[] {part, l[1]});
		}
		int h = 8;
		for (Object[] l : wrapped) h += l == null ? 4 : 10;
		h = Math.min(h + 4, maxH);
		context.fill(x, y, x + w, y + h, 0xE0101418);
		context.fill(x, y, x + 2, y + h, 0xFFFF5A4A);
		int ty = y + 6;
		for (Object[] l : wrapped) {
			if (ty > y + h - 10) break;
			if (l == null) {
				ty += 4;
				continue;
			}
			context.drawText(font, (net.minecraft.text.OrderedText) l[0], x + 8, ty, (int) l[1], false);
			ty += 10;
		}
	}
}
