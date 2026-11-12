package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/**
 * Who you're playing as, at the top right of the title, multiplayer and pause screens: your skin's face, your name, and
 * a dot for the kind of account (green Microsoft, grey offline). Click it for the account screen.
 */
final class AccountChip extends ClickableWidget {
	static final int HEIGHT = 20;
	private static final int FACE = 14, PAD = 3, MARGIN = 4;
	private static final int FILL = 0xC8111820, FILL_HOVER = 0xE01A2430, BORDER = 0x40FFFFFF, BORDER_HOVER = 0xFFFFD814,
			NAME = 0xFFF2F4F6, ONLINE = 0xFF4CD39B, OFFLINE = 0xFF9AA5B1, CHEVRON = 0xFF9AA5B1;

	private final Screen parent;
	private final Accounts.Account account;
	private float hover;
	private long lastFrame;

	AccountChip(Screen parent, int screenWidth) {
		super(0, MARGIN, 0, HEIGHT, Text.literal(MinecraftClient.getInstance().getSession().getUsername()));
		this.parent = parent;
		this.account = Accounts.currentAccount();
		TextRenderer font = UiFont.renderer();
		int width = PAD + FACE + 6 + font.getWidth(name()) + 8 + 5 + 6;
		setWidth(width);
		setX(screenWidth - width - MARGIN);
		setTooltip(Tooltip.of(Text.literal("Playing as " + account.name + " · " + kindLabel()
				+ "\nClick to see your skin and switch accounts")));
	}

	private Text name() {
		return Text.literal(account.name).styled(s -> s.withBold(true));
	}

	private String kindLabel() {
		return switch (account.kind()) {
			case MICROSOFT -> "Microsoft account";
			case LAUNCHER -> "Microsoft account, from the launcher";
			case OFFLINE -> "Offline name (single player only)";
		};
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		long now = System.currentTimeMillis();
		hover = UiPaint.approach(hover, isHovered() ? 1 : 0, lastFrame == 0 ? 0 : now - lastFrame);
		lastFrame = now;
		TextRenderer font = UiFont.renderer();
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		boolean online = account.kind() != Accounts.Kind.OFFLINE;

		UiPaint.shadow(context, x, y, w, h, 5, 6);
		UiPaint.framed(context, x, y, w, h, 5, UiPaint.mix(FILL, FILL_HOVER, hover), UiPaint.mix(BORDER, BORDER_HOVER, hover));
		context.fill(x + 4, y + 1, x + w - 4, y + 2, 0x18FFFFFF);

		// The face, on a ring in the account's colour.
		int fx = x + PAD, fy = y + (h - FACE) / 2;
		UiPaint.rounded(context, fx - 1, fy - 1, FACE + 2, FACE + 2, 2, online ? ONLINE : OFFLINE);
		PlayerSkinDrawer.draw(context, account.skin(), fx, fy, FACE);

		int tx = fx + FACE + 6;
		context.drawText(font, name(), tx, y + (h - 8) / 2 + 1, NAME, false);

		// A status dot and a little chevron: it opens something.
		int dx = tx + font.getWidth(name()) + 6;
		UiPaint.rounded(context, dx, y + h / 2 - 2, 4, 4, 2, online ? ONLINE : OFFLINE);
		int cx = dx + 8, cy = y + h / 2 - 1;
		context.fill(cx, cy, cx + 5, cy + 1, CHEVRON);
		context.fill(cx + 1, cy + 1, cx + 4, cy + 2, CHEVRON);
		context.fill(cx + 2, cy + 2, cx + 3, cy + 3, CHEVRON);
	}

	@Override
	public void onClick(Click click, boolean doubled) {
		MinecraftClient.getInstance().setScreen(new AccountsScreen(parent));
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		appendDefaultNarrations(builder);
	}
}
