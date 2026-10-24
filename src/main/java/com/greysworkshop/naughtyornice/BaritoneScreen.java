package com.greysworkshop.naughtyornice;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Baritone with buttons and boxes instead of commands: go to a place (or a trail's end, or a place someone gave away),
 * follow a player, mine, explore, farm, fly; roam a highway (getting through damaged ones); and Baritone's main
 * settings as switches. Opened with B or /nn baritone.
 */
public class BaritoneScreen extends Screen {
	private enum Page { PLACES, GO, HIGHWAY, SETTINGS, COMMANDS }

	private static Page page = Page.PLACES;
	/** Go to…: the search, which kinds are shown, how far the list is scrolled. */
	private TextFieldWidget searchField, waypointField;
	private static Destinations.Kind kindFilter;
	private double listScroll;
	/** Rows drawn this frame, for clicks: x0, y0, x1, y1, what a click does, the place's name, and whether it's the ×. */
	private final java.util.List<Object[]> rowHits = new java.util.ArrayList<>();
	private TextFieldWidget xField, yField, zField, playerField, blockField, heightField, distanceField;
	private String status = "";
	private int statusColor = 0xFFAAAAAA;
	/**
	 * The job last handed to Baritone, watched while this screen is open: when Baritone stops it (done, or given up:
	 * nothing to farm, nobody to follow), the status says so rather than still saying it's working.
	 */
	private String watchProcess, watchFailed;

	/** Every command: the search, the one picked, what to add after it, and what Baritone suggests for that. */
	private TextFieldWidget commandSearch, argsField;
	private static String pickedCommand;
	private List<BaritoneBridge.Command> commands = List.of();
	private String suggestedFor;
	private List<String> suggestions = List.of();
	/** Settings: the search, the one picked (to type a value for), and Baritone's settings as last read. */
	private TextFieldWidget settingSearch, valueField;
	private static String pickedSetting;
	private List<BaritoneBridge.Setting> settings = List.of();
	private long settingsReadAt;

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
	protected void init() {
		int x = width / 2 - 200;
		int y = 6;
		for (Page p : Page.values()) {
			String label = switch (p) {
				case PLACES -> "Go to…";
				case GO -> "Do";
				case HIGHWAY -> "Highway roam";
				case SETTINGS -> "Settings";
				case COMMANDS -> "Every command";
			};
			addDrawableChild(ButtonWidget.builder(Text.literal((p == page ? "› " : "") + label), b -> {
				page = p;
				listScroll = 0;
				clearAndInit();
			}).dimensions(x, y, 77, 18).build());
			x += 80;
		}
		x = width / 2 - 200;
		y = 46;
		if (!BaritoneBridge.present()) return;
		switch (page) {
			case PLACES -> initPlaces(x, y);
			case GO -> initGo(x, y);
			case HIGHWAY -> initHighway(x, y);
			case SETTINGS -> initSettings(x, y);
			case COMMANDS -> initCommands(x, y);
		}
	}

	private TextFieldWidget field(int x, int y, int w, String hint, String value) {
		TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 18, Text.literal(hint));
		f.setPlaceholder(Text.literal(hint).withColor(0xFF777777));
		f.setText(value == null ? "" : value);
		addDrawableChild(f);
		return f;
	}

	private void button(int x, int y, int w, String label, Runnable run) {
		addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> run.run()).dimensions(x, y, w, 18).build());
	}

	private void check(int x, int y, String label, Supplier<Boolean> get, Consumer<Boolean> set) {
		Boolean on = get.get();
		addDrawableChild(CheckboxWidget.builder(Text.literal(label), textRenderer).pos(x, y).checked(on != null && on)
				.callback((box, checked) -> set.accept(checked)).build());
	}

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
		status = ok ? said : "Baritone didn't take that (see its message in chat)";
		statusColor = ok ? 0xFF7CE0A0 : 0xFFFF8A80;
	}

	/** Runs a command, then checks a moment later that Baritone's {@code process} really is at it. */
	private void runWatched(String command, String said, String process, String failed) {
		run(command, said);
		if (!status.equals(said)) return;
		watchProcess = process;
		watchFailed = failed;
	}

	// ---- Go ----

	private void initGo(int x, int y) {
		var p = client.player;
		xField = field(x + 12, y, 70, "x", xField == null ? "" : xField.getText());
		yField = field(x + 100, y, 46, "y (optional)", yField == null ? "" : yField.getText());
		zField = field(x + 164, y, 70, "z", zField == null ? "" : zField.getText());
		button(x + 240, y, 50, "Go", this::goTyped);
		button(x + 294, y, 106, "Use my position", () -> {
			if (p == null) return;
			xField.setText(String.valueOf(p.getBlockX()));
			yField.setText(String.valueOf(p.getBlockY()));
			zField.setText(String.valueOf(p.getBlockZ()));
		});
		y += 24;
		// The nether calculator: one nether block is eight overworld blocks across (y stays as it is).
		button(x, y, 198, "Overworld → Nether (÷8)", () -> convert(true));
		button(x + 202, y, 198, "Nether → Overworld (×8)", () -> convert(false));
		y += 24;
		button(x, y, 132, "Latest trail's far end", () -> {
			List<ChunkTrails.Trail> trails = NaughtyOrNiceClient.INSTANCE.ledger.chunkTrails.trails;
			if (trails.isEmpty()) {
				status = "No chunk trails found yet";
				statusColor = 0xFFFFC14D;
				return;
			}
			ChunkTrails.Trail t = trails.stream().max(java.util.Comparator.comparingLong(tr -> tr.lastSeen)).orElseThrow();
			int[] end = t.awayEnd();
			fill(end[0], null, end[1]);
		});
		button(x + 136, y, 132, "Latest place given away", () -> {
			PlayerStats.Place best = null;
			for (Profile pr : NaughtyOrNiceClient.INSTANCE.ledger.all()) {
				for (PlayerStats.Place pl : pr.stats.places) {
					if (best == null || pl.time > best.time) best = pl;
				}
			}
			if (best == null) {
				status = "Nobody has given a place away yet";
				statusColor = 0xFFFFC14D;
				return;
			}
			fill(best.x, best.hasY() ? best.y : null, best.z);
		});
		button(x + 272, y, 128, "Paste coordinates", () -> {
			List<ChatIntel.Coord> coords = ChatIntel.coords(client.keyboard.getClipboard());
			if (coords.isEmpty()) {
				status = "No coordinates on the clipboard";
				statusColor = 0xFFFFC14D;
				return;
			}
			ChatIntel.Coord c = coords.get(0);
			fill(c.x(), c.hasY() ? c.y() : null, c.z());
		});
		y += 30;
		playerField = field(x + 46, y, 190, "player name", playerField == null ? "" : playerField.getText());
		button(x + 240, y, 160, "Follow them", () -> {
			String name = playerField.getText().strip();
			if (!name.isEmpty()) runWatched("follow player " + name, "Following " + name, "getFollowProcess",
					"Stopped following " + name + " (out of sight, or see Baritone's message in chat)");
		});
		y += 24;
		blockField = field(x + 46, y, 190, "block, e.g. diamond_ore", blockField == null ? "" : blockField.getText());
		button(x + 240, y, 160, "Mine it", () -> {
			String block = blockField.getText().strip().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
			if (!block.isEmpty()) runWatched("mine " + block, "Mining " + block, "getMineProcess",
					"Stopped mining " + block + " (see Baritone's message in chat)");
		});
		y += 30;
		button(x, y, 96, "Explore", () -> runWatched("explore", "Exploring from here", "getExploreProcess",
				"Stopped exploring (see Baritone's message in chat)"));
		button(x + 100, y, 96, "Farm nearby", () -> runWatched("farm", "Farming nearby", "getFarmProcess",
				"Farming stopped: nothing (more) to harvest or plant nearby"));
		button(x + 200, y, 200, "Fly there by elytra (nether)", () -> {
			Integer[] at = typed();
			if (at == null) return;
			if (client.world == null || client.world.getRegistryKey() != net.minecraft.world.World.NETHER) {
				watchProcess = null;
				status = "Elytra flying only works in the nether";
				statusColor = 0xFFFF8A80;
				return;
			}
			if (BaritoneBridge.run("goal " + at[0] + " " + at[2])) {
				runWatched("elytra", "Flying to " + at[0] + ", " + at[2], "getElytraProcess",
						"Flying stopped (there, or see Baritone's message in chat)");
			}
		});
		y += 24;
		button(x, y, 130, "Stop", () -> {
			HighwayRoam.stop(null);
			SmartTravel.stop();
			run("stop", "Stopped");
		});
		button(x + 135, y, 130, "Pause", () -> run("pause", "Paused"));
		button(x + 270, y, 130, "Resume", () -> run("resume", "Going again"));
		y += 24;
		button(x, y, 400, WorldDownload.active() ? "Stop downloading (" + WorldDownload.chunks() + " chunks saved)" : "Download the land I load into a singleplayer world", () -> {
			if (WorldDownload.active()) {
				WorldDownload.stop(client);
			} else {
				String why = WorldDownload.start(client);
				status = why == null ? "Downloading: every chunk you load is saved" : why;
				statusColor = why == null ? 0xFF7CE0A0 : 0xFFFF8A80;
			}
			clearAndInit();
		});
	}

	/** Turns the typed x and z into the matching spot in the nether (or back), in the boxes, ready for Go. */
	private void convert(boolean toNether) {
		Integer[] at = typed();
		if (at == null) return;
		int x = toNether ? Math.floorDiv(at[0], 8) : at[0] * 8, z = toNether ? Math.floorDiv(at[2], 8) : at[2] * 8;
		xField.setText(String.valueOf(x));
		zField.setText(String.valueOf(z));
		status = (toNether ? "Nether " : "Overworld ") + x + ", " + z + " (from " + (toNether ? "overworld " : "nether ") + at[0] + ", " + at[2] + ")";
		statusColor = 0xFFDDDDDD;
	}

	private void fill(int x, Integer y, int z) {
		xField.setText(String.valueOf(x));
		yField.setText(y == null ? "" : String.valueOf(y));
		zField.setText(String.valueOf(z));
		status = "Filled in " + x + ", " + (y == null ? "" : y + ", ") + z + ": press Go";
		statusColor = 0xFFDDDDDD;
	}

	/** The typed x, y (or null), z; null (with a message) if x or z aren't numbers. */
	private Integer[] typed() {
		try {
			int x = Integer.parseInt(xField.getText().strip());
			int z = Integer.parseInt(zField.getText().strip());
			String ys = yField.getText().strip();
			return new Integer[] {x, ys.isEmpty() ? null : Integer.parseInt(ys), z};
		} catch (NumberFormatException e) {
			status = "Type whole numbers for x and z (y can be empty)";
			statusColor = 0xFFFF8A80;
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
		status = said;
		statusColor = ok ? 0xFF7CE0A0 : 0xFFFF8A80;
		// Back to the game to watch it go, not to whatever screen opened this one.
		if (ok) client.setScreen(null);
	}

	// ---- Go to… ----

	private void initPlaces(int x, int y) {
		searchField = field(x, y, 190, "Search places", searchField == null ? "" : searchField.getText());
		searchField.setChangedListener(t -> listScroll = 0);
		String shown = kindFilter == null ? "Show: everything" : "Show: " + kindFilter.label;
		button(x + 196, y, 204, shown + " ▸", () -> {
			Destinations.Kind[] kinds = Destinations.Kind.values();
			kindFilter = kindFilter == null ? kinds[0] : kindFilter.ordinal() + 1 < kinds.length ? kinds[kindFilter.ordinal() + 1] : null;
			listScroll = 0;
			clearAndInit();
		});
		y += 22;
		waypointField = field(x, y, 190, "Name this spot, e.g. Home", waypointField == null ? "" : waypointField.getText());
		button(x + 196, y, 204, "Save where I am", () -> {
			Destinations.Waypoint w = Destinations.addHere(client, waypointField.getText());
			if (w == null) {
				status = "Type a name for this spot first";
				statusColor = 0xFFFFC14D;
				return;
			}
			status = "Saved \"" + w.name + "\" at " + w.x + ", " + w.y + ", " + w.z;
			statusColor = 0xFF7CE0A0;
			waypointField.setText("");
		});
	}

	/** The list on Go to…, drawn by hand: one row per place, with its distance, direction and a Go button. */
	private void renderPlaces(DrawContext context, int mouseX, int mouseY) {
		rowHits.clear();
		int x = width / 2 - 200, top = 92, bottom = height - 20, w = 400, rowH = 22;
		String q = searchField == null ? "" : searchField.getText().strip().toLowerCase(java.util.Locale.ROOT);
		java.util.List<Destinations.Destination> all = Destinations.all(client);
		all.removeIf(d -> kindFilter != null && d.kind() != kindFilter
				|| !q.isEmpty() && !d.name().toLowerCase(java.util.Locale.ROOT).contains(q) && !d.detail().toLowerCase(java.util.Locale.ROOT).contains(q));
		if (all.isEmpty()) {
			context.drawTextWithShadow(textRenderer, q.isEmpty() ? "Nothing here yet. Save a spot above, or play a while: trails, places players give away and"
					: "Nothing matches.", x, top + 4, 0xFFAAAAAA);
			if (q.isEmpty()) context.drawTextWithShadow(textRenderer, "the last places your list's players were seen all show up here.", x, top + 16, 0xFFAAAAAA);
			return;
		}
		int maxScroll = Math.max(0, all.size() * rowH - (bottom - top));
		listScroll = Math.max(0, Math.min(listScroll, maxScroll));
		String here = client.world == null ? "" : client.world.getRegistryKey().getValue().toString();
		context.enableScissor(x, top, x + w, bottom);
		int y = top - (int) listScroll;
		for (Destinations.Destination d : all) {
			if (y + rowH >= top && y <= bottom) {
				boolean hover = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + rowH - 2, bottom);
				context.fill(x, y, x + w, y + rowH - 2, hover ? 0x60FFFFFF : 0x40000000);
				int color = switch (d.kind()) {
					case WAYPOINT -> 0xFF8AB4FF;
					case BASE -> 0xFF4CD39B;
					case TRAIL -> 0xFFFFB020;
					case PLACE -> 0xFFFF7A1A;
					case SEEN -> 0xFFFFC14D;
					case EVENT -> 0xFFB070FF;
					case CLIPBOARD -> 0xFFDDDDDD;
				};
				context.fill(x, y, x + 3, y + rowH - 2, color);
				// Both: its overworld and its nether coordinates (one nether block is eight overworld ones), each with a Go.
				boolean end = d.dimension().endsWith("the_end"), inNether = d.dimension().endsWith("the_nether");
				int ox = inNether ? d.x() * 8 : d.x(), oz = inNether ? d.z() * 8 : d.z();
				int nx = inNether ? d.x() : Math.floorDiv(d.x(), 8), nz = inNether ? d.z() : Math.floorDiv(d.z(), 8);
				Destinations.Destination overworld = new Destinations.Destination(d.kind(), d.name(), d.detail(), ox, inNether ? Integer.MIN_VALUE : d.y(), oz,
						"minecraft:overworld", d.time());
				Destinations.Destination nether = new Destinations.Destination(d.kind(), d.name(), d.detail(), nx, inNether ? d.y() : Integer.MIN_VALUE, nz,
						"minecraft:the_nether", d.time());
				String name = textRenderer.trimToWidth(d.name(), 170);
				context.drawTextWithShadow(textRenderer, name, x + 7, y + 2, 0xFFFFFFFF);
				context.drawText(textRenderer, textRenderer.trimToWidth(" · " + d.kind().label + " · " + d.detail(), 290 - 7 - textRenderer.getWidth(name)),
						x + 7 + textRenderer.getWidth(name), y + 2, 0xFF999999, false);
				String coords = end ? String.format(java.util.Locale.ROOT, "End %,d %,d", d.x(), d.z())
						: String.format(java.util.Locale.ROOT, "OW %,d %,d   Nether %,d %,d", ox, oz, nx, nz);
				context.drawText(textRenderer, coords, x + 7, y + 11, 0xFFBBBBBB, false);
				// How far, and which way, from where you are.
				String where = null;
				if (client.player != null) {
					boolean hereNether = here.endsWith("the_nether"), hereEnd = here.endsWith("the_end");
					if (end == hereEnd) {
						double dx = (end ? d.x() : hereNether ? nx : ox) - client.player.getX(), dz = (end ? d.z() : hereNether ? nz : oz) - client.player.getZ();
						where = String.format(java.util.Locale.ROOT, "%,d %s", (int) Math.hypot(dx, dz), ChunkTrails.compass(dx, dz));
					} else {
						where = ShopScreen.dimensionName(d.dimension());
					}
				}
				int right = x + w - 18;
				if (end) {
					goButton(context, mouseX, mouseY, right - 34, y, top, bottom, rowH, "Go", d, d.name());
					right -= 34;
				} else {
					goButton(context, mouseX, mouseY, right - 44, y, top, bottom, rowH, "Nether", nether, d.name() + " (nether)");
					goButton(context, mouseX, mouseY, right - 44 - 3 - 30, y, top, bottom, rowH, "OW", overworld, d.name());
					right -= 44 + 3 + 30;
				}
				if (where != null) context.drawText(textRenderer, where, right - 4 - textRenderer.getWidth(where), y + 11, 0xFFDDDDDD, false);
				if (d.kind() == Destinations.Kind.WAYPOINT) {
					int rx = x + w - 16;
					context.drawTextWithShadow(textRenderer, "×", rx + 3, y + 6, mouseX >= rx && mouseX < rx + 14 && mouseY >= y && mouseY < y + rowH ? 0xFFFF8080 : 0xFF999999);
					rowHits.add(new Object[] {rx, Math.max(y, top), rx + 14, Math.min(y + rowH, bottom), (Runnable) () -> {
						Destinations.remove(d.name());
						status = "Removed \"" + d.name() + "\"";
						statusColor = 0xFFDDDDDD;
					}, d.name(), true});
				}
			}
			y += rowH;
		}
		context.disableScissor();
		if (maxScroll > 0) {
			int bar = Math.max(12, (bottom - top) * (bottom - top) / (all.size() * rowH));
			int by = top + (int) ((bottom - top - bar) * (listScroll / maxScroll));
			context.fill(x + w + 3, by, x + w + 5, by + bar, 0xFF888888);
		}
	}

	/** A Go button on a place's row; {@code key} is what the self-test clicks it by. */
	private void goButton(DrawContext context, int mouseX, int mouseY, int gx, int y, int top, int bottom, int rowH, String label, Destinations.Destination to, String key) {
		int gw = label.equals("Nether") ? 44 : label.equals("OW") ? 30 : 34;
		boolean over = mouseX >= gx && mouseX < gx + gw && mouseY >= y + 2 && mouseY < y + rowH - 4;
		int base = label.equals("Nether") ? 0xFF8F2B2B : 0xFF2E6BBF, lit = label.equals("Nether") ? 0xFFC0453F : 0xFF4CA0FF;
		context.fill(gx, y + 2, gx + gw, y + rowH - 4, over ? lit : base);
		context.drawTextWithShadow(textRenderer, label, gx + gw / 2 - textRenderer.getWidth(label) / 2, y + 6, 0xFFFFFFFF);
		rowHits.add(new Object[] {gx, Math.max(y + 2, top), gx + gw, Math.min(y + rowH - 4, bottom), (Runnable) () -> go(to), key, false});
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		if (page == Page.PLACES || page == Page.SETTINGS || page == Page.COMMANDS) {
			for (Object[] hit : java.util.List.copyOf(rowHits)) {
				if (click.x() >= (int) hit[0] && click.x() < (int) hit[2] && click.y() >= (int) hit[1] && click.y() < (int) hit[3]) {
					((Runnable) hit[4]).run();
					return true;
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (page == Page.PLACES || page == Page.SETTINGS || page == Page.COMMANDS) {
			listScroll -= verticalAmount * 22;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	// ---- Highway ----

	private void initHighway(int x, int y) {
		int bx = x;
		for (HighwayRoam.Axis a : HighwayRoam.AXES) {
			boolean on = a == HighwayRoam.axis;
			button(bx, y, 46, (on ? "›" : "") + a.name(), () -> {
				HighwayRoam.axis = a;
				clearAndInit();
			});
			bx += 50;
		}
		y += 24;
		button(x, y, 96, (HighwayRoam.elytra ? "Elytra" : "Walk") + " ▸", () -> {
			HighwayRoam.elytra = !HighwayRoam.elytra;
			clearAndInit();
		});
		heightField = field(x + 150, y, 60, "here", HighwayRoam.height == Integer.MIN_VALUE ? "" : String.valueOf(HighwayRoam.height));
		distanceField = field(x + 290, y, 110, "blocks", String.valueOf(HighwayRoam.distance));
		y += 26;
		check(x, y, "Break through blocked parts", () -> HighwayRoam.breakThrough, v -> HighwayRoam.breakThrough = v);
		check(x + 205, y, "Bridge gaps and missing floor", () -> HighwayRoam.bridgeGaps, v -> HighwayRoam.bridgeGaps = v);
		y += 22;
		check(x, y, "Parkour jumps", () -> HighwayRoam.parkour, v -> HighwayRoam.parkour = v);
		check(x + 205, y, "Walk on lava (Fire Resistance)", () -> HighwayRoam.walkOnLava, v -> HighwayRoam.walkOnLava = v);
		y += 22;
		check(x, y, "Stop and ask at chunk trails", () -> HighwayRoam.stopAtTrails, v -> HighwayRoam.stopAtTrails = v);
		check(x + 205, y, "Stop when a stranger shows up", () -> HighwayRoam.stopForStrangers, v -> HighwayRoam.stopForStrangers = v);
		y += 28;
		button(x, y, 196, HighwayRoam.active() ? "Roaming to " + HighwayRoam.target() : "Start roaming", () -> {
			if (!readHighwayFields()) return;
			String why = HighwayRoam.start(client);
			status = why == null ? "Roaming the " + HighwayRoam.axis.name() + " highway" : why;
			statusColor = why == null ? 0xFF7CE0A0 : 0xFFFF8A80;
			clearAndInit();
		});
		button(x + 204, y, 196, "Stop", () -> {
			HighwayRoam.stop("you stopped it");
			BaritoneBridge.run("stop");
			clearAndInit();
		});
	}

	private boolean readHighwayFields() {
		try {
			String h = heightField.getText().strip();
			HighwayRoam.height = h.isEmpty() ? Integer.MIN_VALUE : Integer.parseInt(h);
			HighwayRoam.distance = Math.max(100, Integer.parseInt(distanceField.getText().strip().replace(",", "")));
			return true;
		} catch (NumberFormatException e) {
			status = "Height and distance need whole numbers (leave height empty to keep your own)";
			statusColor = 0xFFFF8A80;
			return false;
		}
	}

	// ---- Settings ----

	private void initSettings(int x, int y) {
		check(x, y + 24, "Nether trips: ride the highways when quicker", () -> NNConfig.INSTANCE.tripUseHighways, v -> {
			NNConfig.INSTANCE.tripUseHighways = v;
			NNConfig.save();
		});
		check(x, y, "On trips: pause when a stranger shows up", () -> NNConfig.INSTANCE.tripStopForStrangers, v -> {
			NNConfig.INSTANCE.tripStopForStrangers = v;
			NNConfig.save();
		});
		int hearts = NNConfig.INSTANCE.tripStopHealth / 2;
		button(x + 260, y, 140, hearts == 0 ? "Stop at low health: off" : "Stop at " + hearts + " hearts", () -> {
			// Off, 2, 3, 4, 5, 6 hearts, and round again.
			int next = NNConfig.INSTANCE.tripStopHealth == 0 ? 4 : NNConfig.INSTANCE.tripStopHealth >= 12 ? 0 : NNConfig.INSTANCE.tripStopHealth + 2;
			NNConfig.INSTANCE.tripStopHealth = next;
			NNConfig.save();
			clearAndInit();
		});
		y += 48;
		settingSearch = field(x, y, 400, "Search Baritone's settings (all of them)", settingSearch == null ? "" : settingSearch.getText());
		settingSearch.setChangedListener(t -> listScroll = 0);
		// A picked setting that isn't on/off: type its value, Set, or put it back.
		BaritoneBridge.Setting picked = setting(pickedSetting);
		if (picked != null && !picked.bool()) {
			int by = height - 40;
			valueField = field(x, by, 250, picked.name(), valueField != null && picked.name().equals(valueField.getMessage().getString()) ? valueField.getText() : picked.value());
			valueField.setMaxLength(2000);
			button(x + 254, by, 70, "Set", () -> {
				run("set " + picked.name() + " " + valueField.getText().strip(), picked.name() + " set to " + valueField.getText().strip());
				settingsReadAt = 0;
			});
			button(x + 328, by, 72, "Reset", () -> {
				BaritoneBridge.reset(picked.name());
				status = picked.name() + " back to " + picked.defaultValue();
				statusColor = 0xFFDDDDDD;
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

	/** The settings list, drawn by hand: on/off ones switch with a click, the rest are picked to type a value. */
	private void renderSettings(DrawContext context, int mouseX, int mouseY) {
		int x = width / 2 - 200, top = 140, bottom = setting(pickedSetting) != null && !setting(pickedSetting).bool() ? height - 58 : height - 20;
		String q = settingSearch == null ? "" : settingSearch.getText().strip().toLowerCase(java.util.Locale.ROOT);
		List<BaritoneBridge.Setting> shown = new java.util.ArrayList<>();
		for (BaritoneBridge.Setting s : currentSettings()) {
			if (q.isEmpty() || s.name().toLowerCase(java.util.Locale.ROOT).contains(q)) shown.add(s);
		}
		context.drawTextWithShadow(textRenderer, shown.size() + " settings · changed ones in green · click an on/off one to switch it, any other to set it",
				x, top - 12, 0xFF888888);
		List<Row> rows = new java.util.ArrayList<>();
		for (BaritoneBridge.Setting s : shown) {
			String value = s.bool() ? (s.value().equals("true") ? "on" : "off") : s.value();
			rows.add(new Row(s.name(), s.name(), value + (s.changed() ? "  (default " + (s.bool() ? (s.defaultValue().equals("true") ? "on" : "off") : s.defaultValue()) + ")" : ""),
					s.changed() ? 0xFF7CE0A0 : 0xFFDDDDDD, s.name().equals(pickedSetting), () -> {
						if (s.bool()) {
							boolean on = !s.value().equals("true");
							BaritoneBridge.setBool(s.name(), on);
							status = s.name() + (on ? ": on" : ": off");
							statusColor = 0xFFDDDDDD;
							settingsReadAt = 0;
						} else {
							pickedSetting = s.name();
							valueField = null;
							clearAndInit();
						}
					}));
		}
		drawRows(context, mouseX, mouseY, x, top, bottom, rows);
		BaritoneBridge.Setting picked = setting(pickedSetting);
		if (picked != null && !picked.bool()) {
			context.drawTextWithShadow(textRenderer, picked.name() + " (default " + picked.defaultValue() + "): type a value, then Set", x, height - 52, 0xFFDDDDDD);
		}
	}

	// ---- Every command ----

	private void initCommands(int x, int y) {
		commands = BaritoneBridge.commands();
		commandSearch = field(x, y, 400, "Search Baritone's commands", commandSearch == null ? "" : commandSearch.getText());
		commandSearch.setChangedListener(t -> listScroll = 0);
		BaritoneBridge.Command picked = command(pickedCommand);
		if (picked != null) {
			int by = height - 70;
			argsField = field(x, by, 296, "what to add after " + picked.name() + " (or nothing)", argsField == null ? "" : argsField.getText());
			argsField.setMaxLength(500);
			button(x + 300, by, 100, "Run " + picked.name(), () -> {
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
		int x = width / 2 - 200;
		BaritoneBridge.Command picked = command(pickedCommand);
		int top = 82, bottom = picked == null ? height - 20 : height - 132;
		String q = commandSearch == null ? "" : commandSearch.getText().strip().toLowerCase(java.util.Locale.ROOT);
		List<Row> rows = new java.util.ArrayList<>();
		for (BaritoneBridge.Command c : commands) {
			boolean match = q.isEmpty() || c.shortDesc().toLowerCase(java.util.Locale.ROOT).contains(q)
					|| c.names().stream().anyMatch(n -> n.toLowerCase(java.util.Locale.ROOT).contains(q));
			if (!match) continue;
			String also = c.names().size() > 1 ? "  (also " + String.join(", ", c.names().subList(1, c.names().size())) + ")" : "";
			rows.add(new Row(c.name(), c.name() + also, c.shortDesc(), 0xFFFFFFFF, c.name().equals(pickedCommand), () -> {
				pickedCommand = c.name();
				if (argsField != null) argsField.setText("");
				suggestedFor = null;
				clearAndInit();
			}));
		}
		context.drawTextWithShadow(textRenderer, rows.size() + " of Baritone's commands · click one", x, top - 12, 0xFF888888);
		drawRows(context, mouseX, mouseY, x, top, bottom, rows);
		if (picked == null) return;
		// How it's used: Baritone's own lines ("> goto <x> <y> <z>").
		int uy = bottom + 4;
		context.drawTextWithShadow(textRenderer, picked.name() + ": " + picked.shortDesc(), x, uy, 0xFFFFFFFF);
		uy += 11;
		int shownLines = 0;
		for (String line : picked.longDesc()) {
			if (!line.startsWith(">") || shownLines >= 4) continue;
			context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(line.substring(1).strip(), 400), x, uy, 0xFFAAAAAA);
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
		int cx = x, cy = height - 48;
		for (String s : suggestions) {
			int w = textRenderer.getWidth(s) + 8;
			if (cx + w > x + 400) {
				cx = x;
				cy += 13;
				if (cy > height - 26) break;
			}
			boolean hover = mouseX >= cx && mouseX < cx + w && mouseY >= cy && mouseY < cy + 11;
			context.fill(cx, cy, cx + w, cy + 11, hover ? 0xFF4CA0FF : 0xFF2E4E7F);
			context.drawTextWithShadow(textRenderer, s, cx + 4, cy + 2, 0xFFFFFFFF);
			String pick = s;
			rowHits.add(new Object[] {cx, cy, cx + w, cy + 11, (Runnable) () -> addSuggestion(pick), "suggest:" + s, false});
			cx += w + 3;
		}
	}

	/** A suggestion replaces the word being typed (or follows the last whole one). */
	private void addSuggestion(String s) {
		String typed = argsField.getText();
		int cut = typed.endsWith(" ") || typed.isEmpty() ? typed.length() : typed.lastIndexOf(' ') + 1;
		argsField.setText(typed.substring(0, cut) + s + " ");
		suggestedFor = null;
	}

	/** One row of a hand-drawn list: the key it's clicked by, its two lines, colour of the second, picked or not. */
	private record Row(String key, String main, String sub, int subColor, boolean picked, Runnable click) {}

	private void drawRows(DrawContext context, int mouseX, int mouseY, int x, int top, int bottom, List<Row> rows) {
		int w = 400, rowH = 22;
		int maxScroll = Math.max(0, rows.size() * rowH - (bottom - top));
		listScroll = Math.max(0, Math.min(listScroll, maxScroll));
		context.enableScissor(x, top, x + w, bottom);
		int y = top - (int) listScroll;
		for (Row r : rows) {
			if (y + rowH >= top && y <= bottom) {
				boolean hover = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, top) && mouseY < Math.min(y + rowH - 2, bottom);
				context.fill(x, y, x + w, y + rowH - 2, r.picked ? 0x803A6FBF : hover ? 0x60FFFFFF : 0x40000000);
				context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(r.main, w - 10), x + 5, y + 2, 0xFFFFFFFF);
				context.drawText(textRenderer, textRenderer.trimToWidth(r.sub, w - 10), x + 5, y + 11, r.subColor, false);
				rowHits.add(new Object[] {x, Math.max(y, top), x + w, Math.min(y + rowH - 2, bottom), r.click, r.key, false});
			}
			y += rowH;
		}
		context.disableScissor();
		if (maxScroll > 0) {
			int bar = Math.max(12, (bottom - top) * (bottom - top) / (rows.size() * rowH));
			int by = top + (int) ((bottom - top - bar) * (listScroll / maxScroll));
			context.fill(x + w + 3, by, x + w + 5, by + bar, 0xFF888888);
		}
	}

	// ---- Drawing ----

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		if (ModsPanel.streamSafe()) {
			// Its fields and places are all coordinates.
			super.render(context, mouseX, mouseY, delta);
			context.fill(0, 0, width, height, 0xF0101418);
			context.drawCenteredTextWithShadow(textRenderer, "Hidden while stream-safe mode is on", width / 2, height / 2 - 10, 0xFFFFFFFF);
			context.drawCenteredTextWithShadow(textRenderer, "It's full of coordinates. Press Esc, then the stream-safe key to show it.", width / 2, height / 2 + 4, 0xFF9AA4AE);
			return;
		}
		super.render(context, mouseX, mouseY, delta);
		int x = width / 2 - 200;
		String state;
		if (!BaritoneBridge.present()) {
			context.drawTextWithShadow(textRenderer, "Baritone isn't installed.", x, 32, 0xFFFF8A80);
			context.drawTextWithShadow(textRenderer, "Get the Fabric 1.21.11 build from github.com/cabaletta/baritone/releases", x, 46, 0xFFAAAAAA);
			context.drawTextWithShadow(textRenderer, "(or use a client that has it built in), then reopen this screen.", x, 58, 0xFFAAAAAA);
			return;
		}
		String goal = BaritoneBridge.goal();
		state = BaritoneBridge.isPathing() ? "Moving" + (goal.isEmpty() ? "" : " · " + goal) : HighwayRoam.active() ? "Roaming (paused or working out a path)" : "Idle";
		context.drawTextWithShadow(textRenderer, "Baritone: " + state, x, 30, 0xFFFFFFFF);
		if (watchProcess != null && !BaritoneBridge.processActive(watchProcess)) {
			replyMark = -1;
			status = watchFailed;
			statusColor = 0xFFFFC14D;
			watchProcess = null;
		}
		// Baritone's answer to the last command: an error (in red) if it said one, otherwise the last thing it said.
		// Only what it says in the few seconds after, each thing once, so it never covers a newer message.
		if (replyMark >= 0 && System.currentTimeMillis() - ranAt > 3000) replyMark = -1;
		if (replyMark >= 0) {
			List<BaritoneBridge.Reply> said = BaritoneBridge.repliesSince(replyMark);
			BaritoneBridge.Reply shown = said.stream().filter(BaritoneBridge.Reply::error).findFirst().orElse(said.isEmpty() ? null : said.get(said.size() - 1));
			if (shown != null) {
				status = "Baritone: " + shown.text();
				statusColor = shown.error() ? 0xFFFF8A80 : 0xFFDDDDDD;
				if (shown.error()) replyMark = -1;
				else replyMark = BaritoneBridge.replyCount();
			}
		}
		if (!status.isEmpty()) context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(status, width - x - 4), x, height - 14, statusColor);
		if (page != Page.PLACES) rowHits.clear();
		if (page == Page.SETTINGS) renderSettings(context, mouseX, mouseY);
		if (page == Page.COMMANDS) renderCommands(context, mouseX, mouseY);
		if (page == Page.PLACES) {
			renderPlaces(context, mouseX, mouseY);
		} else if (page == Page.GO) {
			int y = 46;
			context.drawTextWithShadow(textRenderer, "x", x + 2, y + 5, 0xFFAAAAAA);
			context.drawTextWithShadow(textRenderer, "y", x + 90, y + 5, 0xFFAAAAAA);
			context.drawTextWithShadow(textRenderer, "z", x + 154, y + 5, 0xFFAAAAAA);
			context.drawTextWithShadow(textRenderer, "Player", x, y + 83, 0xFFAAAAAA);
			context.drawTextWithShadow(textRenderer, "Block", x, y + 107, 0xFFAAAAAA);
		} else if (page == Page.HIGHWAY) {
			int y = 70;
			context.drawTextWithShadow(textRenderer, "Height", x + 108, y + 5, 0xFFAAAAAA);
			context.drawTextWithShadow(textRenderer, "Distance", x + 240, y + 5, 0xFFAAAAAA);
			int hy = y + 126;
			for (var l : textRenderer.wrapLines(Text.literal("Damaged highway? It breaks through, bridges gaps with blocks you carry, or goes round the side."), 400)) {
				context.drawText(textRenderer, l, x, hy, 0xFF888888, true);
				hy += 10;
			}
		}
	}

	/** For the self-test: what Baritone suggests for what's typed on the Every command page. */
	public List<String> testSuggestions() {
		return suggestions;
	}

	/** For the self-test: shows a page (0 Go to…, 1 Do, 2 Highway roam, 3 Settings, 4 Every command). */
	public void testPage(int index) {
		page = Page.values()[index];
		clearAndInit();
	}

	/** For the self-test: clicks the button or switch whose label starts with {@code label}, as the mouse would. */
	public boolean testPress(String label) {
		// The exact label first, so "Go" isn't taken for "Go to…" or "Stop" for "Stop at 2 hearts".
		for (boolean exact : new boolean[] {true, false}) {
			for (var e : List.copyOf(children())) {
				if (!(e instanceof net.minecraft.client.gui.widget.ClickableWidget w) || !w.visible || w instanceof TextFieldWidget) continue;
				String shown = w.getMessage().getString().replaceFirst("^› ?", "");
				if (exact ? shown.equals(label) : shown.startsWith(label)) {
					testClickAt(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
					return true;
				}
			}
		}
		return false;
	}

	/** For the self-test: whether a button or switch whose label starts with {@code label} is showing. */
	public boolean testHas(String label) {
		return children().stream().anyMatch(e -> e instanceof net.minecraft.client.gui.widget.ClickableWidget w && w.visible
				&& w.getMessage().getString().replaceFirst("^› ?", "").startsWith(label));
	}

	/** For the self-test: which page is showing (0 Go to…, 1 Do, 2 Highway roam, 3 Settings). */
	public int testPageShown() {
		return page.ordinal();
	}

	/** For the self-test: types into the box whose hint is {@code hint}. */
	public boolean testType(String hint, String text) {
		for (var e : children()) {
			if (e instanceof TextFieldWidget f && f.getMessage().getString().equals(hint)) {
				f.setText(text);
				return true;
			}
		}
		return false;
	}

	/** For the self-test: what a box on screen holds, or null if there's no such box. */
	public String testField(String hint) {
		for (var e : children()) {
			if (e instanceof TextFieldWidget f && f.getMessage().getString().equals(hint)) return f.getText();
		}
		return null;
	}

	/** For the self-test: whether a row button with this key was drawn. */
	public boolean testHasRow(String key) {
		return rowHits.stream().anyMatch(h -> key.equals(h[5]));
	}

	/** For the self-test: clicks a place's Go (or its ×) on the Go to… list as last drawn. */
	public boolean testRow(String name, boolean remove) {
		for (Object[] hit : List.copyOf(rowHits)) {
			if (name.equals(hit[5]) && remove == (boolean) hit[6]) {
				testClickAt(((int) hit[0] + (int) hit[2]) / 2.0, ((int) hit[1] + (int) hit[3]) / 2.0);
				return true;
			}
		}
		return false;
	}

	private void testClickAt(double x, double y) {
		net.minecraft.client.gui.Click click = new net.minecraft.client.gui.Click(x, y, new net.minecraft.client.input.MouseInput(0, 0));
		mouseClicked(click, false);
		mouseReleased(click);
	}

	/** For the self-test: whether the status is shown as an error (in red). */
	public boolean testStatusIsError() {
		return statusColor == 0xFFFF8A80;
	}

	public String testStatus() {
		return status;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
