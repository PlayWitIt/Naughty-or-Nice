package com.greysworkshop.naughtyornice;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The account switcher: your saved accounts on a card, the one you're playing as glowing green, Use on the others.
 * Adding a Microsoft account shows a code to type at microsoft.com/link (the page opens for you and the code is
 * copied), and waits; adding an offline name is for single player. Opened from the title and multiplayer screens.
 */
public final class AccountsScreen extends Screen {
	private enum View { LIST, CODE, OFFLINE }

	private static final int CARD = 0xF01A2129, EDGE = 0xFF2D3742, TEXT = 0xFFE7E9EC, MUTED = 0xFF9AA5B1, LINK = 0xFF5CC8E0,
			GOOD = 0xFF4CD39B, BAD = 0xFFFF7A66, YELLOW = 0xFFFFD814, YELLOW_TOP = 0xFFFFE07A, YELLOW_EDGE = 0xFFFCD200,
			SOFT = 0xFF25303B, SOFT_HOVER = 0xFF2E3A46, DARK_TEXT = 0xFF0F1111;

	private final Screen parent;
	private View view = View.LIST;
	private String status = "";
	private boolean statusBad;
	private boolean busy;
	private Accounts.DeviceCode code;
	private volatile boolean cancelled;
	private TextFieldWidget nameBox;
	private double scroll;
	private final List<Hit> hits = new ArrayList<>();

	private record Hit(int x0, int y0, int x1, int y1, Runnable run) {}

	public AccountsScreen(Screen parent) {
		super(Text.literal("Accounts"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		textRenderer = UiFont.renderer();
		nameBox = new TextFieldWidget(textRenderer, 0, 0, 140, 16, Text.literal("Name"));
		nameBox.setMaxLength(16);
		nameBox.setTextPredicate(s -> s.matches("[A-Za-z0-9_]*"));
		nameBox.visible = view == View.OFFLINE;
		addDrawableChild(nameBox);
	}

	@Override
	public void close() {
		cancelled = true;
		client.setScreen(parent);
	}

	private int cardW() {
		return Math.min(330, width - 16);
	}

	private int cardH() {
		return Math.min(230, height - 16);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		int w = cardW(), h = cardH();
		int x = (width - w) / 2, y = (height - h) / 2;
		// Shadow, card, glow along the top, highlight on its edge.
		rounded(context, x + 2, y + 3, w, h, 0x60000000);
		rounded(context, x, y, w, h, EDGE);
		rounded(context, x + 1, y + 1, w - 2, h - 2, CARD);
		context.fillGradient(x + 1, y + 1, x + w - 1, y + 40, 0x2A5CC8E0, 0x005CC8E0);
		context.fill(x + 3, y + 1, x + w - 3, y + 2, 0x20FFFFFF);

		context.drawText(textRenderer, Text.literal("Accounts").styled(s -> s.withBold(true)), x + 10, y + 9, TEXT, false);
		String playing = "Playing as " + client.getSession().getUsername();
		context.drawText(textRenderer, playing, x + w - 10 - textRenderer.getWidth(playing), y + 9, GOOD, false);
		context.fill(x + 10, y + 22, x + w - 10, y + 23, EDGE);

		int bottom = y + h - 26;
		switch (view) {
			case LIST -> renderList(context, mouseX, mouseY, x + 8, y + 28, w - 16, bottom - 4);
			case CODE -> renderCode(context, mouseX, mouseY, x + 10, y + 30, w - 20);
			case OFFLINE -> renderOffline(context, mouseX, mouseY, x + 10, y + 30, w - 20);
		}

		// Status line and the buttons along the bottom.
		if (!status.isEmpty()) {
			String shown = fit(status, w - 20);
			context.drawText(textRenderer, shown, x + 10, bottom - 12, statusBad ? BAD : busy ? LINK : GOOD, false);
		}
		int by = y + h - 21;
		if (view == View.LIST) {
			int bw = (w - 30) / 3;
			button(context, mouseX, mouseY, x + 10, by, bw, 15, "+ Microsoft", true, !busy, this::startMicrosoft);
			button(context, mouseX, mouseY, x + 15 + bw, by, bw, 15, "+ Offline name", false, !busy, () -> show(View.OFFLINE));
			button(context, mouseX, mouseY, x + 20 + 2 * bw, by, w - 30 - 2 * bw, 15, "Done", false, true, this::close);
		} else {
			button(context, mouseX, mouseY, x + w - 90, by, 80, 15, "Back", false, true, () -> {
				cancelled = true;
				busy = false;
				show(View.LIST);
			});
		}
		super.render(context, mouseX, mouseY, delta);
	}

	private void renderList(DrawContext context, int mouseX, int mouseY, int x, int top, int w, int bottom) {
		List<Accounts.Account> accounts = new ArrayList<>(Accounts.all());
		if (accounts.isEmpty()) {
			int ty = top + 14;
			for (var line : textRenderer.wrapLines(Text.literal("No saved accounts yet. Add your Microsoft accounts here once each; "
					+ "then switch between them with one click, right here, any time you're not in a world."), w - 8)) {
				context.drawText(textRenderer, line, x + 4, ty, MUTED, false);
				ty += 10;
			}
			return;
		}
		int rowH = 24;
		int maxScroll = Math.max(0, accounts.size() * (rowH + 2) - (bottom - top - 14));
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		context.enableScissor(x, top, x + w, bottom - 14);
		int ry = top - (int) scroll;
		for (Accounts.Account a : accounts) {
			boolean now = Accounts.current(a);
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowH && mouseY >= top && mouseY < bottom - 14;
			int accent = now ? GOOD : a.offline ? MUTED : LINK;
			if (now) {
				hGradient(context, x, ry, w, rowH, 0x704CD39B, 0x004CD39B);
				context.fill(x, ry + 2, x + 2, ry + rowH - 2, GOOD);
			} else if (hover) {
				hGradient(context, x, ry, w, rowH, 0x405CC8E0, 0x005CC8E0);
			}
			UUID id;
			try {
				id = UUID.fromString(a.uuid);
			} catch (RuntimeException e) {
				id = UUID.nameUUIDFromBytes(a.name.getBytes());
			}
			rounded(context, x + 5, ry + 3, 18, 18, 0xFF000000 | (accent & 0xFFFFFF));
			PlayerSkinDrawer.draw(context, DefaultSkinHelper.getSkinTextures(id), x + 6, ry + 4, 16);
			context.drawText(textRenderer, Text.literal(a.name).styled(s -> s.withBold(true)), x + 28, ry + 4, TEXT, false);
			context.drawText(textRenderer, now ? "● Playing now" : a.offline ? "Offline name" : "Microsoft account", x + 28, ry + 14,
					now ? GOOD : MUTED, false);
			int bx = x + w - 18;
			Accounts.Account account = a;
			smallButton(context, mouseX, mouseY, bx, ry + 5, 14, 14, "✖", BAD, !busy, () -> {
				Accounts.forget(account);
				say("Removed " + account.name + ".", false);
			});
			if (!now) {
				bx -= 42;
				button(context, mouseX, mouseY, bx, ry + 5, 38, 14, "Use", true, !busy, () -> use(account));
			}
			ry += rowH + 2;
		}
		context.disableScissor();
	}

	private void renderCode(DrawContext context, int mouseX, int mouseY, int x, int y, int w) {
		if (code == null) {
			context.drawText(textRenderer, "Asking Microsoft for a code…", x, y + 4, MUTED, false);
			return;
		}
		context.drawText(textRenderer, Text.literal("1.").styled(s -> s.withBold(true)), x, y + 4, LINK, false);
		context.drawText(textRenderer, "Go to microsoft.com/link (it opened for you)", x + 12, y + 4, TEXT, false);
		button(context, mouseX, mouseY, x + 12, y + 16, 90, 14, "Open the page", false, true, () -> Util.getOperatingSystem().open(URI.create(code.url())));
		context.drawText(textRenderer, Text.literal("2.").styled(s -> s.withBold(true)), x, y + 40, LINK, false);
		context.drawText(textRenderer, "Type this code and sign in:", x + 12, y + 40, TEXT, false);
		// The code, big, on its own tile.
		String c = code.userCode();
		float scale = 2.5f;
		int cw = (int) (textRenderer.getWidth(c) * scale) + 20;
		int cx = x + (w - cw) / 2, cy = y + 54;
		rounded(context, cx, cy, cw, 32, 0xFF3D3015);
		rounded(context, cx + 1, cy + 1, cw - 2, 30, 0xFF2A2210);
		var m = context.getMatrices();
		m.pushMatrix();
		m.translate(cx + 10, cy + 6);
		m.scale(scale, scale);
		context.drawText(textRenderer, Text.literal(c).styled(s -> s.withBold(true)), 0, 0, 0xFFFFC14D, true);
		m.popMatrix();
		button(context, mouseX, mouseY, x + (w - 70) / 2, cy + 36, 70, 14, "Copy code", false, true, () -> {
			client.keyboard.setClipboard(c);
			say("Code copied. Waiting for you to sign in…", false);
			busy = true;
		});
		long left = Math.max(0, (code.expiresAt() - System.currentTimeMillis()) / 1000);
		String dots = ".".repeat((int) (System.currentTimeMillis() / 400 % 4));
		String wait = "Waiting for you to sign in" + dots + "  (code works " + left / 60 + ":" + String.format("%02d", left % 60) + " more)";
		context.drawText(textRenderer, wait, x + (w - textRenderer.getWidth(wait)) / 2, cy + 56, MUTED, false);
	}

	private void renderOffline(DrawContext context, int mouseX, int mouseY, int x, int y, int w) {
		for (var line : textRenderer.wrapLines(Text.literal("An offline name works in single player and on offline-mode servers only. "
				+ "Online servers (like Constantiam) need a Microsoft account."), w)) {
			context.drawText(textRenderer, line, x, y + 2, MUTED, false);
			y += 10;
		}
		context.drawText(textRenderer, "Name:", x, y + 12, TEXT, false);
		nameBox.setX(x + 36);
		nameBox.setY(y + 8);
		nameBox.setWidth(Math.min(140, w - 36 - 80));
		button(context, mouseX, mouseY, x + 36 + nameBox.getWidth() + 6, y + 9, 70, 14, "Add & use", true, nameBox.getText().length() >= 3,
				this::addOffline);
	}

	// ---- Actions ----

	private void show(View v) {
		view = v;
		if (nameBox != null) {
			nameBox.visible = v == View.OFFLINE;
			if (v == View.OFFLINE) {
				nameBox.setText("");
				setFocused(nameBox);
			}
		}
		if (v == View.LIST) code = null;
	}

	private void say(String text, boolean bad) {
		status = text == null ? "" : text;
		statusBad = bad;
	}

	private void startMicrosoft() {
		cancelled = false;
		busy = true;
		code = null;
		show(View.CODE);
		say("Asking Microsoft for a code…", false);
		CompletableFuture.runAsync(() -> {
			try {
				Accounts.DeviceCode c = Accounts.startSignIn();
				client.execute(() -> {
					code = c;
					client.keyboard.setClipboard(c.userCode());
					Util.getOperatingSystem().open(URI.create(c.url()));
					say("Code copied and the page opened. Waiting for you to sign in…", false);
				});
				Accounts.Account a = Accounts.finishSignIn(c, s -> client.execute(() -> say(s, false)), () -> cancelled);
				client.execute(() -> {
					show(View.LIST);
					say("Added " + a.name + ". Switching to it…", false);
					use(a);
				});
			} catch (InterruptedException e) {
				client.execute(() -> busy = false);
			} catch (Exception e) {
				NaughtyOrNiceClient.LOGGER.warn("Microsoft sign-in failed: {}", e.getMessage());
				client.execute(() -> {
					busy = false;
					show(View.LIST);
					say("Sign-in didn't work: " + e.getMessage(), true);
				});
			}
		});
	}

	private void use(Accounts.Account account) {
		if (client.world != null) {
			say("Leave the world first: you can only switch from the menus.", true);
			return;
		}
		busy = true;
		say(account.offline ? "Switching to " + account.name + "…" : "Signing in as " + account.name + "…", false);
		Accounts.use(account, s -> client.execute(() -> say(s, false)), error -> {
			busy = false;
			if (error == null) say("Now playing as " + account.name + ".", false);
			else say(error, true);
		});
	}

	private void addOffline() {
		String name = nameBox.getText();
		if (name.length() < 3) return;
		Accounts.Account a = Accounts.offline(name);
		show(View.LIST);
		use(a);
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() == 0) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit h = hits.get(i);
				if (click.x() >= h.x0 && click.x() < h.x1 && click.y() >= h.y0 && click.y() < h.y1) {
					client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1f));
					h.run.run();
					return true;
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		scroll -= vertical * 13;
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (view == View.OFFLINE && (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			addOffline();
			return true;
		}
		return super.keyPressed(input);
	}

	// ---- Drawing ----

	private void button(DrawContext context, int mouseX, int mouseY, int x, int y, int w, int h, String label, boolean primary, boolean enabled, Runnable run) {
		boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		int lift = hover ? 1 : 0;
		int top = !enabled ? SOFT : primary ? YELLOW_TOP : SOFT_HOVER, bottom = !enabled ? SOFT : primary ? YELLOW_EDGE : SOFT;
		rounded(context, x, y + 1, w, h, primary && enabled ? 0xFFB89A00 : EDGE);
		rounded(context, x, y - lift, w, h, bottom);
		context.fillGradient(x + 1, y - lift + 1, x + w - 1, y - lift + h - 1, top, bottom);
		context.fill(x + 2, y - lift + 1, x + w - 2, y - lift + 2, 0x30FFFFFF);
		Text t = Text.literal(fit(label, w - 6)).styled(s -> s.withBold(true));
		context.drawText(textRenderer, t, x + (w - textRenderer.getWidth(t)) / 2, y - lift + (h - 8) / 2 + 1,
				!enabled ? MUTED : primary ? DARK_TEXT : TEXT, false);
		if (enabled) hits.add(new Hit(x, y - 1, x + w, y + h + 1, run));
	}

	private void smallButton(DrawContext context, int mouseX, int mouseY, int x, int y, int w, int h, String label, int color, boolean enabled, Runnable run) {
		boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		rounded(context, x, y, w, h, hover ? (0x60000000 | (color & 0xFFFFFF)) : SOFT);
		context.drawText(textRenderer, label, x + (w - textRenderer.getWidth(label)) / 2 + 1, y + (h - 8) / 2 + 1, hover ? TEXT : color, false);
		if (enabled) hits.add(new Hit(x, y, x + w, y + h, run));
	}

	private String fit(String s, int maxWidth) {
		if (textRenderer.getWidth(s) <= maxWidth) return s;
		return textRenderer.trimToWidth(s, Math.max(0, maxWidth - textRenderer.getWidth("…"))).stripTrailing() + "…";
	}

	private static void rounded(DrawContext context, int x, int y, int w, int h, int color) {
		context.fill(x + 2, y, x + w - 2, y + h, color);
		context.fill(x + 1, y + 1, x + w - 1, y + h - 1, color);
		context.fill(x, y + 2, x + w, y + h - 2, color);
	}

	private static void hGradient(DrawContext context, int x, int y, int w, int h, int from, int to) {
		for (int i = 0; i < w; i += 2) {
			float t = (float) i / Math.max(1, w - 1);
			int a = Math.round((from >>> 24) * (1 - t) + (to >>> 24) * t);
			int r = Math.round((from >> 16 & 0xFF) * (1 - t) + (to >> 16 & 0xFF) * t);
			int g = Math.round((from >> 8 & 0xFF) * (1 - t) + (to >> 8 & 0xFF) * t);
			int b = Math.round((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
			context.fill(x + i, y, x + Math.min(w, i + 2), y + h, a << 24 | r << 16 | g << 8 | b);
		}
	}
}
