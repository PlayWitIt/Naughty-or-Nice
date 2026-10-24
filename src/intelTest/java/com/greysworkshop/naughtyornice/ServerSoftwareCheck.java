package com.greysworkshop.naughtyornice;

import java.util.*;

/** What ServerSoftware reads from brands and command lists; run with ./gradlew testIntel (after ChatIntelCheck). */
public class ServerSoftwareCheck {
	static int pass, fail;

	static void eq(String what, Object got, Object want) {
		if (Objects.equals(got, want)) pass++; else { fail++; System.out.println("SERVER FAIL: " + what + "\n   got " + got + "\n  want " + want); }
	}

	static String plugins(String... commands) {
		return String.join(", ", ServerSoftware.plugins(List.of(commands)).stream().map(p -> p.name() + (p.note() == ServerSoftware.Note.NONE ? "" : "[" + p.note().name() + "]")).toList());
	}

	public static int run() {
		eq("plain brand", Arrays.asList(ServerSoftware.brand("Paper")), List.of("", "Paper"));
		eq("velocity arrow", Arrays.asList(ServerSoftware.brand("Velocity (git-8a3c) -> Purpur")), List.of("Velocity", "Purpur"));
		eq("bungee arrow", Arrays.asList(ServerSoftware.brand("BungeeCord (git:BungeeCord-Bootstrap:1.21-R0.1:abc:1900) <- Paper")), List.of("BungeeCord", "Paper"));
		eq("velocity in brackets", Arrays.asList(ServerSoftware.brand("Paper (Velocity)")), List.of("Velocity", "Paper"));
		eq("no brand", Arrays.asList(ServerSoftware.brand(null)), List.of("", ""));
		eq("vanilla", Arrays.asList(ServerSoftware.brand("vanilla")), List.of("", "vanilla"));

		eq("core namespaces aren't plugins", plugins("minecraft:tell", "bukkit:version", "paper:paper", "spigot:tps", "msg"), "");
		eq("namespaces are plugins", plugins("essentials:home", "essentials:tpa", "coreprotect:co", "grim:grim", "somecustom:thing"),
				"CoreProtect[LOGGING], EssentialsX, Grim[ANTICHEAT], somecustom");
		eq("known commands without namespaces", plugins("co", "lp", "dynmap", "grimac", "home"), "CoreProtect[LOGGING], Dynmap[WEB_MAP], Grim[ANTICHEAT], LuckPerms");
		eq("common words alone are nobody's", plugins("f", "tab", "res", "plot", "spark", "region"), "");
		eq("...but as namespaces they are", plugins("tab:tab", "spark:spark"), "spark (lag profiler), TAB");
		eq("evidence lists the commands", ServerSoftware.plugins(List.of("worldguard:rg", "worldguard:region")).get(0).evidence(), List.of("/worldguard:rg", "/worldguard:region"));
		eq("core from commands", ServerSoftware.coreFromCommands(List.of("bukkit:ver", "paper:paper", "purpur:purpur")), List.of("Purpur", "Paper", "Bukkit"));

		eq("bungee channel", ServerSoftware.channel("bungeecord:main"), "A BungeeCord or Velocity proxy");
		eq("unknown channel shows its namespace", ServerSoftware.channel("myserver:sync"), "myserver");
		eq("vanilla channel says nothing", ServerSoftware.channel("minecraft:register"), "");
		System.out.println("ServerSoftware: " + pass + " passed, " + fail + " failed");
		return fail;
	}
}
