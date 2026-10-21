# Naughty or Nice

A client-side Fabric mod for Minecraft 1.21.11 that keeps your **Friends** (in circles you name) and a **Nice list**, a
**Look Into list** and a **Naughty list** of players, with a store-style screen where one click puts someone on a list.

By **PlayWit Creation**.

## Installing
1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.11**, plus
   [Fabric API](https://modrinth.com/mod/fabric-api).
2. Get `naughty-or-nice-<version>+1.21.11.jar` from the
   [Releases](https://github.com/PlayWitIt/Naughty-or-Nice/releases) page (or build it, see below).
3. Put it in your `.minecraft/mods` folder and start the game with the Fabric profile.

Optional: [Mod Menu](https://modrinth.com/mod/modmenu) for a settings button, and Base Insurance for base-aware
features. To share lists with a friend who has the mod, use **⇄ Share** in the lists screen.

## How to use it
- Press **N** to open it. If you're looking at a player, it opens their page.
- Every player is shown as a card with their face, a star rating, a **☆** in the corner to make them a friend, and three buttons: **✔ Nice**, **? Look Into** (not
  sure yet, keep an eye on them) and **✖ Naughty**. Click again to take them off. Every change can be undone from the
  banner at the top.
- **+ Add players** opens a panel where you type one or more names (spaces or commas between them), pick from
  suggestions, add an optional detail and group, and press **Nice list**, **Look Into** or **Naughty list**. Works for
  offline players too.
- Search finds players by name, their details, their groups, or anything they've said in chat.
- Tabs: All, Nearby, Online, **Offline**, **Friends**, Nice list, **Look Into** (your Look Into list, then players whose behaviour
  suggests a list), Naughty list, **Groups**, **Chat**, Settings.
- Everyone you see (nearby or on the tab list) is remembered, so they're still listed after logging off. Players who
  never did anything are forgotten after 180 days unseen (configurable).
- Light and dark themes: the ☾ / ☀ button at the top (dark by default).
- Comfortable at any GUI scale: the screen picks its own size from the window, whatever the game's GUI scale.
  Settings → **Screen size**: Small fits the most (about 900 wide), Medium is in between (about 680), Large is the
  biggest (about 540, never so big the panels don't fit). Whole-pixel scales are used where they're close, so text
  stays sharp.
- Commands: `/nn`, `/nn add`, `/nn friends`, `/nn chat`, `/nn groups`, `/nn share`, `/nn import`, `/nn open <link>`,
  `/nn disconnects`, `/nn friend <name> [circle]`, `/nn nice <name>`, `/nn look <name>`, `/nn naughty <name>`,
  `/nn unsort <name>`, `/nn group <name> <group>`, `/nn who <name>`, `/nn chat <name>`, `/nn settings`.

## Friends
Friends are people you actually know, kept apart from Nice (behaves well), Look Into and Naughty. Each friend is in a
**circle**: Close friends, Base mates and Allies to start with; rename them, recolour them, reorder them, add your own
(up to 8) or remove them in Settings → **Friend circles**.
- Press the **☆** in a card's corner to make someone a friend (they go in the top circle, or the circle you're looking
  at). Press the **★** again to move them to another circle or stop being friends. The player page, the Add players
  panel (pick the circle, then **★ Friends**) and `/nn friend <name> [circle]` work too.
- The **Friends** tab shows them all, or one circle at a time, with how many of each are online.
- Friends get a ★ in their circle's colour on nametags, in the tab list, in chat and on Base Insurance's map.
- Per circle: **Trusted at my bases** (Base Insurance never alerts about them or names them as suspects) and **Ping
  when they join or come near**.
- Shared lists send your friends as Nice (older versions understand that), marked "their friend". Nothing a friend
  shares ever takes your friends off your Friends.

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
| Trash talk in chat | |

Listed players get a green ✔, gold ? or red ✖ on their nametag and in the tab list, followed by their group tag. You
get a warning (chat + sound) when a Naughty-list player comes into view or joins the server, and a quiet chat note for
Look Into players.

With **Base Insurance** installed, friends in trusted circles (and Nice-list players, unless you turn that off) count as Base Insurance friends, and base visits and block
breaking inside your bases show up on players' pages.

Lists are saved per server in `config/naughtyornice/lists/`. The address is tidied up, so `Constantiam.net` and
`constantiam.net:25565` share one set of lists; lists that older versions saved under different spellings of the same
address are merged automatically (the extra files are kept as `.merged`).

## Where & when, and who with
- **Sightings:** every time a player comes into view, where (with how far it was from your nearest base), how long
  they stayed, and their gear (armour, elytra, what's in their hands). With Base Insurance, the last sighting of
  everyone on your lists or in a group shows as a diamond on its map.
- **When they're on:** a 24-hour chart of when each player is online (while you are), and "usually on 6pm to 11pm".
- **Names they've had:** lists follow the account, so a rename keeps their history; search finds old names too.
- **Seen with:** who logs on or off at the same time, plays near them, or takes turns with them (one logs off and
  the other logs on, like alts). The Groups tab suggests crews it notices, with Make a group / Not a group.
- **Screenshots:** taken automatically when someone kills you or a Naughty / Look Into player comes into your base,
  listed on their page (saved in `screenshots/naughtyornice`).

## Alerts
- **Join summary:** who's online from your lists and groups, once, when you join.
- **Group alerts:** turn on ♪ on a group's page and you hear that group's own bell when one of them joins or comes
  near.
- **Chat alerts:** a ping when someone says your name or any word you list in Settings, names one of your bases, or
  posts coordinates within 300 blocks of one (overworld or nether coordinates).

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
`./gradlew runSelfTest` runs a scripted in-game test (158 checks) that spawns a fake griefer and saves screenshots to
`run/screenshots/`.
