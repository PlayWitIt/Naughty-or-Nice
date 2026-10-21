package com.greysworkshop.naughtyornice;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Talks to Base Insurance (the companion mod), if it's installed: which base a block belongs to, and keeping
 * its friends list in step with the Nice list. Uses reflection so neither mod needs the other.
 */
public final class BaseInsuranceBridge {
	private static boolean resolved;
	private static Object client;
	private static Field managerField;
	private static Method inDimension;
	private static Method contains;
	private static Field nameField;
	private static Field configInstance;
	private static Field friendsField;
	private static Method saveConfig;
	private static Method allBases;
	private static Field dimensionField;
	private static Field minXField, maxXField, minZField, maxZField;

	private BaseInsuranceBridge() {}

	public static boolean present() {
		return FabricLoader.getInstance().isModLoaded("baseinsurance");
	}

	private static boolean resolve() {
		if (resolved) return managerField != null;
		resolved = true;
		if (!present()) return false;
		try {
			Class<?> clientClass = Class.forName("com.greysworkshop.baseinsurance.BaseInsuranceClient");
			Class<?> managerClass = Class.forName("com.greysworkshop.baseinsurance.BaseManager");
			Class<?> baseClass = Class.forName("com.greysworkshop.baseinsurance.Base");
			Class<?> configClass = Class.forName("com.greysworkshop.baseinsurance.BIConfig");
			client = clientClass.getField("INSTANCE").get(null);
			inDimension = managerClass.getMethod("inDimension", String.class);
			contains = baseClass.getMethod("contains", BlockPos.class);
			nameField = baseClass.getField("name");
			configInstance = configClass.getField("INSTANCE");
			friendsField = configClass.getField("friends");
			saveConfig = configClass.getMethod("save");
			allBases = managerClass.getMethod("all");
			dimensionField = baseClass.getField("dimension");
			minXField = baseClass.getField("minX");
			maxXField = baseClass.getField("maxX");
			minZField = baseClass.getField("minZ");
			maxZField = baseClass.getField("maxZ");
			managerField = clientClass.getField("manager");
			return true;
		} catch (ReflectiveOperationException | LinkageError e) {
			NaughtyOrNiceClient.LOGGER.warn("Base Insurance is installed but couldn't be linked up: {}", e.toString());
			managerField = null;
			return false;
		}
	}

	/** The name of the insured base containing this block, or null. */
	public static String baseAt(String dimension, BlockPos pos) {
		if (!resolve()) return null;
		try {
			Object manager = managerField.get(client);
			for (Object base : (List<?>) inDimension.invoke(manager, dimension)) {
				if ((boolean) contains.invoke(base, pos)) return (String) nameField.get(base);
			}
		} catch (ReflectiveOperationException | ClassCastException e) {
			NaughtyOrNiceClient.LOGGER.debug("Base lookup failed", e);
		}
		return null;
	}

	/** One of your insured bases: its name, dimension and area. */
	public record BaseArea(String name, String dimension, int minX, int maxX, int minZ, int maxZ) {
		/** Blocks from the base's edge to x/z (0 inside it). */
		public double distance(double x, double z) {
			double dx = Math.max(0, Math.max(minX - x, x - maxX));
			double dz = Math.max(0, Math.max(minZ - z, z - maxZ));
			return Math.hypot(dx, dz);
		}
	}

	/** Your insured bases on this server, or none without Base Insurance. */
	public static List<BaseArea> bases() {
		if (!resolve()) return List.of();
		List<BaseArea> result = new java.util.ArrayList<>();
		try {
			Object manager = managerField.get(client);
			for (Object base : (List<?>) allBases.invoke(manager)) {
				result.add(new BaseArea((String) nameField.get(base), (String) dimensionField.get(base), minXField.getInt(base), maxXField.getInt(base),
						minZField.getInt(base), maxZField.getInt(base)));
			}
		} catch (ReflectiveOperationException | ClassCastException e) {
			NaughtyOrNiceClient.LOGGER.debug("Base list failed", e);
		}
		return result;
	}

	/** Puts pins on Base Insurance's map where players from your lists were last seen. */
	public static void registerMapPins(java.util.function.Function<String, List<java.util.Map<String, Object>>> provider) {
		if (!present()) return;
		try {
			Class.forName("com.greysworkshop.baseinsurance.MapMarkers").getMethod("register", java.util.function.Function.class).invoke(null, provider);
		} catch (ReflectiveOperationException | LinkageError e) {
			// An older Base Insurance without map pins.
			NaughtyOrNiceClient.LOGGER.info("Base Insurance has no map pins (update it to see sightings on its map)");
		}
	}

	/** Nice-list players become Base Insurance friends; anyone we added stops being one when they leave the list. */
	@SuppressWarnings("unchecked")
	public static void syncFriend(Profile profile) {
		if (!NNConfig.INSTANCE.shareWithBaseInsurance || !resolve()) return;
		try {
			List<String> friends = (List<String>) friendsField.get(configInstance.get(null));
			boolean listed = friends.stream().anyMatch(f -> f.equalsIgnoreCase(profile.name));
			boolean changed = false;
			if (profile.verdict == Profile.Verdict.NICE && !listed) {
				friends.add(profile.name);
				profile.sharedFriend = true;
				changed = true;
			} else if (profile.verdict == Profile.Verdict.NAUGHTY || (profile.verdict != Profile.Verdict.NICE && profile.sharedFriend)) {
				changed = friends.removeIf(f -> f.equalsIgnoreCase(profile.name));
				profile.sharedFriend = false;
			}
			if (changed) saveConfig.invoke(null);
		} catch (ReflectiveOperationException | ClassCastException | UnsupportedOperationException e) {
			NaughtyOrNiceClient.LOGGER.debug("Friend sync failed", e);
		}
	}
}
