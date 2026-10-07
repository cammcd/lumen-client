# Lumen

A clean, customizable Fabric client for **Minecraft 26.3**.

## Modules

| Module | What it does |
|---|---|
| **Storage ESP** | Highlights chests, trapped chests, ender chests, shulker boxes, droppers and barrels. Dispensers, hoppers, furnaces, crafters and decorated pots can be switched on too. Every block type has its own colour and toggle. |
| **Spawner ESP** | Highlights monster spawners and trial spawners, with tracers on by default. |
| **Entity ESP** | Highlights players, hostile mobs, passive mobs, dropped items, tamed pets, named mobs, item frames and chest/hopper minecarts and boats, each with its own toggle and colour. Villagers, golems and armor stands can be switched on too. Boxes follow entities smoothly between ticks. |
| **Nametags** | Player labels with health (absorption in gold), distance, ping and their armor and held items, drawn crisply over the world and through walls. Can include named mobs. |
| **X-Ray** | Highlights chosen ores and blocks through terrain, each group with its own colour and toggle: diamonds, ancient debris and emeralds by default; gold, iron, redstone, lapis, copper, coal, quartz, obsidian and budding amethyst optional. "Exposed only" limits it to blocks touching air or liquid. It can only show what the server sends, so servers with anti-xray hide or fake ores. |
| **Fullbright** | Lights up caves and nights as if it were day. It gives you endless night vision on your own client only, with no particles or icon, and leaves a real night vision potion alone. |
| **Freecam** | Detaches the camera and flies it through blocks while your body stays still. WASD to move, Space and Shift for up and down, Sprint for a speed boost, scroll wheel to change speed. |
| **New Chunks** | Colours chunks the world just generated differently from chunks that existed before. Liquids generate still and only flow once a chunk has been loaded, so old chunks in fresh land show where players have travelled. |
| **Stash Finder** | Flags chunks with more containers than a threshold you set, then pops up a notification, prints the coordinates in chat (client-side only) and appends them to `config/lumen/stashes.csv`. |
| **Base Finder** | Scores each chunk for blocks that never or rarely generate naturally (ender chests, shulker boxes, beacons, concrete, hoppers, signs, and in the Nether or End, everyday blocks like crafting tables and torches). Village-type blocks count little and are capped, so villages alone do not trigger it. Finds are marked in the world and logged to `config/lumen/bases.csv`. The box covers the band of up to 16 blocks (Max box height) where most of the base is, so a stray block far above or below does not stretch it; Mark blocks still outlines every block it counted. |
| **Sign Reader** | Reads both sides of every loaded sign and shows the text above it, through walls. Signs that look like they hold coordinates (`1200 64 -3400`, `X: 1200 Z: -3400`, `1200, -3400`) are outlined in orange, announced, and logged to `config/lumen/signs.csv`. |
| **Logout Spots** | When a nearby player disappears from the tab list, marks where they logged out with a ghost box and a label showing how long ago. Logged to `config/lumen/logouts.csv`. |
| **Sound Locator** | Marks where server-wide events (a wither spawning, the dragon dying, an end portal opening) and loud distant sounds came from, with a beam, a tracer and a label. Logged to `config/lumen/sounds.csv`. |
| **Waypoints** | Saved locations per server and dimension with a beam, name and distance. Press **N** to add one where you stand; a death waypoint is saved automatically. Manage them from the **Waypoints** button in the Click GUI. |
| **Radar** | A north-up minimap of nearby chunks: new and old chunks from New Chunks, stashes, bases, players and waypoints. |
| **HUD** | Watermark with FPS, an animated list of active modules, coordinates with Nether/Overworld conversion, and toggle notifications. |
| **Click GUI** | Theme settings for the menu itself. |
| **Auto Reconnect** | Adds a reconnect button to the disconnect screen and, by default, counts down and rejoins the last server. |
| **Fake Name** | Shows a name you choose in place of yours, on your screen only: chat, the tab list, nametags, the scoreboard and signs. Click the Name field in its settings and type; Enter saves. Nothing is sent to the server, so other players still see your real name. |

### Player

The Player panel sits under the Client panel.

| Module | What it does |
|---|---|
| **Auto Tool** | Switches to the fastest tool in your hotbar while you mine, skips tools about to break, and switches back when you stop. |
| **Chest Stealer** | Takes everything from chests, barrels and shulker boxes when you open them, then closes the menu. It only empties a container you are looking at, so server menus like /shop and /ah, which are chests too, are left alone. |
| **Anti AFK** | Jumps, swings and looks around on a timer, with random jitter, so idle-kick plugins see you as active. |
| **Scaffold** | Places full solid blocks from your hotbar under your feet as you walk, so you can bridge gaps. It bridges at the height you last stood at, so jumping does not build a tower. |
| **Auto Walk** | Holds forward for you, and swims up in water. Pauses while a menu is open. |
| **Auto Sprint** | Sprints whenever the game itself would let you start: moving forward, enough food, not sneaking, eating or blind. |
| **Safe Walk** | Stops you walking off edges, the way sneaking does, at full speed. |
| **Elytra+** | Cruise control for elytra flight: holds a pitch or an altitude, and fires a rocket from your hotbar or offhand when speed drops. |
| **Printer** | Builds a Litematica schematic (`.litematic`) for you. Put the file in `.minecraft/schematics` (where Litematica saves) or `config/lumen/schematics`, type its name in the File setting, and turn it on where the build's lowest corner should go, or type the corner in Origin. It places blocks from the bottom up within reach, asks the game which click and look direction give each block the facing, half and axis the schematic wants, and only uses that one. Blocks come from your hotbar, then your inventory, and in creative it takes whatever it needs. With Buy missing on, it buys blocks you run out of (see below). Schematics from other tools and older versions load too; block types this version does not have are named in chat and skipped, and blocks with no item, like water and lava, are left for you to place by hand. Farmland is made the way you would: dirt goes down where needed and is hoed with a hoe from your inventory (grass and dirt already there are hoed in place), before crops are planted on it. **Break wrong blocks** (on) mines blocks that differ from the schematic, with the fastest tool in your hotbar, and puts the right one in; **Clear inside** (off) also mines anything inside the build's area where the schematic has air, such as terrain. It mines from the top down and never breaks containers or anything else holding items, unbreakable blocks, the block you stand on, or blocks touching lava. **Place water** (on) pours water sources from water buckets in your inventory, last in each area so the water does not run over spots still to fill; flowing water in a schematic is left to flow by itself. Blocks still to place are outlined, and wrong blocks in red; it never breaks anything. |
| **Trail Follower** | Steers along trails of old chunks from New Chunks, which are where other players have travelled. Pair it with Auto Walk or Elytra+. |

### Buying blocks for the Printer (DonutSMP)

Turn on **Buy missing** in the Printer's settings. When you run out of a block, the Printer
works out how many the rest of the build needs and buys them the way you would by hand:

1. **/shop.** It opens each category until it finds the block, sets the amount with the
   Add, Remove and Set buttons, and confirms. What each category sells is remembered, so
   later purchases go straight there. DonutSMP's shop sells few building blocks
   (obsidian, end stone, ender chests, respawn anchors), so most blocks go to step 2.
2. **/ah.** It searches the auction house for the block and buys the listing that covers
   what is needed for the least money: a big stack is skipped when a small one is enough.
   Before confirming, it checks the buy screen shows the same listing at the same price.

**Max price each** (default 100) and **Max spend** (default 50k, for each time you turn the
Printer on) take amounts like `250`, `2.5k` or `1m`. Nothing over either limit is bought.
A purchase only counts once the blocks are in your inventory, and every purchase and
anything it could not buy is reported in chat. **Shop command** and **Auction command**
can be changed for other servers; `{item}` becomes the block's name.

It only reads the first page of auction results, and it does not buy in creative.
It finds buttons by their names (Confirm, Cancel, Add 10, Set to 64) and prices by the
`Price: $1.5K` line in their descriptions, in plain or small-caps text. It is tested
against a stand-in built from descriptions of DonutSMP's menus, not on DonutSMP itself.

### Combat

These are the modules servers ban for fastest. Nothing here is built to get past an anti-cheat.

| Module | What it does |
|---|---|
| **Kill Aura** | Attacks the best target in range whenever your attack is charged. Optional turning to face the target, weapon-only mode, and pauses while you eat or have a menu open. |
| **Trigger Bot** | Hits the target under your crosshair once your attack is charged, after a short reaction delay. |
| **Criticals** | Times Kill Aura and Trigger Bot hits with a hop so they land as critical hits, the way you would by hand. |
| **Auto Clicker** | Clicks at a rate between a min and max CPS while you hold a mouse button. Left click only on entities by default, so mining is not interrupted. |
| **Aim Assist** | Smoothly pulls your aim toward the nearest target inside a field-of-view cone, optionally only while clicking. |
| **Bow Aimbot** | While you draw a bow, aims at the best target with arrow drop and target movement allowed for. |
| **Velocity** | Scales the knockback you take from hits and explosions, separately for sideways and upward. |
| **Auto Totem** | Keeps a totem of undying in your offhand, always or only below a health threshold. |
| **Auto Armor** | Equips the best armor in your inventory, by material then durability, and leaves a worn elytra alone. |
| **Auto Gapple** | Eats a golden apple from your hotbar when health drops below a threshold, then switches back. |
| **Crystal Aura** | Places end crystals on obsidian or bedrock near a target and breaks them, picking the spot that hurts the target most. Skips any crystal that would deal you more than your max self damage, and with Anti suicide on, any that would kill you. |
| **Anchor Aura** | Outside the Nether, places a respawn anchor next to a target, charges it with glowstone and sets it off, with the same damage limits as Crystal Aura. |
| **Auto Trap** | Boxes the nearest target in obsidian: around the feet, around the head, and a roof. |
| **Surround** | Rings your feet with obsidian (or ender chests) so crystals cannot be placed beside you. Turns off when you step out of the block. |

Crystal Aura and Anchor Aura estimate explosion damage with the game's own formula:
distance, how much of the hitbox the blast can see, difficulty, armor and Resistance.
Blast Protection is not counted, so the estimate runs high, which keeps the self-damage
limit on the safe side.

Kill Aura, Trigger Bot, Aim Assist, Bow Aimbot, Crystal Aura, Anchor Aura and Auto Trap share target settings: range, players,
hostile mobs, passive mobs, named mobs, invisible entities, through walls, and priority
(closest, lowest health, or nearest the crosshair). Tamed pets and creative or spectator
players are never targeted. Through walls starts on for Crystal Aura, Anchor Aura and Auto
Trap, since their targets are usually behind blocks, and off for the rest.

### ESP options (Storage, Spawner and Entity ESP)

Render mode (fill + outline, outline, fill), through walls, fill opacity, outline opacity,
line width, box padding, tracers (width and opacity), range up to 512 blocks, distance fade,
far markers (a fixed-size dot on anything over 24 blocks away, so distant blocks never shrink
to nothing), and a breathing pulse effect. Every colour has a full HSV picker with alpha and a rainbow mode.
Shulker boxes can use their own dye colour.

### Freecam options

Speed, vertical speed, sprint boost, smooth movement with adjustable smoothness, scroll to
change speed, hide hand, hide block outline, a tracer box and line back to your body (with
its own colour), and disable on damage. Freecam always starts switched off.

### Theme options

Two accent colours with gradients, panel colour, text colour, corner radius, shadows,
background dim, blur, animation speed, hover descriptions and text shadow.

## Controls

- **Right Shift** opens the Click GUI (rebind it under Options > Controls > Lumen).
- Left click a module to toggle it. Right click to open its settings.
- Middle click a module, or use its Keybind row, to bind a key. Backspace clears a bind.
- Start typing to search. It matches module names, descriptions and setting names. Esc clears the search.
- **Profiles** (top right) saves your whole setup under a name, and loads or deletes saved setups.
- Drag panel headers to move them. Right click a header to collapse it. Scroll long panels.
- Right click most settings to reset them.

Settings, keybinds and panel positions are saved to `config/lumen.json`. Profiles are saved
in `config/lumen/profiles/`.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer for Minecraft 26.3.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and the Lumen jar in your `mods` folder.

Requires Java 25.

## Building

```bash
./gradlew build
```

The jar lands in `build/libs/`. Every push is also built by GitHub Actions, and the jar is
attached to the run as the `lumen-jar` artifact.
