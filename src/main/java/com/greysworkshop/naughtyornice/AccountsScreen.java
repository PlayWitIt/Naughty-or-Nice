package com.greysworkshop.naughtyornice;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.PlayerSkinWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The account screen. On the left, whoever you're playing as, in 3D (drag to turn; hover an account to preview its
 * skin). On the right, your accounts as cards: the one in use marked Active, Use on the others (Rejoin on a server).
 * Adding a Microsoft account walks you through the code at microsoft.com/link; an offline name is for single player.
 */
public final class AccountsScreen extends Screen {
	private enum View { LIST, CODE, OFFLINE }

	private enum Tone { WORKING, GOOD, BAD }

	// Colours: a dark slate card, a yellow call to action, green for "in use", red for removing.
	private static final int CARD_TOP = 0xFF1D2733, CARD_BOTTOM = 0xFF131A22, CARD_EDGE = 0xFF2B3746,
			ROW = 0xFF18202A, ROW_HOVER = 0xFF202B37, ROW_EDGE = 0xFF27323F, ROW_EDGE_HOVER = 0xFF3B4B5E,
			TEXT = 0xFFF2F4F6, MUTED = 0xFF8D99A6, FAINT = 0xFF5F6B78,
			GREEN = 0xFF4CD39B, GREEN_DARK = 0xFF2F8F6A, RED = 0xFFFF6B5E, BLUE = 0xFF5CC8E0,
			YELLOW = 0xFFFFD814, YELLOW_TOP = 0xFFFFE27A, YELLOW_SHADE = 0xFFC9A400, INK = 0xFF101418,
			GHOST = 0xFF232D39, GHOST_HOVER = 0xFF2D3947;

	private static final int MARGIN = 8, GAP = 6, RADIUS = 6, ROW_H = 28, ROW_GAP = 3, FOOTER = 30;

	private final Screen parent;
	private View view = View.LIST;
	private TextFieldWidget nameBox;
	private PlayerSkinWidget skinView;

	private boolean busy;
	private Accounts.Account switching;
	private Accounts.Account hovered;
	/** Last frame's hovered account: the left card is drawn before the list finds out what's under the mouse. */
	private Accounts.Account previewHover;
	private Accounts.Account confirmRemove;
	private MicrosoftLogin.DeviceCode code;
	private long codeShownAt, copiedAt;
	private String codeProgress;
	private volatile boolean cancelled;

	private String toast = "";
	private Tone toastTone = Tone.GOOD;
	private long toastAt;

	private double scroll;
	private final List<Hit> hits = new ArrayList<>();
	private final Map<Object, Float> hover = new HashMap<>();
	/** Where each account's card was drawn last frame (its middle), for the self-test to point at. */
	private final Map<String, int[]> rowCentres = new HashMap<>();
	private long lastFrame;
	private float frameMs;

	private record Hit(int x0, int y0, int x1, int y1, Runnable run) {
		boolean contains(double x, double y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	/** Where things go this frame, worked out from the window size. */
	private record Layout(int x, int y, int w, int h, int heroW) {
		boolean hero() {
			return heroW > 0;
		}

		int listX() {
			return hero() ? x + heroW + GAP : x;
		}

		int listW() {
			return hero() ? w - heroW - GAP : w;
		}
	}

	public AccountsScreen(Screen parent) {
		super(Text.literal("Accounts"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		textRenderer = UiFont.renderer();
		nameBox = new TextFieldWidget(textRenderer, 0, 0, 120, 18, Text.literal("Name"));
		nameBox.setMaxLength(16);
		nameBox.setTextPredicate(s -> s.matches("[A-Za-z0-9_]*"));
		nameBox.visible = view == View.OFFLINE;
		addDrawableChild(nameBox);
		Layout l = layout();
		int skinH = Math.max(60, l.h() - 84);
		skinView = new PlayerSkinWidget(Math.min(l.heroW() - 16, skinH * 6 / 10), skinH, client.getLoadedEntityModels(), this::previewSkin);
		skinView.visible = l.hero();
		addDrawableChild(skinView);
	}

	private Layout layout() {
		int w = Math.min(width - 2 * MARGIN, 540), h = Math.min(height - 2 * MARGIN, 290);
		int heroW = width >= 330 && height >= 200 ? Math.max(118, Math.min(170, w * 32 / 100)) : 0;
		return new Layout((width - w) / 2, (height - h) / 2, w, h, heroW);
	}

	@Override
	public void close() {
		cancelled = true;
		client.setScreen(parent);
	}

	// ---- Drawing ----

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		long now = System.currentTimeMillis();
		frameMs = lastFrame == 0 ? 0 : Math.min(100, now - lastFrame);
		lastFrame = now;
		hits.clear();
		Layout l = layout();
		context.fillGradient(0, 0, width, height, 0x50000000, 0x90000000);

		hovered = null;
		if (l.hero()) renderHero(context, l);
		else skinView.visible = false;
		renderListCard(context, mouseX, mouseY, l);
		previewHover = hovered;
		super.render(context, mouseX, mouseY, delta);
		renderToast(context, l);
	}

	/** The left card: who you are (or who you're pointing at), in 3D, on a soft spotlight. */
	private void renderHero(DrawContext context, Layout l) {
		int x = l.x(), y = l.y(), w = l.heroW(), h = l.h();
		card(context, x, y, w, h);
		Accounts.Account shown = previewAccount();
		boolean previewing = !shown.current();
		int accent = shown.kind() == Accounts.Kind.OFFLINE ? MUTED : GREEN;

		String label = previewing ? "PREVIEW" : "PLAYING AS";
		context.drawText(textRenderer, label, x + (w - textRenderer.getWidth(label)) / 2, y + 9, previewing ? BLUE : FAINT, false);
		Text name = Text.literal(fit(shown.name, w - 12)).styled(s -> s.withBold(true));
		context.drawText(textRenderer, name, x + (w - textRenderer.getWidth(name)) / 2, y + 20, TEXT, false);
		pill(context, x + w / 2, y + 32, kindLabel(shown), accent, true);

		// Spotlight behind the model and its shadow on the floor.
		int cx = x + w / 2, top = y + 50, bottom = y + h - 22;
		int mid = (top + bottom) / 2;
		for (int i = 0; i < 5; i++) {
			UiPaint.ellipse(context, cx, mid, w / 2 - 6 - i * 7, (bottom - top) / 2 - i * 9, UiPaint.fade(accent, 0.035f));
		}
		UiPaint.ellipse(context, cx, bottom - 3, Math.max(12, w / 5), 3, 0x55000000);
		skinView.visible = true;
		skinView.setX(cx - skinView.getWidth() / 2);
		skinView.setY(bottom - skinView.getHeight() - 2);

		String hint = shown.kind() == Accounts.Kind.OFFLINE ? "Offline names get a default skin" : "Drag to turn";
		context.drawText(textRenderer, fit(hint, w - 10), x + (w - Math.min(w - 10, textRenderer.getWidth(hint))) / 2, y + h - 13, FAINT, false);
	}

	/** The right card: the list, or one of the add-an-account steps, and the buttons along the bottom. */
	private void renderListCard(DrawContext context, int mouseX, int mouseY, Layout l) {
		int x = l.listX(), y = l.y(), w = l.listW(), h = l.h();
		card(context, x, y, w, h);
		int bodyTop = renderHeader(context, mouseX, mouseY, l, x, y, w);
		int bodyBottom = y + h - FOOTER;
		switch (view) {
			case LIST -> renderAccounts(context, mouseX, mouseY, x + 6, bodyTop, w - 12, bodyBottom);
			case CODE -> renderCode(context, mouseX, mouseY, x + 10, bodyTop + 2, w - 20, bodyBottom);
			case OFFLINE -> renderOffline(context, mouseX, mouseY, x + 10, bodyTop + 2, w - 20);
		}
		renderFooter(context, mouseX, mouseY, x, y + h - FOOTER, w);
	}

	/** Title, close button, and on a server a note that switching rejoins it. Returns where the body starts. */
	private int renderHeader(DrawContext context, int mouseX, int mouseY, Layout l, int x, int y, int w) {
		int tx = x + 10;
		if (view != View.LIST) {
			iconButton(context, mouseX, mouseY, x + 6, y + 6, 16, 16, "‹", MUTED, this::backToList);
			tx = x + 26;
		} else if (!l.hero()) {
			// No room for the 3D card: your face goes in the header instead.
			UiPaint.rounded(context, tx - 1, y + 6, 14, 14, 2, GREEN);
			PlayerSkinDrawer.draw(context, Accounts.currentAccount().skin(), tx, y + 7, 12);
			tx += 18;
		}
		String title = switch (view) {
			case LIST -> "Accounts";
			case CODE -> "Add a Microsoft account";
			case OFFLINE -> "Add an offline name";
		};
		Text titleText = Text.literal(title).styled(s -> s.withBold(true));
		context.drawText(textRenderer, titleText, tx, y + 10, TEXT, false);
		if (view == View.LIST) {
			String count = String.valueOf(Accounts.listed().size());
			int cx = tx + textRenderer.getWidth(titleText) + 5;
			UiPaint.rounded(context, cx, y + 8, textRenderer.getWidth(count) + 8, 12, 4, GHOST);
			context.drawText(textRenderer, count, cx + 4, y + 10, MUTED, false);
		}
		iconButton(context, mouseX, mouseY, x + w - 22, y + 6, 16, 16, "✕", MUTED, this::close);

		int top = y + 26;
		String where = whereNote();
		if (where != null) {
			context.drawText(textRenderer, fit(where, w - 20), x + 10, y + 24, BLUE, false);
			top = y + 36;
		}
		context.fill(x + 8, top - 2, x + w - 8, top - 1, CARD_EDGE);
		return top + 2;
	}

	private String whereNote() {
		if (view != View.LIST) return null;
		return switch (Accounts.where()) {
			case MENUS -> null;
			case SERVER -> {
				ServerInfo server = client.getCurrentServerEntry();
				yield "Switching leaves and rejoins " + (server == null ? "this server" : server.address) + " as that account";
			}
			case SINGLE_PLAYER -> "Switching saves this world and goes to the title screen";
		};
	}

	private void renderAccounts(DrawContext context, int mouseX, int mouseY, int x, int top, int w, int bottom) {
		List<Accounts.Account> accounts = Accounts.listed();
		if (accounts.isEmpty()) {
			renderEmpty(context, x, top, w, bottom);
			return;
		}
		int contentH = accounts.size() * (ROW_H + ROW_GAP) - ROW_GAP;
		int viewH = bottom - top - 4;
		double maxScroll = Math.max(0, contentH - viewH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		boolean scrolls = maxScroll > 0;
		int rowW = scrolls ? w - 5 : w;

		context.enableScissor(x - 1, top, x + w + 1, bottom - 2);
		int ry = top + 2 - (int) scroll;
		for (Accounts.Account account : accounts) {
			boolean visible = ry + ROW_H > top && ry < bottom;
			if (visible) renderRow(context, mouseX, mouseY, account, x, ry, rowW, top, bottom);
			ry += ROW_H + ROW_GAP;
		}
		context.disableScissor();

		if (scrolls) {
			int barH = Math.max(14, viewH * viewH / contentH);
			int barY = top + 2 + (int) ((viewH - barH) * (scroll / maxScroll));
			UiPaint.rounded(context, x + w - 3, top + 2, 3, viewH, 1, 0x30FFFFFF);
			UiPaint.rounded(context, x + w - 3, barY, 3, barH, 1, 0x90FFFFFF);
		}
	}

	private void renderRow(DrawContext context, int mouseX, int mouseY, Accounts.Account account, int x, int y, int w, int clipTop, int clipBottom) {
		boolean inClip = mouseY >= clipTop && mouseY < clipBottom;
		boolean over = inClip && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_H;
		if (over) hovered = account;
		rowCentres.put(account.name, new int[] {x + w / 3, y + ROW_H / 2});
		if (!over && confirmRemove == account) confirmRemove = null;
		float hv = animate(account.id(), over);
		boolean current = account.current();
		boolean offline = account.kind() == Accounts.Kind.OFFLINE;

		int fill = current ? 0xFF15302A : UiPaint.mix(ROW, ROW_HOVER, hv);
		int edge = current ? GREEN_DARK : UiPaint.mix(ROW_EDGE, ROW_EDGE_HOVER, hv);
		UiPaint.framed(context, x, y, w, ROW_H, 4, fill, edge);
		if (current) {
			UiPaint.hGradient(context, x + 1, y + 1, w / 2, ROW_H - 2, 0x304CD39B, 0x004CD39B);
			UiPaint.rounded(context, x + 1, y + 5, 2, ROW_H - 10, 1, GREEN);
		}

		// Face on a ring: green for accounts that can join online servers, grey for offline names.
		int fx = x + 6, fy = y + 4;
		UiPaint.rounded(context, fx - 1, fy - 1, 22, 22, 3, offline ? FAINT : current ? GREEN : UiPaint.mix(0xFF3A6E5C, GREEN, hv));
		PlayerSkinDrawer.draw(context, account.skin(), fx, fy, 20);

		// Room on the right for the action, then the name and the line under it.
		int actionW = current ? pillWidth("Active") : 40;
		int removeW = account.kind() == Accounts.Kind.LAUNCHER || current ? 0 : 18;
		int textW = w - 34 - actionW - removeW - 10;
		Text name = Text.literal(fit(account.name, textW)).styled(s -> s.withBold(true));
		context.drawText(textRenderer, name, x + 32, y + 6, TEXT, false);
		context.drawText(textRenderer, fit(subline(account), textW), x + 32, y + 17, current ? GREEN : MUTED, false);

		int ax = x + w - 6;
		if (current) {
			pill(context, ax - actionW / 2, y + (ROW_H - 12) / 2, "Active", GREEN, true);
		} else if (switching == account) {
			spinner(context, ax - 12, y + ROW_H / 2, YELLOW);
		} else if (confirmRemove == account) {
			int bw = 58;
			button(context, mouseX, mouseY, ax - bw, y + 6, bw, 16, "Remove?", RED, TEXT, !busy, () -> remove(account));
			return;
		} else {
			String use = switch (Accounts.where()) {
				case MENUS -> "Use";
				case SERVER -> "Rejoin";
				case SINGLE_PLAYER -> "Switch";
			};
			int bw = Math.max(40, textRenderer.getWidth(use) + 16);
			primary(context, mouseX, mouseY, ax - bw, y + 6, bw, 16, use, !busy && canUse(account), () -> use(account));
			ax -= bw;
		}
		if (removeW > 0 && confirmRemove != account && hv > 0.05f) {
			int rx = ax - 4 - 16;
			int color = UiPaint.fade(MUTED, hv);
			iconButton(context, mouseX, mouseY, rx, y + 6, 16, 16, "✕", color, () -> confirmRemove = account);
		}
	}

	private void renderEmpty(DrawContext context, int x, int top, int w, int bottom) {
		int cy = top + (bottom - top) / 2 - 26;
		UiPaint.rounded(context, x + w / 2 - 13, cy - 1, 26, 26, 4, CARD_EDGE);
		PlayerSkinDrawer.draw(context, DefaultSkinHelper.getSkinTextures(Accounts.offlineUuid("Steve")), x + w / 2 - 12, cy, 24);
		Text title = Text.literal("No accounts yet").styled(s -> s.withBold(true));
		context.drawText(textRenderer, title, x + (w - textRenderer.getWidth(title)) / 2, cy + 32, TEXT, false);
		int ly = cy + 44;
		for (OrderedText line : textRenderer.wrapLines(Text.literal("Add each Microsoft account once with a code. Then switch with one click."), w - 30)) {
			context.drawText(textRenderer, line, x + (w - textRenderer.getWidth(line)) / 2, ly, MUTED, false);
			ly += 10;
		}
	}

	/** Signing in: three numbered steps, the code in big tiles, and how long it still works. */
	private void renderCode(DrawContext context, int mouseX, int mouseY, int x, int y, int w, int bottom) {
		if (code == null) {
			int cy = y + (bottom - y) / 2 - 10;
			spinner(context, x + w / 2, cy, YELLOW);
			String asking = "Asking Microsoft for a code";
			context.drawText(textRenderer, asking, x + (w - textRenderer.getWidth(asking)) / 2, cy + 12, MUTED, false);
			return;
		}
		boolean signingIn = codeProgress != null;
		step(context, x, y, 1, "Open microsoft.com/link", signingIn ? Tone.GOOD : null);
		ghost(context, mouseX, mouseY, x + w - 58, y - 2, 58, 15, "Open page", () -> Util.getOperatingSystem().open(URI.create(code.url())));

		step(context, x, y + 20, 2, "Enter this code", signingIn ? Tone.GOOD : null);
		String c = code.userCode();
		int tile = 16, tileH = 22, tileGap = 3;
		int total = c.length() * (tile + tileGap) - tileGap;
		int tx = x + (w - total) / 2, ty = y + 34;
		float pulse = signingIn ? 0 : (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 300.0));
		for (int i = 0; i < c.length(); i++) {
			int cx = tx + i * (tile + tileGap);
			UiPaint.framed(context, cx, ty, tile, tileH, 3, 0xFF2A2312, UiPaint.mix(0xFF4A3C17, 0xFFB8921A, pulse * 0.6f));
			Text letter = Text.literal(String.valueOf(c.charAt(i))).styled(s -> s.withBold(true));
			var m = context.getMatrices();
			m.pushMatrix();
			float scale = 1.6f;
			m.translate(cx + (tile - textRenderer.getWidth(letter) * scale) / 2f, ty + (tileH - 8 * scale) / 2f + 1);
			m.scale(scale, scale);
			context.drawText(textRenderer, letter, 0, 0, 0xFFFFD45C, false);
			m.popMatrix();
		}
		boolean copied = System.currentTimeMillis() - copiedAt < 2500;
		ghost(context, mouseX, mouseY, x + (w - 64) / 2, ty + tileH + 4, 64, 14, copied ? "✔ Copied" : "Copy code", () -> {
			client.keyboard.setClipboard(c);
			copiedAt = System.currentTimeMillis();
		});

		int sy = ty + tileH + 24;
		step(context, x, sy, 3, signingIn ? codeProgress : "Sign in with the account that owns Minecraft", signingIn ? Tone.WORKING : null);

		// How long the code still works, as a draining bar.
		long left = code.secondsLeft();
		long totalSeconds = Math.max(1, (code.expiresAt() - codeShownAt) / 1000);
		int by = Math.min(bottom - 8, sy + 22);
		UiPaint.rounded(context, x, by, w, 3, 1, 0x30FFFFFF);
		UiPaint.rounded(context, x, by, (int) (w * Math.min(1, (double) left / totalSeconds)), 3, 1, left < 60 ? RED : YELLOW);
		String dots = ".".repeat((int) (System.currentTimeMillis() / 400 % 4));
		String wait = (signingIn ? "Almost there" : "Waiting for you") + dots;
		String time = left / 60 + ":" + String.format("%02d", left % 60) + " left";
		context.drawText(textRenderer, wait, x, by - 11, MUTED, false);
		context.drawText(textRenderer, time, x + w - textRenderer.getWidth(time), by - 11, FAINT, false);
	}

	/** One numbered step: grey circle, then a tick when it's done, or a spinner while it's happening. */
	private void step(DrawContext context, int x, int y, int number, String text, Tone state) {
		int color = state == Tone.GOOD ? GREEN : state == Tone.WORKING ? YELLOW : 0xFF3A4654;
		UiPaint.rounded(context, x, y - 2, 12, 12, 6, color);
		String mark = state == Tone.GOOD ? "✔" : String.valueOf(number);
		context.drawText(textRenderer, mark, x + 6 - textRenderer.getWidth(mark) / 2, y, state == null ? TEXT : INK, false);
		context.drawText(textRenderer, text, x + 17, y, state == Tone.GOOD ? MUTED : TEXT, false);
	}

	private void renderOffline(DrawContext context, int mouseX, int mouseY, int x, int y, int w) {
		int ly = y;
		for (OrderedText line : textRenderer.wrapLines(Text.literal("For single player and offline-mode servers. "
				+ "Online servers like Constantiam need a Microsoft account."), w)) {
			context.drawText(textRenderer, line, x, ly, MUTED, false);
			ly += 10;
		}
		ly += 8;
		String name = nameBox.getText();
		UiPaint.rounded(context, x - 1, ly - 1, 20, 20, 3, FAINT);
		PlayerSkinDrawer.draw(context, DefaultSkinHelper.getSkinTextures(Accounts.offlineUuid(name.isEmpty() ? "Steve" : name)), x, ly, 18);
		nameBox.setX(x + 26);
		nameBox.setY(ly);
		nameBox.setWidth(w - 26);
		boolean valid = name.length() >= 3;
		String rule = name.isEmpty() ? "3 to 16 letters, numbers or _" : valid ? "Looks good" : "At least 3 characters";
		context.drawText(textRenderer, rule, x + 26, ly + 23, name.isEmpty() ? FAINT : valid ? GREEN : RED, false);
		primary(context, mouseX, mouseY, x + w - 80, ly + 36, 80, 18, "Add & use", valid && !busy, this::addOffline);
	}

	private void renderFooter(DrawContext context, int mouseX, int mouseY, int x, int y, int w) {
		context.fill(x + 8, y, x + w - 8, y + 1, CARD_EDGE);
		int by = y + 7, bh = 17;
		if (view == View.LIST) {
			int first = (w - 20) * 57 / 100;
			primary(context, mouseX, mouseY, x + 8, by, first, bh, "+ Microsoft account", !busy, this::startMicrosoft);
			ghost(context, mouseX, mouseY, x + 12 + first, by, w - 20 - first, bh, "+ Offline name", () -> {
				if (!busy) show(View.OFFLINE);
			});
		} else {
			ghost(context, mouseX, mouseY, x + w - 78, by, 70, bh, view == View.CODE ? "Cancel" : "Back", this::backToList);
		}
	}

	/** Progress, success or a problem, as a pill floating over the bottom of the list card. Fades after a while. */
	private void renderToast(DrawContext context, Layout l) {
		if (toast.isEmpty()) return;
		long age = System.currentTimeMillis() - toastAt;
		long life = toastTone == Tone.BAD ? 9000 : toastTone == Tone.GOOD ? 3500 : Long.MAX_VALUE;
		if (age > life) {
			toast = "";
			return;
		}
		float alpha = Math.min(1, age / 150f) * (life == Long.MAX_VALUE ? 1 : Math.min(1, (life - age) / 400f));
		int maxW = l.listW() - 24;
		List<OrderedText> lines = textRenderer.wrapLines(Text.literal(toast), maxW - 24);
		if (lines.size() > 2) lines = lines.subList(0, 2);
		int textW = 0;
		for (OrderedText line : lines) textW = Math.max(textW, textRenderer.getWidth(line));
		int w = textW + 26, h = 8 + lines.size() * 10;
		int x = l.listX() + (l.listW() - w) / 2;
		int y = l.y() + l.h() - FOOTER - h - 6 + (int) ((1 - Math.min(1, age / 150f)) * 6);
		int color = toastTone == Tone.BAD ? RED : toastTone == Tone.GOOD ? GREEN : YELLOW;
		context.getMatrices().pushMatrix();
		UiPaint.shadow(context, x, y, w, h, 5, (int) (10 * alpha));
		UiPaint.framed(context, x, y, w, h, 5, UiPaint.fade(0xF0101820, alpha), UiPaint.fade(color, alpha));
		if (toastTone == Tone.WORKING) spinner(context, x + 10, y + h / 2, UiPaint.fade(color, alpha));
		else context.drawText(textRenderer, toastTone == Tone.GOOD ? "✔" : "✖", x + 6, y + h / 2 - 4, UiPaint.fade(color, alpha), false);
		int ty = y + 5;
		for (OrderedText line : lines) {
			context.drawText(textRenderer, line, x + 19, ty, UiPaint.fade(TEXT, alpha), false);
			ty += 10;
		}
		context.getMatrices().popMatrix();
	}

	// ---- What's shown on the left ----

	/** The hovered account while you point at one; the name being typed while adding an offline name; else you. */
	private Accounts.Account previewAccount() {
		if (previewHover != null && view == View.LIST) return previewHover;
		if (view == View.OFFLINE && nameBox != null && nameBox.getText().length() >= 3) {
			Accounts.Account typed = new Accounts.Account();
			typed.name = nameBox.getText();
			typed.offline = true;
			typed.uuid = Accounts.offlineUuid(typed.name).toString();
			return typed;
		}
		return Accounts.currentAccount();
	}

	private SkinTextures previewSkin() {
		return previewAccount().skin();
	}

	private static String kindLabel(Accounts.Account account) {
		return switch (account.kind()) {
			case MICROSOFT -> "Microsoft";
			case LAUNCHER -> "Microsoft · launcher";
			case OFFLINE -> "Offline";
		};
	}

	private static String subline(Accounts.Account account) {
		String kind = switch (account.kind()) {
			case MICROSOFT -> "Microsoft";
			case LAUNCHER -> "From the launcher";
			case OFFLINE -> "Offline · single player";
		};
		if (account.current()) return "Playing now · " + kind;
		return account.lastUsed > 0 ? kind + " · used " + ago(account.lastUsed) : kind;
	}

	private static String ago(long time) {
		long minutes = (System.currentTimeMillis() - time) / 60_000;
		if (minutes < 1) return "just now";
		if (minutes < 60) return minutes + " min ago";
		if (minutes < 48 * 60) return minutes / 60 + " h ago";
		return minutes / (24 * 60) + " days ago";
	}

	private boolean canUse(Accounts.Account account) {
		// On an online server an offline name can't join, so don't offer to rejoin as one.
		return !(Accounts.where() == Accounts.Where.SERVER && account.kind() == Accounts.Kind.OFFLINE);
	}

	// ---- Actions ----

	private void show(View v) {
		view = v;
		scroll = 0;
		confirmRemove = null;
		nameBox.visible = v == View.OFFLINE;
		if (v == View.OFFLINE) {
			nameBox.setText("");
			setFocused(nameBox);
		} else {
			setFocused(null);
		}
		if (v == View.LIST) {
			code = null;
			codeProgress = null;
		}
	}

	private void backToList() {
		cancelled = true;
		if (view == View.CODE) busy = false;
		if (toastTone == Tone.WORKING) toast = "";
		show(View.LIST);
	}

	private void say(String text, Tone tone) {
		toast = text == null ? "" : text;
		toastTone = tone;
		toastAt = System.currentTimeMillis();
	}

	private void startMicrosoft() {
		cancelled = false;
		busy = true;
		code = null;
		codeProgress = null;
		show(View.CODE);
		CompletableFuture.runAsync(() -> {
			try {
				MicrosoftLogin.DeviceCode c = MicrosoftLogin.requestCode();
				client.execute(() -> {
					if (cancelled) return;
					code = c;
					codeShownAt = System.currentTimeMillis();
					copiedAt = codeShownAt;
					client.keyboard.setClipboard(c.userCode());
					Util.getOperatingSystem().open(URI.create(c.url()));
				});
				MicrosoftLogin.Login login = MicrosoftLogin.awaitCode(c, s -> client.execute(() -> codeProgress = s), () -> cancelled);
				client.execute(() -> {
					Accounts.Account added = Accounts.rememberLogin(login);
					show(View.LIST);
					busy = false;
					use(added);
				});
			} catch (InterruptedException e) {
				client.execute(() -> busy = false);
			} catch (Exception e) {
				NaughtyOrNiceClient.LOGGER.warn("Microsoft sign-in failed: {}", e.getMessage());
				client.execute(() -> {
					busy = false;
					show(View.LIST);
					say("Sign-in didn't work: " + e.getMessage(), Tone.BAD);
				});
			}
		});
	}

	private void use(Accounts.Account account) {
		if (!canUse(account)) {
			say("An offline name can't join this server.", Tone.BAD);
			return;
		}
		busy = true;
		switching = account;
		say(account.kind() == Accounts.Kind.MICROSOFT ? "Signing in as " + account.name : "Switching to " + account.name, Tone.WORKING);
		Accounts.switchTo(account, s -> say(s, Tone.WORKING), error -> {
			busy = false;
			switching = null;
			if (error == null) say("Now playing as " + account.name, Tone.GOOD);
			else say(error, Tone.BAD);
		});
	}

	private void remove(Accounts.Account account) {
		Accounts.forget(account);
		confirmRemove = null;
		say("Removed " + account.name, Tone.GOOD);
	}

	private void addOffline() {
		String name = nameBox.getText();
		if (name.length() < 3 || busy) return;
		Accounts.Account account = Accounts.offline(name);
		show(View.LIST);
		use(account);
	}

	/** For the self-test: show the code step with a real code from Microsoft. */
	void showCodeForTest(MicrosoftLogin.DeviceCode testCode) {
		show(View.CODE);
		code = testCode;
		codeShownAt = System.currentTimeMillis();
	}

	/** For the self-test: show the offline-name step with a name typed in. */
	void showOfflineForTest(String name) {
		show(View.OFFLINE);
		nameBox.setText(name);
	}

	void showListForTest() {
		backToList();
	}

	int[] rowForTest(String name) {
		return rowCentres.get(name);
	}

	String previewNameForTest() {
		return previewAccount().name;
	}

	boolean heroForTest() {
		return layout().hero() && skinView.visible;
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() == 0) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				if (hits.get(i).contains(click.x(), click.y())) {
					client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1f));
					hits.get(i).run().run();
					return true;
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		scroll -= vertical * (ROW_H + ROW_GAP);
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (view == View.OFFLINE && (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			addOffline();
			return true;
		}
		if (view != View.LIST && input.key() == GLFW.GLFW_KEY_ESCAPE) {
			backToList();
			return true;
		}
		return super.keyPressed(input);
	}

	// ---- Pieces ----

	private void card(DrawContext context, int x, int y, int w, int h) {
		UiPaint.shadow(context, x, y, w, h, RADIUS, 14);
		UiPaint.rounded(context, x, y, w, h, RADIUS, CARD_EDGE);
		UiPaint.roundedGradient(context, x + 1, y + 1, w - 2, h - 2, RADIUS - 1, CARD_TOP, CARD_BOTTOM);
		context.fill(x + RADIUS, y + 1, x + w - RADIUS, y + 2, 0x14FFFFFF);
	}

	/** The yellow call-to-action button. */
	private void primary(DrawContext context, int mouseX, int mouseY, int x, int y, int w, int h, String label, boolean enabled, Runnable run) {
		boolean over = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		float hv = animate("p:" + label + x + "," + y, over);
		if (!enabled) {
			UiPaint.rounded(context, x, y, w, h, 4, GHOST);
			centered(context, label, x, y, w, h, FAINT);
			return;
		}
		UiPaint.rounded(context, x, y + 1, w, h, 4, YELLOW_SHADE);
		UiPaint.roundedGradient(context, x, y, w, h, 4, UiPaint.mix(YELLOW_TOP, 0xFFFFF0B0, hv), UiPaint.mix(YELLOW, 0xFFFFE24A, hv));
		centered(context, label, x, y, w, h, INK);
		hits.add(new Hit(x, y, x + w, y + h + 1, run));
	}

	/** A quiet button: dark, lighter under the mouse. */
	private void ghost(DrawContext context, int mouseX, int mouseY, int x, int y, int w, int h, String label, Runnable run) {
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		float hv = animate("g:" + label + x + "," + y, over);
		UiPaint.framed(context, x, y, w, h, 4, UiPaint.mix(GHOST, GHOST_HOVER, hv), UiPaint.mix(ROW_EDGE, ROW_EDGE_HOVER, hv));
		centered(context, label, x, y, w, h, TEXT);
		hits.add(new Hit(x, y, x + w, y + h, run));
	}

	/** A coloured button (e.g. red Remove?). */
	private void button(DrawContext context, int mouseX, int mouseY, int x, int y, int w, int h, String label, int color, int text, boolean enabled, Runnable run) {
		boolean over = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		UiPaint.rounded(context, x, y, w, h, 4, over ? color : UiPaint.mix(color, 0xFF000000, 0.2f));
		centered(context, label, x, y, w, h, text);
		if (enabled) hits.add(new Hit(x, y, x + w, y + h, run));
	}

	/** A small square button with a symbol, round-cornered under the mouse. */
	private void iconButton(DrawContext context, int mouseX, int mouseY, int x, int y, int w, int h, String icon, int color, Runnable run) {
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		float hv = animate("i:" + icon + x + "," + y, over);
		if (hv > 0.01f) UiPaint.rounded(context, x, y, w, h, 4, UiPaint.fade(GHOST_HOVER, hv));
		context.drawText(textRenderer, icon, x + (w - textRenderer.getWidth(icon)) / 2, y + (h - 8) / 2, over ? TEXT : color, false);
		hits.add(new Hit(x, y, x + w, y + h, run));
	}

	private int pillWidth(String text) {
		return textRenderer.getWidth(text) + 16;
	}

	/** A rounded label with a dot, centred on {@code cx}. */
	private void pill(DrawContext context, int cx, int y, String text, int color, boolean dot) {
		int w = pillWidth(text) - (dot ? 0 : 6);
		int x = cx - w / 2;
		UiPaint.rounded(context, x, y, w, 12, 6, UiPaint.fade(color, 0.16f));
		int tx = x + 6;
		if (dot) {
			UiPaint.rounded(context, tx, y + 4, 4, 4, 2, color);
			tx += 7;
		}
		context.drawText(textRenderer, text, tx, y + 2, color, false);
	}

	/** Eight dots going round. */
	private static void spinner(DrawContext context, int cx, int cy, int color) {
		double turn = (System.currentTimeMillis() % 960) / 960.0;
		for (int i = 0; i < 8; i++) {
			double angle = (i / 8.0 + turn) * Math.PI * 2;
			int dx = (int) Math.round(Math.cos(angle) * 4), dy = (int) Math.round(Math.sin(angle) * 4);
			context.fill(cx + dx - 1, cy + dy - 1, cx + dx + 1, cy + dy + 1, UiPaint.fade(color, (i + 1) / 8f));
		}
	}

	private void centered(DrawContext context, String label, int x, int y, int w, int h, int color) {
		Text text = Text.literal(fit(label, w - 6)).styled(s -> s.withBold(true));
		context.drawText(textRenderer, text, x + (w - textRenderer.getWidth(text)) / 2, y + (h - 8) / 2 + 1, color, false);
	}

	private float animate(Object key, boolean on) {
		float value = UiPaint.approach(hover.getOrDefault(key, 0f), on ? 1 : 0, frameMs);
		hover.put(key, value);
		return value;
	}

	private String fit(String s, int maxWidth) {
		if (textRenderer.getWidth(s) <= maxWidth) return s;
		return textRenderer.trimToWidth(s, Math.max(0, maxWidth - textRenderer.getWidth("…"))).stripTrailing() + "…";
	}
}
