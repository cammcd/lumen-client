# Lumen

A clean, customizable Fabric client for **Minecraft 26.3**.

## Modules

| Module | What it does |
|---|---|
| **Storage ESP** | Highlights chests, trapped chests, ender chests, shulker boxes, droppers and barrels. Dispensers, hoppers, furnaces, crafters and decorated pots can be switched on too. Every block type has its own colour and toggle. |
| **Spawner ESP** | Highlights monster spawners and trial spawners, with tracers on by default. |
| **Freecam** | Detaches the camera and flies it through blocks while your body stays still. WASD to move, Space and Shift for up and down, Sprint for a speed boost, scroll wheel to change speed. |
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
- Drag panel headers to move them. Right click a header to collapse it. Scroll long panels.
- Right click most settings to reset them.

Settings, keybinds and panel positions are saved to `config/lumen.json`.

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
