package com.greysworkshop.naughtyornice;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Baritone, with every one of its jobs on a button, in Naughty or Nice's own look: nothing needs typing as a command.
 * <ul>
 * <li>A header that's always there: what Baritone is doing right now (where to, how far, how fast, what mining has
 * got you), Pause, Resume and Stop, and when it's idle, what you did lately, to do again with a click.</li>
 * <li>A search bar on every page (press /) that understands what you mean: coordinates go there, a place's name goes
 * to it, a block mines it, a player's name follows them, a setting's name flips it.</li>
 * <li>Go (every known place and typed coordinates, one Go each), Mine (every block by picture and name, ore families,
 * what's near you, how many to get), Follow (players with faces, every kind of mob), Roam (highways and old-chunk
 * trails), Tools (find a block, tunnel, surface, explore, farm, pick up, clear, fill or build in an area, build a
 * schematic, download), Settings (all of Baritone's in plain words, grouped, with the right control for each, and
 * presets) and Commands (for anything else).</li>
 * </ul>
 * It sizes itself like the main screen (Settings → Screen size), whatever the game's GUI scale. Opened with B,
 * /nn baritone, or the Baritone tab.
 */
public class BaritoneScreen extends Screen {
	private enum Page {
		GO("Go"), MINE("Mine"), FOLLOW("Follow"), ROAM("Roam"), TOOLS("Tools"), SETTINGS("Settings"), COMMANDS("Commands");

		final String label;

		Page(String label) {
			this.label = label;
		}
	}

	// Naughty or Nice's look, light or dark as the main screen is set (☀ / ☾ in either).
	private static int NAV, SUBNAV, PAGE_BG, CARD, FIELD, BORDER, TEXT, MUTED, DIM, LINK, SOFT, SOFT_HOVER, HOVER_BG, HOVER_BORDER, INPUT_BORDER, GOOD, BAD,
			AMBER, BLUE, PURPLE, PICKED_BG, PICKED_HOVER, GOLD, MENU_BG, SHADOW, DANGER_BG, DANGER_HOVER, DANGER_EDGE;
	/** The header, tabs and bottom bar stay dark in both themes, as on the main screen. */
	private static final int NAV_TEXT = 0xFFE7E9EC, NAV_MUTED = 0xFFA7B1BC, NAV_DIM = 0xFF7D8894, NAV_SOFT = 0xFF2A3644, NAV_SOFT_HOVER = 0xFF364557,
			NAV_BORDER = 0xFF3A4654, YELLOW = 0xFFFFD814, YELLOW_HOVER = 0xFFF7CA00, YELLOW_EDGE = 0xFFFCD200, BUTTON_TEXT = 0xFF0F1111, ORANGE = 0xFFFFA41C;
	private static boolean dark;

	static {
		applyTheme(NNConfig.INSTANCE.darkMode);
	}

	private static void applyTheme(boolean isDark) {
		dark = isDark;
		NAV = isDark ? 0xFF0A0D11 : 0xFF131921;
		SUBNAV = isDark ? 0xFF161D26 : 0xFF232F3E;
		PAGE_BG = isDark ? 0xFF0F1318 : 0xFFEAEDED;
		CARD = isDark ? 0xFF1A2129 : 0xFFFFFFFF;
		FIELD = isDark ? 0xFF0F141A : 0xFFFFFFFF;
		BORDER = isDark ? 0xFF2D3742 : 0xFFD5D9D9;
		TEXT = isDark ? 0xFFE7E9EC : 0xFF0F1111;
		MUTED = isDark ? 0xFF9AA5B1 : 0xFF565959;
		DIM = isDark ? 0xFF7D8894 : 0xFF767B7B;
		LINK = isDark ? 0xFF5CC8E0 : 0xFF007185;
		SOFT = isDark ? 0xFF25303B : 0xFFF0F2F2;
		SOFT_HOVER = isDark ? 0xFF2E3A46 : 0xFFE3E6E6;
		HOVER_BG = isDark ? 0xFF222B35 : 0xFFF7FAFA;
		HOVER_BORDER = isDark ? 0xFF4A5663 : 0xFFA0A6A6;
		INPUT_BORDER = isDark ? 0xFF4A5663 : 0xFF888C8C;
		GOOD = isDark ? 0xFF4CD39B : 0xFF067D62;
		BAD = isDark ? 0xFFFF7A66 : 0xFFB12704;
		AMBER = isDark ? 0xFFFFC14D : 0xFFB06A00;
		BLUE = isDark ? 0xFF5BA8FF : 0xFF2457C5;
		PURPLE = isDark ? 0xFFB388FF : 0xFF6A1B9A;
		PICKED_BG = isDark ? 0xFF243E50 : 0xFFE3F4F7;
		PICKED_HOVER = isDark ? 0xFF2C4A5E : 0xFFD3EDF2;
		GOLD = isDark ? 0xFFFFC83D : 0xFFB07A00;
		MENU_BG = isDark ? 0xFF121820 : 0xFFFFFFFF;
		SHADOW = isDark ? 0x70000000 : 0x40000000;
		DANGER_BG = isDark ? 0xFF8F2B2B : 0xFFC7332B;
		DANGER_HOVER = isDark ? 0xFFC0453F : 0xFFDC4A41;
		DANGER_EDGE = isDark ? 0xFFB03A35 : 0xFFA82822;
	}

	/** A bright colour (a kind's, a list's) made readable on the page: darker on the light theme. */
	private static int ink(int color) {
		return dark ? color : tint(color, 0xFF000000, 0.42f);
	}

	private void toggleTheme() {
		NNConfig.INSTANCE.darkMode = !NNConfig.INSTANCE.darkMode;
		NNConfig.save();
		applyTheme(NNConfig.INSTANCE.darkMode);
		ShopScreen.applyTheme(NNConfig.INSTANCE.darkMode);
		relayout();
	}
	/** Status colours: done, error, warning, plain. */
	private static final int OK = 0xFF7CE0A0, ERROR = 0xFFFF8A80, WARN = 0xFFFFC14D, PLAIN = 0xFFDDDDDD;

	private static final int HEADER = 26, TABS = 20, TOP = HEADER + TABS + 8, STATUS_H = 18;

	private static Page page = Page.GO;
	/** Go: which kind of place is shown (null: all). */
	private static Destinations.Kind kindFilter;
	/** Settings: which group (null: all, "changed", "trips" or a category's name). */
	private static String settingsGroup;
	/** Mine: which group of blocks the picker shows. */
	private static int mineTab = 3;
	/** Follow: which kind of mob the list shows. */
	private static int mobTab = 0;

	private Pill pauseButton, resumeButton;
	/** The compass's middle (x, y, w, h) when it's a "you are here" mark, not a button. */
	private int[] compassYou;
	private double listScroll;
	/** Form pages (Roam, Tools) scroll by being laid out again; this is how far. */
	private static int pageScroll;
	private int pageHeight;

	/** Where clicks land on what's drawn by hand: rows, chips, icons. */
	private record Hit(int x0, int y0, int x1, int y1, Runnable run, String key, boolean remove, String hint) {
		boolean has(double x, double y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	private final List<Hit> hits = new ArrayList<>();
	private final List<Hit> smartHits = new ArrayList<>();

	private Field searchField, waypointField, xField, yField, zField, playerField, heightField, distanceField, roamXField, roamZField, smartField,
			amountField, minYField, maxYField, gotoYField, thisWayField, exploreXField, exploreZField, farmField, tunnelHField, tunnelWField, tunnelDField;
	private String status = "";
	private int statusColor = MUTED;
	private long statusAt;
	/** Whatever the mouse is over says what it does here, in the bar along the bottom. */
	private String hoverHint;
	/**
	 * The job last handed to Baritone, watched while this screen is open: when Baritone stops it (done, or given up:
	 * nothing to farm, nobody to follow), the status says so rather than still saying it's working.
	 */
	private String watchProcess, watchFailed;

	/** Commands: the search, the one picked, what to add after it, and what Baritone suggests for that. */
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
	/** Roam: where the compass is, and where the way it'll go is written. */
	private int compassX, compassY, routeX, routeY, routeW;
	/** Mine: the picker's area and the picks' area; Follow: the players' list and the mobs'. */
	private int[] browserArea, picksArea, playersArea, mobsArea, mineProgressArea;

	/** The screen is laid out at its own size and drawn scaled by this, like the main screen. */
	private float uiScale = 1;

	/** A menu, or a picker, over the page (it takes the clicks while it's open). */
	private Overlay overlay;

	/** The block (or mob) lists: the search box, which group, how far scrolled. */
	private final Browser mineBrowser = new Browser();
	private final Browser mobBrowser = new Browser();

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

	// ---- Things done lately, to do again with a click ----

	private record Recent(String label, ItemStack icon, String glyph, Runnable run) {}

	private static final List<Recent> recent = new ArrayList<>();

	private static void remember(String label, ItemStack icon, String glyph, Runnable run) {
		recent.removeIf(r -> r.label.equals(label));
		recent.add(0, new Recent(label, icon == null ? ItemStack.EMPTY : icon, glyph, run));
		while (recent.size() > 8) recent.remove(recent.size() - 1);
	}

	// ---- Widgets in the mod's look ----

	/** How a pill looks: the main action, a quiet one, picked, or one that stops things. */
	private enum Look { PRIMARY, SOFT, PICKED, DANGER, OVERWORLD, NETHER, NAV }

	/** A rounded button. Its message is its label (what the self-test presses it by); a badge can go after it. */
	private final class Pill extends ClickableWidget {
		private final Runnable run;
		private final Look look;
		private Supplier<String> badge;
		private String icon;
		private String hint;
		private ItemStack item = ItemStack.EMPTY;

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

		Pill hint(String hint) {
			this.hint = hint;
			return this;
		}

		Pill item(ItemStack item) {
			this.item = item;
			return this;
		}

		/** The compass's arrow, as an angle (radians, 0 = north/up), or NaN for none. */
		double arrow = Double.NaN;

		@Override
		protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			float t = ease("pill:" + getMessage().getString() + ":" + getX() + ":" + getY(), isHovered() && active);
			int text = paintButton(context, getX(), getY(), width, height, look, t, active);
			if (!Double.isNaN(arrow)) {
				int sub = look == Look.PRIMARY ? 0xFF5A4A00 : look == Look.SOFT ? MUTED : text;
				drawArrow(context, getX() + width / 2.0, getY() + 8.5, arrow, 5, sub);
				String label = getMessage().getString();
				context.drawText(textRenderer, label, getX() + (width - textRenderer.getWidth(label)) / 2, getY() + 15, text, false);
				return;
			}
			String label = getMessage().getString();
			if (icon != null && height >= 24) {
				// Tall: the icon above the label.
				int sub = look == Look.PRIMARY ? 0xFF5A4A00 : MUTED;
				context.drawText(textRenderer, icon, getX() + (width - textRenderer.getWidth(icon)) / 2, getY() + 4, sub, false);
				context.drawText(textRenderer, label, getX() + (width - textRenderer.getWidth(label)) / 2, getY() + 14, text, false);
				return;
			}
			String b = badge == null ? null : badge.get();
			boolean chevron = label.endsWith("▾");
			if (chevron) label = label.substring(0, label.length() - 1).stripTrailing();
			String full = (icon == null ? "" : icon + " ") + label;
			int iw = item.isEmpty() ? 0 : 18;
			int bw = b == null ? 0 : textRenderer.getWidth(b) + 6;
			String shown = fit(full, width - 8 - bw - iw);
			int cw = chevron ? (label.isEmpty() ? 5 : 9) : 0;
			int tw = textRenderer.getWidth(shown) + bw + iw + cw;
			int tx = getX() + (width - tw) / 2, ty = getY() + (height - 8) / 2;
			if (iw > 0) drawIcon(context, item, tx, getY() + (height - 16) / 2);
			context.drawText(textRenderer, shown, tx + iw, ty, text, false);
			if (chevron) chevron(context, tx + iw + textRenderer.getWidth(shown) + cw - 5, ty + 3, text);
			if (b != null) context.drawText(textRenderer, b, tx + tw - bw + 6, ty, look == Look.PRIMARY ? 0xFF5A4A00 : MUTED, false);
		}

		@Override
		public void onClick(Click click, boolean doubled) {
			if (active) run.run();
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
		private String hint;

		Toggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
			super(x, y, w, 14, Text.literal(label));
			this.get = get;
			this.set = set;
		}

		Toggle hint(String hint) {
			this.hint = hint;
			return this;
		}

		@Override
		protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			boolean on = get.getAsBoolean();
			drawSwitch(context, getX(), getY() + 2, on, isHovered());
			context.drawText(textRenderer, fit(getMessage().getString(), width - 26), getX() + 24, getY() + 3, isHovered() ? LINK : TEXT, false);
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
		/** What Enter does in it (null: nothing). */
		private Runnable enter;

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

		Field onEnter(Runnable enter) {
			this.enter = enter;
			return this;
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

	/** A switch for one of Baritone's own on/off settings (shown only if this Baritone has it). */
	private Toggle baritoneToggle(int x, int y, int w, String setting, String label) {
		if (BaritoneBridge.bool(setting) == null) return null;
		Toggle t = new Toggle(x, y, w, label, () -> Boolean.TRUE.equals(BaritoneBridge.bool(setting)), v -> {
			BaritoneBridge.setBool(setting, v);
			BaritoneBridge.saveSettings();
			settingsReadAt = 0;
			say(BaritoneSettingsGuide.plainName(setting) + (v ? ": on" : ": off"), PLAIN);
		});
		t.hint(BaritoneSettingsGuide.description(setting));
		addDrawableChild(t);
		hideIfOutside(t);
		return t;
	}

	/** A box with its name written above it instead of inside (the name stays its hint for the self-test). */
	private Field labelledField(int x, int y, int w, String hint, Field before, String fallback) {
		label(hint, x, y - 10);
		Field f = field(x, y, w, hint, before, fallback);
		f.setPlaceholder(Text.empty());
		return f;
	}

	/** A box, at (x, y) for its text; its frame is drawn round it. Keeps what was typed when the page is laid out again. */
	private Field field(int x, int y, int w, String hint, Field before, String fallback) {
		Field f = new Field(x + 5, y + 4, w - 10, hint);
		f.setMaxLength(256);
		f.setText(before != null ? before.getText() : fallback == null ? "" : fallback);
		if (before != null) f.enter = before.enter;
		addDrawableChild(f);
		fields.add(f);
		hideIfOutside(f);
		return f;
	}

	/** Form pages scroll by being laid out again; what's off the page isn't shown or clickable. */
	private void hideIfOutside(ClickableWidget w) {
		if (w.getY() >= TOP - 2 && w.getY() + w.getHeight() <= height - STATUS_H - 2) return;
		// The header and the tabs are always there; the page's own widgets scrolled up under them are hidden.
		if (!layingPage && w.getY() < HEADER + TABS) return;
		w.visible = false;
		w.active = false;
	}

	/** Whether init is laying out the page (not the header and tabs above it). */
	private boolean layingPage;

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

	private int boldWidth(String s) {
		return textRenderer.getWidth(Text.literal(s).styled(st -> st.withBold(true)));
	}

	private void bold(DrawContext context, String s, int x, int y, int color) {
		context.drawText(textRenderer, Text.literal(s).styled(st -> st.withBold(true)), x, y, color, false);
	}

	private Card card(int x, int y, int w, int h, String title, String note) {
		Card c = new Card(x, y, w, h, title, note);
		cards.add(c);
		return c;
	}

	private void label(String text, int x, int y) {
		labels.add(new Object[] {text, x, y});
	}

	private void drawIcon(DrawContext context, ItemStack stack, int x, int y) {
		if (stack == null || stack.isEmpty()) return;
		context.drawItem(stack, x, y);
	}

	// ---- Layout ----

	/** Lays the page out again, keeping the box you were typing in and where in it. */
	private void relayout() {
		String focused = getFocused() instanceof Field f ? f.getMessage().getString() : null;
		int cursor = getFocused() instanceof Field f ? f.getCursor() : 0;
		clearAndInit();
		if (focused == null) return;
		for (var e : children()) {
			if (e instanceof Field f && f.getMessage().getString().equals(focused) && f.visible) {
				setFocused(f);
				f.setFocused(true);
				f.setCursor(Math.min(cursor, f.getText().length()), false);
				break;
			}
		}
	}

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
		browserArea = picksArea = playersArea = mobsArea = mineProgressArea = null;
		fields.clear();

		// Header, right to left: Stop, Resume, Pause (always), and light/dark.
		int right = width - 8;
		Pill stop = pill(right - 58, 5, 58, 16, "Stop", Look.DANGER, this::stopEverything);
		stop.icon("■").hint("Stops whatever Baritone is doing (" + NaughtyOrNiceClient.INSTANCE.stopKeyName() + " does too, anywhere in the game)");
		resumeButton = pill(right - 58 - 4 - 64, 5, 64, 16, "Resume", Look.NAV, () -> {
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
		}).icon("▶").hint("Carries on with what was paused");
		pauseButton = pill(right - 58 - 4 - 64 - 4 - 52, 5, 52, 16, "Pause", Look.NAV, () -> run("pause", "Paused")).hint("Holds Baritone where it is; Resume carries on");
		themeX = right - 58 - 4 - 64 - 4 - 52 - 6 - 16;
		updateHeaderButtons();

		// The pages, as tabs with their pictures.
		int tx = 6;
		boolean roomy = tabsWidth(true) + 220 < width;
		for (Page p : Page.values()) {
			TabButton tab = new TabButton(tx, HEADER, p, roomy);
			addDrawableChild(tab);
			tx += tab.getWidth() + 2;
		}
		tabsEnd = tx;
		layingPage = false;
		smartField = null;
		if (!BaritoneBridge.present() || ModsPanel.streamSafe()) return;
		// The search bar that does anything, in the header, as on the main screen.
		int left = logoEnd() + 12;
		int sw = Math.min(460, themeX - 10 - left);
		if (sw >= 120) {
			int sx = left + Math.max(0, (themeX - 10 - left - sw) / 2);
			smartBox = new int[] {sx, 5, sw, 16};
			Field before = smartFieldKeep;
			smartField = new Field(sx + 18, 9, sw - 40, smartHint(sw));
			smartField.setMaxLength(200);
			smartField.setText(before != null ? before.getText() : "");
			smartField.setChangedListener(t -> smartPick = 0);
			smartField.onEnter(this::runSmart);
			addDrawableChild(smartField);
			smartFieldKeep = smartField;
		} else {
			smartBox = null;
		}

		int x = contentX(), y = TOP - pageScroll;
		layingPage = true;
		switch (page) {
			case GO -> initGo(x, TOP);
			case MINE -> initMine(x, TOP);
			case FOLLOW -> initFollow(x, TOP);
			case ROAM -> pageHeight = initRoam(x, y) - y;
			case TOOLS -> pageHeight = initTools(x, y) - y;
			case SETTINGS -> initSettings(x, TOP);
			case COMMANDS -> initCommands(x, TOP);
		}
		layingPage = false;
		if (overlay instanceof Picker p) p.attach();
	}

	/** Where the search bar is (x, y, w, h), and the box itself kept across layouts. */
	private int[] smartBox;
	private Field smartFieldKeep;
	private int themeX, tabsEnd;
	/** The tabs' underline, gliding to the open one. */
	private float underlineX, underlineW;

	private String smartHint(int w) {
		String full = "Search or type anything: diamonds, home, 1200 -3400, a player, sprint…";
		return textRenderer.getWidth(full) <= w - 44 ? full : "Search or type anything…";
	}

	/** Where the name at the top left ends (and the back link, when opened from Naughty or Nice). */
	private int logoEnd() {
		int x = 8;
		if (parent != null) x += textRenderer.getWidth("‹") + 10;
		return x + boldWidth("baritone");
	}

	private static ItemStack pageIcon(Page p) {
		return new ItemStack(switch (p) {
			case GO -> net.minecraft.item.Items.COMPASS;
			case MINE -> net.minecraft.item.Items.DIAMOND_PICKAXE;
			case FOLLOW -> net.minecraft.item.Items.LEAD;
			case ROAM -> net.minecraft.item.Items.RAIL;
			case TOOLS -> net.minecraft.item.Items.CRAFTING_TABLE;
			case SETTINGS -> net.minecraft.item.Items.COMPARATOR;
			case COMMANDS -> net.minecraft.item.Items.COMMAND_BLOCK;
		});
	}

	private int tabsWidth(boolean icons) {
		int w = 6;
		for (Page p : Page.values()) w += textRenderer.getWidth(p.label) + (icons ? 26 : 14) + 2;
		return w;
	}

	/** A page's tab: its picture and name on the dark bar; the open one bright, with the orange underline under it. */
	private final class TabButton extends ClickableWidget {
		final Page p;
		final boolean icon;

		TabButton(int x, int y, Page p, boolean icon) {
			super(x, y, textRenderer.getWidth(p.label) + (icon ? 26 : 14), TABS, Text.literal(p.label));
			this.p = p;
			this.icon = icon;
		}

		@Override
		protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			boolean open = p == page;
			float t = ease("tab:" + p, isHovered() || open);
			if (!open && t > 0) rounded(context, getX() + 1, getY() + 2, width - 2, height - 4, alphaOf(0xFFFFFFFF, (int) (0x14 * t)));
			int x = getX() + 7;
			if (icon) {
				var m = context.getMatrices();
				m.pushMatrix();
				m.translate(x, getY() + (height - 12) / 2f);
				m.scale(0.75f, 0.75f);
				drawIcon(context, pageIcon(p), 0, 0);
				m.popMatrix();
				x += 14;
			}
			int color = open ? NAV_TEXT : blend(NAV_MUTED, NAV_TEXT, t);
			if (open) bold(context, p.label, x, getY() + (height - 8) / 2, color);
			else context.drawText(textRenderer, p.label, x, getY() + (height - 8) / 2, color, false);
		}

		@Override
		public void onClick(Click click, boolean doubled) {
			showPage(p);
		}

		@Override
		protected void appendClickableNarrations(NarrationMessageBuilder builder) {
			appendDefaultNarrations(builder);
		}
	}

	private static String pageHint(Page p) {
		return switch (p) {
			case GO -> "Every place it knows, and coordinates: one click goes there";
			case MINE -> "Pick blocks by picture and name, how many, and go";
			case FOLLOW -> "Follow a player or any kind of mob";
			case ROAM -> "Ride the highways, or follow the trail of old chunks someone left";
			case TOOLS -> "Find a block, tunnel, surface, explore, farm, clear or fill an area, build, download";
			case SETTINGS -> "Every Baritone setting in plain words, and presets";
			case COMMANDS -> "Every Baritone command, for anything that isn't a button";
		};
	}

	private void showPage(Page p) {
		if (page != p) {
			page = p;
			listScroll = 0;
			pageScroll = 0;
		}
		overlay = null;
		clearAndInit();
	}

	/** The page's width: the whole screen up to a comfortable reading width. */
	private int contentW() {
		return Math.min(780, width - 24);
	}

	private int contentX() {
		return (width - contentW()) / 2;
	}

	/** Two columns when there's room. */
	private boolean wide() {
		return contentW() >= 560;
	}

	private int pageBottom() {
		return height - STATUS_H - 6;
	}

	// ---- Talking to Baritone ----

	/** What Baritone had said before the last command run here; what it says after is its answer. */
	private int replyMark = -1;
	private long ranAt;

	/** Runs a command and shows whether Baritone took it; Baritone's own answer replaces that once it comes. */
	private boolean run(String command, String said) {
		watchProcess = null;
		BaritoneBridge.listen();
		replyMark = BaritoneBridge.replyCount();
		ranAt = System.currentTimeMillis();
		boolean ok = BaritoneBridge.run(command);
		say(ok ? said : "Baritone didn't take that (see its message in chat)", ok ? OK : ERROR);
		if (ok && !command.matches("(stop|pause|resume|cancel|forcecancel|set .*|sel .*|blacklist.*)")) jobLabel = said;
		return ok;
	}

	/** Runs a command, then checks a moment later that Baritone's {@code process} really is at it. */
	private boolean runWatched(String command, String said, String process, String failed) {
		boolean ok = run(command, said);
		if (!ok || !status.equals(said)) return ok;
		watchProcess = process;
		watchFailed = failed;
		return true;
	}

	private void stopEverything() {
		HighwayRoam.stop(null);
		SmartTravel.stop();
		TrailFollow.stop(null);
		MineJob.stopped();
		run("stop", "Stopped");
		relayout();
	}

	/** Something waiting for a second click to be sure ("undo-all", "remove:Home"), and since when. */
	private String confirmKey;
	private long confirmAt;

	/** True on the second click within 4 seconds; on the first, asks (in the status) and the button says "Sure?". */
	private boolean confirmed(String key, String ask) {
		if (asking(key)) {
			confirmKey = null;
			return true;
		}
		confirmKey = key;
		confirmAt = System.currentTimeMillis();
		say(ask, WARN);
		relayout();
		return false;
	}

	private boolean asking(String key) {
		return key.equals(confirmKey) && System.currentTimeMillis() - confirmAt < 4000;
	}

	private void say(String text, int color) {
		status = text;
		statusColor = color;
		statusAt = System.currentTimeMillis();
	}

	// ---- Go ----

	private void initGo(int x, int y) {
		var p = client.player;
		int w = contentW();
		boolean two = wide();
		int colW = two ? (w - 10) * 3 / 5 : w;
		int rx = two ? x + colW + 10 : x, rw = two ? w - colW - 10 : w;

		// Go to coordinates: x, y, z, Go, and where to fill them in from.
		card(x, y, colW, 50, "Go to coordinates", p == null ? "Walks, or flies when it can"
				: String.format(Locale.ROOT, "You're at %,d  %,d  %,d", p.getBlockX(), p.getBlockY(), p.getBlockZ()));
		int fy = y + 26, inner = colW - 20;
		int fillW = textRenderer.getWidth("Fill from ▾") + 16;
		int fw = (inner - 3 * 6 - 56 - 6 - fillW) / 3;
		xField = field(x + 10, fy, fw, "x", xField, "").onEnter(this::goTyped);
		yField = field(x + 10 + fw + 6, fy, fw, "y (optional)", yField, "").onEnter(this::goTyped);
		zField = field(x + 10 + 2 * (fw + 6), fy, fw, "z", zField, "").onEnter(this::goTyped);
		pill(x + 10 + 3 * (fw + 6), fy, 56, 18, "Go there", Look.PRIMARY, this::goTyped).hint("Goes to the typed coordinates (Enter does too)");
		Pill fill = pill(x + colW - 10 - fillW, fy, fillW, 18, "Fill from ▾", Look.SOFT, () -> {});
		fill.hint("Your position, the clipboard, the latest trail or place given away, or the other dimension's matching spot");
		Pill fillRef = fill;
		// The menu opens under the button.
		replaceRun(fill, () -> openMenu(fillRef.getX(), fillRef.getY() + fillRef.getHeight() + 2, 220, fillOptions()));

		int sy = two ? y : y + 58;
		card(rx, sy, rw, 50, "Save this spot", null);
		int saveW = textRenderer.getWidth("Save where I am") + 18;
		waypointField = field(rx + 10, sy + 26, rw - 20 - saveW - 6, "Name this spot, e.g. Home", waypointField, "");
		waypointField.onEnter(this::saveHere);
		pill(rx + rw - 10 - saveW, sy + 26, saveW, 18, "Save where I am", Look.PRIMARY, this::saveHere).hint("Saves where you're standing under that name");

		// The places: search, sort, kinds.
		int ly = (two ? y + 50 : sy + 50) + 10;
		int searchW = Math.min(240, w / 2 - 10);
		searchField = field(x, ly, searchW, "Search places", searchField, "");
		searchField.setChangedListener(t -> listScroll = 0);
		NNConfig c = NNConfig.INSTANCE;
		int sx = x + w;
		String[][] sorts = {{"NAME", "A–Z"}, {"NEWEST", "Newest"}, {"NEAREST", "Nearest"}};
		for (String[] s : sorts) {
			int sw = textRenderer.getWidth(s[1]) + 14;
			sx -= sw;
			pill(sx, ly, sw, 18, s[1], s[0].equals(c.placesSort) ? Look.PICKED : Look.SOFT, () -> {
				c.placesSort = s[0];
				NNConfig.save();
				relayout();
			}).hint("Sort the places");
			sx -= 3;
		}
		label("§8Sort", sx - 24, ly + 5);
		java.util.Arrays.fill(kindCounts, 0);
		for (Destinations.Destination d : Destinations.all(client)) {
			kindCounts[d.kind().ordinal()]++;
			kindCounts[kindCounts.length - 1]++;
		}
		int cx = x, cy = ly + 24;
		cx += pill(cx, cy, textRenderer.getWidth("All") + 30, 14, "All", kindFilter == null ? Look.PICKED : Look.SOFT, () -> {
			kindFilter = null;
			listScroll = 0;
			relayout();
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
				relayout();
			}).badge(() -> String.valueOf(placesCount(k))).getWidth() + 4;
		}
		placesTop = cy + 20;
	}

	/** Gives a pill something to do that needs the pill itself (the menu opens under it). */
	private void replaceRun(Pill p, Runnable run) {
		remove(p);
		Pill again = new Pill(p.getX(), p.getY(), p.getWidth(), p.getHeight(), p.getMessage().getString(), p.look, run);
		again.hint = p.hint;
		again.icon = p.icon;
		addDrawableChild(again);
		hideIfOutside(again);
	}

	private void saveHere() {
		Destinations.Waypoint wp = Destinations.addHere(client, waypointField.getText());
		if (wp == null) {
			say("Type a name for this spot first", WARN);
			return;
		}
		say("Saved \"" + wp.name + "\" at " + ModsPanel.hide(wp.x + ", " + wp.y + ", " + wp.z), OK);
		waypointField.setText("");
		relayout();
	}

	private List<Option> fillOptions() {
		var p = client.player;
		List<Option> o = new ArrayList<>();
		o.add(new Option("My position", new ItemStack(net.minecraft.item.Items.COMPASS), null, () -> {
			if (p == null) return;
			fill(p.getBlockX(), p.getBlockY(), p.getBlockZ());
		}, "Where you're standing"));
		o.add(new Option("Paste coordinates", new ItemStack(net.minecraft.item.Items.PAPER), null, () -> {
			List<ChatIntel.Coord> coords = ChatIntel.coords(client.keyboard.getClipboard());
			if (coords.isEmpty()) {
				say("No coordinates on the clipboard", WARN);
				return;
			}
			ChatIntel.Coord c = coords.get(0);
			fill(c.x(), c.hasY() ? c.y() : null, c.z());
		}, "The first coordinates on your clipboard"));
		o.add(new Option("Latest trail's far end", new ItemStack(net.minecraft.item.Items.FILLED_MAP), null, this::fillTrail, "Where the newest chunk trail leads"));
		o.add(new Option("Latest place given away", new ItemStack(net.minecraft.item.Items.WRITABLE_BOOK), null, this::fillPlace, "The newest coordinates a player wrote in chat"));
		o.add(new Option("Overworld → Nether (÷8)", new ItemStack(net.minecraft.item.Items.NETHERRACK), null, () -> convert(true), "The matching spot in the nether"));
		o.add(new Option("Nether → Overworld (×8)", new ItemStack(net.minecraft.item.Items.GRASS_BLOCK), null, () -> convert(false), "The matching spot in the overworld"));
		return o;
	}

	private void fillTrail() {
		List<ChunkTrails.Trail> trails = NaughtyOrNiceClient.INSTANCE.ledger.chunkTrails.trails;
		if (trails.isEmpty()) {
			say("No chunk trails found yet", WARN);
			return;
		}
		ChunkTrails.Trail t = trails.stream().max(Comparator.comparingLong(tr -> tr.lastSeen)).orElseThrow();
		int[] end = t.awayEnd();
		fill(end[0], null, end[1]);
	}

	private void fillPlace() {
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
		Integer x = BaritoneScreen.whole(xField.getText()), z = BaritoneScreen.whole(zField.getText());
		String ys = yField.getText().strip();
		Integer y = ys.isEmpty() ? null : BaritoneScreen.whole(ys);
		if (x == null || z == null || !ys.isEmpty() && y == null) {
			say("Type whole numbers for x and z (y can be empty)", ERROR);
			return null;
		}
		return new Integer[] {x, y, z};
	}

	private void goTyped() {
		Integer[] at = typed();
		if (at == null) return;
		String name = at[0] + ", " + (at[1] == null ? "" : at[1] + ", ") + at[2];
		Destinations.Destination d = new Destinations.Destination(Destinations.Kind.CLIPBOARD, name, "Typed", at[0], at[1] == null ? Integer.MIN_VALUE : at[1], at[2], "", 0);
		go(d);
	}

	private void go(Destinations.Destination d) {
		String said = SmartTravel.go(client, d);
		boolean ok = said.startsWith("Going");
		say(said, ok ? OK : ERROR);
		if (ok) remember("Go to " + d.name(), new ItemStack(net.minecraft.item.Items.COMPASS), null, () -> SmartTravel.go(client, d));
		// Back to the game to watch it go, not to whatever screen opened this one.
		if (ok) client.setScreen(null);
	}

	private int placesTop = TOP + 44;
	/** How many places of each kind. */
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

	/** How far a place is from you, in your dimension's blocks (or MAX_VALUE in another one, like the end). */
	private double distance(Destinations.Destination d) {
		if (client.player == null || client.world == null) return Double.MAX_VALUE;
		String here = client.world.getRegistryKey().getValue().toString();
		boolean end = d.dimension().endsWith("the_end"), inNether = d.dimension().endsWith("the_nether");
		boolean hereNether = here.endsWith("the_nether"), hereEnd = here.endsWith("the_end");
		if (end != hereEnd) return Double.MAX_VALUE;
		int ox = inNether ? d.x() * 8 : d.x(), oz = inNether ? d.z() * 8 : d.z();
		int nx = inNether ? d.x() : Math.floorDiv(d.x(), 8), nz = inNether ? d.z() : Math.floorDiv(d.z(), 8);
		double dx = (end ? d.x() : hereNether ? nx : ox) - client.player.getX(), dz = (end ? d.z() : hereNether ? nz : oz) - client.player.getZ();
		return Math.hypot(dx, dz);
	}

	/** The list on Go: a card per place, with where it is in both dimensions, how far and which way, and its Go. */
	private void renderPlaces(DrawContext context, int mouseX, int mouseY) {
		int x = contentX(), w = contentW(), top = placesTop, bottom = pageBottom(), rowH = 34;
		String q = searchField == null ? "" : searchField.getText().strip().toLowerCase(Locale.ROOT);
		List<Destinations.Destination> all = Destinations.all(client);
		java.util.Arrays.fill(kindCounts, 0);
		for (Destinations.Destination d : all) {
			kindCounts[d.kind().ordinal()]++;
			kindCounts[kindCounts.length - 1]++;
		}
		all.removeIf(d -> kindFilter != null && d.kind() != kindFilter
				|| !q.isEmpty() && !d.name().toLowerCase(Locale.ROOT).contains(q) && !d.detail().toLowerCase(Locale.ROOT).contains(q));
		List<String> pinned = NNConfig.INSTANCE.pinnedPlaces;
		Comparator<Destinations.Destination> order = switch (NNConfig.INSTANCE.placesSort) {
			case "NEWEST" -> Comparator.comparingLong(d -> -d.time());
			case "NAME" -> Comparator.comparing(d -> d.name().toLowerCase(Locale.ROOT));
			default -> Comparator.comparingDouble(this::distance);
		};
		all.sort(Comparator.comparing((Destinations.Destination d) -> pinned.contains(d.name()) ? 0 : 1).thenComparing(order));
		if (all.isEmpty()) {
			boolean none = q.isEmpty() && kindFilter == null;
			roundedBorder(context, x, top, w, none ? 70 : 46, CARD, BORDER);
			drawIcon(context, new ItemStack(net.minecraft.item.Items.COMPASS), x + 12, top + 9);
			bold(context, none ? "No places yet" : "Nothing matches", x + 34, top + 10, TEXT);
			context.drawText(textRenderer, fit(none
					? "Save spots here; chunk trails, places players give away and where your list's players were seen show up by themselves."
					: "Try another word, or All.", w - 46), x + 34, top + 24, MUTED, false);
			if (none) {
				int bx2 = x + 34;
				handButton(context, mouseX, mouseY, bx2, top + 42, 120, 18, "Save where I am", Look.PRIMARY, () -> {
					if (waypointField != null) {
						setFocused(waypointField);
						waypointField.setFocused(true);
						say("Name this spot, then Enter", PLAIN);
					}
				}, "empty:save", top, top + 70, "Name the spot you're standing on, and keep it");
				handButton(context, mouseX, mouseY, bx2 + 126, top + 42, 140, 18, "Paste coordinates", Look.SOFT, () -> fillOptions().get(1).run().run(),
						"empty:paste", top, top + 70, "Fills the boxes above from your clipboard");
			}
			return;
		}
		int maxScroll = Math.max(0, all.size() * rowH - (bottom - top));
		listScroll = Math.max(0, Math.min(listScroll, maxScroll));
		context.enableScissor(x, top, x + w, bottom);
		int y = top - (int) listScroll;
		for (Destinations.Destination d : all) {
			if (y + rowH >= top && y <= bottom) drawPlace(context, mouseX, mouseY, d, x, y, w, rowH, top, bottom, pinned.contains(d.name()));
			y += rowH;
		}
		// More below: the last card fades out instead of being cut by the edge.
		if (listScroll < maxScroll) context.fillGradient(x, bottom - 14, x + w, bottom, PAGE_BG & 0x00FFFFFF, PAGE_BG);
		context.disableScissor();
		scrollbar(context, x + w + 4, top, bottom, all.size() * rowH, maxScroll);
	}

	private void drawPlace(DrawContext context, int mouseX, int mouseY, Destinations.Destination d, int x, int y, int w, int rowH, int top, int bottom, boolean pin) {
		int h = rowH - 4;
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + h, bottom) && overlay == null;
		roundedBorder(context, x, y, w, h, hover ? HOVER_BG : CARD, hover ? HOVER_BORDER : BORDER);
		int kc = ink(kindColor(d.kind()));
		context.fill(x + 1, y + 3, x + 4, y + h - 3, kc);
		boolean end = d.dimension().endsWith("the_end"), inNether = d.dimension().endsWith("the_nether");
		int ox = inNether ? d.x() * 8 : d.x(), oz = inNether ? d.z() * 8 : d.z();
		int nx = inNether ? d.x() : Math.floorDiv(d.x(), 8), nz = inNether ? d.z() : Math.floorDiv(d.z(), 8);
		boolean waypoint = d.kind() == Destinations.Kind.WAYPOINT;
		int buttons = 44 + 3 + 18;
		int right = x + w - 10 - (waypoint ? 16 : 0);
		int bx = right - buttons;
		int textRight = bx - 80;
		// The pin, its kind as a tag, then the name, then what it is.
		int px = x + 9;
		boolean overPin = mouseX >= px - 1 && mouseX < px + 9 && mouseY >= y + 4 && mouseY < y + 16 && overlay == null;
		context.drawText(textRenderer, pin ? "★" : "☆", px, y + 7, pin ? GOLD : overPin ? TEXT : DIM, false);
		hits.add(new Hit(px - 2, Math.max(y + 3, top), px + 10, Math.min(y + 17, bottom), () -> togglePin(d.name()), "pin:" + d.name(), false,
				pin ? "Unpin it from the top" : "Pin it to the top of the list"));
		String tag = d.kind().label;
		int tagW = textRenderer.getWidth(tag) + 8;
		int tagX = px + 13;
		roundedBorder(context, tagX, y + 5, tagW, 11, tint(kc, CARD, 0.18f), tint(kc, CARD, 0.5f));
		context.drawText(textRenderer, tag, tagX + 4, y + 7, kc, false);
		int nameX = tagX + tagW + 6;
		String name = fitBold(d.name(), Math.max(20, textRight - nameX));
		bold(context, name, nameX, y + 7, TEXT);
		int detailX = nameX + boldWidth(name) + 6;
		if (!d.detail().isEmpty() && detailX < textRight) context.drawText(textRenderer, fit(d.detail(), textRight - detailX), detailX, y + 7, MUTED, false);
		String coords = end ? String.format(Locale.ROOT, "End  %,d  %,d", d.x(), d.z())
				: String.format(Locale.ROOT, "Overworld  %,d  %,d      Nether  %,d  %,d", ox, oz, nx, nz);
		context.drawText(textRenderer, fit(coords, textRight - x - 10), px, y + 19, DIM, false);
		// How far, and which way, from where you are.
		if (client.player != null) {
			double dist = distance(d);
			String far, way;
			if (dist != Double.MAX_VALUE) {
				String here = client.world.getRegistryKey().getValue().toString();
				boolean hereNether = here.endsWith("the_nether");
				double dx = (end ? d.x() : hereNether ? nx : ox) - client.player.getX(), dz = (end ? d.z() : hereNether ? nz : oz) - client.player.getZ();
				far = String.format(Locale.ROOT, "%,d blocks", (int) dist);
				// Which way from where you're facing: an arrow, and in words.
				double target = Math.atan2(dx, -dz), facing = Math.toRadians(client.player.getYaw() + 180);
				double rel = Math.atan2(Math.sin(target - facing), Math.cos(target - facing));
				way = ChunkTrails.compass(dx, dz) + " · " + relative(rel);
				drawArrow(context, bx - 8 - Math.max(textRenderer.getWidth(far), textRenderer.getWidth(way)) - 12, y + 13.5, rel, 5, dist < 32 ? GOOD : LINK);
			} else {
				far = ShopScreen.dimensionName(d.dimension());
				way = "";
			}
			context.drawText(textRenderer, far, bx - 8 - textRenderer.getWidth(far), y + 7, TEXT, false);
			context.drawText(textRenderer, way, bx - 8 - textRenderer.getWidth(way), y + 19, MUTED, false);
		}
		// One Go (Smart Go works out the dimension and how), and ▾ for the rest.
		handButton(context, mouseX, mouseY, bx, y + 7, 44, 16, "Go", Look.PRIMARY, () -> go(d), d.name(), top, bottom, "Goes there: walks, or flies when it can");
		Destinations.Destination overworld = new Destinations.Destination(d.kind(), d.name(), d.detail(), ox, inNether ? Integer.MIN_VALUE : d.y(), oz,
				"minecraft:overworld", d.time());
		Destinations.Destination nether = new Destinations.Destination(d.kind(), d.name() + " (nether)", d.detail(), nx, inNether ? d.y() : Integer.MIN_VALUE, nz,
				"minecraft:the_nether", d.time());
		int mx = bx + 47;
		handButton(context, mouseX, mouseY, mx, y + 7, 18, 16, "▾", Look.SOFT, () -> {
			List<Option> o = new ArrayList<>();
			if (!end) {
				o.add(new Option(String.format(Locale.ROOT, "Go to its overworld spot (%,d, %,d)", ox, oz), new ItemStack(net.minecraft.item.Items.GRASS_BLOCK), null, () -> go(overworld), null));
				o.add(new Option(String.format(Locale.ROOT, "Go to its nether spot (%,d, %,d)", nx, nz), new ItemStack(net.minecraft.item.Items.NETHERRACK), null, () -> go(nether), null));
			}
			o.add(new Option("Set it as the goal (don't go yet)", new ItemStack(net.minecraft.item.Items.WHITE_BANNER), null, () -> run("goal " + d.x() + (d.hasY() ? " " + d.y() + " " : " ") + d.z(),
					"Goal set: " + d.name() + ". Press Resume or use Go to set off"), "Baritone shows its goal box there"));
			o.add(new Option("Copy coordinates", new ItemStack(net.minecraft.item.Items.PAPER), null, () -> {
				client.keyboard.setClipboard(d.hasY() ? d.x() + " " + d.y() + " " + d.z() : d.x() + " " + d.z());
				say("Copied " + d.coords(), OK);
			}, null));
			o.add(new Option(pin ? "Unpin" : "Pin to the top", new ItemStack(net.minecraft.item.Items.NETHER_STAR), null, () -> togglePin(d.name()), null));
			if (waypoint) o.add(new Option("Remove this spot", new ItemStack(net.minecraft.item.Items.BARRIER), null, () -> removeWaypoint(d.name()), null));
			openMenu(Math.max(contentX(), mx + 18 - 260), y + 25, 260, o);
		}, d.name() + " ▾", top, bottom, "More: either dimension's spot, set as goal, copy, pin");
		// Hidden: the nether's own Go, for the self-test and for keyboards.
		hits.add(new Hit(-100, -100, -99, -99, () -> go(nether), d.name() + " (nether)", false, null));
		if (waypoint) {
			int rx = x + w - 20;
			boolean sure = asking("remove:" + d.name());
			boolean over = mouseX >= rx && mouseX < rx + 14 && mouseY >= y + 6 && mouseY < y + 22 && overlay == null;
			if (sure) {
				String q2 = "Sure?";
				int qw = textRenderer.getWidth(q2) + 8;
				roundedBorder(context, x + w - 6 - qw, y + 7, qw, 16, DANGER_BG, DANGER_EDGE);
				context.drawText(textRenderer, q2, x + w - 2 - qw, y + 11, 0xFFFFFFFF, false);
			} else {
				context.drawText(textRenderer, "×", rx + 4, y + 10, over ? BAD : DIM, false);
			}
			hits.add(new Hit(sure ? x + w - 40 : rx, Math.max(y + 6, top), x + w - 2, Math.min(y + 22, bottom), () -> {
				if (confirmed("remove:" + d.name(), "Click again to remove \"" + d.name() + "\"")) removeWaypoint(d.name());
			}, d.name(), true, "Remove this saved spot"));
		}
	}

	/** "ahead", "ahead-left", "to your right", "behind"... for an angle from where you're facing. */
	private static String relative(double rel) {
		double d = Math.toDegrees(rel);
		double a = Math.abs(d);
		String side = d < 0 ? "left" : "right";
		if (a <= 22.5) return "ahead";
		if (a <= 67.5) return "ahead-" + side;
		if (a <= 112.5) return "to your " + side;
		if (a <= 157.5) return "behind-" + side;
		return "behind";
	}

	private void togglePin(String name) {
		List<String> pins = NNConfig.INSTANCE.pinnedPlaces;
		if (!pins.remove(name)) pins.add(name);
		NNConfig.save();
	}

	private void removeWaypoint(String name) {
		Destinations.remove(name);
		say("Removed \"" + name + "\"", PLAIN);
	}

	/** A button drawn by hand on a list row; {@code key} is what the self-test clicks it by. */
	private void handButton(DrawContext context, int mouseX, int mouseY, int gx, int gy, int gw, int gh, String label, Look look, Runnable run, String key,
			int top, int bottom, String hint) {
		boolean over = overlay == null && mouseX >= gx && mouseX < gx + gw && mouseY >= gy && mouseY < gy + gh && mouseY >= top && mouseY < bottom;
		int text = paintButton(context, gx, gy, gw, gh, look, ease("hand:" + key, over), true);
		if (label.equals("▾")) chevron(context, gx + gw / 2 - 2, gy + gh / 2 - 1, text);
		else context.drawText(textRenderer, label, gx + gw / 2 - textRenderer.getWidth(label) / 2, gy + (gh - 8) / 2, text, false);
		hits.add(new Hit(gx, Math.max(gy, top), gx + gw, Math.min(gy + gh, bottom), run, key, false, hint));
	}

	private void scrollbar(DrawContext context, int x, int top, int bottom, int total, double scroll, int maxScroll) {
		if (maxScroll <= 0) return;
		int bar = Math.max(14, (bottom - top) * (bottom - top) / Math.max(1, total));
		int by = top + (int) ((bottom - top - bar) * (scroll / maxScroll));
		context.fill(x, top, x + 3, bottom, dark ? 0x30FFFFFF : 0x18000000);
		context.fill(x, by, x + 3, by + bar, HOVER_BORDER);
	}

	private void scrollbar(DrawContext context, int x, int top, int bottom, int total, int maxScroll) {
		scrollbar(context, x, top, bottom, total, listScroll, maxScroll);
	}

	// ---- Picking from a list with pictures: blocks, items, mobs ----

	/** One group of what a list shows ("Ores", "Hostile"...), and what's in it. */
	private record Tab(String label, Predicate<BlockCatalog.Entry> in, Comparator<BlockCatalog.Entry> order) {}

	/** A searchable list of things with their icons, with groups to browse; Mine, Follow and the pickers use it. */
	private final class Browser {
		Field search;
		double scroll;
		List<BlockCatalog.Entry> source = List.of();
		List<Tab> tabs = List.of();
		/** Which tab, by index into {@link #tabs}. */
		Supplier<Integer> tab = () -> 0;
		Consumer<Integer> setTab = i -> {};
		/** Which are picked, by key (shown ticked). */
		Supplier<Set<String>> picked = Set::of;
		/** A click on a row. */
		Consumer<BlockCatalog.Entry> click = e -> {};
		/** A second button on a row ("Mine", "Follow"), or null. */
		String action;
		Consumer<BlockCatalog.Entry> act;
		/** What's written on the right of a row ("1,204 near"), or null. */
		java.util.function.Function<BlockCatalog.Entry, String> right;
		/** Show the ★ to favourite (blocks). */
		boolean stars;
		/** The list as last drawn, for the keyboard, and the row the arrows are on (Enter picks it). */
		List<BlockCatalog.Entry> shown = List.of();
		int cursor;

		void move(int by) {
			if (shown.isEmpty()) return;
			cursor = Math.max(0, Math.min(shown.size() - 1, cursor + by));
			// Keep it in view.
			double rowTop = cursor * 20.0;
			if (rowTop < scroll) scroll = rowTop;
			if (rowTop + 20 > scroll + lastHeight) scroll = rowTop + 20 - lastHeight;
		}

		void enter() {
			if (shown.isEmpty()) return;
			BlockCatalog.Entry e = shown.get(Math.max(0, Math.min(shown.size() - 1, cursor)));
			if (!e.blocks().isEmpty() && BlockCatalog.cantMine(e) != null) {
				say(BlockCatalog.cantMine(e), WARN);
				return;
			}
			click.accept(e);
		}

		int lastHeight = 100;

		List<BlockCatalog.Entry> filtered() {
			String q = search == null ? "" : search.getText();
			if (q != null && !q.isBlank()) return BlockCatalog.search(source, q, BlockCatalog.Group.ALL, Set.of());
			if (tabs.isEmpty()) return source;
			Tab t = tabs.get(Math.max(0, Math.min(tabs.size() - 1, tab.get())));
			List<BlockCatalog.Entry> out = new ArrayList<>();
			for (BlockCatalog.Entry e : source) if (t.in().test(e)) out.add(e);
			if (t.order() != null) out.sort(t.order());
			return out;
		}

		/** The group chips, wrapped to fit; returns where the list starts. */
		int drawTabs(DrawContext context, int mouseX, int mouseY, int x, int y, int w, List<Hit> into) {
			if (tabs.isEmpty()) return y;
			boolean searching = search != null && !search.getText().isBlank();
			int cx = x, cy = y;
			for (int i = 0; i < tabs.size(); i++) {
				Tab t = tabs.get(i);
				int cw = textRenderer.getWidth(t.label()) + 12;
				if (cx + cw > x + w) {
					cx = x;
					cy += 16;
				}
				boolean on = !searching && i == tab.get();
				boolean over = overlayFree(into) && mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + 14;
				roundedBorder(context, cx, cy, cw, 14, on ? PICKED_BG : over ? SOFT_HOVER : SOFT, on ? LINK : over ? HOVER_BORDER : BORDER);
				context.drawText(textRenderer, t.label(), cx + 6, cy + 3, on ? TEXT : searching ? DIM : MUTED, false);
				int index = i;
				into.add(new Hit(cx, cy, cx + cw, cy + 14, () -> {
					if (search != null) search.setText("");
					setTab.accept(index);
					scroll = 0;
				}, "tab:" + t.label(), false, searching ? "Clear the search to browse by group" : null));
				cx += cw + 3;
			}
			if (searching) {
				cy += 16;
				context.drawText(textRenderer, "Searching everything for \"" + fit(search.getText().strip(), w - 140) + "\"", x, cy + 3, DIM, false);
			}
			return cy + 18;
		}

		void drawList(DrawContext context, int mouseX, int mouseY, int x, int top, int w, int bottom, List<Hit> into) {
			int rowH = 20;
			List<BlockCatalog.Entry> list = filtered();
			if (!list.equals(shown)) cursor = Math.min(cursor, Math.max(0, list.size() - 1));
			shown = list;
			lastHeight = bottom - top;
			boolean keyed = search != null && search.isFocused();
			int index = 0;
			Set<String> picks = picked.get();
			int maxScroll = Math.max(0, list.size() * rowH - (bottom - top));
			scroll = Math.max(0, Math.min(scroll, maxScroll));
			if (list.isEmpty()) {
				context.drawText(textRenderer, search != null && !search.getText().isBlank() ? "Nothing by that name" : "Nothing here yet", x + 8, top + 6, MUTED, false);
				return;
			}
			context.enableScissor(x, top, x + w, bottom);
			int y = top - (int) scroll;
			for (BlockCatalog.Entry e : list) {
				if (y + rowH >= top && y <= bottom) {
					boolean on = picks.contains(e.key());
					boolean over = overlayFree(into) && mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + rowH - 2, bottom);
					boolean at = keyed && index == cursor;
					String cant = e.blocks().isEmpty() ? null : BlockCatalog.cantMine(e);
					float t = ease("row:" + e.key() + System.identityHashCode(this), over || at);
					roundedBorder(context, x, y, w, rowH - 2, on ? blend(PICKED_BG, PICKED_HOVER, t) : blend(CARD, HOVER_BG, t),
							on ? LINK : at ? ORANGE : blend(BORDER, HOVER_BORDER, t));
					drawIcon(context, e.icon(), x + 3, y + 1);
					int tx = x + (e.icon().isEmpty() ? 8 : 24);
					int rightEdge = x + w - 6;
					// The ★, the second button, and what's on the right, from the right edge in.
					if (stars) {
						boolean fav = NNConfig.INSTANCE.baritoneFavourites.contains(e.key());
						int sx = rightEdge - 10;
						boolean overStar = over && mouseX >= sx - 2 && mouseX < sx + 10;
						context.drawText(textRenderer, fav ? "★" : "☆", sx, y + 5, fav ? GOLD : overStar ? TEXT : DIM, false);
						into.add(new Hit(sx - 3, Math.max(y, top), sx + 10, Math.min(y + rowH - 2, bottom), () -> {
							List<String> favs = NNConfig.INSTANCE.baritoneFavourites;
							if (!favs.remove(e.key())) favs.add(0, e.key());
							NNConfig.save();
						}, "star:" + e.key(), false, fav ? "Take it off your favourites" : "Add it to your favourites (★ group)"));
						rightEdge = sx - 6;
					}
					if (action != null && cant == null) {
						// Shown when you point at the row (it's always there to click, where it shows).
						int aw = textRenderer.getWidth(action) + 12;
						int ax = rightEdge - aw;
						into.add(new Hit(ax, Math.max(y, top), ax + aw, Math.min(y + rowH - 2, bottom), () -> act.accept(e), "act:" + e.key(), false,
								"Mines just this, now"));
					}
					if (action != null && over && cant == null) {
						int aw = textRenderer.getWidth(action) + 12;
						int ax = rightEdge - aw;
						boolean overAct = mouseX >= ax && mouseX < ax + aw;
						roundedBorder(context, ax, y + 2, aw, rowH - 6, overAct ? YELLOW_HOVER : YELLOW, YELLOW_EDGE);
						context.drawText(textRenderer, action, ax + 6, y + 5, BUTTON_TEXT, false);
						rightEdge = ax - 6;
					} else if (right != null) {
						String r = right.apply(e);
						if (r != null && !r.isEmpty()) {
							context.drawText(textRenderer, r, rightEdge - textRenderer.getWidth(r), y + 5, r.startsWith("✔") ? LINK : GOOD, false);
							rightEdge -= textRenderer.getWidth(r) + 6;
						}
					}
					String name = e.name();
					int nameColor = cant != null ? DIM : TEXT;
					String shownName = fit((on ? "✔ " : "") + name, rightEdge - tx - (e.family() ? 50 : 0));
					context.drawText(textRenderer, shownName, tx, y + 5, nameColor, false);
					if (e.family()) {
						int fx = tx + textRenderer.getWidth(shownName) + 5;
						roundedBorder(context, fx, y + 3, 42, 11, tint(GOLD, CARD, 0.15f), tint(GOLD, CARD, 0.5f));
						context.drawText(textRenderer, "family", fx + 6, y + 5, GOLD, false);
					}
					into.add(new Hit(x, Math.max(y, top), x + w, Math.min(y + rowH - 2, bottom), () -> {
						if (cant != null) {
							say(cant, WARN);
							return;
						}
						click.accept(e);
					}, "pick:" + e.key(), false, cant != null ? cant : e.family() ? "All of these: " + String.join(", ", e.ids()) : e.key()));
				}
				y += rowH;
				index++;
			}
			if (scroll < maxScroll) context.fillGradient(x, bottom - 12, x + w, bottom, PAGE_BG & 0x00FFFFFF, PAGE_BG);
			context.disableScissor();
			scrollbar(context, x + w + 3, top, bottom, list.size() * rowH, scroll, maxScroll);
		}
	}

	/** Whether the page (not a menu over it) is what the mouse is on, for hit lists that belong to the page. */
	private boolean overlayFree(List<Hit> into) {
		return overlay == null || into != hits;
	}

	// ---- Mine ----

	private List<Tab> blockTabs() {
		List<Tab> t = new ArrayList<>();
		NNConfig c = NNConfig.INSTANCE;
		t.add(new Tab("★ Favourites", e -> c.baritoneFavourites.contains(e.key()), Comparator.comparing(e -> c.baritoneFavourites.indexOf(e.key()))));
		t.add(new Tab("Recent", e -> c.baritoneRecent.contains(e.key()), Comparator.comparing(e -> c.baritoneRecent.indexOf(e.key()))));
		t.add(new Tab("Near you", e -> e.near() > 0, Comparator.comparing((BlockCatalog.Entry e) -> -e.near()).thenComparing(BlockCatalog.Entry::name)));
		for (BlockCatalog.Group g : BlockCatalog.Group.values()) {
			if (g == BlockCatalog.Group.FAVOURITES || g == BlockCatalog.Group.RECENT || g == BlockCatalog.Group.NEAR) continue;
			t.add(new Tab(g.label, e -> BlockCatalog.in(e, g), g == BlockCatalog.Group.ORES
					? Comparator.comparing((BlockCatalog.Entry e) -> e.family() ? 0 : 1).thenComparing(BlockCatalog.Entry::name) : null));
		}
		return t;
	}

	private static String nearText(BlockCatalog.Entry e) {
		if (BlockCatalog.nearAt() == 0 || e.blocks().isEmpty()) return null;
		int n = e.near();
		if (n == 0) return null;
		return (n >= 10_000 ? String.format(Locale.ROOT, "%.0fk", n / 1000.0) : String.format(Locale.ROOT, "%,d", n)) + " near";
	}

	/** Where an ore is most common (1.18+): the best Y, and the range worth mining in. */
	private record BestY(String what, int y, int low, int high, boolean nether) {}

	private static BestY bestY(BlockCatalog.Entry e) {
		String k = e.family() ? e.key().substring(e.key().indexOf(':', 7) + 1) : e.key();
		k = k.replaceFirst("^deepslate_", "").replaceFirst("_ore$", "");
		return switch (k) {
			case "diamond" -> new BestY("Diamonds", -59, -64, -48, false);
			case "redstone" -> new BestY("Redstone", -59, -64, -32, false);
			case "lapis" -> new BestY("Lapis", 0, -32, 32, false);
			case "gold" -> new BestY("Gold", -16, -64, 32, false);
			case "iron" -> new BestY("Iron", 16, -24, 56, false);
			case "copper" -> new BestY("Copper", 48, 0, 96, false);
			case "coal" -> new BestY("Coal", 96, 0, 192, false);
			case "emerald" -> new BestY("Emeralds (in mountains)", 232, 100, 256, false);
			case "ancient_debris" -> new BestY("Ancient debris", 15, 8, 22, true);
			case "nether_quartz" -> new BestY("Nether quartz", 15, 10, 117, true);
			case "nether_gold" -> new BestY("Nether gold", 15, 10, 117, true);
			default -> null;
		};
	}

	private List<BlockCatalog.Entry> minePicks() {
		List<BlockCatalog.Entry> out = new ArrayList<>();
		for (String k : NNConfig.INSTANCE.mineBlocks) {
			BlockCatalog.Entry e = BlockCatalog.get(k);
			if (e != null) out.add(e);
		}
		return out;
	}

	private static void used(String key) {
		List<String> r = NNConfig.INSTANCE.baritoneRecent;
		r.remove(key);
		r.add(0, key);
		while (r.size() > 24) r.remove(r.size() - 1);
	}

	private void initMine(int x, int y) {
		BlockCatalog.countNear(client);
		if (!wide()) {
			initMineCompact(x, y);
			return;
		}
		NNConfig c = NNConfig.INSTANCE;
		int w = contentW();
		boolean two = wide();
		int leftW = two ? (w - 12) * 11 / 20 : w;
		int rx = two ? x + leftW + 12 : x, rw = two ? w - leftW - 12 : w;
		List<BlockCatalog.Entry> picks = minePicks();

		// The right: what's picked, how many, how, and Start.
		int cy = y;
		boolean active = MineJob.active();
		// The picks as chips with their icons, laid out here so what's under them knows where to go.
		int chipsY = cy + 24;
		int chipsH = picksHeight(picks, rw - 20);
		picksArea = new int[] {rx + 10, chipsY, rw - 20, chipsH};
		int ay = chipsY + chipsH + 8;
		// Where the first ore picked is most common, with a click to mine there.
		BestY best = picks.isEmpty() ? null : bestY(picks.get(0));
		if (best != null) {
			String line = best.what() + " are most common at Y " + best.y() + (best.nether() ? " (nether)" : "");
			label("§r" + line, rx + 10, ay + 4);
			int uw = textRenderer.getWidth("Mine there") + 14;
			pill(rx + rw - 10 - uw, ay, uw, 15, "Mine there", Look.SOFT, () -> {
				if (minYField != null) minYField.setText(String.valueOf(best.low()));
				if (maxYField != null) maxYField.setText(String.valueOf(best.high()));
				if (BaritoneBridge.bool("legitMine") != null) BaritoneBridge.set("legitMineYLevel", String.valueOf(best.y()));
				say("Y range set to " + best.low() + " to " + best.high() + " (legit mining digs at Y " + best.y() + ")", OK);
			}).hint("Sets the Y range to " + best.low() + "–" + best.high() + ", and legit mining's level to " + best.y());
			ay += 20;
		}
		label("How many", rx + 10, ay);
		int px = rx + 10;
		ay += 11;
		for (int n : new int[] {0, 16, 32, 64, 128, 256}) {
			String l = n == 0 ? "All" : String.valueOf(n);
			int pw = textRenderer.getWidth(l) + 14;
			pill(px, ay, pw, 16, l, c.mineAmount == n ? Look.PICKED : Look.SOFT, () -> {
				c.mineAmount = n;
				NNConfig.save();
				if (amountField != null) amountField.setText("");
				relayout();
			}).hint(n == 0 ? "Everything it can find" : "Stops once you've picked up " + n + " (of what they drop: diamonds, raw iron...)");
			px += pw + 3;
		}
		int afw = Math.max(46, rx + rw - 10 - px);
		boolean custom = c.mineAmount != 0 && !List.of(16, 32, 64, 128, 256).contains(c.mineAmount);
		amountField = field(px, ay - 1, afw, "other", amountField, custom ? String.valueOf(c.mineAmount) : "");
		amountField.setChangedListener(t -> {
			Integer n = whole(t);
			if (n != null && n > 0) {
				c.mineAmount = n;
				NNConfig.save();
			}
		});
		amountField.onEnter(this::startMining);
		ay += 24;
		int tw = rw - 20;
		Toggle t1 = baritoneToggle(rx + 10, ay, tw, "legitMine", "Only ores it can see (legit, no x-ray)");
		if (t1 != null) ay += 15;
		Toggle t2 = baritoneToggle(rx + 10, ay, tw, "mineScanDroppedItems", "Pick up what drops");
		if (t2 != null) ay += 15;
		Toggle t3 = baritoneToggle(rx + 10, ay, tw, "allowOnlyExposedOres", "Only ores next to air (no digging around)");
		if (t3 != null) ay += 15;
		Toggle t4 = baritoneToggle(rx + 10, ay, tw, "exploreForBlocks", "Explore when none are known yet");
		if (t4 != null) ay += 15;
		Integer minY = intSetting("minYLevelWhileMining"), maxY = intSetting("maxYLevelWhileMining");
		if (minY != null && maxY != null) {
			ay += 12;
			int hw = (tw - 6) / 2;
			minYField = labelledField(rx + 10, ay, hw, "Lowest Y (empty: any)", minYField, minY == 0 ? "" : String.valueOf(minY));
			maxYField = labelledField(rx + 10 + hw + 6, ay, tw - hw - 6, "Highest Y (empty: any)", maxYField, maxY >= 2031 ? "" : String.valueOf(maxY));
			ay += 22;
		}
		ay += 6;
		int bw = (tw - 6) * 2 / 3;
		pill(rx + 10, ay, bw, 22, active ? "Stop mining" : picks.isEmpty() ? "Pick blocks to mine" : "Start mining", active ? Look.DANGER : Look.PRIMARY, () -> {
			if (MineJob.active()) {
				MineJob.stopped();
				run("stop", "Stopped mining");
				relayout();
				return;
			}
			startMining();
		}).item(active ? ItemStack.EMPTY : new ItemStack(net.minecraft.item.Items.DIAMOND_PICKAXE)).icon(active ? "■" : null).hint(active ? "Stops mining" : "Mines every one it can reach, until you have the amount, or you stop it").active = active || !picks.isEmpty();
		pill(rx + 10 + bw + 6, ay, tw - bw - 6, 22, "Clear", Look.SOFT, () -> {
			c.mineBlocks.clear();
			NNConfig.save();
			relayout();
		}).hint("Takes everything off the list").active = !picks.isEmpty();
		ay += 30;
		mineProgressArea = new int[] {rx + 10, ay, tw, 0};
		int cardH = ay + (active || MineJob.endedWith() != null ? 40 : 4) - cy;
		card(rx, cy, rw, cardH, picks.isEmpty() ? "What to mine" : "Mining " + picks.size() + (picks.size() == 1 ? " kind" : " kinds"), active ? "going" : "click blocks on the " + (two ? "left" : "list"));

		// The left (or below): the picker.
		int ly = two ? y : cy + cardH + 10;
		Field before = mineBrowser.search;
		mineBrowser.search = field(x, ly, leftW, "Search every block: diamond, oak log, chest…", before, "");
		mineBrowser.search.setChangedListener(t -> {
			mineBrowser.scroll = 0;
			mineBrowser.cursor = 0;
		});
		mineBrowser.search.onEnter(mineBrowser::enter);
		setUpMineBrowser();
		browserArea = new int[] {x, ly + 22, leftW, pageBottom() - ly - 22};
	}

	/**
	 * Mine on a narrow screen: the block list fills the page, and a bar along the bottom holds what's picked, how many,
	 * Start and Clear, with the switches and the best height behind Options.
	 */
	private void initMineCompact(int x, int y) {
		NNConfig c = NNConfig.INSTANCE;
		int w = contentW();
		List<BlockCatalog.Entry> picks = minePicks();
		boolean active = MineJob.active();
		int barH = 66, by = pageBottom() - barH;
		card(x, by, w, barH, null, null);
		// Row one: what's picked (or how it's going), one line.
		picksArea = new int[] {x + 10, by + 7, w - 20, -1};
		if (active) mineProgressArea = new int[] {x + 10, by + 7, w - 20, -1};
		// Row two: how many, then Options, Clear, Start.
		int ry = by + 40;
		int px = x + 10;
		for (int n : new int[] {0, 16, 64, 256}) {
			String l = n == 0 ? "All" : String.valueOf(n);
			int pw = textRenderer.getWidth(l) + 12;
			pill(px, ry, pw, 18, l, c.mineAmount == n ? Look.PICKED : Look.SOFT, () -> {
				c.mineAmount = n;
				NNConfig.save();
				if (amountField != null) amountField.setText("");
				relayout();
			}).hint(n == 0 ? "Everything it can find" : "Stops once you've picked up " + n);
			px += pw + 3;
		}
		boolean custom = c.mineAmount != 0 && !List.of(16, 64, 256).contains(c.mineAmount);
		amountField = field(px, ry, 46, "other", amountField, custom ? String.valueOf(c.mineAmount) : "");
		amountField.setChangedListener(t -> {
			Integer n = whole(t);
			if (n != null && n > 0) {
				c.mineAmount = n;
				NNConfig.save();
			}
		});
		amountField.onEnter(this::startMining);
		int right = x + w - 10;
		int sw = 104;
		pill(right - sw, ry, sw, 18, active ? "Stop mining" : "Start mining", active ? Look.DANGER : Look.PRIMARY, () -> {
			if (MineJob.active()) {
				MineJob.stopped();
				run("stop", "Stopped mining");
				relayout();
				return;
			}
			startMining();
		}).item(active ? ItemStack.EMPTY : new ItemStack(net.minecraft.item.Items.DIAMOND_PICKAXE)).icon(active ? "■" : null).active = active || !picks.isEmpty();
		pill(right - sw - 4 - 44, ry, 44, 18, "Clear", Look.SOFT, () -> {
			c.mineBlocks.clear();
			NNConfig.save();
			relayout();
		}).active = !picks.isEmpty();
		int ow = textRenderer.getWidth("Options ▾") + 16;
		Pill opt = pill(right - sw - 4 - 44 - 4 - ow, ry, ow, 18, "Options ▾", Look.SOFT, () -> {});
		Pill optRef = opt;
		replaceRun(opt, () -> openMenu(optRef.getX(), optRef.getY() - 4, 260, mineOptions(picks)));
		// The list above it.
		Field before = mineBrowser.search;
		mineBrowser.search = field(x, y, w, "Search every block: diamond, oak log, chest…", before, "");
		mineBrowser.search.setChangedListener(t -> {
			mineBrowser.scroll = 0;
			mineBrowser.cursor = 0;
		});
		mineBrowser.search.onEnter(mineBrowser::enter);
		setUpMineBrowser();
		browserArea = new int[] {x, y + 22, w, by - 8 - y - 22};
	}

	/** Mine's switches and the best height, as a menu (on a narrow screen). */
	private List<Option> mineOptions(List<BlockCatalog.Entry> picks) {
		List<Option> o = new ArrayList<>();
		String[][] switches = {{"legitMine", "Only ores it can see (legit)"}, {"mineScanDroppedItems", "Pick up what drops"},
				{"allowOnlyExposedOres", "Only ores next to air"}, {"exploreForBlocks", "Explore when none are known"}};
		for (String[] sw : switches) {
			Boolean on = BaritoneBridge.bool(sw[0]);
			if (on == null) continue;
			o.add(new Option((on ? "On: " : "Off: ") + sw[1], on ? "✔" : "•", () -> {
				BaritoneBridge.setBool(sw[0], !on);
				BaritoneBridge.saveSettings();
				say(sw[1] + (on ? ": off" : ": on"), PLAIN);
			}, BaritoneSettingsGuide.description(sw[0])));
		}
		BestY best = picks.isEmpty() ? null : bestY(picks.get(0));
		if (best != null) {
			o.add(new Option("Mine where " + best.what().toLowerCase(Locale.ROOT) + " are common (Y " + best.low() + " to " + best.high() + ")", "⇕", () -> {
				BaritoneBridge.set("minYLevelWhileMining", String.valueOf(best.low()));
				BaritoneBridge.set("maxYLevelWhileMining", String.valueOf(best.high()));
				if (BaritoneBridge.bool("legitMine") != null) BaritoneBridge.set("legitMineYLevel", String.valueOf(best.y()));
				say("Y range set to " + best.low() + " to " + best.high(), OK);
			}, "Most common at Y " + best.y()));
		}
		o.add(new Option("Any height", "•", () -> {
			BaritoneBridge.set("minYLevelWhileMining", "0");
			BaritoneBridge.set("maxYLevelWhileMining", "2031");
			say("Mining at any height", OK);
		}, null));
		return o;
	}

	private void setUpMineBrowser() {
		mineBrowser.source = BlockCatalog.all();
		mineBrowser.tabs = blockTabs();
		mineBrowser.tab = () -> mineTab;
		mineBrowser.setTab = i -> mineTab = i;
		mineBrowser.picked = () -> new LinkedHashSet<>(NNConfig.INSTANCE.mineBlocks);
		mineBrowser.click = this::toggleMinePick;
		mineBrowser.action = "Mine now";
		mineBrowser.act = e -> {
			NNConfig.INSTANCE.mineBlocks.clear();
			NNConfig.INSTANCE.mineBlocks.add(e.key());
			NNConfig.save();
			startMining();
		};
		mineBrowser.right = BaritoneScreen::nearText;
		mineBrowser.stars = true;
	}

	private void toggleMinePick(BlockCatalog.Entry e) {
		List<String> list = NNConfig.INSTANCE.mineBlocks;
		if (!list.remove(e.key())) {
			list.add(e.key());
			used(e.key());
			say("Added " + e.name() + (e.family() ? " (" + String.join(", ", e.ids()) + ")" : ""), PLAIN);
		} else {
			say("Took " + e.name() + " off", PLAIN);
		}
		NNConfig.save();
		relayout();
	}

	/** How tall the picks' chips are, wrapped into {@code w}. */
	private int picksHeight(List<BlockCatalog.Entry> picks, int w) {
		if (picks.isEmpty()) return 30;
		int cx = 0, rows = 1;
		for (BlockCatalog.Entry e : picks) {
			int cw = chipWidth(e);
			if (cx + cw > w && cx > 0) {
				cx = 0;
				rows++;
			}
			cx += cw + 3;
		}
		return rows * 21 - 3;
	}

	private int chipWidth(BlockCatalog.Entry e) {
		return Math.min(180, 20 + textRenderer.getWidth(e.name()) + 16);
	}

	private void drawPicks(DrawContext context, int mouseX, int mouseY) {
		if (picksArea == null) return;
		int x = picksArea[0], y = picksArea[1], w = picksArea[2];
		List<BlockCatalog.Entry> picks = minePicks();
		if (picksArea[3] == -1) {
			// One line: "Mining:" and as many chips as fit, then "+3 more" (or how it's going, while it mines).
			if (mineProgressArea != null) return;
			bold(context, "Mine", x, y + 5, TEXT);
			int cx = x + boldWidth("Mine") + 8;
			if (picks.isEmpty()) {
				context.drawText(textRenderer, fit("Click blocks above to add them, or Mine now on one", x + w - cx), cx, y + 5, MUTED, false);
				return;
			}
			for (int i = 0; i < picks.size(); i++) {
				BlockCatalog.Entry e = picks.get(i);
				int cw = chipWidth(e);
				String more = "+" + (picks.size() - i) + " more";
				if (cx + cw > x + w - (i < picks.size() - 1 ? textRenderer.getWidth(more) + 6 : 0)) {
					context.drawText(textRenderer, more, cx, y + 5, MUTED, false);
					break;
				}
				boolean over = overlay == null && mouseX >= cx && mouseX < cx + cw && mouseY >= y && mouseY < y + 18;
				roundedBorder(context, cx, y, cw, 18, over ? HOVER_BG : PICKED_BG, over ? BAD : LINK);
				drawIcon(context, e.icon(), cx + 1, y + 1);
				context.drawText(textRenderer, fit(e.name(), cw - 36), cx + 19, y + 5, TEXT, false);
				context.drawText(textRenderer, "×", cx + cw - 10, y + 5, over ? BAD : MUTED, false);
				hits.add(new Hit(cx, y, cx + cw, y + 18, () -> toggleMinePick(e), "unpick:" + e.key(), true, "Take " + e.name() + " off"));
				cx += cw + 3;
			}
			return;
		}
		if (picks.isEmpty()) {
			context.drawText(textRenderer, "Nothing yet. Click blocks in the list to add them,", x, y + 2, MUTED, false);
			context.drawText(textRenderer, "or Mine now on one to mine just that.", x, y + 13, MUTED, false);
			return;
		}
		int cx = x, cy = y;
		for (BlockCatalog.Entry e : picks) {
			int cw = chipWidth(e);
			if (cx + cw > x + w && cx > x) {
				cx = x;
				cy += 21;
			}
			boolean over = overlay == null && mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + 18;
			roundedBorder(context, cx, cy, cw, 18, over ? HOVER_BG : PICKED_BG, over ? BAD : LINK);
			drawIcon(context, e.icon(), cx + 1, cy + 1);
			context.drawText(textRenderer, fit(e.name(), cw - 36), cx + 19, cy + 5, TEXT, false);
			context.drawText(textRenderer, "×", cx + cw - 10, cy + 5, over ? BAD : MUTED, false);
			hits.add(new Hit(cx, cy, cx + cw, cy + 18, () -> toggleMinePick(e), "unpick:" + e.key(), true, "Take " + e.name() + " off"));
			cx += cw + 3;
		}
	}

	private void drawMineProgress(DrawContext context) {
		if (mineProgressArea == null) return;
		int x = mineProgressArea[0], y = mineProgressArea[1], w = mineProgressArea[2];
		if (MineJob.active()) {
			long secs = (System.currentTimeMillis() - MineJob.startedAt()) / 1000;
			int got = MineJob.got(client);
			double perMin = got / Math.max(secs / 60.0, 0.25);
			String left = "";
			if (MineJob.amount() > 0 && got > 0 && got < MineJob.amount() && secs >= 20) {
				long eta = Math.round((MineJob.amount() - got) / perMin);
				left = eta < 1 ? " · under a minute left" : " · about " + eta + " min left";
			}
			String head = "Mining " + MineJob.what() + " · " + String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60)
					+ (MineJob.amount() > 0 ? " · got " + got + " of " + MineJob.amount() : got > 0 ? " · got " + got : "")
					+ (got > 0 && secs >= 20 ? String.format(Locale.ROOT, " · %.0f a minute", perMin) : "") + left;
			// A bar for how far towards the amount.
			if (MineJob.amount() > 0) {
				int bw = w;
				rounded(context, x, y + 30, bw, 4, SOFT);
				int fw = (int) (bw * Math.min(1.0, got / (double) MineJob.amount()));
				if (fw > 0) rounded(context, x, y + 30, Math.max(4, fw), 4, GOOD);
			}
			context.drawText(textRenderer, fit(head, w), x, y, GOOD, false);
			int ix = x, iy = y + 12;
			for (var e : MineJob.gained(client).entrySet()) {
				if (ix + 40 > x + w) break;
				drawIcon(context, new ItemStack(e.getKey()), ix, iy);
				context.drawText(textRenderer, "×" + e.getValue(), ix + 17, iy + 5, TEXT, false);
				ix += 22 + textRenderer.getWidth("×" + e.getValue());
			}
			if (MineJob.gained(client).isEmpty()) context.drawText(textRenderer, "Nothing picked up yet", ix, iy + 5, DIM, false);
		} else if (MineJob.endedWith() != null) {
			context.drawText(textRenderer, fit(MineJob.endedWith(), w), x, y, MUTED, false);
		}
	}

	private static Integer intSetting(String name) {
		for (BaritoneBridge.Setting s : BaritoneBridge.settings()) {
			if (s.name().equals(name)) {
				try {
					return Integer.parseInt(s.value());
				} catch (NumberFormatException e) {
					return null;
				}
			}
		}
		return null;
	}

	private void startMining() {
		List<BlockCatalog.Entry> picks = minePicks();
		if (picks.isEmpty()) {
			say("Pick something to mine first", WARN);
			return;
		}
		// The Y range, if one's typed (empty: any height).
		if (minYField != null && maxYField != null) {
			Integer lo = minYField.getText().isBlank() ? Integer.valueOf(0) : whole(minYField.getText());
			Integer hi = maxYField.getText().isBlank() ? Integer.valueOf(2031) : whole(maxYField.getText());
			if (lo == null || hi == null) {
				say("Type whole numbers for the Y range (or leave it empty)", ERROR);
				return;
			}
			BaritoneBridge.set("minYLevelWhileMining", String.valueOf(lo));
			BaritoneBridge.set("maxYLevelWhileMining", String.valueOf(hi));
		}
		BaritoneBridge.listen();
		replyMark = BaritoneBridge.replyCount();
		ranAt = System.currentTimeMillis();
		int amount = NNConfig.INSTANCE.mineAmount;
		String why = MineJob.start(client, picks, amount);
		if (why != null) {
			say(why, ERROR);
			return;
		}
		for (BlockCatalog.Entry e : picks) used(e.key());
		NNConfig.save();
		String what = MineJob.what();
		String said = "Mining " + what + (amount > 0 ? " until you have " + amount : "");
		say(said, OK);
		watchProcess = "getMineProcess";
		watchFailed = "Stopped mining " + what + " (see Baritone's message in chat)";
		List<BlockCatalog.Entry> again = List.copyOf(picks);
		remember("Mine " + what, picks.get(0).icon(), null, () -> MineJob.start(client, again, amount));
		relayout();
	}

	// ---- Follow ----

	private void initFollow(int x, int y) {
		int w = contentW();
		boolean two = wide();
		int leftW = two ? (w - 12) / 2 : w;
		int rx = two ? x + leftW + 12 : x, rw = two ? w - leftW - 12 : w;

		// Players: by name, everyone, or anything; then the list with faces.
		card(x, y, leftW, 72, "Follow a player", "Until you stop it");
		int fy = y + 26;
		playerField = field(x + 10, fy, leftW - 20 - 94, "player name", playerField, "").onEnter(this::followTyped);
		pill(x + leftW - 10 - 90, fy, 90, 18, "Follow them", Look.PRIMARY, this::followTyped).hint("Follows the player typed (Enter does too)");
		int half = (leftW - 20 - 4) / 2;
		pill(x + 10, fy + 24, half, 16, "Follow every player", Look.SOFT, () -> {
			if (runWatched("follow players", "Following every player in view", "getFollowProcess", "Stopped following (nobody in view)"))
				remember("Follow every player", new ItemStack(net.minecraft.item.Items.PLAYER_HEAD), null, () -> BaritoneBridge.run("follow players"));
		}).hint("Follows whoever is nearest of all players");
		pill(x + 10 + half + 4, fy + 24, leftW - 20 - half - 4, 16, "Follow anything", Look.SOFT, () -> {
			if (runWatched("follow entities", "Following anything that moves", "getFollowProcess", "Stopped following (nothing in view)"))
				remember("Follow anything", new ItemStack(net.minecraft.item.Items.LEAD), null, () -> BaritoneBridge.run("follow entities"));
		}).hint("Follows the nearest living thing, player or mob");
		int sy = y + 80;
		// How close: Baritone's own two settings, as steppers.
		Integer radius = intSetting("followRadius");
		if (radius != null) {
			label("How close", x, sy + 4);
			stepper(x + 60, sy, "followRadius", radius, 1, 0, 64, " blocks");
			String off = settingValue("followOffsetDistance");
			if (off != null) {
				label("Behind by", x + leftW / 2 + 4, sy + 4);
				double v = parseDouble(off);
				stepperDecimal(x + leftW / 2 + 64, sy, "followOffsetDistance", v, 1, 0, 32);
			}
			sy += 22;
		}
		playersArea = new int[] {x, sy, leftW, pageBottom() - sy};

		// Mobs and animals.
		int my = two ? y : pageBottom() + 10;
		if (!two) {
			// Narrow: players fill the page; mobs are under the "Mobs" chip instead.
			playersArea[3] = Math.max(60, (pageBottom() - sy) / 2 - 6);
			my = playersArea[1] + playersArea[3] + 10;
		}
		Field before = mobBrowser.search;
		mobBrowser.search = field(rx, my, rw, "Search mobs: cow, skeleton, villager…", before, "");
		mobBrowser.search.setChangedListener(t -> {
			mobBrowser.scroll = 0;
			mobBrowser.cursor = 0;
		});
		mobBrowser.search.onEnter(mobBrowser::enter);
		mobBrowser.source = mobEntries();
		mobBrowser.tabs = mobTabs();
		mobBrowser.tab = () -> mobTab;
		mobBrowser.setTab = i -> mobTab = i;
		mobBrowser.click = this::followMob;
		mobBrowser.right = e -> {
			int n = nearMobs().getOrDefault(e.key(), 0);
			return n == 0 ? null : n + " in view";
		};
		mobsArea = new int[] {rx, my + 22, rw, pageBottom() - my - 22};
	}

	private void followTyped() {
		String name = playerField.getText().strip();
		if (name.isEmpty()) {
			say("Type a player's name, or click one in the list", WARN);
			return;
		}
		if (runWatched("follow player " + name, "Following " + name, "getFollowProcess",
				"Stopped following " + name + " (out of sight, or see Baritone's message in chat)")) {
			remember("Follow " + name, new ItemStack(net.minecraft.item.Items.PLAYER_HEAD), null, () -> BaritoneBridge.run("follow player " + name));
		}
	}

	private void followPlayer(String name) {
		if (playerField != null) playerField.setText(name);
		followTyped();
	}

	private void followMob(BlockCatalog.Entry e) {
		String id = e.key().substring(4);
		if (runWatched("follow entity " + id, "Following the nearest " + e.name(), "getFollowProcess",
				"Stopped following " + e.name() + " (none in view, or see Baritone's message in chat)")) {
			remember("Follow " + e.name(), e.icon(), null, () -> BaritoneBridge.run("follow entity " + id));
		}
	}

	private static List<BlockCatalog.Entry> mobEntryList;

	private static List<BlockCatalog.Entry> mobEntries() {
		if (mobEntryList != null) return mobEntryList;
		List<BlockCatalog.Entry> out = new ArrayList<>();
		for (BlockCatalog.Mob m : BlockCatalog.mobs()) {
			out.add(new BlockCatalog.Entry("mob:" + m.id(), m.name(), m.icon(), List.of(), false));
		}
		mobEntryList = List.copyOf(out);
		return mobEntryList;
	}

	private static net.minecraft.entity.SpawnGroup mobGroup(BlockCatalog.Entry e) {
		String id = e.key().substring(4);
		for (BlockCatalog.Mob m : BlockCatalog.mobs()) if (m.id().equals(id)) return m.group();
		return net.minecraft.entity.SpawnGroup.MISC;
	}

	private List<Tab> mobTabs() {
		List<Tab> t = new ArrayList<>();
		t.add(new Tab("In view", e -> nearMobs().containsKey(e.key()), Comparator.comparing((BlockCatalog.Entry e) -> -nearMobs().getOrDefault(e.key(), 0))));
		t.add(new Tab("Hostile", e -> mobGroup(e) == net.minecraft.entity.SpawnGroup.MONSTER, null));
		t.add(new Tab("Animals", e -> mobGroup(e) == net.minecraft.entity.SpawnGroup.CREATURE, null));
		t.add(new Tab("Water", e -> mobGroup(e).getName().contains("water"), null));
		t.add(new Tab("Other", e -> {
			var g = mobGroup(e);
			return g == net.minecraft.entity.SpawnGroup.AMBIENT || g == net.minecraft.entity.SpawnGroup.MISC;
		}, null));
		t.add(new Tab("All", e -> true, null));
		return t;
	}

	private Map<String, Integer> nearMobsCache = Map.of();
	private long nearMobsAt;

	/** How many of each kind of mob are loaded around you, by "mob:id". */
	private Map<String, Integer> nearMobs() {
		long now = System.currentTimeMillis();
		if (now - nearMobsAt < 1000 || client.world == null) return nearMobsCache;
		nearMobsAt = now;
		Map<String, Integer> m = new HashMap<>();
		for (Entity e : client.world.getEntities()) {
			if (e instanceof PlayerEntity || !(e instanceof net.minecraft.entity.LivingEntity)) continue;
			Identifier id = Registries.ENTITY_TYPE.getId(e.getType());
			m.merge("mob:" + (id.getNamespace().equals("minecraft") ? id.getPath() : id.toString()), 1, Integer::sum);
		}
		nearMobsCache = m;
		return m;
	}

	private void drawPlayers(DrawContext context, int mouseX, int mouseY) {
		if (playersArea == null || client.player == null) return;
		int x = playersArea[0], top = playersArea[1], w = playersArea[2], bottom = top + playersArea[3], rowH = 26;
		// Everyone in view, nearest first, then everyone else online (they can't be followed until they're near).
		record Row(String name, UUID id, double dist, boolean inView) {}
		List<Row> rows = new ArrayList<>();
		Set<String> seen = new java.util.HashSet<>();
		if (client.world != null) {
			for (PlayerEntity o : client.world.getPlayers()) {
				if (o == client.player) continue;
				rows.add(new Row(o.getGameProfile().name(), o.getUuid(), Math.sqrt(o.squaredDistanceTo(client.player)), true));
				seen.add(o.getGameProfile().name().toLowerCase(Locale.ROOT));
			}
		}
		rows.sort(Comparator.comparingDouble(Row::dist));
		if (client.getNetworkHandler() != null) {
			List<Row> online = new ArrayList<>();
			for (PlayerListEntry e : client.getNetworkHandler().getPlayerList()) {
				String n = e.getProfile().name();
				if (n.equalsIgnoreCase(client.player.getGameProfile().name()) || seen.contains(n.toLowerCase(Locale.ROOT))) continue;
				online.add(new Row(n, e.getProfile().id(), Double.MAX_VALUE, false));
			}
			online.sort(Comparator.comparing(r -> r.name().toLowerCase(Locale.ROOT)));
			rows.addAll(online);
		}
		String q = playerField == null ? "" : playerField.getText().strip().toLowerCase(Locale.ROOT);
		if (!q.isEmpty()) rows.removeIf(r -> !r.name().toLowerCase(Locale.ROOT).contains(q));
		if (rows.isEmpty()) {
			roundedBorder(context, x, top, w, 40, CARD, BORDER);
			context.drawText(textRenderer, q.isEmpty() ? "Nobody else here" : "Nobody by that name online", x + 10, top + 8, TEXT, false);
			context.drawText(textRenderer, fit("Players in view show here with a Follow button; everyone online is listed too.", w - 20), x + 10, top + 22, MUTED, false);
			return;
		}
		int maxScroll = Math.max(0, rows.size() * rowH - (bottom - top));
		listScroll = Math.max(0, Math.min(listScroll, maxScroll));
		context.enableScissor(x, top, x + w, bottom);
		int y = top - (int) listScroll;
		Ledger ledger = NaughtyOrNiceClient.INSTANCE.ledger;
		for (Row r : rows) {
			if (y + rowH >= top && y <= bottom) {
				boolean over = overlay == null && mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + rowH - 3, bottom);
				roundedBorder(context, x, y, w, rowH - 3, over && r.inView() ? HOVER_BG : CARD, over && r.inView() ? HOVER_BORDER : BORDER);
				SkinTextures skin;
				PlayerListEntry entry = client.getNetworkHandler() == null ? null : client.getNetworkHandler().getPlayerListEntry(r.id());
				skin = entry != null ? entry.getSkinTextures() : DefaultSkinHelper.getSkinTextures(r.id());
				PlayerSkinDrawer.draw(context, skin, x + 5, y + 3, 16);
				Profile prof = ledger.byName(r.name());
				Profile.Verdict v = prof == null ? Profile.Verdict.NONE : prof.verdict;
				String mark = switch (v) {
					case FRIEND -> "★ ";
					case NICE -> "✔ ";
					case LOOK -> "? ";
					case NAUGHTY -> "✖ ";
					case NONE -> "";
				};
				int vc = switch (v) {
					case FRIEND -> prof != null ? 0xFF000000 | Friends.color(prof) : LINK;
					case NICE -> GOOD;
					case LOOK -> AMBER;
					case NAUGHTY -> BAD;
					case NONE -> r.inView() ? TEXT : MUTED;
				};
				context.drawText(textRenderer, mark + r.name(), x + 27, y + 4, vc, false);
				String sub = r.inView() ? String.format(Locale.ROOT, "%,d blocks away", (int) r.dist()) : "online, not in view";
				context.drawText(textRenderer, sub, x + 27, y + 13, DIM, false);
				if (r.inView()) {
					String name = r.name();
					handButton(context, mouseX, mouseY, x + w - 6 - 54, y + 3, 54, 17, "Follow", Look.PRIMARY, () -> followPlayer(name), "follow:" + name, top, bottom,
							"Follows " + name + " until you stop it");
					hits.add(new Hit(x, Math.max(y, top), x + w - 64, Math.min(y + rowH - 3, bottom), () -> followPlayer(name), "row:" + name, false,
							"Follows " + name));
				} else {
					hits.add(new Hit(x, Math.max(y, top), x + w, Math.min(y + rowH - 3, bottom), () -> say(r.name() + " isn't in view: Baritone can only follow players it can see", WARN),
							"row:" + r.name(), false, "Not in view: come closer to follow"));
				}
			}
			y += rowH;
		}
		context.disableScissor();
		scrollbar(context, x + w + 3, top, bottom, rows.size() * rowH, maxScroll);
	}

	// ---- Steppers for numbers ----

	private String settingValue(String name) {
		for (BaritoneBridge.Setting s : currentSettings()) if (s.name().equals(name)) return s.value();
		return null;
	}

	private static double parseDouble(String s) {
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** − value + for a whole-number Baritone setting. */
	private void stepper(int x, int y, String setting, int value, int step, int min, int max, String unit) {
		pill(x, y, 16, 16, "−", Look.SOFT, () -> {
			BaritoneBridge.set(setting, String.valueOf(Math.max(min, value - step)));
			settingsReadAt = 0;
			relayout();
		}).hint("Less: " + BaritoneSettingsGuide.description(setting));
		String v = value + unit;
		label("§f" + v, x + 20, y + 4);
		int vw = Math.max(textRenderer.getWidth(v) + 8, 30);
		pill(x + 20 + vw, y, 16, 16, "+", Look.SOFT, () -> {
			BaritoneBridge.set(setting, String.valueOf(Math.min(max, value + step)));
			settingsReadAt = 0;
			relayout();
		}).hint("More: " + BaritoneSettingsGuide.description(setting));
	}

	private void stepperDecimal(int x, int y, String setting, double value, double step, double min, double max) {
		pill(x, y, 16, 16, "−", Look.SOFT, () -> {
			BaritoneBridge.set(setting, trimNumber(Math.max(min, value - step)));
			settingsReadAt = 0;
			relayout();
		}).hint("Less: " + BaritoneSettingsGuide.description(setting));
		String v = trimNumber(value);
		label("§f" + v, x + 20, y + 4);
		int vw = Math.max(textRenderer.getWidth(v) + 8, 24);
		pill(x + 20 + vw, y, 16, 16, "+", Look.SOFT, () -> {
			BaritoneBridge.set(setting, trimNumber(Math.min(max, value + step)));
			settingsReadAt = 0;
			relayout();
		}).hint("More: " + BaritoneSettingsGuide.description(setting));
	}

	private static String trimNumber(double v) {
		String s = String.format(Locale.ROOT, "%.3f", v);
		s = s.replaceAll("0+$", "");
		if (s.endsWith(".")) s += "0";
		return s;
	}

	// ---- Roam: highways and old-chunk trails ----

	private int initRoam(int x, int y) {
		NNConfig c = NNConfig.INSTANCE;
		int w = contentW();
		boolean two = wide();
		int colW = two ? (w - 10) * 11 / 20 : w;
		int lx = x, rx = two ? x + colW + 10 : x, rw = two ? w - colW - 10 : w;

		// Where to: a distance along a highway, or coordinates; the highway picked on a compass.
		int whereH = c.roamToCoords ? 214 : 166;
		card(lx, y, colW, whereH, "Highway roam", "Where to");
		int segW = (colW - 20) / 2;
		pill(lx + 10, y + 22, segW, 16, "A distance along a highway", c.roamToCoords ? Look.SOFT : Look.PICKED, () -> {
			c.roamToCoords = false;
			if (HighwayRoam.axis() == null) HighwayRoam.pick(HighwayRoam.AXES[0]);
			NNConfig.save();
			relayout();
		});
		pill(lx + 10 + segW, y + 22, colW - 20 - segW, 16, "To coordinates", c.roamToCoords ? Look.PICKED : Look.SOFT, () -> {
			c.roamToCoords = true;
			NNConfig.save();
			relayout();
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
							relayout();
						}).hint("The quickest highway there, through spawn if need be");
					}
					continue;
				}
				HighwayRoam.Axis a = axis(name);
				pill(gx, gy, cell, ch, name, a == HighwayRoam.axis() ? Look.PRIMARY : Look.SOFT, () -> {
					HighwayRoam.pick(a);
					relayout();
				}).hint("The " + name + " highway").arrow = compassAngle(name);
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
				Integer d = whole(t);
				if (d == null || d < 100) return;
				c.roamDistance = d;
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
				relayout();
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
				relayout();
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
			relayout();
		});
		pill(compassX + hw2, hy, 3 * cell + 2 * gap - hw2, 16, "Elytra", c.roamElytra ? Look.PICKED : Look.SOFT, () -> {
			c.roamElytra = true;
			NNConfig.save();
			relayout();
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
		card(x, sy2, w, routeH, null, null);
		routeX = x + 10;
		routeY = sy2 + 8;
		routeW = w - 20 - 150;
		boolean active = HighwayRoam.active();
		pill(x + w - 10 - 140, sy2 + 13, 140, 20, active ? "Stop roaming" : "Start roaming", active ? Look.DANGER : Look.PRIMARY, () -> {
			if (HighwayRoam.active()) {
				HighwayRoam.stop("you stopped it");
				BaritoneBridge.run("stop");
				say("Stopped roaming", PLAIN);
			} else {
				String why = HighwayRoam.start(client);
				say(why == null ? "Roaming to " + HighwayRoam.target() : why, why == null ? OK : ERROR);
				if (why == null) remember("Roam to " + HighwayRoam.target(), new ItemStack(net.minecraft.item.Items.RAIL), null, () -> HighwayRoam.start(client));
			}
			relayout();
		});
		int fcy = sy2 + routeH + 10;

		// Following old chunks: along the path someone left through new land.
		card(x, fcy, w, 92, "Follow old chunks", "Keeps to the path someone left through new land");
		int fsy = fcy + 24;
		int half = (w - 20) / 2;
		int seg = (half - 6) / 2;
		pill(x + 10, fsy, seg, 16, "Away from spawn", c.trailFollowAway ? Look.PICKED : Look.SOFT, () -> {
			c.trailFollowAway = true;
			NNConfig.save();
			relayout();
		}).hint("Starts the way that leads away from spawn (towards a trail's far end)");
		pill(x + 10 + seg, fsy, half - 6 - seg, 16, "The way I'm facing", c.trailFollowAway ? Look.SOFT : Look.PICKED, () -> {
			c.trailFollowAway = false;
			NNConfig.save();
			relayout();
		});
		toggle(x + 10 + half + 4, fsy, half - 4, "Stop and ask in wide old areas (a base?)", () -> c.trailFollowStopWide, v -> c.trailFollowStopWide = v);
		toggle(x + 10 + half + 4, fsy + 16, half - 4, "Fly it by elytra (the nether)", () -> c.trailFollowElytra, v -> c.trailFollowElytra = v);
		boolean following = TrailFollow.active();
		pill(x + 10, fsy + 36, half - 6, 20, following ? "Stop following" : "Follow old chunks", following ? Look.DANGER : Look.PRIMARY, () -> {
			if (TrailFollow.active()) {
				TrailFollow.stop("you stopped it");
				say("Stopped following old chunks", PLAIN);
				relayout();
				return;
			}
			String why = TrailFollow.start(client, c.trailFollowAway);
			if (why != null) {
				say(why, ERROR);
				return;
			}
			say("Following old chunks, heading " + TrailFollow.heading(), OK);
			remember("Follow old chunks", new ItemStack(net.minecraft.item.Items.FILLED_MAP), null, () -> TrailFollow.start(client, NNConfig.INSTANCE.trailFollowAway));
			// Back to the game to watch it go.
			client.setScreen(null);
		});
		return fcy + 92;
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

	/** Which way a highway runs on the compass, clockwise from north (up). */
	private static double compassAngle(String name) {
		return switch (name) {
			case "-Z" -> 0;
			case "+X-Z" -> Math.PI / 4;
			case "+X" -> Math.PI / 2;
			case "+X+Z" -> 3 * Math.PI / 4;
			case "+Z" -> Math.PI;
			case "-X+Z" -> -3 * Math.PI / 4;
			case "-X" -> -Math.PI / 2;
			default -> -Math.PI / 4;
		};
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

	// ---- Tools ----

	/** The block "Go to a block" and the area's fill use, by key. */
	private static String toolBlock = "chest", fillBlock = "stone", replaceFrom = "stone", replaceTo = "air";

	private int initTools(int x, int y) {
		NNConfig c = NNConfig.INSTANCE;
		int w = contentW();
		boolean two = wide();
		int colW = two ? (w - 10) / 2 : w;
		int lx = x, rx = two ? x + colW + 10 : x;
		int inner = colW - 20;
		int ly = y, ry;

		// ---- Find a block ----
		card(lx, ly, colW, 74, "Go to a block", "The nearest one it knows of");
		BlockCatalog.Entry tb = BlockCatalog.get(toolBlock);
		int by = ly + 24;
		int pickW = inner - 70 - 6;
		pill(lx + 10, by, pickW, 18, tb == null ? "Pick a block…" : tb.name() + "  ▾", Look.SOFT, () -> openPicker("Go to which block?", BlockCatalog.all(), false,
				Set.of(toolBlock), picked -> {
					if (!picked.isEmpty()) toolBlock = picked.get(0).key();
				})).item(tb == null ? ItemStack.EMPTY : tb.icon()).hint("Choose the block to go to");
		pill(lx + 10 + pickW + 6, by, 70, 18, "Go", Look.PRIMARY, () -> gotoBlock(BlockCatalog.get(toolBlock))).hint("Walks to the nearest one");
		// Quick ones.
		int qx = lx + 10, qy = by + 24;
		for (String[] q : new String[][] {{"chest", "Chest"}, {"crafting_table", "Crafting table"}, {"ender_chest", "Ender chest"}, {"furnace", "Furnace"},
				{"spawner", "Spawner"}, {"end_portal_frame", "End portal"}}) {
			BlockCatalog.Entry e = BlockCatalog.get(q[0]);
			if (e == null) continue;
			int qw = textRenderer.getWidth(q[1]) + 28;
			if (qx + qw > lx + colW - 10) break;
			pill(qx, qy, qw, 18, q[1], Look.SOFT, () -> gotoBlock(e)).item(e.icon()).hint("Go to the nearest " + e.name().toLowerCase(Locale.ROOT));
			qx += qw + 3;
		}
		ly += 74 + 8;

		// ---- Get around ----
		card(lx, ly, colW, 96, "Get around", null);
		int gy = ly + 24;
		int half = (inner - 6) / 2;
		pill(lx + 10, gy, half, 18, "Up to the surface", Look.SOFT, () -> {
			if (run("surface", "Heading up to the surface")) remember("Up to the surface", new ItemStack(net.minecraft.item.Items.LADDER), null, () -> BaritoneBridge.run("surface"));
		}).item(new ItemStack(net.minecraft.item.Items.LADDER)).hint("Out of a cave or a mine, to the nearest open sky");
		pill(lx + 10 + half + 6, gy, inner - half - 6, 18, "Come to my camera", Look.SOFT, () -> run("come", "Coming to where your camera is"))
				.hint("For freecam: walks your player to where the camera is");
		gy += 24;
		int numW = 64;
		thisWayField = field(lx + 10, gy, numW, "blocks", thisWayField, String.valueOf(c.thisWayDistance));
		thisWayField.onEnter(this::goThisWay);
		pill(lx + 10 + numW + 6, gy, inner - numW - 6, 18, "Go this many blocks the way I'm facing", Look.SOFT, this::goThisWay).item(new ItemStack(net.minecraft.item.Items.COMPASS));
		gy += 24;
		gotoYField = field(lx + 10, gy, numW, "Y", gotoYField, "");
		gotoYField.onEnter(this::goToY);
		pill(lx + 10 + numW + 6, gy, inner - numW - 6, 18, "Go up or down to this Y level", Look.SOFT, this::goToY).item(new ItemStack(net.minecraft.item.Items.SCAFFOLDING))
				.hint("E.g. -58 for diamonds, 15 for ancient debris, 120 for the nether roof's height");
		ly += 96 + 8;

		// ---- Tunnel ----
		card(lx, ly, colW, 74, "Dig a tunnel", "The way you're facing");
		int ty = ly + 34;
		int tf = (inner - 2 * 6 - 6 - 110) / 3;
		tunnelHField = labelledField(lx + 10, ty, tf, "height", tunnelHField, String.valueOf(c.tunnelHeight));
		tunnelWField = labelledField(lx + 10 + tf + 6, ty, tf, "width", tunnelWField, String.valueOf(c.tunnelWidth));
		tunnelDField = labelledField(lx + 10 + 2 * (tf + 6), ty, tf, "length (empty: on)", tunnelDField, c.tunnelDepth == 0 ? "" : String.valueOf(c.tunnelDepth));
		pill(lx + colW - 10 - 110, ty - 1, 110, 20, "Dig tunnel", Look.PRIMARY, this::digTunnel).item(new ItemStack(net.minecraft.item.Items.IRON_PICKAXE))
				.hint("Digs straight ahead; a plain 1×2 tunnel goes on until you stop it");
		ly += 74 + 8;

		// ---- Explore, farm, pick up ----
		card(lx, ly, colW, 98, "Explore, farm, pick up", null);
		int ey = ly + 24;
		pill(lx + 10, ey, half, 18, "Explore from here", Look.SOFT, () -> {
			if (runWatched("explore", "Exploring from here", "getExploreProcess", "Stopped exploring (see Baritone's message in chat)"))
				remember("Explore", new ItemStack(net.minecraft.item.Items.MAP), null, () -> BaritoneBridge.run("explore"));
		}).hint("Walks out to land it hasn't loaded yet, in widening rings");
		int exW = (inner - half - 6 - 6 - 40) / 2;
		exploreXField = field(lx + 10 + half + 6, ey, exW, "x", exploreXField, "");
		exploreZField = field(lx + 10 + half + 6 + exW + 3, ey, exW, "z", exploreZField, "");
		pill(lx + colW - 10 - 40, ey, 40, 18, "From", Look.SOFT, () -> {
			Integer ex = whole(exploreXField.getText()), ez = whole(exploreZField.getText());
			if (ex == null || ez == null) {
				say("Type the x and z to explore out from", WARN);
				return;
			}
			runWatched("explore " + ex + " " + ez, "Exploring out from " + ex + ", " + ez, "getExploreProcess", "Stopped exploring");
		}).hint("Explores out from the typed x and z instead");
		ey += 24;
		farmField = field(lx + 10, ey, numW, "range", farmField, c.farmRange == 0 ? "" : String.valueOf(c.farmRange));
		farmField.setChangedListener(t -> {
			Integer n = whole(t);
			c.farmRange = n == null || n < 0 ? 0 : n;
			NNConfig.save();
		});
		pill(lx + 10 + numW + 6, ey, inner - numW - 6, 18, "Farm nearby", Look.SOFT, this::farm).item(new ItemStack(net.minecraft.item.Items.WHEAT))
				.hint("Harvests ripe crops and plants new ones (range: how far from here; empty: anywhere)");
		ey += 24;
		pill(lx + 10, ey, inner, 18, "Pick up dropped items nearby", Look.SOFT, () -> runWatched("pickup", "Picking up what's lying around", "getFollowProcess",
				"Done picking up")).item(new ItemStack(net.minecraft.item.Items.HOPPER));
		ly += 98 + 8;

		// ---- The right column: an area ----
		ry = two ? y : ly;
		int sels = BaritoneBridge.selections();
		card(rx, ry, colW, 160, "An area", sels == 0 ? "Mark two corners first" : sels + (sels == 1 ? " area marked" : " areas marked"));
		int ay = ry + 24;
		int third = (inner - 12) / 3;
		pill(rx + 10, ay, third, 18, "Corner 1 here", Look.SOFT, () -> {
			run("sel pos1", "Corner 1 is where you're standing");
			relayout();
		}).hint("Marks one corner of the area where you're standing");
		pill(rx + 10 + third + 6, ay, third, 18, "Corner 2 here", Look.SOFT, () -> {
			run("sel pos2", "Corner 2 set: the area is marked");
			relayout();
		}).hint("Marks the opposite corner: the area between is marked");
		pill(rx + 10 + 2 * (third + 6), ay, inner - 2 * (third + 6), 18, "Clear marks", Look.SOFT, () -> {
			run("sel clear", "Area marks cleared");
			relayout();
		}).active = sels > 0;
		ay += 24;
		BlockCatalog.Entry fb = BlockCatalog.get(fillBlock);
		int fbw = inner - 3 * 62 - 3 * 4;
		pill(rx + 10, ay, fbw, 18, fb == null ? "Block…" : fb.name() + " ▾", Look.SOFT, () -> openPicker("Fill with which block?", BlockCatalog.all(), false,
				Set.of(fillBlock), picked -> {
					if (!picked.isEmpty()) fillBlock = picked.get(0).key();
				})).item(fb == null ? ItemStack.EMPTY : fb.icon()).hint("The block to fill or wall the area with");
		int ax = rx + 10 + fbw + 4;
		for (String[] a : new String[][] {{"Fill", "set"}, {"Walls", "walls"}, {"Shell", "shell"}}) {
			pill(ax, ay, 62, 18, a[0], Look.SOFT, () -> areaWith(a[1], a[0])).hint(switch (a[1]) {
				case "set" -> "Fills the whole area with the block";
				case "walls" -> "Builds walls round the area's sides";
				default -> "Walls, floor and ceiling: a hollow box";
			}).active = sels > 0;
			ax += 66;
		}
		ay += 24;
		pill(rx + 10, ay, inner, 18, asking("clear-area") ? "Sure? Click again to dig it all out" : "Dig the whole area out (clear it)",
				asking("clear-area") ? Look.DANGER : Look.SOFT, () -> {
			if (!confirmed("clear-area", "Click again to break every block in the marked area")) return;
			if (run("sel cleararea", "Clearing the area")) remember("Clear the area", new ItemStack(net.minecraft.item.Items.IRON_SHOVEL), null, () -> BaritoneBridge.run("sel cleararea"));
		}).item(new ItemStack(net.minecraft.item.Items.IRON_SHOVEL)).hint("Breaks every block in the marked area").active = sels > 0;
		ay += 24;
		BlockCatalog.Entry rf = BlockCatalog.get(replaceFrom), rt = BlockCatalog.get(replaceTo);
		int rw2 = (inner - 6 - 30 - 6 - 60) / 2;
		pill(rx + 10, ay, rw2, 18, rf == null ? "Block…" : rf.name() + " ▾", Look.SOFT, () -> openPicker("Replace which block?", BlockCatalog.all(), false,
				Set.of(replaceFrom), picked -> {
					if (!picked.isEmpty()) replaceFrom = picked.get(0).key();
				})).item(rf == null ? ItemStack.EMPTY : rf.icon());
		label("with", rx + 10 + rw2 + 6, ay + 5);
		pill(rx + 10 + rw2 + 36, ay, rw2, 18, rt == null ? "Air (remove it) ▾" : rt.name() + " ▾", Look.SOFT, () -> openPicker("Replace it with which block?", BlockCatalog.all(), false,
				Set.of(replaceTo), picked -> {
					if (!picked.isEmpty()) replaceTo = picked.get(0).key();
				})).item(rt == null ? ItemStack.EMPTY : rt.icon());
		pill(rx + colW - 10 - 60, ay, 60, 18, "Replace", Look.SOFT, () -> {
			BlockCatalog.Entry from = BlockCatalog.get(replaceFrom), to = BlockCatalog.get(replaceTo);
			String fromIds = from == null ? replaceFrom : String.join(" ", from.ids());
			String toId = to == null ? "air" : to.ids().get(0);
			run("sel replace " + fromIds + " " + toId, "Replacing " + (from == null ? replaceFrom : from.name()) + " with " + (to == null ? "air" : to.name()));
		}).active = sels > 0;
		ay += 24;
		int h2 = (inner - 6) / 2;
		pill(rx + 10, ay, h2, 18, "Copy the area", Look.SOFT, () -> run("sel copy", "Copied the area (from where you stand)")).active = sels > 0;
		pill(rx + 10 + h2 + 6, ay, inner - h2 - 6, 18, "Paste it here", Look.SOFT, () -> run("sel paste", "Building the copy here"))
				.hint("Builds what you copied, placed the same way from where you stand");
		ry += 160 + 8;

		// ---- Build a schematic ----
		List<Path> schematics = schematicFiles();
		int listed = Math.min(5, schematics.size());
		int sh = 46 + Math.max(1, listed) * 20;
		card(rx, ry, colW, sh, "Build a schematic", "From your schematics folder");
		int sy = ry + 24;
		if (schematics.isEmpty()) {
			label("§7No schematics yet (.schem, .schematic or .litematic).", rx + 10, sy + 4);
			sy += 20;
		}
		for (int i = 0; i < listed; i++) {
			Path f = schematics.get(i);
			String file = f.getFileName().toString();
			label("§f" + fit(file, inner - 100), rx + 10, sy + 5);
			pill(rx + colW - 10 - 90, sy, 90, 18, "Build here", Look.SOFT, () -> {
				if (run("build " + file, "Building " + file + " where you stand")) remember("Build " + file, new ItemStack(net.minecraft.item.Items.BRICKS), null, () -> BaritoneBridge.run("build " + file));
			}).hint("Builds it with its corner where you're standing");
			sy += 20;
		}
		pill(rx + 10, sy + 2, inner, 16, "Open the schematics folder", Look.SOFT, () -> {
			Path dir = client.runDirectory.toPath().resolve("schematics");
			try {
				Files.createDirectories(dir);
			} catch (java.io.IOException e) {
				// Opened anyway.
			}
			Util.getOperatingSystem().open(dir.toFile());
		}).hint("Put schematic files in here, then come back");
		ry += sh + 8;

		// ---- World download, and unsticking ----
		boolean downloading = WorldDownload.active();
		card(rx, ry, colW, 74, "World download & more", downloading ? null : "Only when you say");
		pill(rx + 10, ry + 24, inner, 18, downloading ? "Stop downloading (" + WorldDownload.chunks() + " chunks saved)" : "Download the land I load into a singleplayer world",
				downloading ? Look.DANGER : Look.SOFT, () -> {
					if (WorldDownload.active()) {
						WorldDownload.stop(client);
					} else {
						String why = WorldDownload.start(client);
						say(why == null ? "Downloading: every chunk you load is saved" : why, why == null ? OK : ERROR);
					}
					relayout();
				});
		pill(rx + 10, ry + 48, half, 18, "Force stop (stuck?)", Look.SOFT, () -> {
			stopEverything();
			run("forcecancel", "Force-stopped everything");
		}).hint("When Stop doesn't take: cancels every job and path at once");
		pill(rx + 10 + half + 6, ry + 48, inner - half - 6, 18, "Clear what it gave up on", Look.SOFT, () -> run("blacklist clear", "Baritone will try those blocks again"))
				.hint("Baritone skips blocks it couldn't reach; this lets it try them again");
		ry += 74;
		return Math.max(ly - 8, ry);
	}

	private List<Path> schematicFiles() {
		Path dir = client.runDirectory.toPath().resolve("schematics");
		if (!Files.isDirectory(dir)) return List.of();
		try (var s = Files.list(dir)) {
			return s.filter(f -> {
				String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
				return n.endsWith(".schem") || n.endsWith(".schematic") || n.endsWith(".litematic");
			}).sorted(Comparator.comparingLong((Path f) -> -f.toFile().lastModified())).toList();
		} catch (java.io.IOException e) {
			return List.of();
		}
	}

	private void gotoBlock(BlockCatalog.Entry e) {
		if (e == null) {
			say("Pick a block first", WARN);
			return;
		}
		used(e.key());
		String ids = e.ids().get(0);
		if (run("goto " + ids, "Going to the nearest " + e.name())) {
			remember("Go to the nearest " + e.name(), e.icon(), null, () -> BaritoneBridge.run("goto " + ids));
		}
	}

	private void goThisWay() {
		Integer n = whole(thisWayField.getText());
		if (n == null || n <= 0) {
			say("Type how many blocks to go", WARN);
			return;
		}
		NNConfig.INSTANCE.thisWayDistance = n;
		NNConfig.save();
		if (run("thisway " + n, "Going " + n + " blocks the way you're facing")) run("path", "Going " + n + " blocks the way you're facing");
	}

	private void goToY() {
		Integer yv = whole(gotoYField.getText());
		if (yv == null) {
			say("Type the Y level to go to", WARN);
			return;
		}
		run("goto " + yv, "Going to Y " + yv);
	}

	private void digTunnel() {
		Integer th = whole(tunnelHField.getText()), tw = whole(tunnelWField.getText());
		Integer td = tunnelDField.getText().isBlank() ? Integer.valueOf(0) : whole(tunnelDField.getText());
		if (th == null || tw == null || td == null || th < 1 || tw < 1 || td < 0) {
			say("Type whole numbers for the tunnel's size", WARN);
			return;
		}
		NNConfig c = NNConfig.INSTANCE;
		c.tunnelHeight = th;
		c.tunnelWidth = tw;
		c.tunnelDepth = td;
		NNConfig.save();
		String cmd;
		if (td == 0) {
			if (th != 2 || tw != 1) {
				say("A tunnel that goes on is 2 high and 1 wide; give a length for other sizes", WARN);
				return;
			}
			cmd = "tunnel";
		} else {
			cmd = "tunnel " + th + " " + tw + " " + td;
		}
		String said = "Digging a " + tw + "×" + th + " tunnel" + (td > 0 ? ", " + td + " long" : "");
		if (run(cmd, said)) remember(said, new ItemStack(net.minecraft.item.Items.IRON_PICKAXE), null, () -> BaritoneBridge.run(cmd));
	}

	private void farm() {
		int r = NNConfig.INSTANCE.farmRange;
		String cmd = r > 0 ? "farm " + r : "farm";
		if (runWatched(cmd, "Farming nearby", "getFarmProcess", "Farming stopped: nothing (more) to harvest or plant nearby"))
			remember("Farm nearby", new ItemStack(net.minecraft.item.Items.WHEAT), null, () -> BaritoneBridge.run(cmd));
	}

	private void areaWith(String how, String label) {
		BlockCatalog.Entry e = BlockCatalog.get(fillBlock);
		if (e == null || e.ids().isEmpty()) {
			say("Pick a block first", WARN);
			return;
		}
		String cmd = "sel " + how + " " + e.ids().get(0);
		if (run(cmd, label + " with " + e.name())) remember(label + " the area with " + e.name(), e.icon(), null, () -> BaritoneBridge.run(cmd));
	}

	// ---- Settings ----

	/** One row of the settings list: Baritone's, or one of this mod's trip switches. */
	private record SettingRow(String key, String title, String raw, String about, BaritoneBridge.Setting setting, BooleanSupplier get, Consumer<Boolean> set) {}

	private List<SettingRow> tripRows() {
		NNConfig c = NNConfig.INSTANCE;
		List<SettingRow> r = new ArrayList<>();
		r.add(new SettingRow("trip:strangers", "On trips: pause when a stranger shows up", "Naughty or Nice",
				"Anyone who isn't a friend or on your Nice list: the trip waits and asks", null, () -> c.tripStopForStrangers, v -> c.tripStopForStrangers = v));
		r.add(new SettingRow("trip:highways", "Nether trips: ride the highways when quicker", "Naughty or Nice",
				"Takes the highways (through spawn if need be) when that beats going straight", null, () -> c.tripUseHighways, v -> c.tripUseHighways = v));
		r.add(new SettingRow("trip:bar", "Trip bar at the top", "Naughty or Nice", "Where to, how far, how long and how fast, while a trip is on", null,
				() -> c.tripBar, v -> c.tripBar = v));
		return r;
	}

	private void initSettings(int x, int y) {
		NNConfig c = NNConfig.INSTANCE;
		int w = contentW();
		// Low health, then the presets.
		int hearts = c.tripStopHealth / 2;
		String hpLabel = hearts == 0 ? "Stop at low health: off" : "Stop at " + hearts + " hearts";
		Pill hp = pill(x, y, textRenderer.getWidth("♥ " + hpLabel) + 18, hpLabel, Look.SOFT, () -> {
			// Off, 2, 3, 4, 5, 6 hearts, and round again.
			c.tripStopHealth = c.tripStopHealth == 0 ? 4 : c.tripStopHealth >= 12 ? 0 : c.tripStopHealth + 2;
			NNConfig.save();
			relayout();
		});
		hp.icon("♥").hint("On trips: stops and tells you when your health gets this low. Click to change");
		int px = x + hp.getWidth() + 14;
		boolean roomy = wide();
		if (!roomy) {
			// Narrow: the presets in a menu.
			Pill pm = fitPill(px, y, "Presets ▾", Look.SOFT, () -> {});
			Pill pmRef = pm;
			replaceRun(pm, () -> {
				List<Option> o = new ArrayList<>();
				for (BaritoneSettingsGuide.Preset p : BaritoneSettingsGuide.PRESETS) o.add(new Option(p.name(), "•", () -> applyPreset(p), p.why()));
				o.add(new Option("Undo all my changes", new ItemStack(net.minecraft.item.Items.BARRIER), null, this::resetAll, "Every setting back to Baritone's own"));
				openMenu(pmRef.getX(), pmRef.getY() + 18, 220, o);
			});
		}
		if (roomy) label("§8Presets", px, y + 5);
		px += textRenderer.getWidth("Presets") + 6;
		for (BaritoneSettingsGuide.Preset p : roomy ? BaritoneSettingsGuide.PRESETS : List.<BaritoneSettingsGuide.Preset>of()) {
			Pill b = fitPill(px, y, p.name(), Look.SOFT, () -> applyPreset(p));
			b.hint(p.why() + " Sets: " + String.join(", ", p.values().keySet()));
			px += b.getWidth() + 3;
		}
		if (roomy) fitPill(px, y, asking("undo-all") ? "Sure? Click again" : "Undo all my changes", asking("undo-all") ? Look.DANGER : Look.SOFT, () -> {
			if (confirmed("undo-all", "Click again to put every Baritone setting back to Baritone's own default")) resetAll();
		}).hint("Puts every Baritone setting back to Baritone's own default");
		int sy = y + 24;
		settingSearch = field(x, sy, Math.min(260, w / 2), "Search Baritone's settings (all of them)", settingSearch, "");
		settingSearch.setChangedListener(t -> listScroll = 0);
		// The groups, wrapped.
		int changed = 0;
		for (BaritoneBridge.Setting s : currentSettings()) if (s.changed()) changed++;
		int cx = x + Math.min(260, w / 2) + 8, cy = sy + 1;
		List<String[]> groups = new ArrayList<>();
		groups.add(new String[] {null, "All"});
		groups.add(new String[] {"changed", "Changed (" + changed + ")"});
		groups.add(new String[] {"trips", "Trips"});
		for (BaritoneSettingsGuide.Category cat : BaritoneSettingsGuide.Category.values()) groups.add(new String[] {cat.name(), cat.label});
		if (!roomy) {
			// Narrow: which group, as one button with a menu.
			String now = "All";
			for (String[] g : groups) if (java.util.Objects.equals(settingsGroup, g[0])) now = g[1];
			int gw = Math.min(170, x + w - cx);
			Pill gp = pill(cx, cy - 1, gw, 18, "Show: " + now + " ▾", Look.SOFT, () -> {});
			Pill gpRef = gp;
			replaceRun(gp, () -> {
				List<Option> o = new ArrayList<>();
				for (String[] g : groups) {
					boolean on = java.util.Objects.equals(settingsGroup, g[0]);
					o.add(new Option(g[1], on ? "✔" : "•", () -> {
						settingsGroup = g[0];
						listScroll = 0;
					}, null));
				}
				openMenu(gpRef.getX(), gpRef.getY() + 20, Math.max(gw, 200), o);
			});
			groups.clear();
		}
		for (String[] g : groups) {
			int gw = textRenderer.getWidth(g[1]) + 12;
			if (cx + gw > x + w) {
				cx = x;
				cy += 17;
			}
			boolean on = java.util.Objects.equals(settingsGroup, g[0]);
			Pill gp = pill(cx, cy, gw, 15, g[1], on ? Look.PICKED : Look.SOFT, () -> {
				settingsGroup = g[0];
				listScroll = 0;
				relayout();
			});
			gp.hint(g[0] == null ? "Every setting" : g[0].equals("changed") ? "Only the ones you've changed from Baritone's own" : "Only these");
			cx += gw + 3;
		}
		settingsTop = cy + 30;
		// A picked setting that's typed in: its box, Set, and Reset.
		BaritoneBridge.Setting picked = setting(pickedSetting);
		if (picked != null && !picked.bool()) {
			// Its box goes in its own row, moved there as the list is drawn.
			valueField = field(x + 10, -100, w - 20 - 150, picked.name(),
					valueField != null && picked.name().equals(valueField.getMessage().getString()) ? valueField : null, picked.value());
			valueField.setMaxLength(2000);
			valueField.onEnter(() -> setTyped(picked));
			// Drawn and framed in its row, not with the page's boxes.
			fields.remove(valueField);
			valueField.visible = true;
			valueField.active = true;
			setFocused(valueField);
			valueField.setFocused(true);
		} else {
			valueField = null;
		}
	}

	private int settingsTop = TOP + 70;

	private void setTyped(BaritoneBridge.Setting picked) {
		run("set " + picked.name() + " " + valueField.getText().strip(), BaritoneSettingsGuide.plainName(picked.name()) + " set to " + valueField.getText().strip());
		settingsReadAt = 0;
	}

	private void applyPreset(BaritoneSettingsGuide.Preset p) {
		int n = 0;
		for (var e : p.values().entrySet()) {
			if (settingValue(e.getKey()) == null) continue;
			if (e.getValue().equals("true") || e.getValue().equals("false")) BaritoneBridge.setBool(e.getKey(), Boolean.parseBoolean(e.getValue()));
			else BaritoneBridge.set(e.getKey(), e.getValue());
			n++;
		}
		BaritoneBridge.saveSettings();
		settingsReadAt = 0;
		say(p.name() + ": " + n + " settings set. " + p.why(), OK);
	}

	private void resetAll() {
		int n = 0;
		for (BaritoneBridge.Setting s : currentSettings()) {
			if (!s.changed()) continue;
			BaritoneBridge.reset(s.name());
			n++;
		}
		BaritoneBridge.saveSettings();
		settingsReadAt = 0;
		say(n == 0 ? "Nothing was changed from Baritone's defaults" : "Put " + n + " settings back to Baritone's defaults", n == 0 ? PLAIN : OK);
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

	/** The settings list: each in plain words with what it does, and the right control on the right. */
	private void renderSettings(DrawContext context, int mouseX, int mouseY) {
		int x = contentX(), w = contentW(), top = settingsTop;
		BaritoneBridge.Setting picked = setting(pickedSetting);
		int bottom = pageBottom();
		String q = settingSearch == null ? "" : settingSearch.getText().strip().toLowerCase(Locale.ROOT);
		List<SettingRow> rows = new ArrayList<>();
		int changed = 0;
		if (settingsGroup == null || settingsGroup.equals("trips")) {
			for (SettingRow r : tripRows()) if (q.isEmpty() || r.title().toLowerCase(Locale.ROOT).contains(q)) rows.add(r);
		}
		if (!"trips".equals(settingsGroup)) {
			for (BaritoneBridge.Setting s : currentSettings()) {
				if (s.changed()) changed++;
				String plain = BaritoneSettingsGuide.plainName(s.name());
				if (!q.isEmpty() && !s.name().toLowerCase(Locale.ROOT).contains(q) && !plain.toLowerCase(Locale.ROOT).contains(q)) continue;
				if ("changed".equals(settingsGroup) && !s.changed()) continue;
				if (settingsGroup != null && !settingsGroup.equals("changed") && BaritoneSettingsGuide.category(s.name()).name().equals(settingsGroup) == false) continue;
				rows.add(new SettingRow(s.name(), plain, s.name(), BaritoneSettingsGuide.description(s.name()), s, null, null));
			}
		}
		context.drawText(textRenderer, rows.size() + " shown · " + changed + " changed from Baritone's own · green: changed", x, top - 11, DIM, false);
		// The picked one is taller: its box to type in, Set and Reset, right in its row.
		int total = 0;
		for (SettingRow r : rows) total += rowHeight(r);
		int maxScroll = Math.max(0, total - (bottom - top));
		listScroll = Math.max(0, Math.min(listScroll, maxScroll));
		if (valueField != null) valueField.visible = false;
		context.enableScissor(x, top, x + w, bottom);
		int y = top - (int) listScroll;
		for (SettingRow r : rows) {
			int rh = rowHeight(r);
			if (y + rh >= top && y <= bottom) drawSettingRow(context, mouseX, mouseY, r, x, y, w, rh - 3, top, bottom);
			y += rh;
		}
		context.disableScissor();
		scrollbar(context, x + w + 4, top, bottom, total, maxScroll);
	}

	private int rowHeight(SettingRow r) {
		return r.setting() != null && !r.setting().bool() && r.setting().name().equals(pickedSetting) ? 54 : 30;
	}

	private void drawSettingRow(DrawContext context, int mouseX, int mouseY, SettingRow r, int x, int y, int w, int fullH, int top, int bottom) {
		BaritoneBridge.Setting s = r.setting();
		boolean picked = s != null && s.name().equals(pickedSetting);
		boolean editing = picked && !s.bool();
		int h = editing ? 27 : fullH;
		boolean over = overlay == null && mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + fullH, bottom);
		float t = ease("set:" + r.key(), over);
		roundedBorder(context, x, y, w, fullH, picked ? PICKED_BG : blend(CARD, HOVER_BG, t), picked ? LINK : blend(BORDER, HOVER_BORDER, t));
		if (editing && valueField != null) {
			// The box, where it belongs, and Set and Reset beside it.
			int fy = y + 31;
			boolean inView = fy - 4 >= top && fy + 14 <= bottom;
			valueField.setX(x + 15);
			valueField.setY(fy + 4);
			valueField.setWidth(w - 20 - 150 - 10);
			valueField.visible = inView;
			valueField.active = inView;
			if (inView) {
				roundedBorder(context, x + 10, fy, w - 20 - 150, 18, FIELD, valueField.isFocused() ? LINK : INPUT_BORDER);
				handButton(context, mouseX, mouseY, x + w - 10 - 140, fy, 68, 18, "Set", Look.PRIMARY, () -> setTyped(s), "set:" + s.name(), top, bottom,
						"Sets it to what's typed (Enter does too)");
				handButton(context, mouseX, mouseY, x + w - 10 - 68, fy, 68, 18, "Reset", Look.SOFT, () -> {
					BaritoneBridge.reset(s.name());
					BaritoneBridge.saveSettings();
					say(BaritoneSettingsGuide.plainName(s.name()) + " back to " + s.defaultValue(), PLAIN);
					settingsReadAt = 0;
					valueField.setText(s.defaultValue());
				}, "reset:" + s.name(), top, bottom, "Back to Baritone's default: " + s.defaultValue());
			}
		}
		int right = x + w - 10;
		Runnable click = null;
		String hint = r.about();
		if (s == null) {
			boolean on = r.get().getAsBoolean();
			drawSwitch(context, right - 20, y + (h - 10) / 2, on, over);
			right -= 28;
			click = () -> {
				r.set().accept(!r.get().getAsBoolean());
				NNConfig.save();
			};
		} else {
			switch (s.kind()) {
				case BOOL -> {
					boolean on = s.value().equals("true");
					drawSwitch(context, right - 20, y + (h - 10) / 2, on, over);
					right -= 28;
					click = () -> {
						boolean now = !s.value().equals("true");
						BaritoneBridge.setBool(s.name(), now);
						BaritoneBridge.saveSettings();
						say(r.title() + (now ? ": on" : ": off"), PLAIN);
						settingsReadAt = 0;
					};
				}
				case WHOLE, DECIMAL -> {
					// − value +, and a click on the value to type one.
					boolean whole = s.kind() == BaritoneBridge.Kind.WHOLE;
					double v = parseDouble(s.value());
					double step = whole ? stepFor(v) : Math.abs(v) <= 2 ? 0.1 : Math.abs(v) <= 20 ? 1 : 10;
					String vs = s.value();
					int vw = Math.max(36, textRenderer.getWidth(vs) + 10);
					int plusX = right - 16, valX = plusX - 3 - vw, minusX = valX - 3 - 16;
					int by = y + (h - 16) / 2;
					handButton(context, mouseX, mouseY, plusX, by, 16, 16, "+", Look.SOFT, () -> {
						BaritoneBridge.set(s.name(), whole ? String.valueOf((long) Math.round(v + step)) : trimNumber(v + step));
						settingsReadAt = 0;
					}, "plus:" + s.name(), top, bottom, "More");
					handButton(context, mouseX, mouseY, valX, by, vw, 16, vs, picked ? Look.PICKED : Look.SOFT, () -> pickSetting(s), "value:" + s.name(), top, bottom,
							"Type a value");
					handButton(context, mouseX, mouseY, minusX, by, 16, 16, "−", Look.SOFT, () -> {
						BaritoneBridge.set(s.name(), whole ? String.valueOf((long) Math.round(v - step)) : trimNumber(v - step));
						settingsReadAt = 0;
					}, "minus:" + s.name(), top, bottom, "Less");
					right = minusX - 8;
					click = () -> pickSetting(s);
				}
				case BLOCKS, ITEMS, BLOCK, ITEM -> {
					// Their icons, and Edit… to pick them.
					List<BlockCatalog.Entry> now = entriesOf(s);
					int ew = textRenderer.getWidth("Edit…") + 14;
					int ex = right - ew, by = y + (h - 16) / 2;
					boolean many = s.kind() == BaritoneBridge.Kind.BLOCKS || s.kind() == BaritoneBridge.Kind.ITEMS;
					Runnable edit = () -> editListSetting(s, many);
					handButton(context, mouseX, mouseY, ex, by, ew, 16, "Edit…", Look.SOFT, edit, "edit:" + s.name(), top, bottom, "Pick them from a list with pictures");
					int ix = ex - 6;
					int shownIcons = 0;
					for (int i = now.size() - 1; i >= 0 && shownIcons < 6; i--, shownIcons++) {
						ix -= 17;
						drawIcon(context, now.get(i).icon(), ix, by);
					}
					if (now.size() > 6) {
						String more = "+" + (now.size() - 6);
						ix -= textRenderer.getWidth(more) + 4;
						context.drawText(textRenderer, more, ix, by + 4, MUTED, false);
					}
					if (now.isEmpty()) {
						String none = "(none)";
						ix -= textRenderer.getWidth(none) + 2;
						context.drawText(textRenderer, none, ix, by + 4, DIM, false);
					}
					right = ix - 8;
					click = edit;
				}
				case COLOR -> {
					int[] rgb = rgb(s.value());
					int sx = right - 16, by = y + (h - 12) / 2;
					roundedBorder(context, sx, by, 16, 12, 0xFF000000 | rgb[0] << 16 | rgb[1] << 8 | rgb[2], BORDER);
					String vs = s.value();
					context.drawText(textRenderer, vs, sx - 6 - textRenderer.getWidth(vs), y + (h - 8) / 2, MUTED, false);
					right = sx - 12 - textRenderer.getWidth(vs);
					click = () -> pickSetting(s);
					hint = (hint.isEmpty() ? "" : hint + " · ") + "Type red,green,blue (0-255 each), e.g. 255,0,0 for red";
				}
				default -> {
					String v = fit(s.value().isEmpty() ? "(empty)" : s.value(), w / 3) + "  ›";
					context.drawText(textRenderer, v, right - textRenderer.getWidth(v), y + (h - 8) / 2, MUTED, false);
					right -= textRenderer.getWidth(v) + 8;
					click = () -> pickSetting(s);
				}
			}
		}
		boolean changed = s != null && s.changed();
		int nameColor = changed ? GOOD : TEXT;
		String title = fit(r.title(), right - x - 20);
		context.drawText(textRenderer, title, x + 10, y + 5, nameColor, false);
		int tx = x + 10 + textRenderer.getWidth(title) + 6;
		String raw = s == null ? r.raw() : changed ? r.raw() + " · default " + (s.bool() ? s.defaultValue().equals("true") ? "on" : "off" : fit(s.defaultValue(), 60)) : r.raw();
		if (tx < right - 20) context.drawText(textRenderer, fit(raw, right - tx), tx, y + 5, DIM, false);
		if (!r.about().isEmpty()) context.drawText(textRenderer, fit(r.about(), right - x - 20), x + 10, y + 16, MUTED, false);
		if (click != null) hits.add(new Hit(x, Math.max(y, top), x + w, Math.min(y + h, bottom), click, r.key(), false, hint));
	}

	private static double stepFor(double v) {
		double a = Math.abs(v);
		return a < 20 ? 1 : a < 200 ? 10 : a < 2000 ? 100 : 1000;
	}

	private static int[] rgb(String v) {
		try {
			String[] p = v.split(",");
			return new int[] {Integer.parseInt(p[0].strip()), Integer.parseInt(p[1].strip()), Integer.parseInt(p[2].strip())};
		} catch (RuntimeException e) {
			return new int[] {128, 128, 128};
		}
	}

	private void pickSetting(BaritoneBridge.Setting s) {
		// A second click on the one open closes it.
		pickedSetting = s.name().equals(pickedSetting) ? null : s.name();
		valueField = null;
		relayout();
	}

	/** The blocks or items a setting holds, as picker entries. */
	private List<BlockCatalog.Entry> entriesOf(BaritoneBridge.Setting s) {
		List<BlockCatalog.Entry> out = new ArrayList<>();
		if (s.kind() == BaritoneBridge.Kind.BLOCKS || s.kind() == BaritoneBridge.Kind.ITEMS) {
			for (Object o : BaritoneBridge.list(s.name())) {
				BlockCatalog.Entry e = o instanceof net.minecraft.block.Block b ? BlockCatalog.get(BlockCatalog.id(b)) : o instanceof Item i ? BlockCatalog.itemEntry(i) : null;
				if (e != null) out.add(e);
			}
		} else if (!s.value().isEmpty()) {
			BlockCatalog.Entry e = s.kind() == BaritoneBridge.Kind.BLOCK ? BlockCatalog.get(s.value()) : itemEntryFor(s.value());
			if (e != null) out.add(e);
		}
		return out;
	}

	private static BlockCatalog.Entry itemEntryFor(String id) {
		Identifier i = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		return i != null && Registries.ITEM.containsId(i) ? BlockCatalog.itemEntry(Registries.ITEM.get(i)) : null;
	}

	private void editListSetting(BaritoneBridge.Setting s, boolean many) {
		boolean items = s.kind() == BaritoneBridge.Kind.ITEMS || s.kind() == BaritoneBridge.Kind.ITEM;
		List<BlockCatalog.Entry> source = items ? BlockCatalog.items() : BlockCatalog.all().stream().filter(e -> !e.family()).toList();
		Set<String> now = new LinkedHashSet<>();
		for (BlockCatalog.Entry e : entriesOf(s)) now.add(e.key());
		openPicker(BaritoneSettingsGuide.plainName(s.name()), source, many, now, picked -> {
			if (many) {
				List<Object> values = new ArrayList<>();
				for (BlockCatalog.Entry e : picked) {
					if (items) {
						Item i = BlockCatalog.item(e);
						if (i != null) values.add(i);
					} else {
						values.addAll(e.blocks());
					}
				}
				boolean ok = BaritoneBridge.setList(s.name(), values);
				say(ok ? BaritoneSettingsGuide.plainName(s.name()) + ": " + picked.size() + " set" : "Baritone didn't take that", ok ? OK : ERROR);
			} else if (!picked.isEmpty()) {
				BlockCatalog.Entry e = picked.get(0);
				String v = items ? e.key().substring(5) : e.ids().get(0);
				run("set " + s.name() + " " + v, BaritoneSettingsGuide.plainName(s.name()) + " set to " + e.name());
			}
			settingsReadAt = 0;
		});
	}

	// ---- Commands ----

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
			Runnable runIt = () -> {
				String line = (picked.name() + " " + argsField.getText().strip()).strip();
				run(line, "Ran: " + line);
			};
			argsField.onEnter(runIt);
			pill(x + w - 10 - 112, by, 112, 18, "Run " + picked.name(), Look.PRIMARY, runIt);
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
		int top = TOP + 34, bottom = picked == null ? pageBottom() : height - STATUS_H - 118;
		String q = commandSearch == null ? "" : commandSearch.getText().strip().toLowerCase(Locale.ROOT);
		List<Object[]> rows = new ArrayList<>();
		for (BaritoneBridge.Command c : commands) {
			boolean match = q.isEmpty() || c.shortDesc().toLowerCase(Locale.ROOT).contains(q)
					|| c.names().stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).contains(q));
			if (match) rows.add(new Object[] {c});
		}
		context.drawText(textRenderer, rows.size() + " of Baritone's commands · click one · most are buttons on the other pages", x, top - 10, DIM, false);
		int rowH = 26;
		int maxScroll = Math.max(0, rows.size() * rowH - (bottom - top));
		listScroll = Math.max(0, Math.min(listScroll, maxScroll));
		context.enableScissor(x, top, x + w, bottom);
		int y = top - (int) listScroll;
		for (Object[] row : rows) {
			BaritoneBridge.Command c = (BaritoneBridge.Command) row[0];
			if (y + rowH >= top && y <= bottom) {
				int h = rowH - 3;
				boolean on = c.name().equals(pickedCommand);
				boolean hover = overlay == null && mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + h, bottom);
				roundedBorder(context, x, y, w, h, on ? PICKED_BG : hover ? HOVER_BG : CARD, on ? LINK : hover ? HOVER_BORDER : BORDER);
				bold(context, fit(c.name(), w - 20), x + 10, y + 4, TEXT);
				int mw = boldWidth(c.name());
				String also = c.names().size() > 1 ? "also " + String.join(", ", c.names().subList(1, c.names().size())) : "";
				if (!also.isEmpty()) context.drawText(textRenderer, fit(also, w - 30 - mw), x + 16 + mw, y + 4, DIM, false);
				context.drawText(textRenderer, fit(c.shortDesc(), w - 20), x + 10, y + 14, MUTED, false);
				hits.add(new Hit(x, Math.max(y, top), x + w, Math.min(y + h, bottom), () -> {
					pickedCommand = c.name();
					if (argsField != null) argsField.setText("");
					suggestedFor = null;
					relayout();
				}, c.name(), false, null));
			}
			y += rowH;
		}
		context.disableScissor();
		scrollbar(context, x + w + 4, top, bottom, rows.size() * rowH, maxScroll);
		if (picked == null) return;
		int by = bottom + 6;
		roundedBorder(context, x, by, w, height - STATUS_H - 4 - by, CARD, LINK);
		bold(context, picked.name(), x + 10, by + 7, TEXT);
		context.drawText(textRenderer, fit(picked.shortDesc(), w - 30 - textRenderer.getWidth(picked.name())), x + 18 + boldWidth(picked.name()), by + 7, MUTED, false);
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
			boolean hover = overlay == null && mouseX >= cx && mouseX < cx + sw && mouseY >= cy && mouseY < cy + 12;
			roundedBorder(context, cx, cy, sw, 12, hover ? PICKED_HOVER : SOFT, hover ? LINK : BORDER);
			context.drawText(textRenderer, s, cx + 5, cy + 2, TEXT, false);
			String pick = s;
			hits.add(new Hit(cx, cy, cx + sw, cy + 12, () -> addSuggestion(pick), "suggest:" + s, false, null));
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

	// ---- The search bar that does anything ----

	/** Something a menu offers: what it says, its icon (a picture or a sign), what it does, and a word about it. */
	private record Option(String label, ItemStack icon, String glyph, Runnable run, String hint) {
		Option(String label, String glyph, Runnable run, String hint) {
			this(label, ItemStack.EMPTY, glyph, run, hint);
		}
	}

	private int smartPick;
	private List<Option> smartCache = List.of();
	private String smartFor;

	/** What the search bar offers for what's typed, best first. */
	private List<Option> smartResults() {
		String q = smartField == null ? "" : smartField.getText().strip();
		if (q.equals(smartFor)) return smartCache;
		smartFor = q;
		List<Option> out = new ArrayList<>();
		String lq = q.toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			for (Recent r : recent) out.add(new Option("Again: " + r.label(), r.icon(), r.glyph() == null ? "•" : r.glyph(), () -> {
				r.run().run();
				say("Again: " + r.label(), OK);
			}, null));
			if (out.isEmpty()) out.add(new Option("Try: \"diamonds\", \"home\", \"1200 -3400\", a player's name, \"sprint\"", "?", () -> {}, null));
			smartCache = out;
			return out;
		}
		// Coordinates first: they can only mean one thing.
		List<ChatIntel.Coord> coords = ChatIntel.coords(q);
		if (coords.isEmpty() && q.matches("-?[\\d,]+k?\\s+-?[\\d,]+k?(\\s+-?[\\d,]+k?)?")) {
			String[] p = q.split("\\s+");
			Integer a = whole(p[0]), b = whole(p[1]), c3 = p.length > 2 ? whole(p[2]) : null;
			if (a != null && b != null) {
				int gx = a, gz = p.length > 2 && c3 != null ? c3 : b;
				Integer gy = p.length > 2 ? b : null;
				Destinations.Destination d = new Destinations.Destination(Destinations.Kind.CLIPBOARD, gx + ", " + (gy == null ? "" : gy + ", ") + gz, "Typed", gx,
						gy == null ? Integer.MIN_VALUE : gy, gz, "", 0);
				out.add(new Option("Go to " + d.name(), new ItemStack(net.minecraft.item.Items.COMPASS), null, () -> go(d), "Walks, or flies when it can"));
			}
		} else if (!coords.isEmpty()) {
			ChatIntel.Coord c = coords.get(0);
			Destinations.Destination d = new Destinations.Destination(Destinations.Kind.CLIPBOARD, c.x() + ", " + (c.hasY() ? c.y() + ", " : "") + c.z(), "Typed", c.x(),
					c.hasY() ? c.y() : Integer.MIN_VALUE, c.z(), c.dimension() == null ? "" : c.dimension(), 0);
			out.add(new Option("Go to " + d.name(), new ItemStack(net.minecraft.item.Items.COMPASS), null, () -> go(d), "Walks, or flies when it can"));
		}
		// Things to do, by their words.
		String[][] verbs = {{"stop", "Stop everything"}, {"pause", "Pause"}, {"resume", "Resume"}, {"explore", "Explore from here"}, {"farm", "Farm nearby"},
				{"surface", "Up to the surface"}, {"up", "Up to the surface"}, {"tunnel", "Dig a tunnel ahead"}, {"pickup", "Pick up dropped items"},
				{"pick up", "Pick up dropped items"}, {"come", "Come to my camera"}, {"download", "Download the world"}, {"old chunks", "Follow old chunks"},
				{"trail", "Follow old chunks"}, {"highway", "Highway roam"}, {"roam", "Highway roam"}, {"careful", "Preset: Careful"}, {"fast", "Preset: Fast"},
				{"legit", "Preset: Legit-looking"}, {"reset", "Undo all my setting changes"}};
		Set<String> addedVerbs = new LinkedHashSet<>();
		for (String[] v : verbs) {
			if ((v[0].startsWith(lq) && lq.length() >= 2 || lq.startsWith(v[0])) && addedVerbs.add(v[1])) {
				out.add(new Option(v[1], "•", verbAction(v[1]), null));
			}
		}
		// Places.
		int places = 0;
		for (Destinations.Destination d : Destinations.all(client)) {
			if (places >= 4) break;
			if (!d.name().toLowerCase(Locale.ROOT).contains(lq)) continue;
			out.add(new Option("Go to " + d.name(), new ItemStack(net.minecraft.item.Items.COMPASS), null, () -> go(d), d.kind().label + " · " + d.coords()));
			places++;
		}
		// Players in view.
		if (client.world != null) {
			int players = 0;
			for (PlayerEntity o : client.world.getPlayers()) {
				if (o == client.player || players >= 3) continue;
				String n = o.getGameProfile().name();
				if (!n.toLowerCase(Locale.ROOT).contains(lq)) continue;
				out.add(new Option("Follow " + n, new ItemStack(net.minecraft.item.Items.PLAYER_HEAD), null, () -> followPlayer(n), null));
				players++;
			}
		}
		// Blocks: mine them, or go to the nearest.
		List<BlockCatalog.Entry> blocks = BlockCatalog.search(lq, BlockCatalog.Group.ALL, Set.of());
		for (int i = 0; i < Math.min(3, blocks.size()); i++) {
			BlockCatalog.Entry e = blocks.get(i);
			out.add(new Option("Mine " + e.name(), e.icon(), null, () -> {
				NNConfig.INSTANCE.mineBlocks.clear();
				NNConfig.INSTANCE.mineBlocks.add(e.key());
				NNConfig.save();
				startMining();
			}, e.family() ? String.join(", ", e.ids()) : null));
			if (i == 0 && !e.family()) out.add(new Option("Go to the nearest " + e.name(), e.icon(), null, () -> gotoBlock(e), null));
		}
		// Mobs.
		List<BlockCatalog.Entry> mobs = BlockCatalog.search(mobEntries(), lq, BlockCatalog.Group.ALL, Set.of());
		for (int i = 0; i < Math.min(2, mobs.size()); i++) {
			BlockCatalog.Entry e = mobs.get(i);
			out.add(new Option("Follow the nearest " + e.name(), e.icon(), null, () -> followMob(e), null));
		}
		// Settings: switch the on/off ones right here.
		int found = 0;
		for (BaritoneBridge.Setting s : currentSettings()) {
			if (found >= 3) break;
			String plain = BaritoneSettingsGuide.plainName(s.name());
			if (!s.name().toLowerCase(Locale.ROOT).contains(lq) && !plain.toLowerCase(Locale.ROOT).contains(lq)) continue;
			found++;
			if (s.bool()) {
				boolean on = s.value().equals("true");
				out.add(new Option("Turn " + (on ? "off" : "on") + ": " + plain, "•", () -> {
					BaritoneBridge.setBool(s.name(), !on);
					BaritoneBridge.saveSettings();
					settingsReadAt = 0;
					say(plain + (on ? ": off" : ": on"), OK);
				}, s.name() + " is " + (on ? "on" : "off")));
			} else {
				out.add(new Option("Setting: " + plain + " (" + fit(s.value(), 60) + ")", "•", () -> {
					settingsGroup = null;
					page = Page.SETTINGS;
					listScroll = 0;
					pickedSetting = s.name();
					valueField = null;
					clearAndInit();
					if (settingSearch != null) settingSearch.setText(s.name());
				}, "Opens it to change"));
			}
		}
		// Pages.
		for (Page p : Page.values()) {
			if (p.label.toLowerCase(Locale.ROOT).startsWith(lq)) out.add(new Option("Open " + p.label, new ItemStack(net.minecraft.item.Items.BOOK), null, () -> showPage(p), pageHint(p)));
		}
		// Anything else: as a command, for those who know them.
		out.add(new Option("Run as a Baritone command: " + q, "#", () -> run(q, "Ran: " + q), "Exactly as typed, as if after # in chat (nothing goes to the server)"));
		if (out.size() > 12) out = new ArrayList<>(out.subList(0, 11));
		smartCache = out;
		return out;
	}

	private Runnable verbAction(String what) {
		return switch (what) {
			case "Stop everything" -> this::stopEverything;
			case "Pause" -> () -> run("pause", "Paused");
			case "Resume" -> () -> run("resume", "Going again");
			case "Explore from here" -> () -> runWatched("explore", "Exploring from here", "getExploreProcess", "Stopped exploring");
			case "Farm nearby" -> this::farm;
			case "Up to the surface" -> () -> run("surface", "Heading up to the surface");
			case "Dig a tunnel ahead" -> () -> run("tunnel", "Digging a 1×2 tunnel ahead");
			case "Pick up dropped items" -> () -> run("pickup", "Picking up what's lying around");
			case "Come to my camera" -> () -> run("come", "Coming to where your camera is");
			case "Download the world" -> () -> showPage(Page.TOOLS);
			case "Follow old chunks", "Highway roam" -> () -> showPage(Page.ROAM);
			case "Preset: Careful" -> () -> applyPreset(BaritoneSettingsGuide.PRESETS.get(0));
			case "Preset: Fast" -> () -> applyPreset(BaritoneSettingsGuide.PRESETS.get(1));
			case "Preset: Legit-looking" -> () -> applyPreset(BaritoneSettingsGuide.PRESETS.get(2));
			case "Undo all my setting changes" -> this::resetAll;
			default -> () -> {};
		};
	}

	private void runSmart() {
		List<Option> r = smartResults();
		if (r.isEmpty()) return;
		Option o = r.get(Math.max(0, Math.min(r.size() - 1, smartPick)));
		String typed = smartField.getText();
		smartField.setText("");
		smartFor = null;
		smartCache = List.of();
		setFocused(null);
		smartField.setFocused(false);
		if (o.label().startsWith("Try:")) {
			smartField.setText(typed);
			return;
		}
		o.run().run();
	}

	private boolean smartOpen() {
		return smartField != null && smartField.isFocused() && smartField.visible;
	}

	private void drawSmart(DrawContext context, int mouseX, int mouseY) {
		smartHits.clear();
		if (!smartOpen()) return;
		List<Option> r = smartResults();
		int x = smartBox[0], w = Math.max(smartBox[2], 320), y = smartBox[1] + 18;
		if (x + w > width - 6) x = width - 6 - w;
		int rowH = 18, h = r.size() * rowH + 8 + 12;
		context.getMatrices().pushMatrix();
		context.fill(x + 2, y + 3, x + w + 2, y + h + 3, SHADOW);
		roundedBorder(context, x, y, w, h, MENU_BG, ORANGE);
		context.drawText(textRenderer, smartField.getText().isBlank() ? "Lately" : "Enter does the first · ↑ ↓ to choose · Esc to close", x + 8, y + 5, DIM, false);
		int ry = y + 16;
		for (int i = 0; i < r.size(); i++) {
			Option o = r.get(i);
			boolean over = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowH;
			if (over) smartPick = i;
			boolean on = i == smartPick;
			if (on) roundedBorder(context, x + 3, ry, w - 6, rowH - 1, PICKED_BG, PICKED_BG);
			if (!o.icon().isEmpty()) drawIcon(context, o.icon(), x + 6, ry);
			else context.drawText(textRenderer, o.glyph() == null ? "•" : o.glyph(), x + 10, ry + 5, on ? LINK : MUTED, false);
			String label = fit(o.label(), w - 32);
			context.drawText(textRenderer, label, x + 26, ry + 5, on ? LINK : TEXT, false);
			if (o.hint() != null) {
				int hx = x + 26 + textRenderer.getWidth(label) + 8;
				if (hx < x + w - 40) context.drawText(textRenderer, fit(o.hint(), x + w - 8 - hx), hx, ry + 5, DIM, false);
			}
			int index = i;
			smartHits.add(new Hit(x, ry, x + w, ry + rowH, () -> {
				smartPick = index;
				runSmart();
			}, "smart:" + o.label(), false, null));
			ry += rowH;
		}
		context.getMatrices().popMatrix();
	}

	// ---- Menus and pickers over the page ----

	private abstract class Overlay {
		final List<Hit> overlayHits = new ArrayList<>();

		abstract void render(DrawContext context, int mouseX, int mouseY);

		/** A click; outside it closes it. */
		boolean click(double x, double y) {
			for (Hit h : List.copyOf(overlayHits)) {
				if (h.has(x, y)) {
					BaritoneScreen.this.click();
					h.run().run();
					return true;
				}
			}
			return false;
		}

		boolean key(KeyInput input) {
			return false;
		}

		void scroll(double amount) {}

		void closed() {}
	}

	private void openMenu(int x, int y, int w, List<Option> options) {
		overlay = new Menu(x, y, w, options);
	}

	/** A short list of choices under a button. */
	private final class Menu extends Overlay {
		final int x, y, w;
		final List<Option> options;

		Menu(int x, int y, int w, List<Option> options) {
			int h = options.size() * 18 + 6;
			this.w = w;
			this.x = Math.max(4, Math.min(x, BaritoneScreen.this.width - w - 4));
			this.y = y + h > BaritoneScreen.this.height - STATUS_H ? Math.max(4, y - h - 22) : y;
			this.options = options;
			// Clickable straight away (a quick click, or the keyboard, before it's drawn).
			layout(-1, -1, null);
		}

		/** Where each choice is (and, with a context, draws them). */
		void layout(int mouseX, int mouseY, DrawContext context) {
			overlayHits.clear();
			int ry = y + 3;
			for (Option o : options) {
				boolean over = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + 18;
				if (context != null) {
					if (over) roundedBorder(context, x + 3, ry, w - 6, 17, PICKED_BG, PICKED_BG);
					if (!o.icon().isEmpty()) drawIcon(context, o.icon(), x + 5, ry);
					else context.drawText(textRenderer, o.glyph() == null ? "•" : o.glyph(), x + 9, ry + 5, over ? LINK : MUTED, false);
					context.drawText(textRenderer, fit(o.label(), w - 32), x + 24, ry + 5, over ? LINK : TEXT, false);
				}
				overlayHits.add(new Hit(x, ry, x + w, ry + 18, () -> {
					overlay = null;
					o.run().run();
					if (BaritoneScreen.this.client.currentScreen == BaritoneScreen.this && overlay == null) relayout();
				}, "menu:" + o.label(), false, o.hint()));
				ry += 18;
			}
		}

		@Override
		void render(DrawContext context, int mouseX, int mouseY) {
			int h = options.size() * 18 + 6;
			context.fill(x + 2, y + 3, x + w + 2, y + h + 3, SHADOW);
			roundedBorder(context, x, y, w, h, MENU_BG, LINK);
			layout(mouseX, mouseY, context);
		}
	}

	/** Opens a picker over the page: pick one (closes on the click) or several (Done when finished). */
	private void openPicker(String title, List<BlockCatalog.Entry> source, boolean many, Set<String> now, Consumer<List<BlockCatalog.Entry>> done) {
		Picker p = new Picker(title, source, many, now, done);
		overlay = p;
		p.attach();
		setFocused(p.browser.search);
		p.browser.search.setFocused(true);
	}

	/** A block (or item) picker in a window over the page, with search, groups and pictures. */
	private final class Picker extends Overlay {
		final String title;
		final boolean many;
		final Set<String> picked;
		final Consumer<List<BlockCatalog.Entry>> done;
		final Browser browser = new Browser();
		int tabIndex;
		int px, py, pw, ph;

		Picker(String title, List<BlockCatalog.Entry> source, boolean many, Set<String> now, Consumer<List<BlockCatalog.Entry>> done) {
			this.title = title;
			this.many = many;
			this.picked = new LinkedHashSet<>(now);
			this.done = done;
			browser.source = source;
			boolean blocks = !source.isEmpty() && !source.get(0).blocks().isEmpty();
			browser.tabs = blocks ? blockTabs() : List.of();
			// Blocks open on Everything when nothing's picked; on Recent or Ores otherwise.
			tabIndex = blocks ? browser.tabs.size() - 1 : 0;
			browser.tab = () -> tabIndex;
			browser.setTab = i -> tabIndex = i;
			browser.picked = () -> picked;
			browser.right = BaritoneScreen::nearText;
			browser.click = e -> {
				if (many) {
					if (!picked.remove(e.key())) picked.add(e.key());
				} else {
					finish(List.of(e));
				}
			};
			browser.stars = blocks;
		}

		void attach() {
			pw = Math.min(460, BaritoneScreen.this.width - 30);
			ph = Math.min(360, BaritoneScreen.this.height - 40);
			px = (BaritoneScreen.this.width - pw) / 2;
			py = (BaritoneScreen.this.height - ph) / 2;
			Field old = browser.search;
			Field f = new Field(px + 15, py + 32, pw - 30, "Search by name…");
			f.setMaxLength(100);
			if (old != null) f.setText(old.getText());
			f.setChangedListener(t -> {
				browser.scroll = 0;
				browser.cursor = 0;
			});
			f.onEnter(browser::enter);
			browser.search = f;
			addSelectableChild(f);
			if (old != null && old.isFocused()) {
				setFocused(f);
				f.setFocused(true);
			}
		}

		void finish(List<BlockCatalog.Entry> result) {
			overlay = null;
			remove(browser.search);
			for (BlockCatalog.Entry e : result) if (!e.blocks().isEmpty()) used(e.key());
			NNConfig.save();
			done.accept(result);
			relayout();
		}

		@Override
		void closed() {
			remove(browser.search);
		}

		@Override
		void render(DrawContext context, int mouseX, int mouseY) {
			overlayHits.clear();
			context.fill(0, 0, BaritoneScreen.this.width, BaritoneScreen.this.height, 0xA0000000);
			roundedBorder(context, px, py, pw, ph, MENU_BG, LINK);
			bold(context, fit(title, pw - 140), px + 12, py + 10, TEXT);
			context.drawText(textRenderer, many ? picked.size() + " picked · click to add or take off" : "Click one", px + 12, py + 20, DIM, false);
			// The search box, drawn on top of the window.
			roundedBorder(context, px + 10, py + 28, pw - 20, 18, FIELD, browser.search.isFocused() ? LINK : INPUT_BORDER);
			browser.search.render(context, mouseX, mouseY, 0);
			int top = browser.drawTabs(context, mouseX, mouseY, px + 10, py + 52, pw - 20, overlayHits);
			int bottom = py + ph - (many ? 34 : 10);
			browser.drawList(context, mouseX, mouseY, px + 10, top, pw - 24, bottom, overlayHits);
			// Close, and Done for several.
			int cx = px + pw - 10 - 60;
			handButtonOn(context, mouseX, mouseY, cx, py + 8, 60, 16, many ? "Cancel" : "Close", Look.SOFT, () -> {
				overlay = null;
				remove(browser.search);
			}, overlayHits);
			if (many) {
				handButtonOn(context, mouseX, mouseY, px + pw - 10 - 100, py + ph - 26, 100, 18, "Done", Look.PRIMARY, () -> {
					List<BlockCatalog.Entry> out = new ArrayList<>();
					for (BlockCatalog.Entry e : browser.source) if (picked.contains(e.key())) out.add(e);
					finish(out);
				}, overlayHits);
				handButtonOn(context, mouseX, mouseY, px + 10, py + ph - 26, 90, 18, "Clear all", Look.SOFT, picked::clear, overlayHits);
			}
		}

		@Override
		boolean click(double x, double y) {
			if (super.click(x, y)) return true;
			if (x >= px && x < px + pw && y >= py && y < py + ph) {
				// In the window but on nothing: the search box takes it.
				if (browser.search.isMouseOver(x, y)) {
					setFocused(browser.search);
					browser.search.setFocused(true);
				}
				return true;
			}
			return false;
		}

		@Override
		void scroll(double amount) {
			browser.scroll -= amount * 22;
		}
	}

	private void handButtonOn(DrawContext context, int mouseX, int mouseY, int gx, int gy, int gw, int gh, String label, Look look, Runnable run, List<Hit> into) {
		boolean over = mouseX >= gx && mouseX < gx + gw && mouseY >= gy && mouseY < gy + gh;
		int text = paintButton(context, gx, gy, gw, gh, look, ease("on:" + label + gx, over), true);
		context.drawText(textRenderer, label, gx + gw / 2 - textRenderer.getWidth(label) / 2, gy + (gh - 8) / 2, text, false);
		into.add(new Hit(gx, gy, gx + gw, gy + gh, run, label, false, null));
	}

	// ---- Drawing ----

	/** Hover and picking, eased (0 to 1) per thing, so they fade instead of snapping. */
	private final Map<String, Float> anim = new HashMap<>();
	private long lastAnimNanos;
	private float animStep = 1;

	private float ease(String key, boolean on) {
		float v = anim.getOrDefault(key, on ? 1f : 0f);
		v += ((on ? 1f : 0f) - v) * animStep;
		if (Math.abs(v - (on ? 1f : 0f)) < 0.01f) v = on ? 1f : 0f;
		anim.put(key, v);
		return v;
	}

	private static int blend(int from, int to, float t) {
		return t <= 0 ? from : t >= 1 ? to : tint(to, from, t);
	}

	/** A button's face in its look, {@code t} of the way to hovered; returns the colour for its label. */
	private int paintButton(DrawContext context, int x, int y, int w, int h, Look look, float t, boolean active) {
		int fill, edge, text;
		switch (look) {
			case PRIMARY -> {
				fill = active ? blend(YELLOW, YELLOW_HOVER, t) : tint(YELLOW, PAGE_BG, 0.45f);
				edge = active ? YELLOW_EDGE : tint(YELLOW_EDGE, PAGE_BG, 0.45f);
				text = active ? BUTTON_TEXT : tint(BUTTON_TEXT, PAGE_BG, 0.5f);
			}
			case PICKED -> {
				fill = blend(PICKED_BG, PICKED_HOVER, t);
				edge = LINK;
				text = TEXT;
			}
			case DANGER, NETHER -> {
				fill = blend(DANGER_BG, DANGER_HOVER, t);
				edge = DANGER_EDGE;
				text = 0xFFFFFFFF;
			}
			case OVERWORLD -> {
				fill = blend(0xFF2E6BBF, 0xFF4CA0FF, t);
				edge = 0xFF3F80D6;
				text = 0xFFFFFFFF;
			}
			case NAV -> {
				fill = active ? blend(NAV_SOFT, NAV_SOFT_HOVER, t) : tint(NAV_SOFT, NAV, 0.5f);
				edge = active ? blend(NAV_BORDER, 0xFF55657A, t) : tint(NAV_BORDER, NAV, 0.5f);
				text = active ? NAV_TEXT : NAV_DIM;
			}
			default -> {
				fill = !active ? tint(SOFT, PAGE_BG, 0.5f) : blend(SOFT, SOFT_HOVER, t);
				edge = !active ? tint(BORDER, PAGE_BG, 0.5f) : blend(BORDER, HOVER_BORDER, t);
				text = active ? TEXT : DIM;
			}
		}
		roundedBorder(context, x, y, w, h, fill, edge);
		// A soft lift on the main action.
		if (look == Look.PRIMARY && active) context.fill(x + 2, y + 1, x + w - 2, y + 2, 0x30FFFFFF);
		return text;
	}

	/** An arrow pointing {@code angle} radians clockwise from up, centred at (cx, cy), {@code r} long each way. */
	private static void drawArrow(DrawContext context, double cx, double cy, double angle, int r, int color) {
		double dx = Math.sin(angle), dy = -Math.cos(angle);
		// The shaft, back to front, as little squares.
		for (double d = -r; d <= r; d += 0.5) dot(context, cx + dx * d, cy + dy * d, color);
		// The head: two strokes back from the tip.
		for (double side : new double[] {-1, 1}) {
			double a = angle + Math.PI + side * 0.6;
			double hx = Math.sin(a), hy = -Math.cos(a);
			for (double d = 0; d <= r * 0.75; d += 0.5) dot(context, cx + dx * r + hx * d, cy + dy * r + hy * d, color);
		}
	}

	private static void dot(DrawContext context, double x, double y, int color) {
		int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
		context.fill(ix, iy, ix + 1, iy + 1, color);
	}

	/** The tooltip waiting by the mouse: what it says, since when the mouse has been on it. */
	private String tipText;
	private long tipSince;

	private void drawTooltip(DrawContext context, int mouseX, int mouseY) {
		String hint = hoverHint;
		if (hint == null || hint.isBlank()) {
			tipText = null;
			return;
		}
		if (!hint.equals(tipText)) {
			tipText = hint;
			tipSince = System.currentTimeMillis();
		}
		long shown = System.currentTimeMillis() - tipSince;
		if (shown < 420) return;
		int a = (int) Math.min(255, (shown - 420) * 255 / 120);
		var lines = textRenderer.wrapLines(Text.literal(hint), 230);
		int w = 0;
		for (var l : lines) w = Math.max(w, textRenderer.getWidth(l));
		int h = lines.size() * 10 + 6;
		int x = mouseX + 10, y = mouseY + 14;
		if (x + w + 10 > width - 4) x = Math.max(4, mouseX - w - 14);
		if (y + h > height - STATUS_H - 2) y = Math.max(4, mouseY - h - 6);
		context.fill(x + 1, y + 2, x + w + 11, y + h + 2, (a * 0x50 / 255) << 24);
		roundedBorder(context, x, y, w + 10, h, alphaOf(MENU_BG, a), alphaOf(dark ? 0xFF4A5663 : 0xFFB8BFC4, a));
		int ly = y + 4;
		for (var l : lines) {
			context.drawText(textRenderer, l, x + 5, ly, alphaOf(TEXT, a), false);
			ly += 10;
		}
	}

	private static int alphaOf(int color, int a) {
		return Math.max(0, Math.min(255, a)) << 24 | (color & 0xFFFFFF);
	}

	/** The game's own button click, for things drawn by hand too. */
	private void click() {
		client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1f));
	}

	/** An on/off switch: a track with a knob, green when on. */
	private void drawSwitch(DrawContext context, int x, int y, boolean on, boolean hover) {
		int track = on ? (hover ? tint(GOOD, 0xFFFFFFFF, 0.2f) : GOOD) : (hover ? HOVER_BORDER : dark ? 0xFF3A4450 : 0xFFC4C9CC);
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
		int mx = (int) Math.floor(scaled(realMouseX)), my = (int) Math.floor(scaled(realMouseY));
		drawPage(context, mx, my);
		context.getMatrices().popMatrix();
	}

	/** Everything behind the buttons and boxes: the header and its live status, the cards, the lists. */
	private void drawPage(DrawContext context, int mouseX, int mouseY) {
		context.fill(0, 0, width, height, PAGE_BG);
		hits.clear();
		drawHeader(context, mouseX, mouseY);
		int x = contentX();
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
			bold(context, "Baritone isn't installed", x + 12, cy + 10, BAD);
			context.drawText(textRenderer, "Get the Fabric 1.21.11 build from github.com/cabaletta/baritone/releases", x + 12, cy + 24, MUTED, false);
			context.drawText(textRenderer, "(or use a client that has it built in), then open this again.", x + 12, cy + 36, MUTED, false);
			return;
		}
		drawStatusChip(context, tabsEnd + 10, mouseX, mouseY);

		// Cards first, then what's written on them, then the boxes' frames: all kept to the page, under the tabs.
		context.enableScissor(0, HEADER + TABS, width, height - STATUS_H);
		for (Card c : cards) {
			if (c.y() + c.h() < TOP - 4 || c.y() > height) continue;
			roundedBorder(context, c.x(), c.y(), c.w(), c.h(), CARD, BORDER);
			if (c.title() != null) bold(context, c.title(), c.x() + 10, c.y() + 8, TEXT);
			if (c.note() != null) {
				int tx = c.title() == null ? c.x() + 10 : c.x() + 16 + boldWidth(c.title());
				context.drawText(textRenderer, fit(c.note(), c.x() + c.w() - 10 - tx), tx, c.y() + 8, DIM, false);
			}
		}
		for (Object[] l : labels) {
			int ly = (int) l[2];
			if (ly < TOP - 2 || ly > height - STATUS_H) continue;
			context.drawText(textRenderer, (String) l[0], (int) l[1], ly, MUTED, false);
		}
		int mx = overlay == null ? mouseX : -1000, my = overlay == null ? mouseY : -1000;
		switch (page) {
			case GO -> renderPlaces(context, mx, my);
			case MINE -> {
				drawPicks(context, mx, my);
				drawMineProgress(context);
				if (browserArea != null) {
					int top = mineBrowser.drawTabs(context, mx, my, browserArea[0], browserArea[1], browserArea[2], hits);
					mineBrowser.drawList(context, mx, my, browserArea[0], top, browserArea[2] - 6, browserArea[1] + browserArea[3], hits);
				}
			}
			case FOLLOW -> {
				drawPlayers(context, mx, my);
				if (mobsArea != null) {
					int top = mobBrowser.drawTabs(context, mx, my, mobsArea[0], mobsArea[1], mobsArea[2], hits);
					mobBrowser.drawList(context, mx, my, mobsArea[0], top, mobsArea[2] - 6, mobsArea[1] + mobsArea[3], hits);
				}
			}
			case SETTINGS -> renderSettings(context, mx, my);
			case COMMANDS -> renderCommands(context, mx, my);
			case ROAM -> drawRoam(context);
			case TOOLS -> {}
		}
		// The boxes' frames last, so a card drawn by the page never covers one.
		for (Field f : fields) {
			if (!f.visible || f == smartField) continue;
			roundedBorder(context, f.getX() - 5, f.getY() - 4, f.getWidth() + 10, 18, FIELD, f.isFocused() ? LINK : INPUT_BORDER);
		}
		context.disableScissor();
		if (page == Page.ROAM || page == Page.TOOLS) {
			int max = Math.max(0, pageHeight - (pageBottom() - TOP));
			scrollbar(context, contentX() + contentW() + 4, TOP, pageBottom(), pageHeight, pageScroll, max);
		}
	}

	/** The dark bar on top (name, search bar, light/dark), and the tabs' bar with its gliding underline. */
	private void drawHeader(DrawContext context, int mouseX, int mouseY) {
		// The same two-tone gradient as the main screen's header.
		for (int i = 0; i < HEADER; i += 2) context.fill(0, i, width, Math.min(HEADER, i + 2), tint(0xFF1C2633, NAV, Math.min(1f, i / (float) HEADER * 1.4f)));
		context.fill(0, HEADER, width, HEADER + TABS, SUBNAV);
		context.fill(0, HEADER + TABS - 1, width, HEADER + TABS, 0x40000000);
		int x = 8;
		if (parent != null) {
			boolean over = overlay == null && mouseX >= 4 && mouseX < x + 10 && mouseY >= 4 && mouseY < 22;
			context.drawText(textRenderer, "‹", x, 9, over ? ORANGE : NAV_MUTED, false);
			hits.add(new Hit(2, 3, x + 10, 23, this::close, "back", false, "Back to Naughty or Nice (Esc)"));
			x += textRenderer.getWidth("‹") + 10;
		}
		bold(context, "baritone", x, 9, NAV_TEXT);
		// An orange underline under its first letters, like the main screen's "or".
		context.fill(x, 19, x + boldWidth("bar"), 20, ORANGE);
		// The search bar: rounded, a magnifier, the "/" key, an orange glow when it's typed in.
		if (smartBox != null && smartField != null) {
			int sx = smartBox[0], sy = smartBox[1], sw = smartBox[2];
			boolean focused = smartField.isFocused();
			float glow = ease("smart-glow", focused);
			boolean over = mouseX >= sx && mouseX < sx + sw && mouseY >= sy && mouseY < sy + 16;
			if (glow > 0) rounded(context, sx - 1, sy - 1, sw + 2, 18, alphaOf(ORANGE, (int) (0x70 * glow)));
			rounded(context, sx, sy, sw, 16, focused ? ORANGE : over ? 0xFF55657A : NAV_BORDER);
			rounded(context, sx + 1, sy + 1, sw - 2, 14, FIELD);
			magnifier(context, sx + 6, sy + 4, focused ? ORANGE : MUTED);
			if (!focused && smartField.getText().isEmpty()) {
				int kx = sx + sw - 16;
				roundedBorder(context, kx, sy + 3, 11, 10, SOFT, BORDER);
				context.drawText(textRenderer, "/", kx + 4, sy + 4, MUTED, false);
			}
			hits.add(new Hit(sx, sy, sx + 18, sy + 16, () -> focusSmart(), "smart", false, null));
		}
		// Light / dark, as on the main screen.
		float th = ease("theme", overlay == null && mouseX >= themeX && mouseX < themeX + 16 && mouseY >= 5 && mouseY < 21);
		rounded(context, themeX, 5, 16, 16, blend(NAV_SOFT, NAV_SOFT_HOVER, th));
		if (dark) sun(context, themeX + 8, 13, 0xFFFFD866);
		else moon(context, themeX + 8, 13, 0xFFDDE3EA);
		hits.add(new Hit(themeX, 5, themeX + 16, 21, this::toggleTheme, "theme", false, dark ? "Light theme (for Naughty or Nice too)" : "Dark theme (for Naughty or Nice too)"));
		// The underline glides to the open page's tab.
		for (var e : children()) {
			if (e instanceof TabButton t && t.p == page) {
				if (underlineW == 0) {
					underlineX = t.getX();
					underlineW = t.getWidth();
				}
				underlineX += (t.getX() - underlineX) * Math.min(1f, animStep * 1.2f);
				underlineW += (t.getWidth() - underlineW) * Math.min(1f, animStep * 1.2f);
				int ux = Math.round(underlineX) + 4, uw = Math.max(4, Math.round(underlineW) - 8);
				context.fill(ux, HEADER + TABS - 3, ux + uw, HEADER + TABS - 1, ORANGE);
				context.fill(ux - 1, HEADER + TABS - 4, ux + uw + 1, HEADER + TABS - 3, alphaOf(ORANGE, 0x50));
			}
		}
	}

	/** A little sun (switch to light), drawn so it reads at any size. */
	private static void sun(DrawContext context, int cx, int cy, int color) {
		for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) if (dx * dx + dy * dy <= 5) context.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
		for (int i = 0; i < 8; i++) {
			double a = i * Math.PI / 4;
			int rx = (int) Math.round(Math.cos(a) * 4.5), ry = (int) Math.round(Math.sin(a) * 4.5);
			context.fill(cx + rx, cy + ry, cx + rx + 1, cy + ry + 1, color);
		}
	}

	/** A crescent moon (switch to dark). */
	private static void moon(DrawContext context, int cx, int cy, int color) {
		for (int dx = -4; dx <= 4; dx++) {
			for (int dy = -4; dy <= 4; dy++) {
				boolean in = dx * dx + dy * dy <= 16;
				boolean bite = (dx - 2) * (dx - 2) + (dy + 1) * (dy + 1) <= 9;
				if (in && !bite) context.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
			}
		}
	}

	/** A small downward chevron (the font's ▾ is too small to read). */
	private static void chevron(DrawContext context, int x, int y, int color) {
		context.fill(x, y, x + 5, y + 1, color);
		context.fill(x + 1, y + 1, x + 4, y + 2, color);
		context.fill(x + 2, y + 2, x + 3, y + 3, color);
	}

	/** A small magnifying glass. */
	private static void magnifier(DrawContext context, int x, int y, int color) {
		context.fill(x + 1, y, x + 5, y + 1, color);
		context.fill(x, y + 1, x + 1, y + 5, color);
		context.fill(x + 5, y + 1, x + 6, y + 5, color);
		context.fill(x + 1, y + 5, x + 5, y + 6, color);
		context.fill(x + 5, y + 6, x + 6, y + 7, color);
		context.fill(x + 6, y + 7, x + 8, y + 8, color);
	}

	private void focusSmart() {
		if (smartField == null) return;
		setFocused(smartField);
		smartField.setFocused(true);
		smartFor = null;
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

	/** What the last job handed over here was, in words ("Following Griefer_Gary"), for the status. */
	private static String jobLabel;

	/** What Baritone is doing right now, on the tabs' bar: a coloured state, then what, where, how far, how fast. */
	private void drawStatusChip(DrawContext context, int x, int mouseX, int mouseY) {
		String state;
		int color;
		String detail = "";
		boolean trip = SmartTravel.active() || HighwayRoam.active() || TrailFollow.active();
		TripBar.Where where = trip ? TripBar.where(client) : null;
		boolean pathing = BaritoneBridge.isPathing();
		String process = process();
		if (HighwayRoam.active() && HighwayRoam.paused() || TrailFollow.active() && TrailFollow.paused() || BaritoneBridge.paused()) {
			state = "Paused";
			color = 0xFFFFC14D;
		} else if (TrailFollow.active() && !TrailFollow.flying()) {
			state = "Following";
			color = 0xFF4CD39B;
		} else if (where != null && where.flying() || "Flying".equals(process)) {
			state = "Flying";
			color = 0xFFB388FF;
		} else if (HighwayRoam.active()) {
			state = "Roaming";
			color = 0xFF5BA8FF;
		} else if (pathing || process != null) {
			state = process != null ? process : "Moving";
			color = 0xFF4CD39B;
		} else {
			state = "Idle";
			color = NAV_DIM;
		}
		if (MineJob.active() && "Mining".equals(process)) {
			int got = MineJob.got(client);
			long secs = (System.currentTimeMillis() - MineJob.startedAt()) / 1000;
			detail = MineJob.what() + " · " + String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60)
					+ (MineJob.amount() > 0 ? " · got " + got + " of " + MineJob.amount() : got > 0 ? " · got " + got : "");
		} else if (where != null) {
			String line = TripBar.line(client, where);
			line = line.replaceFirst("^▶ ", "");
			int cut = line.lastIndexOf(" · ");
			if (cut > 0) line = line.substring(0, cut);
			detail = line;
		} else if (process != null || pathing) {
			// What it was asked to do here, and how far its goal is.
			String what = jobLabel == null ? "" : jobLabel.replaceFirst("^(Following|Mining|Farming|Exploring|Building|Finding|Going to|Heading|Digging|Picking up|Coming to) ?", "");
			int[] at = TripBar.goalXZ(BaritoneBridge.goal());
			String far = at != null && client.player != null && !ModsPanel.streamSafe()
					? String.format(Locale.ROOT, "%,d blocks away", (int) Math.hypot(at[0] - client.player.getX(), at[1] - client.player.getZ())) : "";
			detail = what.isBlank() ? far : far.isEmpty() ? what : what + " · " + far;
		}
		int y = HEADER + 3, h = TABS - 6;
		int right = width - 8;
		// Idle with things done lately: "Again ▾" on the right, for a menu of them.
		if (state.equals("Idle") && !recent.isEmpty()) {
			String again = "↻ Again ▾";
			int aw = textRenderer.getWidth(again) + 12;
			int ax = right - aw;
			boolean over = overlay == null && mouseX >= ax && mouseX < right && mouseY >= y && mouseY < y + h;
			int text = paintButton(context, ax, y, aw, h, Look.NAV, ease("again", over), true);
			context.drawText(textRenderer, again, ax + 6, y + (h - 8) / 2, text, false);
			hits.add(new Hit(ax, y, right, y + h, () -> {
				List<Option> o = new ArrayList<>();
				for (Recent r : recent) o.add(new Option(r.label(), r.icon(), r.glyph(), () -> {
					r.run().run();
					say("Again: " + r.label(), OK);
				}, null));
				openMenu(width - 8 - 240, HEADER + TABS, 240, o);
			}, "again", false, "Do something you did lately again"));
			right = ax - 8;
		}
		if (right - x < 50) return;
		int sw = textRenderer.getWidth(state) + 18;
		boolean blink = !state.equals("Idle") && (System.currentTimeMillis() / 500) % 2 == 0;
		roundedBorder(context, x, y, sw, h, tint(color, SUBNAV, 0.16f), tint(color, SUBNAV, 0.55f));
		context.fill(x + 5, y + h / 2 - 2, x + 9, y + h / 2 + 2, blink ? tint(color, SUBNAV, 0.5f) : color);
		context.drawText(textRenderer, state, x + 12, y + (h - 8) / 2, color, false);
		int dx = x + sw + 6;
		if (detail.isEmpty() && state.equals("Idle") && right - dx >= textRenderer.getWidth("Pick something below, or press / and type it")) {
			detail = "Pick something below, or press / and type it";
		}
		if (!detail.isEmpty() && right - dx > 30) context.drawText(textRenderer, fit(detail, right - dx), dx, y + (h - 8) / 2, state.equals("Idle") ? NAV_DIM : NAV_MUTED, false);
	}

	/** Which of Baritone's jobs is running, in a word, or null. */
	private static String process() {
		if (BaritoneBridge.processActive("getElytraProcess")) return "Flying";
		if (BaritoneBridge.processActive("getMineProcess")) return "Mining";
		if (BaritoneBridge.processActive("getFollowProcess")) return "Following";
		if (BaritoneBridge.processActive("getFarmProcess")) return "Farming";
		if (BaritoneBridge.processActive("getExploreProcess")) return "Exploring";
		if (BaritoneBridge.processActive("getBuilderProcess")) return "Building";
		if (BaritoneBridge.processActive("getGetToBlockProcess")) return "Finding";
		return null;
	}

	/** Roam: the arrows on the compass, and the way it'll go. */
	private void drawRoam(DrawContext context) {
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
		long now = System.nanoTime();
		float dt = lastAnimNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastAnimNanos) / 1e9f);
		lastAnimNanos = now;
		animStep = 1 - (float) Math.exp(-dt * 14);
		int mouseX = (int) Math.floor(scaled(realMouseX)), mouseY = (int) Math.floor(scaled(realMouseY));
		var m = context.getMatrices();
		m.pushMatrix();
		m.scale(uiScale, uiScale);
		boolean hidden = ModsPanel.streamSafe() || !BaritoneBridge.present();
		for (var e : children()) {
			if (e instanceof Field f && hidden) f.visible = false;
		}
		updateHeaderButtons();
		boolean covered = overlay != null;
		super.render(context, covered ? -1000 : mouseX, covered ? -1000 : mouseY, delta);
		// What the mouse is over says what it does.
		hoverHint = null;
		if (!covered) {
			for (var e : children()) {
				if (e instanceof Pill p && p.visible && p.isMouseOver(mouseX, mouseY) && p.hint != null) hoverHint = p.hint;
				if (e instanceof Toggle t && t.visible && t.isMouseOver(mouseX, mouseY) && t.hint != null) hoverHint = t.hint;
			}
			for (Hit h : hits) if (h.hint() != null && h.has(mouseX, mouseY)) hoverHint = h.hint();
		}
		drawSmart(context, mouseX, mouseY);
		if (overlay != null) {
			context.getMatrices().pushMatrix();
			overlay.render(context, mouseX, mouseY);
			for (Hit h : overlay.overlayHits) if (h.hint() != null && h.has(mouseX, mouseY)) hoverHint = h.hint();
			context.getMatrices().popMatrix();
		}
		updateStatus();
		drawStatusBar(context);
		drawTooltip(context, mouseX, mouseY);
		m.popMatrix();
	}

	@Override
	public void tick() {
		// An unanswered "Sure?" goes back to what it was.
		if (confirmKey != null && System.currentTimeMillis() - confirmAt >= 4000) {
			confirmKey = null;
			relayout();
		}
		if (page == Page.MINE) BlockCatalog.countNear(client);
	}

	/** Watches the job handed to Baritone, and reads Baritone's own answer to the last command. */
	private void updateStatus() {
		if (watchProcess != null && System.currentTimeMillis() - ranAt > 1500 && !BaritoneBridge.processActive(watchProcess)) {
			replyMark = -1;
			say(watchProcess.equals("getMineProcess") && MineJob.endedWith() != null ? MineJob.endedWith() : watchFailed, WARN);
			watchProcess = null;
		}
		// Baritone's answer to the last command: an error (in red) if it said one, otherwise the last thing it said.
		// Only what it says in the few seconds after, each thing once, so it never covers a newer message.
		if (replyMark >= 0 && System.currentTimeMillis() - ranAt > 3000) replyMark = -1;
		if (replyMark >= 0) {
			List<BaritoneBridge.Reply> said = BaritoneBridge.repliesSince(replyMark);
			// Its own internals ("Mining [BlockOptionalMeta{...}]", "Following [OtherClientPlayerEntity[...]]") aren't words for people:
			// what was said here already says it better.
			List<BaritoneBridge.Reply> readable = said.stream().filter(r -> r.error() || !r.text().matches(".*(Entity\\[|BlockOptionalMeta|\\{[^}]*=).*")).toList();
			BaritoneBridge.Reply shown = readable.stream().filter(BaritoneBridge.Reply::error).findFirst().orElse(readable.isEmpty() ? null : readable.get(readable.size() - 1));
			if (shown != null) {
				say("Baritone: " + shown.text(), shown.error() ? ERROR : PLAIN);
				if (shown.error()) replyMark = -1;
				else replyMark = BaritoneBridge.replyCount();
			}
		}
	}

	/** The bar along the bottom: the last thing done or said, or what the mouse is over. */
	private void drawStatusBar(DrawContext context) {
		int y = height - STATUS_H;
		context.fill(0, y, width, height, NAV);
		int x = contentX();
		long age = System.currentTimeMillis() - statusAt;
		// The keys, on the right, always; what just happened on the left (fading back after a while).
		String keys = "Esc back · / search · 1–7 pages · " + NaughtyOrNiceClient.INSTANCE.stopKeyName() + " stops anywhere";
		int kw = textRenderer.getWidth(keys);
		boolean showKeys = status.isEmpty() || contentW() - kw > 220;
		if (showKeys) context.drawText(textRenderer, keys, x + contentW() - kw, y + 5, NAV_DIM, false);
		if (status.isEmpty()) return;
		int room = contentW() - (showKeys ? kw + 16 : 0) - 14;
		String mark = statusColor == ERROR ? "✖" : statusColor == OK ? "✔" : statusColor == WARN ? "!" : "•";
		int color = age < 10_000 || statusColor == ERROR ? statusColor : NAV_MUTED;
		// Fresh news stands out for a moment.
		if (age < 1500) {
			int a = (int) (0xFF * (1 - age / 1500.0));
			context.fill(0, y, width, y + 1, alphaOf(statusColor, a));
			context.fill(0, y + 1, width, height, alphaOf(statusColor, a / 10));
		}
		context.drawText(textRenderer, mark, x, y + 5, color, false);
		context.drawText(textRenderer, fit(status, room), x + 12, y + 5, color, false);
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click realClick, boolean doubled) {
		Click click = scaled(realClick);
		if (smartOpen()) {
			for (Hit h : List.copyOf(smartHits)) {
				if (h.has(click.x(), click.y())) {
					click();
					h.run().run();
					return true;
				}
			}
		}
		if (overlay != null) {
			Overlay o = overlay;
			if (o.click(click.x(), click.y())) {
				if (o instanceof Picker p && overlay == p) {
					// Let the search box take the click too (to move the cursor).
					p.browser.search.mouseClicked(click, doubled);
				}
				return true;
			}
			o.closed();
			overlay = null;
			return true;
		}
		if (super.mouseClicked(click, doubled)) return true;
		for (Hit hit : List.copyOf(hits)) {
			if (hit.has(click.x(), click.y())) {
				click();
				hit.run().run();
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
		double x = scaled(mouseX), y = scaled(mouseY);
		if (overlay != null) {
			overlay.scroll(verticalAmount);
			return true;
		}
		if (page == Page.ROAM || page == Page.TOOLS) {
			int max = Math.max(0, pageHeight - (pageBottom() - TOP));
			int next = (int) Math.max(0, Math.min(max, pageScroll - verticalAmount * 20));
			if (next != pageScroll) {
				pageScroll = next;
				relayout();
			}
			return true;
		}
		if (page == Page.MINE && browserArea != null) {
			mineBrowser.scroll -= verticalAmount * 22;
			return true;
		}
		if (page == Page.FOLLOW && mobsArea != null && x >= mobsArea[0] && x < mobsArea[0] + mobsArea[2] && y >= mobsArea[1]) {
			mobBrowser.scroll -= verticalAmount * 22;
			return true;
		}
		listScroll -= verticalAmount * 22;
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		int key = input.key();
		if (overlay != null && key == GLFW.GLFW_KEY_ESCAPE) {
			overlay.closed();
			overlay = null;
			return true;
		}
		if (smartOpen()) {
			int n = smartResults().size();
			switch (key) {
				case GLFW.GLFW_KEY_DOWN -> {
					smartPick = n == 0 ? 0 : (smartPick + 1) % n;
					return true;
				}
				case GLFW.GLFW_KEY_UP -> {
					smartPick = n == 0 ? 0 : (smartPick - 1 + n) % n;
					return true;
				}
				case GLFW.GLFW_KEY_ESCAPE -> {
					smartField.setText("");
					setFocused(null);
					smartField.setFocused(false);
					return true;
				}
				default -> {}
			}
		}
		// ↑ ↓ through a list while typing in its search.
		if ((key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) && getFocused() instanceof Field f) {
			Browser b = overlay instanceof Picker pk && pk.browser.search == f ? pk.browser : mineBrowser.search == f ? mineBrowser : mobBrowser.search == f ? mobBrowser : null;
			if (b != null) {
				b.move(key == GLFW.GLFW_KEY_DOWN ? 1 : -1);
				return true;
			}
		}
		// Enter in a box does what it's for (Go, Follow, Mine, Set...).
		if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && getFocused() instanceof Field f && f.enter != null) {
			f.enter.run();
			return true;
		}
		boolean typing = getFocused() instanceof TextFieldWidget tf && tf.isFocused();
		if (!typing && overlay == null) {
			if (key == GLFW.GLFW_KEY_SLASH && smartField != null) {
				setFocused(smartField);
				smartField.setFocused(true);
				smartFor = null;
				swallowChar = true;
				return true;
			}
			if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_7) {
				showPage(Page.values()[key - GLFW.GLFW_KEY_1]);
				return true;
			}
		}
		if (key == GLFW.GLFW_KEY_F && input.hasCtrlOrCmd()) {
			Field f = switch (page) {
				case GO -> searchField;
				case MINE -> mineBrowser.search;
				case FOLLOW -> mobBrowser.search;
				case SETTINGS -> settingSearch;
				case COMMANDS -> commandSearch;
				default -> smartField;
			};
			if (f != null) {
				setFocused(f);
				f.setFocused(true);
				return true;
			}
		}
		if (key == GLFW.GLFW_KEY_ESCAPE && typing) {
			setFocused(null);
			return true;
		}
		return super.keyPressed(input);
	}

	/** The "/" that opened the search bar isn't typed into it. */
	private boolean swallowChar;

	@Override
	public boolean charTyped(net.minecraft.client.input.CharInput input) {
		if (swallowChar) {
			swallowChar = false;
			if (input.codepoint() == '/') return true;
		}
		boolean typing = getFocused() instanceof TextFieldWidget tf && tf.isFocused();
		// "/" and the page numbers are keys, not letters, when nothing's being typed in.
		if (!typing && overlay == null && (input.codepoint() == '/' || input.codepoint() >= '1' && input.codepoint() <= '7')) return true;
		return super.charTyped(input);
	}

	// ---- For the self-test ----

	/** What Baritone suggests for what's typed on the Commands page. */
	public List<String> testSuggestions() {
		return suggestions;
	}

	/** Shows a page (0 Go, 1 Mine, 2 Follow, 3 Roam, 4 Tools, 5 Settings, 6 Commands). */
	public void testPage(int index) {
		page = Page.values()[index];
		pageScroll = 0;
		overlay = null;
		clearAndInit();
	}

	/** Clicks the button or switch whose label starts with {@code label}, as the mouse would. */
	public boolean testPress(String label) {
		if (pressShown(label)) return true;
		// On a page that scrolls, scroll to it as you would.
		if (page == Page.ROAM || page == Page.TOOLS) {
			int was = pageScroll;
			for (pageScroll = 0; pageScroll <= Math.max(0, pageHeight); pageScroll += 30) {
				relayout();
				if (pressShown(label)) return true;
			}
			pageScroll = was;
			relayout();
		}
		return false;
	}

	private boolean pressShown(String label) {
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

	/** Which page is showing (0 Go, 1 Mine, 2 Follow, 3 Roam, 4 Tools, 5 Settings, 6 Commands). */
	public int testPageShown() {
		return page.ordinal();
	}

	/** Which kind of place Go shows ("" for all). */
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

	/** Whether something drawn by hand with this key was drawn (a place's Go, a block in the picker, a menu's choice). */
	public boolean testHasRow(String key) {
		return hits.stream().anyMatch(h -> key.equals(h.key())) || overlay != null && overlay.overlayHits.stream().anyMatch(h -> key.equals(h.key()));
	}

	/** Clicks something drawn by hand by its key (the × when {@code remove}), as last drawn. */
	public boolean testRow(String name, boolean remove) {
		if (overlay != null) {
			for (Hit h : List.copyOf(overlay.overlayHits)) {
				if (name.equals(h.key()) && remove == h.remove()) {
					testClickAt((h.x0() + h.x1()) / 2.0, (h.y0() + h.y1()) / 2.0);
					return true;
				}
			}
		}
		for (Hit hit : List.copyOf(hits)) {
			if (name.equals(hit.key()) && remove == hit.remove()) {
				if (hit.x0() < 0) {
					// Not on screen (the nether's own Go): run it as the menu would.
					hit.run().run();
					return true;
				}
				testClickAt((hit.x0() + hit.x1()) / 2.0, (hit.y0() + hit.y1()) / 2.0);
				return true;
			}
		}
		return false;
	}

	/** Types into the search bar, and returns what it offers. */
	public List<String> testSmart(String text) {
		if (smartField == null) return List.of();
		setFocused(smartField);
		smartField.setFocused(true);
		smartField.setText(text);
		return smartResults().stream().map(Option::label).toList();
	}

	/** Does what the search bar offers first (as Enter would). */
	public void testSmartEnter() {
		smartPick = 0;
		runSmart();
	}

	/** "ahead", "to your right"... for an angle from where you're facing. */
	public static String testRelative(double rel) {
		return relative(rel);
	}

	/** Puts the typing cursor in the box whose hint is {@code hint}. */
	public boolean testFocus(String hint) {
		for (var e : children()) {
			if (e instanceof TextFieldWidget f && f.getMessage().getString().equals(hint)) {
				setFocused(f);
				f.setFocused(true);
				return true;
			}
		}
		return false;
	}

	/** Whether a menu or picker is open over the page. */
	public boolean testOverlay() {
		return overlay != null;
	}

	/** Types into the open picker's search box. */
	public boolean testPickerType(String text) {
		if (!(overlay instanceof Picker p)) return false;
		p.browser.search.setText(text);
		return true;
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
