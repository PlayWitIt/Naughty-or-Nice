package com.greysworkshop.naughtyornice;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.util.List;
import java.util.UUID;

/**
 * Development-only scripted test, run with {@code ./gradlew runSelfTest}. Not included in the release jar.
 * Seeds some players, spawns a fake griefer who hits and kills you, clicks through the store and screenshots it.
 */
public final class SelfTest {
	private static final String WORLD = "nn-selftest";
	private static final String GRIEFER = "Griefer_Gary";

	private static boolean started;
	private static int tick = -1;
	private static FakePlayer griefer;
	private static int passed;
	private static int failed;
	private static int frames;
	private static int framesAtLastStep;

	private SelfTest() {}

	public static void start() {
		log("Self-test armed");
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (started || !(screen instanceof TitleScreen)) return;
			started = true;
			// Keep the game running while the window isn't focused (someone using the computer meanwhile), or the
			// singleplayer server pauses and nothing that needs time (walking, flowing water) happens.
			client.options.pauseOnLostFocus = false;
			client.send(() -> openWorld(client));
		});
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
		// What actually reaches the server as chat, to prove the Baritone screen and the send guards leak nothing.
		net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
				serverHeard.add(message.getContent().getString()));
		net.fabricmc.fabric.api.message.v1.ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) ->
				serverHeard.add(message.getContent().getString()));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (walkTicks > 0 && griefer != null) {
				walkTicks--;
				griefer.refreshPositionAndAngles(griefer.getX() + 0.25, griefer.getY(), griefer.getZ(), 180, 0);
			}
		});
		// Count frames, so each step waits for the screen to have been drawn since the last one (clicks need
		// what's on screen to be current, and a busy machine can draw fewer frames than it runs ticks).
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> frames++);
	}

	private static void openWorld(MinecraftClient client) {
		if (client.getLevelStorage().levelExists(WORLD)) {
			log("Loading existing test world");
			client.createIntegratedServerLoader().start(WORLD, () -> {});
			return;
		}
		log("Creating flat test world");
		LevelInfo info = new LevelInfo(WORLD, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true,
				new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
		client.createIntegratedServerLoader().createAndStart(WORLD, info, GeneratorOptions.createTestWorld(),
				WorldPresets::createTestOptions, new TitleScreen());
	}

	private static UUID id(String name) {
		return NaughtyOrNiceClient.offlineId(name);
	}

	private static void tick(MinecraftClient client) {
		afterDisconnect(client);
		if (downloadCheck(client)) return;
		if (client.player == null || client.world == null || client.getServer() == null) return;
		if (frames == framesAtLastStep) return;
		framesAtLastStep = frames;
		if (baritoneStep > 0) {
			baritoneButtons(client);
			return;
		}
		tick++;
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		Ledger ledger = mod.ledger;
		switch (tick) {
			case 58 -> {
				// The same starting spot every run (the test world remembers where the player last was).
				IntegratedServer server = client.getServer();
				server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), "tp @a 0.5 -60 0.5 -90 0"));
				// Nothing left from earlier runs: water (the chunk-trail test needs land with none), crops, farmland and
				// dropped items (the farm test needs nothing to farm at first).
				for (String cmd : new String[] {"fill -90 -60 -90 90 -60 90 air replace water", "fill -90 -60 -90 90 -60 90 air replace wheat",
						"fill -90 -61 -90 90 -61 90 grass_block replace farmland", "kill @e[type=item]"}) {
					server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd));
				}
			}
			case 60 -> {
				log("Clearing old lists and seeding players");
				for (Profile p : List.copyOf(ledger.all())) ledger.forget(p);
				NNConfig.INSTANCE.autoNaughtyKillers = true;
				NNConfig.INSTANCE.nametagMarks = true;

				Profile hana = ledger.getOrCreate(id("Helpful_Hana"), "Helpful_Hana");
				hana.secondsNear = 3 * 3600 + 600;
				for (int i = 0; i < 4; i++) mod.watcher.record(hana, Profile.Kind.PEACEFUL, "");
				Profile rick = ledger.getOrCreate(id("RandomRick"), "RandomRick");
				mod.watcher.record(rick, Profile.Kind.HIT_PLAYER, "Helpful_Hana");
				mod.watcher.record(rick, Profile.Kind.HIT_PLAYER, "Helpful_Hana");
				ledger.getOrCreate(id("Steve_Builder"), "Steve_Builder").secondsNear = 300;
				Profile toxic = ledger.getOrCreate(id("xX_Toxic_Xx"), "xX_Toxic_Xx");
				for (int i = 0; i < 3; i++) mod.watcher.record(toxic, Profile.Kind.HIT_PLAYER, "Steve_Builder");
				mod.watcher.record(toxic, Profile.Kind.KILLED_PLAYER, "Steve_Builder");
				mod.watcher.record(toxic, Profile.Kind.RUDE, "ez noob");
				ledger.setVerdict(ledger.getOrCreate(id("OldFriend"), "OldFriend"), Profile.Verdict.NICE, false, null);

				log("Chat: Hana says thanks");
				mod.watcher.onGameMessage(client, Text.literal("<Helpful_Hana> thanks for the help earlier!"));
				check("friendly chat noticed", hana.count(Profile.Kind.KIND_WORDS) == 1);
				check("chat kept on the profile", hana.recentChat.contains("thanks for the help earlier!"));
				mod.watcher.onGameMessage(client, Text.literal("<xX_Toxic_Xx> ez noob"));
				check("trash talk folded into one entry", toxic.count(Profile.Kind.RUDE) == 2 && toxic.actions.stream().filter(a -> a.kind == Profile.Kind.RUDE).count() == 1);

				// ---- Stats: all-time counts, death messages, chat ----
				check("chat counted (" + hana.stats.messages + " messages, " + hana.stats.words + " words)", hana.stats.messages == 1 && hana.stats.words == 5);
				Profile sam = ledger.getOrCreate(id("Serial_Sam"), "Serial_Sam");
				for (int i = 0; i < 70; i++) mod.watcher.record(sam, Profile.Kind.KILLED_PLAYER, "Victim" + i);
				check("counts survive the activity list being trimmed (" + sam.count(Profile.Kind.KILLED_PLAYER) + ", " + sam.actions.size() + " entries)",
						sam.count(Profile.Kind.KILLED_PLAYER) == 70 && sam.actions.size() <= NNConfig.INSTANCE.maxActions);
				ledger.forget(sam);
				Text vipHana = Text.literal("[VIP] ").append(Text.literal("Helpful_Hana").styled(st -> st.withInsertion("Helpful_Hana")));
				Text toxicName = Text.literal("xX_Toxic_Xx").styled(st -> st.withInsertion("xX_Toxic_Xx"));
				check("a ranked name in a message is read as the player", "Helpful_Hana".equals(Watcher.playerArg(vipHana)));
				check("a mob in a message isn't a player", Watcher.playerArg(Text.literal("Zombie").styled(st -> st.withInsertion(UUID.randomUUID().toString()))) == null);
				check("a plugin's plain ranked name still finds the player", "Steve".equals(Watcher.playerArg(Text.literal("[Admin] Steve"))));
				mod.watcher.onGameMessage(client, Text.translatable("death.attack.player.item", vipHana, toxicName, Text.literal("[Diamond Sword]")));
				check("death counted for the victim (" + hana.stats.deaths + ")", hana.stats.deaths == 1 && hana.stats.deathsToPlayers == 1
						&& hana.stats.killedBy.getOrDefault("xX_Toxic_Xx", 0) == 1);
				check("kill counted for the killer, with victim and weapon (" + toxic.count(Profile.Kind.KILLED_PLAYER) + ", " + toxic.stats.weapons + ")",
						toxic.count(Profile.Kind.KILLED_PLAYER) == 2 && toxic.stats.victims.getOrDefault("Helpful_Hana", 0) == 1
						&& toxic.stats.weapons.getOrDefault("Diamond Sword", 0) == 1);
				mod.watcher.onGameMessage(client, Text.translatable("death.fell.accident.generic", Text.literal("Steve_Builder").styled(st -> st.withInsertion("Steve_Builder"))));
				check("a death with no killer counts as a death only", ledger.byName("Steve_Builder").stats.deaths == 1 && ledger.byName("Steve_Builder").stats.deathsToPlayers == 0);
				Text steveName = Text.literal("Steve_Builder").styled(st -> st.withInsertion("Steve_Builder"));
				mod.watcher.onGameMessage(client, Text.translatable("death.attack.flyIntoWall", steveName));
				check("how they died is sorted (" + ledger.byName("Steve_Builder").stats.deathCauses + ", " + hana.stats.deathCauses + ")",
						ledger.byName("Steve_Builder").stats.deathCauses.getOrDefault("Fall", 0) == 1
						&& ledger.byName("Steve_Builder").stats.deathCauses.getOrDefault("Elytra crash", 0) == 1
						&& hana.stats.deathCauses.getOrDefault("Killed by a player", 0) == 1);
				mod.watcher.onGameMessage(client, Text.translatable("chat.type.advancement.task", vipHana, Text.literal("[Diamonds!]")));
				check("advancements noted (" + hana.stats.advancements.size() + ")", hana.stats.advancements.size() == 1 && hana.stats.advancements.get(0).text.equals("Diamonds!"));
				check("account types told apart", PlayerStats.accountType(UUID.fromString("00000000-0000-0000-0009-01f6d8e1a2b3")).startsWith("Bedrock")
						&& PlayerStats.accountType(UUID.randomUUID()).startsWith("Java") && PlayerStats.accountType(id("Helpful_Hana")).startsWith("Offline"));
				check("usual login time needs logins close together", usualTimes());
			}
			case 80 -> {
				log("Spawning the fake griefer in front of the player");
				IntegratedServer server = client.getServer();
				server.execute(() -> {
					ServerPlayerEntity me = server.getPlayerManager().getPlayerList().get(0);
					griefer = FakePlayer.get(me.getEntityWorld(), new GameProfile(id(GRIEFER), GRIEFER));
					Vec3d pos = me.getEntityPos().add(0, 0, 4);
					griefer.refreshPositionAndAngles(pos.x, pos.y, pos.z, 180, 0);
					server.getPlayerManager().sendToAll(PlayerListS2CPacket.entryFromPlayer(List.of(griefer)));
					griefer.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new net.minecraft.item.ItemStack(net.minecraft.item.Items.ELYTRA));
					griefer.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new net.minecraft.item.ItemStack(net.minecraft.item.Items.NETHERITE_HELMET));
					griefer.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.END_CRYSTAL));
					me.getEntityWorld().spawnEntity(griefer);
					me.changeGameMode(GameMode.SURVIVAL);
				});
			}
			case 110 -> {
				PlayerEntity seen = client.world.getPlayerByUuid(id(GRIEFER));
				check("fake griefer visible on the client", seen != null);
				Profile gary = ledger.get(id(GRIEFER));
				check("griefer got a profile from being seen", gary != null);
				// Face the griefer for the N-key test.
				client.player.setYaw(0);
				client.player.setPitch(0);
			}
			case 112 -> check("N opens the page of the player you look at", NaughtyOrNiceClient.lookedAt(client) != null
					&& NaughtyOrNiceClient.lookedAt(client).getUuid().equals(id(GRIEFER)));
			case 120 -> {
				log("Griefer hits the player three times");
				hitMe(client, 1f);
			}
			case 135 -> hitMe(client, 1f);
			case 150 -> hitMe(client, 1f);
			case 170 -> {
				Profile gary = ledger.get(id(GRIEFER));
				check("hits seen through the damage packet (" + (gary == null ? 0 : gary.count(Profile.Kind.HIT_YOU)) + ")",
						gary != null && gary.count(Profile.Kind.HIT_YOU) == 3);
				check("hits folded into one activity entry", gary != null && gary.actions.stream().filter(a -> a.kind == Profile.Kind.HIT_YOU).count() == 1);
				DeathRecap.testOpen = true;
				log("Griefer kills the player");
				hitMe(client, 1000f);
			}
			case 210 -> {
				Profile gary = ledger.get(id(GRIEFER));
				check("kill recorded once (" + (gary == null ? 0 : gary.count(Profile.Kind.KILLED_YOU)) + ")", gary != null && gary.count(Profile.Kind.KILLED_YOU) == 1);
				check("killer put on the Naughty list automatically", gary != null && gary.verdict == Profile.Verdict.NAUGHTY && gary.autoListed);
				EventHooks.flush(true);
				String meName = client.player.getGameProfile().name();
				List<Float> health = EventLog.since(System.currentTimeMillis() - 120_000).stream()
						.filter(e -> e.type == EventLog.Type.HIT && e.target.equals(meName)).map(e -> e.health).toList();
				// Creative mode: the small hits leave you at 20. The killing hit shows 0, which it only can if the health is read after the
				// server's health update (read straight away, it was still 20).
				check("each hit logs the health left after it (" + health + ")", health.size() == 4 && health.get(3) == 0f);
				long now = System.currentTimeMillis();
				long inWindow = EventLog.since(now - DeathRecap.WINDOW_MS).stream().filter(e -> e.type == EventLog.Type.HIT && e.target.equals(meName)).count();
				DeathRecap.Recap recap = DeathRecap.build(meName, now);
				check("death recap: every hit in the last 30 s, all from the griefer (" + recap.hits() + " of " + inWindow + ", " + recap.sources() + ")",
						inWindow >= 1 && recap.hits() == inWindow && recap.sources().size() == 1 && recap.sources().get(0).name().equals(GRIEFER));
				check("death screen has the recap's save button", client.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen
						&& net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(client.currentScreen).stream().anyMatch(b -> b.getMessage().getString().equals("Save this fight")));
				screenshot(client, "0_death_message");
				client.player.requestRespawn();
				client.setScreen(null);
			}
			case 250 -> {
				client.player.setYaw(0);
				client.player.setPitch(0);
				PlayerEntity seen = client.world.getPlayerByUuid(id(GRIEFER));
				check("nametag marked naughty", seen != null && seen.getDisplayName().getString().startsWith("✖"));
				PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(id(GRIEFER));
				check("tab list marked naughty", entry != null
						&& client.inGameHud.getPlayerListHud().getPlayerName(entry).getString().startsWith("✖"));
				client.options.playerListKey.setPressed(true);
			}
			case 265 -> screenshot(client, "1_nametag_and_tab");
			case 270 -> {
				client.options.playerListKey.setPressed(false);
				client.setScreen(new ShopScreen(ShopScreen.Tab.ALL, null));
			}
			case 285 -> {
				check("All tab lists everyone but you (" + screen(client).testResultCount() + ")", screen(client).testResultCount() == 6);
				screenshot(client, "2_store_all");
			}
			case 290 -> check("one click puts Hana on the Nice list", screen(client).testClick("nice:Helpful_Hana")
					&& ledger.get(id("Helpful_Hana")).verdict == Profile.Verdict.NICE);
			case 300 -> screenshot(client, "3_added_banner");
			case 303 -> check("taking someone off a list asks first", screen(client).testClick("nice:Helpful_Hana")
					&& ledger.get(id("Helpful_Hana")).verdict == Profile.Verdict.NICE);
			case 306 -> screenshot(client, "3b_confirm_move");
			case 309 -> check("clicking again takes her off", screen(client).testClick("nice:Helpful_Hana")
					&& ledger.get(id("Helpful_Hana")).verdict == Profile.Verdict.NONE);
			case 312 -> check("undo puts her back", screen(client).testClick("undo") && ledger.get(id("Helpful_Hana")).verdict == Profile.Verdict.NICE);
			case 318 -> check("card opens the player page", screen(client).testClick("card:" + GRIEFER)
					&& screen(client).testSelected() != null && screen(client).testSelected().name.equals(GRIEFER));
			case 326 -> screenshot(client, "4_player_page");
			case 330 -> {
				check("back to results", screen(client).testClick("back") && screen(client).testSelected() == null);
				screen(client).testSearch("hana");
			}
			case 335 -> check("search narrows to one (" + screen(client).testResultCount() + ")", screen(client).testResultCount() == 1);
			case 340 -> screen(client).testSearch("Newbie_Ned");
			case 345 -> screenshot(client, "5_add_by_name");
			case 350 -> {
				check("adding an unknown name works", screen(client).testClick("add-naughty")
						&& ledger.byName("Newbie_Ned") != null && ledger.byName("Newbie_Ned").verdict == Profile.Verdict.NAUGHTY);
				screen(client).testSearch("");
				screen(client).testClick("tab:LOOK");
			}
			case 360 -> {
				check("Look Into shows Toxic as a suggestion (" + screen(client).testResultCount() + ")", screen(client).testResultCount() == 1);
				screenshot(client, "6_needs_a_look");
				screen(client).testClick("tab:NAUGHTY");
			}
			case 370 -> {
				check("Naughty list has Gary and Ned (" + screen(client).testResultCount() + ")", screen(client).testResultCount() == 2);
				screenshot(client, "7_naughty_list");
				screen(client).testClick("tab:SETTINGS");
			}
			case 380 -> {
				screenshot(client, "8_settings");
				boolean before = NNConfig.INSTANCE.nametagMarks;
				check("settings toggle", screen(client).testClick("option:Marks on nametags") && NNConfig.INSTANCE.nametagMarks != before);
				screen(client).testClick("option:Marks on nametags");
			}
			case 390 -> {
				client.setScreen(null);
				log("Saving and reloading the lists");
				String key = ledger.key();
				ledger.switchTo(null);
				ledger.switchTo(key);
				Profile gary = ledger.get(id(GRIEFER));
				check("lists survive a reload", gary != null && gary.verdict == Profile.Verdict.NAUGHTY
						&& ledger.get(id("Helpful_Hana")).verdict == Profile.Verdict.NICE && gary.count(Profile.Kind.KILLED_YOU) == 1);
				check("everyone seen is remembered", ledger.get(id("Steve_Builder")) != null);
			}
			case 400 -> {
				log("Opening with N while looking at the griefer");
				PlayerEntity gary = client.world.getPlayerByUuid(id(GRIEFER));
				if (gary != null) {
					Vec3d d = gary.getEyePos().subtract(client.player.getEyePos());
					client.player.setYaw((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
					client.player.setPitch((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
				}
				client.setScreen(mod.openScreen(client));
			}
			case 415 -> {
				check("N opened Gary's page", client.currentScreen instanceof ShopScreen s && s.testSelected() != null
						&& s.testSelected().name.equals(GRIEFER));
				screenshot(client, "9_n_key_page");
			}
			case 425 -> {
				client.setScreen(null);
				log("Chat logging: a conversation around Hana (Nice list)");
				NNConfig.INSTANCE.chatLog = true;
				NNConfig.INSTANCE.chatFiles = true;
				NNConfig.INSTANCE.chatQuietSeconds = 3;
				NNConfig.INSTANCE.chatContextSeconds = 60;
				mod.watcher.onGameMessage(client, Text.literal("<Steve_Builder> anyone selling diamonds?"));
				Profile hana = ledger.get(id("Helpful_Hana"));
				check("no log for unlisted players", ledger.get(id("Steve_Builder")) == null || ledger.get(id("Steve_Builder")).conversations.isEmpty());
				mod.watcher.onGameMessage(client, Text.literal("<Helpful_Hana> I have some, meet at spawn"));
				Profile.Conversation c = hana.conversations.isEmpty() ? null : hana.conversations.get(hana.conversations.size() - 1);
				check("listed player speaking opens a conversation", c != null && c.open && mod.chatLogger.isOpen(hana));
				check("it starts with what was said just before (" + (c == null ? 0 : c.lines.size()) + " lines)",
						c != null && c.lines.size() == 2 && c.lines.get(0).sender.equals("Steve_Builder") && c.lines.get(1).them);
				mod.watcher.onGameMessage(client, Text.literal("<RandomRick> lol ok"));
				mod.watcher.onGameMessage(client, Text.translatable("commands.message.display.incoming", Text.literal("Helpful_Hana"), Text.literal("bring 5 iron")));
				mod.watcher.onGameMessage(client, Text.translatable("commands.message.display.outgoing", Text.literal("RandomRick"), Text.literal("secret to rick")));
				check("replies and her whisper are in it, other whispers aren't (" + (c == null ? 0 : c.lines.size()) + ")", c != null && c.lines.size() == 4
						&& c.lines.get(3).whisper && c.lines.stream().noneMatch(l -> l.text.equals("secret to rick")));
				log("Gary talks in a ranked chat format");
				mod.watcher.onGameMessage(client, Text.literal("[VIP] Griefer_Gary » you are all trash lol"));
				Profile gary = ledger.get(id(GRIEFER));
				check("ranked format recognised and logged for Gary", gary.conversations.stream().anyMatch(cv -> cv.open
						&& cv.lines.stream().anyMatch(l -> l.them && l.text.equals("you are all trash lol"))));
				check("his trash talk counted", gary.count(Profile.Kind.RUDE) >= 1);
				log("Toxic says something, then gets listed: the log catches up");
				mod.watcher.onGameMessage(client, Text.literal("<xX_Toxic_Xx> gonna grief your base tonight"));
				Profile toxic = ledger.get(id("xX_Toxic_Xx"));
				ledger.setVerdict(toxic, Profile.Verdict.NAUGHTY, false, null);
				check("listing someone logs what they just said", mod.chatLogger.isOpen(toxic)
						&& toxic.conversations.get(0).lines.stream().anyMatch(l -> l.them && l.text.contains("grief your base")));
				log("You say something through the real chat");
				client.player.networkHandler.sendChatMessage("ok Hana omw");
			}
			case 440 -> {
				Profile hana = ledger.get(id("Helpful_Hana"));
				Profile.Conversation c = hana.conversations.get(hana.conversations.size() - 1);
				check("your own chat is in the conversation", c.lines.stream().anyMatch(l -> l.me && l.text.equals("ok Hana omw")));
			}
			case 520 -> {
				Profile hana = ledger.get(id("Helpful_Hana"));
				Profile.Conversation c = hana.conversations.get(hana.conversations.size() - 1);
				check("conversation closes after she's been quiet", !c.open && !mod.chatLogger.isOpen(hana) && c.end > 0);
				java.nio.file.Path file = mod.chatLogger.file(hana);
				String text = "";
				try {
					text = java.nio.file.Files.readString(file);
				} catch (java.io.IOException e) {
					log("could not read " + file + ": " + e);
				}
				check("conversation written to " + file.getFileName(), text.contains("<Helpful_Hana> I have some, meet at spawn")
						&& text.contains("Helpful_Hana whispered to you: bring 5 iron") && text.contains("<Steve_Builder> anyone selling"));
				client.setScreen(new ShopScreen(ShopScreen.Tab.ALL, null));
			}
			case 530 -> screen(client).testSearch("meet at spawn");
			case 535 -> {
				check("search finds players by what they said (" + screen(client).testResultCount() + ")", screen(client).testResultCount() == 1);
				screenshot(client, "10_search_chat");
				screen(client).testClick("card:Helpful_Hana");
			}
			case 545 -> {
				screenshot(client, "11_chat_log");
				client.currentScreen.mouseScrolled(200, 200, 0, -8);
			}
			case 548 -> {
				screenshot(client, "11b_chat_log_lines");
				// Back up to the section tabs (the page is long: it has everything she said).
				client.currentScreen.mouseScrolled(200, 200, 0, 100);
			}
			case 550 -> {
				check("chat log tab clickable", screen(client).testClick("section:CHAT"));
				screen(client).testSearch("");
				screen(client).testClick("tab:NICE");
			}
			case 560 -> {
				screenshot(client, "12_nice_tab_add_card");
				check("Add players opens the panel", screen(client).testClick("open-add") && screen(client).testAdding());
				screen(client).testAddText("Alice_1, Bob_2 bad!name", "server friends");
			}
			case 570 -> screenshot(client, "13_add_panel");
			case 575 -> {
				check("panel adds several names at once", screen(client).testPanelClick("add-to-nice")
						&& ledger.byName("Alice_1") != null && ledger.byName("Alice_1").verdict == Profile.Verdict.NICE
						&& ledger.byName("Bob_2") != null && ledger.byName("Bob_2").verdict == Profile.Verdict.NICE
						&& ledger.byName("Alice_1").details.stream().anyMatch(d -> d.text.equals("server friends") && d.mine()) && ledger.byName("bad!name") == null);
			}
			case 585 -> screenshot(client, "14_add_panel_done");
			case 590 -> {
				screen(client).testAddText("Ri", "");
			}
			case 598 -> {
				check("panel suggests matching players", screen(client).testPanelClick("suggest:RandomRick"));
				screen(client).testPanelClick("add-to-naughty");
				check("picked suggestion added to Naughty", ledger.get(id("RandomRick")).verdict == Profile.Verdict.NAUGHTY);
				screen(client).testPanelClick("add-close");
			}
			case 605 -> {
				check("panel closes", !screen(client).testAdding());
				screen(client).testClick("tab:SETTINGS");
			}
			case 615 -> {
				screenshot(client, "15_settings_chat");
				// Everything is logged now, so there's no conversation length, or how long chat is kept, to set.
				check("no conversation-length setting", !screen(client).testClick("step+:A conversation ends after"));
				check("no how-long-to-keep-chat settings", !screen(client).testClick("step+:Chat tab keeps") && !screen(client).testClick("step+:Keep chat logs for"));
				boolean trails = NNConfig.INSTANCE.chunkTrails;
				check("chunk trails turn off and on from their own card", screen(client).testClick("chunks-main") && NNConfig.INSTANCE.chunkTrails != trails
						&& screen(client).testClick("chunks-main") && NNConfig.INSTANCE.chunkTrails == trails);
				Profile.Line one = new Profile.Line();
				one.time = System.currentTimeMillis();
				one.sender = "Steve_Builder";
				one.text = "anyone selling diamonds?";
				check("a chat line for Details says when it was said (" + ChatLogger.whenSaid(one) + ")",
						ChatLogger.whenSaid(one).matches("in chat on \\w{3} \\d{1,2} \\w{3} \\d{4} at \\d\\d:\\d\\d"));
				Profile steve = ledger.byName("Steve_Builder");
				int detailsBefore = steve.details.size();
				check("+ Details puts what they said into their Details", screen(client).lineToDetails(one) == steve
						&& steve.details.size() == detailsBefore + 1 && steve.details.get(steve.details.size() - 1).text.endsWith(": \"anyone selling diamonds?\"")
						&& screen(client).lineToDetails(one) == null);
				steve.details.remove(steve.details.size() - 1);
				check("a copied chat line has its date, time and who said it (" + ChatLogger.formatLine(one) + ")",
						ChatLogger.formatLine(one).matches("\\[\\w{3} \\d{1,2} \\w{3} \\d{4} \\d\\d:\\d\\d:\\d\\d\\] <Steve_Builder> anyone selling diamonds\\?"));
				ShopScreen shop = screen(client);
				check("Baritone tab opens Baritone", shop.testClick("tab:BARITONE") && client.currentScreen instanceof BaritoneScreen);
				client.currentScreen.close();
				check("Esc from Baritone comes back", client.currentScreen == shop);
			}
			case 625 -> {
				client.setScreen(null);
				log("Reload keeps chat logs");
				String key = ledger.key();
				mod.chatLogger.closeAll();
				ledger.switchTo(null);
				ledger.switchTo(key);
				Profile hana = ledger.get(id("Helpful_Hana"));
				check("chat logs survive a reload", hana != null && !hana.conversations.isEmpty()
						&& hana.conversations.get(hana.conversations.size() - 1).lines.size() >= 5);
			}
			case 635 -> {
				log("Forgetting old passers-by");
				NNConfig.INSTANCE.forgetAfterDays = 30;
				ledger.getOrCreate(id("Old_Passerby"), "Old_Passerby").lastSeen = System.currentTimeMillis() - 100L * 86_400_000L;
				Profile toxic = ledger.get(id("xX_Toxic_Xx"));
				toxic.lastSeen = System.currentTimeMillis() - 100L * 86_400_000L;
				String key = ledger.key();
				ledger.switchTo(null);
				ledger.switchTo(key);
				check("unremarkable players unseen for too long are forgotten", ledger.get(id("Old_Passerby")) == null);
				check("players with a history are kept however old", ledger.get(id("xX_Toxic_Xx")) != null);
				NNConfig.INSTANCE.forgetAfterDays = 180;
				client.setScreen(new ShopScreen(ShopScreen.Tab.OFFLINE, null));
			}
			case 650 -> {
				long expected = ledger.all().stream().filter(p -> client.getNetworkHandler().getPlayerListEntry(p.id) == null).count();
				check("Offline tab lists remembered players (" + screen(client).testResultCount() + "/" + expected + ")",
						screen(client).testResultCount() == expected && expected >= 5);
				screenshot(client, "16_offline_dark");
			}
			case 655 -> {
				boolean before = NNConfig.INSTANCE.darkMode;
				check("theme button switches light/dark", screen(client).testClick("theme") && NNConfig.INSTANCE.darkMode != before);
			}
			case 665 -> screenshot(client, "17_offline_light");
			case 670 -> {
				screen(client).testClick("theme");
				screen(client).testClick("tab:ALL");
			}
			case 680 -> {
				screen(client).testClick("card:Helpful_Hana");
			}
			case 690 -> {
				screen(client).testClick("section:CHAT");
				screenshot(client, "18_chat_log_dark");
			}
			case 700 -> {
				mediumWidth = screen(client).width;
				log("Medium screen is " + mediumWidth + " wide");
				client.setScreen(new ShopScreen(ShopScreen.Tab.SETTINGS, null));
			}
			case 705 -> screenshot(client, "19_settings_size");
			case 710 -> check("Screen size is at the top of Settings", screen(client).testClick("step-:Screen size") && NNConfig.INSTANCE.screenSize == 0);
			case 715 -> {
				smallWidth = screen(client).width;
				screen(client).testClick("step+:Screen size");
				log("Small screen is " + smallWidth + " wide");
			}
			case 717 -> screen(client).testClick("step+:Screen size");
			case 719 -> screenshot(client, "19b_settings_large");
			case 720 -> {
				int large = screen(client).width;
				log("Large screen is " + large + " wide, " + screen(client).height + " high");
				check("Small, Medium and Large are three different sizes (" + smallWidth + " > " + mediumWidth + " > " + large + ")",
						NNConfig.INSTANCE.screenSize == 2 && smallWidth > mediumWidth && mediumWidth > large);
				check("Large still leaves room for the panels (" + screen(client).height + " high)", screen(client).height >= 290);
				screen(client).testClick("step-:Screen size");
			}
			case 725 -> {
				check("back to Medium", NNConfig.INSTANCE.screenSize == 1);
				client.setScreen(new ShopScreen(null, ledger.byName("Helpful_Hana")));
			}
			// ---- Details ----
			case 735 -> {
				Profile hana = ledger.byName("Helpful_Hana");
				check("a player page opens on Details", screen(client).testClick("starter:Base near"));
				screen(client).testDetailText("Base near 1200, -3400 (sugar cane farm)");
				screen(client).testClick("detail-add");
				screen(client).testDetailText("Friends with OldFriend; plays evenings");
				screen(client).testClick("detail-add");
				check("details are added", hana.details.stream().anyMatch(d -> d.text.startsWith("Base near 1200") && d.mine())
						&& hana.details.size() >= 2);
				screen(client).testDetailText("base near 1200, -3400 (sugar cane farm)");
				screen(client).testClick("detail-add");
				check("the same detail isn't added twice", hana.details.stream().filter(d -> d.text.toLowerCase().startsWith("base near 1200")).count() == 1);
			}
			case 740 -> screenshot(client, "20_details");
			case 742 -> {
				screen(client).testSearch("sugar cane");
				check("search finds players by their details", screen(client).testResultCount() == 1);
				screen(client).testSearch("");
			}
			// ---- Sharing: sending ----
			case 750 -> client.setScreen(new ShopScreen().withSharePanel(false));
			case 756 -> {
				check("Share panel opens", screen(client).testSharing());
				screenshot(client, "21_share_send");
			}
			case 760 -> {
				check("Copy share code copies a code", screen(client).testPanelClick("share-copy")
						&& client.keyboard.getClipboard().startsWith(Sharing.CODE_PREFIX));
				Sharing.Pack back = Sharing.read("Here you go! " + client.keyboard.getClipboard() + " (from Discord)");
				long listed = Sharing.choose(ledger, Sharing.Scope.LISTS, client.player.getUuid()).size();
				check("a share code reads back, even inside a chat message (" + back.players.size() + "/" + listed + ")",
						back.players.size() == listed && back.players.stream().anyMatch(sp -> sp.name.equals("Helpful_Hana")
								&& sp.details.stream().anyMatch(d -> d.text.startsWith("Base near 1200"))));
				int before = Sharing.files().size();
				screen(client).testPanelClick("share-save");
				check("Save as file writes a share file", Sharing.files().size() == before + 1
						&& Sharing.readFile(Sharing.files().get(0)).players.size() == listed);
			}
			// ---- Sharing: receiving a friend's lists ----
			case 770 -> {
				steveBefore = ledger.byName("Steve_Builder").verdict;
				rickBefore = ledger.byName("RandomRick").verdict;
				friendCode = Sharing.code(friendPack(client, ledger));
				screen(client).testPanelClick("share-receive");
				screen(client).testLoadShare(friendCode);
				check("a friend's share shows a preview (" + screen(client).testImportCount() + " to import)",
						screen(client).testImportCount() == 4);
			}
			case 776 -> screenshot(client, "22_share_preview");
			case 780 -> {
				screen(client).testPanelClick("share-import");
				Profile sam = ledger.byName("Sneaky_Sam");
				check("import adds new players to the friend's list, with their details", sam != null && sam.verdict == Profile.Verdict.NAUGHTY
						&& sam.details.stream().anyMatch(d -> d.text.equals("Alt of Griefer_Gary") && d.from.equals("Friend_Fran") && !d.seen)
						&& sam.details.stream().anyMatch(d -> d.text.equals("Killed Friend_Fran ×2") && d.seen));
				Profile steve = ledger.byName("Steve_Builder");
				check("import fills in players you hadn't listed", steve.details.stream().anyMatch(d -> d.text.equals("Builds the spawn highway"))
						&& (steveBefore != Profile.Verdict.NONE || steve.verdict == Profile.Verdict.NICE));
				check("where you disagree, your list wins", ledger.byName("RandomRick").verdict == rickBefore);
				check("details they got from you come back as yours", ledger.byName("Helpful_Hana").details.stream()
						.filter(d -> d.text.startsWith("Base near 1200")).allMatch(Profile.Detail::mine));
			}
			case 786 -> {
				screenshot(client, "23_after_import");
				check("import can be undone", screen(client).testClick("undo"));
			}
			case 790 -> {
				Profile steve = ledger.byName("Steve_Builder");
				check("undo removes what was imported", ledger.byName("Sneaky_Sam") == null && steve.verdict == steveBefore
						&& steve.details.stream().noneMatch(d -> d.text.equals("Builds the spawn highway")));
				client.setScreen(new ShopScreen().withSharePanel(true));
			}
			case 796 -> {
				screen(client).testLoadShare(friendCode);
				screen(client).testPanelClick("share-theirs");
				screen(client).testPanelClick("share-import");
				check("\"use theirs\" switches lists you disagree on", rickBefore == Profile.Verdict.NONE
						|| ledger.byName("RandomRick").verdict == Profile.Verdict.NICE);
			}
			case 800 -> screen(client).testClick("undo");
			case 804 -> {
				check("undo puts your list back", ledger.byName("RandomRick").verdict == rickBefore);
				screen(client).testClick("open-share");
				screen(client).testPanelClick("share-receive");
				screen(client).testLoadShare("hello there");
				check("a bad code says what's wrong instead of importing", screen(client).testSharing() && screen(client).testImportCount() == -1);
			}
			case 810 -> {
				screenshot(client, "24_share_bad_code");
				client.setScreen(new ShopScreen(ShopScreen.Tab.ALL, null));
			}
			// ---- Look Into ----
			case 814 -> {
				// Rick is on another list already, so the first click asks and the second moves him.
				check("cards have a Look Into button", screen(client).testClick("look:RandomRick"));
				if (ledger.byName("RandomRick").verdict != Profile.Verdict.LOOK) screen(client).testClick("look:RandomRick");
			}
			case 817 -> {
				Profile rick = ledger.byName("RandomRick");
				check("Look Into puts them on the Look Into list", rick.verdict == Profile.Verdict.LOOK);
				check("Look Into players get a ? on their nametag", Marks.decorate(rick.id, Text.literal(rick.name)).getString().startsWith("? "));
				check("Look Into players' chat is logged", NNConfig.INSTANCE.chatLog && ledger.byName("RandomRick").verdict != Profile.Verdict.NONE);
				screen(client).testClick("tab:LOOK");
			}
			case 820 -> {
				check("the Look Into tab lists them (" + screen(client).testResultCount() + ")", screen(client).testResultCount() >= 1);
				screenshot(client, "25_look_into");
				client.setScreen(new ShopScreen(null, ledger.byName("RandomRick")));
			}
			case 823 -> check("moving them asks first: clicking Naughty leaves them on Look Into", screen(client).testClick("page-naughty")
					&& ledger.byName("RandomRick").verdict == Profile.Verdict.LOOK);
			case 824 -> check("and Yes moves them", screen(client).testClick("confirm") && ledger.byName("RandomRick").verdict == Profile.Verdict.NAUGHTY);
			case 825 -> {
				check("undo puts them back on Look Into", screen(client).testClick("undo") && ledger.byName("RandomRick").verdict == Profile.Verdict.LOOK);
				screen(client).testClick("page-look");
				check("the player page has a Look Into button that toggles", screen(client).testClick("page-look")
						&& ledger.byName("RandomRick").verdict == Profile.Verdict.NONE);
			}
			case 827 -> {
				screen(client).testClick("page-look");
				check("and back on", ledger.byName("RandomRick").verdict == Profile.Verdict.LOOK);
			}
			// ---- Groups ----
			case 829 -> check("+ Add to a group opens the group panel", screen(client).testClick("group-add") && screen(client).testGrouping());
			case 832 -> {
				screenshot(client, "26_group_panel");
				screen(client).testGroupText("SCG", "RandomRick, xX_Toxic_Xx, Sneaky_Sid");
				screen(client).testPanelClick("group-save");
				Profile sid = ledger.byName("Sneaky_Sid");
				check("a group is made with everyone typed", !screen(client).testGrouping() && ledger.members("SCG").size() == 3
						&& ledger.byName("RandomRick").inGroup("SCG") && sid != null && sid.inGroup("scg"));
				check("group tags show on nametags", Marks.decorate(ledger.byName("RandomRick").id, Text.literal("RandomRick")).getString().contains("[SCG]"));
			}
			case 836 -> screenshot(client, "27_player_groups");
			case 838 -> client.setScreen(new ShopScreen(ShopScreen.Tab.GROUPS, null));
			case 841 -> {
				screenshot(client, "28_groups");
				check("the Groups tab shows the group", screen(client).testClick("group:SCG"));
			}
			case 844 -> {
				check("a group's page shows its members as cards (" + screen(client).testResultCount() + ")",
						"SCG".equals(screen(client).testOpenGroup()) && screen(client).testResultCount() == 3);
				screenshot(client, "29_group_page");
				screen(client).testClick("group-rename");
			}
			case 847 -> {
				screen(client).testGroupText("SCG Crew", "");
				screen(client).testPanelClick("group-save");
				check("groups can be renamed", ledger.groupName("SCG Crew") != null && ledger.groupName("SCG") == null
						&& ledger.byName("xX_Toxic_Xx").inGroup("SCG Crew") && "SCG Crew".equals(screen(client).testOpenGroup()));
			}
			case 850 -> screen(client).testClick("undo");
			case 853 -> {
				check("and the rename undone", ledger.groupName("SCG") != null && ledger.groupName("SCG Crew") == null);
				client.setScreen(new ShopScreen().withAddPanel(Profile.Verdict.LOOK));
			}
			case 856 -> {
				screenshot(client, "30_add_panel_look");
				screen(client).testAddText("Lurking_Larry", "");
				screen(client).testAddGroup("scg");
				screen(client).testPanelClick("add-to-look");
				Profile larry = ledger.byName("Lurking_Larry");
				check("the Add panel adds to Look Into and a group at once", larry != null && larry.verdict == Profile.Verdict.LOOK
						&& larry.inGroup("SCG") && larry.groups.get(0).equals("SCG"));
			}
			case 859 -> {
				Profile rick = ledger.byName("RandomRick");
				Sharing.Pack pack = Sharing.read(Sharing.code(Sharing.pack(List.of(rick), false, "Me", client.player.getUuid(), ledger.key())));
				check("groups and Look Into go along when sharing", pack.players.get(0).groups.contains("SCG")
						&& pack.players.get(0).verdict == Profile.Verdict.LOOK);
				client.setScreen(new ShopScreen(null, rick));
			}
			case 862 -> {
				int before = ledger.members("SCG").size();
				screen(client).testClick("group-leave:SCG");
				check("leaving a group from the player page", !ledger.byName("RandomRick").inGroup("SCG") && ledger.members("SCG").size() == before - 1);
			}
			case 865 -> {
				screen(client).testClick("undo");
				check("and undo puts them back", ledger.byName("RandomRick").inGroup("SCG"));
			}
			// ---- Chat tab ----
			case 868 -> {
				mod.watcher.onGameMessage(client, Text.literal("<RandomRick> anyone want to trade? got 2 stacks of obby"));
				mod.watcher.onGameMessage(client, Text.literal("<Helpful_Hana> sure, meet at spawn"));
				mod.watcher.onGameMessage(client, Text.literal("<xX_Toxic_Xx> ez, you'll never find our base"));
				mod.watcher.onGameMessage(client, Text.literal("<OldFriend> hana be careful, Rick is with SCG"));
				client.setScreen(new ShopScreen(ShopScreen.Tab.CHAT, null));
			}
			case 872 -> {
				check("the Chat tab shows what's been said (" + screen(client).testChatRows() + ")", screen(client).testChatRows() >= 4);
				screenshot(client, "31_chat_tab");
				chatAll = screen(client).testChatRows();
				screen(client).testClick("chat-filter:LOOK");
			}
			case 875 -> {
				int look = screen(client).testChatRows();
				check("the Look Into filter shows only their lines (" + look + " of " + chatAll + ")", look >= 1 && look < chatAll);
				screen(client).testClick("chat-filter:ALL");
				screen(client).testClick("chat-group:SCG");
			}
			case 878 -> {
				int group = screen(client).testChatRows();
				check("a group filter shows only the group's lines (" + group + ")", group >= 2 && group < chatAll);
				screenshot(client, "32_chat_group");
				screen(client).testClick("chat-all");
			}
			case 881 -> {
				screen(client).testChatText("hello from the chat tab");
				check("the chat box sends", screen(client).testClick("chat-send"));
			}
			case 890 -> {
				boolean sent = mod.chatLogger.feed().stream().anyMatch(l -> l.me && l.text.equals("hello from the chat tab"));
				check("what you send shows up as yours", sent);
				check("Copy chat log copies what the Chat tab shows", screen(client).testClick("chat-copy")
						&& client.keyboard.getClipboard().startsWith("Naughty or Nice chat log")
						&& client.keyboard.getClipboard().contains("<You> hello from the chat tab"));
				screenshot(client, "33_chat_sent");
				screen(client).testClick("theme");
			}
			case 894 -> {
				screenshot(client, "34_chat_other_theme");
				screen(client).testClick("theme");
				// The three sizes are different on common screens.
				int[][] windows = {{1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {2560, 1440}, {3440, 1440}, {3840, 2160}, {854, 480}};
				boolean distinct = true;
				StringBuilder sizes = new StringBuilder();
				for (int[] wh : windows) {
					float a = ShopScreen.pixelsPerUnit(wh[0], wh[1], 0), b = ShopScreen.pixelsPerUnit(wh[0], wh[1], 1), c = ShopScreen.pixelsPerUnit(wh[0], wh[1], 2);
					distinct &= a < b && b < c;
					sizes.append(wh[0]).append('x').append(wh[1]).append(": ").append((int) (wh[0] / a)).append('/').append((int) (wh[0] / b)).append('/')
							.append((int) (wh[0] / c)).append(" wide (").append((int) (wh[1] / c)).append(" high at Large); ");
				}
				log("Screen sizes: " + sizes);
				check("Small, Medium and Large differ on every common window size", distinct);
			}
			// ---- Chat check ----
			case 900 -> {
				client.setScreen(null);
				Diagnostics d = mod.diagnostics;
				int unknownBefore = d.unknown().size();
				mod.watcher.onGameMessage(client, Text.literal("[Server] Restarting in 5 minutes"));
				mod.watcher.onGameMessage(client, Text.literal("[Server] Restarting in 1 minutes"));
				check("lines not understood are grouped by shape (" + d.unknown().size() + ")", d.unknown().size() == unknownBefore + 1
						&& d.unknown().stream().anyMatch(sh -> sh.shape.equals("[Server] Restarting in # minutes") && sh.count == 2));
				int kills = d.count(Diagnostics.Kind.KILL);
				Profile toxic = ledger.byName("xX_Toxic_Xx");
				int toxicKills = toxic.count(Profile.Kind.KILLED_PLAYER);
				mod.watcher.onGameMessage(client, Text.literal(GRIEFER + " was slain by xX_Toxic_Xx using [Diamond Sword]"));
				check("a plain-text death message is read as a kill", d.count(Diagnostics.Kind.KILL) == kills + 1
						&& toxic.count(Profile.Kind.KILLED_PLAYER) == toxicKills + 1);
				int joins = d.count(Diagnostics.Kind.JOIN_LEAVE);
				mod.watcher.onGameMessage(client, Text.translatable("multiplayer.player.joined.renamed", Text.literal(GRIEFER), Text.literal("OldGary")));
				mod.watcher.onGameMessage(client, Text.literal("Helpful_Hana left the game"));
				check("joins (also renamed ones) and plain-text leaves are understood", d.count(Diagnostics.Kind.JOIN_LEAVE) == joins + 2);
				check("the report lists what wasn't understood", d.report(ledger.key()).contains("[Server] Restarting in # minutes"));
				// From a real report (constantiam.net, 2026-10-23).
				int ignored = d.count(Diagnostics.Kind.IGNORED), unknown = d.count(Diagnostics.Kind.UNKNOWN);
				mod.watcher.onGameMessage(client, Text.literal("---- ----"));
				check("decoration lines are server messages, not 'not understood'", d.count(Diagnostics.Kind.IGNORED) == ignored + 1
						&& d.count(Diagnostics.Kind.UNKNOWN) == unknown);
				int deaths = d.count(Diagnostics.Kind.DEATH);
				mod.watcher.onGameMessage(client, Text.literal(GRIEFER + " became epstein'd"));
				check("a joke death message (\"became ...'d\") is a death", d.count(Diagnostics.Kind.DEATH) == deaths + 1);
				Profile rick = ledger.byName("RandomRick");
				int abuse = rick.count(Profile.Kind.ABUSE_TALK);
				mod.watcher.onGameMessage(client, Text.literal("<RandomRick> babies are plap meat"));
				mod.watcher.onGameMessage(client, Text.literal("<RandomRick> again, sexualizing children is gross"));
				check("talk of abusing children counts against them, with their words", rick.count(Profile.Kind.ABUSE_TALK) == abuse + 1
						&& rick.actions.stream().anyMatch(a -> a.kind == Profile.Kind.ABUSE_TALK && a.detail.equals("babies are plap meat")));
				check("the report compares the two chunk readings", d.report(ledger.key()).contains("== Chunk reading on this server =="));
				check("a kick sent while joining isn't called a dropped connection",
						!Disconnects.looksLikeDrop("you were kicked from the server. please wait before reconnecting.") && Disconnects.looksLikeDrop("timed out"));
				client.setScreen(new ShopScreen().withChatCheck());
			}
			case 906 -> screenshot(client, "35_chat_check");
			case 908 -> {
				check("the chat check copies its report", screen(client).testPanelClick("check-copy")
						&& client.keyboard.getClipboard().contains("Naughty or Nice chat check"));
				screen(client).testPanelClick("check-done");
			}
			case 911 -> {
				screenshot(client, "36_settings_new");
				// ---- Alerts in chat ----
				NNConfig.INSTANCE.alertWords = new java.util.ArrayList<>(List.of("{me}", "stash"));
				mod.watcher.onGameMessage(client, Text.literal("<RandomRick> anyone know where the stash is"));
				Profile.Line last = mod.chatLogger.feed().get(mod.chatLogger.feed().size() - 1);
				check("an alert word in chat is flagged (" + last.alert + ")", last.alert != null && last.alert.contains("stash"));
				mod.watcher.onGameMessage(client, Text.literal("<RandomRick> hey " + client.player.getGameProfile().name() + " come here"));
				last = mod.chatLogger.feed().get(mod.chatLogger.feed().size() - 1);
				check("your name in chat is flagged", "said your name".equals(last.alert));
				mod.watcher.onGameMessage(client, Text.literal("<RandomRick> nothing to see"));
				last = mod.chatLogger.feed().get(mod.chatLogger.feed().size() - 1);
				check("ordinary chat isn't flagged", last.alert == null);
				check("coordinates are matched to a base, in either dimension", new BaseInsuranceBridge.BaseArea("Home", "minecraft:overworld", 1000, 1100, -2000, -1900)
						.distance(1050, -1950) == 0 && new BaseInsuranceBridge.BaseArea("Home", "minecraft:overworld", 1000, 1100, -2000, -1900).distance(1300, -1950) == 200);
				client.setScreen(new ShopScreen(ShopScreen.Tab.CHAT, null));
			}
			case 913 -> {
				// Everything a player said is on their page, listed or not: read from the all-chat files.
				mod.chatLogger.saveFeed(true);
				mod.chatLogger.everything(ledger.byName("Steve_Builder"));
			}
			case 916 -> {
				Profile steve = ledger.byName("Steve_Builder");
				List<Profile.Line> said = mod.chatLogger.everything(steve);
				check("an unlisted player's page has everything they said (" + (said == null ? "still reading" : said.size() + " lines") + ")",
						steve.verdict == Profile.Verdict.NONE && said != null && said.stream().anyMatch(l -> l.them && l.text.equals("anyone selling diamonds?")));
				screenshot(client, "37_chat_alerts");
			}
			// ---- Sightings and evidence ----
			case 918 -> {
				Profile gary = ledger.get(id(GRIEFER));
				Profile.Sighting sg = gary.lastSighting();
				check("seeing a player notes where (" + gary.sightings.size() + " sightings)", sg != null && sg.dimension.equals("minecraft:overworld"));
				check("...and their gear (" + (sg == null ? "" : sg.gear) + ")", sg != null && sg.gear.contains("elytra") && sg.gear.contains("End Crystal")
						&& sg.gear.contains("Netherite"));
				check("being killed took a screenshot (" + gary.evidence.size() + ")", !gary.evidence.isEmpty()
						&& EvidenceShots.file(client, gary.evidence.get(0)).exists());
				gary.hours = new int[24];
				for (int h = 18; h < 23; h++) gary.hours[h] = 40;
				gary.hours[3] = 5;
				check("when they're usually on (" + gary.usuallyOn() + ")", "6pm to 11pm".equals(gary.usuallyOn()));
				client.setScreen(new ShopScreen(null, gary));
			}
			case 921 -> check("the Where & when section opens", screen(client).testClick("section:SEEN"));
			case 925 -> screenshot(client, "38_where_and_when");
			// ---- Seen together ----
			case 928 -> {
				Links links = ledger.links;
				UUID a = id("Crew_Carl"), b = id("Crew_Cody"), c = id("Main_Max"), alt = id("Alt_Al");
				ledger.getOrCreate(a, "Crew_Carl");
				ledger.getOrCreate(b, "Crew_Cody");
				ledger.getOrCreate(c, "Main_Max");
				ledger.getOrCreate(alt, "Alt_Al");
				long t = System.currentTimeMillis() - 100_000_000L;
				for (int i = 0; i < 3; i++) {
					t += 600_000;
					links.joined(a, t);
					links.joined(b, t + 4000);
					t += 600_000;
					links.left(a, t);
					links.left(b, t + 2000);
					t += 600_000;
					links.left(c, t);
					links.joined(alt, t + 5000);
					t += 600_000;
					links.left(alt, t);
					links.joined(c, t + 4000);
				}
				for (int i = 0; i < 300; i++) {
					links.seen(a);
					links.seen(b);
					links.near(a, b, t);
				}
				// A busy server: 60 strangers logging on and off at random, a few seconds apart, and standing about together.
				java.util.Random random = new java.util.Random(7);
				List<UUID> strangers = new java.util.ArrayList<>();
				for (int i = 0; i < 60; i++) strangers.add(id("Stranger_" + i));
				for (int i = 0; i < 2000; i++) {
					t += 2000 + random.nextInt(6000);
					UUID who = strangers.get(random.nextInt(strangers.size()));
					if (random.nextBoolean()) links.joined(who, t);
					else links.left(who, t);
				}
				for (int s = 0; s < 600; s++) {
					UUID x = strangers.get(random.nextInt(10)), y = strangers.get(10 + random.nextInt(10));
					links.seen(x);
					links.seen(y);
					if (s % 10 == 0) links.near(x, y, t);
				}
				int falseLinks = 0;
				for (UUID st : strangers) falseLinks += links.of(st).size();
				check("strangers on a busy server aren't linked (" + falseLinks + " false links)", falseLinks == 0);
				check("logging on and off together links players (" + links.describe(links.get(a, b), a) + ")", links.get(a, b) != null
						&& links.get(a, b).joins == 3 && links.get(a, b).leaves == 3 && links.of(a).size() == 1);
				Links.Link turns = links.get(c, alt);
				check("taking turns looks like an alt (" + (turns == null ? "no link" : links.describe(turns, c)) + ")", turns != null && links.looksLikeAlt(turns));
				links.minute(java.util.Set.of(c, alt));
				check("...but not once they're both on at the same time", !links.looksLikeAlt(turns));
				turns.minutesBothOn = 0;
				client.setScreen(new ShopScreen(null, ledger.get(a)));
			}
			case 931 -> check("the Seen with section opens", screen(client).testClick("section:LINKS"));
			case 935 -> {
				screenshot(client, "39_seen_with");
				client.setScreen(new ShopScreen(ShopScreen.Tab.GROUPS, null));
			}
			case 939 -> {
				screenshot(client, "40_suggested_groups");
				check("the Groups tab suggests the crew as a group", screen(client).testClick("suggested-make:Crew_Carl") && screen(client).testGrouping());
				screen(client).testGroupText("Carl crew", null);
			}
			case 942 -> {
				screenshot(client, "41_suggested_panel");
				check("saving the suggestion makes the group", screen(client).testPanelClick("group-save")
						&& ledger.get(id("Crew_Carl")).inGroup("Carl crew") && ledger.get(id("Crew_Cody")).inGroup("Carl crew"));
			}
			case 945 -> {
				check("a group is no longer suggested once made", screen(client).testLabels().indexOf("suggested-make:Crew_Carl") < 0);
				screen(client).testClick("group:SCG");
			}
			case 947 -> check("group alerts can be turned on", screen(client).testClick("group-alerts") && ledger.alertsFor("SCG"));
			case 949 -> {
				screenshot(client, "42_group_alerts");
				client.setScreen(null);
				// ---- Name history ----
				Profile rick = ledger.byName("RandomRick");
				ledger.getOrCreate(rick.id, "RenamedRick");
				check("a new name keeps the old one", rick.name.equals("RenamedRick") && rick.pastNames.contains("RandomRick")
						&& ledger.byPastName("RandomRick") == rick);
				client.setScreen(new ShopScreen(ShopScreen.Tab.ALL, null));
			}
			case 952 -> screen(client).testSearch("randomrick");
			case 955 -> {
				check("searching an old name finds them (" + screen(client).testResultCount() + ")", screen(client).testResultCount() == 1);
				screenshot(client, "43_search_old_name");
				screen(client).testSearch("");
				client.setScreen(null);
				ledger.getOrCreate(ledger.byName("RenamedRick").id, "RandomRick");
				// ---- A friend shares again: their changes replace what they sent before ----
				Sharing.Pack pack = friendPack(client, ledger);
				pack.created = System.currentTimeMillis();
				Sharing.apply(pack, Sharing.plan(pack, ledger, client.player.getUuid(), client.getSession().getUsername(), true, false), ledger,
						client.getSession().getUsername());
				Sharing.Player steve = pack.players.stream().filter(pl -> pl.name.equals("Steve_Builder")).findFirst().orElseThrow();
				steve.details.clear();
				steve.details.add(new Profile.Detail("Builds the nether highway", "Friend_Fran", false, pack.created));
				steve.verdict = Profile.Verdict.NAUGHTY;
				List<Sharing.Row> rows = Sharing.plan(pack, ledger, client.player.getUuid(), client.getSession().getUsername(), true, false);
				Sharing.Row row = rows.stream().filter(r -> r.in.name.equals("Steve_Builder")).findFirst().orElseThrow();
				check("sharing again notices changed details (" + row.goneDetails + ")", row.goneDetails == 1 && row.viewChanged);
				Sharing.apply(pack, rows, ledger, client.getSession().getUsername());
				Profile s2 = ledger.byName("Steve_Builder");
				check("...and replaces them, noting the friend's list", s2.details.stream().anyMatch(dd -> dd.text.equals("Builds the nether highway"))
						&& s2.details.stream().noneMatch(dd -> dd.text.equals("Builds the spawn highway"))
						&& s2.friendView("Friend_Fran") != null && s2.friendView("Friend_Fran").verdict == Profile.Verdict.NAUGHTY);
			}
			// ---- The Chat tab between sessions, and servers with addresses spelled differently ----
			case 958 -> {
				mod.chatLogger.saveFeed(true);
				int lines = mod.chatLogger.feed().size();
				String key = ledger.key();
				ledger.switchTo(null);
				ledger.switchTo(key);
				check("the Chat tab keeps its lines after leaving (" + mod.chatLogger.feed().size() + " of " + lines + ")",
						mod.chatLogger.feed().size() == lines && mod.chatLogger.feed().stream().anyMatch(l -> l.text.equals("hello from the chat tab")));
				check("server addresses are tidied", Ledger.normalizeAddress(" Constantiam.NET.:25565 ").equals("constantiam.net")
						&& Ledger.normalizeAddress("play.example.com:25566").equals("play.example.com:25566"));
				java.nio.file.Path dir = NNConfig.dir().resolve("lists");
				try {
					java.nio.file.Files.createDirectories(dir);
					cleanTestServer(dir);
					java.nio.file.Files.writeString(dir.resolve("server_Test.Example.net_25565.json"), "[{\"id\":\"" + id("Legacy_Larry")
							+ "\",\"name\":\"Legacy_Larry\",\"verdict\":\"NAUGHTY\",\"lastSeen\":" + System.currentTimeMillis() + "}]");
					java.nio.file.Files.writeString(dir.resolve("server_test.example.net.json"), "[{\"id\":\"" + id("Legacy_Lou")
							+ "\",\"name\":\"Legacy_Lou\",\"verdict\":\"NICE\",\"lastSeen\":" + System.currentTimeMillis() + "}]");
				} catch (java.io.IOException e) {
					log("could not write test lists: " + e);
				}
				ledger.switchTo("server:test.example.net");
				check("lists saved under other spellings of the address are brought together",
						ledger.byName("Legacy_Larry") != null && ledger.byName("Legacy_Larry").verdict == Profile.Verdict.NAUGHTY
								&& ledger.byName("Legacy_Lou") != null && ledger.byName("Legacy_Lou").verdict == Profile.Verdict.NICE);
				ledger.switchTo(key);
				check("...and saved under the tidy name", java.nio.file.Files.exists(dir.resolve("server_test.example.net.json")));
				cleanTestServer(dir);
				mod.watcher.joinSummary(client);
			}
			case 962 -> screenshot(client, "44_join_summary");
			// ---- Friends and their circles ----
			case 964 -> {
				NNConfig.INSTANCE.circles = Friends.defaultCircles();
				ledger.getOrCreate(id("Friend_Fred"), "Friend_Fred");
				client.setScreen(new ShopScreen(ShopScreen.Tab.ALL, null));
			}
			case 965 -> screen(client).testSearch("Helpful_Hana");
			case 966 -> {
				Profile hana = ledger.byName("Helpful_Hana");
				// She's on the Nice list: moving her asks first.
				boolean clicked = screen(client).testClick("star:Helpful_Hana");
				check("moving her to Friends asks first", clicked && hana.verdict == Profile.Verdict.NICE);
				screen(client).testClick("star:Helpful_Hana");
				check("the star on a card makes them a friend", clicked
						&& hana.verdict == Profile.Verdict.FRIEND && Friends.of(hana) == Friends.circles().get(0));
				check("friends get a star on their nametag", Marks.decorate(hana.id, Text.literal("Helpful_Hana")).getString().startsWith("★ "));
			}
			case 968 -> check("the star on a friend opens the circle picker", screen(client).testClick("star:Helpful_Hana") && screen(client).testCircleMenu());
			case 970 -> screenshot(client, "45_circle_picker");
			case 972 -> {
				screen(client).testSearch("");
				check("picking a circle moves them to it", screen(client).testMenuClick("circle-pick:Base mates")
						&& Friends.circleName(ledger.byName("Helpful_Hana")).equals("Base mates") && !screen(client).testCircleMenu());
				client.setScreen(new ShopScreen(ShopScreen.Tab.FRIENDS, null));
			}
			case 974 -> {
				check("the Friends tab lists friends (" + screen(client).testResultCount() + ")", screen(client).testResultCount() == 1);
				screen(client).testClick("circle:Close friends");
			}
			case 976 -> {
				check("a circle shows just its friends", screen(client).testResultCount() == 0);
				screen(client).testClick("circle-all");
			}
			case 978 -> {
				screenshot(client, "46_friends_tab");
				client.setScreen(new ShopScreen(null, ledger.byName("Helpful_Hana")));
			}
			case 980 -> {
				// From another list it asks first, so this is two clicks.
				screen(client).testClick("page-circle:Allies");
				if (ledger.byName("Helpful_Hana").verdict != Profile.Verdict.FRIEND) screen(client).testClick("page-circle:Allies");
				check("the player page picks their circle", Friends.circleName(ledger.byName("Helpful_Hana")).equals("Allies"));
			}
			case 982 -> {
				screenshot(client, "47_friend_page");
				client.setScreen(new ShopScreen().withAddPanel(Profile.Verdict.FRIEND));
			}
			case 984 -> {
				screen(client).testAddText("Friend_Fred", "");
				check("the Add panel has a circle picker", screen(client).testPanelClick("add-circle:Base mates"));
			}
			case 986 -> {
				screenshot(client, "48_add_friend");
				check("...and adds friends to that circle", screen(client).testPanelClick("add-to-friends")
						&& ledger.byName("Friend_Fred").verdict == Profile.Verdict.FRIEND && Friends.circleName(ledger.byName("Friend_Fred")).equals("Base mates"));
				client.setScreen(null);
				// Sharing friends: they go out as Nice (older versions understand that), marked as the sender's friend.
				Sharing.Pack pack = Sharing.pack(List.of(ledger.byName("Helpful_Hana")), false, "Me", client.player.getUuid(), ledger.key());
				Sharing.Pack back = Sharing.read(Sharing.code(pack));
				check("a friend is shared as Nice, marked as a friend", back.players.get(0).verdict == Profile.Verdict.NICE && back.players.get(0).friend);
				Sharing.Pack old = Sharing.read("{\"format\":\"naughtyornice-share\",\"version\":1,\"from\":\"Friend_Fran\",\"players\":[{\"id\":\""
						+ id("Friend_Fred") + "\",\"name\":\"Friend_Fred\",\"verdict\":\"NAUGHTY\"}]}");
				Sharing.Row row = Sharing.plan(old, ledger, client.player.getUuid(), "Me", true, true).get(0);
				check("a friend's say never takes your friends off your Friends", row.result == Profile.Verdict.FRIEND && row.outcome != Sharing.Outcome.CONFLICT);
				client.setScreen(new ShopScreen(ShopScreen.Tab.SETTINGS, null));
			}
			case 988 -> {
				screen(client).testCircleText("Old crew");
				check("Settings makes new circles", screen(client).testClick("circle-save") && Friends.byName("Old crew") != null);
			}
			case 990 -> {
				check("circles can be renamed", screen(client).testClick("circle-rename:Old crew"));
				screen(client).testCircleText("Discord crew");
				check("...to a new name", screen(client).testClick("circle-save") && Friends.byName("Discord crew") != null && Friends.byName("Old crew") == null);
			}
			case 992 -> check("a circle can be set to ping on joins", screen(client).testClick("circle-alert:Discord crew") && Friends.byName("Discord crew").joinAlert);
			case 993 -> {
				screen(client).testClick("edit-circles");
				screenshot(client, "49_settings_circles");
				Friends.Circle crew = Friends.byName("Discord crew");
				check("circles can be removed", screen(client).testClick("circle-remove:Discord crew") && Friends.byName("Discord crew") == null);
				check("...and the last one can't", crew != null && Friends.circles().size() == 3);
			}
			// ---- Disconnects: who did it ----
			case 996 -> {
				Disconnects.Entry e = new Disconnects.Entry();
				Disconnects.classify(e, new StackTraceElement[] {
						new StackTraceElement("net.minecraft.network.ClientConnection", "disconnect", null, 1),
						new StackTraceElement("net.minecraft.client.network.ClientCommonNetworkHandler", "onDisconnect", null, 1),
						new StackTraceElement("meteordevelopment.meteorclient.systems.modules.misc.AutoLog", "onTick", null, 1),
						new StackTraceElement("net.minecraft.client.MinecraftClient", "tick", null, 1)});
				check("a mod pretending the server kicked you is caught (" + e.kind + ", " + e.culprit + ")", e.kind == Disconnects.Kind.MOD
						&& e.culprit.startsWith("meteordevelopment"));
				Disconnects.Entry server = new Disconnects.Entry();
				Disconnects.classify(server, new StackTraceElement[] {
						new StackTraceElement("net.minecraft.network.ClientConnection", "disconnect", null, 1),
						new StackTraceElement("net.minecraft.client.network.ClientCommonNetworkHandler", "onDisconnect", null, 1),
						new StackTraceElement("net.minecraft.network.NetworkThreadUtils", "method_11072", null, 1),
						new StackTraceElement("net.minecraft.client.MinecraftClient", "tick", null, 1)});
				check("a real kick is the server's (" + server.kind + ")", server.kind == Disconnects.Kind.SERVER);
				Disconnects.Entry mixin = new Disconnects.Entry();
				Disconnects.classify(mixin, new StackTraceElement[] {
						new StackTraceElement("net.minecraft.network.ClientConnection", "disconnect", null, 1),
						new StackTraceElement("net.minecraft.client.MinecraftClient", "handler$zza000$autolog$onTick", null, 1),
						new StackTraceElement("net.minecraft.client.MinecraftClient", "tick", null, 1)});
				check("a mod's code inside the game is caught too (" + mixin.culprit + ")", mixin.kind == Disconnects.Kind.MOD && mixin.culprit.equals("autolog"));
				e.reason = "[AutoLog] Test_Tom came within 20 blocks";
				Disconnects.Nearby tom = new Disconnects.Nearby();
				tom.name = "Test_Tom";
				tom.distance = 18;
				tom.list = "Nice";
				e.nearby.add(tom);
				check("the player named in the reason is picked out", Disconnects.namedInReason(e) == tom && Disconnects.cause(e).contains("Test_Tom"));
				Safe.run("a self-test error", () -> {
					throw new IllegalStateException("on purpose");
				});
				check("errors in hooks are caught, not passed on", Safe.caught().stream().anyMatch(c -> c.where.equals("a self-test error")));
			}
			// ---- Share links ----
			case 998 -> client.setScreen(new ShopScreen().withSharePanel(false));
			case 1000 -> check("Get share link starts an upload", screen(client).testPanelClick("share-link"));
			case 1001, 1003, 1005, 1007, 1009, 1011, 1013, 1015, 1017, 1019, 1021, 1023 -> {
				// Waiting for the upload (it's on the internet).
				if (screen(client).testShareLink() != null && shareLink == null) {
					shareLink = screen(client).testShareLink();
					log("Share link: " + shareLink + " (" + screen(client).testShareStatus() + ")");
				}
			}
			case 1025 -> {
				// A slow connection can finish just after the last wait.
				if (shareLink == null) shareLink = screen(client).testShareLink();
				check("a share link was made (" + shareLink + " / " + screen(client).testShareStatus() + ")", shareLink != null && shareLink.startsWith("NN2:"));
				check("...and copied", shareLink != null && shareLink.equals(client.keyboard.getClipboard()));
				screenshot(client, "50_share_link");
				if (shareLink != null) {
					try {
						Sharing.Pack pack = CloudShare.download(shareLink).get(40, java.util.concurrent.TimeUnit.SECONDS);
						check("the link downloads and unlocks (" + pack.players.size() + " players)", !pack.players.isEmpty()
								&& pack.players.stream().anyMatch(pl -> pl.name.equals("Friend_Fred") && pl.friend));
					} catch (Exception ex) {
						check("the link downloads and unlocks (" + ex + ")", false);
					}
					String broken = shareLink.substring(0, shareLink.length() - 3) + (shareLink.endsWith("AAA") ? "BBB" : "AAA");
					try {
						CloudShare.download(broken).get(40, java.util.concurrent.TimeUnit.SECONDS);
						check("a link with the wrong key can't be read", false);
					} catch (Exception ex) {
						check("a link with the wrong key can't be read", true);
					}
					check("your own link isn't offered back to you", mod.watcher.offered(shareLink));
					String other = "NN2:bTestKey1.abcdefghijklmnopqrstuv";
					mod.watcher.onGameMessage(client, Text.literal("<Friend_Fran> here's my lists " + other));
					check("a friend's link in chat gets a button", mod.watcher.offered(other));
					client.setScreen(new ShopScreen().withShareLink(shareLink));
				}
			}
			case 1027, 1029, 1031, 1033, 1035, 1037, 1039, 1041 -> {
				// Waiting for the download.
			}
			case 1043 -> {
				check("opening a link shows the import preview", screen(client).testPreview());
				screenshot(client, "51_open_link");
				client.setScreen(null);
			}
			// ---- Server activity ----
			case 1045 -> {
				ServerActivity act = new ServerActivity();
				java.time.LocalDateTime monday = java.time.LocalDateTime.of(2026, 10, 19, 0, 0);
				for (int d = 0; d < 7; d++) {
					for (int hh = 0; hh < 24; hh++) {
						// Busy in the evening, quiet early morning; weekends busier. A few hours never seen.
						if (d == 2 && hh < 6) continue;
						int players = hh >= 18 && hh <= 22 ? 40 + d * 3 : hh >= 3 && hh <= 8 ? 6 : 18;
						for (int m = 0; m < 15; m++) act.sample(players + (m % 5), monday.plusDays(d).plusHours(hh));
					}
				}
				ledger.activity = act;
				int busy = ServerActivity.window(act.byHour(), 3, true);
				int quiet = ServerActivity.window(act.byHour(), 3, false);
				check("the busiest hours are found (" + busy + ")", busy >= 18 && busy <= 20);
				check("...and the quietest (" + quiet + ")", quiet >= 3 && quiet <= 6);
				check("hours not seen enough aren't guessed", act.average(ServerActivity.cell(java.time.DayOfWeek.WEDNESDAY, 2)) < 0);
				check("newer versions are told apart", UpdateCheck.newer("1.10.0", "1.9.2") && !UpdateCheck.newer("1.5.0", "1.5.0")
						&& !UpdateCheck.newer("1.4.9", "1.5.0") && UpdateCheck.newer("1.5.1", "1.5"));
				client.setScreen(new ShopScreen(ShopScreen.Tab.ACTIVITY, null));
			}
			case 1048 -> screenshot(client, "52_activity");
			// ---- Notes about one player: their chat, their logging in and out ----
			case 1050 -> {
				client.setScreen(new ShopScreen(ShopScreen.Tab.LOOK, null));
				screen(client).testSearch("Lurking_Larry");
			}
			case 1052 -> {
				Profile larry = ledger.byName("Lurking_Larry");
				check("Look Into cards switch chat notes on", screen(client).testClick("notify-chat:Lurking_Larry") && larry.notifyChat);
			}
			case 1054 -> {
				Profile larry = ledger.byName("Lurking_Larry");
				check("...and log in/out notes", screen(client).testClick("notify-join:Lurking_Larry") && larry.notifyJoin);
				mod.watcher.onGameMessage(client, Text.literal("<Lurking_Larry> anyone at spawn?"));
				screenshot(client, "53_look_into_notes");
			}
			case 1056 -> {
				client.setScreen(new ShopScreen(null, ledger.byName("Lurking_Larry")));
			}
			case 1058 -> {
				check("the player page has the same switches", screen(client).testClick("page-notify-chat") && !ledger.byName("Lurking_Larry").notifyChat);
				screenshot(client, "54_page_notes");
				client.setScreen(null);
			}
			// ---- Stats from a player in view: a totem pop, walking at a known speed, time online ----
			case 1059 -> {
				log("Griefer pops a totem and walks off at 5 blocks a second");
				IntegratedServer server = client.getServer();
				server.execute(() -> griefer.getEntityWorld().sendEntityStatus(griefer, net.minecraft.entity.EntityStatuses.USE_TOTEM_OF_UNDYING));
				walkTicks = 100;
			}
			case 1064 -> {
				Profile gary = ledger.get(id(GRIEFER));
				check("totem pop counted (" + gary.stats.totemPops + ")", gary.stats.totemPops == 1);
				PlayerEntity popped = client.world.getPlayerByUuid(id(GRIEFER));
				check("totems popped shown on the nametag (" + (popped == null ? "not seen" : popped.getDisplayName().getString()) + ")",
						TotemCounter.pops(id(GRIEFER)) == 1 && popped != null && popped.getDisplayName().getString().endsWith("[1 pop]"));
				Profile rick = ledger.byName("RandomRick");
				mod.watcher.onGameMessage(client, Text.literal("<RandomRick> hey " + GRIEFER + " meet me at 1200 -3400, join discord.gg/abc123 ?"));
				check("who they talk to (" + rick.stats.mentions + ")", rick.stats.mentions.getOrDefault(GRIEFER, 0) == 1 && gary.stats.mentionedBy.getOrDefault("RandomRick", 0) == 1);
				check("coordinates and links they post are kept (" + rick.stats.places.size() + ")", rick.stats.places.size() == 1
						&& rick.stats.places.get(0).x == 1200 && rick.stats.places.get(0).z == -3400 && rick.stats.linksPosted.size() == 1
						&& rick.stats.linksPosted.get(0).text.equals("discord.gg/abc123") && rick.stats.questions == 1);
				Profile hana = ledger.byName("Helpful_Hana");
				Profile alt = ledger.getOrCreate(id("HelpfulHana2"), "HelpfulHana2");
				hana.stats.skin = "abc123skin";
				alt.stats.skin = "abc123skin";
				List<Alts.Suspect> alts = Alts.of(ledger, hana);
				check("a same-skin, same-name account is a possible alt (" + (alts.isEmpty() ? "none" : alts.get(0).score() + " " + alts.get(0).reasons()) + ")",
						!alts.isEmpty() && alts.get(0).other() == alt && alts.get(0).score() >= 60);
				alt.stats.skin = "";
				check("...but a similar name alone isn't enough", Alts.of(ledger, hana).isEmpty());
				Profile bob = ledger.getOrCreate(id("Unrelated_Bob"), "Unrelated_Bob");
				hana.stats.skin = "abc123skin";
				bob.stats.skin = "abc123skin";
				check("...and neither is the same skin alone (popular skins)", Alts.of(ledger, hana).isEmpty());
				bob.stats.skin = "";
				ledger.forget(bob);

				// ---- Intel: amounts aren't places, threats, tone, wither summoners, the coordinate guard ----
				mod.watcher.onGameMessage(client, Text.literal("<RandomRick> I have 1000 diamonds and 200 gold"));
				check("amounts aren't taken for coordinates", rick.stats.places.size() == 1);
				int stashTalk = rick.stats.topics.getOrDefault("STASH", 0);
				mod.watcher.onGameMessage(client, Text.literal("<RandomRick> stash is on the -x highway at 20k nether"));
				PlayerStats.Place hw = rick.stats.places.get(rick.stats.places.size() - 1);
				check("highway distances are places (" + hw.coords() + " " + hw.dimension + ")", hw.x == -20000 && hw.z == 0 && hw.how.equals("highway")
						&& hw.dimension.equals("minecraft:the_nether") && rick.stats.topics.getOrDefault("STASH", 0) == stashTalk + 1);
				String me = client.player.getGameProfile().name();
				check("a threat aimed at you alerts (" + mod.watcher.alertFor(client, "gonna grief " + me + "'s base lol", false) + ")",
						"threatened you".equals(mod.watcher.alertFor(client, "im gonna grief your base " + me, false))
						&& "threatened you".equals(mod.watcher.alertFor(client, "i know where your stash is", true)));
				check("...one aimed at nobody in particular doesn't", mod.watcher.alertFor(client, "we griefed spawn yesterday", false) == null);
				int rudeBefore = hana.count(Profile.Kind.RUDE);
				mod.watcher.onGameMessage(client, Text.literal("<Helpful_Hana> brb taking out the trash"));
				check("ordinary words aren't trash talk", hana.count(Profile.Kind.RUDE) == rudeBefore);
				long t0 = System.currentTimeMillis();
				mod.watcher.summonedWither(rick, t0);
				mod.watcher.worldEvent("wither", "minecraft:overworld", 500, 70, -900, t0 + 11_000);
				PlayerStats.Place w = rick.stats.places.get(rick.stats.places.size() - 1);
				check("a wither's spawn is matched to its summoner", w.how.equals("wither") && w.x == 500 && w.z == -900);
				Profile hanaW = ledger.byName("Helpful_Hana");
				int hanaPlaces = hanaW.stats.places.size();
				mod.watcher.summonedWither(hanaW, t0 + 100_000);
				mod.watcher.summonedWither(rick, t0 + 100_500);
				mod.watcher.worldEvent("wither", "minecraft:overworld", 9000, 70, 9000, t0 + 111_000);
				check("...but not when two players could have done it", hanaW.stats.places.size() == hanaPlaces && rick.stats.places.stream().noneMatch(pl -> pl.x == 9000));
				check("the coordinate guard reads your messages", !CoordGuard.reasons("meet me at 1200 -3400").isEmpty() && CoordGuard.reasons("hello there").isEmpty()
						&& !CoordGuard.reasons("im at " + client.player.getBlockX() + " lol").isEmpty() == (Math.abs(client.player.getBlockX()) >= 500));
				check("...holds a message that gives coordinates away", !CoordGuard.allowChat("base at 1200 -3400") && CoordGuard.allowChat("hello there"));
				hana.stats.skin = "";
				ledger.forget(alt);
			}
			case 1190 -> {
				Profile gary = ledger.get(id(GRIEFER));
				PlayerStats st = gary.stats;
				log("Griefer stats: travelled " + st.travelled + ", top speed " + st.topSpeed + ", in view " + st.inViewMs + "ms, online " + st.onlineMs
						+ "ms, seen " + st.timesSeen + ", closest " + st.closest + ", logins " + st.logins + ", sessions " + st.sessions.size());
				check("distance travelled in view (" + Math.round(st.travelled) + ")", st.travelled >= 15 && st.travelled <= 35);
				check("top speed is the walking speed (" + String.format(java.util.Locale.ROOT, "%.1f", st.topSpeed) + ")", st.topSpeed >= 3.5 && st.topSpeed <= 6.5);
				check("time in view and online counted", st.inViewMs >= 30_000 && st.onlineMs >= 30_000 && st.timesSeen >= 1 && st.closest >= 0 && st.closest < 8);
				check("their login was seen, and one session runs through list reloads (" + st.sessions.size() + ")", st.logins == 1 && st.sessions.size() == 1 && st.sessions.get(0).startSeen
						&& st.longestSessionMs >= 60_000);
				check("best armour and held item noted", st.bestArmour.equals("netherite") && "End Crystal".equals(PlayerStats.top(st.held)));
				client.setScreen(new ShopScreen(null, gary));
			}
			case 1193 -> check("Stats section opens", screen(client).testClick("section:STATS"));
			case 1196 -> {
				String stats = screen(client).testStats();
				log("Stats rows:\n" + stats);
				check("Stats page shows fights, movement and gear", stats.contains("Totems popped: 1") && stats.contains("Best armour: Netherite")
						&& stats.contains("Top speed: ") && stats.contains("Kills: 1 (1 of them you)")
						&& stats.contains("Account: Offline-mode") && stats.contains("Days online: 1"));
				screenshot(client, "55_stats");
			}
			case 1199 -> client.setScreen(new ShopScreen(ShopScreen.Tab.ACTIVITY, null));
			case 1201 -> screen(client).testScroll(10_000);
			case 1203 -> {
				screenshot(client, "56_top_players");
				check("Top players open a player's page", screen(client).testClick("top:2:Griefer_Gary") && screen(client).testSelected() == ledger.get(id(GRIEFER)));
			}
			case 1205 -> client.setScreen(new ShopScreen(ShopScreen.Tab.INTEL, null));
			case 1207 -> {
				check("Intel lists places and topics", screen(client).testClick("intel-filter:PLACES"));
				screenshot(client, "57_intel");
			}
			case 1209 -> {
				log("Griefer logs out in front of the player");
				IntegratedServer server = client.getServer();
				server.execute(() -> server.getPlayerManager().sendToAll(new net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket(List.of(id(GRIEFER)))));
				loggedOutAt = System.currentTimeMillis();
			}
			case 1214 -> {
				Profile gary = ledger.get(id(GRIEFER));
				PlayerStats.Place last = gary.stats.places.isEmpty() ? null : gary.stats.places.get(gary.stats.places.size() - 1);
				// Logouts are noticed when the tab list is next looked at (about once a second): wait for it, up to 5 s.
				if ((last == null || !last.how.equals("logout")) && System.currentTimeMillis() - loggedOutAt < 5000) {
					tick--;
					return;
				}
				check("where they logged out in view is kept (" + (last == null ? "none" : last.how + " " + last.coords()) + ")",
						last != null && last.how.equals("logout") && Math.abs(last.x - (int) Math.floor(griefer.getX())) <= 2);
				check("a logout spot is shown where they went", LogoutSpots.spots().stream().anyMatch(sp -> sp.name().equals(GRIEFER)
						&& Math.abs(sp.x() - griefer.getX()) <= 2));
				screenshot(client, "57b_logout_spot");
				client.setScreen(new ShopScreen(ShopScreen.Tab.SETTINGS, null));
			}
			case 1216 -> screen(client).testScroll(1_000_000);
			case 1218 -> {
				screenshot(client, "58_settings_bottom");
				client.setScreen(null);
			}
			// ---- Chunk trails ----
			case 1219 -> {
				check("a corridor of old land through fresh land is a trail", trailTests());
				log("Pouring water 4 chunks away, in land that had none");
				IntegratedServer server = client.getServer();
				// A chunk 4 away with no liquid in it yet (earlier runs left their water in the saved world).
				BlockPos at = null;
				for (int k = 0; k < 64 && at == null; k++) {
					double angle = k * Math.PI / 32;
					BlockPos p = client.player.getBlockPos().add((int) (Math.cos(angle) * 64), 0, (int) (Math.sin(angle) * 64));
					var chunk = client.world.getChunkManager().getWorldChunk(p.getX() >> 4, p.getZ() >> 4);
					if (chunk != null && ledger.chunkTrails.watching(p.getX() >> 4, p.getZ() >> 4) && chunk.getFluidState(p).isEmpty()
							&& ledger.chunkTrails.state(client.world.getRegistryKey().getValue().toString(), p.getX() >> 4, p.getZ() >> 4) == 0) at = p;
				}
				if (at == null) at = client.player.getBlockPos().add(64, 0, 0);
				log("Pouring at " + at.toShortString() + " (chunk " + (at.getX() >> 4) + ", " + (at.getZ() >> 4) + ")");
				ChunkTrails.debugKey = net.minecraft.util.math.ChunkPos.toLong(at.getX() >> 4, at.getZ() >> 4);
				waterAt = at;
				BlockPos pour = at;
				server.execute(() -> server.getPlayerManager().getPlayerList().get(0).getEntityWorld()
						.setBlockState(pour, net.minecraft.block.Blocks.WATER.getDefaultState()));
			}
			case 1250 -> {
				String dim = client.world.getRegistryKey().getValue().toString();
				int cx = waterAt.getX() >> 4, cz = waterAt.getZ() >> 4;
				check("liquid starting to flow marks the chunk fresh (" + ledger.chunkTrails.state(dim, cx, cz) + ")", ledger.chunkTrails.state(dim, cx, cz) == ChunkTrails.NEW);
				IntegratedServer cleanup = client.getServer();
				int wx = waterAt.getX(), wz = waterAt.getZ();
				cleanup.execute(() -> cleanup.getCommandManager().parseAndExecute(cleanup.getCommandSource(),
						"fill " + (wx - 12) + " -60 " + (wz - 12) + " " + (wx + 12) + " -59 " + (wz + 12) + " air replace water"));
				ChunkTrails probe = new ChunkTrails();
				probe.chunkLoaded(dim, client.world.getChunk(cx, cz), false);
				check("...and arriving with liquid already flowed reads as old", probe.state(dim, cx, cz) == ChunkTrails.OLD);
				ChunkTrails close = new ChunkTrails();
				close.chunkLoaded(dim, client.world.getChunk(cx, cz), ChunkTrails.inReach(client, cx, cz));
				check("...but not when it arrives within your reach (it may have flowed for you)", close.state(dim, cx, cz) == 0);
				// A trail beside the player, for the radar.
				ChunkTrails trails = ledger.chunkTrails;
				int pcx = client.player.getBlockX() >> 4, pcz = client.player.getBlockZ() >> 4;
				for (int i = -40; i <= 40; i++) {
					for (int j = -40; j <= 40; j++) trails.set(dim, pcx + i, pcz + j, Math.abs(j - 6 + i / 4) <= 1 ? ChunkTrails.OLD : ChunkTrails.NEW);
				}
				for (int i = -40; i <= 40; i++) {
					for (int j = -1; j <= 1; j++) unmine(trails, dim, pcx + i, pcz + 6 - i / 4 + j);
				}
				NNConfig.INSTANCE.trailRadar = true;
				client.player.setYaw(-90);
			}
			case 1320 -> {
				String[] say = ledger.chunkTrails.guidance(client.world.getRegistryKey().getValue().toString(), client.player.getX(), client.player.getZ(), client.player.getYaw());
				log("Radar says: " + (say == null ? "nothing" : say[0] + " / " + say[1]));
				check("the radar finds the trail beside you and says how to follow it", say != null && say[0].startsWith("Trail:"));
				screenshot(client, "59_trail_radar");
				client.player.setPitch(55);
			}
			case 1322 -> {
				check("the chunks are drawn in the world (" + ChunkOverlay.shown() + " squares)", ChunkOverlay.shown() > 100);
				screenshot(client, "59b_chunks_in_world");
				client.player.setPitch(0);
			}
			case 1323 -> {
				NNConfig.INSTANCE.trailRadar = false;
				client.setScreen(new ShopScreen(ShopScreen.Tab.INTEL, null));
			}
			case 1326 -> check("the Intel map opens", screen(client).testClick("intel-view:Map"));
			case 1332 -> screenshot(client, "60_intel_map");
			case 1333 -> check("the map switches to heat", screen(client).testClick("map-heat"));
			case 1337 -> screenshot(client, "61_heat_map");
			case 1339 -> {
				client.setScreen(null);
				ledger.chunkTrails.clear();
				// ---- World download: a gold block and a sign beside you, saved, then opened again later ----
				IntegratedServer server = client.getServer();
				BlockPos gold = client.player.getBlockPos().add(2, 0, 0);
				BlockPos sign = client.player.getBlockPos().add(2, 0, 1);
				downloadGold = gold;
				downloadSign = sign;
				server.execute(() -> {
					var world = server.getPlayerManager().getPlayerList().get(0).getEntityWorld();
					world.setBlockState(gold, net.minecraft.block.Blocks.GOLD_BLOCK.getDefaultState());
					world.setBlockState(sign, net.minecraft.block.Blocks.OAK_SIGN.getDefaultState());
					if (world.getBlockEntity(sign) instanceof net.minecraft.block.entity.SignBlockEntity be) {
						be.setText(be.getFrontText().withMessage(0, Text.literal("NN download test")), true);
						be.markDirty();
						world.updateListeners(sign, world.getBlockState(sign), world.getBlockState(sign), 3);
					}
				});
			}
			case 1341 -> check("a download starts when asked", WorldDownload.start(client) == null && WorldDownload.active());
			case 1343 -> {
				WorldDownload.stop(client);
				downloadName = WorldDownload.name();
				check("...and saves what's loaded (" + WorldDownload.chunks() + " chunks)", WorldDownload.chunks() > 20 && !WorldDownload.active());
			}
			// ---- Baritone: its screen, its settings, a trip, a highway roam ----
			case 1344 -> {
				check("Baritone is found", BaritoneBridge.present());
				BaritoneBridge.setBool("allowParkour", true);
				boolean on = Boolean.TRUE.equals(BaritoneBridge.bool("allowParkour"));
				BaritoneBridge.setBool("allowParkour", false);
				check("...its settings can be read and changed", on && Boolean.FALSE.equals(BaritoneBridge.bool("allowParkour")));
				check("a #goto is left to Baritone", CoordGuard.allowChat("#goto 1200 64 -3400"));
				BaritoneBridge.setBool("prefixControl", false);
				check("...but held when Baritone isn't reading chat", !CoordGuard.allowChat("#goto 1200 64 -3400"));
				BaritoneBridge.setBool("prefixControl", true);
				NNConfig.INSTANCE.askBeforeSending = true;
				check("ask before sending holds an ordinary message", !CoordGuard.allowChat("hello there") && !CoordGuard.allowCommand("msg Hana hi")
						&& CoordGuard.allowCommand("nn help"));
				serverHeard.clear();
				check("...and [Send] sends it", CoordGuard.allowChat("hello there") == false && CoordGuard.sendHeld());
				NNConfig.INSTANCE.askBeforeSending = false;
				client.setScreen(new BaritoneScreen());
			}
			case 1347 -> {
				check("...the server got it once you said send", serverHeard.contains("hello there"));
				screenshot(client, "62_baritone_go");
			}
			case 1349 -> ((BaritoneScreen) client.currentScreen).testPage(2);
			case 1352 -> screenshot(client, "63_baritone_highway");
			case 1354 -> {
				// A trip from the screen, no commands typed: fill in x y z and press Go.
				BlockPos to = client.player.getBlockPos().add(0, 0, 40);
				tripFrom = client.player.getBlockPos();
				serverHeard.clear();
				// The griefer is still standing nearby; that pause is right, but here we want to see it walk.
				NNConfig.INSTANCE.tripStopForStrangers = false;
				BaritoneScreen screen = (BaritoneScreen) client.currentScreen;
				screen.testPage(1);
				boolean typed = screen.testType("x", String.valueOf(to.getX())) && screen.testType("y (optional)", String.valueOf(to.getY()))
						&& screen.testType("z", String.valueOf(to.getZ()));
				check("a trip is handed to Baritone from its screen", typed && screen.testPress("Go") && client.currentScreen == null);
			}
			case 1356 -> {
				check("...without anything reaching the server's chat", serverHeard.isEmpty());
				log("Baritone goal: " + BaritoneBridge.goal() + ", pathing " + BaritoneBridge.isPathing());
				check("...with the goal set", BaritoneBridge.goal().contains("z=" + (tripFrom.getZ() + 40)));
			}
			case 1370 -> {
				double moved = Math.sqrt(client.player.getBlockPos().getSquaredDistance(tripFrom));
				log("Moved " + String.format(java.util.Locale.ROOT, "%.1f", moved) + " blocks");
				check("...and it walks there (" + (int) moved + " blocks so far)", moved >= 3);
				BaritoneBridge.run("stop");
				SmartTravel.stop();
				NNConfig.INSTANCE.tripStopForStrangers = true;
				HighwayRoam.axis = HighwayRoam.AXES[0];
				HighwayRoam.elytra = false;
				HighwayRoam.distance = 300;
				HighwayRoam.height = Integer.MIN_VALUE;
				String why = HighwayRoam.start(client);
				check("a highway roam starts (" + (why == null ? HighwayRoam.target() : why) + ")", why == null && HighwayRoam.active());
			}
			case 1385 -> {
				log("Roam goal: " + BaritoneBridge.goal());
				check("...towards a point on the highway", BaritoneBridge.goal().contains(HighwayRoam.target().split(", ")[0]));
				HighwayRoam.stop("test over");
				check("...and stops", !HighwayRoam.active());
				BaritoneBridge.run("stop");
				tp(client, "0.5 -60 0.5");
				// Now every button on the Baritone screen, clicked like a mouse (the main steps wait meanwhile).
				baritoneStep = 1;
			}
			// ---- A real disconnect, by "another mod" (this test): recorded with who did it and who was near ----
			// ---- The event log: the griefer's fight earlier in this test, read back ----
			case 1388 -> {
				java.util.Set<String> players = new java.util.HashSet<>();
				for (Profile p : ledger.all()) players.add(p.name.toLowerCase(java.util.Locale.ROOT));
				players.add(client.player.getGameProfile().name().toLowerCase(java.util.Locale.ROOT));
				EventReport r = new EventReport(EventLog.blackBox(), players);
				String me = client.player.getGameProfile().name();
				log("Event log: " + r.entries.size() + " entries, first hit " + (r.firstHit == null ? "none" : r.firstHit.actor + " -> " + r.firstHit.target + " (" + r.firstHit.detail + ")"));
				check("the fight's first hit is found (the griefer on you)", r.firstHit != null && r.firstHit.actor.equals(GRIEFER) && r.firstHit.target.equals(me));
				check("...with what he held", r.firstHit != null && r.firstHit.detail.contains("End Crystal"));
				check("the kill is in the kill feed", r.kills.stream().anyMatch(k -> k.actor.equals(GRIEFER) && k.target.equals(me)));
				check("totem pops and positions are logged", r.entries.stream().anyMatch(e -> e.type == EventLog.Type.TOTEM && e.actor.equals(GRIEFER))
						&& r.entries.stream().anyMatch(e -> e.type == EventLog.Type.POSITION && e.actor.equals(GRIEFER) && e.located()));
				try {
					java.nio.file.Path saved = EventLog.saveLast(30, "self-test");
					List<EventLog.Entry> back = EventLog.load(saved);
					java.nio.file.Path csv = saved.resolveSibling("self-test.csv");
					EventLog.exportCsv(back, csv);
					check("saving the last 30 minutes keeps every entry (" + back.size() + ")", back.size() >= r.entries.size() - 5 && back.size() > 20);
					check("...and it exports to CSV", java.nio.file.Files.readAllLines(csv).size() == back.size() + 1);
				} catch (java.io.IOException e) {
					check("saving the last 30 minutes (" + e.getMessage() + ")", false);
				}
				client.setScreen(new EventScreen());
			}
			case 1392 -> screenshot(client, "65_events");
			case 1393 -> check("the replay map has the fight's players", client.currentScreen instanceof EventScreen es && es.testReplay() >= 2);
			case 1396 -> screenshot(client, "66_replay");
			case 1397 -> client.setScreen(null);
			case 1398 -> {
				log("Disconnecting on purpose");
				disconnectedAt = System.currentTimeMillis();
				client.getNetworkHandler().getConnection().disconnect(Text.literal("[AutoLog] " + GRIEFER + " came near"));
			}
			default -> {}
		}
	}

	/** Logins all around the clock have no usual time; ones near 8pm do. */
	private static boolean usualTimes() {
		PlayerStats spread = new PlayerStats();
		PlayerStats evening = new PlayerStats();
		for (int h = 0; h < 24; h += 3) {
			spread.logins++;
			spread.clock(true, java.time.LocalTime.of(h, 0));
		}
		for (int m : new int[] {50, 55, 5, 10}) {
			evening.logins++;
			evening.clock(true, java.time.LocalTime.of(m > 30 ? 19 : 20, m));
		}
		return spread.usualTime(true) == null && "20:00".equals(evening.usualTime(true));
	}

	private static BlockPos waterAt;
	private static BlockPos tripFrom;
	/** Where the Baritone button walk-through is (0 when it isn't running). */
	private static int baritoneStep;
	/** What Baritone had said before the every-setting round trip. */
	private static int settingsMark;
	/** When the last Baritone button was pressed, for steps that must wait a while by the clock. */
	private static long baritonePressed;
	/** Where the test wheat was planted (x of the first of three, and z). */
	private static int wheatX, wheatZ;
	private static final List<String> serverHeard = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** For the radar test: lets a chunk be old again, though the test marked it fresh first. */
	private static void unmine(ChunkTrails trails, String dim, int x, int z) {
		trails.forgetMine(dim, x, z);
		trails.set(dim, x, z, ChunkTrails.OLD);
	}

	/** The trail finder on made-up land: what's a trail, what isn't, and which way it leads. */
	private static boolean trailTests() {
		String dim = "test:dim";
		// Fresh land with an old corridor 4 chunks wide running east-west, its east end further from spawn.
		ChunkTrails t = new ChunkTrails();
		for (int x = 60; x <= 140; x++) {
			for (int z = 60; z <= 140; z++) t.set(dim, x, z, z >= 98 && z <= 101 && x >= 70 && x <= 130 ? ChunkTrails.OLD : ChunkTrails.NEW);
		}
		for (int x = 70; x <= 130; x++) {
			for (int z = 98; z <= 101; z++) {
				t.forgetMine(dim, x, z);
				t.set(dim, x, z, ChunkTrails.OLD);
			}
		}
		List<ChunkTrails.Trail> found = t.analyse(dim, 100, 100, 1000, null);
		boolean ok = found.size() == 1;
		if (ok) {
			ChunkTrails.Trail tr = found.get(0);
			int[] far = tr.awayEnd();
			log("Trail: " + tr.x1 + "," + tr.z1 + " to " + tr.x2 + "," + tr.z2 + " width " + tr.width + " away " + tr.away);
			ok = Math.abs(far[0] - (130 * 16 + 8)) <= 16 && tr.length() >= 900 && tr.width >= 3 && tr.width <= 5;
			String[] say = t.guidance(dim, 100 * 16, 99 * 16, -90);
			log("Guidance on it, facing east: " + (say == null ? "none" : say[0] + " / " + say[1]));
			ok &= say != null && say[1].startsWith("Away from spawn: E") && say[1].endsWith("straight on");
		}
		// A big explored square in fresh land: old land, but not a trail.
		ChunkTrails square = new ChunkTrails();
		for (int x = 60; x <= 140; x++) {
			for (int z = 60; z <= 140; z++) {
				boolean old = x >= 85 && x <= 115 && z >= 85 && z <= 115;
				if (!old) square.set(dim, x, z, ChunkTrails.NEW);
				else square.set(dim, x, z, ChunkTrails.OLD);
			}
		}
		boolean squareOk = square.analyse(dim, 100, 100, 1000, null).isEmpty();
		// The edge of explored land (old on one side, fresh on the other): not a trail.
		ChunkTrails edge = new ChunkTrails();
		for (int x = 60; x <= 140; x++) {
			for (int z = 60; z <= 140; z++) {
				if (z < 100) edge.set(dim, x, z, ChunkTrails.NEW);
				else if (z <= 103) edge.set(dim, x, z, ChunkTrails.OLD);
			}
		}
		boolean edgeOk = edge.analyse(dim, 100, 100, 1000, null).isEmpty();
		// Your own path (chunks you generated) is never a trail.
		ChunkTrails own = new ChunkTrails();
		for (int x = 60; x <= 140; x++) {
			for (int z = 60; z <= 140; z++) own.set(dim, x, z, ChunkTrails.NEW);
		}
		boolean ownOk = own.analyse(dim, 100, 100, 1000, null).isEmpty();
		// Saved and loaded again: your chunks, old chunks and trails all come back.
		java.nio.file.Path dir = MinecraftClient.getInstance().runDirectory.toPath().resolve("nn-test-trails");
		java.nio.file.Path trailsFile = dir.resolve("t.trails.json"), chunksFile = dir.resolve("t.chunks.bin");
		t.save(trailsFile, chunksFile, true);
		ChunkTrails back = new ChunkTrails();
		back.load(trailsFile, chunksFile);
		boolean savedOk = back.trails.size() == 1 && back.state(dim, 100, 99) == ChunkTrails.OLD && back.state(dim, 100, 80) == ChunkTrails.NEW
				&& back.state(dim, 10, 10) == 0;
		// Your own path again, flown faster than the server makes land: a strip beside you stayed unknown (its liquid
		// flowed with you right there, or before it was sent). Coming back later it arrives flowed, far from you: still
		// not old, so it's no trail.
		ChunkTrails fast = new ChunkTrails();
		for (int x = 60; x <= 140; x++) {
			for (int z = 60; z <= 140; z++) {
				if (Math.abs(z - 100) > 1) fast.set(dim, x, z, ChunkTrails.NEW);
			}
		}
		for (int x = 60; x <= 140; x += 2) fast.walk(dim, x, 100, 2, k -> true);
		for (int x = 60; x <= 140; x++) {
			for (int z = 99; z <= 101; z++) fast.arrived(dim, net.minecraft.util.math.ChunkPos.toLong(x, z), true, false);
		}
		boolean fastOk = fast.analyse(dim, 100, 100, 1000, null).isEmpty() && fast.state(dim, 100, 100) == 0;
		// Arriving flowed within your reach can't be told; far from you, it's old. And what you were near is saved.
		ChunkTrails reach = new ChunkTrails();
		reach.arrived(dim, net.minecraft.util.math.ChunkPos.toLong(1, 1), true, true);
		reach.walk(dim, 50, 50, 1, k -> true);
		reach.arrived(dim, net.minecraft.util.math.ChunkPos.toLong(50, 51), true, false);
		reach.arrived(dim, net.minecraft.util.math.ChunkPos.toLong(90, 90), true, false);
		reach.save(dir.resolve("r.trails.json"), dir.resolve("r.chunks.bin"), true);
		ChunkTrails reachBack = new ChunkTrails();
		reachBack.load(dir.resolve("r.trails.json"), dir.resolve("r.chunks.bin"));
		reachBack.arrived(dim, net.minecraft.util.math.ChunkPos.toLong(50, 49), true, false);
		boolean reachOk = reach.state(dim, 1, 1) == 0 && reach.state(dim, 50, 51) == 0 && reach.state(dim, 90, 90) == ChunkTrails.OLD
				&& reachBack.state(dim, 50, 49) == 0 && reachBack.state(dim, 90, 90) == ChunkTrails.OLD;
		log("Trail tests: corridor " + ok + ", square " + squareOk + ", edge " + edgeOk + ", own path " + ownOk + ", saved " + savedOk
				+ ", fast own path " + fastOk + ", reach " + reachOk);
		return ok && squareOk && edgeOk && ownOk && savedOk && fastOk && reachOk;
	}

	private static String shareLink;
	/** Server ticks the fake griefer has left to walk (a quarter block each, east). */
	private static int walkTicks;
	private static long disconnectedAt;
	private static long loggedOutAt;

	/** After the test's own disconnect: check it was recorded, then finish. */
	private static void afterDisconnect(MinecraftClient client) {
		if (disconnectedAt == 0) return;
		List<Disconnects.Entry> all = Disconnects.all();
		Disconnects.Entry e = all.isEmpty() ? null : all.get(all.size() - 1);
		// Wait for it to be recorded (or give up after 20 seconds).
		boolean recorded = e != null && e.time >= disconnectedAt - 1000;
		if (!recorded && System.currentTimeMillis() - disconnectedAt < 20_000) return;
		disconnectedAt = 0;
		if (!recorded) e = null;
		check("the disconnect was recorded (" + (e == null ? "nothing" : Disconnects.line(e)) + ")", e != null && e.reason.contains("[AutoLog]"));
		check("...as done by a mod (" + (e == null ? "" : e.kind + ": " + e.culprit + " · " + e.detail) + ")", e != null && e.kind == Disconnects.Kind.MOD
				&& e.detail.contains("SelfTest"));
		check("...with who was near", e != null && e.nearby.stream().anyMatch(n -> n.name.equals(GRIEFER)));
		check("...and the player in the reason picked out", e != null && Disconnects.namedInReason(e) != null);
		if (downloadName != null) {
			// Open the downloaded world, to see the gold block and sign really are in it.
			WorldDownload.flush();
			log("Opening the downloaded world \"" + downloadName + "\"");
			checkingDownload = System.currentTimeMillis();
			client.createIntegratedServerLoader().start(downloadName, () -> {});
			return;
		}
		finish(client);
	}

	private static void finish(MinecraftClient client) {
		log("DONE: " + passed + " passed, " + failed + " failed");
		client.scheduleStop();
	}

	private static BlockPos downloadGold, downloadSign;
	private static String downloadName;
	private static long checkingDownload;
	private static boolean downloadScreenLogged;

	/** While the downloaded world is open: once it's loaded, its blocks are compared, then the test finishes. */
	private static boolean downloadCheck(MinecraftClient client) {
		if (checkingDownload == 0) return false;
		boolean timedOut = System.currentTimeMillis() - checkingDownload > 60_000;
		long waited = System.currentTimeMillis() - checkingDownload;
		if (waited > 10_000 && !downloadScreenLogged) {
			downloadScreenLogged = true;
			log("Waiting on screen: " + (client.currentScreen == null ? "none" : client.currentScreen.getClass().getName()
					+ " \"" + client.currentScreen.getTitle().getString() + "\""));
			screenshot(client, "64_download_opening");
		}
		if (!timedOut && (client.world == null || client.player == null || client.world.getChunkManager().getWorldChunk(downloadGold.getX() >> 4, downloadGold.getZ() >> 4) == null)) return true;
		checkingDownload = 0;
		boolean opened = !timedOut && client.world != null;
		check("the downloaded world opens", opened);
		boolean goldOk = opened && client.world.getBlockState(downloadGold).isOf(net.minecraft.block.Blocks.GOLD_BLOCK);
		check("...with the gold block where it was", goldOk);
		String signText = opened && client.world.getBlockEntity(downloadSign) instanceof net.minecraft.block.entity.SignBlockEntity be
				? be.getFrontText().getMessage(0, false).getString() : "";
		check("...and the sign's text (" + signText + ")", signText.equals("NN download test"));
		downloadName = null;
		finish(client);
		return true;
	}

	private static int mediumWidth;
	private static int smallWidth;
	private static int chatAll;
	private static Profile.Verdict steveBefore;
	private static Profile.Verdict rickBefore;
	private static String friendCode;

	/** A friend's shared lists: a new naughty player, one you haven't listed, one you disagree on, and you. */
	private static Sharing.Pack friendPack(MinecraftClient client, Ledger ledger) {
		Sharing.Pack pack = new Sharing.Pack();
		pack.from = "Friend_Fran";
		pack.server = ledger.key();
		pack.created = System.currentTimeMillis() - 3_600_000L;

		Sharing.Player sam = new Sharing.Player();
		sam.id = NaughtyOrNiceClient.offlineId("Sneaky_Sam");
		sam.name = "Sneaky_Sam";
		sam.verdict = Profile.Verdict.NAUGHTY;
		sam.details.add(new Profile.Detail("Alt of Griefer_Gary", "Friend_Fran", false, pack.created));
		sam.seen.add("Killed Friend_Fran ×2");
		pack.players.add(sam);

		Sharing.Player steve = new Sharing.Player();
		steve.id = ledger.byName("Steve_Builder").id;
		steve.name = "Steve_Builder";
		steve.verdict = Profile.Verdict.NICE;
		steve.details.add(new Profile.Detail("Builds the spawn highway", "Friend_Fran", false, pack.created));
		pack.players.add(steve);

		Sharing.Player rick = new Sharing.Player();
		rick.id = ledger.byName("RandomRick").id;
		rick.name = "RandomRick";
		rick.verdict = Profile.Verdict.NICE;
		pack.players.add(rick);

		Sharing.Player hana = new Sharing.Player();
		hana.id = ledger.byName("Helpful_Hana").id;
		hana.name = "Helpful_Hana";
		hana.verdict = Profile.Verdict.NICE;
		// Something Fran got from you earlier.
		hana.details.add(new Profile.Detail("Base near 1200, -3400 (sugar cane farm)", client.getSession().getUsername(), false, pack.created));
		pack.players.add(hana);

		Sharing.Player me = new Sharing.Player();
		me.id = client.player.getUuid();
		me.name = client.getSession().getUsername();
		me.verdict = Profile.Verdict.NICE;
		pack.players.add(me);
		return pack;
	}

	private static void cleanTestServer(java.nio.file.Path dir) {
		try (var files = java.nio.file.Files.list(dir)) {
			for (java.nio.file.Path f : files.toList()) {
				if (f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith("server_test.example.net")) java.nio.file.Files.delete(f);
			}
		} catch (java.io.IOException e) {
			log("could not clean test lists: " + e);
		}
	}

	private static ShopScreen screen(MinecraftClient client) {
		return client.currentScreen instanceof ShopScreen s ? s : new ShopScreen();
	}

	private static void hitMe(MinecraftClient client, float amount) {
		IntegratedServer server = client.getServer();
		server.execute(() -> {
			ServerPlayerEntity me = server.getPlayerManager().getPlayerList().get(0);
			me.timeUntilRegen = 0;
			me.damage(me.getEntityWorld(), me.getEntityWorld().getDamageSources().playerAttack(griefer), amount);
		});
	}

	private static void tp(MinecraftClient client, String where) {
		IntegratedServer server = client.getServer();
		server.execute(() -> server.getCommandManager().parseAndExecute(server.getCommandSource(), "tp @a " + where + " -90 0"));
	}

	/**
	 * Every button, switch and box on the Baritone screen, pressed the way a mouse click does, each checked against
	 * what Baritone is really doing afterwards, and nothing typed into chat or sent to the server all the while.
	 */
	private static void baritoneButtons(MinecraftClient client) {
		int s = baritoneStep++;
		BaritoneScreen b = client.currentScreen instanceof BaritoneScreen screen ? screen : null;
		Ledger ledger = NaughtyOrNiceClient.INSTANCE.ledger;
		var p = client.player;
		switch (s) {
			case 1 -> {
				log("Baritone: every button on its screen");
				serverHeard.clear();
				NNConfig.INSTANCE.tripStopForStrangers = false;
				client.setScreen(new BaritoneScreen());
			}
			case 4 -> {
				boolean ok = b.testPress("Do") && b.testPageShown() == 1;
				ok &= b.testPress("Highway roam") && b.testPageShown() == 2;
				ok &= b.testPress("Settings") && b.testPageShown() == 3;
				ok &= b.testPress("Go to…") && b.testPageShown() == 0;
				check("its four pages open from their buttons", ok);
			}
			// ---- Go to…: save a spot, find it, go there, remove it ----
			case 6 -> {
				b.testType("Name this spot, e.g. Home", "NN test spot");
				b.testPress("Save where I am");
				check("Save where I am saves a spot (" + b.testStatus() + ")",
						Destinations.waypoints(ledger).stream().anyMatch(w -> w.name.equals("NN test spot") && w.x == p.getBlockX() && w.z == p.getBlockZ()));
				int kinds = Destinations.Kind.values().length;
				boolean filtered = b.testPress("Show:") && !b.testHas("Show: everything");
				for (int i = 0; i < kinds; i++) b.testPress("Show:");
				check("the Show filter goes through every kind and back", filtered && b.testHas("Show: everything"));
				b.testType("Search places", "NN test spot");
				tp(client, "0.5 -60 30.5");
			}
			case 16 -> {
				check("each place has a Go for its overworld and one for its nether coordinates", b.testHasRow("NN test spot") && b.testHasRow("NN test spot (nether)"));
				check("its row's Go (OW) sets off", b.testRow("NN test spot", false) && client.currentScreen == null);
			}
			case 19 -> {
				log("Goal: " + BaritoneBridge.goal());
				check("...for the saved spot", BaritoneBridge.goal().contains("x=0,y=-60,z=0"));
			}
			case 40 -> {
				double left = Math.hypot(p.getX() - 0.5, p.getZ() - 0.5);
				check("...and walks there (" + (int) left + " blocks left of 30)", left < 22);
				SmartTravel.stop();
				client.setScreen(new BaritoneScreen());
			}
			case 43 -> {
				b.testPress("Go to…");
				b.testType("Search places", "NN test spot");
			}
			case 46 -> check("its × removes the spot", b.testRow("NN test spot", true)
					&& Destinations.waypoints(ledger).stream().noneMatch(w -> w.name.equals("NN test spot")));
			// ---- Do: filling in where to go ----
			case 48 -> {
				b.testPress("Do");
				b.testPress("Use my position");
				check("Use my position fills in where you are", String.valueOf(p.getBlockX()).equals(b.testField("x"))
						&& String.valueOf(p.getBlockY()).equals(b.testField("y (optional)")) && String.valueOf(p.getBlockZ()).equals(b.testField("z")));
				client.keyboard.setClipboard("meet me at 120 -60 -45");
				b.testPress("Paste coordinates");
				check("Paste coordinates reads the clipboard", "120".equals(b.testField("x")) && "-60".equals(b.testField("y (optional)"))
						&& "-45".equals(b.testField("z")));
				b.testPress("Overworld → Nether");
				check("the nether calculator divides by 8 (" + b.testStatus() + ")", "15".equals(b.testField("x")) && "-6".equals(b.testField("z"))
						&& "-60".equals(b.testField("y (optional)")));
				b.testPress("Nether → Overworld");
				check("...and multiplies back", "120".equals(b.testField("x")) && "-48".equals(b.testField("z")));
				b.testPress("Latest place given away");
				String place = b.testStatus();
				check("Latest place given away (" + place + ")", place.startsWith("Filled in") || place.startsWith("Nobody has"));
				b.testPress("Latest trail's far end");
				String trail = b.testStatus();
				check("Latest trail's far end (" + trail + ")", trail.startsWith("Filled in") || trail.startsWith("No chunk trails"));
				b.testType("x", "abc");
				String goal = BaritoneBridge.goal();
				b.testPress("Go");
				check("Go with a bad number says so and goes nowhere", b.testStatus().startsWith("Type whole numbers") && client.currentScreen == b
						&& BaritoneBridge.goal().equals(goal));
			}
			// ---- Do: follow, mine, explore, farm, pause, resume, stop ----
			case 50 -> {
				b.testType("player name", GRIEFER);
				b.testPress("Follow them");
			}
			case 56 -> {
				check("Follow them follows (" + b.testStatus() + ")", BaritoneBridge.processActive("getFollowProcess"));
				b.testPress("Stop");
			}
			case 59 -> {
				check("Stop stops following", !BaritoneBridge.processActive("getFollowProcess"));
				b.testType("block, e.g. diamond_ore", "Dirt");
				b.testPress("Mine it");
			}
			case 65 -> {
				check("Mine it mines (" + b.testStatus() + ")", BaritoneBridge.processActive("getMineProcess"));
				b.testPress("Stop");
			}
			case 68 -> {
				check("Stop stops mining", !BaritoneBridge.processActive("getMineProcess"));
				b.testPress("Explore");
			}
			case 74 -> {
				check("Explore explores (" + b.testStatus() + ")", BaritoneBridge.processActive("getExploreProcess"));
				b.testPress("Pause");
			}
			case 80 -> {
				check("Pause stops it moving (" + b.testStatus() + ")", !BaritoneBridge.isPathing() && b.testStatus().contains("Paused"));
				b.testPress("Resume");
			}
			case 88 -> {
				check("Resume carries on exploring", BaritoneBridge.processActive("getExploreProcess") && (b.testStatus().contains("Resumed") || b.testStatus().equals("Going again")));
				b.testPress("Stop");
			}
			case 91 -> {
				check("Stop stops exploring", !BaritoneBridge.processActive("getExploreProcess"));
				b.testPress("Farm nearby");
				baritonePressed = System.currentTimeMillis();
			}
			case 115 -> {
				// Baritone takes a moment to give up; the screen notices as soon as it does.
				long since = System.currentTimeMillis() - baritonePressed;
				if (since < 2000 || since < 15_000 && BaritoneBridge.processActive("getFarmProcess")) {
					baritoneStep--;
					return;
				}
				log("Farm with no crops: " + b.testStatus() + ", active " + BaritoneBridge.processActive("getFarmProcess"));
				check("Farm nearby with nothing to farm says so (" + b.testStatus() + ")",
						!BaritoneBridge.processActive("getFarmProcess") && b.testStatus().startsWith("Farming stopped: nothing"));
				// Now some ripe wheat next to you.
				IntegratedServer server = client.getServer();
				int px = p.getBlockX(), pz = p.getBlockZ();
				wheatX = px + 6;
				wheatZ = pz;
				server.execute(() -> {
					// No wheat left over from an earlier run.
					server.getCommandManager().parseAndExecute(server.getCommandSource(), "fill -50 -60 -50 50 -60 50 air replace wheat");
					// Out of reach, so Baritone has to walk to it, as on a real farm.
					for (int dx = 6; dx <= 8; dx++) {
						for (String cmd : new String[] {"setblock " + (px + dx) + " -61 " + pz + " farmland", "setblock " + (px + dx) + " -60 " + pz + " wheat[age=7]"}) {
							server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd);
						}
					}
				});
			}
			case 118 -> {
				StringBuilder seen = new StringBuilder();
				for (int dx = -2; dx <= 10; dx++) {
					BlockPos at = BlockPos.ofFloored(p.getX() + dx, -60, p.getZ());
					seen.append(at.getX()).append(':').append(client.world.getBlockState(at).getBlock().getTranslationKey().replace("block.minecraft.", "")).append(' ');
				}
				log("Before farming, at " + p.getBlockPos().toShortString() + ": " + seen + "; Baritone's scanner finds wheat at "
						+ BaritoneBridge.scan(net.minecraft.block.Blocks.WHEAT));
				b.testPress("Farm nearby");
				baritonePressed = System.currentTimeMillis();
			}
			case 120, 122, 125, 130 -> log("Farming? active " + BaritoneBridge.processActive("getFarmProcess") + ", goal " + BaritoneBridge.goal()
					+ ", at " + p.getBlockPos().toShortString() + ", Baritone's scanner finds wheat at " + BaritoneBridge.scan(net.minecraft.block.Blocks.WHEAT));
			case 140 -> {
				// Until it's done (or 20 s, if it never is).
				long since = System.currentTimeMillis() - baritonePressed;
				if (since < 2500 || since < 20_000 && BaritoneBridge.processActive("getFarmProcess")) {
					baritoneStep--;
					return;
				}
				log("Farm with wheat: " + b.testStatus() + ", active " + BaritoneBridge.processActive("getFarmProcess"));
				int ripe = 0;
				for (int dx = 0; dx < 3; dx++) {
					var state = client.world.getBlockState(new BlockPos(wheatX + dx, -60, wheatZ));
					if (state.isOf(net.minecraft.block.Blocks.WHEAT) && state.get(net.minecraft.block.CropBlock.AGE) == 7) ripe++;
				}
				check("Farm nearby walks over and harvests the wheat (" + ripe + " of 3 still ripe)", ripe == 0);
				check("...and says so when it's done (" + b.testStatus() + ")", BaritoneBridge.processActive("getFarmProcess")
						? b.testStatus().equals("Farming nearby") : b.testStatus().startsWith("Farming stopped: nothing"));
				b.testPress("Stop");
			}
			case 143 -> {
				check("Stop stops farming", !BaritoneBridge.processActive("getFarmProcess"));
				b.testType("x", "600");
				b.testType("y (optional)", "");
				b.testType("z", "600");
				b.testPress("Fly there by elytra");
				check("the elytra button says it only works in the nether (" + b.testStatus() + ")",
						b.testStatus().startsWith("Elytra flying only works in the nether") && !BaritoneBridge.goal().contains("x=600"));
			}
			// ---- Highway roam ----
			case 146 -> {
				b.testPress("Highway roam");
				check("a highway is picked with its button", b.testPress("+Z") && HighwayRoam.axis.name().equals("+Z"));
				boolean was = HighwayRoam.elytra;
				b.testPress(was ? "Elytra ▸" : "Walk ▸");
				boolean flipped = HighwayRoam.elytra != was;
				b.testPress(HighwayRoam.elytra ? "Elytra ▸" : "Walk ▸");
				check("Walk / Elytra switches and back", flipped && HighwayRoam.elytra == was);
				boolean parkour = HighwayRoam.parkour;
				b.testPress("Parkour jumps");
				boolean parkourFlipped = HighwayRoam.parkour != parkour;
				b.testPress("Parkour jumps");
				check("its switches switch (parkour)", parkourFlipped && HighwayRoam.parkour == parkour);
				if (HighwayRoam.stopForStrangers) b.testPress("Stop when a stranger shows up");
				check("...and the stranger one", !HighwayRoam.stopForStrangers);
				b.testType("here", "");
				b.testType("blocks", "200");
				b.testPress("Start roaming");
			}
			case 154 -> {
				log("Roam: " + b.testStatus() + ", goal " + BaritoneBridge.goal() + ", distance " + HighwayRoam.distance);
				check("Start roaming roams the +Z highway, 200 blocks", HighwayRoam.active() && HighwayRoam.distance == 200
						&& BaritoneBridge.goal().contains("x=0") && BaritoneBridge.goal().contains("z="));
				b.testPress("Stop");
			}
			case 157 -> {
				check("Stop stops the roam", !HighwayRoam.active() && !BaritoneBridge.isPathing());
				HighwayRoam.stopForStrangers = true;
				HighwayRoam.axis = HighwayRoam.AXES[0];
				// ---- Settings: all of Baritone's, searched and clicked ----
				b.testPress("Settings");
				int health = NNConfig.INSTANCE.tripStopHealth;
				b.testPress("Stop at");
				boolean healthChanged = NNConfig.INSTANCE.tripStopHealth != health;
				for (int i = 0; i < 8 && NNConfig.INSTANCE.tripStopHealth != health; i++) b.testPress("Stop at");
				check("the low-health button goes round its choices", healthChanged && NNConfig.INSTANCE.tripStopHealth == health);
				b.testPress("On trips: pause when a stranger");
				check("the strangers switch switches", NNConfig.INSTANCE.tripStopForStrangers);
				b.testType("Search Baritone's settings (all of them)", "allowSprint");
			}
			case 159 -> {
				int count = BaritoneBridge.settings().size();
				Boolean sprint = BaritoneBridge.bool("allowSprint");
				boolean clicked = b.testRow("allowSprint", false);
				boolean flipped = !java.util.Objects.equals(sprint, BaritoneBridge.bool("allowSprint"));
				BaritoneBridge.setBool("allowSprint", Boolean.TRUE.equals(sprint));
				check("Settings has all of Baritone's (" + count + "), and an on/off one switches with a click", count > 100 && clicked && flipped);
				check("...backfill among them", BaritoneBridge.settings().stream().anyMatch(st -> st.name().equals("backfill") && st.bool()));
				// Every setting that isn't on/off, Set to what it shows: each must come back exactly the same, with no error.
				BaritoneBridge.listen();
				settingsMark = BaritoneBridge.replyCount();
				List<String> wrong = new java.util.ArrayList<>();
				int tried = 0;
				for (BaritoneBridge.Setting st : BaritoneBridge.settings()) {
					if (st.bool() || st.value().isEmpty()) continue;
					tried++;
					BaritoneBridge.run("set " + st.name() + " " + st.value());
					String after = BaritoneBridge.settings().stream().filter(x -> x.name().equals(st.name())).findFirst().map(BaritoneBridge.Setting::value).orElse("?");
					if (!after.equals(st.value())) wrong.add(st.name() + ": " + st.value() + " -> " + after);
				}
				log("Set " + tried + " settings to what they show; changed: " + wrong);
				check("every setting (" + tried + ") Sets back exactly as it shows (" + wrong + ")", tried > 50 && wrong.isEmpty());
				b.testType("Search Baritone's settings (all of them)", "followRadius");
			}
			case 161 -> {
				List<String> errors = BaritoneBridge.repliesSince(settingsMark).stream().filter(BaritoneBridge.Reply::error).map(BaritoneBridge.Reply::text).toList();
				check("...and Baritone took every one without an error (" + errors + ")", errors.isEmpty());
				check("a setting with a value is picked with a click", b.testRow("followRadius", false) && b.testField("followRadius") != null);
			}
			case 163 -> {
				b.testType("followRadius", "7");
				b.testPress("Set");
				String set = BaritoneBridge.settings().stream().filter(st -> st.name().equals("followRadius")).findFirst().map(BaritoneBridge.Setting::value).orElse("?");
				b.testPress("Reset");
				BaritoneBridge.Setting after = BaritoneBridge.settings().stream().filter(st -> st.name().equals("followRadius")).findFirst().orElse(null);
				check("...Set gives it the typed value (" + set + "), Reset puts it back", "7".equals(set) && after != null && !after.changed());
				// ---- Every command ----
				b.testPress("Every command");
				List<String> names = BaritoneBridge.commands().stream().flatMap(c -> c.names().stream()).toList();
				check("Every command lists all of Baritone's (" + BaritoneBridge.commands().size() + ")", BaritoneBridge.commands().size() > 30
						&& names.containsAll(List.of("goto", "mine", "tunnel", "sel", "come", "follow", "farm", "explore", "set", "stop")));
				b.testType("Search Baritone's commands", "mine");
			}
			case 165 -> {
				check("a command is picked with a click", b.testRow("mine", false) && b.testField("what to add after mine (or nothing)") != null);
				b.testType("what to add after mine (or nothing)", "dir");
			}
			case 168 -> {
				check("Baritone's suggestions show for what's typed (" + b.testSuggestions().size() + ")", b.testSuggestions().contains("dirt"));
				b.testRow("suggest:dirt", false);
				check("...and clicking one fills it in, spelled right", "dirt ".equals(b.testField("what to add after mine (or nothing)")));
				b.testPress("Run mine");
			}
			case 172 -> {
				check("Run runs it (" + b.testStatus() + ")", BaritoneBridge.processActive("getMineProcess") && !b.testStatusIsError());
				b.testType("Search Baritone's commands", "stop");
			}
			case 174 -> {
				String stopName = BaritoneBridge.commands().stream().filter(c -> c.names().contains("stop")).findFirst().map(BaritoneBridge.Command::name).orElse("stop");
				b.testRow(stopName, false);
				b.testPress("Run " + stopName);
			}
			case 177 -> {
				check("...and stop from the list stops it", !BaritoneBridge.processActive("getMineProcess"));
				// Typed wrong: Baritone's own error shows, in red, not "Ran".
				b.testType("Search Baritone's commands", "mine");
			}
			case 178 -> {
				b.testRow("mine", false);
				b.testType("what to add after mine (or nothing)", "dir");
				b.testPress("Run mine");
			}
			case 181 -> check("a mistyped command shows Baritone's error in red (" + b.testStatus() + ")", b.testStatusIsError() && b.testStatus().startsWith("Baritone:")
					&& !BaritoneBridge.processActive("getMineProcess"));
			case 183 -> {
				check("nothing went to the server's chat through all of it (" + serverHeard + ")", serverHeard.isEmpty());
				String coords = CoordsHud.line(client);
				// An F3 entry set to always on (hitboxes) doesn't count as F3 open: the coordinates, chunk line and radar stay.
				var hitboxes = net.minecraft.util.Identifier.of("minecraft", "entity_hitboxes");
				var was = client.debugHudEntryList.getVisibility(hitboxes);
				client.debugHudEntryList.setEntryVisibility(hitboxes, net.minecraft.client.gui.hud.debug.DebugHudEntryVisibility.ALWAYS_ON);
				boolean shownWithHitboxes = !CoordsHud.f3Open(client);
				client.debugHudEntryList.setF3Enabled(true);
				boolean hiddenWithF3 = CoordsHud.f3Open(client);
				client.debugHudEntryList.setF3Enabled(false);
				client.debugHudEntryList.setEntryVisibility(hitboxes, was);
				check("on-screen things show with an F3 entry always on, and hide only with F3 open", shownWithHitboxes && hiddenWithF3);
				check("your coordinates show in the bottom left by default (" + coords + ")", NNConfig.INSTANCE.showCoords
						&& coords.startsWith("XYZ " + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ())
						&& coords.contains("Nether " + Math.floorDiv(p.getBlockX(), 8) + " " + Math.floorDiv(p.getBlockZ(), 8)));
				client.setScreen(null);
				NNConfig.save();
				baritoneStep = 0;
			}
			default -> {}
		}
	}

	private static void screenshot(MinecraftClient client, String name) {
		ScreenshotRecorder.saveScreenshot(client.runDirectory, "nn_" + name + ".png", client.getFramebuffer(), 1, message -> {});
		log("Screenshot " + name);
	}

	private static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		log((ok ? "PASS " : "FAIL ") + what);
	}

	private static void log(String message) {
		NaughtyOrNiceClient.LOGGER.info("[SELFTEST] {}", message);
	}
}
