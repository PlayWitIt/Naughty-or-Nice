package com.greysworkshop.naughtyornice;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** /nn commands. Client-side only; nothing reaches the server. */
public final class Commands {
	private Commands() {}

	private static final SuggestionProvider<FabricClientCommandSource> NAMES = (context, builder) -> {
		List<String> names = new ArrayList<>();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getNetworkHandler() != null) {
			for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) names.add(entry.getProfile().name());
		}
		for (Profile profile : NaughtyOrNiceClient.INSTANCE.ledger.all()) {
			if (!names.contains(profile.name)) names.add(profile.name);
		}
		return CommandSource.suggestMatching(names, builder);
	};

	private static final SuggestionProvider<FabricClientCommandSource> GROUPS = (context, builder) ->
			CommandSource.suggestMatching(NaughtyOrNiceClient.INSTANCE.ledger.groups().keySet(), builder);

	private static final SuggestionProvider<FabricClientCommandSource> CIRCLES = (context, builder) ->
			CommandSource.suggestMatching(Friends.circles().stream().map(c -> c.name).toList(), builder);

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("nn")
				.executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen());
					return 1;
				})
				.then(literal("nice").then(argument("player", StringArgumentType.word()).suggests(NAMES)
						.executes(c -> mark(c, Profile.Verdict.NICE))))
				.then(literal("naughty").then(argument("player", StringArgumentType.word()).suggests(NAMES)
						.executes(c -> mark(c, Profile.Verdict.NAUGHTY))))
				.then(literal("look").then(argument("player", StringArgumentType.word()).suggests(NAMES)
						.executes(c -> mark(c, Profile.Verdict.LOOK))))
				.then(literal("friend").then(argument("player", StringArgumentType.word()).suggests(NAMES)
						.executes(c -> mark(c, Profile.Verdict.FRIEND))
						.then(argument("circle", StringArgumentType.greedyString()).suggests(CIRCLES).executes(Commands::friend))))
				.then(literal("open").then(argument("link", StringArgumentType.greedyString()).executes(c -> {
					String link = CloudShare.find(StringArgumentType.getString(c, "link"));
					if (link == null) {
						c.getSource().sendError(Text.literal("That isn't a Naughty or Nice share link (they start with " + CloudShare.PREFIX + ")."));
						return 0;
					}
					NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen().withShareLink(link));
					return 1;
				})))
				.then(literal("update").then(literal("skip").then(argument("version", StringArgumentType.word()).executes(c -> {
					NNConfig.INSTANCE.skippedVersion = StringArgumentType.getString(c, "version");
					NNConfig.save();
					c.getSource().sendFeedback(Alerts.prefix().append(Text.literal("You won't be told about " + NNConfig.INSTANCE.skippedVersion
							+ " again. Newer versions still get a note.").formatted(Formatting.GRAY)));
					return 1;
				}))))
				.then(literal("sendanyway").executes(c -> {
					if (!CoordGuard.sendHeld()) c.getSource().sendError(Text.literal("No message is being held."));
					return 1;
				}))
				.then(literal("coords").executes(Commands::coords))
				.then(literal("sounds").executes(c -> {
					Alerts.demo();
					return 1;
				}))
				.then(literal("events").executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new EventScreen());
					return 1;
				}))
				.then(literal("baritone").executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new BaritoneScreen());
					return 1;
				}))
				.then(literal("download")
						.then(literal("start").executes(c -> {
							String why = WorldDownload.start(MinecraftClient.getInstance());
							if (why != null) c.getSource().sendError(Text.literal(why));
							return 1;
						}))
						.then(literal("stop").executes(c -> {
							WorldDownload.stop(MinecraftClient.getInstance());
							return 1;
						})))
				.then(literal("trip").then(literal("stop").executes(c -> {
					SmartTravel.stop();
					c.getSource().sendFeedback(Alerts.prefix().append(Text.literal("Stopped.").formatted(Formatting.GRAY)));
					return 1;
				})))
				.then(literal("roam")
						.then(literal("stop").executes(c -> {
							HighwayRoam.stop("you stopped it");
							return 1;
						}))
						.then(literal("resume").executes(c -> {
							HighwayRoam.resume();
							return 1;
						}))
						.then(literal("follow").then(argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
								.then(argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer()).executes(c -> {
									HighwayRoam.follow(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "x"),
											com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "z"));
									return 1;
								})))))
				.then(literal("radar").executes(c -> {
					NNConfig.INSTANCE.trailRadar = !NNConfig.INSTANCE.trailRadar;
					if (NNConfig.INSTANCE.trailRadar) NNConfig.INSTANCE.chunkTrails = true;
					NNConfig.save();
					c.getSource().sendFeedback(Alerts.prefix().append(Text.literal("Chunk trail radar " + (NNConfig.INSTANCE.trailRadar ? "on (J toggles it)" : "off"))
							.formatted(Formatting.GRAY)));
					return 1;
				}))
				.then(literal("server").executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen(ShopScreen.Tab.SERVER, null));
					return 1;
				}))
				.then(literal("intel").executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen(ShopScreen.Tab.INTEL, null));
					return 1;
				}))
				.then(literal("disconnects").executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen().withDisconnects());
					return 1;
				}))
				.then(literal("friends").executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen(ShopScreen.Tab.FRIENDS, null));
					return 1;
				}))
				.then(literal("group").then(argument("player", StringArgumentType.word()).suggests(NAMES)
						.then(argument("group", StringArgumentType.greedyString()).suggests(GROUPS).executes(Commands::group))))
				.then(literal("chat").executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen(ShopScreen.Tab.CHAT, null));
					return 1;
				}).then(argument("player", StringArgumentType.word()).suggests(NAMES)
						.executes(c -> {
							Profile profile = find(c);
							if (profile == null) return 0;
							NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen(null, profile).withChatLog());
							return 1;
						})))
				.then(literal("groups").executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen(ShopScreen.Tab.GROUPS, null));
					return 1;
				}))
				.then(literal("unsort").then(argument("player", StringArgumentType.word()).suggests(NAMES)
						.executes(c -> mark(c, Profile.Verdict.NONE))))
				.then(literal("who").then(argument("player", StringArgumentType.word()).suggests(NAMES)
						.executes(c -> {
							Profile profile = find(c);
							if (profile == null) return 0;
							NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen(null, profile));
							return 1;
						})))
				.then(literal("add").executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen().withAddPanel(Profile.Verdict.NICE));
					return 1;
				}))
				.then(literal("share").executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen().withSharePanel(false));
					return 1;
				}))
				.then(literal("import").executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen().withSharePanel(true));
					return 1;
				}))
				.then(literal("check").executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen().withChatCheck());
					return 1;
				}))
				.then(literal("settings").executes(c -> {
					NaughtyOrNiceClient.INSTANCE.openLater(new ShopScreen(ShopScreen.Tab.SETTINGS, null));
					return 1;
				})));
	}

	/** A player we know of (online or in the lists) by the name argument, or null with an error shown. */
	private static Profile find(CommandContext<FabricClientCommandSource> c) {
		String name = StringArgumentType.getString(c, "player");
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		MinecraftClient client = MinecraftClient.getInstance();
		Profile profile = mod.ledger.byName(name);
		if (profile == null && client.getNetworkHandler() != null) {
			PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(name);
			if (entry != null) profile = mod.ledger.getOrCreate(entry.getProfile().id(), entry.getProfile().name());
		}
		if (profile == null) c.getSource().sendError(Text.literal("No player called " + name + " is online or on your lists. Add them with /nn add."));
		return profile;
	}

	private static int mark(CommandContext<FabricClientCommandSource> c, Profile.Verdict verdict) {
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		String name = StringArgumentType.getString(c, "player");
		if (mod.ledger.key() == null) {
			c.getSource().sendError(Text.literal("Join a world or server first."));
			return 0;
		}
		Profile profile = mod.ledger.addByName(MinecraftClient.getInstance(), name);
		mod.ledger.setVerdict(profile, verdict, false, null);
		mod.ledger.save(false);
		Text message = switch (verdict) {
			case NICE -> Text.literal(profile.name + " is on your Nice list.").formatted(Formatting.GREEN);
			case NAUGHTY -> Text.literal(profile.name + " is on your Naughty list.").formatted(Formatting.RED);
			case LOOK -> Text.literal(profile.name + " is on your Look Into list.").formatted(Formatting.GOLD);
			case FRIEND -> Text.literal(profile.name + " is your friend (" + Friends.circleName(profile) + ").").formatted(Formatting.AQUA);
			case NONE -> Text.literal(profile.name + " is off your lists.").formatted(Formatting.GRAY);
		};
		c.getSource().sendFeedback(Alerts.prefix().append(message));
		return 1;
	}

	/** /nn friend &lt;player&gt; &lt;circle&gt;: makes them a friend in that circle. */
	private static int friend(CommandContext<FabricClientCommandSource> c) {
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		if (mod.ledger.key() == null) {
			c.getSource().sendError(Text.literal("Join a world or server first."));
			return 0;
		}
		String circleName = StringArgumentType.getString(c, "circle");
		Friends.Circle circle = Friends.byName(circleName);
		if (circle == null) {
			c.getSource().sendError(Text.literal("No friend type called \"" + circleName + "\". Your friend types: "
					+ String.join(", ", Friends.circles().stream().map(x -> x.name).toList())));
			return 0;
		}
		Profile profile = mod.ledger.addByName(MinecraftClient.getInstance(), StringArgumentType.getString(c, "player"));
		mod.ledger.setCircle(profile, circle);
		mod.ledger.save(false);
		c.getSource().sendFeedback(Alerts.prefix().append(Text.literal(profile.name + " is your friend (" + circle.name + ").").formatted(Formatting.AQUA)));
		return 1;
	}

	/** /nn group &lt;player&gt; &lt;group&gt;: puts them in the group (made if it's new). */
	private static int group(CommandContext<FabricClientCommandSource> c) {
		NaughtyOrNiceClient mod = NaughtyOrNiceClient.INSTANCE;
		if (mod.ledger.key() == null) {
			c.getSource().sendError(Text.literal("Join a world or server first."));
			return 0;
		}
		String group = Ledger.cleanGroupName(StringArgumentType.getString(c, "group"));
		if (group.isEmpty()) {
			c.getSource().sendError(Text.literal("Give the group a name."));
			return 0;
		}
		Profile profile = mod.ledger.addByName(MinecraftClient.getInstance(), StringArgumentType.getString(c, "player"));
		boolean added = mod.ledger.addToGroup(profile, group);
		mod.ledger.save(false);
		String name = mod.ledger.groupName(group);
		c.getSource().sendFeedback(Alerts.prefix().append(Text.literal(added
				? profile.name + " is in the group " + name + " (" + mod.ledger.members(name).size() + " members)."
				: profile.name + " is already in " + name + ".").formatted(Formatting.AQUA)));
		return 1;
	}

	/** Says why the coordinates aren't showing, and turns them back on: the setting, and any profile for this server that turns it off. */
	private static int coords(CommandContext<FabricClientCommandSource> c) {
		MinecraftClient client = MinecraftClient.getInstance();
		String reason = CoordsHud.hiddenReason(client);
		NNConfig.INSTANCE.showCoords = true;
		NNConfig.save();
		int profiles = 0;
		var entry = client.getCurrentServerEntry();
		if (entry != null) {
			List<ModsPanel.Profile> all = ModsPanel.loadProfiles();
			String here = ModsPanel.server(entry.address);
			for (ModsPanel.Profile p : all) {
				if (p.servers.stream().map(ModsPanel::server).noneMatch(here::equals)) continue;
				if (Boolean.FALSE.equals(p.switches.get(NaughtyOrNiceClient.MOD_ID + "/My coordinates"))) {
					p.switches.put(NaughtyOrNiceClient.MOD_ID + "/My coordinates", true);
					profiles++;
				}
			}
			if (profiles > 0) ModsPanel.saveProfiles(all);
		}
		String said = reason == null ? "Your coordinates are on, " + CoordsHud.where(client) + "." : "They weren't showing because " + reason;
		if (reason != null && NNConfig.on() && !ModsPanel.streamSafe() && !client.options.hudHidden && !CoordsHud.f3Open(client)) said += " They're on now.";
		if (profiles > 0) said += " A Mods panel profile for this server was turning them off each time you joined; it keeps them on now.";
		c.getSource().sendFeedback(Alerts.prefix().append(Text.literal(said).formatted(Formatting.GRAY)));
		return 1;
	}
}
