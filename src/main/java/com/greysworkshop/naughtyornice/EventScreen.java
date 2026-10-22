package com.greysworkshop.naughtyornice;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Events: record what happens (or save the last 30 minutes after the fact), then read it back: how it started, every
 * kill and its weapon, a line per player, and a timeline of everything, filtered by player and kind.
 */
public class EventScreen extends Screen {
	private enum View { SUMMARY, KILLS, PLAYERS, TIMELINE, REPLAY }

	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
	private static View view = View.SUMMARY;
	/** null = the live black box; otherwise a saved recording. */
	private static Path open;
	private TextFieldWidget nameField, filterField;
	private static String typedName = "";
	private static EventLog.Type typeFilter;
	private EventReport report;
	private long reportBuiltAt;
	private double scroll;
	private String status = "";
	private int statusColor = 0xFFAAAAAA;
	private final List<Object[]> hits = new ArrayList<>();
	private List<Path> recordings = List.of();

	public EventScreen() {
		super(Text.literal("Events"));
	}

	private int left() {
		return Math.max(6, width / 2 - 260);
	}

	/** How wide the screen's content is (at most 520). */
	private int span() {
		return Math.min(520, width - 12);
	}

	@Override
	protected void init() {
		int x = left(), y = 6;
		// The top row shares the width: name box, record, save, folder.
		int span = span(), gap = 4;
		int nameW = span * 26 / 100, recW = span * 25 / 100, saveW = span * 28 / 100, folderW = span - nameW - recW - saveW - 3 * gap;
		nameField = new TextFieldWidget(textRenderer, x, y, nameW, 18, Text.literal("Name"));
		nameField.setPlaceholder(Text.literal("Name it (optional)").withColor(0xFF777777));
		nameField.setText(typedName);
		nameField.setChangedListener(t -> typedName = t);
		addDrawableChild(nameField);
		addDrawableChild(ButtonWidget.builder(Text.literal(EventLog.recording() ? "■ Stop recording" : "● Start recording"), b -> {
			if (EventLog.recording()) {
				Path saved = EventLog.stopRecording();
				open = saved;
				report = null;
				say("Saved the recording", 0xFF7CE0A0);
			} else {
				String why = EventLog.startRecording(nameField.getText());
				say(why == null ? "Recording everything (the last minute is included)" : why, why == null ? 0xFF7CE0A0 : 0xFFFF8A80);
			}
			clearAndInit();
		}).dimensions(x + nameW + gap, y, recW, 18).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Save last 30 minutes"), b -> {
			try {
				Path saved = EventLog.saveLast(30, nameField.getText());
				if (saved == null) {
					say("Nothing in the last 30 minutes yet", 0xFFFFC14D);
				} else {
					open = saved;
					report = null;
					say("Saved the last 30 minutes", 0xFF7CE0A0);
				}
			} catch (IOException e) {
				say("Couldn't save: " + e.getMessage(), 0xFFFF8A80);
			}
			clearAndInit();
		}).dimensions(x + nameW + recW + 2 * gap, y, saveW, 18).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Open folder"), b -> {
			try {
				java.nio.file.Files.createDirectories(EventLog.folder());
			} catch (IOException ignored) {
				// Opening will just fail quietly.
			}
			Util.getOperatingSystem().open(EventLog.folder());
		}).dimensions(x + nameW + recW + saveW + 3 * gap, y, folderW, 18).build());
		// The report's views, a filter, and export.
		y = 30;
		int vx = x + 132;
		int viewW = (span - 132 - 5 * 3 - 40) / 5;
		for (View v : View.values()) {
			String label = switch (v) {
				case SUMMARY -> "Summary";
				case KILLS -> "Kills";
				case PLAYERS -> "Players";
				case TIMELINE -> "Timeline";
				case REPLAY -> "Replay";
			};
			ButtonWidget button = ButtonWidget.builder(Text.literal(label), b -> {
				view = v;
				scroll = 0;
				clearAndInit();
			}).dimensions(vx, y, viewW, 18).build();
			// The view you're on shows as pressed.
			button.active = v != view;
			addDrawableChild(button);
			vx += viewW + 3;
		}
		addDrawableChild(ButtonWidget.builder(Text.literal("CSV"), b -> exportCsv()).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Export as a spreadsheet (CSV)"))).dimensions(vx + 1, y, x + span - vx - 1, 18).build());
		if (view == View.TIMELINE) {
			filterField = new TextFieldWidget(textRenderer, x + 132, y + 22, 150, 16, Text.literal("Player"));
			filterField.setPlaceholder(Text.literal("Only this player").withColor(0xFF777777));
			filterField.setChangedListener(t -> scroll = 0);
			addDrawableChild(filterField);
			addDrawableChild(ButtonWidget.builder(Text.literal(typeFilter == null ? "All kinds ▸" : typeFilter.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " ▸"), b -> {
				EventLog.Type[] types = EventLog.Type.values();
				typeFilter = typeFilter == null ? types[0] : typeFilter.ordinal() + 1 < types.length ? types[typeFilter.ordinal() + 1] : null;
				scroll = 0;
				clearAndInit();
			}).dimensions(x + 286, y + 22, 120, 16).build());
		}
		recordings = EventLog.recordings();
	}

	private void say(String s, int color) {
		status = s;
		statusColor = color;
	}

	private void exportCsv() {
		EventReport r = report();
		if (r == null || r.entries.isEmpty()) {
			say("Nothing to export", 0xFFFFC14D);
			return;
		}
		try {
			String base = open == null ? "black box" : open.getFileName().toString().replace(".jsonl", "");
			Path file = EventLog.folder().resolve(base + ".csv");
			java.nio.file.Files.createDirectories(file.getParent());
			EventLog.exportCsv(r.entries, file);
			say("Exported to " + file.getFileName() + " (Open folder)", 0xFF7CE0A0);
		} catch (IOException e) {
			say("Couldn't export: " + e.getMessage(), 0xFFFF8A80);
		}
	}

	/** The report on what's open: the live black box is rebuilt every second. */
	private EventReport report() {
		long now = System.currentTimeMillis();
		if (report != null && (open != null || now - reportBuiltAt < 1000)) return report;
		List<EventLog.Entry> entries;
		try {
			entries = open == null ? EventLog.blackBox() : EventLog.load(open);
		} catch (IOException e) {
			say("Couldn't read it: " + e.getMessage(), 0xFFFF8A80);
			entries = List.of();
		}
		Set<String> players = new HashSet<>();
		for (Profile p : NaughtyOrNiceClient.INSTANCE.ledger.all()) players.add(p.name.toLowerCase(Locale.ROOT));
		if (client.player != null) players.add(client.player.getGameProfile().name().toLowerCase(Locale.ROOT));
		report = new EventReport(entries, players);
		reportBuiltAt = now;
		return report;
	}

	static String clock(long t) {
		return Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(CLOCK);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		hits.clear();
		int x = left();
		// Recordings, on the left.
		int ly = 32;
		context.drawTextWithShadow(textRenderer, "Recordings", x, ly, 0xFFFFFFFF);
		ly += 12;
		ly = recordingRow(context, mouseX, mouseY, x, ly, null, EventLog.recording() ? "● Recording now: " + EventLog.recordingName() : "Last 30 minutes (live)");
		for (Path p : recordings) {
			if (ly > height - 30) break;
			ly = recordingRow(context, mouseX, mouseY, x, ly, p, label(p));
		}
		if (EventLog.recording()) {
			String rec = "● Recording: " + EventLog.recordedCount() + " entries, " + ShopScreen.duration((int) ((System.currentTimeMillis() - EventLog.recordingSince()) / 1000));
			context.drawTextWithShadow(textRenderer, rec, x + span() - textRenderer.getWidth(rec), height - 12, 0xFFFF8080);
		}
		// The report.
		EventReport r = report();
		int rx = x + 132, ry = view == View.TIMELINE ? 74 : 54, rw = x + span() - rx;
		if (view == View.REPLAY) {
			replay(context, r, rx, ry, rw, height - 16 - ry, mouseX, mouseY);
			if (!status.isEmpty()) context.drawTextWithShadow(textRenderer, status, x, height - 12, statusColor);
			return;
		}
		context.enableScissor(rx, ry, rx + rw, height - 16);
		int y = ry - (int) scroll;
		if (r.entries.isEmpty()) {
			context.drawTextWithShadow(textRenderer, "Nothing recorded yet: the log fills in as things happen around you.", rx, y, 0xFFAAAAAA);
		} else {
			y = switch (view) {
				case SUMMARY -> summary(context, r, rx, y, rw);
				case KILLS -> kills(context, r, rx, y, rw);
				case PLAYERS -> playersTable(context, r, rx, y, rw);
				case TIMELINE -> timeline(context, r, rx, y, rw);
				case REPLAY -> y;
			};
		}
		context.disableScissor();
		maxScroll = Math.max(0, y + (int) scroll - (height - 16));
		if (!status.isEmpty()) context.drawTextWithShadow(textRenderer, status, x, height - 12, statusColor);
	}

	private int maxScroll;

	/** "Oct 22 04:09 · self-test" from "2026-10-22 04-09-13 self-test.jsonl". */
	static String label(Path p) {
		String n = p.getFileName().toString().replace(".jsonl", "");
		try {
			java.time.LocalDateTime when = java.time.LocalDateTime.parse(n.substring(0, 19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss", Locale.ROOT));
			String name = n.length() > 20 ? n.substring(20) : "";
			return when.format(DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT)) + (name.isEmpty() ? "" : " · " + name);
		} catch (RuntimeException e) {
			return n;
		}
	}

	private static String count(int n, String one, String many) {
		return n + " " + (n == 1 ? one : many);
	}

	private int recordingRow(DrawContext context, int mouseX, int mouseY, int x, int y, Path p, String label) {
		boolean on = p == null ? open == null : p.equals(open);
		boolean hover = mouseX >= x && mouseX < x + 126 && mouseY >= y && mouseY < y + 11;
		context.drawText(textRenderer, textRenderer.trimToWidth((on ? "› " : "") + label, 126), x, y, on ? 0xFFFFFFFF : hover ? 0xFF8AB4FF : 0xFF6FA8DC, false);
		hits.add(new Object[] {x, y, x + 126, y + 11, (Runnable) () -> {
			open = p;
			report = null;
			scroll = 0;
		}});
		return y + 12;
	}

	private int line(DrawContext context, String s, int x, int y, int w, int color) {
		for (var l : textRenderer.wrapLines(Text.literal(s), w)) {
			context.drawText(textRenderer, l, x, y, color, false);
			y += 10;
		}
		return y;
	}

	private int summary(DrawContext context, EventReport r, int x, int y, int w) {
		long secs = (r.end - r.start) / 1000;
		y = line(context, "From " + clock(r.start) + " to " + clock(r.end) + " (" + ShopScreen.duration((int) secs) + ") · " + count(r.players.size(), "player", "players")
				+ " · " + count(r.kills.size(), "death", "deaths") + " · " + count(r.entries.size(), "entry", "entries"), x, y, w, 0xFFDDDDDD) + 6;
		context.drawTextWithShadow(textRenderer, "How the fighting started", x, y, 0xFFFFB020);
		y += 12;
		if (r.firstHit == null) {
			y = line(context, "No player hit another player in this recording.", x, y, w, 0xFFAAAAAA);
		} else {
			EventLog.Entry h = r.firstHit;
			y = line(context, clock(h.t) + ": " + h.actor + " hit " + h.target + (h.detail.isEmpty() ? "" : " (" + h.detail + ")")
					+ (h.located() ? " at " + ModsPanel.hide(String.format(Locale.ROOT, "%d, %d, %d", (int) h.x, (int) h.y, (int) h.z)) : ""), x, y, w, 0xFFFFFFFF);
			y = line(context, "This is the first hit between two players: the server says who damaged whom, so it's certain.", x, y, w, 0xFF888888);
			List<EventLog.Entry> swings = r.swingsBeforeFirstHit();
			if (!swings.isEmpty()) {
				EventLog.Entry s = swings.get(0);
				y = line(context, "First swing before it: " + s.actor + " at " + clock(s.t) + (s.target.isEmpty() ? "" : ", at " + s.target), x, y, w, 0xFFDDDDDD);
			}
		}
		y += 8;
		context.drawTextWithShadow(textRenderer, "Most kills", x, y, 0xFFFFB020);
		y += 12;
		int shown = 0;
		for (EventReport.Player p : r.ranked()) {
			if (shown++ >= 5 || p.kills == 0 && p.hitsDealt == 0) break;
			y = line(context, p.name + ": " + count(p.kills, "kill", "kills") + ", " + count(p.deaths, "death", "deaths") + ", "
					+ count(p.hitsDealt, "hit", "hits") + " dealt, " + p.hitsTaken + " taken" + (p.totems > 0 ? ", " + count(p.totems, "totem", "totems") + " popped" : ""),
					x, y, w, 0xFFDDDDDD);
		}
		return y;
	}

	private int kills(DrawContext context, EventReport r, int x, int y, int w) {
		if (r.kills.isEmpty()) return line(context, "Nobody died in this recording.", x, y, w, 0xFFAAAAAA);
		for (EventLog.Entry k : r.kills) {
			String who = k.actor.isEmpty() ? k.target + " died" : k.actor + " killed " + k.target;
			y = line(context, clock(k.t) + "  " + who + (k.detail.isEmpty() ? "" : " · " + k.detail), x, y, w, k.actor.isEmpty() ? 0xFFAAAAAA : 0xFFFF8A80);
		}
		return y;
	}

	private int playersTable(DrawContext context, EventReport r, int x, int y, int w) {
		String[] heads = {"Player", "Kills", "Deaths", "Hits", "Taken", "Totems", "Crystals", "Shots", "Chat"};
		int[] cols = {0, 110, 145, 185, 220, 258, 300, 350, 390};
		for (int i = 0; i < heads.length; i++) context.drawText(textRenderer, heads[i], x + cols[i], y, 0xFFFFB020, false);
		y += 12;
		for (EventReport.Player p : r.ranked()) {
			int[] v = {p.kills, p.deaths, p.hitsDealt, p.hitsTaken, p.totems, p.crystals, p.shots, p.chat};
			context.drawText(textRenderer, textRenderer.trimToWidth(p.name, 106), x, y, 0xFFFFFFFF, false);
			for (int i = 0; i < v.length; i++) context.drawText(textRenderer, String.valueOf(v[i]), x + cols[i + 1], y, v[i] == 0 ? 0xFF666666 : 0xFFDDDDDD, false);
			y += 11;
			String top = PlayerStats.top(p.hit), by = PlayerStats.top(p.hitBy), weapon = PlayerStats.top(p.weapons);
			if (top != null || by != null || weapon != null) {
				y = line(context, "   " + (top == null ? "" : "hit " + top + " most") + (by == null ? "" : (top == null ? "" : " · ") + "hit most by " + by)
						+ (weapon == null ? "" : " · mostly with " + weapon), x, y, w, 0xFF888888);
			}
		}
		return y;
	}

	private int timeline(DrawContext context, EventReport r, int x, int y, int w) {
		String who = filterField == null ? "" : filterField.getText().strip().toLowerCase(Locale.ROOT);
		int shown = 0;
		for (EventLog.Entry e : r.entries) {
			if (typeFilter == null && e.type == EventLog.Type.POSITION) continue;
			if (typeFilter != null && e.type != typeFilter) continue;
			if (!who.isEmpty() && !e.actor.toLowerCase(Locale.ROOT).contains(who) && !e.target.toLowerCase(Locale.ROOT).contains(who)) continue;
			if (y > height + 200) {
				y += 10;
				continue;
			}
			String what = switch (e.type) {
				case CHAT -> e.actor + ": " + e.detail;
				case WHISPER -> e.actor + " → " + e.target + ": " + e.detail;
				case JOIN -> e.actor + " joined";
				case LEAVE -> e.actor + " left";
				case DEATH -> (e.actor.isEmpty() ? e.target + " died" : e.actor + " killed " + e.target) + (e.detail.isEmpty() ? "" : " · " + e.detail);
				case HIT -> (e.actor.isEmpty() ? "?" : e.actor) + " hit " + e.target + (e.detail.isEmpty() ? "" : " · " + e.detail) + (e.health >= 0 ? String.format(Locale.ROOT, " · %.1f♥ left", e.health / 2) : "");
				case SWING -> e.actor + " swung" + (e.target.isEmpty() ? "" : " at " + e.target);
				case TOTEM -> e.actor + " popped a totem";
				case SHOT -> e.actor + " fired " + e.target;
				case CRYSTAL_PLACED -> "A " + e.target + " appeared · " + e.detail;
				case CRYSTAL_SET_OFF -> (e.actor.isEmpty() ? "Something" : e.actor) + " set off an end crystal";
				case EXPLOSION -> "Explosion · " + e.detail;
				case GEAR -> e.actor + " changed gear: " + e.detail;
				case POSITION -> e.actor + " at " + ModsPanel.hide(String.format(Locale.ROOT, "%d, %d, %d", (int) e.x, (int) e.y, (int) e.z)) + (e.detail.isEmpty() ? "" : " · " + e.detail);
				case ADVANCEMENT -> e.actor + " made " + e.detail;
				case NOTE -> e.detail;
			};
			int color = switch (e.type) {
				case DEATH -> 0xFFFF6060;
				case HIT -> 0xFFFF9A6A;
				case TOTEM, CRYSTAL_SET_OFF, EXPLOSION -> 0xFFFFB020;
				case CHAT, WHISPER -> 0xFFDDDDDD;
				case JOIN, LEAVE, NOTE -> 0xFF8AB4FF;
				default -> 0xFFAAAAAA;
			};
			y = line(context, clock(e.t) + "  " + what, x, y, w, color);
			if (++shown >= 2000) {
				y = line(context, "… narrow it down with the player box or the kind button to see the rest.", x, y, w, 0xFF888888);
				break;
			}
		}
		if (shown == 0) y = line(context, "Nothing matches.", x, y, w, 0xFFAAAAAA);
		return y;
	}

	private ReplayView replay;
	private EventReport replayOf;

	/** The replay map, with play, speed and "first hit" above it. */
	private void replay(DrawContext context, EventReport r, int x, int y, int w, int h, int mouseX, int mouseY) {
		if (replay == null || replayOf != r) {
			replay = new ReplayView(r.entries);
			replayOf = r;
			replay.toFirstHit(r.firstHit);
		}
		if (replay.empty()) {
			line(context, "No one's position was recorded here, so there's nothing to replay. Positions are logged once a second for "
					+ "everyone in view (this needs a recording from version 1.12.0 or later to include you).", x, y, w, 0xFFAAAAAA);
			return;
		}
		int bx = x;
		bx += replayButton(context, mouseX, mouseY, bx, y, replay.playing ? "❚❚ Pause" : "▶ Play", () -> replay.playing = !replay.playing) + 4;
		bx += replayButton(context, mouseX, mouseY, bx, y, replay.speed + "× speed", replay::faster) + 4;
		if (r.firstHit != null) bx += replayButton(context, mouseX, mouseY, bx, y, "First hit", () -> replay.toFirstHit(r.firstHit)) + 4;
		String dim = replay.dimension.replace("minecraft:", "").replace('_', ' ');
		context.drawText(textRenderer, dim, x + w - textRenderer.getWidth(dim), y + 4, 0xFF888888, false);
		replay.render(context, textRenderer, x, y + 18, w, h - 18);
		hits.add(new Object[] {replay.sliderX, replay.sliderY - 4, replay.sliderX + replay.sliderW, replay.sliderY + 12, (Runnable) () -> {
			replay.playing = false;
			replay.seek((lastClickX - replay.sliderX) / (double) replay.sliderW);
		}});
	}

	private int replayButton(DrawContext context, int mouseX, int mouseY, int x, int y, String label, Runnable run) {
		int w = textRenderer.getWidth(label) + 12;
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 14;
		context.fill(x, y, x + w, y + 14, over ? 0xFF36414E : 0xFF2A333E);
		context.drawText(textRenderer, label, x + 6, y + 3, 0xFFE7E9EC, false);
		hits.add(new Object[] {x, y, x + w, y + 14, run});
		return w;
	}

	private double lastClickX;

	/** Shows the replay (at the first hit); returns how many players it has. For the self-test. */
	int testReplay() {
		view = View.REPLAY;
		clearAndInit();
		replay = new ReplayView(report().entries);
		replayOf = report();
		replay.toFirstHit(replayOf.firstHit);
		return replay.players();
	}

	@Override
	public boolean mouseDragged(Click click, double dx, double dy) {
		if (view == View.REPLAY && replay != null && click.y() >= replay.sliderY - 6 && click.y() < replay.sliderY + 14) {
			replay.playing = false;
			replay.seek((click.x() - replay.sliderX) / (double) replay.sliderW);
			return true;
		}
		return super.mouseDragged(click, dx, dy);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		lastClickX = click.x();
		for (Object[] hit : List.copyOf(hits)) {
			if (click.x() >= (int) hit[0] && click.x() < (int) hit[2] && click.y() >= (int) hit[1] && click.y() < (int) hit[3]) {
				((Runnable) hit[4]).run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll = Math.max(0, Math.min(maxScroll, scroll - verticalAmount * 20));
		return true;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
