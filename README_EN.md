# WaveXinAddon

**Language / 语言:** [中文](README.md) | English

WaveXinAddon is a Meteor Client addon designed for the **2b2t.xin** server. It provides elytra flight, automatic elytra replacement, path flight, semi-automatic Litematica printing, auto login, auto answer, automatic Daily Flower (monthly pass) check-ins, base scanning, chat filtering, turtle-potion throwing, entity nametags, and other utility modules.

<p align="center">
  <img src="assets/wavexin_readme_preview.png" alt="WaveXinAddon Preview" width="850">
</p>

## Features

* Better Elytra Fly: Configurable horizontal and vertical flight control with auto start/stop, speed limiting, no-drag mode, and low-durability elytra replacement.
* Elytra Fly Path: Flies toward a target X/Z coordinate with Nether conversion, automatic takeoff, arrival stopping, and optional disconnect.
* Chicken Nametags: Renders configurable name, health, and distance labels for nearby chickens.
* Sniffer Nametags: Renders configurable name, health, and distance labels for nearby sniffers.
* Auto Login: Automates supported 2b2t.xin login, quiz, Daily Flower check-in, and join flows while encrypting offline-account passwords.
* Chat Filter: Separately filters MSG, public chat, and death messages with independent allowlists and an own-message bypass enabled by default.
* Turtle Potion Thrower: One-shot bind for normal, long, or strong splash Turtle Master potions with offhand, hotbar, Quick Swap, slot restore, and notification support.
* Base Finder: Normal/spiral scanning, checkpoint resume, immediate visited-chunk rendering, container and pearl recording, and optional Xaero waypoints with area limits and exact creation-message colors.
* Litematica Printer: While the player moves manually, places projection blocks with real support inside interaction range; handles directional states, double chests, hotbar refills, whole-stack restocking from a selected region, progress/container caches, and a dedicated debug log.

For implementation details and feature-specific notes, see the [Feature Logic Guide](docs/README_LOGIC_EN.md). The staged Minecraft 1.21.1 port and acceptance criteria are documented in the [1.21.1 Compatibility Plan](docs/MC_1_21_1_PORT_PLAN.md).

## Requirements

* Minecraft 1.21.11
* Java 21
* Fabric Loader
* Fabric API
* Meteor Client

Litematica Printer also requires a Litematica build matching the active Minecraft version.

### Optional

Base Finder's Xaero waypoint features may use the following components:

* Xaero's Minimap
* Xaero's World Map
* XaeroPlus

## Build

Windows:

```powershell
.\gradlew.bat clean build --stacktrace --console=plain
```

Linux / macOS:

```bash
./gradlew clean build --stacktrace --console=plain
```

The built jar will be generated in:

```text
build/libs/
```

Use the normal jar file, not the `sources` or `dev` jar.

## Installation

Place the WaveXinAddon `.jar` built locally or downloaded from Releases into your Minecraft `mods` folder, and make sure the following are installed:

* Fabric API
* Meteor Client

## Configuration

WaveXinAddon configuration files are automatically saved in:

```text
meteor-client/wavexin/
```

## Base Finder Recommended Settings

Default configuration:

```text
Chunk Load Radius = 5
Chunk Wait Distance = 4
Recommended Render Distance = 2
Recommended Simulation Distance = 5
```

Alternative recommended configuration:

```text
Chunk Load Radius = 8
Chunk Wait Distance = 6
Recommended Render Distance = 5
Recommended Simulation Distance = 7
```

If you are not sure how to adjust these values, use the default or recommended configuration. Incorrect values can make scanning freeze.

## Notice

This project is mainly designed for the 2b2t.xin use case. Please check server rules yourself and use this project at your own risk.

## Credits

WaveXinAddon was originally inspired by [EasyAddon](https://github.com/IDhammaI/easyaddon) by [IDhammaI](https://github.com/IDhammaI).

Spiral Scan is inspired by [WTmbp](https://github.com/2698269088/WTmbp) by [2698269088](https://github.com/2698269088).

WaveXinAddon is now **independently** modified and maintained by [WaltomAdaam](https://github.com/WaltomAdaam2).

## License

See [LICENSE](LICENSE).

WaveXinAddon is licensed under the GNU General Public License v3.0.
Copyright (C) 2026 WaltomAdaam2.
