package com.greysworkshop.naughtyornice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Other accounts that may be the same person, from everything that points that way: the same skin (now or before),
 * a skin borrowed from the other account, taking turns being online without ever being on together, names that are
 * nearly the same, and the same ping. Each counts for points; enough of them together makes a suspect. None of it
 * is proof on its own, so every suspect comes with its reasons.
 */
public final class Alts {
	/** Points needed to be shown. */
	public static final int SHOWN = 40;

	public record Suspect(Profile other, int score, List<String> reasons) {}

	private Alts() {}

	/** Possible alts of {@code p}, likeliest first. */
	public static List<Suspect> of(Ledger ledger, Profile p) {
		List<Suspect> found = new ArrayList<>();
		for (Profile q : ledger.all()) {
			if (q == p || q.placeholder) continue;
			Suspect s = judge(ledger, p, q);
			if (s.score() >= SHOWN) found.add(s);
		}
		found.sort(Comparator.comparingInt(Suspect::score).reversed());
		return found;
	}

	static Suspect judge(Ledger ledger, Profile p, Profile q) {
		PlayerStats a = p.stats, b = q.stats;
		int score = 0;
		List<String> why = new ArrayList<>();
		if (!a.skin.isEmpty() && a.skin.equals(b.skin)) {
			score += 45;
			why.add("same skin");
		} else if (shared(a, b)) {
			score += 30;
			why.add("wore the same skin");
		}
		if (!a.skinOwner.isEmpty() && a.skinOwner.equalsIgnoreCase(q.name)) {
			score += 40;
			why.add("uses " + q.name + "'s skin");
		} else if (!b.skinOwner.isEmpty() && b.skinOwner.equalsIgnoreCase(p.name)) {
			score += 40;
			why.add(q.name + " uses their skin");
		} else if (!a.skinOwner.isEmpty() && a.skinOwner.equalsIgnoreCase(b.skinOwner)) {
			score += 30;
			why.add("both use " + a.skinOwner + "'s skin");
		}
		Links.Link link = p.id == null || q.id == null ? null : ledger.links.get(p.id, q.id);
		if (link != null) {
			if (ledger.links.looksLikeAlt(link)) {
				score += 45;
				why.add("took turns " + link.swaps + "× and never on together");
			} else if (link.swaps >= 2 && link.minutesBothOn == 0) {
				score += 15;
				why.add("took turns " + link.swaps + "×");
			}
			if (link.minutesBothOn > 0) score -= 15;
		}
		if (similarNames(p.name, q.name)) {
			score += 20;
			why.add("similar names");
		}
		// Ping says roughly where someone plays from; the same to within 3 ms over many samples is a small hint.
		if (a.pingSamples >= 20 && b.pingSamples >= 20 && Math.abs(a.averagePing() - b.averagePing()) <= 3) {
			score += 10;
			why.add("same ping (" + a.averagePing() + " ms)");
		}
		return new Suspect(q, Math.max(0, Math.min(100, score)), why);
	}

	private static boolean shared(PlayerStats a, PlayerStats b) {
		List<String> mine = new ArrayList<>(a.pastSkins);
		if (!a.skin.isEmpty()) mine.add(a.skin);
		for (String s : mine) {
			if (s.equals(b.skin) || b.pastSkins.contains(s)) return true;
		}
		return false;
	}

	/** "Steve_Alt" and "Steve", "xSteve2" and "Steve_": the same once digits, underscores and x's are taken off, or a letter or two apart. */
	static boolean similarNames(String x, String y) {
		String a = core(x), b = core(y);
		if (a.length() < 4 || b.length() < 4) return false;
		if (a.equals(b)) return true;
		if (Math.min(a.length(), b.length()) >= 5 && (a.contains(b) || b.contains(a))) return true;
		return Math.min(a.length(), b.length()) >= 6 && distance(a, b) <= 1;
	}

	private static String core(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[0-9_]", "").replaceAll("^x+|x+$", "");
	}

	private static int distance(String a, String b) {
		int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) prev[j] = j;
		for (int i = 1; i <= a.length(); i++) {
			cur[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
			}
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return prev[b.length()];
	}
}
