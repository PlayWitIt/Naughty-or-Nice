package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** User settings, saved to config/naughtyornice/settings.json. Missing fields keep their defaults. */
public final class NNConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static NNConfig INSTANCE = new NNConfig();

	/** The whole mod on or off (the Mods panel's first switch). Off: it watches, records and shows nothing; your lists are kept. */
	public boolean enabled = true;

	/** Whether the mod is on (see {@link #enabled}). */
	public static boolean on() {
		return INSTANCE.enabled;
	}

	// ---- Looks ----
	/** Dark theme for the lists screen. */
	public boolean darkMode = true;
	/** How big the lists screen is drawn: 0 small, 1 medium, 2 large (the game's own GUI scale). */
	public int screenSize = 1;
	/** The Chat tab shows chat from players on no list too (otherwise just listed or grouped players). */
	public boolean chatShowEveryone = true;

	// ---- Showing the lists ----
	/** Green tick / red cross in front of names above players' heads. */
	public boolean nametagMarks = true;
	/** The same marks in the tab list. */
	public boolean tabListMarks = true;
	/** Group tags ("[SCG]") after the marks, on nametags and in the tab list. */
	public boolean groupTags = true;

	// ---- Warnings ----
	/** Warn when a Naughty-list player comes into view. */
	public boolean warnNaughtyNearby = true;
	/** A quieter note when someone on your Look Into list comes into view or joins. */
	public boolean noteLookInto = true;
	/** Warn when a Naughty-list player joins the server. */
	public boolean warnNaughtyJoin = true;
	public boolean warnSounds = true;

	// ---- Watching what people do ----
	/** Put players who kill you on the Naughty list automatically (if they're not on a list yet). */
	public boolean autoNaughtyKillers = true;
	/** Notice friendly words and trash talk in chat. */
	public boolean watchChat = true;
	/** Players within this many blocks count as "near you" for peaceful time. */
	public int nearRadius = 32;
	/** Remember every player you see (nearby or in the tab list), not just ones on your lists. */
	public boolean rememberEveryone = true;
	/** Players not on a list, with no note and nothing done, are forgotten after this many days unseen (0 = never). */
	public int forgetAfterDays = 180;
	/** Most activity entries kept per player. */
	public int maxActions = 60;

	// ---- Chat log ----
	/** Record conversations involving anyone on your Nice or Naughty list. */
	public boolean chatLog = true;
	/** A conversation ends once they've said nothing for this long. */
	public int chatQuietSeconds = 180;
	/** Chat from this long before they first spoke is kept, so you can see what they were answering. */
	public int chatContextSeconds = 60;
	/** Most conversations kept in-game per player. */
	public int chatKeepConversations = 100;
	/** Also append every finished conversation to a text file per player. */
	public boolean chatFiles = true;
	/** Also write everything said on the server to a text file a day. */
	public boolean chatAllFiles = true;

	// ---- Alerts ----
	/** Words to be alerted about when someone else says them; "{me}" stands for your name. */
	public java.util.List<String> alertWords = new java.util.ArrayList<>(java.util.List.of("{me}"));
	/** Alert when someone posts coordinates near one of your Base Insurance bases. */
	public boolean alertBaseCoords = true;
	/** Alert when someone says the name of one of your Base Insurance bases. */
	public boolean alertBaseNames = true;
	/** Alert when someone threatens you or your base (or whispers you a threat). */
	public boolean alertThreats = true;
	/** Topics ({@link ChatIntel.Topic} names) to be pinged about whenever anyone talks about them. */
	public java.util.List<String> topicPings = new java.util.ArrayList<>();
	/** Ping whenever anyone posts coordinates. */
	public boolean pingAllCoords = false;
	/** Hold your own chat message when it gives away coordinates or a base name, until you say send anyway. */
	public boolean guardMyCoords = true;
	/** Hold every chat message and whisper you send until you confirm it (off: the coordinate guard catches leaks). */
	public boolean askBeforeSending = false;
	/** Tell old land from fresh land and look for trails through it. */
	public boolean chunkTrails = true;
	/** Show the chunk-trail radar (J toggles it). */
	public boolean trailRadar = false;
	/** The radar's size: 0 small, 1 medium, 2 large. */
	public int radarSize = 0;
	/** Set once 1.14.0 has put the radar away (it's off until you turn it on). */
	public boolean radarPutAway = false;
	/** Your coordinates in the bottom left (hidden in stream-safe mode). */
	public boolean showCoords = true;
	/** Draw them one GUI scale step smaller than the rest of the HUD. */
	public boolean smallCoords = true;
	/** In the bottom-right corner, clear of the chat; off: the bottom left. */
	public boolean coordsRight = true;
	/** Set once 1.13.11 has turned the coordinates back on for everyone who'd lost them. */
	public boolean coordsRestored = false;
	/** A chat note when a new chunk trail is found. */
	public boolean trailAlerts = true;
	/** The lists' sort, and whether Intel shows as a map: as you last left them. */
	public String listSort = "RECENT";
	public boolean intelMap = false;

	/** The bar at the top while Baritone takes you somewhere: where to, how far, how long, how fast. */
	public boolean tripBar = true;

	// ---- Highway roam (the Baritone screen): kept until you change them ----
	public String roamAxis = "+X";
	public boolean roamElytra = false;
	/** The highway's height; null for "where I am". */
	public Integer roamHeight = null;
	public int roamDistance = 50_000;
	/** Go to coordinates (roamX, roamZ) rather than a distance. */
	public boolean roamToCoords = false;
	public Integer roamX = null, roamZ = null;
	/** The coordinates are overworld ones: in the nether, go to the matching spot (÷8). */
	public boolean roamOverworld = false;
	/** At the highway's nearest point, carry on off it to the spot (or stop on the highway). */
	public boolean roamGoOn = true;
	public boolean roamBreak = true, roamBridge = true, roamParkour = true, roamLava = false, roamStopTrails = true, roamStopStrangers = true;

	/** A note when new chunks (or, from new land, old ones) come up ahead as you travel. */
	public boolean aheadAlerts = true;
	/** Draw the chunks that have been read in the world, as coloured squares (K toggles it). */
	public boolean chunkOverlay = true;
	/** The height the squares are drawn at, or {@link ChunkOverlay#FOLLOW} for at your feet. */
	public int chunkOverlayY = ChunkOverlay.FOLLOW;
	/** How see-through the squares' fill is: 0 (outline only) to 100 (solid). */
	public int chunkOverlayFill = 25;
	/** On a Baritone trip: pause and ask when someone who isn't a friend (or on your Nice list) shows up. */
	public boolean tripStopForStrangers = true;
	/** Trips in the nether ride the highways when that's quicker than going straight (see HighwayRoute). */
	public boolean tripUseHighways = true;
	/** On a Baritone trip: stop when your health is at or below this (half-hearts; 0 = never). */
	public int tripStopHealth = 8;
	/** Keep a log of everything around you (the last 30 minutes always; recordings when you start one). */
	public boolean eventLog = true;
	/** Totems players have popped since they last died, after their name. */
	public boolean totemCounter = true;
	/** A marker where players logged out while you could see them. */
	public boolean logoutSpots = true;
	/** What hit you in your last 30 seconds, on the death screen. */
	public boolean deathRecap = true;
	/** A summary of who's online from your lists when you join a server. */
	public boolean joinSummary = true;

	// ---- Sightings ----
	/** Note where and when players come into view, and what they're wearing. */
	public boolean recordSightings = true;
	/** Most sightings kept per player. */
	public int maxSightings = 40;
	/** Track who logs on, off and plays together, to suggest groups and spot alts. */
	public boolean trackLinks = true;
	/** Keep track of what hours players are online. */
	public boolean trackHours = true;
	/** Take a screenshot when someone kills you or a Naughty player comes into one of your bases. */
	public boolean evidenceShots = true;

	// ---- Friends ----
	/** Your friend circles, in the order they're shown. The first is where new friends go. */
	public java.util.List<Friends.Circle> circles = Friends.defaultCircles();

	// ---- Sharing ----
	/** The command used to send a share link to someone in game ("msg" sends /msg name link). */
	public String whisperCommand = "msg";
	/** Offer to open share links friends send you in chat. */
	public boolean openSharedLinks = true;

	// ---- Phone alerts ----
	/** A Discord webhook link: warnings and pings are posted there too, so they reach your phone. Empty: nothing is sent. */
	public String phoneWebhook = "";
	/** Only send them while the game isn't the window you're using. */
	public boolean phoneOnlyAway = true;

	// ---- Updates ----
	/** Say when a newer version is out on GitHub (once per game session). */
	public boolean checkUpdates = true;
	/** A version you said not to be told about again. */
	public String skippedVersion = "";

	// ---- Disconnects ----
	/** Note why you were disconnected and who was around, and say so when you're back. */
	public boolean recordDisconnects = true;

	// ---- Other mods ----
	/** Nice-list players count as friends in Base Insurance (no alerts about them). */
	public boolean shareWithBaseInsurance = true;

	/** Suggested groups you said aren't groups (their players' ids, sorted and joined). */
	public java.util.List<String> dismissedSuggestions = new java.util.ArrayList<>();

	/** Whether the "chunk trails are on: press J for the radar" tip has been shown. */
	public boolean seenRadarTip = false;

	/** Whether the first-time "press N" tip has been shown. */
	public boolean seenTip = false;

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("naughtyornice");
	}

	private static Path file() {
		return dir().resolve("settings.json");
	}

	public static void load() {
		if (!Files.exists(file())) {
			INSTANCE.coordsRestored = true;
			INSTANCE.radarPutAway = true;
			return;
		}
		try (Reader reader = Files.newBufferedReader(file())) {
			NNConfig loaded = GSON.fromJson(reader, NNConfig.class);
			if (loaded != null) {
				if (loaded.alertWords == null) loaded.alertWords = new java.util.ArrayList<>(java.util.List.of("{me}"));
				loaded.alertWords.removeIf(w -> w == null || w.isBlank());
				if (loaded.topicPings == null) loaded.topicPings = new java.util.ArrayList<>();
				if (loaded.dismissedSuggestions == null) loaded.dismissedSuggestions = new java.util.ArrayList<>();
				if (loaded.circles != null) loaded.circles.removeIf(c -> c == null || c.id == null || c.id.isBlank() || c.name == null || c.name.isBlank());
				if (loaded.circles == null || loaded.circles.isEmpty()) loaded.circles = Friends.defaultCircles();
				if (loaded.skippedVersion == null) loaded.skippedVersion = "";
				if (loaded.whisperCommand == null || loaded.whisperCommand.isBlank()) loaded.whisperCommand = "msg";
				boolean putAway = !loaded.radarPutAway;
				if (putAway) {
					loaded.trailRadar = false;
					loaded.radarPutAway = true;
				}
				boolean restore = !loaded.coordsRestored;
				if (restore) {
					loaded.showCoords = true;
					loaded.coordsRestored = true;
					// A profile saved with them off would turn them off again on every join.
					List<ModsPanel.Profile> profiles = ModsPanel.loadProfiles();
					boolean changed = false;
					for (ModsPanel.Profile p : profiles) changed |= p.switches.remove(NaughtyOrNiceClient.MOD_ID + "/My coordinates") != null;
					if (changed) ModsPanel.saveProfiles(profiles);
				}
				INSTANCE = loaded;
				if (restore || putAway) save();
			}
		} catch (IOException | RuntimeException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not read {}", file(), e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(dir());
			try (Writer writer = Files.newBufferedWriter(file())) {
				GSON.toJson(INSTANCE, writer);
			}
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not write {}", file(), e);
		}
	}
}
