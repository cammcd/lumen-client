# Lumen

A clean, customizable Fabric client for **Minecraft 26.3**.

## Modules

| Module | What it does |
|---|---|
| **Storage ESP** | Highlights chests, trapped chests, ender chests, shulker boxes, droppers and barrels. Dispensers, hoppers, furnaces, crafters and decorated pots can be switched on too. Every block type has its own colour and toggle. |
| **Spawner ESP** | Highlights monster spawners and trial spawners, with tracers on by default. |
| **Entity ESP** | Highlights players, hostile mobs, passive mobs, dropped items, tamed pets, named mobs, item frames and chest/hopper minecarts and boats, each with its own toggle and colour. Villagers, golems and armor stands can be switched on too. Boxes follow entities smoothly between ticks. |
| **Nametags** | Player labels with health (absorption in gold), distance, ping and their armor and held items, drawn crisply over the world and through walls. Can include named mobs. |
| **Freecam** | Detaches the camera and flies it through blocks while your body stays still. WASD to move, Space and Shift for up and down, Sprint for a speed boost, scroll wheel to change speed. |
| **New Chunks** | Colours chunks the world just generated differently from chunks that existed before. Liquids generate still and only flow once a chunk has been loaded, so old chunks in fresh land show where players have travelled. |
| **Stash Finder** | Flags chunks with more containers than a threshold you set, then pops up a notification, prints the coordinates in chat (client-side only) and appends them to `config/lumen/stashes.csv`. |
| **Base Finder** | Scores each chunk for blocks that never or rarely generate naturally (ender chests, shulker boxes, beacons, concrete, hoppers, signs, and in the Nether or End, everyday blocks like crafting tables and torches). Village-type blocks count little and are capped, so villages alone do not trigger it. Finds are marked in the world and logged to `config/lumen/bases.csv`. |
| **Logout Spots** | When a nearby player disappears from the tab list, marks where they logged out with a ghost box and a label showing how long ago. Logged to `config/lumen/logouts.csv`. |
| **Sound Locator** | Marks where server-wide events (a wither spawning, the dragon dying, an end portal opening) and loud distant sounds came from, with a beam, a tracer and a label. Logged to `config/lumen/sounds.csv`. |
| **Waypoints** | Saved locations per server and dimension with a beam, name and distance. Press **N** to add one where you stand; a death waypoint is saved automatically. Manage them from the **Waypoints** button in the Click GUI. |
| **Radar** | A north-up minimap of nearby chunks: new and old chunks from New Chunks, stashes, bases, players and waypoints. |
| **HUD** | Watermark with FPS, an animated list of active modules, coordinates with Nether/Overworld conversion, and toggle notifications. |
| **Click GUI** | Theme settings for the menu itself. |

### ESP options (both modules)

Render mode (fill + outline, outline, fill), through walls, fill opacity, outline opacity,
line width, box padding, tracers (width and opacity), range up to 512 blocks, distance fade,
and a breathing pulse effect. Every colour has a full HSV picker with alpha and a rainbow mode.
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
