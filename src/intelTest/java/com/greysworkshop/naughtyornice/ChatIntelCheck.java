package com.greysworkshop.naughtyornice;

import com.greysworkshop.naughtyornice.ChatIntel.*;
import java.util.*;

/** Every line here is read by ChatIntel and must come out as written: run with ./gradlew testIntel. */
public class ChatIntelCheck {
	static int pass, fail;
	static Set<String> NAMES = Set.of("steve", "griefer_gary", "alex", "scgconst", "notheybge");

	static void coords(String line, String expect) {
		List<Coord> c = ChatIntel.coords(line);
		String got = c.isEmpty() ? "none" : String.join(" | ", c.stream().map(x -> x.text() + (x.dimension() == null ? "" : " " + x.dimension().replace("minecraft:", ""))).toList());
		if (got.equals(expect)) pass++; else { fail++; System.out.println("COORDS FAIL: \"" + line + "\"\n   got " + got + "\n  want " + expect); }
	}
	static void topics(String line, String expect) {
		Set<Topic> t = ChatIntel.topics(line, NAMES);
		String got = t.isEmpty() ? "none" : String.join(",", t.stream().map(Enum::name).toList());
		if (got.equals(expect)) pass++; else { fail++; System.out.println("TOPIC FAIL: \"" + line + "\"\n   got " + got + "\n  want " + expect); }
	}
	static void tone(String line, String expect) {
		String got = ChatIntel.tone(line, NAMES).name();
		if (got.equals(expect)) pass++; else { fail++; System.out.println("TONE FAIL: \"" + line + "\" got " + got + " want " + expect); }
	}

	public static void main(String[] a) {
		// --- coordinates that ARE places ---
		coords("x: 1200 z: -3400", "1200, -3400");
		coords("X 1200 Y 64 Z -3400", "1200, 64, -3400");
		coords("x1200 z-3400", "1200, -3400");
		coords("my base is at 1200 -3400", "1200, -3400");
		coords("1200 -3400", "1200, -3400");
		coords("1200, -3400", "1200, -3400");
		coords("1200,-3400", "1200, -3400");
		coords("1200/-3400", "1200, -3400");
		coords("meet me at 500 600", "500, 600");
		coords("coords 1200 64 -3400", "1200, 64, -3400");
		coords("coords are 1200, -3400 in the nether", "1200, -3400 the_nether");
		coords("stash at 12k -4k", "12000, -4000");
		coords("its at 1.5k 2k ow", "1500, 2000 overworld");
		coords("XYZ: 1200.523 / 64.00000 / -400.123", "1201, 64, -400");
		coords("1200.5 / 70 / -400.2", "1201, 70, -400");
		coords("1200 64 -3400", "1200, 64, -3400");
		coords("-1200 70 3400 come here", "-1200, 70, 3400");
		coords("on the -x highway at 20k", "-20000, 0");
		coords("-x 20k", "-20000, 0");
		coords("+x+z hwy 5000", "5000, 5000");
		coords("+x-z 15k nether", "15000, -15000 the_nether");
		coords("xz: 300 -900", "300, -900");
		coords("im at 4500 -2300 rn", "4500, -2300");
		coords("tp to 1000 1000", "1000, 1000");
		coords("go to 100000 -50000", "100000, -50000");
		coords("found a base at x 25000 z 31000", "25000, 31000");
		coords("x=100 z=200", "100, 200");
		coords("portal at 125 -400", "125, -400");
		coords("it's around 9000 9000", "9000, 9000");
		coords("1,200 -3,400 nether", "1200, -3400 the_nether");
		// --- numbers that are NOT places ---
		coords("I have 1000 diamonds and 200 gold", "none");
		coords("selling 1000 2000 stacks", "none");
		coords("1000 2000 diamonds", "none");
		coords("it costs $500 600", "none");
		coords("my ping is 150 200 ms", "none");
		coords("played from 2019 2024", "none");
		coords("1920 1080 monitor", "none");
		coords("update 1.21.11 is out", "none");
		coords("10/21/2026", "none");
		coords("150 200 300", "none");
		coords("i got 1500 kills and 300 deaths", "none");
		coords("the server has 120 players", "none");
		coords("wait 100 200 seconds", "none");
		coords("lol 100 200", "none");
		coords("im 1000 2000 blocks away", "none");
		coords("xX_420_Xx 1200", "none");
		coords("my render distance is 32", "none");
		coords("version 1.20.4 to 1.21", "none");
		coords("give me 5 10 totems", "none");
		coords("ow that hurt", "none");
		coords("it took 100 hours and 500 mins", "none");
		coords("2x 64 stacks", "none");
		coords("1000 2000 per hour", "none");
		coords("he has 900 300 iq", "none");

		// --- topics ---
		topics("im gonna grief your base", "THREAT,GRIEF");
		topics("i know where your stash is", "THREAT,STASH");
		topics("found your base lol", "THREAT,STASH");
		topics("coming for you", "THREAT");
		topics("im going to find steve's base", "THREAT");
		topics("we will raid griefer_gary", "THREAT");
		topics("gonna find food", "none");
		topics("coming for the drop party", "none");
		topics("i'm going to kill the dragon", "none");
		topics("found a stash at spawn", "STASH");
		topics("base hunting rn", "STASH");
		topics("where's your base", "STASH");
		topics("Doesn't matter what you say, we will always keep griefing the nether highways", "GRIEF");
		topics("someone lavacasted spawn", "GRIEF");
		topics("withered their base", "GRIEF");
		topics("anyone got a dupe", "DUPE");
		topics("duping is dead", "DUPE");
		topics("i duplicate my files", "none");
		topics("using meteor client", "HACKS");
		topics("the meteor shower was cool", "none");
		topics("in the future i will build", "none");
		topics("he uses freecam", "HACKS");
		topics("xray is so op", "HACKS");
		topics("wts 5 dubs of totems", "TRADE");
		topics("selling enchanted golden apples", "TRADE");
		topics("buying a house irl", "none");
		topics("meet me at spawn", "MEETUP");
		topics("where are you", "MEETUP");
		topics("where are you from", "none");
		topics("1v1 me", "PVP");
		topics("crystal pvp is fun", "PVP");
		topics("thats my alt", "ALTS");
		topics("press alt f4", "none");
		topics("UnlawfulDuc is an alt of LawfulDuc", "ALTS");
		topics("nice weather today", "none");

		// --- tone ---
		tone("thanks for the help", "KIND");
		tone("ty", "KIND");
		tone("gg", "KIND");
		tone("gg ez", "RUDE");
		tone("ez", "RUDE");
		tone("ez steve", "RUDE");
		tone("no thanks", "NONE");
		tone("kys", "RUDE");
		tone("you're trash", "RUDE");
		tone("u r a noob", "RUDE");
		tone("your trash lol", "RUDE");
		tone("take your trash out", "NONE");
		tone("taking out the trash", "NONE");
		tone("pizza is trash", "NONE");
		tone("steve is trash", "RUDE");
		tone("welcome to hell", "NONE");
		tone("nice base", "NONE");
		tone("you're not bad", "NONE");
		tone("uninstall the mod and try again", "NONE");
		tone("the ratio of iron to gold", "NONE");
		tone("stfu", "RUDE");
		tone("skill issue", "RUDE");
		tone("this is easy", "NONE");
		tone("np man", "KIND");
		tone("good luck soldier o7", "KIND");
		tone("can u stfu with ur mommie issues", "RUDE");
		tone("is it a noob trap", "NONE");
		tone("what a noob", "NONE");

		coords("150 200 300 1200 64 -3400", "none");
		coords("my base: 1200 -3400 and stash: -500 900", "1200, -3400 | -500, 900");
		coords("i have 64 stacks 1200 3400", "none");
		coords("x 5 z 7", "5, 7");
		coords("send coords", "none");
		coords("at 5 o clock", "none");
		coords("the year 2024 2025 was wild at spawn", "none");
		coords("-500", "none");
		coords("im at -500", "none");
		coords("tpa 1200 3400", "1200, 3400");
		coords("highway -x 500k", "-500000, 0");
		coords("he died at 100 200", "100, 200");
		coords("kd 1200 300", "none");
		coords("1200 3400 lol", "1200, 3400");
		topics("gonna grief", "GRIEF");
		topics("he found steve's base", "THREAT,STASH");
		topics("i hate griefers", "GRIEF");
		tone("ur mom", "NONE");
		tone("you suck", "NONE");
		tone("ty bro", "KIND");
		tone("L", "RUDE");
		tone("nobody asked", "RUDE");
		tone("ez clap", "RUDE");
		tone("you are all trash lol", "RUDE");
		tone("yall are trash", "RUDE");
		tone("you guys are noobs", "RUDE"); tone("you are bosses", "NONE");
		tone("you all are so bad", "RUDE");
		tone("are you all ok", "NONE");
		System.out.println("ChatIntel: " + pass + " passed, " + fail + " failed");
		if (fail > 0) System.exit(1);
	}
}
