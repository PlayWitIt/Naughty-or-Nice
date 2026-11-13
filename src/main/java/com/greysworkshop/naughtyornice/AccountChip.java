package com.greysworkshop.naughtyornice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/**
 * Who you're playing as, at the top left of the title, multiplayer and pause screens (under the PlayWit Hub button
 * where there is one, the same width): your skin's face, your name, and a dot for the kind of account (green
 * Microsoft, grey offline). Click it for the account screen.
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
	private boolean placed;
	/** Its width with everything shown. */
	private int full;

	AccountChip(Screen parent, int screenWidth) {
		super(MARGIN, MARGIN, 0, HEIGHT, Text.literal(MinecraftClient.getInstance().getSession().getUsername()));
		this.parent = parent;
		this.account = Accounts.currentAccount();
		full = PAD + FACE + 6 + UiFont.renderer().getWidth(name()) + 6 + 4 + 6 + 5 + 6;
		setWidth(full);
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

	/**
	 * Lines up under the PlayWit Hub button, if the screen has one, the two the same width when there's room. On the
	 * title screen the chip mustn't run into the Minecraft logo, so in a small window it stays narrower (the name cut
	 * short only if even the arrow and dot make no room; the full name is in the tooltip). Done on the first frame rather than when made: the hub
	 * may be added after the chip (by whichever PlayWit mod draws it).
	 */
	private void place() {
		placed = true;
		for (Element child : parent.children()) {
			if (child != this && child instanceof ClickableWidget hub && hub.getMessage().getString().equals(ModsPanel.NAME)) {
				setPosition(hub.getX(), hub.getY() + hub.getHeight() + 4);
				int hubWidth = hub instanceof ModsPanel.HubButton b ? b.naturalWidth() : hub.getWidth();
				// The logo is 256 wide, centred, from 30 down: keep 3 pixels clear of it.
				int room = parent instanceof TitleScreen ? parent.width / 2 - 128 - 3 - getX() : Integer.MAX_VALUE;
				int width = Math.max(full, hubWidth);
				if (width <= room) {
					if (hub instanceof ModsPanel.HubButton) hub.setWidth(width);
					full = width;
				}
				setWidth(Math.min(full, Math.max(room, PAD + FACE + 6 + 24)));
				return;
			}
		}
	}

	/** Where the chip ended up (it moves under the hub button on the first frame), for the self-test. */
	void placeForTest() {
		if (!placed) place();
	}


	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		if (!placed) place();
		long now = System.currentTimeMillis();
		float frame = lastFrame == 0 ? 0 : now - lastFrame;
		lastFrame = now;
		hover = UiPaint.approach(hover, isHovered() ? 1 : 0, frame);
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

		// The name, a status dot, and a chevron at the right edge (it opens something). Where the chip is kept narrow
		// the chevron goes first, then the dot (the ring says the same), and only then is the name cut short.
		int tx = fx + FACE + 6, nameWidth = font.getWidth(name());
		boolean chevron = tx + nameWidth + 6 + 4 + 6 + 5 + 6 <= x + w;
		boolean dot = tx + nameWidth + 6 + 4 + 6 <= x + w;
		int cx = x + w - 6 - 5, cy = y + h / 2 - 1, room = x + w - 6 - tx;
		Text name = name();
		if (nameWidth > room) {
			// Too long in bold: plain letters are narrower; cut short only if even those don't fit.
			name = Text.literal(account.name);
			String cut = account.name;
			while (font.getWidth(name) > room && !cut.isEmpty()) {
				cut = cut.substring(0, cut.length() - 1);
				name = Text.literal(cut + "…");
			}
		}
		context.drawText(font, name, tx, y + (h - 8) / 2 + 1, NAME, false);
		if (dot) UiPaint.rounded(context, tx + font.getWidth(name) + 6, y + h / 2 - 2, 4, 4, 2, online ? ONLINE : OFFLINE);
		if (!chevron) return;
		int color = UiPaint.mix(CHEVRON, BORDER_HOVER, hover);
		context.fill(cx, cy, cx + 5, cy + 1, color);
		context.fill(cx + 1, cy + 1, cx + 4, cy + 2, color);
		context.fill(cx + 2, cy + 2, cx + 3, cy + 3, color);
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
