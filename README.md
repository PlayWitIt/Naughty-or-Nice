# Naughty or Nice

A client-side Fabric mod for Minecraft 1.21.11 that keeps your **Friends** (each with a friend type you name) and a **Nice list**, a
**Look Into list** and a **Naughty list** of players, with a store-style screen where one click puts someone on a list.

By **PlayWit Creations**.

## Installing
1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.11**, plus
   [Fabric API](https://modrinth.com/mod/fabric-api).
2. Get `naughty-or-nice-<version>+1.21.11.jar` from the
   [Releases](https://github.com/PlayWitIt/Naughty-or-Nice/releases) page (or build it, see below).
3. Put it in your `.minecraft/mods` folder and start the game with the Fabric profile.

Optional: [Mod Menu](https://modrinth.com/mod/modmenu) for a settings button, and Base Insurance for base-aware
features. To share lists with a friend who has the mod, use **⇄ Share** in the lists screen.

**Mods panel:** press **Right Shift** on the title screen, the server or world list, or the pause menu, or click
**Mods panel** at the top left of the title screen. It shows the main switches of every PlayWit mod you have (Naughty or Nice,
Base Insurance, Hoard Eye), so you can turn something off before you join a world, and opens each one's full settings.
It also has:
- **Profiles:** save how your switches are set under a name ("Anarchy", "SMP") and pick the servers it's for. Joining one
  of them sets the switches for you.
- **Backups:** one click saves everything the mods keep (lists, notes, chat logs, recordings, bases, maps, containers,
  settings) to config/playwit/backups. Restoring happens the next time you start the game, after what you have now is
  backed up too.
- **Stream-safe mode** (**F7** in game, or the switch in the panel): hides coordinates everywhere in the mods, Base
  Insurance's minimap and map, and the coordinates in F3, while you record or stream.

## How to use it
- Press **N** to open it. If you're looking at a player, it opens their page. Press **J** for the chunk trail radar, **K** for chunks in the world, **B** for Baritone, **Z** to stop Baritone.
- Every player is shown as a card with their face, a star rating, a **☆** in the corner to make them a friend, and three buttons: **✔ Nice**, **? Look Into** (not
  sure yet, keep an eye on them) and **✖ Naughty**. Click again to take them off. Moving someone who's already on a list
  (or taking them off) asks first: the button says **Sure?** and the banner asks with a **Yes**, so a slip of the mouse
  doesn't do it. Every change can be undone from the banner at the top.
- **Naughty or Nice on/off:** the first switch in Settings (and in the Mods panel) turns the whole mod off. Your lists
  are kept.
- **+ Add players** opens a panel where you type one or more names (spaces or commas between them), pick from
  suggestions, add an optional detail and group, and press **Nice list**, **Look Into** or **Naughty list**. Works for
  offline players too.
- Search finds players by name, their details, their groups, or anything they've said in chat.
- Tabs: All, Nearby, Online, **Offline**, **Friends**, Nice list, **Look Into** (your Look Into list, then players whose behaviour
  suggests a list), Naughty list, **Groups**, **Activity**, **Server**, **Intel**, **Chat**, Settings.
- Everyone you see (nearby or on the tab list) is remembered, so they're still listed after logging off. Players who
  never did anything are forgotten after 180 days unseen (configurable).
- Light and dark themes: the ☾ / ☀ button at the top (dark by default).
- Comfortable at any GUI scale: the screen picks its own size from the window, whatever the game's GUI scale.
  Settings → **Screen size**: Small fits the most (about 900 wide), Medium is in between (about 680), Large is the
  biggest (about 540, never so big the panels don't fit). Whole-pixel scales are used where they're close, so text
  stays sharp.
- **Your coordinates** show in green in the bottom-right corner, clear of the chat (x y z, facing, and the matching
  nether or overworld spot), a size smaller than the rest of the HUD and still sharp, on a dark backing. Settings
  move them to the bottom left (under the chat) or make them full size. Not showing? `/nn coords` says why and turns
  them back on.
- Everything you set (here, in the Baritone screen, highway roam, Baritone's own settings, how the lists are sorted)
  is saved and stays until you change it.
- Commands: `/nn`, `/nn add`, `/nn friends`, `/nn chat`, `/nn groups`, `/nn server`, `/nn intel`, `/nn coords`, `/nn share`, `/nn import`, `/nn open <link>`,
  `/nn disconnects`, `/nn sendanyway`, `/nn radar`, `/nn events`, `/nn baritone`, `/nn friend <name> [type]`, `/nn nice <name>`, `/nn look <name>`, `/nn naughty <name>`,
  `/nn unsort <name>`, `/nn group <name> <group>`, `/nn who <name>`, `/nn chat <name>`, `/nn settings`.

## Friends
Friends are people you actually know, kept apart from Nice (behaves well), Look Into and Naughty. Each friend has a
**friend type** saying how you know them: Close friends, Base mates and Allies to start with. Rename, recolour, reorder,
add your own (up to 8) or remove them in Settings → **Friend types**. Friend types always show with a **★**.

Friend types aren't groups: a **friend type** is how *you* know someone (only friends have one); a **group** is who plays
*together* (a crew, a team, someone's alts), and anyone can be in one, friend or Naughty. Groups show as square tags
like `[SCG]`.
- Press the **☆** in a card's corner to make someone a friend (they get the top type, or the one you're looking at).
  Press the **★** again to change their type or stop being friends. The player page, the Add players panel (pick the
  type, then **★ Friends**) and `/nn friend <name> [type]` work too.
- The **Friends** tab shows them all, or one type at a time, with how many of each are online.
- Friends get a ★ in their type's colour on nametags, in the tab list, in chat and on Base Insurance's map.
- Per type: **Trusted at my bases** (Base Insurance never alerts about them or names them as suspects) and **Ping
  when they join or come near**.
- Shared lists send your friends as Nice (older versions understand that), marked "their friend". Nothing a friend
  shares ever takes your friends off your Friends.

## Notes about one player
On any player's page, **Notify me when they**: **chat** (a ping with what they said) and **log in or out**. On the
**Look Into** tab the same two switches are right on each card (**♪ Chat**, **♪ Log in/out**; ✔ when on).

## Activity: when the server is busy
The **Activity** tab shows when the server you're on is busy and quiet, from your own time on it: once a minute while
you play, it counts who's on.
- **Busiest** and **quietest** three-hour stretches, and whether **right now** is busier or quieter than usual.
- **Players online by hour of day**: a bar per hour (the busiest stretch shaded, now marked). Until an hour has 10
  minutes of your time measured, it's estimated from when players were seen on, and drawn as an outline.
- **By day and hour**: the week as a grid, darker blue (lighter on the dark theme) for busier; hours you haven't been
  on yet are empty outlines.
- **When your lists are on**: when your Friends, Nice, Look Into and Naughty players are usually online.
- Hover any bar or square for the numbers: about how many, the most at once, and how much of your time it's from.

## Server: everything it shows about itself
The **Server** tab (or `/nn server`) lists everything a server tells a player, and nothing is sent to it to find out.
A coloured mark on each line says how it's known: the server said so (blue), it was seen happening (amber), or it
can't be known from a player's side (grey).
- **The server**: address, message of the day, version and protocol (and whether ViaVersion is translating), the
  software from its brand (Paper, Purpur, Fabric...), a proxy in front (Velocity, BungeeCord), slots, secure chat,
  and its links (Discord, website) to open.
- **How it's running**: ticks per second, measured from the server's own clock, now and over a minute, the slowest
  minute since you joined, a warning when it stalls, your ping and everyone's, and how much is loaded around you.
- **The world**: difficulty (and whether it's locked), hardcore, dimensions, build height, sea level, time and
  weather, world spawn, world border (and your distance to its edge), the server's view and simulation distance,
  and experiments.
- **Game rules**: the ones that reach players (doDaylightCycle, doImmediateRespawn, reducedDebugInfo,
  doLimitedCrafting), the ones that can be seen (death messages, PvP), and a plain note that the rest
  (keepInventory, mobGriefing...) never leave the server.
- **You**: game mode, operator level, how many commands you may use.
- **Plugins its commands show**: every `plugin:command` namespace, plus well-known plugins by their own commands.
  Anticheats, block loggers (CoreProtect: griefs can be rolled back) and live web maps (Dynmap, BlueMap: your base
  may be on a public map) are shown in red. Your own mods' commands are left out.
- **Plugin channels** and **data packs** (custom biomes, enchantments and the like, by namespace).
- **Tab list and sidebar**: the tab list's top and bottom text and the sidebar's lines.
- **Changed since your last visit**: new or removed plugins, a new version or software.

**Copy report** and **Save report** (to `config/naughtyornice/server-reports`) give all of it as text, with every
command you can use.

## Groups
Group people you know are teamed up or associated: a base crew, a griefing group, someone's alts. Make one from a
player's page (**+ Add to a group**), the **Groups** tab (**+ New group**), the Add players panel (its Group box), or
`/nn group <name> <group>`. Type several names at once; nearby players are suggested first.
- The **Groups** tab shows every group with how its members are listed (✔ ? ✖) and who's online. Open one to see its
  members as cards, add members, rename it, change its colour (click the swatch) or delete it (members stay listed).
- A player's groups show as coloured tags on their card, on their page (with who they're grouped with and how many of
  those are on your Naughty list), on nametags and in the tab list (`[SCG]`), and in the Chat tab.
- Groups travel with shared lists.

## Chat tab
A chat window where everyone is marked so you can tell who's who at a glance: a badge for their list (★ Friend,
✔ Nice, ? Look Into, ✖ Naughty, • not listed, ◆ you), their group tags, and their name in their list's colour. Naughty and
Look Into lines are lightly tinted.
- Filter by list (All, On a list, Friends, Nice, Look Into, Naughty, Not listed, Whispers) or by group; the search box filters
  too. "Talking lately" shows who's been chatting.
- Hover a line for ★ ✔ ? ✖ buttons to list whoever said it; click the line to open their page.
- Type in the box at the bottom to talk (Enter sends, Up/Down for what you've sent, `/` for commands).
- Scroll up to read back; new lines won't move what you're reading, and a button jumps back to the newest.

## Details
Each player page has a **Details** tab: a list of things worth remembering about them (alts, where their base is, who
they play with, what they did). Type in the box and press Enter or **Add**; one-click starters ("Alt of…", "Base
near…", "Friends with…") get you going. Every detail shows who wrote it and when, and can be edited or removed (with
undo). A coloured stripe shows whose it is: yours, a friend's, or what a friend's mod saw. The newest detail shows on the
player's card, and search looks through them. Notes from older versions become details automatically.

## Sharing with friends
**⇄ Share** (above the list, in the sidebar, or `/nn share`) sends your lists to a friend who has the mod:
- Pick what to send: your Friends and lists, everyone with details or history, or everyone you've seen; chat logs are
  optional. Groups go along.
- **🔗 Get share link** makes a short link like `NN2:bAbC123xyz.k3y…` and copies it. Paste it anywhere: Discord, a
  whisper, a sign. Or click an online player's name under **Send in game** and it's whispered to them (`/msg`, can be
  changed in the settings file).
- The lists are locked (AES-GCM) with a new random key that only exists inside the link, then put on a paste site
  (bytebin.lucko.me, the one LuckPerms and spark use; paste.rs if that's down). The site only ever sees scrambled
  bytes. Links expire after a while; make a new one any time.
- When someone sends you a link in chat, a **[Look at them]** button appears (not for Naughty-list players).
  Nothing changes until you press Import.
- Other ways, for no internet or very big lists: **Copy long code** (`NN1:…`) and **Save as file** (a `.nnshare` file
  in `config/naughtyornice/shared/`).
- **⇄ Share this player** on a player page copies a link for just them.

Your friend clicks the button, uses `/nn open <link>`, or opens **Get from a friend** and pastes the link or code
(a copied link is picked up by itself), drops a file onto the game window, or picks it from their shared folder. Before anything changes they see a preview: who's new, who gets new details, and
where you disagree (highlighted). By default their lists fill in players you haven't listed, and yours wins where you
disagree; both can be changed. Details arrive marked with who they came from, what the sender's mod saw comes along
in their words ("Killed Alex ×2"), and the whole import can be undone from the banner.

## Chat log
Everyone on your Nice, Look Into or Naughty list has their chat logged. When they say something, a conversation opens on their page:
it starts with the last minute of chat before they spoke (so you see what they were answering), takes in everything said
after (replies, your own messages, their whispers to you), and closes once they've been quiet for 3 minutes. Their own
lines are highlighted. Conversations can be copied, and each finished one is also appended to a text file per player in
`config/naughtyornice/chatlogs/<server>/`. Plain `<name>` chat, ranked formats like `[VIP] name » hi`, and common
whisper formats are all recognised. Putting someone on a list also catches up on what they said in the last few minutes.
All the timings are in Settings.

## What it watches
Things other players do around you build their rating and activity history:

| Counts against them | Counts for them |
| --- | --- |
| Killing you (and puts them on the Naughty list, with Undo) | Time spent near you without trouble |
| Hitting you, hurting or killing your pets | Friendly chat (thanks, gg, np...) |
| Killing or attacking other players | |
| Breaking blocks in your bases (needs Base Insurance) | |
| Trash talk aimed at someone ("you're trash", "kys", "gg ez"; never ordinary words like "trash can") | |

Listed players get a green ✔, gold ? or red ✖ on their nametag and in the tab list, followed by their group tag. You
get a warning (chat + sound) when a Naughty-list player comes into view or joins the server, and a quiet chat note for
Look Into players.

With **Base Insurance** installed, friends whose type is trusted at your bases (and Nice-list players, unless you turn that off) count as Base Insurance friends, and base visits and block
breaking inside your bases show up on players' pages.

Lists are saved per server in `config/naughtyornice/lists/`. The address is tidied up, so `Constantiam.net` and
`constantiam.net:25565` share one set of lists; lists that older versions saved under different spellings of the same
address are merged automatically (the extra files are kept as `.merged`).

## Intel
The **Intel** tab gathers what's been given away on the server, newest first, with filters and counts:
- **Places**: coordinates players wrote in chat, read strictly so only real places count. Labeled (`x: 1200 z: -3400`,
  `xz 1200 -3400`), `x y z` (the middle number a height), F3 pastes (`1200.5 / 64 / -400.2`), `12k -4k`, highway
  distances (`-x 20k`, `+x+z hwy 5000`), and pairs when the line says it's a place ("base at 1200 -3400", "meet me at...")
  or is nothing but the numbers. Amounts never count ("1000 diamonds and 200 gold", "$500 600", "150 200 ms", years,
  screen sizes, dates). The dimension is noted when the line names it.
- **Where they logged out**, when they did it in front of you: often at or near their base.
- **Wither summoners**: servers tell every player in a dimension where a wither finished spawning. When exactly one
  player got "Withering Heights" in the 5 to 40 seconds before, that's who, and where they were. Two candidates means no
  answer, never a guess.
- **World events**: lit end portals (a stronghold), withers, the dragon dying, with exact positions.
- **Talk about**: threats, stashes and base hunting, griefing, duping, hacks and exploits, trading, meeting up, PvP, alts.
  Each needs unambiguous wording ("gonna grief your base", "found a stash", "wts", "meteor client"), so "gonna find
  food", "the meteor shower", "buying a house irl" or "press alt f4" don't count.

Each place shows how far it is from your nearest base (the other dimension too, at 8 to 1) and from you, with
**Copy**. Places with a known dimension are pinned on Base Insurance's map. A player's own places, topics and the way
they were heading when they left your view are on their Stats tab.

**Alerts**: a threat aimed at you or your base (or whispered to you) always pings. You can also be pinged for any
topic, or any coordinates at all (Settings).

## Chunk trails
Press **J** for the trail radar (or `/nn radar`); it's off until you turn it on, and Settings → Chunk trails sets
its size (Small, Medium, Large). It's a map on the right of the screen that turns with you, so ahead is always up. It
draws the land as it really is, like a map (each spot's top block in its map colour, shaded by height; in the nether,
the floor near you), as far as your render distance:
- **Land in its own colours: new to you**: generated just now, as you got there. Nobody had loaded it before.
- **Tinted green: loaded before**: someone was here before you.
- Past your render distance, what's remembered: faint red for new, green for old.
- **Small dot: can't tell**: it's loaded, but has no water or lava to tell by.
- **Dark: not loaded**.
- **Amber line**: a **trail**, a long, narrow strip of old land with fresh land on both sides. That's someone's path.
  The **arrow** is on the end further from spawn (when one end clearly is). That's a fact about the trail, not a
  promise of a base: it's where they went, or came from.
- **Told as you go**: no line stays on screen. When new chunks come up ahead while you're in old land, or old chunks
  (someone has been there) while you're in new land, it says so once, in chat and above the hotbar, with a chime:
  "New chunks ahead: 48 blocks NE". It looks along the way you're moving and only counts an edge two chunks deep.
  Off in Settings (Tell me when the land changes ahead).
- Underneath, in small words: "Trail: 1,920 blocks long, runs NE–SW" and either "Away from spawn: NE, turn 35° left" when
  you're on it, or "300 blocks ahead-left (W)" when you're not.

When a new trail turns up you get a chat note with its far end. Every trail is kept on the **Intel** tab (Chunk trails),
with Copy for the far end, and its far end is pinned on Base Insurance's map.

**Chunks in the world** (**K**): every chunk that's been read is drawn as a flat square in the world, the same
colours as the radar: fresh land blue, old land grey, and the chunks of a trail amber. They're drawn at your feet by
default, or at a height you pick (Settings: "Chunks in the world are drawn at"), with the fill from outline only to
60%.

**The Intel map** (Intel → Map) shows it all on one big map, north up: fresh and old land, trails with their arrows,
your bases, places players gave away (where they logged out in front of you, where they summoned a wither,
coordinates they wrote with the dimension named), world events, and where players on your lists were last seen.
Drag to move, scroll to zoom, Overworld / Nether / End to switch, **Center on me** to come back. Hover anything for
what it is and when; click to copy its coordinates. The mouse's coordinates and what's known about that land show in
the corner.

**Heat** turns the map into a heat map of where people have been: each area is coloured by how much of the land
there is old, blue where it's all fresh, through yellow, to red where it's all old. Highways and the routes players
fly come out as hot lines through cool fresh land; areas where little could be read are drawn fainter.

What the mod learns about land is kept per server (old land and the land you generated), so trails build up over many
sessions instead of starting over.

How it knows: land generated for the first time gets its water and lava flowing *after* it reaches you, so the server
sends flowing-liquid updates for it. Land that already existed arrives with its liquids already flowed. Chunks without
liquid can't be told either way and are left out, never guessed. The nether, with its lava, shows the most; the end
shows nothing. Chunks you generated yourself are remembered per server, so your own old paths never show up as someone
else's. Land within your simulation distance has its liquids run for you, so it's never read as old when it arrives
(a busy server can send fresh land late, after it has flowed), and land that was still unknown while you were that
close is remembered and never read as old when you come back. So flying faster than the server makes land can't turn
your own path into a trail. A patch of old land only becomes a trail when it's at least 8 chunks long, twice as long as it is wide, and has
fresh land on both sides. The edge of explored land, or a big explored area, never counts. Things that can go wrong
only ever hide trails, never invent them: if a server lets fresh land flow before sending it, everything reads as old
and no trail shows; a liquid poured by a player out of your sight marks one chunk fresh, which can't make a trail. If
a trail is wider than your view distance you'll see only old land around you, and the radar says so. Water flowing
out of fresh land into a dry old chunk next to it can mark that one chunk fresh, so a trail's edge can look a chunk
narrower than it is; it can't move or make a trail.

## Events: everything that happened, and who started it
The mod keeps a log of everything around you, so when an event turns into a brawl you can find out exactly what
happened. `/nn events` opens it.
- **What's logged**, with the time to the millisecond: chat and whispers, joins and leaves, deaths (killer, weapon and
  cause, and where if it was in view), every hit (who, on whom, with what item, what kind of damage, and the health
  left as the server shows it), arm swings (and who at), totem pops, shots (arrows, tridents, pearls, potions,
  fireworks, and who fired them), end crystals (who set one off; who was near when one appeared), explosions, gear
  changes, advancements, and where everyone in view was, every second.
- **Always on**: the last 30 minutes are always kept, so after something happens, **Save last 30 minutes** keeps it,
  even if you never pressed record. Or **Start recording** before the event: it writes to a file as it goes, so even
  a crash loses nothing.
- **The report**: when it ran, how many took part and died; **How the fighting started** (the first hit one player
  landed on another, which the server reports exactly, and the swings just before it); a **Kill feed** with weapons;
  **Players** (kills, deaths, hits dealt and taken, totems, crystals, shots, chat, who they hit most and with what);
  and a **Timeline** of everything, filtered by player or kind.
- **Replay**: a map of the fight from above. Everyone's position moves along a time slider (or press Play, at up to
  32× speed). Each hit is drawn as a line from who hit to who was hit, and deaths, totem pops and explosions are marked
  where they happened. **First hit** jumps to where the fighting started. It shows no coordinates, only a scale.
- **Death recap**: when you die, the death screen shows who hit you in your last 30 seconds, how often and with what,
  the totems you popped, and your last hits with the health you had left after each, plus **Save this fight**.
- **Export CSV** opens in any spreadsheet. Recordings are kept in config/naughtyornice/recordings/&lt;server&gt;/, one line per
  entry (JSON Lines).

Who did something is only written when the server says so (the attacker of a hit, the shooter of a projectile,
whoever damaged a crystal). When it doesn't (who placed a crystal), the players close by are listed instead.

## Baritone, with buttons
If you have [Baritone](https://github.com/cabaletta/baritone) installed (on its own, or inside a client that has it),
press **B** (or `/nn baritone`). Naughty or Nice drives it through Baritone's own API, so nothing goes through chat.
- **Go to…**: every place the mod knows, in one list with distance and direction: your saved waypoints (type a name,
  **Save where I am**), your bases, chunk trails' far ends, places players gave away, where your list's players were
  last seen, lit end portals (strongholds), and coordinates on your clipboard. Search it, filter it, press **Go**.
- **Smart Go** works out how: a place in the other dimension becomes the matching spot in yours (8 to 1); it flies by
  elytra only when that will work (in the nether, over 1,000 blocks, an elytra on and fireworks with you) and walks
  otherwise.
- **Trip bar**: whenever Baritone takes you somewhere (a place, a highway roam, or anything from the Do page), a bar at
  the top shows where to, how far is left (along the highways when the route is by them), about how long that is
  from the speed you're actually making, how fast you're going, and how. Off in Settings (Trip bar).
- **Z stops everything**: the trip, a highway roam, and Baritone. (Change it in Controls.)
- **Safety on every trip**: it pauses and asks when someone who isn't a friend or on your Nice list shows up, and stops
  when your health gets low (Settings page).
- **Do**: type coordinates, follow a player, mine a block, explore, farm, Stop / Pause / Resume.
- **Highway roam**: a distance along a highway, or **to coordinates**: type them (or paste them, or take the latest
  trail's end), say if they're overworld ones (÷8 in the nether), and pick a highway or **Best** (the quickest,
  through spawn if need be). It rides the highway to its nearest point and then goes on to them, or stops there if
  you'd rather; the page says the way before you start. It walks the highway a short step at a time along its
  middle, so Baritone never cuts through the walls, and puts Baritone's own settings back when it ends. Walk or
  elytra, the highway's height. Damaged highways:
  break through filled-in parts, bridge gaps and missing floor with blocks you carry (or go round the side), parkour
  jumps, and walking on lava only if you have Fire Resistance. It stops and asks when a chunk trail turns up near the
  highway ([Follow it], [Keep going], [Download this area]) or when a stranger shows up.
- **Settings**: every Baritone setting, searched and clicked, saved to Baritone's own file so they stay.

## Downloading the world (only when you say yes)
Press **Download** (Baritone screen, or the button when a highway roam stops at a trail): from then until you stop,
every chunk you load in that dimension is saved into a singleplayer world named after the server, the dimension and the
time. It saves blocks, biomes and the block data the server sends (signs, banners...). Chest contents aren't sent to
players unless you open the chest, so they can't be saved. Each chunk is saved when it arrives and again when you leave
it, so blocks broken or placed while you were there are kept. The world is plain vanilla: open it from Singleplayer in
creative. Everything you didn't load is empty, never made up.

## Guarding your own location
**Guard my coordinates** (on by default) reads your own chat messages and whispers before they're sent. If one would
give away coordinates, one of your base names, or your own current x or z, it's held, with **[Send anyway]** and
**[Edit]**. It catches Baritone commands typed into public chat by mistake, pasted F3 coordinates, and slips.

What else gives players' locations away in 1.21.11, so you know what to avoid:
- **Being in someone's tracking range**: they get your exact position, through walls. If they're near when you log
  out, some clients mark the spot.
- **Chunk trails**: flying from a highway into new land leaves a line of old chunks that others can follow (this mod's
  radar shows exactly that). Leave highways at random points, cross explored land, double back.
- **Withers, end portals, the dragon**: their positions are sent to everyone in the dimension (unless the server turns
  that off), and "Withering Heights" names who summoned one.
- **What you say and show**: coordinates, directions, base names, landmarks, F3 or terrain in screenshots (with a known
  seed, terrain can be matched to a place).
- **Your mods**: Baritone commands in public chat, minimap waypoint sharing, Discord status showing coordinates or biome.
- **Items you hand over**: a lodestone compass points at your lodestone; a map shows its area.
- **Nether portals**: being seen near yours gives away your overworld base's area (8 to 1).

Not a risk: death messages, the tab list, ping, skins and advancements (except "Withering Heights", above).

## Stats
Every player page has a **Stats** tab: all-time numbers from everything your game gets to see. Most of it works for
anyone on the server, wherever they are; only "Up close" needs them in render distance. Each box says where its
numbers come from, and nothing is guessed. Totals are never trimmed, however long you play.
- **Online** (from the tab list, while you're on too): time online to the second, and what share of *your* time on the
  server they were on; days online (in the last 7 and 30 days too), most days in a row, busiest day of the week, the
  time they usually log in and out; logins and logouts you saw, average session (only ones you saw start *and* end) and
  the longest; ping (average, range, steady or jumpy); game mode if it isn't survival.
- **Fights** (death messages, which the whole server gets): kills (how many were you), deaths, kills per death, **how
  they die** (player, explosion, elytra crash, fall, void...), who they kill most and who kills them most, what they
  kill with, last death; totems popped and hits (in view).
- **Up close** (in render distance): times seen, time in view and near you, closest they've come, distance travelled,
  top speed (the slowest of three seconds in a row, so pearls don't count), elytra time, dimensions, what they ride,
  best armour, what they usually hold.
- **Chat**: messages, words per message, whispers both ways, chattiest hour, **who they talk to** and who talks about
  them, how often they say your name, favourite words, all caps, questions.
- **Places they gave away** (see Intel) and **links they posted** (Discord invites...).
- **Around you**: how often they log in just after you join, log out as you join, log out within 30 seconds of coming
  into your view, or log out after dying. Each is shown against how often it would happen **by chance**, and marked
  "more than chance" when it's well over.
- **Progress**: advancements the server announced for them.
- **Account**: the kind of account (Java/Mojang, offline-mode, Bedrock through Geyser, NPC), the server's name for
  them in the tab list (ranks) and what it used to be, chat signing (off hints at a no-chat-reports mod or a modified
  client) and new chat keys (game restarts), their skin and where it came from (skin plugins name the account),
  skin changes, names they've had.
- **Maybe also**: other accounts that may be the same person, with a score and the reasons: the same skin (now or
  before), a borrowed skin, taking turns online without ever being on together, similar names, the same ping. It takes
  two different kinds of evidence to be listed: popular skins are shared by strangers, so a skin alone never is.

The **Activity** tab has **Top players** boards for the server: most time online, most days online, most kills, most
deaths, most totems, chattiest, most advancements, most places given away, fastest seen. Click a name for their page.
The player summary uses the same numbers, the page header says when they were last *online*, and the list can be
sorted by **Most time online** and **Most kills**.

## Where & when, and who with
- **Sightings:** every time a player comes into view, where (with how far it was from your nearest base), how long
  they stayed, and their gear (armour, elytra, what's in their hands). With Base Insurance, the last sighting of
  everyone on your lists or in a group shows as a diamond on its map.
- **When they're on:** a 24-hour chart of when each player is online (while you are), and "usually on 6pm to 11pm".
- **Names they've had:** lists follow the account, so a rename keeps their history; search finds old names too.
- **Seen with:** who logs on or off with them, plays near them, or takes turns with them (one logs off and the
  other logs on, like alts). Busy servers are full of coincidences, so a pair only shows when it's a real share of
  what they do: at least 3 logins or logouts together making up half or more of the quieter player's, or 5+ minutes
  near each other away from crowds (spawn, highways) making up a quarter or more of the time they're seen. Logins
  during a rush (a restart) don't count. "Maybe an alt" needs 3+ turn-takings and the two never on at the same time.
  Each link says why, e.g. "logged on or off together 4 of 5 times". The Groups tab suggests crews it notices, with
  Make a group / Not a group. (Seen-with data from before 1.6.0 counted every coincidence, so it starts afresh; the
  old file is kept as `.links.json.old`.)
- **Screenshots:** taken automatically when someone kills you or a Naughty / Look Into player comes into your base,
  listed on their page (saved in `screenshots/naughtyornice`).

## Alerts
- **Totems popped:** "Steve [3 pops]" on nametags and the tab list: the totems they've popped in front of you since they
  last died (the server's own totem signal, so every count is real).
- **Logout spots:** where a player logged out while you could see them, marked on screen until they come back.
- **Phone alerts:** paste a Discord webhook link in Settings (channel settings, Integrations, Webhooks) and warnings and
  pings (a naughty player joins or comes near, a threat, someone kills you, Base Insurance damage, theft or a player at
  your base) are posted there too, so Discord on your phone tells you. By default only while you're away from the
  game. Messages can't ping anyone, and repeats are held back.
- **Join summary:** who's online from your lists and groups, once, when you join.
- **Group alerts:** turn on ♪ on a group's page and you hear that group's own bell when one of them joins or comes
  near.
- **Chat alerts:** a ping when someone says your name or any word you list in Settings, names one of your bases, or
  posts coordinates within 300 blocks of one (overworld or nether coordinates).

## Updates
Once per game session, a little after you join a world, the mod says in chat if a newer version is out on GitHub,
with a **[Download]** link and **[Skip this version]**. Nothing downloads or installs by itself. Turn it off in
Settings (**Tell me about updates**).

## Disconnects
When you get disconnected, the mod notes the reason, **what did it**, who was near (and which list they're on) and the
last lines of chat, and tells you when you're back. It watches the moment the connection closes, so it can tell:
- **the server** kicked you (a kick, restart, or its anti-cheat),
- **another mod** disconnected you on purpose, and which one. Auto-log features in other mods do this when someone
  not on *their* friends list comes near, and they often pretend the server did it,
- **an error** handling something the server sent (and in which mod's code),
- **the connection** dropped or timed out, or **you** left.

See the last few in Settings → **Disconnects** or with `/nn disconnects` (**Copy details** for the full record).

This mod's own code can never disconnect you: everything it does while the game handles a packet is guarded, and any
error is skipped, logged once and listed in the chat check report.

## Chat check
Servers word chat, deaths and joins differently. Settings → **Chat check** (or `/nn check`) shows how many lines were
understood and every kind of line that wasn't, grouped, with examples. **Copy report** puts it all on the clipboard to
send in, so the mod can learn the server's wording. Death messages reworded by server plugins ("X was slain by Y
using Z") and plain-text joins and leaves are understood too.

The Chat tab keeps a week of chat between sessions (Settings), with a divider each time you join, and everything said
is also written to a text file a day in `config/naughtyornice/chatlogs/<server>/all-chat/`.

## Building
Requires JDK 21. Run `./gradlew build`; the jar goes to `build/libs/`.
`./gradlew runPanelTest` tests the Mods panel with Base Insurance and Hoard Eye beside it (their jars in run-panel/mods).
`./gradlew testIntel` checks the chat detectors (coordinates, topics, trash talk) against 146 lines in a few seconds.
`./gradlew runSelfTest` runs a scripted in-game test (about 240 checks) that spawns a fake griefer and saves screenshots to
`run/screenshots/`.
