package com.greysworkshop.naughtyornice;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The Naughty or Nice "store": a search bar, tabs, and a grid of player cards with one-click Nice / Naughty
 * buttons. Clicking a card opens that player's page with their rating, what they've done, and a note.
 */
public class ShopScreen extends Screen {
	public enum Tab {
		ALL("All"), NEARBY("Nearby"), ONLINE("Online"), OFFLINE("Offline"), FRIENDS("Friends"), NICE("Nice list"), LOOK("Look Into"), NAUGHTY("Naughty list"),
		GROUPS("Groups"), CHAT("Chat"), SETTINGS("Settings");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private enum Sort {
		RECENT("Recently seen"), CLOSEST("Closest"), NAME("Name"), NAUGHTIEST("Naughtiest"), NICEST("Nicest");

		final String label;

		Sort(String label) {
			this.label = label;
		}
	}

	private enum Rating { ANY, GOOD, BAD }

	// Colours, after the big online store. Most change with the theme (see applyTheme).
	private static final int WHITE = 0xFFFFFFFF;
	private static final int STAR = 0xFFFFA41C;
	private static final int YELLOW = 0xFFFFD814;
	private static final int YELLOW_EDGE = 0xFFFCD200;
	private static final int ORANGE = 0xFFFFA41C;
	private static final int ORANGE_EDGE = 0xFFFF8F00;
	private static final int SEARCH_BUTTON = 0xFFFEBD69;
	/** Text on the yellow and orange buttons, dark in both themes. */
	private static final int BUTTON_TEXT = 0xFF0F1111;
	private static final int WHISPER_LIGHT = 0xFF8B3FB0;

	private static int NAV;
	private static int SUBNAV;
	private static int PAGE;
	private static int CARD;
	private static int FIELD;
	private static int BORDER;
	private static int TEXT;
	private static int MUTED;
	private static int LINK;
	private static int HOVER_LINK;
	private static int STAR_EMPTY;
	private static int NICE;
	private static int NAUGHTY;
	private static int BADGE;
	private static int IMAGE_BG;
	private static int HOVER_BG;
	private static int HOVER_BORDER;
	private static int SOFT;
	private static int SOFT_HOVER;
	private static int INPUT_BORDER;
	private static int BAD_BG;
	private static int GOOD_BG;
	private static int HIGHLIGHT;
	private static int WHISPER;
	/** The Look Into list: amber, between Nice green and Naughty red. */
	private static int LOOK;
	private static int LOOK_BG;
	/** Friends: blue, set apart from the three lists. */
	private static int FRIEND;
	private static int FRIEND_BG;
	private static int[] GROUP_COLORS;

	static {
		applyTheme(NNConfig.INSTANCE.darkMode);
	}

	private static void applyTheme(boolean dark) {
		NAV = dark ? 0xFF0A0D11 : 0xFF131921;
		SUBNAV = dark ? 0xFF161D26 : 0xFF232F3E;
		PAGE = dark ? 0xFF0F1318 : 0xFFEAEDED;
		CARD = dark ? 0xFF1A2129 : 0xFFFFFFFF;
		FIELD = dark ? 0xFF0F141A : 0xFFFFFFFF;
		BORDER = dark ? 0xFF2D3742 : 0xFFD5D9D9;
		TEXT = dark ? 0xFFE7E9EC : 0xFF0F1111;
		MUTED = dark ? 0xFF9AA5B1 : 0xFF565959;
		LINK = dark ? 0xFF5CC8E0 : 0xFF007185;
		HOVER_LINK = dark ? 0xFFFFB347 : 0xFFC7511F;
		STAR_EMPTY = dark ? 0xFF3B4551 : 0xFFD5D9D9;
		NICE = dark ? 0xFF4CD39B : 0xFF067D62;
		NAUGHTY = dark ? 0xFFFF7A66 : 0xFFB12704;
		BADGE = dark ? 0xFF37475A : 0xFF232F3E;
		IMAGE_BG = dark ? 0xFF232B35 : 0xFFF7F8F8;
		HOVER_BG = dark ? 0xFF222B35 : 0xFFF7FAFA;
		HOVER_BORDER = dark ? 0xFF4A5663 : 0xFFA0A6A6;
		SOFT = dark ? 0xFF25303B : 0xFFF0F2F2;
		SOFT_HOVER = dark ? 0xFF2E3A46 : 0xFFE3E6E6;
		INPUT_BORDER = dark ? 0xFF4A5663 : 0xFF888C8C;
		BAD_BG = dark ? 0xFF4A201A : 0xFFFFD8D0;
		GOOD_BG = dark ? 0xFF163D2E : 0xFFD8F5E6;
		HIGHLIGHT = dark ? 0xFF3A3220 : 0xFFFFF4D6;
		WHISPER = dark ? 0xFFCF8BEF : WHISPER_LIGHT;
		LOOK = dark ? 0xFFFFC14D : 0xFFB06A00;
		LOOK_BG = dark ? 0xFF3D3015 : 0xFFFFEFC7;
		FRIEND = dark ? 0xFF8AB4FF : 0xFF2457C5;
		FRIEND_BG = dark ? 0xFF1C2A44 : 0xFFE3ECFF;
		GROUP_COLORS = dark
				? new int[] {0xFF5BC0FF, 0xFFFF9F43, 0xFFB388FF, 0xFF4DD0A1, 0xFFFF6FA8, 0xFFFFD54F, 0xFF80DEEA, 0xFFC5E1A5, 0xFFFF8A65, 0xFFB0BEC5}
				: new int[] {0xFF1565C0, 0xFFE65100, 0xFF6A1B9A, 0xFF00796B, 0xFFC2185B, 0xFF8D6E00, 0xFF00838F, 0xFF558B2F, 0xFFD84315, 0xFF546E7A};
	}

	/** {@code color} mixed into {@code base}: {@code amount} 0 is all base, 1 all colour. */
	private static int tint(int color, int base, float amount) {
		int r = Math.round((base >> 16 & 0xFF) * (1 - amount) + (color >> 16 & 0xFF) * amount);
		int g = Math.round((base >> 8 & 0xFF) * (1 - amount) + (color >> 8 & 0xFF) * amount);
		int b = Math.round((base & 0xFF) * (1 - amount) + (color & 0xFF) * amount);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private int groupColor(String group) {
		return GROUP_COLORS[mod.ledger.groupColor(group)];
	}

	/** Switches between light and dark, and redraws (text boxes pick their colours up when built). */
	private void toggleTheme() {
		NNConfig.INSTANCE.darkMode = !NNConfig.INSTANCE.darkMode;
		NNConfig.save();
		applyTheme(NNConfig.INSTANCE.darkMode);
		clearAndInit();
	}

	private static final int HEADER = 26;
	private static final int TABS = 15;
	private static final int BODY = HEADER + TABS;
	private static final int CARD_W = 172;
	private static final int CARD_H = 74;
	private static final int GAP = 6;
	private static final int SIDEBAR = 96;
	private static final int BUY_BOX = 150;

	private static Tab lastTab;
	private static Sort sort = Sort.RECENT;
	private static boolean onlineOnly;
	private static Rating rating = Rating.ANY;
	/** Faces of players seen this session, so they keep their skin after logging off. */
	private static final Map<UUID, SkinTextures> SKINS = new HashMap<>();

	private record Hit(int x0, int y0, int x1, int y1, String label, Runnable run) {}

	/**
	 * A text box whose placeholder is drawn without a shadow: the game always shadows it, which smears grey text on
	 * the light theme's white boxes.
	 */
	private static final class Box extends TextFieldWidget {
		private final net.minecraft.client.font.TextRenderer renderer;
		private Text hint = Text.empty();

		Box(net.minecraft.client.font.TextRenderer renderer, int x, int y, int w, int h, Text narration) {
			super(renderer, x, y, w, h, narration);
			this.renderer = renderer;
		}

		@Override
		public void setPlaceholder(Text placeholder) {
			hint = placeholder;
		}

		@Override
		public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
			super.renderWidget(context, mouseX, mouseY, delta);
			if (getText().isEmpty() && !isFocused()) context.drawText(renderer, hint, getX(), getY(), 0xFFFFFFFF, false);
		}
	}

	private final NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
	private final List<Hit> hits = new ArrayList<>();
	private Tab tab;
	private Profile selected;
	private String query = "";
	private TextFieldWidget search;
	private TextFieldWidget detailField;
	/** The detail being edited in the box, or null when the box adds a new one. */
	private Profile.Detail editing;
	private int detailFieldTop = -1000;
	/** Settings: the words to be alerted about. */
	private TextFieldWidget alertField;
	private int alertFieldTop = -1000;
	/** The Chat check panel is open. */
	private boolean checking;
	private double checkScroll;
	private int checkMaxScroll;
	private String checkStatus;
	private double scroll;
	private int contentHeight;
	private String banner;
	private int bannerColor;
	private Runnable bannerUndo;
	private long bannerUntil;
	private boolean confirmForget;
	private List<Profile> cachedResults;

	/** What the player page shows under "About this player". */
	private enum Section { DETAILS, ACTIVITY, SEEN, LINKS, CHAT }

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ROOT);
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
	private static final String NAME_PATTERN = "[.*]?[A-Za-z0-9_]{1,16}";

	private Section section = Section.DETAILS;
	/** Conversations opened or closed by hand, by start time. Otherwise only the newest is open. */
	private final Map<Long, Boolean> expanded = new HashMap<>();

	// The "Add players" panel.
	private boolean adding;
	private boolean addOnOpen;
	private Profile.Verdict addTarget = Profile.Verdict.NICE;
	/** The circle "★ Friends" adds to. */
	private String addCircle;
	private TextFieldWidget addField;
	private TextFieldWidget addNote;
	private String addStatus;
	private int addStatusColor;
	private final List<Hit> panelHits = new ArrayList<>();
	private int addFieldTop;
	private int addNoteTop;

	// The Share panel.
	private enum ShareView { SEND, RECEIVE, PREVIEW }
	private boolean sharing;
	private ShareView shareOnOpen;
	private ShareView shareView = ShareView.SEND;
	private static Sharing.Scope shareScope = Sharing.Scope.LISTS;
	private static boolean shareChat;
	private static boolean takeLists = true;
	private static boolean theirsWins;
	private String shareStatus;
	private int shareStatusColor;
	private Path shareSavedFile;
	private String shareCode;
	private String shareCodeKey;
	private Sharing.Pack incoming;
	/** The share link made for the current choice, and which choice that was. */
	private String shareLink;
	private String shareLinkKey = "";
	private boolean uploading;
	/** A link to open when the screen opens (from a chat button). */
	private String openLink;
	private List<Sharing.Row> incomingRows;
	private double previewScroll;
	private int previewMaxScroll;

	// Friends: the circle shown on the Friends tab (null for all), and the circle picker.
	private static String friendCircle;
	/** The player the circle picker is open for, and where it hangs from. */
	private Profile circleMenu;
	private int circleMenuX, circleMenuY;
	private final List<Hit> menuHits = new ArrayList<>();
	/** Settings opened from "Edit circles…" or /nn disconnects: scroll down to that card. */
	private boolean scrollToCircles;
	private boolean scrollToDisconnects;
	/** Settings: the box for naming circles, and the circle being renamed (null to make a new one). */
	private TextFieldWidget circleField;
	private int circleFieldTop = -1000;
	private Friends.Circle circleEditing;

	// Groups: the group being looked at on the Groups tab (null for all of them), and the group panel.
	private String openGroup;
	private boolean confirmDeleteGroup;
	private enum GroupMode { ADD, RENAME }
	private boolean grouping;
	private GroupMode groupMode = GroupMode.ADD;
	/** The group the panel adds to or renames; null to pick or make one. */
	private String groupPanelGroup;
	private TextFieldWidget groupField;
	private TextFieldWidget membersField;
	private int groupFieldTop;
	private int membersFieldTop;
	private String groupStatus;
	private int groupStatusColor;
	/** In the Add players panel: a group to put everyone in. */
	private TextFieldWidget addGroup;
	private int addGroupTop;

	// The Chat tab.
	private enum ChatFilter {
		ALL("All"), LISTED("On a list"), FRIENDS("Friends"), NICE("Nice"), LOOK("Look Into"), NAUGHTY("Naughty"), UNLISTED("Not listed"), WHISPERS("Whispers");

		final String label;

		ChatFilter(String label) {
			this.label = label;
		}
	}
	private static ChatFilter chatFilter = ChatFilter.ALL;
	/** Only chat from this group's members, or null. */
	private static String chatGroup;
	private TextFieldWidget chatField;
	/** How far the chat is scrolled up from the newest line. */
	private double chatScroll;
	private int chatMaxScroll;
	private int chatUnread;
	private record ChatRow(Profile.Line line, Profile who, List<net.minecraft.text.OrderedText> wrapped, int h) {}
	private List<ChatRow> chatRows = List.of();
	private String chatRowsKey;

	public ShopScreen() {
		this(null, null);
	}

	public ShopScreen(Tab tab, Profile selected) {
		super(Text.literal("Naughty or Nice"));
		this.tab = tab != null ? tab : lastTab != null ? lastTab : null;
		this.selected = selected;
	}

	/** Opens with the Add players panel showing, set to add to {@code target}. */
	public ShopScreen withAddPanel(Profile.Verdict target) {
		addOnOpen = true;
		addTarget = target;
		return this;
	}

	/** Opens with the Share panel showing: the sending side, or the receiving side. */
	public ShopScreen withSharePanel(boolean receive) {
		shareOnOpen = receive ? ShareView.RECEIVE : ShareView.SEND;
		return this;
	}

	/** Opens on Settings, scrolled to the disconnects. */
	public ShopScreen withDisconnects() {
		tab = Tab.SETTINGS;
		scrollToDisconnects = true;
		return this;
	}

	/** Opens on Settings with the Chat check showing. */
	public ShopScreen withChatCheck() {
		tab = Tab.SETTINGS;
		checking = true;
		return this;
	}

	/** Opens a player's page on their chat log. */
	public ShopScreen withChatLog() {
		section = Section.CHAT;
		return this;
	}

	// ---- Layout ----

	private int logoRight() {
		return 8 + textRenderer.getWidth("naughty&nice") + 4;
	}

	private int countersLeft() {
		return width - 8 - 4 * counterWidth() - 18;
	}

	/** The light/dark switch sits between the search box and the counters. */
	private int themeLeft() {
		return countersLeft() - 22;
	}

	private int counterWidth() {
		return Math.max(textRenderer.getWidth("Naughty list"), textRenderer.getWidth("999 people")) + 4;
	}

	/** The screen is laid out at its own size and drawn scaled down by this, so it isn't huge at big GUI scales. */
	private float uiScale = 1;

	/** How wide (in its own pixels) the screen is laid out for each size: Small fits the most, Large the least. */
	private static final int[] SIZE_WIDTHS = {900, 680, 540};
	/** The screen never gets shorter than this, so panels fit. */
	private static final int MIN_HEIGHT = 300;

	/**
	 * The scale to draw at, as window pixels per screen pixel divided by the game's GUI scale. Each size aims for
	 * its {@link #SIZE_WIDTHS layout width}; a whole number of window pixels is used when one is close (crispest
	 * text), otherwise the nearest half or quarter. The three sizes always come out different.
	 */
	private float chooseScale() {
		var window = client.getWindow();
		int gui = Math.max(1, window.getScaleFactor());
		return pixelsPerUnit(window.getFramebufferWidth(), window.getFramebufferHeight(), NNConfig.INSTANCE.screenSize) / gui;
	}

	/** Window pixels per screen pixel for a size, in a window this big. */
	static float pixelsPerUnit(int windowW, int windowH, int size) {
		float[] chosen = new float[SIZE_WIDTHS.length];
		for (int i = 0; i < SIZE_WIDTHS.length; i++) {
			float cap = Math.max(1, windowH / (float) MIN_HEIGHT);
			float exact = Math.max(1, Math.min(windowW / (float) SIZE_WIDTHS[i], cap));
			float whole = Math.round(exact);
			float half = Math.round(exact * 2) / 2f;
			float pick;
			if (whole >= 1 && whole <= cap && Math.abs(whole - exact) / exact <= 0.08f) pick = whole;
			else if (half >= 1 && half <= cap && Math.abs(half - exact) / exact <= 0.08f) pick = half;
			else pick = Math.max(1, (float) Math.floor(exact * 4) / 4f);
			// Each size bigger than the last, even in small windows.
			if (i > 0 && pick <= chosen[i - 1]) pick = chosen[i - 1] + 0.25f;
			chosen[i] = pick;
		}
		return chosen[Math.max(0, Math.min(SIZE_WIDTHS.length - 1, size))];
	}

	/** A mouse position on the game's GUI scale, on this screen's scale. */
	private double scaled(double v) {
		return v / uiScale;
	}

	private Click scaled(Click click) {
		return new Click(scaled(click.x()), scaled(click.y()), click.buttonInfo());
	}

	@Override
	protected void init() {
		uiScale = chooseScale();
		width = (int) Math.ceil(client.getWindow().getScaledWidth() / uiScale);
		height = (int) Math.ceil(client.getWindow().getScaledHeight() / uiScale);
		applyTheme(NNConfig.INSTANCE.darkMode);
		if (tab == null) tab = nearby().isEmpty() ? Tab.ONLINE : Tab.NEARBY;
		int searchLeft = logoRight() + 8;
		int searchRight = themeLeft() - 6;
		search = new Box(textRenderer, searchLeft + 5, 9, Math.max(20, searchRight - searchLeft - 30), 10, Text.literal("Search"));
		search.setDrawsBackground(false);
		search.setEditableColor(TEXT);
		search.setTextShadow(false);
		search.setMaxLength(32);
		search.setPlaceholder(Text.literal("Search players, details, groups or chat").withColor(MUTED));
		search.setText(query);
		search.setChangedListener(q -> {
			query = q.trim();
			scroll = 0;
			cachedResults = null;
			if (selected != null || tab == Tab.SETTINGS) {
				selected = null;
				if (tab == Tab.SETTINGS) tab = Tab.ALL;
			}
		});
		addDrawableChild(search);

		String typedDetail = detailField == null ? "" : detailField.getText();
		detailField = new Box(textRenderer, 0, -100, 100, 10, Text.literal("Detail"));
		detailField.setDrawsBackground(false);
		detailField.setEditableColor(TEXT);
		detailField.setTextShadow(false);
		detailField.setMaxLength(256);
		detailField.setPlaceholder(Text.literal("Write a detail: alts, where their base is, who they play with…").withColor(MUTED));
		detailField.setText(typedDetail);
		addDrawableChild(detailField);

		String typedChat = chatField == null ? "" : chatField.getText();
		chatField = new Box(textRenderer, 0, -100, 100, 10, Text.literal("Chat"));
		chatField.setDrawsBackground(false);
		chatField.setEditableColor(TEXT);
		chatField.setTextShadow(false);
		chatField.setMaxLength(256);
		chatField.setPlaceholder(Text.literal("Say something in chat… (Enter sends, / for commands)").withColor(MUTED));
		chatField.setText(typedChat);
		addDrawableChild(chatField);

		alertField = new Box(textRenderer, 0, -100, 100, 10, Text.literal("Alert words"));
		alertField.setDrawsBackground(false);
		alertField.setEditableColor(TEXT);
		alertField.setTextShadow(false);
		alertField.setMaxLength(300);
		alertField.setPlaceholder(Text.literal("e.g. {me}, my base, stash, grief").withColor(MUTED));
		alertField.setText(String.join(", ", NNConfig.INSTANCE.alertWords));
		alertField.setChangedListener(text -> {
			List<String> words = new ArrayList<>();
			for (String word : text.split(",")) {
				if (!word.isBlank() && words.stream().noneMatch(word.strip()::equalsIgnoreCase)) words.add(word.strip());
			}
			NNConfig.INSTANCE.alertWords = words;
			NNConfig.save();
		});
		addDrawableChild(alertField);

		String typedCircle = circleField == null ? "" : circleField.getText();
		circleField = new Box(textRenderer, 0, -100, 100, 10, Text.literal("Circle"));
		circleField.setDrawsBackground(false);
		circleField.setEditableColor(TEXT);
		circleField.setTextShadow(false);
		circleField.setMaxLength(Friends.NAME_MAX);
		circleField.setPlaceholder(Text.literal("New circle, e.g. Old crew, Discord friends").withColor(MUTED));
		circleField.setText(typedCircle);
		addDrawableChild(circleField);

		// Group panel boxes, drawn and fed by the panel like the Add players ones.
		String typedGroup = groupField == null ? "" : groupField.getText();
		String typedMembers = membersField == null ? "" : membersField.getText();
		groupField = new Box(textRenderer, 0, 0, 100, 10, Text.literal("Group"));
		groupField.setDrawsBackground(false);
		groupField.setEditableColor(TEXT);
		groupField.setTextShadow(false);
		groupField.setMaxLength(Ledger.GROUP_NAME_MAX);
		groupField.setPlaceholder(Text.literal("e.g. SCG, Spawn builders, Duc's alts").withColor(MUTED));
		groupField.setText(typedGroup);
		membersField = new Box(textRenderer, 0, 0, 100, 10, Text.literal("Members"));
		membersField.setDrawsBackground(false);
		membersField.setEditableColor(TEXT);
		membersField.setTextShadow(false);
		membersField.setMaxLength(400);
		membersField.setPlaceholder(Text.literal("Names, separated with spaces or commas").withColor(MUTED));
		membersField.setText(typedMembers);

		// The Add players panel's boxes aren't children: the panel draws them on top and routes input to them.
		String typed = addField == null ? "" : addField.getText();
		String typedNote = addNote == null ? "" : addNote.getText();
		addField = new Box(textRenderer, 0, 0, 100, 10, Text.literal("Names"));
		addField.setDrawsBackground(false);
		addField.setEditableColor(TEXT);
		addField.setTextShadow(false);
		addField.setMaxLength(400);
		addField.setPlaceholder(Text.literal("e.g. Steve, Alex_99").withColor(MUTED));
		addField.setText(typed);
		addNote = new Box(textRenderer, 0, 0, 100, 10, Text.literal("Note"));
		addNote.setDrawsBackground(false);
		addNote.setEditableColor(TEXT);
		addNote.setTextShadow(false);
		addNote.setMaxLength(100);
		addNote.setPlaceholder(Text.literal("Optional, e.g. base mates").withColor(MUTED));
		addNote.setText(typedNote);
		String typedAddGroup = addGroup == null ? "" : addGroup.getText();
		addGroup = new Box(textRenderer, 0, 0, 100, 10, Text.literal("Group"));
		addGroup.setDrawsBackground(false);
		addGroup.setEditableColor(TEXT);
		addGroup.setTextShadow(false);
		addGroup.setMaxLength(Ledger.GROUP_NAME_MAX);
		addGroup.setPlaceholder(Text.literal("Optional, e.g. SCG").withColor(MUTED));
		addGroup.setText(typedAddGroup);
		if (addOnOpen) {
			addOnOpen = false;
			openAdd(addTarget);
		} else if (adding) {
			setFocused(addField);
		} else if (grouping) {
			setFocused(groupPanelGroup != null && groupMode == GroupMode.ADD ? membersField : groupField);
		}
		if (shareOnOpen != null) {
			ShareView view = shareOnOpen;
			shareOnOpen = null;
			openShare(view);
			// Opened with /nn import and a code already copied: go straight to the preview.
			String clip = client.keyboard.getClipboard();
			if (view == ShareView.RECEIVE && (clip.contains(Sharing.CODE_PREFIX) || CloudShare.find(clip) != null)) pasteShare();
		}
		if (openLink != null) {
			String link = openLink;
			openLink = null;
			openShare(ShareView.RECEIVE);
			openShareLink(link);
		}
		cachedResults = null;
	}

	// ---- Data ----

	private UUID me() {
		return client.player == null ? null : client.player.getUuid();
	}

	private List<PlayerEntity> nearby() {
		List<PlayerEntity> result = new ArrayList<>();
		if (client.world == null) return result;
		for (PlayerEntity player : client.world.getPlayers()) {
			if (!player.getUuid().equals(me())) result.add(player);
		}
		return result;
	}

	private List<PlayerListEntry> online() {
		List<PlayerListEntry> result = new ArrayList<>();
		if (client.getNetworkHandler() == null) return result;
		for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
			if (!entry.getProfile().id().equals(me())) result.add(entry);
		}
		return result;
	}

	private boolean isOnline(Profile profile) {
		return client.getNetworkHandler() != null && client.getNetworkHandler().getPlayerListEntry(profile.id) != null;
	}

	private PlayerEntity entity(Profile profile) {
		return client.world == null ? null : client.world.getPlayerByUuid(profile.id);
	}

	private double distance(Profile profile) {
		PlayerEntity player = entity(profile);
		return player == null || client.player == null ? Double.MAX_VALUE : player.distanceTo(client.player);
	}

	private int count(Tab t) {
		return switch (t) {
			case NEARBY -> nearby().size();
			case ONLINE -> online().size();
			case OFFLINE -> (int) mod.ledger.all().stream().filter(p -> !isOnline(p) && !p.id.equals(me())).count();
			case NICE -> mod.ledger.onList(Profile.Verdict.NICE).size();
			case FRIENDS -> mod.ledger.onList(Profile.Verdict.FRIEND).size();
			case NAUGHTY -> mod.ledger.onList(Profile.Verdict.NAUGHTY).size();
			case LOOK -> (int) mod.ledger.all().stream().filter(this::onLookTab).count();
			case GROUPS -> mod.ledger.groups().size();
			default -> -1;
		};
	}

	/** Players we'd suggest a list for (not on one yet, but they've done enough good or bad). */
	private boolean suggested(Profile p) {
		return p.verdict == Profile.Verdict.NONE && p.suggestion() != Profile.Verdict.NONE && !p.id.equals(me());
	}

	/** The Look Into tab: your Look Into list, then players whose behaviour suggests a list. */
	private boolean onLookTab(Profile p) {
		return p.verdict == Profile.Verdict.LOOK || suggested(p);
	}

	private List<Profile> results() {
		if (cachedResults != null) return cachedResults;
		Map<UUID, Profile> found = new LinkedHashMap<>();
		switch (tab) {
			case NEARBY -> nearby().forEach(p -> found.put(p.getUuid(), mod.ledger.getOrCreate(p.getUuid(), p.getGameProfile().name())));
			case ONLINE -> online().forEach(e -> found.put(e.getProfile().id(), mod.ledger.getOrCreate(e.getProfile().id(), e.getProfile().name())));
			case OFFLINE -> List.copyOf(mod.ledger.all()).stream().filter(p -> !isOnline(p)).forEach(p -> found.put(p.id, p));
			case NICE -> mod.ledger.onList(Profile.Verdict.NICE).forEach(p -> found.put(p.id, p));
			case FRIENDS -> mod.ledger.onList(Profile.Verdict.FRIEND).stream()
					.filter(p -> friendCircle == null || Friends.of(p) == Friends.byId(friendCircle)).forEach(p -> found.put(p.id, p));
			case NAUGHTY -> mod.ledger.onList(Profile.Verdict.NAUGHTY).forEach(p -> found.put(p.id, p));
			case LOOK -> List.copyOf(mod.ledger.all()).stream().filter(this::onLookTab).forEach(p -> found.put(p.id, p));
			case GROUPS -> {
				if (openGroup != null) mod.ledger.members(openGroup).forEach(p -> found.put(p.id, p));
			}
			default -> {
				online().forEach(e -> mod.ledger.getOrCreate(e.getProfile().id(), e.getProfile().name()));
				List.copyOf(mod.ledger.all()).forEach(p -> found.put(p.id, p));
			}
		}
		found.remove(me());
		String q = query.toLowerCase(Locale.ROOT);
		List<Profile> list = new ArrayList<>();
		for (Profile p : found.values()) {
			if (!q.isEmpty() && !p.name.toLowerCase(Locale.ROOT).contains(q) && !p.pastNamesContain(q) && !p.detailsContain(q)
					&& !p.groupsContain(q) && !Friends.circleContains(p, q) && chatMatch(p, q) == null) continue;
			if (onlineOnly && !isOnline(p)) continue;
			if (rating == Rating.GOOD && p.stars() < 4) continue;
			if (rating == Rating.BAD && p.stars() > 2) continue;
			list.add(p);
		}
		Comparator<Profile> order = switch (sort) {
			case RECENT -> Comparator.comparing((Profile p) -> !isOnline(p)).thenComparing(p -> -p.lastSeen);
			case CLOSEST -> Comparator.comparingDouble(this::distance).thenComparing(p -> -p.lastSeen);
			case NAME -> Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT));
			case NAUGHTIEST -> Comparator.comparingInt(Profile::score).thenComparing(p -> -p.lastSeen);
			case NICEST -> Comparator.comparingInt((Profile p) -> -p.score()).thenComparing(p -> -p.lastSeen);
		};
		// Your Look Into list comes before the suggestions.
		if (tab == Tab.LOOK) order = Comparator.comparing((Profile p) -> p.verdict != Profile.Verdict.LOOK).thenComparing(order);
		list.sort(order);
		cachedResults = list;
		return list;
	}

	/** The newest thing they said containing {@code q} (lower case), from their chat log or recent chat. */
	private static String chatMatch(Profile p, String q) {
		for (int c = p.conversations.size() - 1; c >= 0; c--) {
			List<Profile.Line> lines = p.conversations.get(c).lines;
			for (int i = lines.size() - 1; i >= 0; i--) {
				Profile.Line line = lines.get(i);
				if (line.them && line.text.toLowerCase(Locale.ROOT).contains(q)) return line.text;
			}
		}
		for (int i = p.recentChat.size() - 1; i >= 0; i--) {
			if (p.recentChat.get(i).toLowerCase(Locale.ROOT).contains(q)) return p.recentChat.get(i);
		}
		return null;
	}

	// ---- Actions ----

	private void setVerdict(Profile profile, Profile.Verdict verdict) {
		Profile.Verdict before = profile.verdict;
		boolean wasAuto = profile.autoListed;
		Profile.Verdict target = before == verdict ? Profile.Verdict.NONE : verdict;
		mod.ledger.setVerdict(profile, target, false, null);
		mod.ledger.save(false);
		cachedResults = null;
		String message = target == Profile.Verdict.NONE
				? "Took " + profile.name + " off your " + verdictWord(before) + " list"
				: "Added " + profile.name + " to your " + verdictWord(target) + " list";
		flash(message, target == Profile.Verdict.NONE ? MUTED : verdictColor(target), () -> {
			mod.ledger.setVerdict(profile, before, wasAuto, wasAuto ? "undo" : null);
			mod.ledger.save(false);
			cachedResults = null;
			flash("Undone", MUTED, null);
		});
	}

	private void flash(String message, int color, Runnable undo) {
		banner = message;
		bannerColor = color;
		bannerUndo = undo;
		bannerUntil = System.currentTimeMillis() + 6000;
	}

	private void open(Profile profile) {
		selected = profile;
		String q = query.toLowerCase(Locale.ROOT);
		section = !q.isEmpty() && !profile.name.toLowerCase(Locale.ROOT).contains(q) && !profile.detailsContain(q)
				&& chatMatch(profile, q) != null ? Section.CHAT : Section.DETAILS;
		expanded.clear();
		scroll = 0;
		confirmForget = false;
		detailField.setText("");
		editing = null;
		setFocused(null);
	}

	private void back() {
		selected = null;
		scroll = 0;
		cachedResults = null;
		setFocused(null);
	}

	/** The Friends tab, showing one circle. */
	private void showCircle(Friends.Circle c) {
		showTab(Tab.FRIENDS);
		friendCircle = c == null ? null : c.id;
	}

	private void showTab(Tab t) {
		tab = t;
		lastTab = t == Tab.SETTINGS ? lastTab : t;
		openGroup = null;
		confirmDeleteGroup = false;
		if (t == Tab.CHAT) chatScroll = 0;
		selected = null;
		scroll = 0;
		cachedResults = null;
		setFocused(null);
	}

	/** Adds someone by name who isn't in the lists yet. */
	private Profile addByName(String name) {
		Profile profile = mod.ledger.addByName(client, name);
		cachedResults = null;
		return profile;
	}

	// ---- Drawing helpers ----

	private static SkinTextures skin(NaughtyOrNiceClient mod, net.minecraft.client.MinecraftClient client, UUID id) {
		if (client.getNetworkHandler() != null) {
			PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(id);
			if (entry != null) {
				SkinTextures textures = entry.getSkinTextures();
				SKINS.put(id, textures);
				return textures;
			}
		}
		SkinTextures known = SKINS.get(id);
		return known != null ? known : DefaultSkinHelper.getSkinTextures(id);
	}

	private void face(DrawContext context, UUID id, int x, int y, int size) {
		PlayerSkinDrawer.draw(context, skin(mod, client, id), x, y, size);
	}

	/** A box with slightly rounded corners. */
	private static void rounded(DrawContext context, int x, int y, int w, int h, int color) {
		context.fill(x + 2, y, x + w - 2, y + h, color);
		context.fill(x + 1, y + 1, x + w - 1, y + h - 1, color);
		context.fill(x, y + 2, x + w, y + h - 2, color);
	}

	private static void roundedBorder(DrawContext context, int x, int y, int w, int h, int fill, int border) {
		rounded(context, x, y, w, h, border);
		rounded(context, x + 1, y + 1, w - 2, h - 2, fill);
	}

	private boolean over(int mouseX, int mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	private void hit(int x, int y, int w, int h, String label, Runnable run) {
		int y0 = Math.max(y, BODY);
		if (y + h > y0) hits.add(new Hit(x, y0, x + w, y + h, label, run));
	}

	/** A store-style pill button. */
	private void pill(DrawContext context, int mouseX, int mouseY, int x, int y, int w, int h, String label, int fill, int edge, int textColor, String hitLabel, Runnable run) {
		boolean hover = over(mouseX, mouseY, x, y, w, h) && mouseY >= BODY;
		int f = hover ? darken(fill) : fill;
		roundedBorder(context, x, y, w, h, f, edge);
		String text = fit(label, w - 6);
		context.drawText(textRenderer, text, x + (w - textRenderer.getWidth(text)) / 2, y + (h - 8) / 2, textColor, false);
		hit(x, y, w, h, hitLabel, run);
	}

	private static int darken(int color) {
		int r = (color >> 16 & 0xFF) * 92 / 100;
		int g = (color >> 8 & 0xFF) * 92 / 100;
		int b = (color & 0xFF) * 92 / 100;
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/** Five stars, filled to {@code stars}. Returns the width drawn. */
	private int stars(DrawContext context, int x, int y, int stars) {
		int cx = x;
		for (int i = 0; i < 5; i++) {
			context.drawText(textRenderer, "★", cx, y, i < stars ? STAR : STAR_EMPTY, false);
			cx += textRenderer.getWidth("★");
		}
		return cx - x;
	}

	private void text(DrawContext context, String s, int x, int y, int color) {
		context.drawText(textRenderer, s, x, y, color, false);
	}

	private void textTrim(DrawContext context, String s, int x, int y, int maxWidth, int color) {
		context.drawText(textRenderer, fit(s, maxWidth), x, y, color, false);
	}

	/** The text cut to fit {@code maxWidth}, ending in "…" if anything was cut. */
	private String fit(String s, int maxWidth) {
		if (textRenderer.getWidth(s) <= maxWidth) return s;
		return textRenderer.trimToWidth(s, Math.max(0, maxWidth - textRenderer.getWidth("…"))).stripTrailing() + "…";
	}

	private void big(DrawContext context, String s, int x, int y, int color, float scale) {
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(x, y);
		context.getMatrices().scale(scale, scale);
		context.drawText(textRenderer, s, 0, 0, color, false);
		context.getMatrices().popMatrix();
	}

	static String ago(long time) {
		if (time <= 0) return "never";
		long s = (System.currentTimeMillis() - time) / 1000;
		if (s < 60) return "just now";
		if (s < 3600) return s / 60 + "m ago";
		if (s < 86400) return s / 3600 + "h ago";
		return s / 86400 + "d ago";
	}

	static String duration(int seconds) {
		if (seconds < 60) return seconds + "s";
		if (seconds < 3600) return seconds / 60 + "m";
		return seconds / 3600 + "h " + seconds % 3600 / 60 + "m";
	}

	private String whereabouts(Profile p) {
		PlayerEntity player = entity(p);
		if (player != null && client.player != null) return "Here now · " + (int) player.distanceTo(client.player) + " blocks away";
		if (isOnline(p)) return "Online now";
		return p.lastSeen <= 0 ? "Not seen yet" : "Seen " + ago(p.lastSeen);
	}

	/** The most telling thing about them, for the card. */
	private String headline(Profile p) {
		if (!query.isEmpty() && !p.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
			if (p.pastNamesContain(query.toLowerCase(Locale.ROOT))) return "Formerly " + String.join(", ", p.pastNames);
			String said = chatMatch(p, query.toLowerCase(Locale.ROOT));
			if (said != null) return "Said: \"" + said + "\"";
		}
		List<String> bad = new ArrayList<>();
		if (p.count(Profile.Kind.KILLED_YOU) > 0) bad.add("Killed you ×" + p.count(Profile.Kind.KILLED_YOU));
		if (p.count(Profile.Kind.BROKE_IN_BASE) > 0) bad.add("Broke your base ×" + p.count(Profile.Kind.BROKE_IN_BASE));
		if (p.count(Profile.Kind.HIT_YOU) > 0) bad.add("Hit you ×" + p.count(Profile.Kind.HIT_YOU));
		if (p.count(Profile.Kind.KILLED_PET) + p.count(Profile.Kind.HURT_PET) > 0) bad.add("Hurt your pets");
		int kills = p.count(Profile.Kind.KILLED_PLAYER);
		if (kills > 0) bad.add("Killed " + kills + (kills == 1 ? " player" : " players"));
		if (!bad.isEmpty()) return String.join(" · ", bad);
		Profile.Detail latest = p.latestDetail();
		if (latest != null) return "✎ " + latest.text + (p.details.size() > 1 ? " (+" + (p.details.size() - 1) + " more)" : "");
		if (p.secondsNear >= 60) return "Near you for " + duration(p.secondsNear);
		return "";
	}

	// ---- Rendering ----

	@Override
	public void renderBackground(DrawContext context, int realMouseX, int realMouseY, float delta) {
		// The game draws this separately from render(), so it gets the same scaling here.
		context.getMatrices().pushMatrix();
		context.getMatrices().scale(uiScale, uiScale);
		renderPage(context, (int) Math.floor(scaled(realMouseX)), (int) Math.floor(scaled(realMouseY)));
		context.getMatrices().popMatrix();
	}

	private void renderPage(DrawContext context, int mouseX, int mouseY) {
		hits.clear();
		// Under the panels, nothing reacts to the mouse.
		if (modal()) {
			mouseX = -1000;
			mouseY = -1000;
		}
		context.fill(0, 0, width, height, PAGE);
		if (tab == Tab.SETTINGS) renderSettings(context, mouseX, mouseY);
		else if (selected != null) renderDetail(context, mouseX, mouseY);
		else if (tab == Tab.CHAT) renderChat(context, mouseX, mouseY);
		else if (tab == Tab.GROUPS && openGroup == null) renderGroups(context, mouseX, mouseY);
		else renderList(context, mouseX, mouseY);
		renderHeader(context, mouseX, mouseY);
		renderBanner(context, mouseX, mouseY);
	}

	@Override
	public void render(DrawContext context, int realMouseX, int realMouseY, float delta) {
		int mouseX = (int) Math.floor(scaled(realMouseX)), mouseY = (int) Math.floor(scaled(realMouseY));
		var matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.scale(uiScale, uiScale);
		// The detail box shows only on a player's Details section, while it's on screen below the header.
		detailField.setVisible(selected != null && tab != Tab.SETTINGS && section == Section.DETAILS
				&& detailFieldTop >= BODY && detailFieldTop + 16 <= height);
		chatField.setVisible(selected == null && tab == Tab.CHAT);
		alertField.setVisible(tab == Tab.SETTINGS && alertFieldTop >= BODY && alertFieldTop + 16 <= height);
		circleField.setVisible(tab == Tab.SETTINGS && circleFieldTop >= BODY && circleFieldTop + 16 <= height);
		boolean modal = modal();
		super.render(context, modal ? -1000 : mouseX, modal ? -1000 : mouseY, delta);
		if (adding) {
			// A new layer, so the panel covers the text and boxes underneath.
			context.createNewRootLayer();
			renderAddPanel(context, mouseX, mouseY);
			addField.render(context, mouseX, mouseY, delta);
			addNote.render(context, mouseX, mouseY, delta);
			addGroup.render(context, mouseX, mouseY, delta);
		}
		if (grouping) {
			context.createNewRootLayer();
			renderGroupPanel(context, mouseX, mouseY);
			if (groupMode == GroupMode.RENAME || groupPanelGroup == null) groupField.render(context, mouseX, mouseY, delta);
			if (groupMode == GroupMode.ADD) membersField.render(context, mouseX, mouseY, delta);
		}
		if (sharing) {
			context.createNewRootLayer();
			renderSharePanel(context, mouseX, mouseY);
		}
		if (checking) {
			context.createNewRootLayer();
			renderCheckPanel(context, mouseX, mouseY);
		}
		if (circleMenu != null) {
			context.createNewRootLayer();
			renderCircleMenu(context, mouseX, mouseY);
		}
		matrices.popMatrix();
	}

	/** A panel is open over the page. */
	private boolean modal() {
		return adding || sharing || grouping || checking || circleMenu != null;
	}

	private void renderHeader(DrawContext context, int mouseX, int mouseY) {
		context.fill(0, 0, width, HEADER, NAV);
		// Logo with the smile underneath.
		int x = 8;
		text(context, "naughty", x, 7, WHITE);
		int amp = x + textRenderer.getWidth("naughty");
		text(context, "&", amp, 7, ORANGE);
		text(context, "nice", amp + textRenderer.getWidth("&"), 7, WHITE);
		int smileW = textRenderer.getWidth("naughty&nice") - 6;
		for (int i = 0; i <= smileW; i++) {
			int dy = (int) Math.round(2.5 * Math.sin(Math.PI * i / smileW));
			context.fill(x + 3 + i, 17 + dy, x + 4 + i, 18 + dy, ORANGE);
		}
		context.fill(x + 1 + smileW, 15, x + 3 + smileW, 16, ORANGE);
		context.fill(x + 3 + smileW, 15, x + 4 + smileW, 18, ORANGE);

		// Search box with the orange button.
		int left = logoRight() + 8;
		int right = themeLeft() - 6;
		rounded(context, left, 5, right - left, 16, search.isFocused() ? ORANGE : FIELD);
		rounded(context, left + (search.isFocused() ? 1 : 0), 5 + (search.isFocused() ? 1 : 0), right - left - (search.isFocused() ? 2 : 0) - 22, 16 - (search.isFocused() ? 2 : 0), CARD);
		context.fill(right - 22, 5 + (search.isFocused() ? 1 : 0), right - (search.isFocused() ? 1 : 0), 21 - (search.isFocused() ? 1 : 0), SEARCH_BUTTON);
		magnifier(context, right - 15, 9);
		hits.add(new Hit(right - 22, 5, right, 21, "search", () -> setFocused(search)));

		// Light/dark switch.
		int themeX = themeLeft();
		boolean themeHover = over(mouseX, mouseY, themeX, 5, 16, 16);
		if (themeHover) context.drawStrokedRectangle(themeX, 5, 16, 16, WHITE);
		String glyph = NNConfig.INSTANCE.darkMode ? "☀" : "☾";
		text(context, glyph, themeX + (16 - textRenderer.getWidth(glyph)) / 2 + 1, 9, NNConfig.INSTANCE.darkMode ? 0xFFFFD866 : 0xFFDDE3EA);
		hits.add(new Hit(themeX, 5, themeX + 16, 21, "theme", this::toggleTheme));

		// "Friends / 4 people", "Nice list / 3 people"..., like the account and cart links.
		int cw = counterWidth();
		int cx = countersLeft();
		counter(context, mouseX, mouseY, cx, "Friends", Tab.FRIENDS, 0xFF8AB4FF);
		counter(context, mouseX, mouseY, cx + cw + 6, "Nice list", Tab.NICE, 0xFF7CE0A0);
		counter(context, mouseX, mouseY, cx + 2 * (cw + 6), "Look Into", Tab.LOOK, 0xFFFFC14D);
		counter(context, mouseX, mouseY, cx + 3 * (cw + 6), "Naughty list", Tab.NAUGHTY, 0xFFFF8A80);

		// Tabs.
		context.fill(0, HEADER, width, BODY, SUBNAV);
		int tx = 6;
		boolean compact = false;
		int total = 0;
		for (Tab t : Tab.values()) total += textRenderer.getWidth(tabLabel(t, false)) + 10;
		if (total > width - 12) compact = true;
		for (Tab t : Tab.values()) {
			String label = tabLabel(t, compact);
			int w = textRenderer.getWidth(label) + 8;
			boolean active = t == tab && (selected == null || t == Tab.SETTINGS);
			if (t == Tab.CHAT && !active && mod.chatLogger.feedVersion() != chatSeen && !mod.chatLogger.feed().isEmpty()) label += " •";
			boolean hover = over(mouseX, mouseY, tx, HEADER + 1, w, TABS - 2);
			if (active || hover) context.drawStrokedRectangle(tx, HEADER + 1, w, TABS - 2, active ? WHITE : 0xFFAAAAAA);
			text(context, label, tx + 4, HEADER + 4, WHITE);
			hits.add(new Hit(tx, HEADER, tx + w, BODY, "tab:" + t.name(), () -> showTab(t)));
			tx += w + 2;
		}
	}

	private String tabLabel(Tab t, boolean compact) {
		if (compact) {
			String shortLabel = switch (t) {
				case ALL -> "All";
				case NEARBY -> "Near";
				case ONLINE -> "Online";
				case OFFLINE -> "Offline";
				case FRIENDS -> "Friends";
				case NICE -> "Nice";
				case NAUGHTY -> "Naughty";
				case LOOK -> "Look";
				case GROUPS -> "Groups";
				case CHAT -> "Chat";
				case SETTINGS -> "Settings";
			};
			return t == Tab.ALL ? "☰ All" : shortLabel;
		}
		int n = count(t);
		String label = t == Tab.ALL ? "☰ All" : t.label;
		return n > 0 && (t == Tab.LOOK || t == Tab.NEARBY || t == Tab.GROUPS || t == Tab.FRIENDS) ? label + " (" + n + ")" : label;
	}

	private void counter(DrawContext context, int mouseX, int mouseY, int x, String label, Tab target, int color) {
		int w = counterWidth();
		boolean hover = over(mouseX, mouseY, x - 2, 3, w, 20);
		if (hover) context.drawStrokedRectangle(x - 2, 3, w, 20, WHITE);
		int n = target == Tab.LOOK ? mod.ledger.onList(Profile.Verdict.LOOK).size() : count(target);
		text(context, label, x, 4, 0xFFCCCCCC);
		context.drawText(textRenderer, Text.literal(n + (n == 1 ? " person" : " people")).styled(s -> s.withBold(true)), x, 14, color, false);
		hits.add(new Hit(x - 2, 3, x - 2 + w, 23, "counter:" + target.name(), () -> showTab(target)));
	}

	private static void magnifier(DrawContext context, int x, int y) {
		int c = NAV;
		context.fill(x + 1, y, x + 5, y + 1, c);
		context.fill(x + 1, y + 5, x + 5, y + 6, c);
		context.fill(x, y + 1, x + 1, y + 5, c);
		context.fill(x + 5, y + 1, x + 6, y + 5, c);
		context.fill(x + 5, y + 5, x + 7, y + 7, c);
		context.fill(x + 6, y + 6, x + 8, y + 8, c);
	}

	private void renderBanner(DrawContext context, int mouseX, int mouseY) {
		if (banner == null || System.currentTimeMillis() > bannerUntil) return;
		String undo = bannerUndo != null ? "Undo" : null;
		String icon = bannerColor == NAUGHTY ? "✖ " : bannerColor == LOOK ? "? " : "✔ ";
		int extra = (undo != null ? textRenderer.getWidth(undo) + 16 : 0) + 16;
		String shown = fit(icon + banner, width - 16 - extra);
		int w = textRenderer.getWidth(shown) + extra;
		int x = (width - w) / 2;
		int y = BODY + 4;
		roundedBorder(context, x, y, w, 16, CARD, bannerColor);
		context.fill(x + 1, y + 2, x + 3, y + 14, bannerColor);
		text(context, shown, x + 8, y + 4, bannerColor);
		if (undo != null) {
			int ux = x + w - 8 - textRenderer.getWidth(undo);
			boolean hover = over(mouseX, mouseY, ux - 2, y, textRenderer.getWidth(undo) + 4, 16);
			context.drawText(textRenderer, Text.literal(undo).styled(s -> s.withUnderline(hover)), ux, y + 4, LINK, false);
			Runnable run = bannerUndo;
			hits.add(new Hit(ux - 2, y, ux + textRenderer.getWidth(undo) + 2, y + 16, "undo", () -> {
				banner = null;
				run.run();
			}));
		}
	}

	// ---- The results grid ----

	private void renderList(DrawContext context, int mouseX, int mouseY) {
		List<Profile> results = results();
		boolean sidebar = width >= 380;
		int left = sidebar ? 8 + SIDEBAR + 8 : 8;
		int right = width - 8;

		// Results bar: "12 results for "abc"" and the sort pill.
		int barY = BODY;
		context.fill(0, barY, width, barY + 16, CARD);
		context.fill(0, barY + 16, width, barY + 17, BORDER);
		String summary = results.size() + (results.size() == 1 ? " player" : " players");
		if (!query.isEmpty()) summary += " for ";
		text(context, summary, 8, barY + 4, TEXT);
		if (!query.isEmpty()) text(context, "\"" + query + "\"", 8 + textRenderer.getWidth(summary), barY + 4, HOVER_LINK);
		String sortLabel = "Sort by: " + sort.label + " ▾";
		int sw = textRenderer.getWidth(sortLabel) + 10;
		pill(context, mouseX, mouseY, right - sw, barY + 2, sw, 12, sortLabel, SOFT, BORDER, TEXT, "sort", () -> {
			sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
			cachedResults = null;
		});
		String addLabel = "+ Add players";
		int aw = textRenderer.getWidth(addLabel) + 12;
		pill(context, mouseX, mouseY, right - sw - aw - 6, barY + 2, aw, 12, addLabel, YELLOW, YELLOW_EDGE, BUTTON_TEXT, "open-add",
				() -> openAdd(listOf(tab)));
		String shareLabel = "⇄ Share";
		int shw = textRenderer.getWidth(shareLabel) + 12;
		pill(context, mouseX, mouseY, right - sw - aw - shw - 12, barY + 2, shw, 12, shareLabel, SOFT, BORDER, TEXT, "open-share",
				() -> openShare(ShareView.SEND));

		int top = barY + 17;
		context.enableScissor(0, top, width, height);
		if (sidebar) renderSidebar(context, mouseX, mouseY, top + 6 - (int) scroll);

		int gridW = right - left;
		int cols = Math.max(1, (gridW + GAP) / (CARD_W + GAP));
		int cardW = (gridW - GAP * (cols - 1)) / cols;
		int y = top + 6 - (int) scroll;
		int startY = y;

		// Someone typed a name that isn't known: offer to add them.
		String q = query;
		boolean exact = results.stream().anyMatch(p -> p.name.equalsIgnoreCase(q));
		if (tab == Tab.GROUPS && openGroup != null) {
			y = groupHeader(context, mouseX, mouseY, openGroup, left, y, gridW) + GAP;
		}
		if (tab == Tab.FRIENDS) y = circleBar(context, mouseX, mouseY, left, y, gridW) + GAP;
		if (q.matches(NAME_PATTERN) && !exact) {
			addCard(context, mouseX, mouseY, left, y, gridW, q);
			y += 30 + GAP;
		} else if (q.isEmpty() && (tab == Tab.NICE || tab == Tab.NAUGHTY || tab == Tab.LOOK || tab == Tab.FRIENDS)) {
			listAddCard(context, mouseX, mouseY, left, y, gridW, listOf(tab));
			y += 24 + GAP;
		}

		if (results.isEmpty() && !(tab == Tab.GROUPS && query.isEmpty())) {
			emptyState(context, left, y, gridW);
			y += 60;
		}
		for (int i = 0; i < results.size(); i++) {
			int col = i % cols;
			int cy = y + (i / cols) * (CARD_H + GAP);
			if (cy > height || cy + CARD_H < top) continue;
			card(context, mouseX, mouseY, results.get(i), left + col * (cardW + GAP), cy, cardW);
		}
		if (!results.isEmpty()) y += ((results.size() + cols - 1) / cols) * (CARD_H + GAP);
		context.disableScissor();
		contentHeight = Math.max(y - startY + 12, sidebar ? sidebarHeight : 0);
		scrollbar(context, top);
	}

	private void renderSidebar(DrawContext context, int mouseX, int mouseY, int y) {
		int x = 8;
		int top = y;
		context.drawText(textRenderer, Text.literal("Rating").styled(s -> s.withBold(true)), x, y, TEXT, false);
		y += 12;
		y = filterRow(context, mouseX, mouseY, x, y, 4, "& Up", rating == Rating.GOOD, () -> rating = rating == Rating.GOOD ? Rating.ANY : Rating.GOOD);
		y = filterRow(context, mouseX, mouseY, x, y, 2, "& Under", rating == Rating.BAD, () -> rating = rating == Rating.BAD ? Rating.ANY : Rating.BAD);
		y += 8;
		context.drawText(textRenderer, Text.literal("Show").styled(s -> s.withBold(true)), x, y, TEXT, false);
		y += 12;
		y = checkbox(context, mouseX, mouseY, x, y, SIDEBAR, "Online only", onlineOnly, "online only", () -> onlineOnly = !onlineOnly);
		y += 8;
		context.drawText(textRenderer, Text.literal("Your lists").styled(s -> s.withBold(true)), x, y, TEXT, false);
		y += 12;
		for (Tab t : new Tab[] {Tab.FRIENDS, Tab.NICE, Tab.LOOK, Tab.NAUGHTY}) {
			int n = t == Tab.LOOK ? mod.ledger.onList(Profile.Verdict.LOOK).size() : count(t);
			String label = t.label + " (" + n + ")";
			boolean hover = over(mouseX, mouseY, x, y - 1, SIDEBAR, 11);
			boolean active = tab == t && (t != Tab.FRIENDS || friendCircle == null);
			context.drawText(textRenderer, Text.literal(label).styled(s -> s.withUnderline(hover).withBold(active)), x, y, hover ? HOVER_LINK : LINK, false);
			int fy = y;
			hits.add(new Hit(x, Math.max(fy - 1, BODY + 17), x + SIDEBAR, fy + 10, "side:" + t.name(), () -> {
				showTab(t);
				friendCircle = null;
			}));
			y += 11;
			if (t == Tab.FRIENDS) {
				// The circles, indented, with their colour.
				for (Friends.Circle c : Friends.circles()) {
					int members = Friends.members(mod.ledger, c).size();
					String count = " " + members;
					int countW = textRenderer.getWidth(count);
					boolean cHover = over(mouseX, mouseY, x, y - 1, SIDEBAR, 11);
					boolean cActive = tab == Tab.FRIENDS && c.id.equals(friendCircle);
					context.fill(x + 6, y + 2, x + 11, y + 7, GROUP_COLORS[c.color % GROUP_COLORS.length]);
					context.drawText(textRenderer, Text.literal(fit(c.name, SIDEBAR - 16 - countW)).styled(st -> st.withUnderline(cHover).withBold(cActive)),
							x + 14, y, cHover ? HOVER_LINK : LINK, false);
					text(context, count, x + SIDEBAR - countW, y, MUTED);
					int cy = y;
					hits.add(new Hit(x, Math.max(cy - 1, BODY + 17), x + SIDEBAR, cy + 10, "side-circle:" + c.name, () -> showCircle(c)));
					y += 11;
				}
			}
		}
		boolean addHover = over(mouseX, mouseY, x, y - 1, SIDEBAR, 11);
		context.drawText(textRenderer, Text.literal("+ Add players").styled(s -> s.withBold(true).withUnderline(addHover)), x, y,
				addHover ? HOVER_LINK : LINK, false);
		int addY = y;
		hits.add(new Hit(x, Math.max(addY - 1, BODY + 17), x + SIDEBAR, addY + 10, "side-add", () -> openAdd(Profile.Verdict.NICE)));
		y += 11;
		boolean shareHover = over(mouseX, mouseY, x, y - 1, SIDEBAR, 11);
		context.drawText(textRenderer, Text.literal("⇄ Share lists").styled(s -> s.withBold(true).withUnderline(shareHover)), x, y,
				shareHover ? HOVER_LINK : LINK, false);
		int shareY = y;
		hits.add(new Hit(x, Math.max(shareY - 1, BODY + 17), x + SIDEBAR, shareY + 10, "side-share", () -> openShare(ShareView.SEND)));
		y += 11;
		y += 8;
		y = sidebarGroups(context, mouseX, mouseY, x, y);
		y += 8;
		context.drawText(textRenderer, Text.literal("Tip").styled(s -> s.withBold(true)), x, y, TEXT, false);
		y += 11;
		for (var line : textRenderer.wrapLines(Text.literal("Look at a player and press N to open their page."), SIDEBAR)) {
			context.drawText(textRenderer, line, x, y, MUTED, false);
			y += 10;
		}
		sidebarHeight = y - top + 10;
	}

	private int sidebarHeight = 190;

	/** "Groups" in the sidebar: each group with a colour dot and size, then New group. */
	private int sidebarGroups(DrawContext context, int mouseX, int mouseY, int x, int y) {
		context.drawText(textRenderer, Text.literal("Groups").styled(s -> s.withBold(true)), x, y, TEXT, false);
		y += 12;
		Map<String, List<Profile>> groups = mod.ledger.groups();
		int shown = 0;
		for (Map.Entry<String, List<Profile>> group : groups.entrySet()) {
			if (shown++ >= 8) {
				int more = groups.size() - 8;
				y = sideLink(context, mouseX, mouseY, x, y, "All " + groups.size() + " groups (+" + more + ")", false, "side-groups", () -> showTab(Tab.GROUPS));
				break;
			}
			String g = group.getKey();
			int color = groupColor(g);
			context.fill(x, y + 1, x + 7, y + 8, color);
			String count = " " + group.getValue().size();
			int countW = textRenderer.getWidth(count);
			boolean hover = over(mouseX, mouseY, x, y - 1, SIDEBAR, 11);
			boolean active = tab == Tab.GROUPS && g.equalsIgnoreCase(openGroup);
			context.drawText(textRenderer, Text.literal(fit(g, SIDEBAR - 10 - countW)).styled(s -> s.withUnderline(hover).withBold(active)), x + 10, y,
					hover ? HOVER_LINK : LINK, false);
			text(context, count, x + SIDEBAR - countW, y, MUTED);
			int fy = y;
			hits.add(new Hit(x, Math.max(fy - 1, BODY + 17), x + SIDEBAR, fy + 10, "side-group:" + g, () -> showGroup(g)));
			y += 11;
		}
		if (groups.isEmpty()) {
			for (var line : textRenderer.wrapLines(Text.literal("Group people who are teamed up or work together."), SIDEBAR)) {
				context.drawText(textRenderer, line, x, y, MUTED, false);
				y += 10;
			}
		}
		return sideLink(context, mouseX, mouseY, x, y, "+ New group", true, "side-new-group", () -> openGroupPanel(null, null));
	}

	private int sideLink(DrawContext context, int mouseX, int mouseY, int x, int y, String label, boolean bold, String hitLabel, Runnable run) {
		boolean hover = over(mouseX, mouseY, x, y - 1, SIDEBAR, 11);
		context.drawText(textRenderer, Text.literal(label).styled(s -> s.withBold(bold).withUnderline(hover)), x, y, hover ? HOVER_LINK : LINK, false);
		int fy = y;
		hits.add(new Hit(x, Math.max(fy - 1, BODY + 17), x + SIDEBAR, fy + 10, hitLabel, run));
		return y + 11;
	}

	private int filterRow(DrawContext context, int mouseX, int mouseY, int x, int y, int starCount, String suffix, boolean active, Runnable run) {
		boolean hover = over(mouseX, mouseY, x, y - 1, SIDEBAR, 11);
		int w = stars(context, x, y, starCount);
		context.drawText(textRenderer, Text.literal(" " + suffix).styled(s -> s.withBold(active).withUnderline(hover)), x + w, y,
				hover ? HOVER_LINK : TEXT, false);
		hits.add(new Hit(x, Math.max(y - 1, BODY + 17), x + SIDEBAR, y + 10, "rating:" + suffix, () -> {
			run.run();
			cachedResults = null;
		}));
		return y + 12;
	}

	private int checkbox(DrawContext context, int mouseX, int mouseY, int x, int y, int w, String label, boolean checked, String hitLabel, Runnable run) {
		boolean hover = over(mouseX, mouseY, x, y - 1, w, 11);
		roundedBorder(context, x, y, 9, 9, checked ? LINK : FIELD, checked ? LINK : INPUT_BORDER);
		if (checked) text(context, "✔", x + 1, y + 1, CARD);
		context.drawText(textRenderer, Text.literal(label).styled(s -> s.withUnderline(hover)), x + 13, y + 1, TEXT, false);
		hits.add(new Hit(x, Math.max(y - 1, BODY + 17), x + w, y + 10, hitLabel, () -> {
			run.run();
			cachedResults = null;
		}));
		return y + 12;
	}

	private void emptyState(DrawContext context, int x, int y, int w) {
		String title = switch (tab) {
			case NEARBY -> "Nobody's in sight right now.";
			case OFFLINE -> "Nobody remembered yet. Everyone you see in game or on the tab list is remembered here once they log off.";
			case FRIENDS -> friendCircle == null ? "No friends yet. Press the ☆ on anyone's card to make them a friend."
					: "Nobody in this circle yet. Press the ★ on a friend's card to move them here.";
			case NICE -> "Your Nice list is empty.";
			case NAUGHTY -> "Your Naughty list is empty.";
			case LOOK -> "Nobody to look into. Put anyone you're unsure about here with the Look Into button; players who do something good or bad show up here too.";
			case GROUPS -> "Nobody in this group matches.";
			default -> query.isEmpty() ? "No players yet." : "No players match \"" + query + "\".";
		};
		String tip = tab == Tab.NEARBY ? "Try the Online tab to see everyone on the server."
				: "Press + Add players to add anyone by name, or press Nice / Naughty on any card.";
		rounded(context, x, y, w, 50, CARD);
		int ty = y + 10;
		for (var line : textRenderer.wrapLines(Text.literal(title).styled(s -> s.withBold(true)), w - 20)) {
			context.drawText(textRenderer, line, x + 10, ty, TEXT, false);
			ty += 10;
		}
		for (var line : textRenderer.wrapLines(Text.literal(tip), w - 20)) {
			context.drawText(textRenderer, line, x + 10, ty + 2, MUTED, false);
			ty += 10;
		}
	}

	/** The list a tab adds to: its own, or Nice. */
	private static Profile.Verdict listOf(Tab t) {
		return switch (t) {
			case FRIENDS -> Profile.Verdict.FRIEND;
			case NAUGHTY -> Profile.Verdict.NAUGHTY;
			case LOOK -> Profile.Verdict.LOOK;
			default -> Profile.Verdict.NICE;
		};
	}

	/**
	 * The top of the Friends tab: a chip per circle to show just that circle (and how many are online), what the
	 * circle does, and a link to edit circles.
	 */
	private int circleBar(DrawContext context, int mouseX, int mouseY, int x, int y, int w) {
		Friends.Circle open = friendCircle == null ? null : Friends.byId(friendCircle);
		if (open == null) friendCircle = null;
		int h = 36;
		roundedBorder(context, x, y, w, h, CARD, BORDER);
		int cx = x + 6;
		int cy = y + 5;
		String edit = "Edit circles";
		int editW = textRenderer.getWidth(edit);
		int right = x + w - 12 - editW;
		List<Profile> friends = mod.ledger.onList(Profile.Verdict.FRIEND);
		cx = circleChip(context, mouseX, mouseY, cx, cy, right, "All friends " + friends.size(), FRIEND, open == null, "circle-all", () -> showCircle(null));
		for (Friends.Circle c : Friends.circles()) {
			List<Profile> members = Friends.members(mod.ledger, c);
			int on = online(members);
			String label = c.name + " " + members.size() + (on > 0 ? " · " + on + " on" : "");
			cx = circleChip(context, mouseX, mouseY, cx, cy, right, label, GROUP_COLORS[c.color % GROUP_COLORS.length], c == open, "circle:" + c.name, () -> showCircle(c));
		}
		link(context, mouseX, mouseY, x + w - 8 - editW, y + 7, edit, "edit-circles", () -> {
			showTab(Tab.SETTINGS);
			scrollToCircles = true;
		});
		String about;
		if (open == null) {
			about = "Friends are people you know, kept apart from Nice, Look Into and Naughty. Press ★ on a card to pick their circle.";
		} else {
			List<String> parts = new ArrayList<>();
			parts.add(open.trusted ? "Trusted at your bases (no Base Insurance alerts)" : "Not trusted at your bases");
			if (open.joinAlert) parts.add("pings you when they join");
			about = String.join(" · ", parts);
		}
		textTrim(context, about, x + 8, y + 23, w - 16, MUTED);
		return y + h;
	}

	/** A rounded chip for the circle bar; returns the x after it. */
	private int circleChip(DrawContext context, int mouseX, int mouseY, int x, int y, int right, String label, int color, boolean on, String id, Runnable run) {
		Text shown = Text.literal(label).styled(s -> s.withBold(on));
		int cw = textRenderer.getWidth(shown) + 18;
		if (x + cw > right) return x;
		boolean hover = over(mouseX, mouseY, x, y, cw, 13) && mouseY > BODY + 17;
		roundedBorder(context, x, y, cw, 13, on ? tint(color, CARD, 0.25f) : hover ? HOVER_BG : CARD, on ? color : hover ? HOVER_BORDER : BORDER);
		context.fill(x + 5, y + 4, x + 10, y + 9, color);
		context.drawText(textRenderer, shown, x + 13, y + 3, on ? color : TEXT, false);
		hit(x, y, cw, 13, id, run);
		return x + cw + 4;
	}

	/** "+ Add someone to your Nice list", at the top of a list tab. */
	private void listAddCard(DrawContext context, int mouseX, int mouseY, int x, int y, int w, Profile.Verdict list) {
		boolean hover = over(mouseX, mouseY, x, y, w, 24) && mouseY > BODY + 17;
		roundedBorder(context, x, y, w, 24, hover ? HOVER_BG : CARD, hover ? HOVER_BORDER : BORDER);
		for (int dx = x + 4; dx < x + w - 4; dx += 6) {
			context.fill(dx, y + 3, dx + 3, y + 4, BORDER);
			context.fill(dx, y + 20, dx + 3, y + 21, BORDER);
		}
		String label = list == Profile.Verdict.FRIEND
				? "+ Add friends" + (friendCircle != null && Friends.byId(friendCircle) != null ? " to " + Friends.byId(friendCircle).name : "")
				: "+ Add someone to your " + verdictWord(list) + " list";
		context.drawText(textRenderer, Text.literal(label).styled(s -> s.withBold(true).withUnderline(hover)),
				x + (w - textRenderer.getWidth(Text.literal(label).styled(s -> s.withBold(true)))) / 2, y + 8, LINK, false);
		hit(x, y, w, 24, "list-add", () -> openAdd(list));
	}

	private void addCard(DrawContext context, int mouseX, int mouseY, int x, int y, int w, String name) {
		roundedBorder(context, x, y, w, 30, CARD, BORDER);
		face(context, NaughtyOrNiceClient.offlineId(name), x + 7, y + 7, 16);
		text(context, "Add ", x + 30, y + 11, TEXT);
		context.drawText(textRenderer, Text.literal(name).styled(s -> s.withBold(true)), x + 30 + textRenderer.getWidth("Add "), y + 11, TEXT, false);
		int bw = Math.min(80, (w - 40 - textRenderer.getWidth("Add " + name)) / 3 - 6);
		if (bw < 36) return;
		int bx = x + w - 3 * bw - 16;
		pill(context, mouseX, mouseY, bx, y + 8, bw, 14, "✔ Nice", YELLOW, YELLOW_EDGE, BUTTON_TEXT, "add-nice", () -> setVerdict(addByName(name), Profile.Verdict.NICE));
		pill(context, mouseX, mouseY, bx + bw + 4, y + 8, bw, 14, firstFit(bw - 6, "? Look Into", "Look Into", "Look"), SOFT, LOOK, TEXT, "add-look",
				() -> setVerdict(addByName(name), Profile.Verdict.LOOK));
		pill(context, mouseX, mouseY, bx + 2 * (bw + 4), y + 8, bw, 14, "✖ Naughty", ORANGE, ORANGE_EDGE, BUTTON_TEXT, "add-naughty", () -> setVerdict(addByName(name), Profile.Verdict.NAUGHTY));
	}

	private void card(DrawContext context, int mouseX, int mouseY, Profile p, int x, int y, int w) {
		boolean hover = over(mouseX, mouseY, x, y, w, CARD_H) && mouseY > BODY + 17;
		roundedBorder(context, x, y, w, CARD_H, CARD, hover ? HOVER_BORDER : BORDER);
		hit(x, y, w, CARD_H, "card:" + p.name, () -> open(p));

		// "Product image".
		context.fill(x + 5, y + 5, x + 43, y + 43, IMAGE_BG);
		face(context, p.id, x + 8, y + 8, 32);
		if (isOnline(p)) {
			context.fill(x + 35, y + 35, x + 42, y + 42, CARD);
			context.fill(x + 36, y + 36, x + 41, y + 41, 0xFF2EB82E);
		}

		int tx = x + 48;
		int tw = w - 52 - 14;
		friendStar(context, mouseX, mouseY, p, x + w - 16, y + 4);
		boolean nameHover = over(mouseX, mouseY, tx, y + 5, textRenderer.getWidth(p.name), 9);
		String name = textRenderer.getWidth(p.name) > tw ? textRenderer.trimToWidth(p.name, tw - 6) + "..." : p.name;
		context.drawText(textRenderer, Text.literal(name).styled(s -> s.withUnderline(nameHover)), tx, y + 6, nameHover ? HOVER_LINK : LINK, false);
		int sw = stars(context, tx, y + 17, p.stars());
		int rated = p.ratedActions();
		text(context, rated == 0 ? " New" : " " + rated, tx + sw, y + 17, LINK);

		// List status, or our suggestion, then their groups.
		int statusEnd;
		if (p.verdict == Profile.Verdict.FRIEND) {
			int color = GROUP_COLORS[Friends.color(p) % GROUP_COLORS.length];
			Text status = Text.literal("★ " + fit(Friends.circleName(p), Math.max(30, tw / 2))).styled(s -> s.withBold(true));
			context.drawText(textRenderer, status, tx, y + 28, color, false);
			statusEnd = tx + textRenderer.getWidth(status);
		} else if (p.verdict != Profile.Verdict.NONE) {
			Text status = Text.literal(verdictMark(p.verdict) + verdictWord(p.verdict) + " list").styled(s -> s.withBold(true));
			context.drawText(textRenderer, status, tx, y + 28, verdictColor(p.verdict), false);
			statusEnd = tx + textRenderer.getWidth(status);
		} else if (p.suggestion() != Profile.Verdict.NONE) {
			statusEnd = choiceBadge(context, tx, y + 27, p.suggestion());
		} else {
			text(context, p.groups.isEmpty() ? "Not on a list" : "No list", tx, y + 28, MUTED);
			statusEnd = tx + textRenderer.getWidth(p.groups.isEmpty() ? "Not on a list" : "No list");
		}
		groupChips(context, p, statusEnd + 5, y + 27, x + w - 5);
		// On a group's page, a way to take them out of it.
		if (tab == Tab.GROUPS && openGroup != null && hover) {
			int rx = x + w - 13;
			boolean xHover = over(mouseX, mouseY, rx - 2, y + 3, 12, 12);
			rounded(context, rx - 2, y + 3, 12, 12, xHover ? BAD_BG : SOFT);
			text(context, "✖", rx, y + 5, xHover ? NAUGHTY : MUTED);
			String g = openGroup;
			hit(rx - 2, y + 3, 12, 12, "ungroup:" + p.name, () -> leaveGroup(p, g));
		}
		String headline = headline(p);
		boolean bad = p.score() < 0 && !headline.startsWith("\"") && !headline.startsWith("Said:");
		textTrim(context, headline.isEmpty() ? whereabouts(p) : headline, tx, y + 39, tw + 14, headline.isEmpty() ? MUTED : bad ? NAUGHTY : MUTED);

		// The three big buttons: Nice, Look Into, Naughty.
		int by = y + CARD_H - 20;
		int bw = (w - 20) / 3;
		verdictButton(context, mouseX, mouseY, p, Profile.Verdict.NICE, x + 6, by, bw);
		verdictButton(context, mouseX, mouseY, p, Profile.Verdict.LOOK, x + 10 + bw, by, bw);
		verdictButton(context, mouseX, mouseY, p, Profile.Verdict.NAUGHTY, x + 14 + 2 * bw, by, w - 20 - 2 * bw);
	}

	/**
	 * The star in a card's corner, like a store's wish-list heart: makes them a friend, or (for a friend) opens the
	 * circle picker.
	 */
	private void friendStar(DrawContext context, int mouseX, int mouseY, Profile p, int x, int y) {
		boolean friend = p.verdict == Profile.Verdict.FRIEND;
		boolean hover = over(mouseX, mouseY, x, y, 12, 12) && mouseY > BODY + 17;
		int color = friend ? GROUP_COLORS[Friends.color(p) % GROUP_COLORS.length] : hover ? FRIEND : STAR_EMPTY;
		if (hover || friend) rounded(context, x, y, 12, 12, friend ? tint(color, CARD, 0.18f) : FRIEND_BG);
		String glyph = friend || hover ? "★" : "☆";
		text(context, glyph, x + (12 - textRenderer.getWidth(glyph)) / 2 + 1, y + 2, color);
		hit(x, y, 12, 12, "star:" + p.name, () -> starClicked(p, x - 80, y + 13));
	}

	/** Not a friend yet: they become one (in the circle being looked at, or the first). A friend: pick their circle. */
	private void starClicked(Profile p, int menuX, int menuY) {
		if (p.verdict == Profile.Verdict.FRIEND) {
			openCircleMenu(p, menuX, menuY);
			return;
		}
		Friends.Circle circle = tab == Tab.FRIENDS && friendCircle != null && Friends.byId(friendCircle) != null ? Friends.byId(friendCircle) : Friends.circles().get(0);
		makeFriend(p, circle);
	}

	/** Puts them in a circle, with undo; the banner offers to pick another circle. */
	private void makeFriend(Profile p, Friends.Circle circle) {
		Profile.Verdict before = p.verdict;
		String beforeCircle = p.circle;
		boolean wasAuto = p.autoListed;
		mod.ledger.setCircle(p, circle);
		mod.ledger.save(false);
		cachedResults = null;
		flash((before == Profile.Verdict.FRIEND ? "Moved " + p.name + " to " : "Added " + p.name + " to Friends · ") + circle.name, FRIEND, () -> {
			p.circle = beforeCircle;
			if (before == Profile.Verdict.FRIEND) mod.ledger.setCircle(p, Friends.of(p));
			else mod.ledger.setVerdict(p, before, wasAuto, "undo");
			BaseInsuranceBridge.syncFriend(p);
			mod.ledger.save(false);
			cachedResults = null;
			flash("Undone", MUTED, null);
		});
	}

	private void openCircleMenu(Profile p, int x, int y) {
		circleMenu = p;
		circleMenuX = x;
		circleMenuY = y;
	}

	/** The circle picker: each circle (tick on theirs), "Not a friend", and a way to edit circles. */
	private void renderCircleMenu(DrawContext context, int mouseX, int mouseY) {
		menuHits.clear();
		Profile p = circleMenu;
		List<Friends.Circle> circles = Friends.circles();
		int w = 0;
		for (Friends.Circle c : circles) w = Math.max(w, textRenderer.getWidth(Text.literal(c.name).styled(st -> st.withBold(true))));
		w = Math.max(w + 34, textRenderer.getWidth("Edit circles…") + 24);
		int rowH = 13;
		int h = 16 + (circles.size() + 2) * rowH + 6;
		int x = Math.max(4, Math.min(circleMenuX, width - w - 4));
		int y = Math.max(BODY, Math.min(circleMenuY, height - h - 4));
		context.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x40000000);
		roundedBorder(context, x, y, w, h, CARD, HOVER_BORDER);
		context.drawText(textRenderer, Text.literal(fit(p.name, w - 12)).styled(s -> s.withBold(true)), x + 6, y + 5, TEXT, false);
		int ry = y + 16;
		Friends.Circle current = Friends.of(p);
		for (Friends.Circle c : circles) {
			boolean hover = over(mouseX, mouseY, x + 2, ry, w - 4, rowH);
			if (hover) context.fill(x + 2, ry, x + w - 2, ry + rowH, HOVER_BG);
			int color = GROUP_COLORS[c.color % GROUP_COLORS.length];
			context.fill(x + 6, ry + 4, x + 11, ry + 9, color);
			context.drawText(textRenderer, Text.literal(c.name).styled(s -> s.withBold(c == current)), x + 15, ry + 3, c == current ? color : TEXT, false);
			if (c == current) text(context, "✔", x + w - 12, ry + 3, color);
			menuHits.add(new Hit(x + 2, ry, x + w - 2, ry + rowH, "circle-pick:" + c.name, () -> {
				circleMenu = null;
				makeFriend(p, c);
			}));
			ry += rowH;
		}
		context.fill(x + 4, ry + 1, x + w - 4, ry + 2, BORDER);
		boolean offHover = over(mouseX, mouseY, x + 2, ry + 2, w - 4, rowH);
		if (offHover) context.fill(x + 2, ry + 2, x + w - 2, ry + 2 + rowH, HOVER_BG);
		text(context, current != null ? "Not a friend" : "Close", x + 15, ry + 5, current != null ? NAUGHTY : MUTED);
		menuHits.add(new Hit(x + 2, ry + 2, x + w - 2, ry + 2 + rowH, "circle-off", () -> {
			circleMenu = null;
			if (p.verdict == Profile.Verdict.FRIEND) setVerdict(p, Profile.Verdict.FRIEND);
		}));
		ry += rowH + 2;
		boolean editHover = over(mouseX, mouseY, x + 2, ry, w - 4, rowH);
		if (editHover) context.fill(x + 2, ry, x + w - 2, ry + rowH, HOVER_BG);
		text(context, "Edit circles…", x + 15, ry + 3, LINK);
		menuHits.add(new Hit(x + 2, ry, x + w - 2, ry + rowH, "circle-edit", () -> {
			circleMenu = null;
			showTab(Tab.SETTINGS);
			scrollToCircles = true;
		}));
	}

	/** Their groups as small coloured chips from {@code x}, as many as fit before {@code right} (then "+n"). */
	private int groupChips(DrawContext context, Profile p, int x, int y, int right) {
		int cx = x;
		for (int i = 0; i < p.groups.size(); i++) {
			String g = p.groups.get(i);
			String more = i < p.groups.size() - 1 ? " +" + (p.groups.size() - 1 - i) : "";
			int moreW = more.isEmpty() ? 0 : textRenderer.getWidth(more) + 2;
			int cw = Math.min(textRenderer.getWidth(g) + 6, right - cx - moreW);
			if (cw < 24) {
				text(context, "+" + (p.groups.size() - i), cx, y + 1, MUTED);
				return cx + textRenderer.getWidth("+" + (p.groups.size() - i));
			}
			groupChip(context, g, cx, y, cw);
			cx += cw + 3;
		}
		return cx;
	}

	/** A group's name in a chip of its colour. */
	private void groupChip(DrawContext context, String g, int x, int y, int w) {
		int color = groupColor(g);
		roundedBorder(context, x, y, w, 11, tint(color, CARD, 0.16f), tint(color, CARD, 0.55f));
		text(context, fit(g, w - 6), x + 3, y + 2, color);
	}

	/** The first label that fits in {@code width}, or the last one cut to fit. */
	private String firstFit(int width, String... labels) {
		for (String label : labels) {
			if (textRenderer.getWidth(label) <= width) return label;
		}
		return labels[labels.length - 1];
	}

	/** "Our pick: Naughty", in the style of a store's pick-of-the-category badge. Returns the x after it. */
	private int choiceBadge(DrawContext context, int x, int y, Profile.Verdict suggestion) {
		String a = "Our pick";
		String b = suggestion == Profile.Verdict.NAUGHTY ? "Naughty" : "Nice";
		int aw = textRenderer.getWidth(a) + 6;
		int bw = textRenderer.getWidth(b) + 6;
		context.fill(x, y, x + aw, y + 11, BADGE);
		text(context, a, x + 3, y + 2, WHITE);
		context.fill(x + aw, y, x + aw + bw, y + 11, suggestion == Profile.Verdict.NAUGHTY ? BAD_BG : GOOD_BG);
		text(context, b, x + aw + 3, y + 2, suggestion == Profile.Verdict.NAUGHTY ? NAUGHTY : NICE);
		return x + aw + bw;
	}

	private void verdictButton(DrawContext context, int mouseX, int mouseY, Profile p, Profile.Verdict v, int x, int y, int w) {
		String id = switch (v) {
			case NICE -> "nice:";
			case LOOK -> "look:";
			default -> "naughty:";
		} + p.name;
		int room = w - 6;
		Runnable run = () -> setVerdict(p, v);
		if (p.verdict == v) {
			// On this list: an outlined button in the list's colour; clicking takes them off.
			String label = switch (v) {
				case NICE -> firstFit(room, "✔ On Nice list", "✔ On Nice", "✔ Nice");
				case LOOK -> firstFit(room, "? Looking into", "? Look Into", "? Look");
				default -> firstFit(room, "✖ On Naughty", "✖ Naughty", "Naughty");
			};
			pill(context, mouseX, mouseY, x, y, w, 14, label, v == Profile.Verdict.LOOK ? LOOK_BG : CARD, verdictColor(v), verdictColor(v), id, run);
		} else if (v == Profile.Verdict.NICE) {
			pill(context, mouseX, mouseY, x, y, w, 14, firstFit(room, "✔ Nice", "Nice"), YELLOW, YELLOW_EDGE, BUTTON_TEXT, id, run);
		} else if (v == Profile.Verdict.LOOK) {
			pill(context, mouseX, mouseY, x, y, w, 14, firstFit(room, "? Look Into", "Look Into", "Look"), SOFT, LOOK, TEXT, id, run);
		} else {
			pill(context, mouseX, mouseY, x, y, w, 14, firstFit(room, "✖ Naughty", "Naughty"), ORANGE, ORANGE_EDGE, BUTTON_TEXT, id, run);
		}
	}

	private void scrollbar(DrawContext context, int top) {
		double max = maxScroll();
		if (max <= 0) return;
		int view = height - top;
		int bar = Math.max(20, (int) (view * (double) view / (view + max)));
		int barY = top + (int) ((view - bar) * scroll / max);
		context.fill(width - 4, top, width - 1, height, 0x20000000);
		context.fill(width - 4, barY, width - 1, barY + bar, INPUT_BORDER);
	}

	private double maxScroll() {
		if (selected == null && tab == Tab.CHAT) return chatMaxScroll;
		int top = selected != null || tab == Tab.SETTINGS ? BODY : BODY + 17;
		return Math.max(0, contentHeight - (height - top));
	}

	// ---- One player's page ----

	private void renderDetail(DrawContext context, int mouseX, int mouseY) {
		Profile p = selected;
		int boxW = width >= 340 ? BUY_BOX : 0;
		int boxX = width - 8 - boxW;
		int left = 8;
		int right = boxW > 0 ? boxX - 10 : width - 8;
		int y0 = BODY + 6 - (int) scroll;

		context.enableScissor(0, BODY, boxW > 0 ? boxX - 4 : width, height);
		int y = y0;
		// Breadcrumb.
		boolean backHover = over(mouseX, mouseY, left, y, textRenderer.getWidth("‹ Back to results"), 10);
		context.drawText(textRenderer, Text.literal("‹ Back to results").styled(s -> s.withUnderline(backHover)), left, y, backHover ? HOVER_LINK : LINK, false);
		hit(left, y - 1, textRenderer.getWidth("‹ Back to results"), 11, "back", this::back);
		y += 14;

		// Big face.
		int img = width >= 400 ? 72 : 48;
		rounded(context, left, y, img + 12, img + 12, CARD);
		face(context, p.id, left + 6, y + 6, img);
		text(context, isOnline(p) ? "● Online" : "○ Offline", left, y + img + 16, isOnline(p) ? 0xFF2EB82E : MUTED);

		int cx = left + img + 22;
		int cw = right - cx;
		int ty = y;
		big(context, textRenderer.trimToWidth(p.name, (int) (cw / 1.6)), cx, ty, TEXT, 1.6f);
		ty += 17;
		String formerly = p.pastNames.isEmpty() ? "" : " · Formerly " + String.join(", ", p.pastNames);
		textTrim(context, whereabouts(p) + formerly + " · First seen " + ago(p.firstSeen), cx, ty, cw, MUTED);
		ty += 12;
		int sw = stars(context, cx, ty, p.stars());
		int rated = p.ratedActions();
		text(context, " " + (rated == 0 ? "No ratings yet" : rated + (rated == 1 ? " rating" : " ratings")), cx + sw, ty, LINK);
		ty += 12;
		ty = detailGroups(context, mouseX, mouseY, p, cx, ty, right);
		context.fill(cx, ty, right, ty + 1, BORDER);
		ty += 6;

		// The "price": their score.
		int score = p.score();
		String scoreText = (score > 0 ? "+" : "") + score;
		text(context, "Trust score", cx, ty + 4, MUTED);
		big(context, scoreText, cx + textRenderer.getWidth("Trust score") + 6, ty, score < 0 ? NAUGHTY : score > 0 ? NICE : TEXT, 1.6f);
		ty += 18;
		if (p.verdict == Profile.Verdict.NONE && p.suggestion() != Profile.Verdict.NONE) {
			choiceBadge(context, cx, ty, p.suggestion());
			ty += 15;
		}

		// "About this player" bullets.
		context.drawText(textRenderer, Text.literal("About this player").styled(s -> s.withBold(true)), cx, ty, TEXT, false);
		ty += 12;
		for (String line : facts(p)) {
			for (var wrapped : textRenderer.wrapLines(Text.literal("• " + line), cw)) {
				context.drawText(textRenderer, wrapped, cx, ty, TEXT, false);
				ty += 10;
			}
		}
		ty += 6;

		// Section tabs: what they've done, and their chat log.
		int lineY = Math.max(ty, y + img + 30);
		context.fill(left, lineY + 14, right, lineY + 15, BORDER);
		int conversations = p.conversations.size();
		String live = mod.chatLogger.isOpen(p) ? " ● live" : "";
		int links = mod.ledger.links.of(p.id).size();
		String[] labels = {"Details (" + p.details.size() + ")", "What they've done", "Where & when (" + p.sightings.size() + ")",
				"Seen with (" + links + ")", "Chat log (" + conversations + ")" + live};
		Section[] targets = {Section.DETAILS, Section.ACTIVITY, Section.SEEN, Section.LINKS, Section.CHAT};
		// Shorter names when the page is narrow, and a second row if even those don't fit.
		if (tabsWidth(labels, 12) > right - left) {
			labels = new String[] {"Details (" + p.details.size() + ")", "Activity", "Seen (" + p.sightings.size() + ")", "With (" + links + ")",
					"Chat (" + conversations + ")" + live};
		}
		int tx = left;
		int tabY = lineY;
		for (int i = 0; i < labels.length; i++) {
			int w = textRenderer.getWidth(Text.literal(labels[i]).styled(s -> s.withBold(true)));
			if (tx > left && tx + w > right) {
				context.fill(left, tabY + 14, right, tabY + 15, BORDER);
				tx = left;
				tabY += 17;
			}
			tx = sectionTab(context, mouseX, mouseY, tx, tabY, labels[i], targets[i]) + 12;
		}
		if (tabY > lineY) context.fill(left, tabY + 14, right, tabY + 15, BORDER);
		ty = tabY + 22;
		if (section != Section.DETAILS) detailFieldTop = -1000;
		if (section == Section.DETAILS) {
			ty = renderDetails(context, mouseX, mouseY, p, left, ty, right - left);
		} else if (section == Section.ACTIVITY) {
			List<Profile.Action> actions = new ArrayList<>(p.actions);
			java.util.Collections.reverse(actions);
			for (Profile.Action action : actions) {
				ty = activityRow(context, action, left, ty, right - left);
			}
		} else if (section == Section.SEEN) {
			ty = renderSeen(context, mouseX, mouseY, p, left, ty, right - left);
		} else if (section == Section.LINKS) {
			ty = renderLinks(context, mouseX, mouseY, p, left, ty, right - left);
		} else {
			ty = renderChatLog(context, mouseX, mouseY, p, left, ty, right - left);
		}
		context.disableScissor();
		contentHeight = ty - y0 + 10;

		if (boxW > 0) buyBox(context, mouseX, mouseY, p, boxX, boxW);
		scrollbar(context, BODY);
	}

	private int tabsWidth(String[] labels, int gap) {
		int w = 0;
		for (String label : labels) w += textRenderer.getWidth(Text.literal(label).styled(s -> s.withBold(true))) + gap;
		return w;
	}

	private static final String[] DIMENSION_NAMES = {"minecraft:overworld", "Overworld", "minecraft:the_nether", "Nether", "minecraft:the_end", "End"};

	static String dimensionName(String id) {
		for (int i = 0; i < DIMENSION_NAMES.length; i += 2) {
			if (DIMENSION_NAMES[i].equals(id)) return DIMENSION_NAMES[i + 1];
		}
		return id.replaceFirst("^minecraft:", "");
	}

	/** "2,400 blocks NE of Home" for the nearest of your bases in that dimension, or "". */
	private static String fromBase(String dimension, int x, int z) {
		BaseInsuranceBridge.BaseArea best = null;
		double bestD = Double.MAX_VALUE;
		for (BaseInsuranceBridge.BaseArea base : BaseInsuranceBridge.bases()) {
			if (!base.dimension().equals(dimension)) continue;
			double d = base.distance(x, z);
			if (d < bestD) {
				bestD = d;
				best = base;
			}
		}
		if (best == null) return "";
		if (bestD < 1) return "in your base " + best.name();
		double dx = x - (best.minX() + best.maxX()) / 2.0, dz = z - (best.minZ() + best.maxZ()) / 2.0;
		String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		String dir = names[(int) Math.floorMod(Math.round(Math.toDegrees(Math.atan2(-dx, dz)) / 45), 8)];
		return String.format(Locale.ROOT, "%,d blocks %s of %s", (int) bestD, dir, best.name());
	}

	/** The "Where & when" section: when they're usually on, names they've had, screenshots, and every sighting. */
	private int renderSeen(DrawContext context, int mouseX, int mouseY, Profile p, int x, int y, int w) {
		// When they're on: a bar per hour of the day.
		context.drawText(textRenderer, Text.literal("When they're on").styled(s -> s.withBold(true)), x, y, TEXT, false);
		y += 12;
		int minutes = p.minutesOnline();
		if (p.hours == null || minutes == 0) {
			for (var line : textRenderer.wrapLines(Text.literal("Not seen online yet. Every minute they're on while you are counts towards when they usually play."), w)) {
				context.drawText(textRenderer, line, x, y, MUTED, false);
				y += 10;
			}
		} else {
			int chartH = 28;
			int barW = Math.max(3, Math.min(12, (w - 23) / 24 - 1));
			int max = 1;
			for (int h : p.hours) max = Math.max(max, h);
			int nowHour = java.time.LocalTime.now().getHour();
			rounded(context, x, y, 24 * (barW + 1) + 7, chartH + 16, SOFT);
			for (int h = 0; h < 24; h++) {
				int bh = p.hours[h] == 0 ? 0 : Math.max(1, p.hours[h] * chartH / max);
				int bx = x + 4 + h * (barW + 1);
				context.fill(bx, y + 3 + chartH - bh, bx + barW, y + 3 + chartH, h == nowHour ? ORANGE : LINK);
				if (over(mouseX, mouseY, bx, y + 3, barW, chartH)) {
					context.fill(bx, y + 3, bx + barW, y + 3 + chartH - bh, HOVER_BORDER);
					String tip = Profile.hourName(h) + ": " + duration(p.hours[h] * 60) + " seen";
					text(context, tip, x + 24 * (barW + 1) + 14, y + 3, TEXT);
				}
			}
			for (int h = 0; h < 24; h += 6) text(context, Profile.hourName(h), x + 4 + h * (barW + 1), y + chartH + 5, MUTED);
			String usually = p.usuallyOn();
			int infoX = x + 24 * (barW + 1) + 14;
			if (infoX + 60 < x + w) {
				textTrim(context, usually == null ? "Not enough yet to tell" : "Usually on " + usually, infoX, y + 15, x + w - infoX, TEXT);
				textTrim(context, duration(minutes * 60) + " seen online", infoX, y + 26, x + w - infoX, MUTED);
			}
			y += chartH + 20;
			if (infoX + 60 >= x + w) {
				textTrim(context, (usually == null ? "" : "Usually on " + usually + " · ") + duration(minutes * 60) + " seen online", x, y, w, TEXT);
				y += 12;
			}
		}
		y += 6;

		if (!p.pastNames.isEmpty()) {
			context.drawText(textRenderer, Text.literal("Names they've had").styled(s -> s.withBold(true)), x, y, TEXT, false);
			y += 12;
			textTrim(context, String.join(" → ", p.pastNames) + " → " + p.name, x, y, w, TEXT);
			y += 16;
		}

		if (!p.evidence.isEmpty()) {
			context.drawText(textRenderer, Text.literal("Screenshots").styled(s -> s.withBold(true)), x, y, TEXT, false);
			link(context, mouseX, mouseY, x + w - textRenderer.getWidth("Open folder"), y, "Open folder", "evidence-folder",
					() -> Util.getOperatingSystem().open(EvidenceShots.folder(client)));
			y += 12;
			for (int i = p.evidence.size() - 1; i >= 0; i--) {
				Profile.Evidence ev = p.evidence.get(i);
				java.io.File file = EvidenceShots.file(client, ev);
				boolean exists = file.exists();
				String when = day(ev.time) + ", " + clock(ev.time);
				textTrim(context, "▣ " + ev.reason + " · " + when, x + 2, y, w - 50, TEXT);
				if (exists) link(context, mouseX, mouseY, x + w - textRenderer.getWidth("Open"), y, "Open", "evidence-open:" + i, () -> Util.getOperatingSystem().open(file));
				else text(context, "Deleted", x + w - textRenderer.getWidth("Deleted"), y, MUTED);
				y += 12;
			}
			y += 6;
		}

		context.drawText(textRenderer, Text.literal("Sightings").styled(s -> s.withBold(true)), x, y, TEXT, false);
		y += 12;
		if (p.sightings.isEmpty()) {
			String why = NNConfig.INSTANCE.recordSightings
					? "Not seen up close yet. Each time they come into view, where they were and what they had on is noted here."
					: "Sightings are off (Settings).";
			for (var line : textRenderer.wrapLines(Text.literal(why), w)) {
				context.drawText(textRenderer, line, x, y, MUTED, false);
				y += 10;
			}
			return y;
		}
		for (int i = p.sightings.size() - 1; i >= 0; i--) {
			Profile.Sighting sg = p.sightings.get(i);
			if (y > height + 40) {
				y += 30;
				continue;
			}
			int h = sg.gear.isEmpty() ? 26 : 36;
			boolean hover = over(mouseX, mouseY, x, y, w, h);
			roundedBorder(context, x, y, w, h, hover ? HOVER_BG : CARD, hover ? HOVER_BORDER : BORDER);
			long stayed = Math.max(0, sg.until - sg.time) / 1000;
			String when = day(sg.time) + ", " + clock(sg.time) + (stayed >= 60 ? " for " + duration((int) stayed) : "");
			String where = dimensionName(sg.dimension) + " " + sg.x + ", " + sg.y + ", " + sg.z;
			if (Math.abs(sg.lastX - sg.x) + Math.abs(sg.lastZ - sg.z) > 16) where += " → " + sg.lastX + ", " + sg.lastZ;
			context.drawText(textRenderer, Text.literal(when).styled(s -> s.withBold(true)), x + 6, y + 4, TEXT, false);
			String copy = "Copy";
			int copyX = x + w - 6 - textRenderer.getWidth(copy);
			String near = !sg.base.isEmpty() ? "in your base " + sg.base : fromBase(sg.dimension, sg.lastX, sg.lastZ);
			textTrim(context, where + (near.isEmpty() ? "" : " · " + near), x + 6, y + 15, copyX - x - 12, near.startsWith("in your base") ? NAUGHTY : MUTED);
			if (!sg.gear.isEmpty()) textTrim(context, sg.gear, x + 6, y + 25, w - 12, TEXT);
			int sy = y;
			link(context, mouseX, mouseY, copyX, y + 4, copy, "sighting-copy:" + i, () -> {
				client.keyboard.setClipboard(sg.lastX + " " + sg.lastY + " " + sg.lastZ);
				flash("Copied " + sg.lastX + " " + sg.lastY + " " + sg.lastZ, NICE, null);
			});
			y = sy + h + 4;
		}
		return y;
	}

	/** The "Seen with" section: who they keep turning up with, and how. */
	private int renderLinks(DrawContext context, int mouseX, int mouseY, Profile p, int x, int y, int w) {
		List<Links.Link> links = mod.ledger.links.of(p.id);
		if (links.isEmpty()) {
			String why = NNConfig.INSTANCE.trackLinks
					? "Nobody yet. Players who log on or off at the same time as them, play near them, or take turns with them "
					+ "(one logs off, the other logs on, like alts) show up here, strongest first."
					: "Tracking who plays together is off (Settings).";
			for (var line : textRenderer.wrapLines(Text.literal(why), w)) {
				context.drawText(textRenderer, line, x, y, MUTED, false);
				y += 10;
			}
			return y;
		}
		List<Profile> all = new ArrayList<>();
		for (Links.Link link : links) {
			Profile other = mod.ledger.get(link.other(p.id));
			if (other != null && !other.inGroup(p.groups.isEmpty() ? "" : p.groups.get(0))) all.add(other);
		}
		if (all.size() >= 1) {
			String label = "+ Group " + p.name + " with " + (all.size() == 1 ? all.get(0).name : "these " + all.size());
			int lw = Math.min(w, textRenderer.getWidth(label) + 14);
			pill(context, mouseX, mouseY, x, y, lw, 14, label, YELLOW, YELLOW_EDGE, BUTTON_TEXT, "links-group", () -> {
				List<Profile> people = new ArrayList<>(all);
				people.add(0, p);
				openGroupWith(people);
			});
			y += 20;
		}
		for (Links.Link link : links) {
			Profile other = mod.ledger.get(link.other(p.id));
			if (other == null) continue;
			int h = 26;
			boolean hover = over(mouseX, mouseY, x, y, w, h);
			roundedBorder(context, x, y, w, h, hover ? HOVER_BG : CARD, hover ? HOVER_BORDER : BORDER);
			face(context, other.id, x + 5, y + 5, 16);
			int c = other.verdict == Profile.Verdict.NONE ? LINK : verdictColor(other.verdict);
			String name = verdictMark(other.verdict) + other.name;
			context.drawText(textRenderer, Text.literal(name).styled(s -> s.withBold(true)), x + 26, y + 4, c, false);
			int nx = x + 26 + textRenderer.getWidth(Text.literal(name).styled(s -> s.withBold(true))) + 6;
			if (link.looksLikeAlt()) {
				String alt = "Maybe an alt";
				int aw = textRenderer.getWidth(alt) + 6;
				rounded(context, nx, y + 3, aw, 10, LOOK_BG);
				text(context, alt, nx + 3, y + 4, LOOK);
				nx += aw + 6;
			}
			for (String g : other.groups) {
				int gc = groupColor(g);
				String tag = "[" + g + "]";
				if (nx + textRenderer.getWidth(tag) > x + w - 6) break;
				text(context, tag, nx, y + 4, gc);
				nx += textRenderer.getWidth(tag) + 4;
			}
			textTrim(context, link.describe() + " · last " + ago(link.last), x + 26, y + 15, w - 32, MUTED);
			hit(x, y, w, h, "link:" + other.name, () -> open(other));
			y += h + 4;
		}
		return y;
	}

	/** Opens the group panel with these players filled in, to make a new group of them (or add them to one). */
	private void openGroupWith(List<Profile> people) {
		openGroupPanel(null, null);
		List<String> names = new ArrayList<>();
		for (Profile person : people) names.add(person.name);
		membersField.setText(String.join(", ", names) + ", ");
		membersField.setCursorToEnd(false);
		setFocused(groupField);
	}

	/** "Groups: [SCG ✖] [+ Add to a group]" on a player's page; returns the y after it. */
	private int detailGroups(DrawContext context, int mouseX, int mouseY, Profile p, int x, int y, int right) {
		int gx = x;
		text(context, "Groups:", gx, y + 2, MUTED);
		gx += textRenderer.getWidth("Groups: ");
		for (String g : List.copyOf(p.groups)) {
			int cw = Math.min(textRenderer.getWidth(g) + 18, right - x);
			if (gx + cw > right) {
				gx = x;
				y += 14;
			}
			int color = groupColor(g);
			boolean hover = over(mouseX, mouseY, gx, y, cw, 12);
			roundedBorder(context, gx, y, cw, 12, tint(color, CARD, hover ? 0.26f : 0.16f), tint(color, CARD, 0.6f));
			text(context, fit(g, cw - 18), gx + 4, y + 2, color);
			int xx = gx + cw - 10;
			boolean xHover = over(mouseX, mouseY, xx - 2, y, 11, 12);
			text(context, "×", xx, y + 2, xHover ? NAUGHTY : tint(color, CARD, 0.7f));
			hit(gx, y, cw - 11, 12, "group-chip:" + g, () -> showGroup(g));
			hit(xx - 2, y, 11, 12, "group-leave:" + g, () -> leaveGroup(p, g));
			gx += cw + 4;
		}
		String add = p.groups.isEmpty() ? "+ Add to a group" : "+ Group";
		int aw = textRenderer.getWidth(add) + 8;
		if (gx + aw > right) {
			gx = x;
			y += 14;
		}
		boolean hover = over(mouseX, mouseY, gx, y, aw, 12);
		roundedBorder(context, gx, y, aw, 12, hover ? SOFT_HOVER : SOFT, hover ? LINK : BORDER);
		text(context, add, gx + 4, y + 2, LINK);
		hit(gx, y, aw, 12, "group-add", () -> openGroupPanel(null, p));
		return y + 17;
	}

	/** An underlined section tab; returns the x after it. */
	private int sectionTab(DrawContext context, int mouseX, int mouseY, int x, int y, String label, Section target) {
		boolean active = section == target;
		Text text = Text.literal(label).styled(s -> s.withBold(true));
		int w = textRenderer.getWidth(text);
		boolean hover = over(mouseX, mouseY, x, y, w, 14);
		context.drawText(textRenderer, text, x, y + 2, active ? TEXT : hover ? HOVER_LINK : LINK, false);
		if (active) context.fill(x, y + 12, x + w, y + 15, ORANGE);
		hit(x, y, w, 14, "section:" + target.name(), () -> {
			section = target;
			scroll = Math.min(scroll, maxScroll());
		});
		return x + w;
	}

	private boolean isExpanded(Profile p, Profile.Conversation c) {
		Boolean set = expanded.get(c.start);
		if (set != null) return set;
		return c == p.conversations.get(p.conversations.size() - 1);
	}

	private String clock(long time) {
		return CLOCK.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
	}

	private String day(long time) {
		LocalDate date = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate();
		LocalDate today = LocalDate.now();
		if (date.equals(today)) return "Today";
		if (date.equals(today.minusDays(1))) return "Yesterday";
		return DAY.format(date);
	}

	/** Their logged conversations, newest first; the newest is open, the rest expand on click. */
	private int renderChatLog(DrawContext context, int mouseX, int mouseY, Profile p, int x, int y, int w) {
		// Links: open the text log, copy everything.
		Path file = mod.chatLogger.file(p);
		int lx = x;
		if (Files.exists(file)) lx = link(context, mouseX, mouseY, lx, y, "Open log file", "open-log", () -> Util.getOperatingSystem().open(file)) + 12;
		if (!p.conversations.isEmpty()) {
			lx = link(context, mouseX, mouseY, lx, y, "Copy all", "copy-all", () -> {
				StringBuilder sb = new StringBuilder();
				for (Profile.Conversation c : p.conversations) sb.append(ChatLogger.format(p, c)).append(System.lineSeparator());
				client.keyboard.setClipboard(sb.toString());
				flash("Copied " + p.conversations.size() + " conversations", NICE, null);
			}) + 12;
		}
		if (lx > x) y += 14;

		if (p.conversations.isEmpty()) {
			String why = !NNConfig.INSTANCE.chatLog ? "Chat logging is off (Settings)."
					: p.verdict == Profile.Verdict.NONE ? "Put them on your Nice or Naughty list and everything they say in chat is logged here."
					: "Nothing yet. Next time they talk in chat, the conversation is logged here.";
			for (var line : textRenderer.wrapLines(Text.literal(why), w)) {
				context.drawText(textRenderer, line, x, y, MUTED, false);
				y += 10;
			}
			// What they've said in the Chat tab's history (the last days), newest first.
			List<Profile.Line> theirs = new ArrayList<>();
			for (Profile.Line line : mod.chatLogger.feed()) {
				if (!line.me && !ChatLogger.isMarker(line) && (line.sender.equalsIgnoreCase(p.name) || p.pastNames.stream().anyMatch(line.sender::equalsIgnoreCase))) {
					theirs.add(line);
				}
			}
			if (!theirs.isEmpty()) {
				y += 6;
				context.drawText(textRenderer, Text.literal("Seen in chat lately (" + theirs.size() + ")").styled(s -> s.withBold(true)), x, y, TEXT, false);
				y += 12;
				for (int i = theirs.size() - 1; i >= 0 && i >= theirs.size() - 60; i--) {
					Profile.Line line = theirs.get(i);
					y = chatRow(context, line, x, y, w, true);
				}
			} else if (!p.recentChat.isEmpty()) {
				y += 6;
				context.drawText(textRenderer, Text.literal("Recently said").styled(s -> s.withBold(true)), x, y, TEXT, false);
				y += 12;
				for (int i = p.recentChat.size() - 1; i >= 0; i--) {
					textTrim(context, "\"" + p.recentChat.get(i) + "\"", x + 4, y, w - 4, MUTED);
					y += 10;
				}
			}
			return y;
		}

		for (int c = p.conversations.size() - 1; c >= 0; c--) {
			Profile.Conversation conversation = p.conversations.get(c);
			boolean open = isExpanded(p, conversation);
			boolean live = conversation.open;
			int theirs = conversation.theirLines();
			// Header row.
			boolean hover = over(mouseX, mouseY, x, y, w, 16);
			rounded(context, x, y, w, 16, hover ? SOFT_HOVER : SOFT);
			String when = day(conversation.start) + ", " + clock(conversation.start) + " - " + (live ? "now" : clock(conversation.end));
			Text title = Text.literal((open ? "▾ " : "▸ ") + when).styled(s -> s.withBold(true));
			context.drawText(textRenderer, title, x + 5, y + 4, TEXT, false);
			String stats = " · " + conversation.lines.size() + " lines, " + theirs + " theirs";
			int tx = x + 5 + textRenderer.getWidth(title);
			int rightEdge = x + w - 6;
			String copy = "Copy";
			int copyX = rightEdge - textRenderer.getWidth(copy);
			int statsMax = copyX - tx - (live ? 34 : 6);
			textTrim(context, stats, tx, y + 4, statsMax, MUTED);
			if (live) {
				int chipX = copyX - 32;
				rounded(context, chipX, y + 3, 26, 10, 0xFFCC0C39);
				text(context, "LIVE", chipX + 3, y + 4, WHITE);
			}
			boolean copyHover = over(mouseX, mouseY, copyX - 2, y, textRenderer.getWidth(copy) + 4, 16);
			context.drawText(textRenderer, Text.literal(copy).styled(s -> s.withUnderline(copyHover)), copyX, y + 4, LINK, false);
			long key = conversation.start;
			hit(x, y, w, 16, "conversation:" + (p.conversations.size() - 1 - c), () -> expanded.put(key, !open));
			hit(copyX - 2, y, textRenderer.getWidth(copy) + 4, 16, "copy:" + (p.conversations.size() - 1 - c), () -> {
				client.keyboard.setClipboard(ChatLogger.format(p, conversation));
				flash("Copied the conversation from " + when, NICE, null);
			});
			y += 18;
			if (!open) continue;
			for (Profile.Line line : conversation.lines) y = chatRow(context, line, x, y, w, false);
			y += 6;
		}
		return y;
	}

	/** One chat line: time, who, what. Their lines are highlighted. */
	private int chatRow(DrawContext context, Profile.Line line, int x, int y, int w, boolean withDay) {
		String time = withDay ? day(line.time) + " " + clock(line.time) : clock(line.time);
		int timeW = textRenderer.getWidth(withDay ? "Wed 30 Sep 00:00" : "00:00") + 6;
		int textX = x + 4 + timeW;
		int textW = w - 8 - timeW;
		Text body;
		if (line.system) {
			body = Text.literal("* " + line.sender + " " + line.text).styled(s -> s.withItalic(true).withColor(MUTED));
		} else {
			String who = line.me ? "You" : line.sender;
			int nameColor = line.them ? 0xFF0F1111 : line.me ? NICE : MUTED;
			Text name = Text.literal(line.whisper ? (line.me ? "You → " + line.sender : who + " → you") + ": " : "<" + who + "> ")
					.styled(s -> s.withBold(line.them).withColor(line.whisper ? WHISPER : nameColor));
			body = Text.empty().append(name).append(Text.literal(line.text).styled(s -> s.withColor(TEXT).withItalic(line.whisper)));
		}
		List<net.minecraft.text.OrderedText> wrapped = textRenderer.wrapLines(body, textW);
		int h = Math.max(1, wrapped.size()) * 10 + 2;
		if (line.them) context.fill(x, y - 1, x + w, y + h - 1, HIGHLIGHT);
		text(context, time, x + 4, y + 1, MUTED);
		int ly = y + 1;
		for (var part : wrapped) {
			context.drawText(textRenderer, part, textX, ly, TEXT, false);
			ly += 10;
		}
		return y + h;
	}

	private int link(DrawContext context, int mouseX, int mouseY, int x, int y, String label, String hitLabel, Runnable run) {
		int w = textRenderer.getWidth(label);
		boolean hover = over(mouseX, mouseY, x, y - 1, w, 11);
		context.drawText(textRenderer, Text.literal(label).styled(s -> s.withUnderline(hover)), x, y, hover ? HOVER_LINK : LINK, false);
		hit(x, y - 1, w, 11, hitLabel, run);
		return x + w;
	}

	/** A link in a panel: its click goes in {@code into} (the panel's hits) rather than the page's. */
	private int link(DrawContext context, int mouseX, int mouseY, int x, int y, String label, String hitLabel, Runnable run, List<Hit> into) {
		int w = textRenderer.getWidth(label);
		boolean hover = over(mouseX, mouseY, x, y - 1, w, 11);
		context.drawText(textRenderer, Text.literal(label).styled(s -> s.withUnderline(hover)), x, y, hover ? HOVER_LINK : LINK, false);
		into.add(new Hit(x, y - 1, x + w, y + 10, hitLabel, run));
		return x + w;
	}

	private List<String> facts(Profile p) {
		List<String> facts = new ArrayList<>();
		if (p.secondsNear > 0) facts.add("Time near you: " + duration(p.secondsNear));
		int[] counts = {p.count(Profile.Kind.KILLED_YOU), p.count(Profile.Kind.HIT_YOU), p.count(Profile.Kind.BROKE_IN_BASE),
				p.count(Profile.Kind.HURT_PET) + p.count(Profile.Kind.KILLED_PET), p.count(Profile.Kind.KILLED_PLAYER),
				p.count(Profile.Kind.HIT_PLAYER), p.count(Profile.Kind.KIND_WORDS), p.count(Profile.Kind.RUDE), p.count(Profile.Kind.VISITED_BASE)};
		String[] labels = {"Killed you", "Hit you", "Broke blocks in your bases", "Hurt your pets", "Killed other players",
				"Attacked other players", "Friendly messages", "Trash talk", "Visits to your bases"};
		for (int i = 0; i < counts.length; i++) {
			if (counts[i] > 0) facts.add(labels[i] + ": " + counts[i] + (counts[i] == 1 ? " time" : " times"));
		}
		if (p.verdict != Profile.Verdict.NONE) {
			facts.add(p.verdict == Profile.Verdict.FRIEND ? "Your friend (" + Friends.circleName(p) + ") since " + ago(p.listedAt)
					: "On your " + verdictWord(p.verdict) + " list since " + ago(p.listedAt) + (p.autoListed ? " (added automatically)" : ""));
		}
		// Who they're grouped with, and how those people are listed.
		for (String g : p.groups) {
			List<String> mates = new ArrayList<>();
			int naughty = 0;
			for (Profile m : mod.ledger.members(g)) {
				if (m == p) continue;
				mates.add(m.name);
				if (m.verdict == Profile.Verdict.NAUGHTY) naughty++;
			}
			if (mates.isEmpty()) continue;
			String names = mates.size() > 4 ? String.join(", ", mates.subList(0, 4)) + " and " + (mates.size() - 4) + " more" : join(mates);
			facts.add("In " + g + " with " + names + (naughty > 0 ? " (" + naughty + " on your Naughty list)" : ""));
		}
		int minutes = p.minutesOnline();
		if (minutes > 0) {
			String usually = p.usuallyOn();
			facts.add("Seen online for " + duration(minutes * 60) + (usually != null ? ", usually " + usually : ""));
		}
		Profile.Sighting last = p.lastSighting();
		if (last != null) {
			String near = fromBase(last.dimension, last.lastX, last.lastZ);
			facts.add("Last seen up close " + ago(last.until) + " at " + last.lastX + ", " + last.lastZ + " (" + dimensionName(last.dimension) + ")"
					+ (near.isEmpty() ? "" : ", " + near));
		}
		for (Profile.FriendView view : p.friendViews) {
			if (view.verdict == Profile.Verdict.NONE) continue;
			facts.add(view.from + (view.verdict == Profile.Verdict.FRIEND ? " is friends with them" : " has them on their " + verdictWord(view.verdict) + " list")
					+ " (shared " + ago(view.time) + ")");
		}
		List<Links.Link> links = mod.ledger.links.of(p.id);
		if (!links.isEmpty()) {
			List<String> with = new ArrayList<>();
			for (Links.Link link : links) {
				Profile other = mod.ledger.get(link.other(p.id));
				if (other != null && with.size() < 3) with.add(other.name + (link.looksLikeAlt() ? " (maybe an alt)" : ""));
			}
			if (!with.isEmpty()) facts.add("Often seen with " + join(with) + (links.size() > 3 ? " and " + (links.size() - 3) + " more" : ""));
		}
		if (facts.isEmpty()) facts.add("Nothing yet. Their rating fills in as they do things around you.");
		return facts;
	}

	/** Ready-made starts for a detail, one click away. */
	private static final String[] STARTERS = {"Alt of ", "Main account: ", "Base near ", "Friends with ", "In a group with ", "Griefed ", "Trades "};

	/** The Details section: a box to write in, starters, then everything written down, newest first. */
	private int renderDetails(DrawContext context, int mouseX, int mouseY, Profile p, int x, int y, int w) {
		String addLabel = editing != null ? "Save" : "Add";
		int bw = textRenderer.getWidth(addLabel) + 20;
		int fw = w - bw - 6;
		roundedBorder(context, x, y, fw, 16, FIELD, detailField.isFocused() ? 0xFFE77600 : editing != null ? ORANGE : INPUT_BORDER);
		detailField.setX(x + 4);
		detailField.setY(y + 4);
		detailField.setWidth(fw - 8);
		detailFieldTop = y;
		pill(context, mouseX, mouseY, x + fw + 6, y, bw, 16, addLabel, YELLOW, YELLOW_EDGE, BUTTON_TEXT, "detail-add", () -> commitDetail(p));
		y += 20;

		if (editing != null) {
			text(context, "Editing a detail.", x, y + 1, HOVER_LINK);
			link(context, mouseX, mouseY, x + textRenderer.getWidth("Editing a detail. "), y + 1, "Cancel", "detail-cancel", this::cancelEdit);
			y += 14;
		} else if (detailField.getText().isEmpty()) {
			// Starters, in as many rows as they need.
			int sx = x;
			for (String starter : STARTERS) {
				String label = starter.strip() + "…";
				int cw = textRenderer.getWidth(label) + 8;
				if (sx + cw > x + w) {
					sx = x;
					y += 14;
				}
				boolean hover = over(mouseX, mouseY, sx, y, cw, 12);
				roundedBorder(context, sx, y, cw, 12, hover ? SOFT_HOVER : SOFT, hover ? LINK : BORDER);
				text(context, label, sx + 4, y + 2, LINK);
				hit(sx, y, cw, 12, "starter:" + starter.strip(), () -> {
					detailField.setText(starter);
					detailField.setCursorToEnd(false);
					setFocused(detailField);
				});
				sx += cw + 4;
			}
			y += 18;
		} else {
			y += 4;
		}

		if (p.details.isEmpty()) {
			String empty = "Nothing written down yet. Add anything worth remembering: their alts, where their base is, "
					+ "who they play with, what they did. Friends' details show up here too when you import their lists.";
			for (var line : textRenderer.wrapLines(Text.literal(empty), w)) {
				context.drawText(textRenderer, line, x, y, MUTED, false);
				y += 10;
			}
			return y;
		}
		for (int i = p.details.size() - 1; i >= 0; i--) y = detailRow(context, mouseX, mouseY, p, p.details.get(i), x, y, w);
		return y;
	}

	/** One detail: the text, who it came from and when, and Edit / Remove on hover. */
	private int detailRow(DrawContext context, int mouseX, int mouseY, Profile p, Profile.Detail d, int x, int y, int w) {
		List<net.minecraft.text.OrderedText> lines = textRenderer.wrapLines(Text.literal(d.text), w - 22);
		int h = lines.size() * 10 + 17;
		boolean hover = over(mouseX, mouseY, x, y, w, h);
		roundedBorder(context, x, y, w, h, hover ? HOVER_BG : CARD, editing == d ? ORANGE : hover ? HOVER_BORDER : BORDER);
		// Colour says whose it is: yours, a friend's, or what a friend's mod saw.
		int mark = d.mine() ? LINK : d.seen ? MUTED : WHISPER;
		context.fill(x + 1, y + 2, x + 3, y + h - 2, mark);
		int ty = y + 5;
		for (var line : lines) {
			context.drawText(textRenderer, line, x + 10, ty, TEXT, false);
			ty += 10;
		}
		String who = d.mine() ? "You" : d.seen ? "Seen by " + d.from + "'s mod" : "From " + d.from;
		String meta = who + " · " + ago(d.time);
		int actionsW = textRenderer.getWidth("Edit") + textRenderer.getWidth("Remove") + 14;
		textTrim(context, meta, x + 10, ty + 1, w - 20 - (hover ? actionsW : 0), d.mine() ? MUTED : mark);
		if (hover || editing == d) {
			int rx = x + w - 8 - textRenderer.getWidth("Remove");
			link(context, mouseX, mouseY, rx, ty + 1, "Remove", "detail-remove:" + d.text, () -> removeDetail(p, d));
			int ex = rx - 8 - textRenderer.getWidth("Edit");
			link(context, mouseX, mouseY, ex, ty + 1, "Edit", "detail-edit:" + d.text, () -> {
				editing = d;
				detailField.setText(d.text);
				detailField.setCursorToEnd(false);
				setFocused(detailField);
			});
		}
		return y + h + 4;
	}

	/** Adds what's in the box as a detail, or saves the one being edited. */
	private void commitDetail(Profile p) {
		String text = detailField.getText().strip();
		// An empty box, or just a starter with nothing after it.
		if (text.isEmpty() || java.util.Arrays.stream(STARTERS).anyMatch(st -> st.strip().equalsIgnoreCase(text))) {
			setFocused(detailField);
			return;
		}
		if (editing != null) {
			Profile.Detail d = editing;
			String before = d.text;
			d.text = text;
			editing = null;
			flash("Saved the detail", NICE, () -> {
				d.text = before;
				mod.ledger.markDirty();
				flash("Undone", MUTED, null);
			});
		} else {
			Profile.Detail d = p.addDetail(text, "", false);
			if (d == null) {
				flash("That's already written down", MUTED, null);
				return;
			}
			flash("Added a detail about " + p.name, NICE, () -> {
				p.details.remove(d);
				mod.ledger.markDirty();
				cachedResults = null;
				flash("Undone", MUTED, null);
			});
		}
		detailField.setText("");
		setFocused(detailField);
		mod.ledger.markDirty();
		mod.ledger.save(false);
		cachedResults = null;
	}

	private void cancelEdit() {
		editing = null;
		detailField.setText("");
	}

	private void removeDetail(Profile p, Profile.Detail d) {
		int at = p.details.indexOf(d);
		if (at < 0) return;
		p.details.remove(at);
		if (editing == d) cancelEdit();
		mod.ledger.markDirty();
		mod.ledger.save(false);
		cachedResults = null;
		flash("Removed the detail", MUTED, () -> {
			p.details.add(Math.min(at, p.details.size()), d);
			mod.ledger.markDirty();
			cachedResults = null;
			flash("Undone", MUTED, null);
		});
	}

	private int activityRow(DrawContext context, Profile.Action action, int x, int y, int w) {
		// A points chip: green for good, red for bad, grey for neutral.
		String chip = action.points > 0 ? "+" + action.points : action.points < 0 ? String.valueOf(action.points) : "•";
		int color = action.points > 0 ? NICE : action.points < 0 ? NAUGHTY : MUTED;
		int chipW = 22;
		rounded(context, x, y - 1, chipW, 11, (color & 0xFFFFFF) | 0x22000000);
		text(context, chip, x + (chipW - textRenderer.getWidth(chip)) / 2, y + 1, color);
		String when = ago(action.time);
		int whenW = textRenderer.getWidth(when);
		textTrim(context, action.describe(), x + chipW + 6, y + 1, w - chipW - whenW - 14, TEXT);
		text(context, when, x + w - whenW, y + 1, MUTED);
		return y + 13;
	}

	private void buyBox(DrawContext context, int mouseX, int mouseY, Profile p, int x, int w) {
		int y = BODY + 6;
		boolean friend = p.verdict == Profile.Verdict.FRIEND;
		int circleRows = friend ? Friends.circles().size() : 0;
		int h = 248 + circleRows * 14;
		roundedBorder(context, x, y, w, h, CARD, BORDER);
		int ix = x + 10;
		int iw = w - 20;
		int ty = y + 10;
		String status = friend ? "★ " + Friends.circleName(p) : p.verdict == Profile.Verdict.NONE ? "Not on a list" : "On your " + verdictWord(p.verdict) + " list";
		int statusColor = friend ? GROUP_COLORS[Friends.color(p) % GROUP_COLORS.length] : p.verdict == Profile.Verdict.NONE ? TEXT : verdictColor(p.verdict);
		context.drawText(textRenderer, Text.literal(status).styled(s -> s.withBold(true)), ix, ty, statusColor, false);
		ty += 12;
		text(context, p.verdict == Profile.Verdict.NONE ? "Pick one with a click." : "Since " + ago(p.listedAt), ix, ty, MUTED);
		ty += 14;

		pill(context, mouseX, mouseY, ix, ty, iw, 16, friend ? "★ Friend" : "★ Add to Friends",
				friend ? FRIEND_BG : FRIEND_BG, FRIEND, FRIEND, "page-friend", () -> {
					if (!friend) makeFriend(p, Friends.circles().get(0));
					else setVerdict(p, Profile.Verdict.FRIEND);
				});
		ty += 18;
		if (friend) {
			// Their circle: a row each, theirs ticked.
			int cw = iw;
			Friends.Circle current = Friends.of(p);
			List<Friends.Circle> circles = Friends.circles();
			for (int i = 0; i < circles.size(); i++) {
				Friends.Circle c = circles.get(i);
				int cx = ix;
				int cy = ty + i * 14;
				int color = GROUP_COLORS[c.color % GROUP_COLORS.length];
				boolean on = c == current;
				boolean hover = over(mouseX, mouseY, cx, cy, cw, 12);
				roundedBorder(context, cx, cy, cw, 12, on ? tint(color, CARD, 0.25f) : hover ? HOVER_BG : CARD, on ? color : BORDER);
				context.fill(cx + 4, cy + 4, cx + 8, cy + 8, color);
				textTrim(context, c.name, cx + 11, cy + 2, cw - 24, on ? color : TEXT);
				if (on) text(context, "✔", cx + cw - 11, cy + 2, color);
				hit(cx, cy, cw, 12, "page-circle:" + c.name, () -> makeFriend(p, c));
			}
			ty += circleRows * 14;
		}
		ty += 4;
		boolean nice = p.verdict == Profile.Verdict.NICE;
		boolean look = p.verdict == Profile.Verdict.LOOK;
		boolean naughty = p.verdict == Profile.Verdict.NAUGHTY;
		pill(context, mouseX, mouseY, ix, ty, iw, 16, nice ? "✔ On Nice List" : "✔ Add to Nice List",
				nice ? CARD : YELLOW, nice ? NICE : YELLOW_EDGE, nice ? NICE : BUTTON_TEXT, "page-nice", () -> setVerdict(p, Profile.Verdict.NICE));
		ty += 20;
		pill(context, mouseX, mouseY, ix, ty, iw, 16, look ? "? On Look Into List" : "? Look Into",
				look ? LOOK_BG : SOFT, LOOK, look ? LOOK : TEXT, "page-look", () -> setVerdict(p, Profile.Verdict.LOOK));
		ty += 20;
		pill(context, mouseX, mouseY, ix, ty, iw, 16, naughty ? "✖ On Naughty List" : "✖ Add to Naughty List",
				naughty ? CARD : ORANGE, naughty ? NAUGHTY : ORANGE_EDGE, naughty ? NAUGHTY : BUTTON_TEXT, "page-naughty", () -> setVerdict(p, Profile.Verdict.NAUGHTY));
		ty += 20;
		if (p.verdict != Profile.Verdict.NONE) {
			pill(context, mouseX, mouseY, ix, ty, iw, 14, friend ? "No longer a friend" : "Take off lists", CARD, BORDER, TEXT, "page-unsort", () -> setVerdict(p, p.verdict));
		}
		ty += 22;

		int n = p.details.size();
		context.drawText(textRenderer, Text.literal(n == 0 ? "No details yet" : n + (n == 1 ? " detail" : " details")).styled(s -> s.withBold(true)),
				ix, ty, TEXT, false);
		ty += 12;
		link(context, mouseX, mouseY, ix, ty, "✎ Write a detail", "box-detail", () -> {
			section = Section.DETAILS;
			scroll = 0;
			setFocused(detailField);
		});
		ty += 13;
		link(context, mouseX, mouseY, ix, ty, "⇄ Share this player", "share-player", () -> sharePlayer(p));
		ty += 17;

		int g = p.groups.size();
		context.drawText(textRenderer, Text.literal(g == 0 ? "In no groups" : g == 1 ? "In 1 group" : "In " + g + " groups").styled(s -> s.withBold(true)),
				ix, ty, TEXT, false);
		ty += 12;
		link(context, mouseX, mouseY, ix, ty, "+ Add to a group", "box-group", () -> openGroupPanel(null, p));
		ty += 17;

		String forget = confirmForget ? "Click again to forget them" : "Forget this player";
		boolean hover = over(mouseX, mouseY, ix, ty, textRenderer.getWidth(forget), 10);
		context.drawText(textRenderer, Text.literal(forget).styled(s -> s.withUnderline(hover)), ix, ty, confirmForget ? NAUGHTY : LINK, false);
		hits.add(new Hit(ix, ty - 1, ix + textRenderer.getWidth(forget), ty + 10, "forget", () -> {
			if (!confirmForget) {
				confirmForget = true;
				return;
			}
			mod.ledger.forget(p);
			mod.ledger.save(true);
			flash("Forgot " + p.name, MUTED, null);
			back();
		}));
	}

	// ---- Groups ----

	/** Shows one group's members as cards. */
	private void showGroup(String group) {
		String name = mod.ledger.groupName(group);
		tab = Tab.GROUPS;
		lastTab = Tab.GROUPS;
		openGroup = name;
		confirmDeleteGroup = false;
		selected = null;
		scroll = 0;
		cachedResults = null;
		setFocused(null);
	}

	/** Takes someone out of a group, with undo. */
	private void leaveGroup(Profile p, String group) {
		int at = -1;
		for (int i = 0; i < p.groups.size(); i++) {
			if (p.groups.get(i).equalsIgnoreCase(group)) at = i;
		}
		if (at < 0) return;
		String was = p.groups.get(at);
		int index = at;
		mod.ledger.removeFromGroup(p, group);
		mod.ledger.save(false);
		cachedResults = null;
		// The group's last member left: it's gone, so go back to all groups.
		if (tab == Tab.GROUPS && openGroup != null && mod.ledger.groupName(openGroup) == null) openGroup = null;
		flash("Took " + p.name + " out of " + was, MUTED, () -> {
			if (!p.inGroup(was)) p.groups.add(Math.min(index, p.groups.size()), was);
			mod.ledger.groupsChanged();
			mod.ledger.save(false);
			cachedResults = null;
			flash("Undone", MUTED, null);
		});
	}

	/** "✔ 2  ? 1  ✖ 3" for a group's members; returns the x after it. */
	private int tally(DrawContext context, List<Profile> members, int x, int y) {
		int[] n = new int[Profile.Verdict.values().length];
		for (Profile m : members) n[m.verdict.ordinal()]++;
		for (Profile.Verdict v : new Profile.Verdict[] {Profile.Verdict.NICE, Profile.Verdict.LOOK, Profile.Verdict.NAUGHTY}) {
			if (n[v.ordinal()] == 0) continue;
			String t = verdictMark(v) + n[v.ordinal()];
			text(context, t, x, y, verdictColor(v));
			x += textRenderer.getWidth(t) + 6;
		}
		return x;
	}

	private int online(List<Profile> members) {
		int n = 0;
		for (Profile m : members) {
			if (isOnline(m)) n++;
		}
		return n;
	}

	/** The Groups tab: a box per group with who's in it, and a box to make a new one. */
	private void renderGroups(DrawContext context, int mouseX, int mouseY) {
		int barY = BODY;
		context.fill(0, barY, width, barY + 16, CARD);
		context.fill(0, barY + 16, width, barY + 17, BORDER);
		Map<String, List<Profile>> groups = mod.ledger.groups();
		String q = query.toLowerCase(Locale.ROOT);
		List<String> shown = new ArrayList<>();
		for (Map.Entry<String, List<Profile>> e : groups.entrySet()) {
			if (q.isEmpty() || e.getKey().toLowerCase(Locale.ROOT).contains(q)
					|| e.getValue().stream().anyMatch(m -> m.name.toLowerCase(Locale.ROOT).contains(q))) shown.add(e.getKey());
		}
		String summary = shown.size() + (shown.size() == 1 ? " group" : " groups");
		if (!query.isEmpty()) summary += " for ";
		text(context, summary, 8, barY + 4, TEXT);
		if (!query.isEmpty()) text(context, "\"" + query + "\"", 8 + textRenderer.getWidth(summary), barY + 4, HOVER_LINK);
		String newLabel = "+ New group";
		int nw = textRenderer.getWidth(newLabel) + 12;
		pill(context, mouseX, mouseY, width - 8 - nw, barY + 2, nw, 12, newLabel, YELLOW, YELLOW_EDGE, BUTTON_TEXT, "new-group", () -> openGroupPanel(null, null));

		int top = barY + 17;
		boolean sidebar = width >= 380;
		int left = sidebar ? 8 + SIDEBAR + 8 : 8;
		int right = width - 8;
		context.enableScissor(0, top, width, height);
		if (sidebar) renderSidebar(context, mouseX, mouseY, top + 6 - (int) scroll);
		int gridW = right - left;
		int boxMin = 190;
		int cols = Math.max(1, (gridW + GAP) / (boxMin + GAP));
		int boxW = (gridW - GAP * (cols - 1)) / cols;
		int y = top + 6 - (int) scroll;
		int startY = y;
		if (groups.isEmpty()) {
			rounded(context, left, y, gridW, 74, CARD);
			int ty = y + 10;
			context.drawText(textRenderer, Text.literal("No groups yet").styled(s -> s.withBold(true)), left + 10, ty, TEXT, false);
			ty += 13;
			String help = "Group people you know are teamed up or associated: a base crew, a griefing group, someone's alts. "
					+ "Their group shows on their card, page, nametag and in the Chat tab, so you can tell who's with who at a glance.";
			for (var line : textRenderer.wrapLines(Text.literal(help), gridW - 20)) {
				context.drawText(textRenderer, line, left + 10, ty, MUTED, false);
				ty += 10;
			}
			y += 80;
			pill(context, mouseX, mouseY, left, y, 120, 16, "+ Make a group", YELLOW, YELLOW_EDGE, BUTTON_TEXT, "first-group", () -> openGroupPanel(null, null));
			y += 22;
		}
		y = groupSuggestions(context, mouseX, mouseY, left, y, gridW);
		int boxH = 116;
		for (int i = 0; i < shown.size(); i++) {
			int col = i % cols;
			int by = y + (i / cols) * (boxH + GAP);
			if (by > height || by + boxH < top) continue;
			groupBox(context, mouseX, mouseY, shown.get(i), groups.get(shown.get(i)), left + col * (boxW + GAP), by, boxW, boxH);
		}
		if (!shown.isEmpty()) y += ((shown.size() + cols - 1) / cols) * (boxH + GAP);
		else if (!groups.isEmpty()) {
			text(context, "No group or member matches \"" + query + "\".", left, y + 4, MUTED);
			y += 20;
		}
		context.disableScissor();
		contentHeight = Math.max(y - startY + 12, sidebar ? sidebarHeight : 0);
		scrollbar(context, top);
	}

	/** Suggested groups from who's been seen together, worked out again only when the links change. */
	private List<List<Profile>> suggestedGroups;
	private int suggestedVersion = -1;
	private int suggestedChanges = -1;

	private List<List<Profile>> suggestedGroups() {
		if (suggestedGroups != null && suggestedVersion == mod.ledger.links.version() && suggestedChanges == mod.ledger.changes()) return suggestedGroups;
		List<List<Profile>> found = new ArrayList<>();
		for (List<UUID> cluster : mod.ledger.links.clusters(mod.ledger, 8)) {
			List<Profile> people = new ArrayList<>();
			for (UUID id : cluster) {
				Profile p = mod.ledger.get(id);
				if (p != null) people.add(p);
			}
			people.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
			if (people.size() >= 2 && !NNConfig.INSTANCE.dismissedSuggestions.contains(suggestionKey(people))) found.add(people);
			if (found.size() >= 6) break;
		}
		suggestedGroups = found;
		suggestedVersion = mod.ledger.links.version();
		suggestedChanges = mod.ledger.changes();
		return found;
	}

	private static String suggestionKey(List<Profile> people) {
		List<String> ids = new ArrayList<>();
		for (Profile p : people) ids.add(p.id.toString());
		java.util.Collections.sort(ids);
		return String.join(",", ids);
	}

	/** "Seen together a lot": crews the mod noticed, each with Make a group and Not a group. Returns the y after. */
	private int groupSuggestions(DrawContext context, int mouseX, int mouseY, int x, int y, int w) {
		List<List<Profile>> suggestions = suggestedGroups();
		if (suggestions.isEmpty() || !query.isEmpty()) return y;
		int h = 18 + suggestions.size() * 16 + 6;
		roundedBorder(context, x, y, w, h, CARD, LOOK);
		context.drawText(textRenderer, Text.literal("Seen together a lot").styled(s -> s.withBold(true)), x + 8, y + 6, LOOK, false);
		textTrim(context, "  These players keep logging on, off or playing together. Teamed up?",
				x + 8 + textRenderer.getWidth(Text.literal("Seen together a lot").styled(s -> s.withBold(true))), y + 6, w - 160, MUTED);
		int ry = y + 20;
		for (List<Profile> people : suggestions) {
			String make = "Make a group";
			String no = "Not a group";
			int mw = textRenderer.getWidth(make) + 12;
			int nx = x + w - 8 - textRenderer.getWidth(no);
			int mx = nx - 10 - mw;
			int fx = x + 8;
			for (Profile p : people) {
				String label = verdictMark(p.verdict) + p.name;
				int lw = textRenderer.getWidth(label) + 16;
				if (fx + lw > mx - 30) {
					text(context, "+" + (people.size() - people.indexOf(p)), fx, ry + 2, MUTED);
					break;
				}
				face(context, p.id, fx, ry + 1, 9);
				boolean hover = over(mouseX, mouseY, fx, ry, lw, 12);
				int c = p.verdict == Profile.Verdict.NONE ? LINK : verdictColor(p.verdict);
				context.drawText(textRenderer, Text.literal(label).styled(st -> st.withUnderline(hover)), fx + 12, ry + 2, c, false);
				hit(fx, ry, lw, 12, "suggested-member:" + p.name, () -> open(p));
				fx += lw;
			}
			pill(context, mouseX, mouseY, mx, ry, mw, 13, make, YELLOW, YELLOW_EDGE, BUTTON_TEXT, "suggested-make:" + people.get(0).name, () -> openGroupWith(people));
			link(context, mouseX, mouseY, nx, ry + 2, no, "suggested-dismiss:" + people.get(0).name, () -> {
				NNConfig.INSTANCE.dismissedSuggestions.add(suggestionKey(people));
				NNConfig.save();
				suggestedGroups = null;
			});
			ry += 16;
		}
		return y + h + GAP;
	}

	/** One group on the Groups tab: colour, name, how its members are listed, and the first few of them. */
	private void groupBox(DrawContext context, int mouseX, int mouseY, String g, List<Profile> members, int x, int y, int w, int h) {
		boolean hover = over(mouseX, mouseY, x, y, w, h) && mouseY > BODY + 17;
		int color = groupColor(g);
		roundedBorder(context, x, y, w, h, CARD, hover ? HOVER_BORDER : BORDER);
		context.fill(x + 1, y + 2, x + w - 1, y + 5, color);
		hit(x, y, w, h, "group:" + g, () -> showGroup(g));
		int tx = x + 8;
		boolean nameHover = over(mouseX, mouseY, tx, y + 9, textRenderer.getWidth(g), 10);
		context.drawText(textRenderer, Text.literal(fit(g, w - 16)).styled(s -> s.withBold(true).withUnderline(nameHover)), tx, y + 10, nameHover ? HOVER_LINK : TEXT, false);
		int on = online(members);
		String sub = members.size() + (members.size() == 1 ? " member" : " members") + (on > 0 ? " · " + on + " online" : "");
		text(context, sub, tx, y + 22, MUTED);
		tally(context, members, tx + textRenderer.getWidth(sub) + 8, y + 22);
		// Members, listed ones first.
		List<Profile> order = new ArrayList<>(members);
		order.sort(Comparator.comparingInt((Profile m) -> switch (m.verdict) {
			case NAUGHTY -> 0;
			case LOOK -> 1;
			case NICE -> 2;
			case FRIEND -> 3;
			case NONE -> 4;
		}).thenComparing(m -> !isOnline(m)).thenComparing(m -> m.name.toLowerCase(Locale.ROOT)));
		int my = y + 35;
		int rows = 6;
		int colW = (w - 16) / 2;
		for (int i = 0; i < order.size() && i < rows * 2; i++) {
			Profile m = order.get(i);
			if (i == rows * 2 - 1 && order.size() > rows * 2) {
				text(context, "+" + (order.size() - i) + " more", tx + colW, my + (i % rows) * 11 + 1, LINK);
				break;
			}
			int mx = tx + (i / rows) * colW;
			int ry = my + (i % rows) * 11;
			face(context, m.id, mx, ry, 9);
			if (isOnline(m)) context.fill(mx + 7, ry + 7, mx + 10, ry + 10, 0xFF2EB82E);
			boolean rowHover = over(mouseX, mouseY, mx, ry - 1, colW - 4, 11);
			int c = m.verdict == Profile.Verdict.NONE ? LINK : verdictColor(m.verdict);
			String label = fit(verdictMark(m.verdict) + m.name, colW - 18);
			context.drawText(textRenderer, Text.literal(label).styled(s -> s.withUnderline(rowHover)), mx + 12, ry + 1, rowHover ? HOVER_LINK : c, false);
			hit(mx, ry - 1, colW - 4, 11, "member:" + m.name, () -> open(m));
		}
		int fy = y + h - 14;
		int lx = link(context, mouseX, mouseY, tx, fy, "See all", "group-open:" + g, () -> showGroup(g)) + 10;
		link(context, mouseX, mouseY, lx, fy, "+ Add members", "group-members:" + g, () -> openGroupPanel(g, null));
	}

	/** The top of one group's page: its name and colour, a summary, and what you can do with it. */
	private int groupHeader(DrawContext context, int mouseX, int mouseY, String g, int x, int y, int w) {
		List<Profile> members = mod.ledger.members(g);
		int h = 46;
		int color = groupColor(g);
		roundedBorder(context, x, y, w, h, CARD, BORDER);
		context.fill(x + 1, y + 2, x + 4, y + h - 2, color);
		int tx = x + 10;
		int lx = link(context, mouseX, mouseY, tx, y + 5, "‹ All groups", "groups-back", () -> {
			openGroup = null;
			scroll = 0;
		});
		// Colour swatch: click for the next colour.
		int sx = tx;
		int sy = y + 18;
		boolean swatchHover = over(mouseX, mouseY, sx, sy, 12, 12);
		roundedBorder(context, sx, sy, 12, 12, color, swatchHover ? TEXT : tint(color, CARD, 0.5f));
		hit(sx, sy, 12, 12, "group-color", () -> {
			mod.ledger.cycleGroupColor(g);
			mod.ledger.save(false);
		});
		int pillsW = textRenderer.getWidth("+ Add members") + textRenderer.getWidth("♪ Alerts on") + 40;
		big(context, fit(g, (int) ((w - 40 - pillsW) / 1.3)), sx + 17, sy + 1, TEXT, 1.3f);
		int on = online(members);
		String sub = members.size() + (members.size() == 1 ? " member" : " members") + (on > 0 ? " · " + on + " online" : "") + "  ";
		int subX = sx + 17;
		text(context, sub, subX, y + 34, MUTED);
		tally(context, members, subX + textRenderer.getWidth(sub), y + 34);
		// Actions, right-aligned on the top line.
		String delete = confirmDeleteGroup ? "Click again to delete" : "Delete group";
		int rx = x + w - 8 - textRenderer.getWidth(delete);
		boolean delHover = over(mouseX, mouseY, rx, y + 4, textRenderer.getWidth(delete), 10);
		context.drawText(textRenderer, Text.literal(delete).styled(s -> s.withUnderline(delHover)), rx, y + 5, confirmDeleteGroup ? NAUGHTY : LINK, false);
		hit(rx, y + 4, textRenderer.getWidth(delete), 11, "group-delete", () -> {
			if (!confirmDeleteGroup) {
				confirmDeleteGroup = true;
				return;
			}
			confirmDeleteGroup = false;
			Runnable undo = mod.ledger.deleteGroup(g);
			mod.ledger.save(false);
			openGroup = null;
			cachedResults = null;
			flash("Deleted the group " + g + " (its members are still on your lists)", MUTED, () -> {
				undo.run();
				mod.ledger.save(false);
				cachedResults = null;
				flash("Undone", MUTED, null);
			});
		});
		int rename = rx - 12 - textRenderer.getWidth("Rename");
		link(context, mouseX, mouseY, rename, y + 5, "Rename", "group-rename", () -> openRename(g));
		int addW = textRenderer.getWidth("+ Add members") + 12;
		pill(context, mouseX, mouseY, x + w - 8 - addW, y + 24, addW, 14, "+ Add members", YELLOW, YELLOW_EDGE, BUTTON_TEXT, "group-add-members",
				() -> openGroupPanel(g, null));
		// Alerts: a bell (at the group's own pitch) when one of them joins or comes near.
		boolean alerts = mod.ledger.alertsFor(g);
		String bell = alerts ? "♪ Alerts on" : "♪ Alert me";
		int bw = textRenderer.getWidth(bell) + 12;
		pill(context, mouseX, mouseY, x + w - 8 - addW - 6 - bw, y + 24, bw, 14, bell, alerts ? tint(color, CARD, 0.3f) : SOFT, alerts ? color : BORDER,
				alerts ? TEXT : LINK, "group-alerts", () -> {
					mod.ledger.setAlerts(g, !alerts);
					mod.ledger.save(false);
					flash(!alerts ? "You'll hear a bell when someone from " + g + " joins or comes near" : "No more alerts for " + g, !alerts ? NICE : MUTED, null);
				});
		return y + h;
	}

	// ---- The group panel ----

	/**
	 * Opens the panel to put people in a group. {@code group} is the group to add to (null to pick or make one);
	 * {@code player} is filled in as the first member.
	 */
	private void openGroupPanel(String group, Profile player) {
		grouping = true;
		groupMode = GroupMode.ADD;
		groupPanelGroup = group;
		groupStatus = null;
		groupField.setText("");
		membersField.setText(player == null ? "" : player.name + ", ");
		membersField.setCursorToEnd(false);
		setFocused(group == null ? groupField : membersField);
	}

	private void openRename(String group) {
		grouping = true;
		groupMode = GroupMode.RENAME;
		groupPanelGroup = group;
		groupStatus = null;
		groupField.setText(group);
		groupField.setCursorToEnd(false);
		setFocused(groupField);
	}

	private void closeGroupPanel() {
		grouping = false;
		groupStatus = null;
		setFocused(null);
	}

	/** Saves the group panel: renames, or adds everyone typed to the group. */
	private void saveGroup() {
		if (groupMode == GroupMode.RENAME) {
			String to = Ledger.cleanGroupName(groupField.getText());
			if (to.isEmpty()) {
				groupStatus = "Give the group a name.";
				groupStatusColor = NAUGHTY;
				return;
			}
			String from = groupPanelGroup;
			if (!mod.ledger.renameGroup(from, to)) {
				groupStatus = "There's already a group called " + mod.ledger.groupName(to) + ".";
				groupStatusColor = NAUGHTY;
				return;
			}
			mod.ledger.save(false);
			if (from.equalsIgnoreCase(openGroup)) openGroup = to;
			closeGroupPanel();
			cachedResults = null;
			flash("Renamed " + from + " to " + to, NICE, () -> {
				mod.ledger.renameGroup(to, from);
				mod.ledger.save(false);
				if (to.equalsIgnoreCase(openGroup)) openGroup = from;
				flash("Undone", MUTED, null);
			});
			return;
		}
		String group = groupPanelGroup != null ? groupPanelGroup : Ledger.cleanGroupName(groupField.getText());
		if (group.isEmpty()) {
			groupStatus = "Give the group a name, or pick one of yours.";
			groupStatusColor = NAUGHTY;
			setFocused(groupField);
			return;
		}
		List<String> added = new ArrayList<>();
		List<String> already = new ArrayList<>();
		List<String> bad = new ArrayList<>();
		List<Runnable> undo = new ArrayList<>();
		for (String raw : membersField.getText().split("[\\s,;]+")) {
			String name = raw.trim();
			if (name.isEmpty()) continue;
			if (!name.matches(NAME_PATTERN)) {
				bad.add(name);
				continue;
			}
			boolean known = mod.ledger.byName(name) != null;
			Profile p = mod.ledger.addByName(client, name);
			if (!mod.ledger.addToGroup(p, group)) {
				already.add(p.name);
				continue;
			}
			added.add(p.name);
			undo.add(known ? () -> mod.ledger.removeFromGroup(p, group) : () -> mod.ledger.forget(p));
		}
		if (added.isEmpty()) {
			groupStatus = !bad.isEmpty() ? "Not a player name: " + join(bad) + "."
					: !already.isEmpty() ? join(already) + (already.size() == 1 ? " is" : " are") + " already in it."
					: "Type who's in the group.";
			groupStatusColor = bad.isEmpty() ? MUTED : NAUGHTY;
			setFocused(membersField);
			return;
		}
		mod.ledger.save(false);
		cachedResults = null;
		String name = mod.ledger.groupName(group);
		closeGroupPanel();
		flash("Added " + join(added) + " to " + name, NICE, () -> {
			for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).run();
			mod.ledger.save(false);
			cachedResults = null;
			if (openGroup != null && mod.ledger.groupName(openGroup) == null) openGroup = null;
			flash("Undone", MUTED, null);
		});
	}

	private void renderGroupPanel(DrawContext context, int mouseX, int mouseY) {
		panelHits.clear();
		context.fill(0, 0, width, height, 0x99000000);
		boolean rename = groupMode == GroupMode.RENAME;
		int w = Math.min(330, width - 16);
		int h = rename ? 112 : groupPanelGroup != null ? 160 : 212;
		int x = (width - w) / 2;
		int y = Math.max(4, (height - h) / 2);
		roundedBorder(context, x, y, w, h, CARD, BORDER);
		context.fill(x + 1, y + 2, x + w - 1, y + 22, SOFT);
		context.fill(x + 1, y + 22, x + w - 1, y + 23, BORDER);
		String title = rename ? "Rename " + groupPanelGroup : groupPanelGroup != null ? "Add members to " + groupPanelGroup : "Put players in a group";
		context.drawText(textRenderer, Text.literal(fit(title, w - 40)).styled(s -> s.withBold(true)), x + 10, y + 8, TEXT, false);
		int closeX = x + w - 16;
		boolean closeHover = over(mouseX, mouseY, closeX - 3, y + 3, 14, 16);
		text(context, "✖", closeX, y + 8, closeHover ? TEXT : MUTED);
		panelHits.add(new Hit(closeX - 3, y + 3, closeX + 11, y + 19, "group-close", this::closeGroupPanel));

		int ix = x + 10;
		int iw = w - 20;
		int ty = y + 30;
		groupFieldTop = -1000;
		membersFieldTop = -1000;
		if (rename || groupPanelGroup == null) {
			text(context, rename ? "New name" : "Group name (new, or pick one below)", ix, ty, MUTED);
			ty += 11;
			roundedBorder(context, ix, ty, iw, 16, FIELD, groupField.isFocused() ? 0xFFE77600 : INPUT_BORDER);
			groupField.setX(ix + 4);
			groupField.setY(ty + 4);
			groupField.setWidth(iw - 8);
			groupFieldTop = ty;
			ty += 20;
		}
		if (!rename && groupPanelGroup == null) {
			// Your groups, to pick one; matching what's typed.
			String typed = groupField.getText().strip().toLowerCase(Locale.ROOT);
			List<String> picks = new ArrayList<>();
			for (String g : mod.ledger.groups().keySet()) {
				if (typed.isEmpty() || g.toLowerCase(Locale.ROOT).contains(typed)) picks.add(g);
			}
			int sx = ix;
			int row = 0;
			if (picks.isEmpty()) {
				textTrim(context, mod.ledger.groups().isEmpty() ? "Your first group: type its name above." : "A new group: \"" + groupField.getText().strip() + "\"",
						ix, ty + 2, iw, MUTED);
			}
			for (String g : picks) {
				int n = mod.ledger.members(g).size();
				String label = g + " " + n;
				int cw = textRenderer.getWidth(label) + 14;
				if (sx + cw > ix + iw) {
					if (++row >= 2) break;
					sx = ix;
				}
				int cy = ty + row * 14;
				int color = groupColor(g);
				boolean hover = over(mouseX, mouseY, sx, cy, cw, 12);
				boolean picked = g.equalsIgnoreCase(groupField.getText().strip());
				roundedBorder(context, sx, cy, cw, 12, tint(color, CARD, picked ? 0.35f : hover ? 0.24f : 0.12f), picked ? color : tint(color, CARD, 0.55f));
				context.fill(sx + 3, cy + 3, sx + 8, cy + 8, color);
				text(context, g, sx + 11, cy + 2, color);
				text(context, String.valueOf(n), sx + 11 + textRenderer.getWidth(g + " "), cy + 2, MUTED);
				panelHits.add(new Hit(sx, cy, sx + cw, cy + 12, "pick-group:" + g, () -> {
					groupField.setText(g);
					setFocused(membersField);
				}));
				sx += cw + 4;
			}
			ty += 32;
		}
		if (!rename) {
			text(context, "Who's in it (several? separate with spaces or commas)", ix, ty, MUTED);
			ty += 11;
			roundedBorder(context, ix, ty, iw, 16, FIELD, membersField.isFocused() ? 0xFFE77600 : INPUT_BORDER);
			membersField.setX(ix + 4);
			membersField.setY(ty + 4);
			membersField.setWidth(iw - 8);
			membersFieldTop = ty;
			ty += 20;
			// Name suggestions: nearby first, so a crew standing together is quick to add.
			int sx = ix;
			String group = groupPanelGroup != null ? groupPanelGroup : groupField.getText().strip();
			List<String> suggestions = suggestions(membersField);
			suggestions.removeIf(n -> {
				Profile known = mod.ledger.byName(n);
				return known != null && !group.isEmpty() && known.inGroup(group);
			});
			if (suggestions.isEmpty()) textTrim(context, "Type any player name, online or not.", ix, ty + 2, iw, MUTED);
			for (String name : suggestions) {
				Profile known = mod.ledger.byName(name);
				Profile.Verdict v = known == null ? Profile.Verdict.NONE : known.verdict;
				String label = verdictMark(v) + name;
				int cw = textRenderer.getWidth(label) + 8;
				if (sx + cw > ix + iw) break;
				boolean hover = over(mouseX, mouseY, sx, ty, cw, 12);
				roundedBorder(context, sx, ty, cw, 12, HOVER_BG, hover ? LINK : BORDER);
				text(context, label, sx + 4, ty + 2, v == Profile.Verdict.NONE ? LINK : verdictColor(v));
				panelHits.add(new Hit(sx, ty, sx + cw, ty + 12, "suggest:" + name, () -> pickSuggestion(membersField, name)));
				sx += cw + 4;
			}
			ty += 18;
		}
		int bw = (iw - 6) / 2;
		shareButton(context, mouseX, mouseY, ix, ty, bw, rename ? "Save name" : "Save group", YELLOW, YELLOW_EDGE, BUTTON_TEXT, "group-save", this::saveGroup);
		shareButton(context, mouseX, mouseY, ix + bw + 6, ty, iw - bw - 6, "Cancel", SOFT, BORDER, TEXT, "group-cancel", this::closeGroupPanel);
		ty += 22;
		if (groupStatus != null) panelText(context, groupStatus, ix, ty, iw, groupStatusColor, 2);
		textTrim(context, "Enter saves · Tab next box · Esc closes", ix, y + h - 13, iw, MUTED);
	}

	private boolean groupClicked(Click click, boolean doubled) {
		double mx = click.x();
		double my = click.y();
		for (Hit hit : List.copyOf(panelHits).reversed()) {
			if (mx >= hit.x0 && mx < hit.x1 && my >= hit.y0 && my < hit.y1) {
				hit.run.run();
				return true;
			}
		}
		for (TextFieldWidget field : new TextFieldWidget[] {groupField, membersField}) {
			int top = field == groupField ? groupFieldTop : membersFieldTop;
			if (mx >= field.getX() - 4 && mx < field.getX() + field.getWidth() + 4 && my >= top && my < top + 16) {
				setFocused(field);
				field.mouseClicked(click, doubled);
				return true;
			}
		}
		return true;
	}

	private boolean groupKey(KeyInput input) {
		switch (input.key()) {
			case GLFW.GLFW_KEY_ESCAPE -> closeGroupPanel();
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				// Enter in the name box of a new group moves on to the members.
				if (getFocused() == groupField && groupMode == GroupMode.ADD && membersField.getText().isBlank()) setFocused(membersField);
				else saveGroup();
			}
			case GLFW.GLFW_KEY_TAB -> {
				List<String> suggestions = suggestions(membersField);
				if (getFocused() == membersField && !lastToken(membersField).isEmpty() && !suggestions.isEmpty()
						&& !suggestions.get(0).equalsIgnoreCase(lastToken(membersField))) {
					pickSuggestion(membersField, suggestions.get(0));
				} else if (groupMode == GroupMode.ADD && groupPanelGroup == null) {
					setFocused(getFocused() == groupField ? membersField : groupField);
				}
			}
			default -> {
				if (getFocused() != null) return getFocused().keyPressed(input);
			}
		}
		return true;
	}

	// ---- The Chat tab ----

	/** Sent messages, for Up and Down in the chat box. */
	private int historyIndex = -1;
	/** Chat lines already seen on the Chat tab, so the tab can show a dot for new ones. */
	private static int chatSeen;

	/** Whose line it is: their profile, or null if it was you or someone we don't know. For a whisper you sent, who it went to. */
	private Profile chatWho(Profile.Line line) {
		if (line.me && !line.whisper) return null;
		return mod.ledger.byName(line.sender);
	}

	private boolean chatShows(Profile.Line line, Profile who) {
		// Dividers ("You joined") show whenever nothing narrows the chat down.
		if (ChatLogger.isMarker(line)) return chatFilter == ChatFilter.ALL && chatGroup == null && query.isEmpty();
		boolean me = line.me && !line.whisper;
		Profile.Verdict v = who == null ? Profile.Verdict.NONE : who.verdict;
		if (chatGroup != null && (who == null || !who.inGroup(chatGroup))) return false;
		boolean shown = switch (chatFilter) {
			case ALL -> NNConfig.INSTANCE.chatShowEveryone || me || v != Profile.Verdict.NONE || (who != null && !who.groups.isEmpty());
			case LISTED -> v != Profile.Verdict.NONE;
			case FRIENDS -> v == Profile.Verdict.FRIEND;
			case NICE -> v == Profile.Verdict.NICE;
			case LOOK -> v == Profile.Verdict.LOOK;
			case NAUGHTY -> v == Profile.Verdict.NAUGHTY;
			case UNLISTED -> !me && v == Profile.Verdict.NONE;
			case WHISPERS -> line.whisper;
		};
		if (!shown) return false;
		if (query.isEmpty()) return true;
		String q = query.toLowerCase(Locale.ROOT);
		return line.sender.toLowerCase(Locale.ROOT).contains(q) || line.text.toLowerCase(Locale.ROOT).contains(q)
				|| (who != null && who.groupsContain(q));
	}

	/** The line as drawn after the time and badge: group tags, name, message. */
	private Text chatText(Profile.Line line, Profile who) {
		net.minecraft.text.MutableText out = Text.empty();
		if (who != null) {
			for (String g : who.groups) {
				int c = groupColor(g);
				out.append(Text.literal("[" + (g.length() > 14 ? g.substring(0, 13) + "…" : g) + "] ").styled(s -> s.withColor(c)));
			}
		}
		if (line.system) {
			String name = who != null ? who.name : line.sender;
			int c = who == null || who.verdict == Profile.Verdict.NONE ? TEXT : verdictColor(who.verdict);
			out.append(Text.literal(name).styled(s -> s.withColor(c).withBold(who != null && who.verdict != Profile.Verdict.NONE)));
			out.append(Text.literal(" " + line.text).styled(s -> s.withColor(MUTED).withItalic(true)));
			return out;
		}
		boolean me = line.me;
		Profile.Verdict v = who == null ? Profile.Verdict.NONE : who.verdict;
		int nameColor = me && !line.whisper ? LINK : v == Profile.Verdict.NONE ? TEXT : verdictColor(v);
		if (line.whisper) {
			String other = who != null ? who.name : line.sender;
			if (me) {
				out.append(Text.literal("You → ").styled(s -> s.withColor(WHISPER)));
				out.append(Text.literal(other).styled(s -> s.withColor(v == Profile.Verdict.NONE ? WHISPER : nameColor).withBold(true)));
			} else {
				out.append(Text.literal(other).styled(s -> s.withColor(v == Profile.Verdict.NONE ? WHISPER : nameColor).withBold(true)));
				out.append(Text.literal(" → you").styled(s -> s.withColor(WHISPER)));
			}
			out.append(Text.literal(": ").styled(s -> s.withColor(WHISPER)));
			out.append(Text.literal(line.text).styled(s -> s.withColor(TEXT).withItalic(true)));
			return out;
		}
		String name = me ? "You" : who != null ? who.name : line.sender;
		out.append(Text.literal(name).styled(s -> s.withColor(nameColor).withBold(v != Profile.Verdict.NONE || me)));
		out.append(Text.literal(": ").styled(s -> s.withColor(MUTED)));
		out.append(Text.literal(line.text).styled(s -> s.withColor(TEXT)));
		if (line.alert != null) out.append(Text.literal("  ⚑ " + line.alert).styled(s -> s.withColor(LOOK).withItalic(true)));
		return out;
	}

	/** The chat rows to draw, redone only when the chat, the filter, the width or the lists change. */
	private List<ChatRow> chatRows(int textW) {
		String key = mod.chatLogger.feedVersion() + "/" + chatFilter + "/" + chatGroup + "/" + textW + "/" + query + "/" + mod.ledger.changes()
				+ "/" + NNConfig.INSTANCE.darkMode + "/" + NNConfig.INSTANCE.chatShowEveryone;
		if (key.equals(chatRowsKey)) return chatRows;
		int before = 0;
		for (ChatRow row : chatRows) before += row.h;
		List<ChatRow> rows = new ArrayList<>();
		int after = 0;
		for (Profile.Line line : mod.chatLogger.feed()) {
			Profile who = ChatLogger.isMarker(line) ? null : chatWho(line);
			if (!chatShows(line, who)) continue;
			if (ChatLogger.isMarker(line)) {
				rows.add(new ChatRow(line, null, List.of(), 16));
				after += 16;
				continue;
			}
			List<net.minecraft.text.OrderedText> wrapped = textRenderer.wrapLines(chatText(line, who), textW);
			int h = Math.max(1, wrapped.size()) * 10 + 3;
			rows.add(new ChatRow(line, who, wrapped, h));
			after += h;
		}
		// Scrolled up reading: keep the same lines in view as new ones arrive below.
		boolean sameView = chatRowsKey != null && chatRowsKey.substring(chatRowsKey.indexOf('/')).equals(key.substring(key.indexOf('/')));
		if (chatScroll > 0 && sameView && after > before) {
			chatScroll += after - before;
			chatUnread += rows.size() - chatRows.size();
		}
		chatRows = rows;
		chatRowsKey = key;
		return rows;
	}

	private void renderChat(DrawContext context, int mouseX, int mouseY) {
		chatSeen = mod.chatLogger.feedVersion();
		int barY = BODY;
		context.fill(0, barY, width, barY + 16, CARD);
		context.fill(0, barY + 16, width, barY + 17, BORDER);
		boolean sidebar = width >= 380;
		int left = sidebar ? 8 + SIDEBAR + 8 : 8;
		int right = width - 8;
		int top = barY + 17;
		int inputTop = height - 22;

		int timeW = textRenderer.getWidth("00:00") + 5;
		int badgeW = 12;
		int textX = left + 4 + timeW + badgeW;
		int textW = right - textX - 4;
		List<ChatRow> rows = chatRows(textW);
		int total = mod.chatLogger.feed().size();
		int days = NNConfig.INSTANCE.chatHistoryDays;
		String summary = rows.size() == total ? total + (total == 1 ? " line" : " lines") + " from the last " + (days == 1 ? "day" : days + " days")
				: rows.size() + " of " + total + " lines";
		if (chatFilter != ChatFilter.ALL || chatGroup != null) {
			summary += " · " + (chatGroup != null ? chatGroup + (chatFilter != ChatFilter.ALL ? ", " + chatFilter.label : "") : chatFilter.label);
		}
		if (!query.isEmpty()) summary += " · matching \"" + query + "\"";
		int clearW = chatFilter != ChatFilter.ALL || chatGroup != null ? textRenderer.getWidth("Show all") + 12 : 0;
		textTrim(context, summary, 8, barY + 4, width - 24 - clearW - (sidebar ? 0 : 0), TEXT);
		if (clearW > 0) {
			link(context, mouseX, mouseY, right - clearW + 12, barY + 4, "Show all", "chat-all", () -> {
				chatFilter = ChatFilter.ALL;
				chatGroup = null;
				chatScroll = 0;
			});
		}

		context.enableScissor(0, top, width, inputTop - 2);
		if (sidebar) renderChatSidebar(context, mouseX, mouseY, 8, top + 6, inputTop - 2);
		rounded(context, left, top + 4, right - left, inputTop - top - 8, CARD);

		int viewTop = top + 6;
		int viewBottom = inputTop - 6;
		int totalH = 0;
		for (ChatRow row : rows) totalH += row.h;
		chatMaxScroll = Math.max(0, totalH - (viewBottom - viewTop));
		chatScroll = Math.max(0, Math.min(chatMaxScroll, chatScroll));
		if (chatScroll == 0) chatUnread = 0;
		context.enableScissor(left, viewTop, right, viewBottom);
		if (rows.isEmpty()) {
			String empty = total == 0
					? "Nothing said yet. Chat shows up here as it happens, each name marked with its list and groups so you can tell who's who."
					: "Nothing matches. Pick All on the left to see everything.";
			int ty = viewTop + 8;
			for (var line : textRenderer.wrapLines(Text.literal(empty), right - left - 20)) {
				context.drawText(textRenderer, line, left + 10, ty, MUTED, false);
				ty += 10;
			}
		}
		int y = viewBottom + (int) chatScroll;
		for (int i = rows.size() - 1; i >= 0 && y > viewTop; i--) {
			ChatRow row = rows.get(i);
			y -= row.h;
			if (y > viewBottom) continue;
			chatLine(context, mouseX, mouseY, row, left + 2, y, right - left - 4, timeW, badgeW, viewTop, viewBottom);
		}
		context.disableScissor();
		context.disableScissor();

		// Scrolled up with new lines below: a pill to jump down.
		if (chatScroll > 0) {
			String jump = chatUnread > 0 ? "▼ " + chatUnread + " new" : "▼ Newest";
			int jw = textRenderer.getWidth(jump) + 14;
			pill(context, mouseX, mouseY, left + (right - left - jw) / 2, viewBottom - 18, jw, 14, jump, LINK, LINK, CARD, "chat-newest", () -> {
				chatScroll = 0;
				chatUnread = 0;
			});
		}
		if (chatMaxScroll > 0) {
			int view = viewBottom - viewTop;
			int bar = Math.max(16, (int) (view * (double) view / (view + chatMaxScroll)));
			int barTop = viewTop + (int) ((view - bar) * (1 - chatScroll / chatMaxScroll));
			context.fill(right - 3, barTop, right - 1, barTop + bar, INPUT_BORDER);
		}

		// The box to talk in.
		boolean connected = client.getNetworkHandler() != null;
		String send = "Send";
		int sendW = textRenderer.getWidth(send) + 20;
		int fieldW = right - left - sendW - 6;
		roundedBorder(context, left, inputTop, fieldW, 16, FIELD, chatField.isFocused() ? 0xFFE77600 : INPUT_BORDER);
		chatField.setX(left + 4);
		chatField.setY(inputTop + 4);
		chatField.setWidth(fieldW - 8);
		chatField.setEditable(connected);
		hits.add(new Hit(left, inputTop, left + fieldW, inputTop + 16, "chat-box", () -> setFocused(chatField)));
		pill(context, mouseX, mouseY, right - sendW, inputTop, sendW, 16, send, connected ? YELLOW : SOFT, connected ? YELLOW_EDGE : BORDER,
				connected ? BUTTON_TEXT : MUTED, "chat-send", this::sendChat);
		contentHeight = 0;
	}

	/** One chat line: time, a badge for their list, then tags, name and message. Hover for quick list buttons. */
	private void chatLine(DrawContext context, int mouseX, int mouseY, ChatRow row, int x, int y, int w, int timeW, int badgeW, int viewTop, int viewBottom) {
		Profile.Line line = row.line;
		Profile who = row.who;
		if (ChatLogger.isMarker(line)) {
			// A divider: "── Tue 21 Oct, 14:02 · You joined constantiam.net ──".
			String label = day(line.time) + ", " + clock(line.time) + " · " + line.text;
			label = fit(label, w - 40);
			int lw = textRenderer.getWidth(label);
			int mid = x + w / 2;
			context.fill(x + 4, y + 8, mid - lw / 2 - 6, y + 9, BORDER);
			context.fill(mid + lw / 2 + 6, y + 8, x + w - 4, y + 9, BORDER);
			text(context, label, mid - lw / 2, y + 4, MUTED);
			return;
		}
		boolean me = line.me && !line.whisper;
		boolean hover = over(mouseX, mouseY, x, y, w, row.h) && mouseY >= viewTop && mouseY < viewBottom;
		Profile.Verdict v = who == null ? Profile.Verdict.NONE : who.verdict;
		if (hover) context.fill(x, y, x + w, y + row.h, HOVER_BG);
		else if (line.alert != null) context.fill(x, y, x + w, y + row.h, HIGHLIGHT);
		else if (v == Profile.Verdict.NAUGHTY) context.fill(x, y, x + w, y + row.h, tint(NAUGHTY, CARD, 0.07f));
		else if (v == Profile.Verdict.LOOK) context.fill(x, y, x + w, y + row.h, tint(LOOK, CARD, 0.07f));
		if (line.whisper) context.fill(x, y, x + 2, y + row.h, WHISPER);
		text(context, clock(line.time), x + 3, y + 2, MUTED);
		if (line.alert != null) context.fill(x, y, x + 2, y + row.h, LOOK);
		// The badge: ★ Friend, ✔ Nice, ? Look Into, ✖ Naughty, • not listed, ◆ you; arrows for joins and leaves.
		int bx = x + 3 + timeW;
		String mark;
		int color;
		if (me) {
			mark = "◆";
			color = LINK;
		} else if (v == Profile.Verdict.NONE) {
			mark = line.system ? (line.text.startsWith("left") ? "←" : "→") : "•";
			color = MUTED;
		} else {
			mark = verdictMark(v).strip();
			color = verdictColor(v);
		}
		if (v != Profile.Verdict.NONE || me) {
			rounded(context, bx - 1, y + 1, 10, 10, tint(color, CARD, 0.2f));
		}
		text(context, mark, bx + (8 - textRenderer.getWidth(mark)) / 2, y + 2, color);
		int ty = y + 2;
		for (var part : row.wrapped) {
			context.drawText(textRenderer, part, bx + badgeW, ty, TEXT, false);
			ty += 10;
		}
		if (!hover || me) return;
		// Quick buttons on the right: Friend, Nice, Look Into, Naughty, for whoever said it.
		String name = who != null ? who.name : line.sender;
		Profile.Verdict[] order = {Profile.Verdict.FRIEND, Profile.Verdict.NICE, Profile.Verdict.LOOK, Profile.Verdict.NAUGHTY};
		int qx = x + w - order.length * 14 - 4;
		rounded(context, qx - 3, y, order.length * 14 + 6, Math.min(row.h, 12), CARD);
		for (int i = 0; i < order.length; i++) {
			Profile.Verdict target = order[i];
			int ox = qx + i * 14;
			boolean on = v == target;
			boolean bHover = over(mouseX, mouseY, ox, y, 12, 11);
			int c = verdictColor(target);
			roundedBorder(context, ox, y, 12, 11, on ? c : bHover ? tint(c, CARD, 0.3f) : tint(c, CARD, 0.12f), c);
			String m = verdictMark(target).strip();
			text(context, m, ox + (12 - textRenderer.getWidth(m)) / 2 + 1, y + 2, on ? CARD : c);
			hits.add(new Hit(ox, Math.max(y, viewTop), ox + 12, Math.min(y + 11, viewBottom), "chat-" + target.name().toLowerCase(Locale.ROOT) + ":" + name, () -> {
				Profile p = who != null ? who : addByName(name);
				setVerdict(p, target);
			}));
		}
		// Anywhere else on the line opens their page.
		hits.add(new Hit(x, Math.max(y, viewTop), qx - 3, Math.min(y + row.h, viewBottom), "chat-line:" + name, () -> {
			Profile p = who != null ? who : mod.ledger.byName(name);
			if (p != null) open(p);
		}));
	}

	/** The Chat tab's sidebar: what to show, the key to the marks, who's talking, and groups. */
	private void renderChatSidebar(DrawContext context, int mouseX, int mouseY, int x, int y, int bottom) {
		context.drawText(textRenderer, Text.literal("Show").styled(s -> s.withBold(true)), x, y, TEXT, false);
		y += 12;
		for (ChatFilter f : ChatFilter.values()) {
			boolean on = chatFilter == f;
			boolean hover = over(mouseX, mouseY, x, y - 1, SIDEBAR, 11);
			int color = switch (f) {
				case FRIENDS -> FRIEND;
				case NICE -> NICE;
				case LOOK -> LOOK;
				case NAUGHTY -> NAUGHTY;
				case WHISPERS -> WHISPER;
				default -> LINK;
			};
			String mark = switch (f) {
				case FRIENDS -> "★ ";
				case NICE -> "✔ ";
				case LOOK -> "? ";
				case NAUGHTY -> "✖ ";
				case UNLISTED -> "• ";
				default -> "";
			};
			context.drawText(textRenderer, Text.literal((on ? "› " : "") + mark + f.label).styled(s -> s.withBold(on).withUnderline(hover)), x, y,
					hover ? HOVER_LINK : on ? TEXT : color, false);
			hits.add(new Hit(x, Math.max(y - 1, BODY + 17), x + SIDEBAR, y + 10, "chat-filter:" + f.name(), () -> {
				chatFilter = f;
				chatScroll = 0;
				chatUnread = 0;
			}));
			y += 11;
		}
		Map<String, List<Profile>> groups = mod.ledger.groups();
		if (!groups.isEmpty()) {
			y += 6;
			context.drawText(textRenderer, Text.literal("Groups").styled(s -> s.withBold(true)), x, y, TEXT, false);
			y += 12;
			int shown = 0;
			for (String g : groups.keySet()) {
				if (shown++ >= 4) break;
				boolean on = g.equalsIgnoreCase(chatGroup);
				boolean hover = over(mouseX, mouseY, x, y - 1, SIDEBAR, 11);
				int color = groupColor(g);
				context.fill(x, y + 1, x + 7, y + 8, color);
				context.drawText(textRenderer, Text.literal(fit(g, SIDEBAR - 10)).styled(s -> s.withBold(on).withUnderline(hover)), x + 10, y,
						hover ? HOVER_LINK : on ? TEXT : LINK, false);
				hits.add(new Hit(x, Math.max(y - 1, BODY + 17), x + SIDEBAR, y + 10, "chat-group:" + g, () -> {
					chatGroup = on ? null : g;
					chatScroll = 0;
					chatUnread = 0;
				}));
				y += 11;
			}
		}

		// The marks not already shown above, and how to use the lines.
		y += 6;
		context.drawText(textRenderer, Text.literal("Key").styled(s -> s.withBold(true)), x, y, TEXT, false);
		y += 12;
		text(context, "◆", x, y, LINK);
		text(context, "You  ", x + 10, y, MUTED);
		int gx = x + 10 + textRenderer.getWidth("You  ");
		text(context, "•", gx, y, MUTED);
		text(context, "No list", gx + 8, y, MUTED);
		y += 10;
		text(context, "[Tag] = their group", x, y, MUTED);
		y += 10;
		for (var line : textRenderer.wrapLines(Text.literal("Hover a line to list them; click it for their page."), SIDEBAR)) {
			context.drawText(textRenderer, line, x, y, MUTED, false);
			y += 10;
		}

		// Who's been talking lately, marked, as many as fit.
		List<Profile> talking = new ArrayList<>();
		long since = System.currentTimeMillis() - 10 * 60_000L;
		List<Profile.Line> feed = mod.chatLogger.feed();
		int room = (bottom - y - 18) / 11;
		for (int i = feed.size() - 1; i >= 0 && talking.size() < room; i--) {
			Profile.Line line = feed.get(i);
			if (line.time < since) break;
			if (line.system || line.me) continue;
			Profile who = mod.ledger.byName(line.sender);
			if (who != null && !talking.contains(who)) talking.add(who);
		}
		if (!talking.isEmpty()) {
			y += 6;
			context.drawText(textRenderer, Text.literal("Talking lately").styled(s -> s.withBold(true)), x, y, TEXT, false);
			y += 12;
			for (Profile p : talking) {
				boolean hover = over(mouseX, mouseY, x, y - 1, SIDEBAR, 11);
				int color = p.verdict == Profile.Verdict.NONE ? LINK : verdictColor(p.verdict);
				String label = fit((p.verdict == Profile.Verdict.NONE ? "• " : verdictMark(p.verdict)) + p.name, SIDEBAR);
				context.drawText(textRenderer, Text.literal(label).styled(s -> s.withUnderline(hover)), x, y, hover ? HOVER_LINK : color, false);
				hits.add(new Hit(x, Math.max(y - 1, BODY + 17), x + SIDEBAR, y + 10, "talking:" + p.name, () -> open(p)));
				y += 11;
			}
		}
	}

	/** Sends what's in the chat box: a message, or a command if it starts with /. */
	private void sendChat() {
		String text = chatField.getText().strip();
		if (text.isEmpty() || client.getNetworkHandler() == null) {
			setFocused(chatField);
			return;
		}
		client.inGameHud.getChatHud().addToMessageHistory(text);
		if (text.startsWith("/")) client.getNetworkHandler().sendChatCommand(text.substring(1));
		else client.getNetworkHandler().sendChatMessage(text);
		chatField.setText("");
		historyIndex = -1;
		chatScroll = 0;
		chatUnread = 0;
		setFocused(chatField);
	}

	/** Up and Down in the chat box step through what you've sent, like the game's chat. */
	private void chatHistory(int step) {
		List<String> history = client.inGameHud.getChatHud().getMessageHistory();
		if (history.isEmpty()) return;
		int next = historyIndex == -1 ? (step < 0 ? history.size() - 1 : -1) : historyIndex + step;
		if (next < 0 || next >= history.size()) {
			historyIndex = -1;
			chatField.setText("");
			return;
		}
		historyIndex = next;
		chatField.setText(history.get(next));
		chatField.setCursorToEnd(false);
	}

	// ---- Settings ----

	private record Option(String label, String help, BooleanSupplier get, Consumer<Boolean> set) {}

	private List<Option> options() {
		NNConfig c = NNConfig.INSTANCE;
		List<Option> options = new ArrayList<>();
		options.add(new Option("Dark mode", "Dark colours for this screen. Also on the ☾ / ☀ button at the top.", () -> c.darkMode, v -> {
			c.darkMode = v;
			applyTheme(v);
			client.execute(this::clearAndInit);
		}));
		options.add(new Option("Remember everyone you see", "Players you see nearby or on the tab list stay in your lists (Offline tab) after they log off.",
				() -> c.rememberEveryone, v -> c.rememberEveryone = v));
		options.add(new Option("Marks on nametags", "Green ✔, gold ? or red ✖ before listed players' names above their heads.", () -> c.nametagMarks, v -> c.nametagMarks = v));
		options.add(new Option("Marks in the tab list", "The same marks in the player list (Tab).", () -> c.tabListMarks, v -> c.tabListMarks = v));
		options.add(new Option("Group tags on names", "A player's group, e.g. [SCG], after the mark on nametags and in the tab list.", () -> c.groupTags, v -> c.groupTags = v));
		options.add(new Option("Warn when a naughty player is near", "Chat message and sound when someone on your Naughty list comes into view.", () -> c.warnNaughtyNearby, v -> c.warnNaughtyNearby = v));
		options.add(new Option("Warn when a naughty player joins", "When someone on your Naughty list joins the server.", () -> c.warnNaughtyJoin, v -> c.warnNaughtyJoin = v));
		options.add(new Option("Note when a Look Into player is near or joins", "A quiet chat note (no sound) for people you're keeping an eye on.",
				() -> c.noteLookInto, v -> c.noteLookInto = v));
		options.add(new Option("Warning sound", "Play a low note with warnings.", () -> c.warnSounds, v -> c.warnSounds = v));
		options.add(new Option("Killers go on the Naughty list", "Anyone not on a list who kills you is added automatically (you can undo it).", () -> c.autoNaughtyKillers, v -> c.autoNaughtyKillers = v));
		options.add(new Option("Read the chat", "Friendly words (thanks, gg, np) count for players; trash talk counts against them.", () -> c.watchChat, v -> c.watchChat = v));
		options.add(new Option("Log chat of listed players", "Everything said around anyone on your lists is saved as conversations on their page.", () -> c.chatLog, v -> c.chatLog = v));
		options.add(new Option("Chat tab shows everyone", "Off: the Chat tab's All shows only players on a list or in a group (and you).",
				() -> c.chatShowEveryone, v -> c.chatShowEveryone = v));
		options.add(new Option("Save chat logs as text files", "Each finished conversation is also added to a .txt file per player.", () -> c.chatFiles, v -> c.chatFiles = v));
		options.add(new Option("Save all chat as text files", "Everything said on the server goes into a file a day (chatlogs/<server>/all-chat), so nothing is lost.",
				() -> c.chatAllFiles, v -> c.chatAllFiles = v));
		options.add(new Option("Summary when you join", "Who's online from your lists and groups, said once when you join a server.", () -> c.joinSummary, v -> c.joinSummary = v));
		options.add(new Option("Note sightings", "Where and when players come into view, and what armour and items they have. On each player's Where & when.",
				() -> c.recordSightings, v -> c.recordSightings = v));
		options.add(new Option("Track who plays together", "Who logs on and off together, plays near each other, or takes turns like alts. Suggests groups.",
				() -> c.trackLinks, v -> c.trackLinks = v));
		options.add(new Option("Track hours online", "What times of day each player is usually on (while you are).", () -> c.trackHours, v -> c.trackHours = v));
		options.add(new Option("Evidence screenshots", "A screenshot when someone kills you, or a Naughty / Look Into player comes into your base. Listed on their page.",
				() -> c.evidenceShots, v -> c.evidenceShots = v));
		if (BaseInsuranceBridge.present()) {
			options.add(new Option("Alert when your bases are named", "A ping when someone says the name of one of your Base Insurance bases in chat.",
					() -> c.alertBaseNames, v -> c.alertBaseNames = v));
			options.add(new Option("Alert on coordinates near your bases", "A ping when someone posts coordinates within 300 blocks of one of your bases "
					+ "(nether or overworld).", () -> c.alertBaseCoords, v -> c.alertBaseCoords = v));
		}
		if (BaseInsuranceBridge.present()) {
			options.add(new Option("Nice list counts as Base Insurance friends", "Nice-list players get no Base Insurance alerts either, not just "
					+ "friends in trusted circles.", () -> c.shareWithBaseInsurance, v -> {
						c.shareWithBaseInsurance = v;
						Friends.resync(mod.ledger);
					}));
		}
		options.add(new Option("Record disconnects", "When you get disconnected: the reason, what did it (the server, a mod, an error) and who was near. "
				+ "Told to you when you're back.", () -> c.recordDisconnects, v -> c.recordDisconnects = v));
		options.add(new Option("Open share links from chat", "When someone sends you a Naughty or Nice share link in chat, offer a button to look at it.",
				() -> c.openSharedLinks, v -> c.openSharedLinks = v));
		return options;
	}

	/** A number setting with - and + buttons, stepping through {@code values}. */
	private record Stepper(String label, int[] values, java.util.function.IntSupplier get, java.util.function.IntConsumer set,
			java.util.function.IntFunction<String> show) {}

	private List<Stepper> steppers() {
		NNConfig c = NNConfig.INSTANCE;
		return List.of(
				new Stepper("A conversation ends after", new int[] {30, 60, 120, 180, 300, 600, 900, 1800},
						() -> c.chatQuietSeconds, v -> c.chatQuietSeconds = v, v -> (v < 60 ? v + " s" : v / 60 + " min") + " of quiet"),
				new Stepper("Context kept from before", new int[] {0, 15, 30, 60, 120, 300},
						() -> c.chatContextSeconds, v -> c.chatContextSeconds = v, v -> v == 0 ? "none" : v < 60 ? v + " s" : v / 60 + " min"),
				new Stepper("Chat tab keeps", new int[] {1, 3, 7, 14, 30},
						() -> c.chatHistoryDays, v -> c.chatHistoryDays = v, v -> v + (v == 1 ? " day" : " days") + " of chat"),
				new Stepper("Keep chat logs for", new int[] {1, 3, 7, 14, 30, 60, 90, 180, 365},
						() -> c.chatKeepDays, v -> c.chatKeepDays = v, v -> v + (v == 1 ? " day" : " days")),
				new Stepper("Forget unlisted players after", new int[] {0, 7, 30, 90, 180, 365},
						() -> c.forgetAfterDays, v -> c.forgetAfterDays = v, v -> v == 0 ? "never" : v + " days unseen"),
				new Stepper("\"Near you\" means within", new int[] {8, 16, 24, 32, 48, 64, 96, 128},
						() -> c.nearRadius, v -> c.nearRadius = v, v -> v + " blocks"));
	}

	/** Shown first in Settings: the one to reach for if the screen feels too big or too small. */
	private Stepper sizeStepper() {
		NNConfig c = NNConfig.INSTANCE;
		return new Stepper("Screen size", new int[] {0, 1, 2}, () -> c.screenSize, v -> {
			c.screenSize = v;
			client.execute(this::clearAndInit);
		}, v -> switch (v) {
			case 0 -> "Small";
			case 2 -> "Large";
			default -> "Medium";
		});
	}

	private void stepper(DrawContext context, int mouseX, int mouseY, int x, int y, Stepper stepper) {
		Text label = Text.literal(stepper.label).styled(s -> s.withBold(true));
		context.drawText(textRenderer, label, x, y + 3, TEXT, false);
		int sx = x + textRenderer.getWidth(label) + 6;
		int current = stepper.get.getAsInt();
		int index = 0;
		for (int i = 0; i < stepper.values.length; i++) {
			if (stepper.values[i] <= current) index = i;
		}
		int at = index;
		pill(context, mouseX, mouseY, sx, y, 14, 14, "-", SOFT, BORDER, TEXT, "step-:" + stepper.label, () -> {
			stepper.set.accept(stepper.values[Math.max(0, at - 1)]);
			NNConfig.save();
		});
		String value = stepper.show.apply(current);
		text(context, value, sx + 20, y + 3, TEXT);
		pill(context, mouseX, mouseY, sx + 26 + textRenderer.getWidth(value), y, 14, 14, "+", SOFT, BORDER, TEXT, "step+:" + stepper.label, () -> {
			stepper.set.accept(stepper.values[Math.min(stepper.values.length - 1, at + 1)]);
			NNConfig.save();
		});
	}

	private void renderSettings(DrawContext context, int mouseX, int mouseY) {
		int w = Math.min(380, width - 16);
		int x = (width - w) / 2;
		int y0 = BODY + 8 - (int) scroll;
		int y = y0;
		context.enableScissor(0, BODY, width, height);
		big(context, "Settings", x, y, TEXT, 1.4f);
		y += 18;
		// Chat check: how well this server's chat is understood.
		Diagnostics d = mod.diagnostics;
		int ch = 62;
		roundedBorder(context, x, y, w, ch, CARD, d.count(Diagnostics.Kind.UNKNOWN) > 0 ? LOOK : BORDER);
		context.drawText(textRenderer, Text.literal("Chat check").styled(s -> s.withBold(true)), x + 10, y + 7, TEXT, false);
		String counts = d.total() == 0 ? "No chat seen yet since the game started."
				: d.total() + " lines: " + d.count(Diagnostics.Kind.CHAT) + " chat, " + d.count(Diagnostics.Kind.WHISPER) + " whispers, "
				+ (d.count(Diagnostics.Kind.KILL) + d.count(Diagnostics.Kind.DEATH)) + " deaths, " + d.count(Diagnostics.Kind.JOIN_LEAVE) + " joins/leaves";
		textTrim(context, counts, x + 10, y + 19, w - 20, MUTED);
		int unknown = d.count(Diagnostics.Kind.UNKNOWN);
		textTrim(context, unknown == 0 ? "Everything was understood." : unknown + (unknown == 1 ? " line" : " lines") + " (" + d.unknown().size() + (d.unknown().size() == 1 ? " kind" : " kinds") + ") weren't understood. Look, then send the report in.",
				x + 10, y + 30, w - 20, unknown == 0 ? NICE : LOOK);
		int bx = x + 10;
		String view = "View the chat check";
		int vw = textRenderer.getWidth(view) + 14;
		pill(context, mouseX, mouseY, bx, y + 43, vw, 14, view, YELLOW, YELLOW_EDGE, BUTTON_TEXT, "open-check", this::openCheck);
		bx += vw + 6;
		String copy = "Copy report";
		int cw2 = textRenderer.getWidth(copy) + 14;
		pill(context, mouseX, mouseY, bx, y + 43, cw2, 14, copy, SOFT, BORDER, TEXT, "check-copy-settings", this::copyCheckReport);
		y += ch + 10;
		int disconnectsAt = y;
		y = disconnectsCard(context, mouseX, mouseY, x, y, w) + 10;
		if (scrollToDisconnects) {
			scrollToDisconnects = false;
			scroll = Math.max(0, disconnectsAt - y0 - 4);
		}
		int circlesAt = y;
		y = circlesCard(context, mouseX, mouseY, x, y, w) + 10;
		if (scrollToCircles) {
			scrollToCircles = false;
			scroll = Math.max(0, circlesAt - y0 - 4);
		}
		List<Option> options = options();
		int boxH = 14 + 20;
		for (Option o : options) boxH += 12 + textRenderer.wrapLines(Text.literal(o.help), w - 34).size() * 10 + 6;
		boxH += steppers().size() * 18 + 8;
		boxH += 46;
		roundedBorder(context, x, y, w, boxH, CARD, BORDER);
		int ty = y + 10;
		stepper(context, mouseX, mouseY, x + 26, ty - 2, sizeStepper());
		ty += 20;
		for (Option o : options) {
			boolean on = o.get.getAsBoolean();
			int rowY = ty;
			int helpLines = textRenderer.wrapLines(Text.literal(o.help), w - 34).size();
			int rowH = 12 + helpLines * 10;
			boolean hover = over(mouseX, mouseY, x + 4, rowY - 2, w - 8, rowH + 2);
			if (hover) context.fill(x + 4, rowY - 3, x + w - 4, rowY + rowH + 1, HOVER_BG);
			roundedBorder(context, x + 10, rowY, 10, 10, on ? LINK : FIELD, on ? LINK : INPUT_BORDER);
			if (on) text(context, "✔", x + 11, rowY + 1, CARD);
			context.drawText(textRenderer, Text.literal(o.label).styled(s -> s.withBold(true)), x + 26, rowY + 1, TEXT, false);
			int hy = rowY + 12;
			for (var line : textRenderer.wrapLines(Text.literal(o.help), w - 34)) {
				context.drawText(textRenderer, line, x + 26, hy, MUTED, false);
				hy += 10;
			}
			hit(x + 4, rowY - 2, w - 8, rowH + 2, "option:" + o.label, () -> {
				o.set.accept(!on);
				NNConfig.save();
			});
			ty += rowH + 6;
		}
		for (Stepper stepper : steppers()) {
			stepper(context, mouseX, mouseY, x + 26, ty, stepper);
			ty += 18;
		}
		// Words to be alerted about.
		context.drawText(textRenderer, Text.literal("Alert me when someone says").styled(s -> s.withBold(true)), x + 26, ty + 2, TEXT, false);
		textTrim(context, "  commas between; {me} is your name", x + 26 + textRenderer.getWidth(Text.literal("Alert me when someone says").styled(s -> s.withBold(true))),
				ty + 2, w - 40 - textRenderer.getWidth("Alert me when someone says"), MUTED);
		ty += 13;
		roundedBorder(context, x + 26, ty, w - 36, 16, FIELD, alertField.isFocused() ? 0xFFE77600 : INPUT_BORDER);
		alertField.setX(x + 30);
		alertField.setY(ty + 4);
		alertField.setWidth(w - 44);
		alertFieldTop = ty;
		int fieldY = ty;
		hit(x + 26, fieldY, w - 36, 16, "alert-words", () -> setFocused(alertField));
		y += boxH + 10;

		link(context, mouseX, mouseY, x, y, "Open chat log folder", "open-chat-folder", () -> {
			try {
				Files.createDirectories(mod.chatLogger.folder());
			} catch (java.io.IOException ignored) {
				// Opening will just fail quietly.
			}
			Util.getOperatingSystem().open(mod.chatLogger.folder());
		});
		y += 14;
		for (var line : textRenderer.wrapLines(Text.literal("Your lists are saved per server in config/naughtyornice/lists, chat logs in config/naughtyornice/chatlogs. "
				+ "Shared lists are saved in config/naughtyornice/shared. "
				+ "Commands: /nn, /nn add, /nn friends, /nn chat, /nn groups, /nn share, /nn import, /nn check, /nn disconnects, /nn friend <name> [circle], "
				+ "/nn nice <name>, /nn look <name>, /nn naughty <name>, /nn unsort <name>, /nn group <name> <group>, /nn chat <name>."), w)) {
			context.drawText(textRenderer, line, x, y, MUTED, false);
			y += 10;
		}
		context.disableScissor();
		contentHeight = y - y0 + 10;
		scrollbar(context, BODY);
	}

	// ---- Disconnects ----

	/** The latest disconnects: the reason, what did it, and who was near. Returns the y after it. */
	private int disconnectsCard(DrawContext context, int mouseX, int mouseY, int x, int y, int w) {
		List<Disconnects.Entry> all = Disconnects.all();
		List<Disconnects.Entry> shown = new ArrayList<>();
		for (int i = all.size() - 1; i >= 0 && shown.size() < 3; i--) shown.add(all.get(i));
		int iw = w - 20;
		// Measure first, so the card fits what's in it.
		int h = 24;
		if (shown.isEmpty()) h += textRenderer.wrapLines(Text.literal(DISCONNECTS_NONE), iw).size() * 10 + 4;
		for (Disconnects.Entry e : shown) h += disconnectHeight(e, iw) + 6;
		boolean warn = !shown.isEmpty() && (shown.get(0).kind == Disconnects.Kind.MOD || shown.get(0).kind == Disconnects.Kind.ERROR)
				&& System.currentTimeMillis() - shown.get(0).time < 86_400_000L;
		roundedBorder(context, x, y, w, h, CARD, warn ? LOOK : BORDER);
		context.drawText(textRenderer, Text.literal("Disconnects").styled(s -> s.withBold(true)), x + 10, y + 7, TEXT, false);
		if (!shown.isEmpty()) {
			String clear = "Clear";
			int clw = textRenderer.getWidth(clear) + 12;
			pill(context, mouseX, mouseY, x + w - 10 - clw, y + 4, clw, 13, clear, SOFT, BORDER, TEXT, "disconnects-clear", () -> {
				Disconnects.clear();
				flash("Cleared the disconnect history", MUTED, null);
			});
			String copy = "Copy details";
			int cpw = textRenderer.getWidth(copy) + 12;
			pill(context, mouseX, mouseY, x + w - 16 - clw - cpw, y + 4, cpw, 13, copy, SOFT, BORDER, TEXT, "disconnects-copy", () -> {
				client.keyboard.setClipboard(Disconnects.report());
				flash("Copied the disconnect details", NICE, null);
			});
		}
		int ty = y + 22;
		if (shown.isEmpty()) {
			for (var line : textRenderer.wrapLines(Text.literal(DISCONNECTS_NONE), iw)) {
				context.drawText(textRenderer, line, x + 10, ty, MUTED, false);
				ty += 10;
			}
		}
		for (Disconnects.Entry e : shown) {
			ty = disconnectRow(context, e, x + 10, ty, iw) + 6;
		}
		return y + h;
	}

	private static final String DISCONNECTS_NONE = "None recorded yet. When you get disconnected, the reason, what did it (the server, another mod, "
			+ "an error or the connection) and who was near show here.";

	private int disconnectHeight(Disconnects.Entry e, int w) {
		int h = 11;
		h += Math.min(2, textRenderer.wrapLines(Text.literal("\"" + e.reason + "\""), w).size()) * 10;
		h += Math.min(4, textRenderer.wrapLines(Text.literal(Disconnects.cause(e)), w).size()) * 10;
		if (!e.nearby.isEmpty()) h += Math.min(2, textRenderer.wrapLines(Text.literal("Nearby: " + Disconnects.nearbyText(e, 8)), w).size()) * 10;
		return h;
	}

	private int disconnectRow(DrawContext context, Disconnects.Entry e, int x, int y, int w) {
		String kind = switch (e.kind) {
			case SERVER -> "Server";
			case YOU -> "You left";
			case MOD -> "A mod";
			case ERROR -> "Error";
			case CONNECTION -> "Connection";
			case UNKNOWN -> "Unknown";
		};
		int kindColor = e.kind == Disconnects.Kind.MOD || e.kind == Disconnects.Kind.ERROR ? LOOK : e.kind == Disconnects.Kind.SERVER ? NAUGHTY : MUTED;
		int kw = textRenderer.getWidth(kind) + 6;
		roundedBorder(context, x, y - 1, kw, 10, tint(kindColor, CARD, 0.15f), kindColor);
		text(context, kind, x + 3, y, kindColor);
		textTrim(context, ago(e.time) + " · " + Sharing.placeName(e.server), x + kw + 5, y, w - kw - 5, MUTED);
		y += 11;
		y = wrapped(context, "\"" + e.reason + "\"", x, y, w, TEXT, 2);
		y = wrapped(context, Disconnects.cause(e), x, y, w, e.kind == Disconnects.Kind.MOD || e.kind == Disconnects.Kind.ERROR ? LOOK : MUTED, 4);
		if (!e.nearby.isEmpty()) y = wrapped(context, "Nearby: " + Disconnects.nearbyText(e, 8), x, y, w, MUTED, 2);
		return y;
	}

	/** Wrapped text, at most {@code maxLines} lines; returns the y after it. */
	private int wrapped(DrawContext context, String s, int x, int y, int w, int color, int maxLines) {
		int n = 0;
		for (var line : textRenderer.wrapLines(Text.literal(s), w)) {
			if (n++ >= maxLines) break;
			context.drawText(textRenderer, line, x, y, color, false);
			y += 10;
		}
		return y;
	}

	// ---- Friend circles ----

	/**
	 * Settings card for friend circles: colour, name, how many, trusted at bases, ping on join, move up/down,
	 * remove; and a box to make a new one or rename one. Returns the y after it.
	 */
	private int circlesCard(DrawContext context, int mouseX, int mouseY, int x, int y, int w) {
		List<Friends.Circle> circles = Friends.circles();
		int rowH = 28;
		String about = "Kinds of friends. New friends go in the top one. Click a colour to change it, a name to rename it.";
		int aboutLines = Math.min(3, textRenderer.wrapLines(Text.literal(about), w - 20).size());
		int h = 26 + aboutLines * 10 + circles.size() * rowH + 24;
		roundedBorder(context, x, y, w, h, CARD, BORDER);
		context.drawText(textRenderer, Text.literal("Friend circles").styled(s -> s.withBold(true)), x + 10, y + 7, TEXT, false);
		wrapped(context, about, x + 10, y + 19, w - 20, MUTED, 3);
		int ry = y + 22 + aboutLines * 10;
		for (int i = 0; i < circles.size(); i++) {
			Friends.Circle c = circles.get(i);
			int color = GROUP_COLORS[c.color % GROUP_COLORS.length];
			boolean rowHover = over(mouseX, mouseY, x + 4, ry - 2, w - 8, rowH);
			if (rowHover) context.fill(x + 4, ry - 2, x + w - 4, ry + rowH - 4, HOVER_BG);
			// Colour: click for the next one.
			boolean swatchHover = over(mouseX, mouseY, x + 10, ry, 10, 10);
			roundedBorder(context, x + 10, ry, 10, 10, color, swatchHover ? TEXT : tint(color, CARD, 0.5f));
			hit(x + 10, ry, 10, 10, "circle-color:" + c.name, () -> {
				c.color = (c.color + 1) % Ledger.GROUP_COLORS;
				NNConfig.save();
			});
			int members = Friends.members(mod.ledger, c).size();
			boolean nameHover = over(mouseX, mouseY, x + 26, ry, textRenderer.getWidth(c.name) + 6, 10);
			Text name = Text.literal(c.name).styled(s -> s.withBold(true).withUnderline(nameHover));
			context.drawText(textRenderer, name, x + 26, ry + 1, color, false);
			int nameW = textRenderer.getWidth(name);
			hit(x + 26, ry, nameW, 10, "circle-rename:" + c.name, () -> startCircleEdit(c));
			text(context, (members == 1 ? " 1 friend" : " " + members + " friends") + (i == 0 ? " · new friends go here" : ""), x + 26 + nameW, ry + 1, MUTED);
			// Up, down, remove on the right.
			int bx = x + w - 10 - 12;
			if (circles.size() > 1) {
				iconButton(context, mouseX, mouseY, bx, ry - 1, "✖", NAUGHTY, "circle-remove:" + c.name, () -> removeCircle(c));
			}
			bx -= 14;
			if (i < circles.size() - 1) iconButton(context, mouseX, mouseY, bx, ry - 1, "↓", LINK, "circle-down:" + c.name, () -> Friends.move(c, 1));
			bx -= 14;
			if (i > 0) iconButton(context, mouseX, mouseY, bx, ry - 1, "↑", LINK, "circle-up:" + c.name, () -> Friends.move(c, -1));
			int cy = ry + 12;
			int cx = checkboxSmall(context, mouseX, mouseY, x + 26, cy, "Trusted at my bases", c.trusted, "circle-trusted:" + c.name, () -> {
				c.trusted = !c.trusted;
				NNConfig.save();
				Friends.resync(mod.ledger);
			});
			checkboxSmall(context, mouseX, mouseY, cx + 10, cy, "Ping when they join or come near", c.joinAlert, "circle-alert:" + c.name, () -> {
				c.joinAlert = !c.joinAlert;
				NNConfig.save();
			});
			ry += rowH;
		}
		// The box: rename the circle being edited, or make a new one.
		int fy = ry + 2;
		String button = circleEditing != null ? "Rename" : "+ Add circle";
		int bw = textRenderer.getWidth(button) + 14;
		int cancelW = circleEditing != null ? textRenderer.getWidth("Cancel") + 12 : 0;
		int fw = w - 20 - bw - 6 - (cancelW > 0 ? cancelW + 4 : 0);
		roundedBorder(context, x + 10, fy, fw, 16, FIELD, circleField.isFocused() ? 0xFFE77600 : INPUT_BORDER);
		circleField.setX(x + 14);
		circleField.setY(fy + 4);
		circleField.setWidth(fw - 8);
		circleFieldTop = fy;
		hit(x + 10, fy, fw, 16, "circle-field", () -> setFocused(circleField));
		pill(context, mouseX, mouseY, x + 10 + fw + 6, fy + 1, bw, 14, button, YELLOW, YELLOW_EDGE, BUTTON_TEXT, "circle-save", this::commitCircle);
		if (circleEditing != null) {
			pill(context, mouseX, mouseY, x + 10 + fw + 6 + bw + 4, fy + 1, cancelW, 14, "Cancel", SOFT, BORDER, TEXT, "circle-cancel", this::cancelCircleEdit);
		}
		return y + h;
	}

	/** A small square button with one symbol. */
	private void iconButton(DrawContext context, int mouseX, int mouseY, int x, int y, String symbol, int color, String id, Runnable run) {
		boolean hover = over(mouseX, mouseY, x, y, 12, 12);
		roundedBorder(context, x, y, 12, 12, hover ? tint(color, CARD, 0.2f) : CARD, hover ? color : BORDER);
		text(context, symbol, x + (12 - textRenderer.getWidth(symbol)) / 2 + 1, y + 2, color);
		hit(x, y, 12, 12, id, run);
	}

	/** A checkbox with its label; returns the x after it. */
	private int checkboxSmall(DrawContext context, int mouseX, int mouseY, int x, int y, String label, boolean checked, String id, Runnable run) {
		int w = 12 + textRenderer.getWidth(label);
		boolean hover = over(mouseX, mouseY, x, y - 1, w, 11);
		roundedBorder(context, x, y, 9, 9, checked ? LINK : FIELD, checked ? LINK : INPUT_BORDER);
		if (checked) text(context, "✔", x + 1, y + 1, CARD);
		context.drawText(textRenderer, Text.literal(label).styled(s -> s.withUnderline(hover)), x + 12, y + 1, TEXT, false);
		hit(x, y - 1, w, 11, id, run);
		return x + w;
	}

	private void startCircleEdit(Friends.Circle c) {
		circleEditing = c;
		circleField.setText(c.name);
		circleField.setCursorToEnd(false);
		setFocused(circleField);
	}

	private void cancelCircleEdit() {
		circleEditing = null;
		circleField.setText("");
		setFocused(null);
	}

	private void commitCircle() {
		String name = circleField.getText().strip();
		if (name.isEmpty()) {
			setFocused(circleField);
			return;
		}
		if (circleEditing != null) {
			String was = circleEditing.name;
			if (!Friends.rename(circleEditing, name)) {
				flash("There's already a circle called " + name, NAUGHTY, null);
				return;
			}
			flash("Renamed " + was + " to " + circleEditing.name, FRIEND, null);
		} else {
			Friends.Circle made = Friends.add(name);
			if (made == null) {
				flash(Friends.circles().size() >= Friends.MAX ? "That's the most circles there can be (" + Friends.MAX + ")" : "There's already a circle called " + name, NAUGHTY, null);
				return;
			}
			flash("Made the circle " + made.name + ". Press ★ on a friend's card to put them in it.", FRIEND, null);
		}
		cancelCircleEdit();
	}

	private void removeCircle(Friends.Circle c) {
		List<Profile> members = Friends.members(mod.ledger, c);
		int at = Friends.circles().indexOf(c);
		if (!Friends.remove(c, mod.ledger)) return;
		mod.ledger.save(false);
		cachedResults = null;
		flash("Removed " + c.name + (members.isEmpty() ? "" : "; its " + members.size() + " moved to " + Friends.circles().get(0).name), MUTED, () -> {
			Friends.circles().add(Math.min(at, Friends.circles().size()), c);
			for (Profile p : members) p.circle = c.id;
			NNConfig.save();
			Friends.resync(mod.ledger);
			mod.ledger.markDirty();
			mod.ledger.save(false);
			cachedResults = null;
			flash("Undone", MUTED, null);
		});
	}

	// ---- The Chat check panel ----

	private void openCheck() {
		checking = true;
		checkScroll = 0;
		checkStatus = null;
		setFocused(null);
	}

	private void copyCheckReport() {
		client.keyboard.setClipboard(mod.diagnostics.report(mod.ledger.key()));
		flash("Copied the chat check report: paste it to whoever looks after the mod", NICE, null);
		checkStatus = "Copied. Paste it to whoever looks after the mod.";
	}

	private void renderCheckPanel(DrawContext context, int mouseX, int mouseY) {
		panelHits.clear();
		context.fill(0, 0, width, height, 0x99000000);
		Diagnostics d = mod.diagnostics;
		int w = Math.min(520, width - 16);
		int h = Math.min(height - 16, 300);
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		roundedBorder(context, x, y, w, h, CARD, BORDER);
		context.fill(x + 1, y + 2, x + w - 1, y + 22, SOFT);
		context.fill(x + 1, y + 22, x + w - 1, y + 23, BORDER);
		context.drawText(textRenderer, Text.literal("Chat check · " + Sharing.placeName(mod.ledger.key())).styled(s -> s.withBold(true)), x + 10, y + 8, TEXT, false);
		int closeX = x + w - 16;
		boolean closeHover = over(mouseX, mouseY, closeX - 3, y + 3, 14, 16);
		text(context, "✖", closeX, y + 8, closeHover ? TEXT : MUTED);
		panelHits.add(new Hit(closeX - 3, y + 3, closeX + 11, y + 19, "check-close", () -> checking = false));

		int ix = x + 10;
		int iw = w - 20;
		int ty = y + 29;
		for (var line : textRenderer.wrapLines(Text.literal("How well this server's chat is read. Lines it couldn't read can mean missed chat, kills or joins: "
				+ "copy the report and send it in so the mod can learn this server's wording."), iw)) {
			context.drawText(textRenderer, line, ix, ty, MUTED, false);
			ty += 10;
		}
		ty += 3;
		// The counts as chips.
		int cx = ix;
		for (Diagnostics.Kind kind : Diagnostics.Kind.values()) {
			String label = kind.label + " " + d.count(kind);
			int lw = textRenderer.getWidth(label) + 10;
			if (cx + lw > ix + iw) {
				cx = ix;
				ty += 14;
			}
			int c = kind == Diagnostics.Kind.UNKNOWN ? (d.count(kind) > 0 ? LOOK : NICE) : kind == Diagnostics.Kind.IGNORED ? MUTED : LINK;
			roundedBorder(context, cx, ty, lw, 12, tint(c, CARD, 0.12f), tint(c, CARD, 0.5f));
			text(context, label, cx + 5, ty + 2, c);
			cx += lw + 4;
		}
		ty += 18;
		context.drawText(textRenderer, Text.literal("Not understood, most common first").styled(s -> s.withBold(true)), ix, ty, TEXT, false);
		ty += 12;
		int listTop = ty;
		int listBottom = y + h - 40;
		rounded(context, ix, listTop, iw, listBottom - listTop, SOFT);
		context.enableScissor(ix, listTop + 2, ix + iw, listBottom - 2);
		int ly = listTop + 4 - (int) checkScroll;
		List<Diagnostics.Shape> shapes = d.unknown();
		if (shapes.isEmpty()) {
			text(context, d.total() == 0 ? "Nothing yet: play a while on the server first." : "Every line was understood.", ix + 6, ly + 2, MUTED);
			ly += 14;
		}
		for (Diagnostics.Shape shape : shapes) {
			String count = shape.count + "×";
			text(context, count, ix + 6, ly, LOOK);
			int tx = ix + 8 + textRenderer.getWidth("9999×");
			for (var line : textRenderer.wrapLines(Text.literal(shape.shape), iw - (tx - ix) - 6)) {
				context.drawText(textRenderer, line, tx, ly, TEXT, false);
				ly += 10;
			}
			for (String example : shape.examples) {
				if (example.equals(shape.shape)) continue;
				for (var line : textRenderer.wrapLines(Text.literal("e.g. " + example), iw - (tx - ix) - 6)) {
					context.drawText(textRenderer, line, tx + 6, ly, MUTED, false);
					ly += 10;
				}
			}
			ly += 4;
		}
		context.disableScissor();
		checkMaxScroll = Math.max(0, ly + (int) checkScroll - listBottom + 4);

		int by = y + h - 32;
		int bw = (iw - 18) / 4;
		shareButton(context, mouseX, mouseY, ix, by, bw, "Copy report", YELLOW, YELLOW_EDGE, BUTTON_TEXT, "check-copy", this::copyCheckReport);
		shareButton(context, mouseX, mouseY, ix + bw + 6, by, bw, "Save as file", SOFT, BORDER, TEXT, "check-save", () -> {
			try {
				Path file = d.save(mod.ledger.key());
				checkStatus = "Saved " + file.getFileName();
				Util.getOperatingSystem().open(file.getParent());
			} catch (java.io.IOException e) {
				checkStatus = "Couldn't save: " + e.getMessage();
			}
		});
		shareButton(context, mouseX, mouseY, ix + 2 * (bw + 6), by, bw, "Start over", SOFT, BORDER, TEXT, "check-clear", () -> {
			d.clear();
			checkStatus = "Cleared. New chat is counted from now.";
		});
		shareButton(context, mouseX, mouseY, ix + 3 * (bw + 6), by, iw - 3 * (bw + 6), "Close", SOFT, BORDER, TEXT, "check-done", () -> checking = false);
		if (checkStatus != null) textTrim(context, checkStatus, ix, y + h - 12, iw, NICE);
	}

	private boolean checkClicked(Click click) {
		for (Hit hit : List.copyOf(panelHits).reversed()) {
			if (click.x() >= hit.x0 && click.x() < hit.x1 && click.y() >= hit.y0 && click.y() < hit.y1) {
				hit.run.run();
				return true;
			}
		}
		return true;
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click realClick, boolean doubled) {
		Click click = scaled(realClick);
		if (circleMenu != null) {
			// A click on a row picks it; anywhere else just closes the picker.
			for (Hit hit : List.copyOf(menuHits)) {
				if (click.x() >= hit.x0 && click.x() < hit.x1 && click.y() >= hit.y0 && click.y() < hit.y1) {
					hit.run.run();
					return true;
				}
			}
			circleMenu = null;
			return true;
		}
		if (adding) return panelClicked(click, doubled);
		if (sharing) return shareClicked(click);
		if (grouping) return groupClicked(click, doubled);
		if (checking) return checkClicked(click);
		if (super.mouseClicked(click, doubled)) return true;
		// Last drawn is on top.
		for (Hit hit : List.copyOf(hits).reversed()) {
			if (click.x() >= hit.x0 && click.x() < hit.x1 && click.y() >= hit.y0 && click.y() < hit.y1) {
				if (!"search".equals(hit.label)) setFocused(null);
				if (!"forget".equals(hit.label)) confirmForget = false;
				if (!"group-delete".equals(hit.label)) confirmDeleteGroup = false;
				hit.run.run();
				return true;
			}
		}
		setFocused(null);
		return false;
	}

	@Override
	public boolean mouseDragged(Click click, double dx, double dy) {
		return super.mouseDragged(scaled(click), scaled(dx), scaled(dy));
	}

	@Override
	public boolean mouseReleased(Click click) {
		return super.mouseReleased(scaled(click));
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		super.mouseMoved(scaled(mouseX), scaled(mouseY));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (adding || grouping || circleMenu != null) return true;
		if (checking) {
			checkScroll = Math.max(0, Math.min(checkMaxScroll, checkScroll - verticalAmount * 22));
			return true;
		}
		if (selected == null && tab == Tab.CHAT) {
			chatScroll = Math.max(0, Math.min(chatMaxScroll, chatScroll + verticalAmount * 24));
			return true;
		}
		if (sharing) {
			if (shareView == ShareView.PREVIEW) previewScroll = Math.max(0, Math.min(previewMaxScroll, previewScroll - verticalAmount * 22));
			return true;
		}
		scroll = Math.max(0, Math.min(maxScroll(), scroll - verticalAmount * 24));
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (circleMenu != null) {
			if (input.key() == GLFW.GLFW_KEY_ESCAPE) circleMenu = null;
			return true;
		}
		if (adding) return panelKey(input);
		if (sharing) return shareKey(input);
		if (grouping) return groupKey(input);
		if (checking) {
			if (input.key() == GLFW.GLFW_KEY_ESCAPE) checking = false;
			return true;
		}
		boolean enter = input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER;
		if (chatField.isFocused()) {
			if (enter) {
				sendChat();
				return true;
			}
			if (input.key() == GLFW.GLFW_KEY_UP || input.key() == GLFW.GLFW_KEY_DOWN) {
				chatHistory(input.key() == GLFW.GLFW_KEY_UP ? -1 : 1);
				return true;
			}
			if (input.key() == GLFW.GLFW_KEY_PAGE_UP || input.key() == GLFW.GLFW_KEY_PAGE_DOWN) {
				chatScroll = Math.max(0, Math.min(chatMaxScroll, chatScroll + (input.key() == GLFW.GLFW_KEY_PAGE_UP ? 1 : -1) * (height - BODY - 60)));
				return true;
			}
		}
		if (enter && circleField.isFocused()) {
			commitCircle();
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_ESCAPE && circleField.isFocused()) {
			cancelCircleEdit();
			return true;
		}
		if (enter && detailField.isFocused() && selected != null) {
			commitDetail(selected);
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_ESCAPE && detailField.isFocused() && editing != null) {
			cancelEdit();
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_ESCAPE && getFocused() instanceof TextFieldWidget field) {
			field.setFocused(false);
			setFocused(null);
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_ESCAPE && (selected != null || tab == Tab.SETTINGS || (tab == Tab.GROUPS && openGroup != null))) {
			if (tab == Tab.SETTINGS) showTab(lastTab != null ? lastTab : Tab.ALL);
			else if (selected != null) back();
			else {
				openGroup = null;
				scroll = 0;
			}
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_ENTER && search.isFocused()) {
			// Enter on a search with one result opens it.
			List<Profile> results = results();
			if (results.size() == 1) open(results.get(0));
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean charTyped(CharInput input) {
		if (sharing || checking) return true;
		if (grouping) {
			if (getFocused() != groupField && getFocused() != membersField) setFocused(groupPanelGroup != null ? membersField : groupField);
			return getFocused().charTyped(input);
		}
		// On the Chat tab, typing goes to the chat box.
		if (getFocused() == null && selected == null && tab == Tab.CHAT && input.isValidChar() && client.getNetworkHandler() != null) {
			setFocused(chatField);
			return chatField.charTyped(input);
		}
		if (adding) {
			if (getFocused() != addField && getFocused() != addNote && getFocused() != addGroup) setFocused(addField);
			return getFocused().charTyped(input);
		}
		// Start typing anywhere to search.
		if (getFocused() == null && input.isValidChar() && !Character.isWhitespace(input.codepoint())) {
			setFocused(search);
			return search.charTyped(input);
		}
		return super.charTyped(input);
	}

	@Override
	public void removed() {
		mod.ledger.save(false);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	// ---- The Add players panel ----

	private void openAdd(Profile.Verdict target) {
		adding = true;
		addTarget = target == Profile.Verdict.NONE ? Profile.Verdict.NICE : target;
		addStatus = null;
		addField.setText("");
		addNote.setText("");
		addGroup.setText(tab == Tab.GROUPS && openGroup != null ? openGroup : "");
		addCircle = tab == Tab.FRIENDS && friendCircle != null ? friendCircle : Friends.circles().get(0).id;
		setFocused(addField);
	}

	private void closeAdd() {
		adding = false;
		addStatus = null;
		setFocused(null);
	}

	/** The name being typed: whatever follows the last space or comma. */
	private String lastToken() {
		return lastToken(addField);
	}

	private String lastToken(TextFieldWidget field) {
		String text = field.getText();
		int cut = Math.max(Math.max(text.lastIndexOf(' '), text.lastIndexOf(',')), text.lastIndexOf(';'));
		return text.substring(cut + 1).trim();
	}

	/** Players to suggest for the name being typed: online first (nearest first), then everyone known. */
	private List<String> suggestions() {
		return suggestions(addField);
	}

	private List<String> suggestions(TextFieldWidget field) {
		String token = lastToken(field).toLowerCase(Locale.ROOT);
		List<String> typed = List.of(field.getText().toLowerCase(Locale.ROOT).split("[\\s,;]+"));
		List<String> candidates = new ArrayList<>();
		nearby().stream().sorted(Comparator.comparingDouble(pl -> client.player == null ? 0 : pl.distanceTo(client.player)))
				.forEach(pl -> candidates.add(pl.getGameProfile().name()));
		for (PlayerListEntry entry : online()) {
			if (!candidates.contains(entry.getProfile().name())) candidates.add(entry.getProfile().name());
		}
		for (Profile p : mod.ledger.all()) {
			if (!candidates.contains(p.name) && !p.id.equals(me())) candidates.add(p.name);
		}
		List<String> starts = new ArrayList<>();
		List<String> contains = new ArrayList<>();
		for (String name : candidates) {
			String lower = name.toLowerCase(Locale.ROOT);
			if (typed.contains(lower) && !lower.equals(token)) continue;
			if (token.isEmpty() || lower.startsWith(token)) starts.add(name);
			else if (lower.contains(token)) contains.add(name);
		}
		starts.addAll(contains);
		// For the lists, nothing typed yet: suggest people who aren't on one.
		if (token.isEmpty() && field == addField) starts.removeIf(name -> {
			Profile p = mod.ledger.byName(name);
			return p != null && p.verdict != Profile.Verdict.NONE;
		});
		return starts.size() > 10 ? new ArrayList<>(starts.subList(0, 10)) : starts;
	}

	private void pickSuggestion(String name) {
		pickSuggestion(addField, name);
	}

	private void pickSuggestion(TextFieldWidget field, String name) {
		String text = field.getText();
		int cut = Math.max(Math.max(text.lastIndexOf(' '), text.lastIndexOf(',')), text.lastIndexOf(';'));
		String head = text.substring(0, cut + 1);
		if (!head.isEmpty() && !head.endsWith(" ")) head += " ";
		field.setText(head + name + ", ");
		field.setCursorToEnd(false);
		setFocused(field);
	}

	private static String join(List<String> names) {
		if (names.size() <= 1) return String.join("", names);
		return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
	}

	/** Adds every name typed to a list. */
	private void addNames(Profile.Verdict list) {
		addTarget = list;
		String note = addNote.getText().trim();
		String group = Ledger.cleanGroupName(addGroup.getText());
		List<String> added = new ArrayList<>();
		List<String> already = new ArrayList<>();
		List<String> bad = new ArrayList<>();
		List<Runnable> undo = new ArrayList<>();
		String myName = client.player == null ? "" : client.player.getGameProfile().name();
		for (String raw : addField.getText().split("[\\s,;]+")) {
			String name = raw.trim();
			if (name.isEmpty()) continue;
			if (!name.matches(NAME_PATTERN) || name.equalsIgnoreCase(myName)) {
				bad.add(name);
				continue;
			}
			boolean known = mod.ledger.byName(name) != null
					|| (client.getNetworkHandler() != null && client.getNetworkHandler().getPlayerListEntry(name) != null
					&& mod.ledger.get(client.getNetworkHandler().getPlayerListEntry(name).getProfile().id()) != null);
			Profile p = mod.ledger.addByName(client, name);
			if (!note.isEmpty()) {
				Profile.Detail d = p.addDetail(note, "", false);
				if (d != null) {
					undo.add(() -> p.details.remove(d));
					mod.ledger.markDirty();
				}
			}
			if (!group.isEmpty() && mod.ledger.addToGroup(p, group)) undo.add(() -> mod.ledger.removeFromGroup(p, group));
			Friends.Circle circle = Friends.byId(addCircle) != null ? Friends.byId(addCircle) : Friends.circles().get(0);
			if (p.verdict == list && (list != Profile.Verdict.FRIEND || Friends.of(p) == circle)) {
				already.add(p.name);
				continue;
			}
			Profile.Verdict before = p.verdict;
			String beforeCircle = p.circle;
			boolean wasAuto = p.autoListed;
			if (list == Profile.Verdict.FRIEND) {
				mod.ledger.setCircle(p, circle);
				Profile who = p;
				undo.add(() -> who.circle = beforeCircle);
			} else {
				mod.ledger.setVerdict(p, list, false, "added by hand");
			}
			added.add(p.name);
			undo.add(known ? () -> mod.ledger.setVerdict(p, before, wasAuto, "undo") : () -> mod.ledger.forget(p));
		}
		mod.ledger.save(false);
		cachedResults = null;

		String listName = list == Profile.Verdict.FRIEND ? "Friends (" + Friends.circleName(mod.ledger.byName(added.isEmpty() ? "" : added.get(0))) + ")" : verdictWord(list);
		StringBuilder status = new StringBuilder();
		if (!added.isEmpty()) status.append("Added ").append(join(added)).append(" to your ").append(listName).append(" list. ");
		if (!already.isEmpty()) status.append(join(already)).append(already.size() == 1 ? " was" : " were").append(" already on it. ");
		if (!bad.isEmpty()) status.append("Not a player name: ").append(join(bad)).append(". ");
		if (status.isEmpty()) status.append("Type a name first.");
		addStatus = status.toString().trim();
		if (!added.isEmpty() && !group.isEmpty()) status.insert(0, "In the group " + mod.ledger.groupName(group) + ". ");
		addStatusColor = !added.isEmpty() ? verdictColor(list) : bad.isEmpty() ? MUTED : NAUGHTY;
		if (!added.isEmpty()) {
			addNote.setText("");
			flash("Added " + join(added) + " to your " + listName + " list" + (group.isEmpty() ? "" : " and the group " + mod.ledger.groupName(group)),
					verdictColor(list), () -> {
				for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).run();
				mod.ledger.save(false);
				cachedResults = null;
				flash("Undone", MUTED, null);
			});
		}
		// Leave only what needs fixing in the box.
		addField.setText(String.join(", ", bad));
		addField.setCursorToEnd(false);
		setFocused(addField);
	}

	private void renderAddPanel(DrawContext context, int mouseX, int mouseY) {
		panelHits.clear();
		context.fill(0, 0, width, height, 0x99000000);
		int w = Math.min(320, width - 16);
		int h = 248;
		int x = (width - w) / 2;
		int y = Math.max(4, (height - h) / 2);
		roundedBorder(context, x, y, w, h, CARD, BORDER);
		context.fill(x + 1, y + 2, x + w - 1, y + 22, SOFT);
		context.fill(x + 1, y + 22, x + w - 1, y + 23, BORDER);
		context.drawText(textRenderer, Text.literal("Add players to your Friends or lists").styled(s -> s.withBold(true)), x + 10, y + 8, TEXT, false);
		int closeX = x + w - 16;
		boolean closeHover = over(mouseX, mouseY, closeX - 3, y + 3, 14, 16);
		text(context, "✖", closeX, y + 8, closeHover ? TEXT : MUTED);
		panelHits.add(new Hit(closeX - 3, y + 3, closeX + 11, y + 19, "add-close", this::closeAdd));

		int ix = x + 10;
		int iw = w - 20;
		int ty = y + 30;
		textTrim(context, "Player names (several? separate with spaces or commas)", ix, ty, iw, MUTED);
		ty += 11;
		roundedBorder(context, ix, ty, iw, 16, FIELD, addField.isFocused() ? 0xFFE77600 : INPUT_BORDER);
		addField.setX(ix + 4);
		addField.setY(ty + 4);
		addField.setWidth(iw - 8);
		addFieldTop = ty;
		ty += 20;

		// Suggestions, up to two rows of chips.
		List<String> suggestions = suggestions();
		int sx = ix;
		int row = 0;
		if (suggestions.isEmpty()) {
			textTrim(context, lastToken().isEmpty() ? "Nobody else online to suggest. Type any name." : "No match online, but you can still add this name.", ix, ty + 2, iw, MUTED);
		}
		for (String name : suggestions) {
			Profile known = mod.ledger.byName(name);
			String mark = known == null ? "" : verdictMark(known.verdict);
			String label = mark + name;
			int cw = textRenderer.getWidth(label) + 8;
			if (sx + cw > ix + iw) {
				if (++row >= 2) break;
				sx = ix;
			}
			int cy = ty + row * 14;
			boolean hover = over(mouseX, mouseY, sx, cy, cw, 12);
			roundedBorder(context, sx, cy, cw, 12, hover ? HOVER_BG : HOVER_BG, hover ? LINK : BORDER);
			int color = known == null || known.verdict == Profile.Verdict.NONE ? LINK : verdictColor(known.verdict);
			text(context, label, sx + 4, cy + 2, color);
			panelHits.add(new Hit(sx, cy, sx + cw, cy + 12, "suggest:" + name, () -> pickSuggestion(name)));
			sx += cw + 4;
		}
		ty += 32;

		text(context, "Detail (optional, added to each)", ix, ty, MUTED);
		ty += 11;
		roundedBorder(context, ix, ty, iw, 16, FIELD, addNote.isFocused() ? 0xFFE77600 : INPUT_BORDER);
		addNote.setX(ix + 4);
		addNote.setY(ty + 4);
		addNote.setWidth(iw - 8);
		addNoteTop = ty;
		ty += 20;

		text(context, "Group (optional, puts them all in it)", ix, ty, MUTED);
		ty += 11;
		roundedBorder(context, ix, ty, iw, 16, FIELD, addGroup.isFocused() ? 0xFFE77600 : INPUT_BORDER);
		addGroup.setX(ix + 4);
		addGroup.setY(ty + 4);
		addGroup.setWidth(iw - 8);
		addGroupTop = ty;
		ty += 20;

		// Which circle "★ Friends" puts them in.
		String circleLabel = "Friend circle: ";
		text(context, circleLabel, ix, ty + 2, MUTED);
		int ccx = ix + textRenderer.getWidth(circleLabel);
		for (Friends.Circle c : Friends.circles()) {
			int cw = textRenderer.getWidth(c.name) + 14;
			if (ccx + cw > ix + iw) break;
			boolean on = c.id.equals(addCircle);
			boolean hover = over(mouseX, mouseY, ccx, ty, cw, 12);
			int color = GROUP_COLORS[c.color % GROUP_COLORS.length];
			roundedBorder(context, ccx, ty, cw, 12, on ? tint(color, CARD, 0.25f) : hover ? HOVER_BG : CARD, on ? color : BORDER);
			context.fill(ccx + 4, ty + 4, ccx + 8, ty + 8, color);
			text(context, c.name, ccx + 10, ty + 2, on ? color : TEXT);
			panelHits.add(new Hit(ccx, ty, ccx + cw, ty + 12, "add-circle:" + c.name, () -> {
				addCircle = c.id;
				addTarget = Profile.Verdict.FRIEND;
			}));
			ccx += cw + 3;
		}
		ty += 18;

		int bw = (iw - 12) / 4;
		panelButton(context, mouseX, mouseY, ix, ty, bw, "★ Friends", FRIEND_BG, FRIEND, FRIEND, Profile.Verdict.FRIEND);
		panelButton(context, mouseX, mouseY, ix + bw + 4, ty, bw, "✔ Nice", YELLOW, YELLOW_EDGE, BUTTON_TEXT, Profile.Verdict.NICE);
		panelButton(context, mouseX, mouseY, ix + 2 * (bw + 4), ty, bw, "? Look Into", SOFT, LOOK, TEXT, Profile.Verdict.LOOK);
		panelButton(context, mouseX, mouseY, ix + 3 * (bw + 4), ty, iw - 3 * (bw + 4), "✖ Naughty", ORANGE, ORANGE_EDGE, BUTTON_TEXT, Profile.Verdict.NAUGHTY);
		ty += 24;

		if (addStatus != null) {
			int lines = 0;
			for (var line : textRenderer.wrapLines(Text.literal(addStatus), iw)) {
				if (++lines > 2) break;
				context.drawText(textRenderer, line, ix, ty, addStatusColor, false);
				ty += 10;
			}
		}
		String target = addTarget == Profile.Verdict.FRIEND ? "Friends (" + (Friends.byId(addCircle) != null ? Friends.byId(addCircle).name : "") + ")" : verdictWord(addTarget) + " list";
		textTrim(context, "Enter adds to your " + target + " · Tab next box · Esc closes", ix, y + h - 14, iw, MUTED);
	}

	private void panelButton(DrawContext context, int mouseX, int mouseY, int x, int y, int w, String label, int fill, int edge, int textColor, Profile.Verdict list) {
		boolean hover = over(mouseX, mouseY, x, y, w, 18);
		roundedBorder(context, x, y, w, 18, hover ? darken(fill) : fill, edge);
		// The list Enter adds to gets a dark outline.
		if (addTarget == list) context.drawStrokedRectangle(x - 1, y - 1, w + 2, 20, TEXT);
		String text = fit(label, w - 6);
		context.drawText(textRenderer, text, x + (w - textRenderer.getWidth(text)) / 2, y + 5, textColor, false);
		String id = switch (list) {
			case NICE -> "add-to-nice";
			case LOOK -> "add-to-look";
			case FRIEND -> "add-to-friends";
			default -> "add-to-naughty";
		};
		panelHits.add(new Hit(x, y, x + w, y + 18, id, () -> addNames(list)));
	}

	private boolean panelClicked(Click click, boolean doubled) {
		double mx = click.x();
		double my = click.y();
		for (Hit hit : List.copyOf(panelHits).reversed()) {
			if (mx >= hit.x0 && mx < hit.x1 && my >= hit.y0 && my < hit.y1) {
				hit.run.run();
				return true;
			}
		}
		for (TextFieldWidget field : new TextFieldWidget[] {addField, addNote, addGroup}) {
			int top = field == addField ? addFieldTop : field == addNote ? addNoteTop : addGroupTop;
			if (mx >= field.getX() - 4 && mx < field.getX() + field.getWidth() + 4 && my >= top && my < top + 16) {
				setFocused(field);
				field.mouseClicked(click, doubled);
				return true;
			}
		}
		return true;
	}

	private boolean panelKey(KeyInput input) {
		switch (input.key()) {
			case GLFW.GLFW_KEY_ESCAPE -> closeAdd();
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> addNames(addTarget);
			case GLFW.GLFW_KEY_TAB -> {
				// Tab with a half-typed name and a suggestion completes it; otherwise it moves to the other box.
				List<String> suggestions = suggestions();
				if (getFocused() == addField && !lastToken().isEmpty() && !suggestions.isEmpty()
						&& !suggestions.get(0).equalsIgnoreCase(lastToken())) {
					pickSuggestion(suggestions.get(0));
				} else {
					setFocused(getFocused() == addField ? addNote : getFocused() == addNote ? addGroup : addField);
				}
			}
			default -> {
				if (getFocused() != null) return getFocused().keyPressed(input);
			}
		}
		return true;
	}

	// ---- The Share panel ----

	private void openShare(ShareView view) {
		sharing = true;
		shareView = view;
		shareStatus = null;
		shareSavedFile = null;
		previewScroll = 0;
		setFocused(null);
	}

	private void closeShare() {
		sharing = false;
		incoming = null;
		incomingRows = null;
		shareStatus = null;
	}

	private String myName() {
		return client.getSession().getUsername();
	}

	private UUID myId() {
		return client.player != null ? client.player.getUuid() : client.getSession().getUuidOrNull();
	}

	private List<Profile> shareChoice() {
		return Sharing.choose(mod.ledger, shareScope, myId());
	}

	/** The share code for the current choice, made once per change rather than every frame. */
	private String currentCode() {
		List<Profile> chosen = shareChoice();
		int details = 0;
		for (Profile p : chosen) details += p.details.size();
		String key = shareScope + "/" + shareChat + "/" + chosen.size() + "/" + details;
		if (!key.equals(shareCodeKey)) {
			shareCodeKey = key;
			shareCode = Sharing.code(Sharing.pack(chosen, shareChat, myName(), myId(), mod.ledger.key()));
		}
		return shareCode;
	}

	private void copyShareCode() {
		if (shareChoice().isEmpty()) {
			shareStatus = "Nobody to share yet. Put someone on a list first.";
			shareStatusColor = NAUGHTY;
			return;
		}
		String code = currentCode();
		client.keyboard.setClipboard(code);
		shareSavedFile = null;
		if (code.length() <= Sharing.DISCORD_LIMIT) {
			shareStatus = "Copied! It fits in one Discord message. Paste it to your friend.";
			shareStatusColor = NICE;
		} else {
			shareStatus = "Copied, but it's " + String.format(Locale.ROOT, "%,d", code.length())
					+ " characters: too long for one Discord message. Save it as a file and send the file instead.";
			shareStatusColor = HOVER_LINK;
		}
	}

	private void saveShareFile() {
		List<Profile> chosen = shareChoice();
		if (chosen.isEmpty()) {
			shareStatus = "Nobody to share yet. Put someone on a list first.";
			shareStatusColor = NAUGHTY;
			return;
		}
		try {
			shareSavedFile = Sharing.save(Sharing.pack(chosen, shareChat, myName(), myId(), mod.ledger.key()));
			shareStatus = "Saved " + shareSavedFile.getFileName() + ". Send that file to your friend.";
			shareStatusColor = NICE;
		} catch (java.io.IOException e) {
			shareSavedFile = null;
			shareStatus = "Couldn't save the file: " + e.getMessage();
			shareStatusColor = NAUGHTY;
		}
	}

	/** Copies a share code for just this player. */
	private void sharePlayer(Profile p) {
		Sharing.Pack pack = Sharing.pack(List.of(p), false, myName(), myId(), mod.ledger.key());
		flash("Making a share link for " + p.name + "…", MUTED, null);
		CloudShare.upload(pack).whenComplete((link, error) -> client.execute(() -> {
			if (error != null) {
				// No internet: the long code works too.
				client.keyboard.setClipboard(Sharing.code(pack));
				flash("Copied a share code for " + p.name + " (couldn't make a link). Paste it to a friend who has the mod.", NICE, null);
				return;
			}
			mod.watcher.ownLink(link);
			client.keyboard.setClipboard(link);
			flash("Copied a share link for " + p.name + ". Paste it to a friend who has the mod.", NICE, null);
		}));
	}

	private void pasteShare() {
		String clip = client.keyboard.getClipboard();
		String link = CloudShare.find(clip);
		if (link != null) openShareLink(link);
		else loadShare(() -> Sharing.read(clip));
	}

	/** Downloads a share link, then shows what importing it would do. */
	private void openShareLink(String link) {
		sharing = true;
		shareView = ShareView.RECEIVE;
		shareStatus = "Downloading and unlocking…";
		shareStatusColor = MUTED;
		CloudShare.download(link).whenComplete((pack, error) -> client.execute(() -> {
			if (error != null) {
				Throwable cause = error.getCause() != null ? error.getCause() : error;
				shareView = ShareView.RECEIVE;
				shareStatus = cause.getMessage() == null ? "Couldn't open that link." : cause.getMessage();
				shareStatusColor = NAUGHTY;
				return;
			}
			loadShare(() -> pack);
		}));
	}

	/** Opens with the share panel downloading this link. */
	public ShopScreen withShareLink(String link) {
		openLink = link;
		return this;
	}

	/** Reads a friend's share and shows what importing it would do. */
	private void loadShare(java.util.function.Supplier<Sharing.Pack> reader) {
		sharing = true;
		try {
			incoming = reader.get();
		} catch (IllegalArgumentException e) {
			incoming = null;
			shareView = ShareView.RECEIVE;
			shareStatus = e.getMessage();
			shareStatusColor = NAUGHTY;
			return;
		}
		if (incoming.players.isEmpty()) {
			incoming = null;
			shareView = ShareView.RECEIVE;
			shareStatus = "That share has nobody in it.";
			shareStatusColor = NAUGHTY;
			return;
		}
		shareStatus = null;
		shareView = ShareView.PREVIEW;
		previewScroll = 0;
		replan();
	}

	private void replan() {
		incomingRows = Sharing.plan(incoming, mod.ledger, myId(), myName(), takeLists, theirsWins);
		// Conflicts first, then new players, then updates; the rest last.
		incomingRows.sort(Comparator.comparingInt((Sharing.Row r) -> switch (r.outcome) {
			case YOU -> 0;
			case CONFLICT -> 1;
			case NEW -> 2;
			case UPDATE -> 3;
			case SAME -> 4;
		}).thenComparing(r -> r.in.name.toLowerCase(Locale.ROOT)));
	}

	private int importCount() {
		int n = 0;
		for (Sharing.Row row : incomingRows) {
			if (row.changes()) n++;
		}
		return n;
	}

	private void importShare() {
		if (incoming == null) return;
		int n = importCount();
		if (n == 0) {
			shareStatus = "Nothing new to import. You already have all of this.";
			shareStatusColor = MUTED;
			return;
		}
		String from = incoming.from.isEmpty() ? "your friend" : incoming.from;
		Runnable undo = Sharing.apply(incoming, incomingRows, mod.ledger, myName());
		closeShare();
		cachedResults = null;
		flash("Imported " + n + (n == 1 ? " player" : " players") + " from " + from, NICE, () -> {
			undo.run();
			cachedResults = null;
			flash("Import undone", MUTED, null);
		});
	}

	@Override
	public void onFilesDropped(List<Path> paths) {
		if (paths.isEmpty()) return;
		Path file = paths.stream().filter(f -> f.getFileName().toString().endsWith(Sharing.EXTENSION)).findFirst().orElse(paths.get(0));
		loadShare(() -> Sharing.readFile(file));
	}

	private void renderSharePanel(DrawContext context, int mouseX, int mouseY) {
		panelHits.clear();
		context.fill(0, 0, width, height, 0x99000000);
		int w = Math.min(360, width - 16);
		int h = Math.min(shareView == ShareView.PREVIEW ? 300 : shareView == ShareView.SEND ? 262 : 236, height - 12);
		int x = (width - w) / 2;
		int y = Math.max(6, (height - h) / 2);
		roundedBorder(context, x, y, w, h, CARD, BORDER);
		context.fill(x + 1, y + 2, x + w - 1, y + 22, SOFT);
		context.fill(x + 1, y + 22, x + w - 1, y + 23, BORDER);
		String title = shareView == ShareView.PREVIEW && incoming != null
				? "Import from " + (incoming.from.isEmpty() ? "a friend" : incoming.from) : "Share your lists";
		context.drawText(textRenderer, Text.literal(fit(title, w - 40)).styled(s -> s.withBold(true)), x + 10, y + 8, TEXT, false);
		int closeX = x + w - 16;
		boolean closeHover = over(mouseX, mouseY, closeX - 3, y + 3, 14, 16);
		text(context, "✖", closeX, y + 8, closeHover ? TEXT : MUTED);
		panelHits.add(new Hit(closeX - 3, y + 3, closeX + 11, y + 19, "share-close", this::closeShare));

		int ix = x + 10;
		int iw = w - 20;
		int ty = y + 30;
		// Send / Get switch.
		int half = (iw - 4) / 2;
		boolean sending = shareView == ShareView.SEND;
		segment(context, mouseX, mouseY, ix, ty, half, "Send to a friend", sending, "share-send", () -> {
			shareView = ShareView.SEND;
			shareStatus = null;
		});
		segment(context, mouseX, mouseY, ix + half + 4, ty, iw - half - 4, "Get from a friend", !sending, "share-receive", () -> {
			shareView = incoming != null ? ShareView.PREVIEW : ShareView.RECEIVE;
			shareStatus = null;
		});
		ty += 22;

		switch (shareView) {
			case SEND -> renderShareSend(context, mouseX, mouseY, ix, ty, iw, y + h);
			case RECEIVE -> renderShareReceive(context, mouseX, mouseY, ix, ty, iw, y + h);
			case PREVIEW -> renderSharePreview(context, mouseX, mouseY, ix, ty, iw, y + h);
		}
	}

	private void segment(DrawContext context, int mouseX, int mouseY, int x, int y, int w, String label, boolean active, String hitLabel, Runnable run) {
		boolean hover = over(mouseX, mouseY, x, y, w, 16);
		roundedBorder(context, x, y, w, 16, active ? TEXT : hover ? SOFT_HOVER : SOFT, active ? TEXT : BORDER);
		String shown = fit(label, w - 8);
		context.drawText(textRenderer, Text.literal(shown).styled(s -> s.withBold(active)), x + (w - textRenderer.getWidth(shown)) / 2, y + 4,
				active ? CARD : TEXT, false);
		panelHits.add(new Hit(x, y, x + w, y + 16, hitLabel, run));
	}

	/** A button in the share panel. */
	private void shareButton(DrawContext context, int mouseX, int mouseY, int x, int y, int w, String label, int fill, int edge, int textColor, String hitLabel, Runnable run) {
		boolean hover = over(mouseX, mouseY, x, y, w, 18);
		roundedBorder(context, x, y, w, 18, hover ? darken(fill) : fill, edge);
		String shown = fit(label, w - 6);
		context.drawText(textRenderer, shown, x + (w - textRenderer.getWidth(shown)) / 2, y + 5, textColor, false);
		panelHits.add(new Hit(x, y, x + w, y + 18, hitLabel, run));
	}

	private int panelCheckbox(DrawContext context, int mouseX, int mouseY, int x, int y, int w, String label, boolean checked, String hitLabel, Runnable run) {
		boolean hover = over(mouseX, mouseY, x, y - 1, w, 11);
		roundedBorder(context, x, y, 9, 9, checked ? LINK : FIELD, checked ? LINK : INPUT_BORDER);
		if (checked) text(context, "✔", x + 1, y + 1, CARD);
		context.drawText(textRenderer, Text.literal(fit(label, w - 14)).styled(s -> s.withUnderline(hover)), x + 13, y + 1, TEXT, false);
		panelHits.add(new Hit(x, y - 1, x + w, y + 10, hitLabel, run));
		return y + 13;
	}

	/** Wrapped text in the panel, at most {@code maxLines} lines; returns the y after it. */
	private int panelText(DrawContext context, String s, int x, int y, int w, int color, int maxLines) {
		int lines = 0;
		for (var line : textRenderer.wrapLines(Text.literal(s), w)) {
			if (++lines > maxLines) break;
			context.drawText(textRenderer, line, x, y, color, false);
			y += 10;
		}
		return y;
	}

	private void renderShareSend(DrawContext context, int mouseX, int mouseY, int x, int y, int w, int bottom) {
		context.drawText(textRenderer, Text.literal("What to share").styled(s -> s.withBold(true)), x, y, TEXT, false);
		y += 13;
		UUID me = myId();
		for (Sharing.Scope scope : Sharing.Scope.values()) {
			int n = Sharing.choose(mod.ledger, scope, me).size();
			boolean on = shareScope == scope;
			boolean hover = over(mouseX, mouseY, x, y - 2, w, 13);
			if (hover) context.fill(x - 2, y - 2, x + w + 2, y + 11, HOVER_BG);
			roundedBorder(context, x, y, 9, 9, on ? LINK : FIELD, on ? LINK : INPUT_BORDER);
			if (on) context.fill(x + 3, y + 3, x + 6, y + 6, CARD);
			String count = n + (n == 1 ? " player" : " players");
			int countW = textRenderer.getWidth(count);
			context.drawText(textRenderer, Text.literal(fit(scope.label, w - countW - 24)).styled(s -> s.withBold(on)), x + 14, y + 1, TEXT, false);
			text(context, count, x + w - countW, y + 1, MUTED);
			panelHits.add(new Hit(x, y - 2, x + w, y + 11, "scope:" + scope.name(), () -> {
				shareScope = scope;
				shareStatus = null;
			}));
			y += 14;
		}
		y += 2;
		y = panelCheckbox(context, mouseX, mouseY, x, y, w, "Include chat logs (makes it much bigger)", shareChat, "share-chat", () -> {
			shareChat = !shareChat;
			shareStatus = null;
		});
		y += 2;

		List<Profile> chosen = shareChoice();
		int details = 0;
		for (Profile p : chosen) details += p.details.size();
		y = panelText(context, chosen.size() + (chosen.size() == 1 ? " player" : " players") + " · " + details + (details == 1 ? " detail" : " details"),
				x, y, w, MUTED, 1);
		y += 4;

		// The easy way: a short link, made once per choice.
		String link = currentLink();
		if (link == null) {
			String label = uploading ? "Making your link…" : "🔗 Get share link";
			shareButton(context, mouseX, mouseY, x, y, w, label, YELLOW, YELLOW_EDGE, BUTTON_TEXT, "share-link", () -> makeLink(null));
		} else {
			int copyW = textRenderer.getWidth("Copy") + 16;
			roundedBorder(context, x, y, w - copyW - 4, 18, FIELD, NICE);
			textTrim(context, link, x + 5, y + 5, w - copyW - 14, TEXT);
			shareButton(context, mouseX, mouseY, x + w - copyW, y, copyW, "Copy", YELLOW, YELLOW_EDGE, BUTTON_TEXT, "share-link-copy", () -> {
				client.keyboard.setClipboard(link);
				shareStatus = "Copied! Paste it to your friend anywhere (Discord, a whisper...). Their mod opens it.";
				shareStatusColor = NICE;
			});
		}
		y += 22;

		// Or straight to someone online, as a whisper: friends first.
		List<PlayerListEntry> people = new ArrayList<>(online());
		people.sort(Comparator.comparingInt((PlayerListEntry e) -> {
			Profile p = mod.ledger.get(e.getProfile().id());
			return p == null ? 4 : switch (p.verdict) {
				case FRIEND -> 0;
				case NICE -> 1;
				case NONE -> 2;
				default -> 3;
			};
		}).thenComparing(e -> e.getProfile().name().toLowerCase(Locale.ROOT)));
		people.removeIf(e -> {
			Profile p = mod.ledger.get(e.getProfile().id());
			return p != null && p.verdict == Profile.Verdict.NAUGHTY;
		});
		if (!people.isEmpty()) {
			String lead = "Send in game: ";
			text(context, lead, x, y + 2, MUTED);
			int cx = x + textRenderer.getWidth(lead);
			int row = 0;
			for (PlayerListEntry e : people) {
				String name = e.getProfile().name();
				Profile p = mod.ledger.get(e.getProfile().id());
				String label = (p == null ? "" : verdictMark(p.verdict)) + name;
				int cw = textRenderer.getWidth(label) + 8;
				if (cx + cw > x + w) {
					if (++row >= 2) break;
					cx = x + textRenderer.getWidth(lead);
				}
				int cy = y + row * 14;
				boolean hover = over(mouseX, mouseY, cx, cy, cw, 12);
				int color = p == null || p.verdict == Profile.Verdict.NONE ? LINK : verdictColor(p.verdict);
				roundedBorder(context, cx, cy, cw, 12, hover ? HOVER_BG : CARD, hover ? color : BORDER);
				text(context, label, cx + 4, cy + 2, color);
				panelHits.add(new Hit(cx, cy, cx + cw, cy + 12, "share-whisper:" + name, () -> makeLink(name)));
				cx += cw + 3;
			}
			y += (row + 1) * 14 + 4;
		}
		if (shareStatus != null) y = panelText(context, shareStatus, x, y, w, shareStatusColor, 3);

		// The old ways, for no internet or very big shares.
		int ly = bottom - 38;
		text(context, "Other ways:", x, ly, MUTED);
		int lx = x + textRenderer.getWidth("Other ways: ");
		lx = link(context, mouseX, mouseY, lx, ly, "Copy long code", "share-copy", this::copyShareCode, panelHits) + 10;
		lx = link(context, mouseX, mouseY, lx, ly, "Save as file", "share-save", this::saveShareFile, panelHits) + 10;
		if (shareSavedFile != null) {
			Path folder = shareSavedFile.getParent();
			link(context, mouseX, mouseY, lx, ly, "Show the file", "share-show", () -> Util.getOperatingSystem().open(folder), panelHits);
		}
		panelText(context, "Links are locked: only someone with the whole link can read it. They expire after a while.",
				x, bottom - 24, w, MUTED, 2);
	}

	/** The share link for the current choice, or null if it hasn't been made (or the choice changed since). */
	private String currentLink() {
		if (shareLink == null) return null;
		return shareLinkKey.equals(shareChoiceKey()) ? shareLink : null;
	}

	private String shareChoiceKey() {
		List<Profile> chosen = shareChoice();
		int details = 0;
		for (Profile p : chosen) details += p.details.size();
		return shareScope + "/" + shareChat + "/" + chosen.size() + "/" + details + "/" + mod.ledger.changes();
	}

	/** Makes the share link (once), then copies it, or whispers it to {@code sendTo}. */
	private void makeLink(String sendTo) {
		if (shareChoice().isEmpty()) {
			shareStatus = "Nobody to share yet. Put someone on a list first.";
			shareStatusColor = NAUGHTY;
			return;
		}
		String ready = currentLink();
		if (ready != null) {
			deliverLink(ready, sendTo);
			return;
		}
		if (uploading) return;
		uploading = true;
		shareStatus = "Locking and uploading…";
		shareStatusColor = MUTED;
		String key = shareChoiceKey();
		Sharing.Pack pack = Sharing.pack(shareChoice(), shareChat, myName(), myId(), mod.ledger.key());
		CloudShare.upload(pack).whenComplete((link, error) -> client.execute(() -> {
			uploading = false;
			if (error != null) {
				Throwable cause = error.getCause() != null ? error.getCause() : error;
				shareStatus = cause.getMessage() == null ? "Couldn't make a link. Use Copy long code instead." : cause.getMessage();
				shareStatusColor = NAUGHTY;
				return;
			}
			shareLink = link;
			shareLinkKey = key;
			mod.watcher.ownLink(link);
			deliverLink(link, sendTo);
		}));
	}

	private void deliverLink(String link, String sendTo) {
		if (sendTo == null) {
			client.keyboard.setClipboard(link);
			shareStatus = "Copied! Paste it to your friend anywhere (Discord, a whisper...). Their mod opens it.";
			shareStatusColor = NICE;
			return;
		}
		if (client.getNetworkHandler() == null) return;
		String command = NNConfig.INSTANCE.whisperCommand.strip().replaceFirst("^/", "");
		client.getNetworkHandler().sendChatCommand(command + " " + sendTo + " My Naughty or Nice lists: " + link);
		shareStatus = "Sent to " + sendTo + " (/" + command + "). If they have the mod, they get a button to open it.";
		shareStatusColor = NICE;
	}

	private void renderShareReceive(DrawContext context, int mouseX, int mouseY, int x, int y, int w, int bottom) {
		// The drop zone.
		int zoneH = 44;
		roundedBorder(context, x, y, w, zoneH, SOFT, BORDER);
		for (int dx = x + 4; dx < x + w - 4; dx += 6) {
			context.fill(dx, y + 3, dx + 3, y + 4, INPUT_BORDER);
			context.fill(dx, y + zoneH - 4, dx + 3, y + zoneH - 3, INPUT_BORDER);
		}
		String drop = "Drop a .nnshare file onto the game";
		context.drawText(textRenderer, Text.literal(drop).styled(s -> s.withBold(true)), x + (w - textRenderer.getWidth(Text.literal(drop).styled(s -> s.withBold(true)))) / 2,
				y + 12, TEXT, false);
		String or = "or paste a share link or code a friend sent you";
		text(context, or, x + (w - textRenderer.getWidth(or)) / 2, y + 25, MUTED);
		y += zoneH + 6;

		int bw = (w - 6) / 2;
		shareButton(context, mouseX, mouseY, x, y, bw, "Paste link or code", YELLOW, YELLOW_EDGE, BUTTON_TEXT, "share-paste", this::pasteShare);
		shareButton(context, mouseX, mouseY, x + bw + 6, y, w - bw - 6, "Open shared folder", SOFT, BORDER, TEXT, "share-folder", () -> {
			try {
				Files.createDirectories(Sharing.folder());
			} catch (java.io.IOException ignored) {
				// Opening will just fail quietly.
			}
			Util.getOperatingSystem().open(Sharing.folder());
		});
		y += 24;
		if (shareStatus != null) y = panelText(context, shareStatus, x, y, w, shareStatusColor, 3) + 4;

		List<Path> files = Sharing.files();
		if (!files.isEmpty()) {
			context.drawText(textRenderer, Text.literal("Share files in your folder").styled(s -> s.withBold(true)), x, y, TEXT, false);
			y += 12;
			for (Path file : files) {
				if (y + 13 > bottom - 8) break;
				String name = file.getFileName().toString();
				String when = ago(file.toFile().lastModified());
				boolean hover = over(mouseX, mouseY, x, y - 1, w, 12);
				if (hover) context.fill(x - 2, y - 2, x + w + 2, y + 10, HOVER_BG);
				int whenW = textRenderer.getWidth(when);
				context.drawText(textRenderer, Text.literal(fit(name, w - whenW - 8)).styled(s -> s.withUnderline(hover)), x, y, hover ? HOVER_LINK : LINK, false);
				text(context, when, x + w - whenW, y, MUTED);
				panelHits.add(new Hit(x, y - 1, x + w, y + 10, "share-file:" + name, () -> loadShare(() -> Sharing.readFile(file))));
				y += 12;
			}
		} else {
			panelText(context, "Files you save or get from friends can also go in the shared folder; they'll be listed here.", x, y, w, MUTED, 2);
		}
	}

	private void renderSharePreview(DrawContext context, int mouseX, int mouseY, int x, int y, int w, int bottom) {
		if (incoming == null || incomingRows == null) {
			shareView = ShareView.RECEIVE;
			return;
		}
		String from = incoming.from.isEmpty() ? "A friend" : incoming.from;
		String place = Sharing.placeName(incoming.server);
		y = panelText(context, from + "'s lists from " + place + " · shared " + ago(incoming.created), x, y, w, MUTED, 2);
		String here = mod.ledger.key();
		if (here != null && !incoming.server.isEmpty() && !incoming.server.equals(here)) {
			y = panelText(context, "These are from " + place + ". You're on " + Sharing.placeName(here) + ", so they'll go into its lists.",
					x, y, w, HOVER_LINK, 2);
		}
		int fresh = 0, updated = 0, conflicts = 0, same = 0;
		Sharing.Row you = null;
		for (Sharing.Row row : incomingRows) {
			switch (row.outcome) {
				case NEW -> fresh++;
				case UPDATE -> updated++;
				case CONFLICT -> conflicts++;
				case SAME -> same++;
				case YOU -> you = row;
			}
		}
		if (you != null && you.in.friend) {
			y = panelText(context, "★ " + from + " has you as a friend.", x, y, w, FRIEND, 1);
		} else if (you != null && you.in.verdict != Profile.Verdict.NONE) {
			y = panelText(context, verdictMark(you.in.verdict) + from + " has you on their " + verdictWord(you.in.verdict) + " list.", x, y, w,
					verdictColor(you.in.verdict), 1);
		}
		List<String> parts = new ArrayList<>();
		if (fresh > 0) parts.add(fresh + " new");
		if (updated > 0) parts.add(updated + " updated");
		if (conflicts > 0) parts.add(conflicts + " you disagree on");
		if (same > 0) parts.add(same + " already the same");
		y = panelText(context, String.join(" · ", parts), x, y + 2, w, TEXT, 2) + 4;

		// The players, scrollable.
		int listBottom = bottom - 64;
		int top = y;
		rounded(context, x, top, w, listBottom - top, SOFT);
		context.enableScissor(x, top, x + w, listBottom);
		int ry = top + 3 - (int) previewScroll;
		int rowH = 24;
		for (Sharing.Row row : incomingRows) {
			if (row.outcome == Sharing.Outcome.YOU) continue;
			if (ry + rowH > top && ry < listBottom) previewRow(context, row, x + 4, ry, w - 8);
			ry += rowH;
		}
		context.disableScissor();
		previewMaxScroll = Math.max(0, ry + (int) previewScroll - listBottom + 3);
		if (previewMaxScroll > 0) {
			int view = listBottom - top;
			int bar = Math.max(12, view * view / (view + previewMaxScroll));
			int barY = top + (int) ((view - bar) * previewScroll / previewMaxScroll);
			context.fill(x + w - 3, barY, x + w - 1, barY + bar, INPUT_BORDER);
		}
		y = listBottom + 5;

		y = panelCheckbox(context, mouseX, mouseY, x, y, w, "Use their lists for players you haven't listed", takeLists, "share-take", () -> {
			takeLists = !takeLists;
			replan();
		});
		y = panelCheckbox(context, mouseX, mouseY, x, y, w, "Where you disagree, use their list, not yours", theirsWins && takeLists, "share-theirs", () -> {
			theirsWins = !theirsWins;
			if (theirsWins) takeLists = true;
			replan();
		});
		y += 3;
		int n = importCount();
		int bw = (w - 6) * 2 / 3;
		shareButton(context, mouseX, mouseY, x, y, bw, n == 0 ? "Nothing new to import" : "Import " + n + (n == 1 ? " player" : " players"),
				n == 0 ? SOFT : YELLOW, n == 0 ? BORDER : YELLOW_EDGE, n == 0 ? MUTED : BUTTON_TEXT, "share-import", this::importShare);
		shareButton(context, mouseX, mouseY, x + bw + 6, y, w - bw - 6, "Back", SOFT, BORDER, TEXT, "share-back", () -> {
			incoming = null;
			incomingRows = null;
			shareView = ShareView.RECEIVE;
		});
		if (shareStatus != null) panelText(context, shareStatus, x, y + 21, w, shareStatusColor, 1);
	}

	/** One player in the import preview: who, which list they're on for the friend, and what happens. */
	private void previewRow(DrawContext context, Sharing.Row row, int x, int y, int w) {
		boolean conflict = row.outcome == Sharing.Outcome.CONFLICT;
		if (conflict) rounded(context, x - 2, y - 1, w + 4, 22, BAD_BG);
		face(context, row.in.id, x, y + 2, 16);
		int tx = x + 22;
		String verdictText = row.in.friend ? "★ Their friend" : verdictWord(row.in.verdict);
		int chipW = textRenderer.getWidth(verdictText) + 8;
		int chipColor = verdictColor(row.in.view());
		context.drawText(textRenderer, Text.literal(fit(row.in.name, w - 22 - chipW - 6)).styled(s -> s.withBold(true)), tx, y + 2, TEXT, false);
		roundedBorder(context, x + w - chipW, y + 1, chipW, 11, CARD, chipColor);
		text(context, verdictText, x + w - chipW + 4, y + 3, chipColor);

		String what;
		Profile.Verdict mine = row.mine == null ? Profile.Verdict.NONE : row.mine.verdict;
		List<String> adds = new ArrayList<>();
		if (row.newDetails > 0) adds.add("+" + row.newDetails + (row.newDetails == 1 ? " detail" : " details"));
		if (row.newChats > 0) adds.add("+" + row.newChats + (row.newChats == 1 ? " chat log" : " chat logs"));
		if (row.newGroups > 0) adds.add("+" + row.newGroups + (row.newGroups == 1 ? " group" : " groups"));
		if (row.goneDetails > 0) adds.add(row.goneDetails + " of their old details updated");
		if (row.viewChanged && row.mine != null) adds.add("their list noted");
		String extras = adds.isEmpty() ? "" : " · " + String.join(", ", adds);
		what = switch (row.outcome) {
			case NEW -> (row.result == Profile.Verdict.NONE ? "New to you" : "New: goes on your " + verdictWord(row.result) + " list") + extras;
			case CONFLICT -> "You: " + verdictWord(mine) + ". " + (row.result == mine ? "Keeping yours" : "Switching to theirs") + extras;
			case UPDATE -> (row.result != mine ? "Goes on your " + verdictWord(row.result) + " list" : "You know them") + extras;
			case SAME -> "Nothing new";
			case YOU -> "";
		};
		int whatColor = conflict ? NAUGHTY : row.outcome == Sharing.Outcome.SAME ? MUTED : row.outcome == Sharing.Outcome.NEW ? NICE : TEXT;
		textTrim(context, what, tx, y + 12, w - 22, whatColor);
	}

	private static String verdictWord(Profile.Verdict verdict) {
		return switch (verdict) {
			case NICE -> "Nice";
			case NAUGHTY -> "Naughty";
			case LOOK -> "Look Into";
			case FRIEND -> "Friends";
			case NONE -> "No list";
		};
	}

	private static int verdictColor(Profile.Verdict verdict) {
		return switch (verdict) {
			case NICE -> NICE;
			case NAUGHTY -> NAUGHTY;
			case LOOK -> LOOK;
			case FRIEND -> FRIEND;
			case NONE -> MUTED;
		};
	}

	/** "✔ ", "? ", "✖ ", "★ " or nothing. */
	private static String verdictMark(Profile.Verdict verdict) {
		return switch (verdict) {
			case NICE -> "✔ ";
			case NAUGHTY -> "✖ ";
			case LOOK -> "? ";
			case FRIEND -> "★ ";
			case NONE -> "";
		};
	}

	private boolean shareClicked(Click click) {
		double mx = click.x();
		double my = click.y();
		for (Hit hit : List.copyOf(panelHits).reversed()) {
			if (mx >= hit.x0 && mx < hit.x1 && my >= hit.y0 && my < hit.y1) {
				hit.run.run();
				return true;
			}
		}
		return true;
	}

	private boolean shareKey(KeyInput input) {
		if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
			if (shareView == ShareView.PREVIEW) {
				incoming = null;
				incomingRows = null;
				shareView = ShareView.RECEIVE;
			} else {
				closeShare();
			}
		} else if (input.isPaste()) {
			pasteShare();
		} else if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
			if (shareView == ShareView.SEND) copyShareCode();
			else if (shareView == ShareView.PREVIEW) importShare();
			else pasteShare();
		}
		return true;
	}

	// ---- Self-test hooks ----

	/** Clicks the first visible thing with this label (e.g. "nice:Steve", "tab:NICE"); used by the self-test. */
	public boolean testClick(String label) {
		for (Hit hit : List.copyOf(hits)) {
			if (hit.label.equals(label)) {
				hit.run.run();
				return true;
			}
		}
		return false;
	}

	/** Clicks something on the Add players panel (e.g. "add-to-nice"); used by the self-test. */
	public boolean testPanelClick(String label) {
		for (Hit hit : List.copyOf(panelHits)) {
			if (hit.label.equals(label)) {
				hit.run.run();
				return true;
			}
		}
		return false;
	}

	public void testAddText(String names, String note) {
		addField.setText(names);
		addNote.setText(note);
	}

	public boolean testAdding() {
		return adding;
	}

	public String testLabels() {
		return hits.stream().map(Hit::label).toList().toString();
	}

	public boolean testSharing() {
		return sharing;
	}

	/** Reads a share as if pasted; used by the self-test. */
	public void testLoadShare(String text) {
		loadShare(() -> Sharing.read(text));
	}

	public int testImportCount() {
		return incomingRows == null ? -1 : importCount();
	}

	public boolean testGrouping() {
		return grouping;
	}

	public void testGroupText(String group, String members) {
		if (group != null) groupField.setText(group);
		if (members != null) membersField.setText(members);
	}

	public void testAddGroup(String group) {
		addGroup.setText(group);
	}

	public int testChatRows() {
		return chatRows.size();
	}

	public void testChatText(String text) {
		chatField.setText(text);
	}

	public String testOpenGroup() {
		return openGroup;
	}

	public float testScale() {
		return uiScale;
	}

	public void testDetailText(String text) {
		detailField.setText(text);
	}

	public void testSearch(String text) {
		search.setText(text);
	}

	public Profile testSelected() {
		return selected;
	}

	public int testResultCount() {
		return results().size();
	}

	/** Clicks a row of the circle picker; used by the self-test. */
	public boolean testMenuClick(String label) {
		for (Hit hit : List.copyOf(menuHits)) {
			if (hit.label.equals(label)) {
				hit.run.run();
				return true;
			}
		}
		return false;
	}

	public boolean testCircleMenu() {
		return circleMenu != null;
	}

	public void testCircleText(String text) {
		circleField.setText(text);
	}

	public String testShareLink() {
		return currentLink();
	}

	public String testShareStatus() {
		return shareStatus;
	}

	public boolean testPreview() {
		return sharing && shareView == ShareView.PREVIEW && incomingRows != null;
	}
}
