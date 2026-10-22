package com.greysworkshop.naughtyornice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Every place worth going to, in one list: your waypoints and bases, chunk trails' far ends, places players gave
 * away, where players on your lists were last seen, world events (lit end portals are strongholds), and coordinates
 * on the clipboard. Waypoints are yours to add and are saved per server.
 */
public final class Destinations {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public enum Kind {
		WAYPOINT("Waypoint"), BASE("Your base"), TRAIL("Trail end"), PLACE("Given away"), SEEN("Last seen"), EVENT("World event"), CLIPBOARD("Clipboard");

		public final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	/** A place: y is Integer.MIN_VALUE when there's no height; dimension "" when not known. */
	public record Destination(Kind kind, String name, String detail, int x, int y, int z, String dimension, long time) {
		public boolean hasY() {
			return y != Integer.MIN_VALUE;
		}

		public String coords() {
			return hasY() ? x + ", " + y + ", " + z : x + ", " + z;
		}
	}

	/** A place you saved, by name. */
	public static final class Waypoint {
		public String name = "";
		public int x, y, z;
		public String dimension = "";
		public long time;
	}

	public static List<Waypoint> waypoints = new ArrayList<>();
	private static String loadedFor;

	private Destinations() {}

	private static Path file(Ledger ledger) {
		return NNConfig.dir().resolve("lists").resolve(ledger.key().replaceAll("[^A-Za-z0-9._-]", "_") + ".waypoints.json");
	}

	/** This server's waypoints (loaded the first time they're needed). */
	public static List<Waypoint> waypoints(Ledger ledger) {
		if (ledger.key() == null) return List.of();
		if (!ledger.key().equals(loadedFor)) {
			loadedFor = ledger.key();
			waypoints = new ArrayList<>();
			Path f = file(ledger);
			if (Files.exists(f)) {
				try (Reader r = Files.newBufferedReader(f)) {
					List<Waypoint> loaded = GSON.fromJson(r, new TypeToken<List<Waypoint>>() {}.getType());
					if (loaded != null) {
						loaded.removeIf(w -> w == null || w.name == null || w.name.isBlank());
						for (Waypoint w : loaded) {
							if (w.dimension == null) w.dimension = "";
						}
						waypoints.addAll(loaded);
					}
				} catch (IOException | RuntimeException e) {
					NaughtyOrNiceClient.LOGGER.error("Could not read {}", f, e);
				}
			}
		}
		return waypoints;
	}

	private static void save(Ledger ledger) {
		Path f = file(ledger);
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(f)) {
				GSON.toJson(waypoints, w);
			}
		} catch (IOException e) {
			NaughtyOrNiceClient.LOGGER.error("Could not write {}", f, e);
		}
	}

	/** Saves where you are under a name (replacing a waypoint of the same name). */
	public static Waypoint addHere(MinecraftClient client, String name) {
		Ledger ledger = NaughtyOrNiceClient.INSTANCE.ledger;
		List<Waypoint> list = waypoints(ledger);
		String clean = name.strip();
		if (clean.isEmpty() || client.player == null || client.world == null) return null;
		list.removeIf(w -> w.name.equalsIgnoreCase(clean));
		Waypoint w = new Waypoint();
		w.name = clean.length() > 32 ? clean.substring(0, 32) : clean;
		w.x = client.player.getBlockX();
		w.y = client.player.getBlockY();
		w.z = client.player.getBlockZ();
		w.dimension = client.world.getRegistryKey().getValue().toString();
		w.time = System.currentTimeMillis();
		list.add(w);
		save(ledger);
		return w;
	}

	public static void remove(String name) {
		Ledger ledger = NaughtyOrNiceClient.INSTANCE.ledger;
		if (waypoints(ledger).removeIf(w -> w.name.equalsIgnoreCase(name))) save(ledger);
	}

	/** Everything, nearest first in your own dimension, then the rest. */
	public static List<Destination> all(MinecraftClient client) {
		Ledger ledger = NaughtyOrNiceClient.INSTANCE.ledger;
		List<Destination> list = new ArrayList<>();
		for (Waypoint w : waypoints(ledger)) list.add(new Destination(Kind.WAYPOINT, w.name, "Saved " + ShopScreen.ago(w.time), w.x, w.y, w.z, w.dimension, w.time));
		for (BaseInsuranceBridge.BaseArea b : BaseInsuranceBridge.bases()) {
			list.add(new Destination(Kind.BASE, b.name(), "Base Insurance", (b.minX() + b.maxX()) / 2, Integer.MIN_VALUE, (b.minZ() + b.maxZ()) / 2, b.dimension(), 0));
		}
		for (ChunkTrails.Trail t : ledger.chunkTrails.trails) {
			int[] end = t.awayEnd();
			String runs = ChunkTrails.compass(t.x2 - t.x1, t.z2 - t.z1) + "–" + ChunkTrails.compass(t.x1 - t.x2, t.z1 - t.z2);
			list.add(new Destination(Kind.TRAIL, String.format(Locale.ROOT, "Trail %,d blocks, %s", t.length(), runs),
					t.away == 0 ? "One end" : "Far end, away from spawn", end[0], Integer.MIN_VALUE, end[1], t.dimension, t.lastSeen));
		}
		for (Profile p : ledger.all()) {
			for (PlayerStats.Place pl : p.stats.places) {
				list.add(new Destination(Kind.PLACE, p.name + ": " + ShopScreen.howLabel(pl.how).toLowerCase(Locale.ROOT),
						pl.how.equals("said") || pl.how.equals("highway") ? "\"" + pl.text + "\"" : pl.text, pl.x, pl.hasY() ? pl.y : Integer.MIN_VALUE, pl.z, pl.dimension, pl.time));
			}
			if (p.verdict == Profile.Verdict.NONE && p.groups.isEmpty()) continue;
			Profile.Sighting sg = p.lastSighting();
			if (sg != null) list.add(new Destination(Kind.SEEN, p.name, "Last seen " + ShopScreen.ago(sg.until), sg.lastX, sg.lastY, sg.lastZ, sg.dimension, sg.until));
		}
		for (ServerActivity.WorldEvent e : ledger.activity.events) {
			String what = switch (e.kind) {
				case "end_portal" -> "End portal lit (a stronghold)";
				case "wither" -> "A wither spawned";
				default -> "The dragon died";
			};
			list.add(new Destination(Kind.EVENT, what, e.who.isEmpty() ? "Heard server-wide" : "By " + e.who, e.x, e.y, e.z, e.dimension, e.time));
		}
		String clip = client.keyboard.getClipboard();
		if (clip != null && clip.length() < 300) {
			for (ChatIntel.Coord c : ChatIntel.coords(clip)) {
				list.add(new Destination(Kind.CLIPBOARD, "On your clipboard", clip.strip(), c.x(), c.y(), c.z(), c.dimension() == null ? "" : c.dimension(), System.currentTimeMillis()));
				break;
			}
		}
		String here = client.world == null ? "" : client.world.getRegistryKey().getValue().toString();
		double px = client.player == null ? 0 : client.player.getX(), pz = client.player == null ? 0 : client.player.getZ();
		list.sort(Comparator.comparing((Destination d) -> !(d.dimension().isEmpty() || d.dimension().equals(here)))
				.thenComparingDouble(d -> Math.hypot(d.x() - px, d.z() - pz)));
		return list;
	}
}
