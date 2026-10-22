package com.greysworkshop.naughtyornice;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Totems each player has popped in front of you since they last died (or since you joined), shown after their name.
 * Counted from the server's own "used a totem" signal, so every count is a real pop.
 */
public final class TotemCounter {
	private static final Map<UUID, Integer> pops = new HashMap<>();

	private TotemCounter() {}

	static void popped(UUID id) {
		pops.merge(id, 1, Integer::sum);
	}

	static void died(UUID id) {
		if (id != null) pops.remove(id);
	}

	static void clear() {
		pops.clear();
	}

	public static int pops(UUID id) {
		return pops.getOrDefault(id, 0);
	}
}
