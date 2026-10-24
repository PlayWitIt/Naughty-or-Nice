package com.greysworkshop.naughtyornice;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Baritone with buttons and boxes instead of commands, in Naughty or Nice's own look. A header that's always there
 * says what Baritone is doing right now (where to, how far, how long, how fast) with Pause, Resume and Stop. Under it:
 * Go to… (every known place as a card, filtered by kind), Do (coordinates, follow, mine, explore, farm, fly, download),
 * Highway roam (the highways picked on a compass), Settings (switches, and every Baritone setting) and Every command.
 * It sizes itself like the main screen (Settings → Screen size), whatever the game's GUI scale. Opened with B,
 * /nn baritone, or the Baritone tab.
 */
public class BaritoneScreen extends Screen {
	private enum Page { PLACES, GO, HIGHWAY, SETTINGS, COMMANDS }

	// Naughty or Nice's dark theme.
	private static final int NAV = 0xFF0A0D11, SUBNAV = 0xFF161D26, PAGE_BG = 0xFF0F1318, CARD = 0xFF1A2129, FIELD = 0xFF0F141A, BORDER = 0xFF2D3742,
			TEXT = 0xFFE7E9EC, MUTED = 0xFF9AA5B1, DIM = 0xFF6B7682, LINK = 0xFF5CC8E0, SOFT = 0xFF25303B, SOFT_HOVER = 0xFF2E3A46,
			HOVER_BG = 0xFF222B35, HOVER_BORDER = 0xFF4A5663, INPUT_BORDER = 0xFF4A5663, YELLOW = 0xFFFFD814, YELLOW_EDGE = 0xFFFCD200,
			BUTTON_TEXT = 0xFF0F1111, GOOD = 0xFF4CD39B, BAD = 0xFFFF7A66, AMBER = 0xFFFFC14D, BLUE = 0xFF5BA8FF, PURPLE = 0xFFB388FF;
	/** Status colours: done, error, warning, plain. */
	private static final int OK = 0xFF7CE0A0, ERROR = 0xFFFF8A80, WARN = 0xFFFFC14D, PLAIN = 0xFFDDDDDD;

	/** Header (status and Stop), then the page tabs, then the page; the status bar along the bottom. */
	private Pill pauseButton, resumeButton;
	/** The compass's middle (x, y, w, h) when it's a "you are here" mark, not a button. */
	private int[] compassYou;
	private static final int HEADER = 34, TABS = 24, TOP = HEADER + TABS + 8, STATUS_H = 18;

	private static Page page = Page.PLACES;
	/** Go to…: which kind of place is shown (null: all). */
	private static Destinations.Kind kindFilter;
	/** Settings: only the ones changed from Baritone's defaults. */
	private static boolean changedOnly;
	private Field searchField, waypointField;
	private double listScroll;
	/** Do and Highway roam: how far the page is scrolled (it's laid out again when it moves). */
	private static int pageScroll;
	private int pageHeight;
	/** Rows drawn this frame, for clicks: x0, y0, x1, y1, what a click does, its key, and whether it's the ×. */
	private final List<Object[]> rowHits = new ArrayList<>();
	private Field xField, yField, zField, playerField, blockField, heightField, distanceField, roamXField, roamZField;
	private String status = "";
	private int statusColor = MUTED;
	private long statusAt;
	/**
	 * The job last handed to Baritone, watched while this screen is open: when Baritone stops it (done, or given up:
	 * nothing to farm, nobody to follow), the status says so rather than still saying it's working.
	 */
	private String watchProcess, watchFailed;

	/** Every command: the search, the one picked, what to add after it, and what Baritone suggests for that. */
	private Field commandSearch, argsField;
	private static String pickedCommand;
	private List<BaritoneBridge.Command> commands = List.of();
	private String suggestedFor;
	private List<String> suggestions = List.of();
	/** Settings: the search, the one picked (to type a value for), and Baritone's settings as last read. */
	private Field settingSearch, valueField;
	private static String pickedSetting;
	private List<BaritoneBridge.Setting> settings = List.of();
	private long settingsReadAt;

	/** What the page draws behind its widgets, worked out when it's laid out: cards, labels, and the boxes' frames. */
	private record Card(int x, int y, int w, int h, String title, String note) {}

	private final List<Card> cards = new ArrayList<>();
	private final List<Object[]> labels = new ArrayList<>();
	private final List<Field> fields = new ArrayList<>();
	/** Highway roam: where the compass is, and where the way it'll go is written. */
	private int compassX, compassY, routeX, routeY, routeW;

	/** The screen is laid out at its own size and drawn scaled by this, like the main screen. */
	private float uiScale = 1;

	public BaritoneScreen() {
		super(Text.literal("Baritone"));
	}

	/** Where Esc goes back to (the game when there's none), e.g. Naughty or Nice when opened from its Baritone tab. */
	private Screen parent;

	public BaritoneScreen withParent(Screen parent) {
		this.parent = parent;
		return this;
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	// ---- Widgets in the mod's look ----

	/** How a pill looks: the main action, a quiet one, picked, or one that stops things. */
	private enum Look { PRIMARY, SOFT, PICKED, DANGER, OVERWORLD, NETHER }

	/** A rounded button. Its message is its label (what the self-test presses it by); a badge can go after it. */
	private final class Pill extends ClickableWidget {
		private final Runnable run;
		private final Look look;
		private Supplier<String> badge;
		private String icon;

		Pill(int x, int y, int w, int h, String label, Look look, Runnable run) {
			super(x, y, w, h, Text.literal(label));
			this.run = run;
			this.look = look;
		}

		Pill badge(Supplier<String> badge) {
			this.badge = badge;
			return this;
		}

		Pill icon(String icon) {
			this.icon = icon;
			return this;
		}

		@Override
		protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			boolean hover = isHovered() && active;
			int fill, edge, text;
			switch (look) {
				case PRIMARY -> {
					fill = hover ? 0xFFF7CA00 : YELLOW;
					edge = YELLOW_EDGE;
					text = BUTTON_TEXT;
				}
				case PICKED -> {
					fill = hover ? 0xFF2C4A5E : 0xFF243E50;
					edge = LINK;
					text = TEXT;
				}
				case DANGER -> {
					fill = hover ? 0xFFC0453F : 0xFF8F2B2B;
					edge = hover ? 0xFFE0605A : 0xFFB03A35;
					text = 0xFFFFFFFF;
				}
				case OVERWORLD -> {
					fill = hover ? 0xFF4CA0FF : 0xFF2E6BBF;
					edge = hover ? 0xFF7CBCFF : 0xFF3F80D6;
					text = 0xFFFFFFFF;
				}
				case NETHER -> {
					fill = hover ? 0xFFC0453F : 0xFF8F2B2B;
					edge = hover ? 0xFFE0605A : 0xFFB03A35;
					text = 0xFFFFFFFF;
				}
				default -> {
					fill = !active ? tint(SOFT, NAV, 0.45f) : hover ? SOFT_HOVER : SOFT;
					edge = !active ? tint(BORDER, NAV, 0.45f) : hover ? HOVER_BORDER : BORDER;
					text = active ? TEXT : DIM;
				}
			}
			roundedBorder(context, getX(), getY(), width, height, fill, edge);
			String label = getMessage().getString();
			if (icon != null && height >= 24) {
				// Tall: the icon above the label (the compass).
				int sub = look == Look.PRIMARY ? 0xFF5A4A00 : MUTED;
				context.drawText(textRenderer, icon, getX() + (width - textRenderer.getWidth(icon)) / 2, getY() + 4, sub, false);
				context.drawText(textRenderer, label, getX() + (width - textRenderer.getWidth(label)) / 2, getY() + 14, text, false);
				return;
			}
			String b = badge == null ? null : badge.get();
			String full = (icon == null ? "" : icon + " ") + label;
			int bw = b == null ? 0 : textRenderer.getWidth(b) + 6;
			String shown = fit(full, width - 8 - bw);
			int tw = textRenderer.getWidth(shown) + bw;
			int tx = getX() + (width - tw) / 2, ty = getY() + (height - 8) / 2;
			context.drawText(textRenderer, shown, tx, ty, text, false);
			if (b != null) context.drawText(textRenderer, b, tx + tw - bw + 6, ty, look == Look.PRIMARY ? 0xFF5A4A00 : MUTED, false);
		}

		@Override
		public void onClick(Click click, boolean doubled) {
			run.run();
		}

		@Override
		protected void appendClickableNarrations(NarrationMessageBuilder builder) {
			appendDefaultNarrations(builder);
		}
	}

	/** An on/off switch with its label; the message is the label. */
	private final class Toggle extends ClickableWidget {
		private final BooleanSupplier get;
		private final Consumer<Boolean> set;

		Toggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
			super(x, y, w, 14, Text.literal(label));
			this.get = get;
			this.set = set;
		}

		@Override
		protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			boolean on = get.getAsBoolean();
			drawSwitch(context, getX(), getY() + 2, on, isHovered());
			context.drawText(textRenderer, fit(getMessage().getString(), width - 26), getX() + 24, getY() + 3, isHovered() ? 0xFFFFFFFF : TEXT, false);
		}

		@Override
		public void onClick(Click click, boolean doubled) {
			set.accept(!get.getAsBoolean());
		}

		@Override
		protected void appendClickableNarrations(NarrationMessageBuilder builder) {
			appendDefaultNarrations(builder);
		}
	}

	/** A text box drawn on the mod's own field (see {@link #drawPage}); its hint is drawn quietly, with no shadow. */
	private final class Field extends TextFieldWidget {
		private Text hint = Text.empty();

		Field(int x, int y, int w, String hint) {
			super(textRenderer, x, y, w, 10, Text.literal(hint));
			setDrawsBackground(false);
			setEditableColor(TEXT);
			this.hint = Text.literal(hint);
		}

		@Override
		public void setPlaceholder(Text placeholder) {
			hint = placeholder;
		}

		@Override
		public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			super.renderWidget(context, mouseX, mouseY, delta);
			if (getText().isEmpty() && !isFocused()) context.drawText(textRenderer, fit(hint.getString(), width), getX(), getY(), DIM, false);
		}
	}

	private Pill pill(int x, int y, int w, String label, Look look, Runnable run) {
		return pill(x, y, w, 16, label, look, run);
	}

	private Pill pill(int x, int y, int w, int h, String label, Look look, Runnable run) {
		Pill p = new Pill(x, y, w, h, label, look, run);
		addDrawableChild(p);
		hideIfOutside(p);
		return p;
	}

	/** A pill as wide as its label needs. */
	private Pill fitPill(int x, int y, String label, Look look, Runnable run) {
		return pill(x, y, textRenderer.getWidth(label) + 16, label, look, run);
	}

	private Toggle toggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
		Toggle t = new Toggle(x, y, w, label, get, v -> {
			set.accept(v);
			NNConfig.save();
		});
		addDrawableChild(t);
		hideIfOutside(t);
		return t;
	}

	/** A box with its name written above it instead of inside (the name stays its hint for the self-test). */
	private Field labelledField(int x, int y, int w, String hint, Field before, String fallback) {
		label(hint, x, y - 9);
		Field f = field(x, y, w, hint, before, fallback);
		f.setPlaceholder(Text.empty());
		return f;
	}

	/** A box, at (x, y) for its text; its frame is drawn round it. Keeps what was typed when the page is laid out again. */
	private Field field(int x, int y, int w, String hint, Field before, String fallback) {
		Field f = new Field(x + 5, y + 4, w - 10, hint);
		f.setMaxLength(256);
		f.setText(before != null ? before.getText() : fallback == null ? "" : fallback);
		addDrawableChild(f);
		fields.add(f);
		hideIfOutside(f);
		return f;
	}

	/** Do and Highway roam scroll by being laid out again; what's off the page isn't shown or clickable. */
	private void hideIfOutside(ClickableWidget w) {
		if (w.getY() >= TOP - 2 && w.getY() + w.getHeight() <= height - STATUS_H - 2) return;
		if (w.getY() < HEADER + TABS) return;
		w.visible = false;
	}

	/** The text cut to fit {@code maxWidth}, ending in "…" if anything was cut. */
	private String fit(String s, int maxWidth) {
		if (textRenderer.getWidth(s) <= maxWidth) return s;
		return textRenderer.trimToWidth(s, Math.max(0, maxWidth - textRenderer.getWidth("…"))).stripTrailing() + "…";
	}

	/** {@link #fit} for bold text, which is a pixel wider a letter. */
	private String fitBold(String s, int maxWidth) {
		String shown = s;
		while (shown.length() > 1 && textRenderer.getWidth(Text.literal(shown).styled(st -> st.withBold(true))) > maxWidth) {
			shown = s.substring(0, shown.length() - (shown.endsWith("…") ? 2 : 1)).stripTrailing() + "…";
		}
		return shown;
	}

	private Card card(int x, int y, int w, int h, String title, String note) {
		Card c = new Card(x, y, w, h, title, note);
		cards.add(c);
		return c;
	}

	private void label(String text, int x, int y) {
		labels.add(new Object[] {text, x, y});
	}

	// ---- Layout ----

	@Override
	protected void init() {
		var window = client.getWindow();
		uiScale = ShopScreen.pixelsPerUnit(window.getFramebufferWidth(), window.getFramebufferHeight(), NNConfig.INSTANCE.screenSize)
				/ Math.max(1, window.getScaleFactor());
		width = (int) Math.ceil(window.getScaledWidth() / uiScale);
		height = (int) Math.ceil(window.getScaledHeight() / uiScale);
		cards.clear();
		labels.clear();
		compassYou = null;
		fields.clear();

		// Header: Pause, Resume and Stop, always.
		int right = width - 10;
		Pill stop = pill(right - 70, 9, 70, 16, "Stop", Look.DANGER, () -> {
			HighwayRoam.stop(null);
			SmartTravel.stop();
			TrailFollow.stop(null);
			run("stop", "Stopped");
			clearAndInit();
		});
		stop.icon("■");
		resumeButton = pill(right - 70 - 4 - 62, 9, 62, 16, "Resume", Look.SOFT, () -> {
			// A roam paused for a stranger or a trail knows it's paused; anything else, Baritone resumes itself.
			if (HighwayRoam.active() && HighwayRoam.paused()) {
				HighwayRoam.resume();
				say("Going again", OK);
			} else if (TrailFollow.active() && TrailFollow.paused()) {
				TrailFollow.resume(client);
				say("Following again", OK);
			} else {
				run("resume", "Going again");
			}
		}).icon("▶");
		pauseButton = pill(right - 70 - 4 - 62 - 4 - 58, 9, 58, 16, "Pause", Look.SOFT, () -> run("pause", "Paused"));
		updateHeaderButtons();

		// The pages.
		int tx = contentX();
		for (Page p : Page.values()) {
			String label = switch (p) {
				case PLACES -> "Go to…";
				case GO -> "Do";
				case HIGHWAY -> "Highway roam";
				case SETTINGS -> "Settings";
				case COMMANDS -> "Every command";
			};
			Pill tab = pill(tx, HEADER + 4, textRenderer.getWidth(label) + 18, 16, label, p == page ? Look.PRIMARY : Look.SOFT, () -> {
				if (page != p) {
					page = p;
					listScroll = 0;
					pageScroll = 0;
				}
				clearAndInit();
			});
			tx += tab.getWidth() + 4;
		}
		if (!BaritoneBridge.present() || ModsPanel.streamSafe()) return;
		int x = contentX(), y = TOP - pageScroll;
		switch (page) {
			case PLACES -> initPlaces(contentX(), TOP);
			case GO -> pageHeight = initGo(x, y) - y;
			case HIGHWAY -> pageHeight = initHighway(x, y) - y;
			case SETTINGS -> initSettings(contentX(), TOP);
			case COMMANDS -> initCommands(contentX(), TOP);
		}
	}

	/** The page's width: the whole screen up to a comfortable reading width. */
	private int contentW() {
		return Math.min(760, width - 24);
	}

	private int contentX() {
		return (width - contentW()) / 2;
	}

	/** Two columns when there's room. */
	private boolean wide() {
		return contentW() >= 560;
	}

	// ---- Talking to Baritone ----

	/** What Baritone had said before the last command run here; what it says after is its answer. */
	private int replyMark = -1;
	private long ranAt;

	/** Runs a command and shows whether Baritone took it; Baritone's own answer replaces that once it comes. */
	private void run(String command, String said) {
		watchProcess = null;
		BaritoneBridge.listen();
		replyMark = BaritoneBridge.replyCount();
		ranAt = System.currentTimeMillis();
		boolean ok = BaritoneBridge.run(command);
		say(ok ? said : "Baritone didn't take that (see its message in chat)", ok ? OK : ERROR);
	}

	/** Runs a command, then checks a moment later that Baritone's {@code process} really is at it. */
	private void runWatched(String command, String said, String process, String failed) {
		run(command, said);
		if (!status.equals(said)) return;
		watchProcess = process;
		watchFailed = failed;
	}

	private void say(String text, int color) {
		status = text;
		statusColor = color;
		statusAt = System.currentTimeMillis();
	}

	// ---- Do ----

	private int initGo(int x, int y) {
		var p = client.player;
		int w = contentW();
		boolean two = wide();
		int colW = two ? (w - 10) / 2 : w;
		int lx = x, rx = two ? x + colW + 10 : x;

		// Go to coordinates.
		int cy = y;
		int inner = colW - 20;
		card(lx, cy, colW, 150, "Go to coordinates", "Walks, or flies when it can");
		int fy = cy + 42;
		int fw = (inner - 2 * 6 - 44) / 3;
		xField = labelledField(lx + 10, fy, fw, "x", xField, "");
		yField = labelledField(lx + 10 + fw + 6, fy, fw, "y (optional)", yField, "");
		zField = labelledField(lx + 10 + 2 * (fw + 6), fy, fw, "z", zField, "");
		pill(lx + colW - 10 - 44, fy, 44, 18, "Go", Look.PRIMARY, this::goTyped);
		int qy = fy + 24, qx = lx + 10;
		int half = (inner - 4) / 2;
		pill(qx, qy, half, "Use my position", Look.SOFT, () -> {
			if (p == null) return;
			xField.setText(String.valueOf(p.getBlockX()));
			yField.setText(String.valueOf(p.getBlockY()));
			zField.setText(String.valueOf(p.getBlockZ()));
		});
		pill(qx + half + 4, qy, inner - half - 4, "Paste coordinates", Look.SOFT, () -> {
			List<ChatIntel.Coord> coords = ChatIntel.coords(client.keyboard.getClipboard());
			if (coords.isEmpty()) {
				say("No coordinates on the clipboard", WARN);
				return;
			}
			ChatIntel.Coord c = coords.get(0);
			fill(c.x(), c.hasY() ? c.y() : null, c.z());
		});
		qy += 20;
		// The nether calculator: one nether block is eight overworld blocks across (y stays as it is).
		pill(qx, qy, half, "Overworld → Nether (÷8)", Look.SOFT, () -> convert(true));
		pill(qx + half + 4, qy, inner - half - 4, "Nether → Overworld (×8)", Look.SOFT, () -> convert(false));
		qy += 20;
		pill(qx, qy, half, "Latest trail's far end", Look.SOFT, () -> {
			List<ChunkTrails.Trail> trails = NaughtyOrNiceClient.INSTANCE.ledger.chunkTrails.trails;
			if (trails.isEmpty()) {
				say("No chunk trails found yet", WARN);
				return;
			}
			ChunkTrails.Trail t = trails.stream().max(Comparator.comparingLong(tr -> tr.lastSeen)).orElseThrow();
			int[] end = t.awayEnd();
			fill(end[0], null, end[1]);
		});
		pill(qx + half + 4, qy, inner - half - 4, "Latest place given away", Look.SOFT, () -> {
			PlayerStats.Place best = null;
			for (Profile pr : NaughtyOrNiceClient.INSTANCE.ledger.all()) {
				for (PlayerStats.Place pl : pr.stats.places) {
					if (best == null || pl.time > best.time) best = pl;
				}
			}
			if (best == null) {
				say("Nobody has given a place away yet", WARN);
				return;
			}
			fill(best.x, best.hasY() ? best.y : null, best.z);
		});
		qy += 20;
		pill(qx, qy, inner, "Fly there by elytra (nether)", Look.SOFT, () -> {
			Integer[] at = typed();
			if (at == null) return;
			if (client.world == null || client.world.getRegistryKey() != net.minecraft.world.World.NETHER) {
				watchProcess = null;
				say("Elytra flying only works in the nether", ERROR);
				return;
			}
			if (BaritoneBridge.run("goal " + at[0] + " " + at[2])) {
				runWatched("elytra", "Flying to " + at[0] + ", " + at[2], "getElytraProcess", "Flying stopped (there, or see Baritone's message in chat)");
			}
		}).icon("✈");
		int leftEnd = cy + 150 + 10;

		// Following old chunks: along the path someone left through new land.
		NNConfig c = NNConfig.INSTANCE;
		int fcy = leftEnd;
		card(lx, fcy, colW, 112, "Follow old chunks", "Keeps to the path someone left");
		int sy = fcy + 24;
		int seg = (colW - 20) / 2;
		pill(lx + 10, sy, seg, 16, "Away from spawn", c.trailFollowAway ? Look.PICKED : Look.SOFT, () -> {
			c.trailFollowAway = true;
			NNConfig.save();
			clearAndInit();
		});
		pill(lx + 10 + seg, sy, colW - 20 - seg, 16, "The way I'm facing", c.trailFollowAway ? Look.SOFT : Look.PICKED, () -> {
			c.trailFollowAway = false;
			NNConfig.save();
			clearAndInit();
		});
		toggle(lx + 10, sy + 22, colW - 20, "Stop and ask in wide old areas (a base?)", () -> c.trailFollowStopWide, v -> c.trailFollowStopWide = v);
		toggle(lx + 10, sy + 38, colW - 20, "Fly it by elytra (the nether)", () -> c.trailFollowElytra, v -> c.trailFollowElytra = v);
		boolean following = TrailFollow.active();
		pill(lx + 10, sy + 58, colW - 20, 20, following ? "Stop following" : "Follow old chunks", following ? Look.DANGER : Look.PRIMARY, () -> {
			if (TrailFollow.active()) {
				TrailFollow.stop("you stopped it");
				say("Stopped following old chunks", PLAIN);
				clearAndInit();
				return;
			}
			String why = TrailFollow.start(client, c.trailFollowAway);
			if (why != null) {
				say(why, ERROR);
				return;
			}
			say("Following old chunks, heading " + TrailFollow.heading(), OK);
			// Back to the game to watch it go.
			client.setScreen(null);
		});
		leftEnd = fcy + 112 + 10;

		// Follow, mine, around you, download.
		int ry = two ? y : leftEnd;
		card(rx, ry, colW, 68, "Follow a player", "Until you stop it");
		int ffy = ry + 30;
		playerField = field(rx + 10, ffy, colW - 20 - 94, "player name", playerField, "");
		pill(rx + colW - 10 - 90, ffy, 90, 18, "Follow them", Look.PRIMARY, () -> {
			String name = playerField.getText().strip();
			if (!name.isEmpty()) runWatched("follow player " + name, "Following " + name, "getFollowProcess",
					"Stopped following " + name + " (out of sight, or see Baritone's message in chat)");
		});
		// Players in view, nearest first: a click fills the name in.
		int chipX = rx + 10;
		if (p != null && client.world != null) {
			List<PlayerEntity> near = new ArrayList<>(client.world.getPlayers());
			near.remove(p);
			near.sort(Comparator.comparingDouble(o -> o.squaredDistanceTo(p)));
			for (PlayerEntity o : near) {
				String name = o.getGameProfile().name();
				int cw = textRenderer.getWidth(name) + 12;
				if (chipX + cw > rx + colW - 10) break;
				Pill chip = pill(chipX, ffy + 21, cw, 12, name, Look.SOFT, () -> playerField.setText(name));
				chipX += chip.getWidth() + 3;
			}
		}
		if (chipX == rx + 10) label("§8Nobody in view to pick", rx + 10, ffy + 23);
		ry += 68 + 8;

		card(rx, ry, colW, 68, "Mine", "Every one it can reach");
		int my = ry + 30;
		blockField = field(rx + 10, my, colW - 20 - 94, "block, e.g. diamond_ore", blockField, "");
		pill(rx + colW - 10 - 90, my, 90, 18, "Mine it", Look.PRIMARY, () -> {
			String block = blockField.getText().strip().toLowerCase(Locale.ROOT).replace(' ', '_');
			if (!block.isEmpty()) runWatched("mine " + block, "Mining " + block, "getMineProcess", "Stopped mining " + block + " (see Baritone's message in chat)");
		});
		chipX = rx + 10;
		for (String ore : new String[] {"diamond_ore", "ancient_debris", "iron_ore", "gold_ore", "obsidian", "coal_ore"}) {
			int cw = textRenderer.getWidth(ore) + 12;
			if (chipX + cw > rx + colW - 10) break;
			Pill chip = pill(chipX, my + 21, cw, 12, ore, Look.SOFT, () -> blockField.setText(ore));
			chipX += chip.getWidth() + 3;
		}
		ry += 68 + 8;

		card(rx, ry, colW, 50, "Around you", null);
		int ay = ry + 24;
		int third = (colW - 20 - 8) / 2;
		pill(rx + 10, ay, third, 18, "Explore", Look.SOFT, () -> runWatched("explore", "Exploring from here", "getExploreProcess",
				"Stopped exploring (see Baritone's message in chat)"));
		pill(rx + 10 + third + 8, ay, colW - 20 - third - 8, 18, "Farm nearby", Look.SOFT, () -> runWatched("farm", "Farming nearby", "getFarmProcess",
				"Farming stopped: nothing (more) to harvest or plant nearby"));
		ry += 50 + 8;

		boolean downloading = WorldDownload.active();
		card(rx, ry, colW, 50, "World download", downloading ? null : "Only when you say");
		pill(rx + 10, ry + 26, colW - 20, 16, downloading ? "Stop downloading (" + WorldDownload.chunks() + " chunks saved)" : "Download the land I load into a singleplayer world",
				downloading ? Look.DANGER : Look.SOFT, () -> {
					if (WorldDownload.active()) {
						WorldDownload.stop(client);
					} else {
						String why = WorldDownload.start(client);
						say(why == null ? "Downloading: every chunk you load is saved" : why, why == null ? OK : ERROR);
					}
					clearAndInit();
				});
		ry += 50;
		return Math.max(leftEnd - 10, ry);
	}

	/** Turns the typed x and z into the matching spot in the nether (or back), in the boxes, ready for Go. */
	private void convert(boolean toNether) {
		Integer[] at = typed();
		if (at == null) return;
		int x = toNether ? Math.floorDiv(at[0], 8) : at[0] * 8, z = toNether ? Math.floorDiv(at[2], 8) : at[2] * 8;
		xField.setText(String.valueOf(x));
		zField.setText(String.valueOf(z));
		say((toNether ? "Nether " : "Overworld ") + x + ", " + z + " (from " + (toNether ? "overworld " : "nether ") + at[0] + ", " + at[2] + ")", PLAIN);
	}

	private void fill(int x, Integer y, int z) {
		xField.setText(String.valueOf(x));
		yField.setText(y == null ? "" : String.valueOf(y));
		zField.setText(String.valueOf(z));
		say("Filled in " + x + ", " + (y == null ? "" : y + ", ") + z + ": press Go", PLAIN);
	}

	/** The typed x, y (or null), z; null (with a message) if x or z aren't numbers. */
	private Integer[] typed() {
		try {
			int x = Integer.parseInt(xField.getText().strip());
			int z = Integer.parseInt(zField.getText().strip());
			String ys = yField.getText().strip();
			return new Integer[] {x, ys.isEmpty() ? null : Integer.parseInt(ys), z};
		} catch (NumberFormatException e) {
			say("Type whole numbers for x and z (y can be empty)", ERROR);
			return null;
		}
	}

	private void goTyped() {
		Integer[] at = typed();
		if (at == null) return;
		String name = at[0] + ", " + (at[1] == null ? "" : at[1] + ", ") + at[2];
		go(new Destinations.Destination(Destinations.Kind.CLIPBOARD, name, "Typed", at[0], at[1] == null ? Integer.MIN_VALUE : at[1], at[2], "", 0));
	}

	private void go(Destinations.Destination d) {
		String said = SmartTravel.go(client, d);
		boolean ok = said.startsWith("Going");
		say(said, ok ? OK : ERROR);
		// Back to the game to watch it go, not to whatever screen opened this one.
		if (ok) client.setScreen(null);
	}

	// ---- Go to… ----

	private void initPlaces(int x, int y) {
		int w = contentW();
		int searchW = Math.min(240, w / 2 - 10);
		searchField = field(x, y, searchW, "Search places", searchField, "");
		searchField.setChangedListener(t -> listScroll = 0);
		int saveW = textRenderer.getWidth("Save where I am") + 18;
		waypointField = field(x + w - saveW - 4 - 150, y, 150, "Name this spot, e.g. Home", waypointField, "");
		pill(x + w - saveW, y, saveW, 18, "Save where I am", Look.PRIMARY, () -> {
			Destinations.Waypoint wp = Destinations.addHere(client, waypointField.getText());
			if (wp == null) {
				say("Type a name for this spot first", WARN);
				return;
			}
			say("Saved \"" + wp.name + "\" at " + wp.x + ", " + wp.y + ", " + wp.z, OK);
			waypointField.setText("");
			clearAndInit();
		});
		// One chip per kind there are places of (and the one picked), with how many.
		java.util.Arrays.fill(kindCounts, 0);
		for (Destinations.Destination d : Destinations.all(client)) {
			kindCounts[d.kind().ordinal()]++;
			kindCounts[kindCounts.length - 1]++;
		}
		int cx = x, cy = y + 24;
		cx += pill(cx, cy, textRenderer.getWidth("All") + 30, 14, "All", kindFilter == null ? Look.PICKED : Look.SOFT, () -> {
			kindFilter = null;
			listScroll = 0;
			clearAndInit();
		}).badge(() -> String.valueOf(placesCount(null))).getWidth() + 4;
		for (Destinations.Kind k : Destinations.Kind.values()) {
			if (placesCount(k) == 0 && kindFilter != k) continue;
			int cw = textRenderer.getWidth(k.label) + 30;
			if (cx + cw > x + w) {
				cx = x;
				cy += 17;
			}
			cx += pill(cx, cy, cw, 14, k.label, kindFilter == k ? Look.PICKED : Look.SOFT, () -> {
				kindFilter = kindFilter == k ? null : k;
				listScroll = 0;
				clearAndInit();
			}).badge(() -> String.valueOf(placesCount(k))).getWidth() + 4;
		}
		placesTop = cy + 20;
	}

	private int placesTop = TOP + 44;
	/** How many places of each kind, worked out once a frame. */
	private final int[] kindCounts = new int[Destinations.Kind.values().length + 1];

	private int placesCount(Destinations.Kind k) {
		return kindCounts[k == null ? kindCounts.length - 1 : k.ordinal()];
	}

	private static int kindColor(Destinations.Kind k) {
		return switch (k) {
			case WAYPOINT -> 0xFF8AB4FF;
			case BASE -> 0xFF4CD39B;
			case TRAIL -> 0xFFFFB020;
			case PLACE -> 0xFFFF7A1A;
			case SEEN -> 0xFFFFC14D;
			case EVENT -> 0xFFB070FF;
			case CLIPBOARD -> 0xFFDDDDDD;
		};
	}

	/** The list on Go to…: a card per place, with where it is in both dimensions, how far and which way, and its Go buttons. */
	private void renderPlaces(DrawContext context, int mouseX, int mouseY) {
		rowHits.clear();
		int x = contentX(), w = contentW(), top = placesTop, bottom = height - STATUS_H - 6, rowH = 34;
		String q = searchField == null ? "" : searchField.getText().strip().toLowerCase(Locale.ROOT);
		List<Destinations.Destination> all = Destinations.all(client);
		java.util.Arrays.fill(kindCounts, 0);
		for (Destinations.Destination d : all) {
			kindCounts[d.kind().ordinal()]++;
			kindCounts[kindCounts.length - 1]++;
		}
		all.removeIf(d -> kindFilter != null && d.kind() != kindFilter
				|| !q.isEmpty() && !d.name().toLowerCase(Locale.ROOT).contains(q) && !d.detail().toLowerCase(Locale.ROOT).contains(q));
		if (all.isEmpty()) {
			roundedBorder(context, x, top, w, 46, CARD, BORDER);
			context.drawText(textRenderer, q.isEmpty() && kindFilter == null ? "No places yet" : "Nothing matches", x + 12, top + 10, TEXT, false);
			context.drawText(textRenderer, fit(q.isEmpty() && kindFilter == null
					? "Save a spot above, or play a while: trails, places players give away and where your list's players were seen all show up here."
					: "Try another word, or All.", w - 24), x + 12, top + 24, MUTED, false);
			return;
		}
		int maxScroll = Math.max(0, all.size() * rowH - (bottom - top));
		listScroll = Math.max(0, Math.min(listScroll, maxScroll));
		String here = client.world == null ? "" : client.world.getRegistryKey().getValue().toString();
		context.enableScissor(x, top, x + w, bottom);
		int y = top - (int) listScroll;
		for (Destinations.Destination d : all) {
			if (y + rowH >= top && y <= bottom) drawPlace(context, mouseX, mouseY, d, x, y, w, rowH, top, bottom, here);
			y += rowH;
		}
		// More below: the last card fades out instead of being cut by the edge.
		if (listScroll < maxScroll) context.fillGradient(x, bottom - 14, x + w, bottom, PAGE_BG & 0x00FFFFFF, PAGE_BG);
		context.disableScissor();
		scrollbar(context, x + w + 4, top, bottom, all.size() * rowH, maxScroll);
	}

	private void drawPlace(DrawContext context, int mouseX, int mouseY, Destinations.Destination d, int x, int y, int w, int rowH, int top, int bottom, String here) {
		int h = rowH - 4;
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + h, bottom);
		roundedBorder(context, x, y, w, h, hover ? HOVER_BG : CARD, hover ? HOVER_BORDER : BORDER);
		int kc = kindColor(d.kind());
		context.fill(x + 1, y + 3, x + 4, y + h - 3, kc);
		// Both: its overworld and its nether coordinates (one nether block is eight overworld ones), each with a Go.
		boolean end = d.dimension().endsWith("the_end"), inNether = d.dimension().endsWith("the_nether");
		int ox = inNether ? d.x() * 8 : d.x(), oz = inNether ? d.z() * 8 : d.z();
		int nx = inNether ? d.x() : Math.floorDiv(d.x(), 8), nz = inNether ? d.z() : Math.floorDiv(d.z(), 8);
		Destinations.Destination overworld = new Destinations.Destination(d.kind(), d.name(), d.detail(), ox, inNether ? Integer.MIN_VALUE : d.y(), oz,
				"minecraft:overworld", d.time());
		Destinations.Destination nether = new Destinations.Destination(d.kind(), d.name(), d.detail(), nx, inNether ? d.y() : Integer.MIN_VALUE, nz,
				"minecraft:the_nether", d.time());
		int buttons = end ? 34 : 60 + 4 + 48;
		boolean waypoint = d.kind() == Destinations.Kind.WAYPOINT;
		int textRight = x + w - 10 - buttons - (waypoint ? 16 : 0) - 70;
		// Its kind as a tag, then the name, then what it is.
		String tag = d.kind().label;
		int tagW = textRenderer.getWidth(tag) + 8;
		roundedBorder(context, x + 10, y + 5, tagW, 11, tint(kc, CARD, 0.18f), tint(kc, CARD, 0.5f));
		context.drawText(textRenderer, tag, x + 14, y + 7, kc, false);
		int nameX = x + 10 + tagW + 6;
		String name = fitBold(d.name(), Math.max(20, textRight - nameX));
		context.drawText(textRenderer, Text.literal(name).styled(s -> s.withBold(true)), nameX, y + 7, TEXT, false);
		int detailX = nameX + textRenderer.getWidth(Text.literal(name).styled(s -> s.withBold(true))) + 6;
		if (!d.detail().isEmpty() && detailX < textRight) context.drawText(textRenderer, fit(d.detail(), textRight - detailX), detailX, y + 7, MUTED, false);
		String coords = end ? String.format(Locale.ROOT, "End  %,d  %,d", d.x(), d.z())
				: String.format(Locale.ROOT, "Overworld  %,d  %,d      Nether  %,d  %,d", ox, oz, nx, nz);
		context.drawText(textRenderer, fit(coords, textRight - x - 10), x + 10, y + 19, DIM, false);
		// How far, and which way, from where you are.
		int right = x + w - 10 - (waypoint ? 16 : 0);
		int bx = right - buttons;
		if (client.player != null) {
			boolean hereNether = here.endsWith("the_nether"), hereEnd = here.endsWith("the_end");
			String far, way;
			if (end == hereEnd) {
				double dx = (end ? d.x() : hereNether ? nx : ox) - client.player.getX(), dz = (end ? d.z() : hereNether ? nz : oz) - client.player.getZ();
				far = String.format(Locale.ROOT, "%,d", (int) Math.hypot(dx, dz));
				way = ChunkTrails.compass(dx, dz);
			} else {
				far = ShopScreen.dimensionName(d.dimension());
				way = "";
			}
			context.drawText(textRenderer, far, bx - 8 - textRenderer.getWidth(far), y + 7, TEXT, false);
			context.drawText(textRenderer, way.isEmpty() ? "" : "blocks " + way, bx - 8 - textRenderer.getWidth("blocks " + way), y + 19, MUTED, false);
		}
		if (end) {
			goButton(context, mouseX, mouseY, bx, y + 7, 34, "Go", Look.OVERWORLD, d, d.name(), top, bottom);
		} else {
			goButton(context, mouseX, mouseY, bx, y + 7, 60, "Overworld", Look.OVERWORLD, overworld, d.name(), top, bottom);
			goButton(context, mouseX, mouseY, bx + 64, y + 7, 48, "Nether", Look.NETHER, nether, d.name() + " (nether)", top, bottom);
		}
		if (waypoint) {
			int rx = x + w - 20;
			boolean over = mouseX >= rx && mouseX < rx + 14 && mouseY >= y + 6 && mouseY < y + 22;
			context.drawText(textRenderer, "×", rx + 4, y + 10, over ? BAD : DIM, false);
			rowHits.add(new Object[] {rx, Math.max(y + 6, top), rx + 14, Math.min(y + 22, bottom), (Runnable) () -> {
				Destinations.remove(d.name());
				say("Removed \"" + d.name() + "\"", PLAIN);
			}, d.name(), true});
		}
	}

	/** A Go button on a place's card; {@code key} is what the self-test clicks it by. */
	private void goButton(DrawContext context, int mouseX, int mouseY, int gx, int gy, int gw, String label, Look look, Destinations.Destination to, String key,
			int top, int bottom) {
		boolean over = mouseX >= gx && mouseX < gx + gw && mouseY >= gy && mouseY < gy + 16 && mouseY >= top && mouseY < bottom;
		boolean ow = look == Look.OVERWORLD;
		roundedBorder(context, gx, gy, gw, 16, over ? (ow ? 0xFF4CA0FF : 0xFFC0453F) : (ow ? 0xFF2E6BBF : 0xFF8F2B2B), over ? (ow ? 0xFF7CBCFF : 0xFFE0605A) : (ow ? 0xFF3F80D6 : 0xFFB03A35));
		context.drawText(textRenderer, label, gx + gw / 2 - textRenderer.getWidth(label) / 2, gy + 4, 0xFFFFFFFF, false);
		rowHits.add(new Object[] {gx, Math.max(gy, top), gx + gw, Math.min(gy + 16, bottom), (Runnable) () -> go(to), key, false});
	}

	private void scrollbar(DrawContext context, int x, int top, int bottom, int total, int maxScroll) {
		if (maxScroll <= 0) return;
		int bar = Math.max(14, (bottom - top) * (bottom - top) / Math.max(1, total));
		int by = top + (int) ((bottom - top - bar) * (listScroll / maxScroll));
		context.fill(x, top, x + 3, bottom, 0x30FFFFFF);
		context.fill(x, by, x + 3, by + bar, 0xFF6B7682);
	}

	// ---- Highway roam ----

	private int initHighway(int x, int y) {
		NNConfig c = NNConfig.INSTANCE;
		int w = contentW();
		boolean two = wide();
		int colW = two ? (w - 10) * 11 / 20 : w;
		int lx = x, rx = two ? x + colW + 10 : x, rw = two ? w - colW - 10 : w;

		// Where to: a distance along a highway, or coordinates; the highway picked on a compass.
		int whereH = c.roamToCoords ? 214 : 166;
		card(lx, y, colW, whereH, "Where to", null);
		int segW = (colW - 20) / 2;
		pill(lx + 10, y + 22, segW, 16, "A distance along a highway", c.roamToCoords ? Look.SOFT : Look.PICKED, () -> {
			c.roamToCoords = false;
			if (HighwayRoam.axis() == null) HighwayRoam.pick(HighwayRoam.AXES[0]);
			NNConfig.save();
			clearAndInit();
		});
		pill(lx + 10 + segW, y + 22, colW - 20 - segW, 16, "To coordinates", c.roamToCoords ? Look.PICKED : Look.SOFT, () -> {
			c.roamToCoords = true;
			NNConfig.save();
			clearAndInit();
		});
		// The compass: north (-Z) up, as on a map; Best in the middle when going to coordinates.
		int cell = 40, ch = 26, gap = 3;
		compassX = lx + 10;
		compassY = y + 46;
		String[][] grid = {{"-X-Z", "-Z", "+X-Z"}, {"-X", null, "+X"}, {"-X+Z", "+Z", "+X+Z"}};
		for (int r = 0; r < 3; r++) {
			for (int col = 0; col < 3; col++) {
				int gx = compassX + col * (cell + gap), gy = compassY + r * (ch + gap);
				String name = grid[r][col];
				if (name == null) {
					if (!c.roamToCoords) {
						compassYou = new int[] {gx, gy, cell, ch};
					} else {
						pill(gx, gy, cell, ch, HighwayRoam.BEST, HighwayRoam.axis() == null ? Look.PRIMARY : Look.SOFT, () -> {
							HighwayRoam.pick(null);
							clearAndInit();
						});
					}
					continue;
				}
				HighwayRoam.Axis a = axis(name);
				pill(gx, gy, cell, ch, name, a == HighwayRoam.axis() ? Look.PRIMARY : Look.SOFT, () -> {
					HighwayRoam.pick(a);
					clearAndInit();
				}).icon(arrow(name));
			}
		}
		// Beside the compass: how far, or the coordinates.
		int sx = compassX + 3 * cell + 2 * gap + 12, sw = lx + colW - 10 - sx;
		int sy = compassY;
		if (!c.roamToCoords) {
			label("Distance (blocks)", sx, sy);
			distanceField = field(sx, sy + 10, sw, "blocks", distanceField, String.valueOf(c.roamDistance));
			distanceField.setPlaceholder(Text.empty());
			distanceField.setChangedListener(t -> {
				String d = t.strip().replace(",", "");
				if (!d.matches("\\d{3,9}")) return;
				c.roamDistance = Math.max(100, Integer.parseInt(d));
				NNConfig.save();
			});
			sy += 34;
		} else {
			int hw = (sw - 4) / 2;
			roamXField = labelledField(sx, sy + 10, hw, "x", roamXField, c.roamX == null ? "" : String.valueOf(c.roamX));
			roamZField = labelledField(sx + hw + 4, sy + 10, sw - hw - 4, "z", roamZField, c.roamZ == null ? "" : String.valueOf(c.roamZ));
			Runnable readCoords = () -> {
				c.roamX = whole(roamXField.getText());
				c.roamZ = whole(roamZField.getText());
				NNConfig.save();
			};
			roamXField.setChangedListener(t -> readCoords.run());
			roamZField.setChangedListener(t -> readCoords.run());
			sy += 34;
			pill(sx, sy, sw, 14, "Paste coordinates", Look.SOFT, () -> {
				List<ChatIntel.Coord> coords = ChatIntel.coords(client.keyboard.getClipboard());
				if (coords.isEmpty()) {
					say("No coordinates on the clipboard", WARN);
					return;
				}
				roamXField.setText(String.valueOf(coords.get(0).x()));
				roamZField.setText(String.valueOf(coords.get(0).z()));
				if (coords.get(0).dimension() != null) c.roamOverworld = coords.get(0).dimension().endsWith("overworld");
				NNConfig.save();
				clearAndInit();
			});
			sy += 17;
			pill(sx, sy, sw, 14, "Latest trail's end", Look.SOFT, () -> {
				List<ChunkTrails.Trail> trails = NaughtyOrNiceClient.INSTANCE.ledger.chunkTrails.trails;
				if (trails.isEmpty()) {
					say("No chunk trails found yet", WARN);
					return;
				}
				int[] end = trails.stream().max(Comparator.comparingLong(tr -> tr.lastSeen)).orElseThrow().awayEnd();
				roamXField.setText(String.valueOf(end[0]));
				roamZField.setText(String.valueOf(end[1]));
				c.roamOverworld = false;
				NNConfig.save();
				clearAndInit();
			});
			sy += 20;
		}
		label("Height (empty: yours)", sx, sy);
		heightField = field(sx, sy + 10, sw, "here", heightField, c.roamHeight == null ? "" : String.valueOf(c.roamHeight));
		heightField.setChangedListener(t -> {
			String h = t.strip();
			if (h.isEmpty()) c.roamHeight = null;
			else if (h.matches("-?\\d{1,4}")) c.roamHeight = Integer.parseInt(h);
			else return;
			NNConfig.save();
		});
		// How: on foot or by elytra.
		int hy = compassY + 3 * ch + 2 * gap + 8;
		int hw2 = (3 * cell + 2 * gap) / 2;
		pill(compassX, hy, hw2, 16, "Walk", c.roamElytra ? Look.SOFT : Look.PICKED, () -> {
			c.roamElytra = false;
			NNConfig.save();
			clearAndInit();
		});
		pill(compassX + hw2, hy, 3 * cell + 2 * gap - hw2, 16, "Elytra", c.roamElytra ? Look.PICKED : Look.SOFT, () -> {
			c.roamElytra = true;
			NNConfig.save();
			clearAndInit();
		});
		int ty = hy + 22;
		if (c.roamToCoords) {
			toggle(lx + 10, ty, colW - 20, "They're overworld coordinates (÷8 in the nether)", () -> c.roamOverworld, v -> c.roamOverworld = v);
			ty += 17;
			toggle(lx + 10, ty, colW - 20, "Then go on to them off the highway (off: stop on it)", () -> c.roamGoOn, v -> c.roamGoOn = v);
		}

		// Getting through, and safety.
		int ry = two ? y : y + whereH + 8;
		card(rx, ry, rw, 82, "Getting through", "Damaged highways");
		int gy = ry + 24;
		toggle(rx + 10, gy, rw - 20, "Break through blocked parts", () -> c.roamBreak, v -> c.roamBreak = v);
		toggle(rx + 10, gy + 14, rw - 20, "Bridge gaps and missing floor", () -> c.roamBridge, v -> c.roamBridge = v);
		toggle(rx + 10, gy + 28, rw - 20, "Parkour jumps", () -> c.roamParkour, v -> c.roamParkour = v);
		toggle(rx + 10, gy + 42, rw - 20, "Walk on lava (Fire Resistance)", () -> c.roamLava, v -> c.roamLava = v);
		ry += 82 + 8;
		card(rx, ry, rw, 52, "Stop and ask", null);
		toggle(rx + 10, ry + 22, rw - 20, "Stop and ask at chunk trails", () -> c.roamStopTrails, v -> c.roamStopTrails = v);
		toggle(rx + 10, ry + 36, rw - 20, "Stop when a stranger shows up", () -> c.roamStopStrangers, v -> c.roamStopStrangers = v);
		ry += 52 + 8;

		// The way it'll go, and Start.
		int sy2 = two ? Math.max(ry, y + whereH + 8) : ry;
		int routeH = 46;
		if (!two) sy2 = ry;
		int fullW = w;
		card(x, sy2, fullW, routeH, null, null);
		routeX = x + 10;
		routeY = sy2 + 8;
		routeW = fullW - 20 - 150;
		boolean active = HighwayRoam.active();
		pill(x + fullW - 10 - 140, sy2 + 13, 140, 20, active ? "Stop roaming" : "Start roaming", active ? Look.DANGER : Look.PRIMARY, () -> {
			if (HighwayRoam.active()) {
				HighwayRoam.stop("you stopped it");
				BaritoneBridge.run("stop");
				say("Stopped roaming", PLAIN);
			} else {
				String why = HighwayRoam.start(client);
				say(why == null ? "Roaming to " + HighwayRoam.target() : why, why == null ? OK : ERROR);
			}
			clearAndInit();
		});
		return sy2 + routeH;
	}

	private static HighwayRoam.Axis axis(String name) {
		for (HighwayRoam.Axis a : HighwayRoam.AXES) if (a.name().equals(name)) return a;
		return null;
	}

	/** A whole number typed in a field (commas and "k" allowed: 12k is 12000), or null. */
	static Integer whole(String text) {
		String t = text.strip().replace(",", "").replace("_", "").toLowerCase(Locale.ROOT);
		try {
			if (t.endsWith("k")) return (int) Math.round(Double.parseDouble(t.substring(0, t.length() - 1)) * 1000);
			return t.isEmpty() ? null : Integer.parseInt(t);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** The arrow for a highway on the compass. */
	private static String arrow(String name) {
		return switch (name) {
			case "-X-Z" -> "↖";
			case "-Z" -> "↑";
			case "+X-Z" -> "↗";
			case "-X" -> "←";
			case "+X" -> "→";
			case "-X+Z" -> "↙";
			case "+Z" -> "↓";
			case "+X+Z" -> "↘";
			default -> "";
		};
	}

	// ---- Settings ----

	private void initSettings(int x, int y) {
		NNConfig c = NNConfig.INSTANCE;
		int w = contentW();
		card(x, y, w, 58, "Trips", null);
		int half = (w - 30) / 2;
		toggle(x + 10, y + 22, half, "On trips: pause when a stranger shows up", () -> c.tripStopForStrangers, v -> c.tripStopForStrangers = v);
		toggle(x + 10, y + 38, half, "Nether trips: ride the highways when quicker", () -> c.tripUseHighways, v -> c.tripUseHighways = v);
		toggle(x + 20 + half, y + 38, half, "Trip bar at the top", () -> c.tripBar, v -> c.tripBar = v);
		int hearts = c.tripStopHealth / 2;
		pill(x + 20 + half, y + 20, Math.min(half, 160), 16, hearts == 0 ? "Stop at low health: off" : "Stop at " + hearts + " hearts", Look.SOFT, () -> {
			// Off, 2, 3, 4, 5, 6 hearts, and round again.
			c.tripStopHealth = c.tripStopHealth == 0 ? 4 : c.tripStopHealth >= 12 ? 0 : c.tripStopHealth + 2;
			NNConfig.save();
			clearAndInit();
		}).icon("♥");
		int sy = y + 66;
		settingSearch = field(x, sy, w - 150, "Search Baritone's settings (all of them)", settingSearch, "");
		settingSearch.setChangedListener(t -> listScroll = 0);
		pill(x + w - 144, sy, 70, 18, "Everything", changedOnly ? Look.SOFT : Look.PICKED, () -> {
			changedOnly = false;
			listScroll = 0;
			clearAndInit();
		});
		pill(x + w - 70, sy, 70, 18, "Changed", changedOnly ? Look.PICKED : Look.SOFT, () -> {
			changedOnly = true;
			listScroll = 0;
			clearAndInit();
		});
		// A picked setting that isn't on/off: type its value, Set, or put it back.
		BaritoneBridge.Setting picked = setting(pickedSetting);
		if (picked != null && !picked.bool()) {
			int by = height - STATUS_H - 30;
			valueField = field(x + 10, by, w - 20 - 150, picked.name(),
					valueField != null && picked.name().equals(valueField.getMessage().getString()) ? valueField : null, picked.value());
			valueField.setMaxLength(2000);
			pill(x + w - 10 - 140, by, 68, 18, "Set", Look.PRIMARY, () -> {
				run("set " + picked.name() + " " + valueField.getText().strip(), picked.name() + " set to " + valueField.getText().strip());
				settingsReadAt = 0;
			});
			pill(x + w - 10 - 68, by, 68, 18, "Reset", Look.SOFT, () -> {
				BaritoneBridge.reset(picked.name());
				BaritoneBridge.saveSettings();
				say(picked.name() + " back to " + picked.defaultValue(), PLAIN);
				settingsReadAt = 0;
				valueField.setText(picked.defaultValue());
			});
		}
	}

	private BaritoneBridge.Setting setting(String name) {
		if (name == null) return null;
		for (BaritoneBridge.Setting s : currentSettings()) {
			if (s.name().equals(name)) return s;
		}
		return null;
	}

	/** Baritone's settings, read again twice a second (or straight after a change here). */
	private List<BaritoneBridge.Setting> currentSettings() {
		long now = System.currentTimeMillis();
		if (now - settingsReadAt > 500) {
			settings = BaritoneBridge.settings();
			settingsReadAt = now;
		}
		return settings;
	}

	/** The settings list: on/off ones as switches you click, the rest picked to type a value. */
	private void renderSettings(DrawContext context, int mouseX, int mouseY) {
		int x = contentX(), w = contentW(), top = TOP + 66 + 34;
		BaritoneBridge.Setting picked = setting(pickedSetting);
		boolean editing = picked != null && !picked.bool();
		int bottom = editing ? height - STATUS_H - 52 : height - STATUS_H - 6;
		String q = settingSearch == null ? "" : settingSearch.getText().strip().toLowerCase(Locale.ROOT);
		List<BaritoneBridge.Setting> shown = new ArrayList<>();
		int changed = 0;
		for (BaritoneBridge.Setting s : currentSettings()) {
			if (s.changed()) changed++;
			if ((q.isEmpty() || s.name().toLowerCase(Locale.ROOT).contains(q)) && (!changedOnly || s.changed())) shown.add(s);
		}
		context.drawText(textRenderer, shown.size() + " shown · " + changed + " changed from Baritone's own · click a switch to flip it, anything else to type its value",
				x, top - 11, DIM, false);
		List<Row> rows = new ArrayList<>();
		for (BaritoneBridge.Setting s : shown) {
			rows.add(new Row(s.name(), s.name(), s, s.name().equals(pickedSetting), () -> {
				if (s.bool()) {
					boolean on = !s.value().equals("true");
					BaritoneBridge.setBool(s.name(), on);
					BaritoneBridge.saveSettings();
					say(s.name() + (on ? ": on" : ": off"), PLAIN);
					settingsReadAt = 0;
				} else {
					pickedSetting = s.name();
					valueField = null;
					clearAndInit();
				}
			}));
		}
		drawRows(context, mouseX, mouseY, x, top, w, bottom, rows, 20);
		if (editing) {
			int by = height - STATUS_H - 46;
			roundedBorder(context, x, by - 2, w, 42, CARD, LINK);
			context.drawText(textRenderer, Text.literal(picked.name()).styled(s -> s.withBold(true)), x + 10, by + 3, TEXT, false);
			context.drawText(textRenderer, "default " + fit(picked.defaultValue(), 200) + " · type a value, then Set",
					x + 16 + textRenderer.getWidth(Text.literal(picked.name()).styled(s -> s.withBold(true))), by + 3, MUTED, false);
		}
	}

	// ---- Every command ----

	private void initCommands(int x, int y) {
		commands = BaritoneBridge.commands();
		int w = contentW();
		commandSearch = field(x, y, w, "Search Baritone's commands", commandSearch, "");
		commandSearch.setChangedListener(t -> listScroll = 0);
		BaritoneBridge.Command picked = command(pickedCommand);
		if (picked != null) {
			int by = height - STATUS_H - 30;
			argsField = field(x + 10, by, w - 20 - 120, "what to add after " + picked.name() + " (or nothing)", argsField, "");
			argsField.setMaxLength(500);
			pill(x + w - 10 - 112, by, 112, 18, "Run " + picked.name(), Look.PRIMARY, () -> {
				String line = (picked.name() + " " + argsField.getText().strip()).strip();
				run(line, "Ran: " + line);
			});
		}
	}

	private BaritoneBridge.Command command(String name) {
		if (name == null) return null;
		for (BaritoneBridge.Command c : commands) {
			if (c.name().equals(name)) return c;
		}
		return null;
	}

	/** The commands list, then the picked one: how it's used, what to add, and Baritone's suggestions to click. */
	private void renderCommands(DrawContext context, int mouseX, int mouseY) {
		int x = contentX(), w = contentW();
		BaritoneBridge.Command picked = command(pickedCommand);
		int top = TOP + 34, bottom = picked == null ? height - STATUS_H - 6 : height - STATUS_H - 118;
		String q = commandSearch == null ? "" : commandSearch.getText().strip().toLowerCase(Locale.ROOT);
		List<Row> rows = new ArrayList<>();
		for (BaritoneBridge.Command c : commands) {
			boolean match = q.isEmpty() || c.shortDesc().toLowerCase(Locale.ROOT).contains(q)
					|| c.names().stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).contains(q));
			if (!match) continue;
			String also = c.names().size() > 1 ? "also " + String.join(", ", c.names().subList(1, c.names().size())) : "";
			rows.add(new Row(c.name(), c.name(), also + "\n" + c.shortDesc(), c.name().equals(pickedCommand), () -> {
				pickedCommand = c.name();
				if (argsField != null) argsField.setText("");
				suggestedFor = null;
				clearAndInit();
			}));
		}
		context.drawText(textRenderer, rows.size() + " of Baritone's commands · click one", x, top - 10, DIM, false);
		drawRows(context, mouseX, mouseY, x, top, w, bottom, rows, 26);
		if (picked == null) return;
		int by = bottom + 6;
		roundedBorder(context, x, by, w, height - STATUS_H - 4 - by, CARD, LINK);
		context.drawText(textRenderer, Text.literal(picked.name()).styled(s -> s.withBold(true)), x + 10, by + 7, TEXT, false);
		context.drawText(textRenderer, fit(picked.shortDesc(), w - 30 - textRenderer.getWidth(picked.name())),
				x + 18 + textRenderer.getWidth(Text.literal(picked.name()).styled(s -> s.withBold(true))), by + 7, MUTED, false);
		// How it's used: Baritone's own lines ("> goto <x> <y> <z>").
		int uy = by + 19, shownLines = 0;
		for (String line : picked.longDesc()) {
			if (!line.startsWith(">") || shownLines >= 3) continue;
			context.drawText(textRenderer, fit(line.substring(1).strip(), w - 20), x + 10, uy, LINK, false);
			uy += 10;
			shownLines++;
		}
		// What Baritone suggests next, for what's typed so far: click to add it (no typos).
		String typed = argsField == null ? "" : argsField.getText();
		String line = picked.name() + " " + typed;
		if (!line.equals(suggestedFor)) {
			suggestedFor = line;
			suggestions = BaritoneBridge.suggest(line);
		}
		int cx = x + 10, cy = Math.max(uy + 2, height - STATUS_H - 50);
		for (String s : suggestions) {
			int sw = textRenderer.getWidth(s) + 10;
			if (cx + sw > x + w - 10) {
				cx = x + 10;
				cy += 14;
				if (cy > height - STATUS_H - 44) break;
			}
			boolean hover = mouseX >= cx && mouseX < cx + sw && mouseY >= cy && mouseY < cy + 12;
			roundedBorder(context, cx, cy, sw, 12, hover ? 0xFF2C4A5E : SOFT, hover ? LINK : BORDER);
			context.drawText(textRenderer, s, cx + 5, cy + 2, TEXT, false);
			String pick = s;
			rowHits.add(new Object[] {cx, cy, cx + sw, cy + 12, (Runnable) () -> addSuggestion(pick), "suggest:" + s, false});
			cx += sw + 3;
		}
	}

	/** A suggestion replaces the word being typed (or follows the last whole one). */
	private void addSuggestion(String s) {
		String typed = argsField.getText();
		int cut = typed.endsWith(" ") || typed.isEmpty() ? typed.length() : typed.lastIndexOf(' ') + 1;
		argsField.setText(typed.substring(0, cut) + s + " ");
		suggestedFor = null;
	}

	/** One row of a list: the key it's clicked by, its title, what's under it (or the setting it shows), picked or not. */
	private record Row(String key, String main, Object sub, boolean picked, Runnable click) {}

	private void drawRows(DrawContext context, int mouseX, int mouseY, int x, int top, int w, int bottom, List<Row> rows, int rowH) {
		int maxScroll = Math.max(0, rows.size() * rowH - (bottom - top));
		listScroll = Math.max(0, Math.min(listScroll, maxScroll));
		context.enableScissor(x, top, x + w, bottom);
		int y = top - (int) listScroll;
		for (Row r : rows) {
			if (y + rowH >= top && y <= bottom) {
				int h = rowH - 3;
				boolean hover = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + h, bottom);
				roundedBorder(context, x, y, w, h, r.picked ? 0xFF243E50 : hover ? HOVER_BG : CARD, r.picked ? LINK : hover ? HOVER_BORDER : BORDER);
				if (r.sub instanceof BaritoneBridge.Setting s) {
					// A setting: its name, and on the right a switch or its value.
					int nameColor = s.changed() ? GOOD : TEXT;
					int right = x + w - 10;
					if (s.bool()) {
						boolean on = s.value().equals("true");
						drawSwitch(context, right - 20, y + (h - 10) / 2, on, hover);
						right -= 26;
					} else {
						String v = fit(s.value().isEmpty() ? "(empty)" : s.value(), w / 2) + "  ›";
						context.drawText(textRenderer, v, right - textRenderer.getWidth(v), y + (h - 8) / 2, MUTED, false);
						right -= textRenderer.getWidth(v) + 8;
					}
					context.drawText(textRenderer, fit(s.name(), right - x - 20), x + 10, y + (h - 8) / 2, nameColor, false);
					if (s.changed()) {
						String was = "default " + (s.bool() ? (s.defaultValue().equals("true") ? "on" : "off") : fit(s.defaultValue(), 80));
						int nw = textRenderer.getWidth(s.name()) + 8;
						if (x + 10 + nw + textRenderer.getWidth(was) < right) context.drawText(textRenderer, was, x + 10 + nw, y + (h - 8) / 2, DIM, false);
					}
				} else {
					String[] parts = String.valueOf(r.sub).split("\n", 2);
					context.drawText(textRenderer, Text.literal(fit(r.main, w - 20)).styled(st -> st.withBold(true)), x + 10, y + 4, TEXT, false);
					int mw = textRenderer.getWidth(Text.literal(r.main).styled(st -> st.withBold(true)));
					if (!parts[0].isEmpty()) context.drawText(textRenderer, fit(parts[0], w - 30 - mw), x + 16 + mw, y + 4, DIM, false);
					if (parts.length > 1) context.drawText(textRenderer, fit(parts[1], w - 20), x + 10, y + 14, MUTED, false);
				}
				rowHits.add(new Object[] {x, Math.max(y, top), x + w, Math.min(y + h, bottom), r.click, r.key, false});
			}
			y += rowH;
		}
		context.disableScissor();
		scrollbar(context, x + w + 4, top, bottom, rows.size() * rowH, maxScroll);
	}

	// ---- Drawing ----

	/** An on/off switch: a track with a knob, green when on. */
	private void drawSwitch(DrawContext context, int x, int y, boolean on, boolean hover) {
		int track = on ? (hover ? 0xFF5FE0AC : GOOD) : (hover ? 0xFF4A5663 : 0xFF3A4450);
		rounded(context, x, y, 18, 10, track);
		int kx = on ? x + 9 : x + 1;
		rounded(context, kx, y + 1, 8, 8, 0xFFFFFFFF);
	}

	private static void rounded(DrawContext context, int x, int y, int w, int h, int color) {
		context.fill(x + 2, y, x + w - 2, y + h, color);
		context.fill(x + 1, y + 1, x + w - 1, y + h - 1, color);
		context.fill(x, y + 2, x + w, y + h - 2, color);
	}

	private static void roundedBorder(DrawContext context, int x, int y, int w, int h, int fill, int border) {
		rounded(context, x, y, w, h, border);
		rounded(context, x + 1, y + 1, w - 2, h - 2, fill);
	}

	private static int tint(int color, int base, float amount) {
		int r = Math.round((base >> 16 & 0xFF) * (1 - amount) + (color >> 16 & 0xFF) * amount);
		int g = Math.round((base >> 8 & 0xFF) * (1 - amount) + (color >> 8 & 0xFF) * amount);
		int b = Math.round((base & 0xFF) * (1 - amount) + (color & 0xFF) * amount);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private double scaled(double v) {
		return v / uiScale;
	}

	private Click scaled(Click click) {
		return new Click(scaled(click.x()), scaled(click.y()), click.buttonInfo());
	}

	@Override
	public void renderBackground(DrawContext context, int realMouseX, int realMouseY, float delta) {
		// The game draws this separately from render(), so it gets the same scaling here.
		context.getMatrices().pushMatrix();
		context.getMatrices().scale(uiScale, uiScale);
		drawPage(context, (int) Math.floor(scaled(realMouseX)), (int) Math.floor(scaled(realMouseY)));
		context.getMatrices().popMatrix();
	}

	/** Everything behind the buttons and boxes: the header and its live status, the cards, the lists. */
	private void drawPage(DrawContext context, int mouseX, int mouseY) {
		context.fill(0, 0, width, height, PAGE_BG);
		context.fill(0, 0, width, HEADER, NAV);
		context.fill(0, HEADER, width, HEADER + TABS, SUBNAV);
		int x = contentX();
		context.drawText(textRenderer, Text.literal("Baritone").styled(s -> s.withBold(true)), x, 8, TEXT, false);
		context.drawText(textRenderer, "in Naughty or Nice", x, 20, DIM, false);
		if (ModsPanel.streamSafe()) {
			// Its fields and places are all coordinates.
			int cy = height / 2 - 20;
			roundedBorder(context, width / 2 - 170, cy, 340, 44, CARD, BORDER);
			context.drawCenteredTextWithShadow(textRenderer, "Hidden while stream-safe mode is on", width / 2, cy + 10, TEXT);
			context.drawCenteredTextWithShadow(textRenderer, "It's full of coordinates. Press Esc, then the stream-safe key to show it.", width / 2, cy + 24, MUTED);
			return;
		}
		if (!BaritoneBridge.present()) {
			int cy = TOP;
			roundedBorder(context, x, cy, contentW(), 58, CARD, BAD);
			context.drawText(textRenderer, Text.literal("Baritone isn't installed").styled(s -> s.withBold(true)), x + 12, cy + 10, BAD, false);
			context.drawText(textRenderer, "Get the Fabric 1.21.11 build from github.com/cabaletta/baritone/releases", x + 12, cy + 24, MUTED, false);
			context.drawText(textRenderer, "(or use a client that has it built in), then open this again.", x + 12, cy + 36, MUTED, false);
			return;
		}
		drawStatusChip(context, x + 112);

		rowHits.clear();
		// Cards first, then what's written on them, then the boxes' frames.
		for (Card c : cards) {
			if (c.y() + c.h() < TOP - 4 || c.y() > height) continue;
			roundedBorder(context, c.x(), c.y(), c.w(), c.h(), CARD, BORDER);
			if (c.title() != null) context.drawText(textRenderer, Text.literal(c.title()).styled(s -> s.withBold(true)), c.x() + 10, c.y() + 8, TEXT, false);
			if (c.note() != null) {
				int tx = c.title() == null ? c.x() + 10 : c.x() + 16 + textRenderer.getWidth(Text.literal(c.title()).styled(s -> s.withBold(true)));
				context.drawText(textRenderer, fit(c.note(), c.x() + c.w() - 10 - tx), tx, c.y() + 8, DIM, false);
			}
		}
		for (Object[] l : labels) {
			int ly = (int) l[2];
			if (ly < TOP - 2 || ly > height - STATUS_H) continue;
			context.drawText(textRenderer, (String) l[0], (int) l[1], ly, MUTED, false);
		}
		switch (page) {
			case PLACES -> renderPlaces(context, mouseX, mouseY);
			case SETTINGS -> renderSettings(context, mouseX, mouseY);
			case COMMANDS -> renderCommands(context, mouseX, mouseY);
			case HIGHWAY -> drawHighway(context);
			case GO -> {}
		}
		// The boxes' frames last, so a card drawn by the page never covers one.
		for (Field f : fields) {
			if (!f.visible) continue;
			roundedBorder(context, f.getX() - 5, f.getY() - 4, f.getWidth() + 10, 18, FIELD, f.isFocused() ? LINK : INPUT_BORDER);
		}
		if (page == Page.GO || page == Page.HIGHWAY) {
			int max = Math.max(0, pageHeight - (height - STATUS_H - 6 - TOP));
			listScroll = pageScroll;
			scrollbar(context, contentX() + contentW() + 4, TOP, height - STATUS_H - 6, pageHeight, max);
		}
	}

	/** Pause only while something is going, Resume only while something is paused. */
	private void updateHeaderButtons() {
		if (pauseButton == null) return;
		boolean roamPaused = HighwayRoam.active() && HighwayRoam.paused() || TrailFollow.active() && TrailFollow.paused();
		boolean going = BaritoneBridge.isPathing() || process() != null || SmartTravel.active()
				|| HighwayRoam.active() && !HighwayRoam.paused() || TrailFollow.active() && !TrailFollow.paused();
		resumeButton.active = roamPaused || BaritoneBridge.paused();
		pauseButton.active = going && !resumeButton.active;
	}

	/** What Baritone is doing right now, at the top of every page: a coloured state, then where to, how far, how fast. */
	private void drawStatusChip(DrawContext context, int x) {
		String state;
		int color;
		String detail = "";
		TripBar.Where where = TripBar.where(client);
		boolean pathing = BaritoneBridge.isPathing();
		String process = process();
		if (HighwayRoam.active() && HighwayRoam.paused() || TrailFollow.active() && TrailFollow.paused()) {
			state = "Paused";
			color = AMBER;
		} else if (TrailFollow.active() && !TrailFollow.flying()) {
			state = "Following";
			color = GOOD;
		} else if (where != null && (where.flying())) {
			state = "Flying";
			color = PURPLE;
		} else if (HighwayRoam.active()) {
			state = "Roaming";
			color = BLUE;
		} else if (pathing || process != null) {
			state = process != null ? process : "Moving";
			color = GOOD;
		} else {
			state = "Idle";
			color = DIM;
		}
		if (where != null) {
			String line = TripBar.line(client, where);
			line = line.replaceFirst("^▶ ", "");
			int cut = line.lastIndexOf(" · ");
			if (cut > 0) line = line.substring(0, cut);
			detail = line;
		} else if (process != null) {
			String goal = BaritoneBridge.goal();
			int[] at = TripBar.goalXZ(goal);
			if (at != null && client.player != null && !ModsPanel.streamSafe()) {
				detail = String.format(Locale.ROOT, "%,d blocks away", (int) Math.hypot(at[0] - client.player.getX(), at[1] - client.player.getZ()));
			}
		} else if (!pathing) {
			detail = "Pick something to do below. " + NaughtyOrNiceClient.INSTANCE.stopKeyName() + " stops it anywhere.";
		}
		int sw = textRenderer.getWidth(state) + 20;
		roundedBorder(context, x, 9, sw, 16, tint(color, NAV, 0.16f), tint(color, NAV, 0.55f));
		boolean blink = !state.equals("Idle") && (System.currentTimeMillis() / 500) % 2 == 0;
		context.fill(x + 6, 15, x + 10, 19, blink ? tint(color, NAV, 0.5f) : color);
		context.drawText(textRenderer, state, x + 14, 13, color, false);
		int dx = x + sw + 8;
		int maxW = width - 10 - 70 - 4 - 62 - 4 - 58 - 10 - dx;
		if (!detail.isEmpty() && maxW > 30) context.drawText(textRenderer, fit(detail, maxW), dx, 13, MUTED, false);
	}

	/** Which of Baritone's jobs is running, in a word, or null. */
	private static String process() {
		if (BaritoneBridge.processActive("getElytraProcess")) return "Flying";
		if (BaritoneBridge.processActive("getMineProcess")) return "Mining";
		if (BaritoneBridge.processActive("getFollowProcess")) return "Following";
		if (BaritoneBridge.processActive("getFarmProcess")) return "Farming";
		if (BaritoneBridge.processActive("getExploreProcess")) return "Exploring";
		return null;
	}

	/** Highway roam: the arrows on the compass, and the way it'll go. */
	private void drawHighway(DrawContext context) {
		if (compassYou != null && compassYou[1] >= TOP && compassYou[1] + compassYou[3] <= height - STATUS_H) {
			// The compass's middle: you, with the highways all round.
			int mx = compassYou[0] + compassYou[2] / 2, my = compassYou[1] + compassYou[3] / 2;
			context.fill(mx - 2, my - 6, mx + 2, my - 2, MUTED);
			context.drawText(textRenderer, "you", mx - textRenderer.getWidth("you") / 2, my + 1, DIM, false);
		}
		if (routeW <= 0 || routeY < TOP || routeY > height - STATUS_H) return;
		String way = HighwayRoam.active() ? "Roaming to " + HighwayRoam.target() + ". Stop roaming, or " + NaughtyOrNiceClient.INSTANCE.stopKeyName() + ", ends it."
				: Safe.get("describing the roam", () -> HighwayRoam.describe(client), "");
		if (way.isEmpty()) way = NNConfig.INSTANCE.roamToCoords ? "Type the x and z to go to." : "Pick a highway on the compass.";
		int ly = routeY;
		for (var l : textRenderer.wrapLines(Text.literal(way), routeW)) {
			if (ly > routeY + 20) break;
			context.drawText(textRenderer, l, routeX, ly, TEXT, false);
			ly += 10;
		}
		context.drawText(textRenderer, fit("Breaks through, bridges gaps or goes round. What you set here is kept.", routeW), routeX, routeY + 22, DIM, false);
	}

	@Override
	public void render(DrawContext context, int realMouseX, int realMouseY, float delta) {
		int mouseX = (int) Math.floor(scaled(realMouseX)), mouseY = (int) Math.floor(scaled(realMouseY));
		var m = context.getMatrices();
		m.pushMatrix();
		m.scale(uiScale, uiScale);
		boolean hidden = ModsPanel.streamSafe() || !BaritoneBridge.present();
		for (var e : children()) {
			if (e instanceof Field f && hidden) f.visible = false;
		}
		updateHeaderButtons();
		super.render(context, mouseX, mouseY, delta);
		updateStatus();
		drawStatusBar(context);
		m.popMatrix();
	}

	/** Watches the job handed to Baritone, and reads Baritone's own answer to the last command. */
	private void updateStatus() {
		if (watchProcess != null && !BaritoneBridge.processActive(watchProcess)) {
			replyMark = -1;
			say(watchFailed, WARN);
			watchProcess = null;
		}
		// Baritone's answer to the last command: an error (in red) if it said one, otherwise the last thing it said.
		// Only what it says in the few seconds after, each thing once, so it never covers a newer message.
		if (replyMark >= 0 && System.currentTimeMillis() - ranAt > 3000) replyMark = -1;
		if (replyMark >= 0) {
			List<BaritoneBridge.Reply> said = BaritoneBridge.repliesSince(replyMark);
			BaritoneBridge.Reply shown = said.stream().filter(BaritoneBridge.Reply::error).findFirst().orElse(said.isEmpty() ? null : said.get(said.size() - 1));
			if (shown != null) {
				say("Baritone: " + shown.text(), shown.error() ? ERROR : PLAIN);
				if (shown.error()) replyMark = -1;
				else replyMark = BaritoneBridge.replyCount();
			}
		}
	}

	/** The bar along the bottom: the last thing done or said, with a mark for how it went. */
	private void drawStatusBar(DrawContext context) {
		int y = height - STATUS_H;
		context.fill(0, y, width, height, NAV);
		int x = contentX();
		if (status.isEmpty()) {
			context.drawText(textRenderer, "Esc goes back. " + NaughtyOrNiceClient.INSTANCE.stopKeyName() + " stops Baritone from anywhere in the game.", x, y + 5, DIM, false);
			return;
		}
		String mark = statusColor == ERROR ? "✖" : statusColor == OK ? "✔" : statusColor == WARN ? "!" : "•";
		// Fresh news stands out for a moment.
		boolean fresh = System.currentTimeMillis() - statusAt < 1500;
		if (fresh) context.fill(0, y, width, y + 1, statusColor);
		context.drawText(textRenderer, mark, x, y + 5, statusColor, false);
		context.drawText(textRenderer, fit(status, contentW() - 14), x + 12, y + 5, statusColor, false);
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click realClick, boolean doubled) {
		Click click = scaled(realClick);
		if (super.mouseClicked(click, doubled)) return true;
		for (Object[] hit : List.copyOf(rowHits)) {
			if (click.x() >= (int) hit[0] && click.x() < (int) hit[2] && click.y() >= (int) hit[1] && click.y() < (int) hit[3]) {
				((Runnable) hit[4]).run();
				return true;
			}
		}
		setFocused(null);
		return false;
	}

	@Override
	public boolean mouseReleased(Click click) {
		return super.mouseReleased(scaled(click));
	}

	@Override
	public boolean mouseDragged(Click click, double dx, double dy) {
		return super.mouseDragged(scaled(click), scaled(dx), scaled(dy));
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		super.mouseMoved(scaled(mouseX), scaled(mouseY));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (page == Page.GO || page == Page.HIGHWAY) {
			int max = Math.max(0, pageHeight - (height - STATUS_H - 6 - TOP));
			int next = (int) Math.max(0, Math.min(max, pageScroll - verticalAmount * 20));
			if (next != pageScroll) {
				pageScroll = next;
				clearAndInit();
			}
			return true;
		}
		listScroll -= verticalAmount * 22;
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		// Enter in the coordinate boxes is Go.
		if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) && page == Page.GO
				&& (getFocused() == xField || getFocused() == yField || getFocused() == zField)) {
			goTyped();
			return true;
		}
		return super.keyPressed(input);
	}

	// ---- For the self-test ----

	/** What Baritone suggests for what's typed on the Every command page. */
	public List<String> testSuggestions() {
		return suggestions;
	}

	/** Shows a page (0 Go to…, 1 Do, 2 Highway roam, 3 Settings, 4 Every command). */
	public void testPage(int index) {
		page = Page.values()[index];
		pageScroll = 0;
		clearAndInit();
	}

	/** Clicks the button or switch whose label starts with {@code label}, as the mouse would. */
	public boolean testPress(String label) {
		// The exact label first, so "Go" isn't taken for "Go to…" or "Stop" for "Stop at 2 hearts".
		for (boolean exact : new boolean[] {true, false}) {
			for (var e : List.copyOf(children())) {
				if (!(e instanceof ClickableWidget w) || !w.visible || w instanceof TextFieldWidget) continue;
				String shown = w.getMessage().getString();
				if (exact ? shown.equals(label) : shown.startsWith(label)) {
					testClickAt(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
					return true;
				}
			}
		}
		return false;
	}

	/** Whether a button or switch whose label starts with {@code label} is showing. */
	public boolean testHas(String label) {
		return children().stream().anyMatch(e -> e instanceof ClickableWidget w && w.visible && w.getMessage().getString().startsWith(label));
	}

	/** Whether the button labelled exactly {@code label} can be pressed (not greyed out). */
	public boolean testEnabled(String label) {
		return children().stream().anyMatch(e -> e instanceof ClickableWidget w && w.visible && w.active && w.getMessage().getString().equals(label));
	}

	/** Which page is showing (0 Go to…, 1 Do, 2 Highway roam, 3 Settings, 4 Every command). */
	public int testPageShown() {
		return page.ordinal();
	}

	/** Which kind of place Go to… shows ("" for all). */
	public String testFilter() {
		return kindFilter == null ? "" : kindFilter.name();
	}

	/** Types into the box whose hint is {@code hint}. */
	public boolean testType(String hint, String text) {
		for (var e : children()) {
			if (e instanceof TextFieldWidget f && f.getMessage().getString().equals(hint)) {
				f.setText(text);
				return true;
			}
		}
		return false;
	}

	/** What a box on screen holds, or null if there's no such box. */
	public String testField(String hint) {
		for (var e : children()) {
			if (e instanceof TextFieldWidget f && f.getMessage().getString().equals(hint)) return f.getText();
		}
		return null;
	}

	/** Whether a row button with this key was drawn. */
	public boolean testHasRow(String key) {
		return rowHits.stream().anyMatch(h -> key.equals(h[5]));
	}

	/** Clicks a place's Go (or its ×) on the Go to… list as last drawn. */
	public boolean testRow(String name, boolean remove) {
		for (Object[] hit : List.copyOf(rowHits)) {
			if (name.equals(hit[5]) && remove == (boolean) hit[6]) {
				testClickAt(((int) hit[0] + (int) hit[2]) / 2.0, ((int) hit[1] + (int) hit[3]) / 2.0);
				return true;
			}
		}
		return false;
	}

	/** A click at a point on this screen's own layout, as the game would deliver it (on its GUI scale). */
	private void testClickAt(double x, double y) {
		Click click = new Click(x * uiScale, y * uiScale, new net.minecraft.client.input.MouseInput(0, 0));
		mouseClicked(click, false);
		mouseReleased(click);
	}

	/** Whether the status is shown as an error (in red). */
	public boolean testStatusIsError() {
		return statusColor == ERROR;
	}

	public String testStatus() {
		return status;
	}
}
