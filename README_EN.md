# WaveXinAddon

**Language / 语言:** [中文](README.md) | English

WaveXinAddon is a Meteor Client addon designed for the **2b2t.xin** server. It provides elytra flight, automatic elytra replacement, path flight, semi-automatic Litematica printing, auto login, auto answer, automatic Daily Flower (monthly pass) check-ins, base scanning, chat filtering, turtle-potion throwing, entity nametags, and other utility modules.

<p align="center">
  <img src="assets/wavexin_readme_preview.png" alt="WaveXinAddon Preview" width="850">
</p>

## Features

* Better Elytra Fly: Configurable horizontal and vertical flight control with low-durability elytra replacement. Optional speed ramping increases from an initial speed to a cap and can reset after a server lagback cooldown.
* Elytra Fly Path: Flies toward a target X/Z coordinate with Nether conversion, automatic takeoff, arrival stopping, optional disconnect, the same speed ramping, and an optional temporary Xaero target waypoint.
* Chicken Nametags: Renders configurable name, health, and distance labels for nearby chickens.
* Sniffer Nametags: Renders configurable name, health, and distance labels for nearby sniffers.
* Auto Login: Automates supported 2b2t.xin login, quiz, Daily Flower check-in, and join flows while encrypting offline-account passwords.
* Chat Filter: Separately filters MSG, public chat, and death messages with independent allowlists and an own-message bypass enabled by default.
* Turtle Potion Thrower: One-shot bind for normal, long, or strong splash Turtle Master potions with offhand, hotbar, Quick Swap, slot restore, and notification support.
* Base Finder: Normal/spiral scanning, checkpoint resume, immediate visited-chunk rendering, container and pearl recording, and optional Xaero waypoints with area limits and exact creation-message colors.
* Container Recorder: An independent recorder for selected loaded-chunk containers that meet its threshold. It supports pearl detection, record files, Xaero waypoints, vanilla achievement toasts, and the achievement sound. Each scan mode can start it automatically through its own option.
* End Gateway Finder: Locally predicts End return gateways from the world seed for 1.12, 1.20.4, or both versions. It rolls the scan around the player, reuses overlapping results for the current game session, reports progress through a top-right toast, and supports routes, dwell time, automatic movement, rendering, and Container Recorder integration.
* Litematica Printer: While the player moves manually, places projection blocks with real support inside interaction range. Green marks the next batch, yellow marks retrying blocks, and red marks states requiring manual correction. It also handles directional states, double chests, hotbar refills, whole-stack restocking from a selected region, progress/container caches, and a dedicated debug log.

For implementation details and feature-specific notes, see the [Feature Logic Guide](docs/README_LOGIC_EN.md). The staged Minecraft 1.21.1 port and acceptance criteria are documented in the [1.21.1 Compatibility Plan](docs/MC_1_21_1_PORT_PLAN.md).

## Commands

The Meteor command prefix is configurable. Current public commands:

* `.sel [1|2|c]`: Select, confirm, or clear the Litematica Printer restock region.
* `.wavexin check-update true|false`: Save the startup update-check preference.
* `.wavexin lang Simplified Chinese`: Change WaveXinAddon visible text only to Simplified Chinese.
* `.wavexin lang English`: Change WaveXinAddon visible text only to English.

## Requirements

* Minecraft 1.21.11 / 1.21.1 (some features still require updates)
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

## Update Checks

By default, WaveXinAddon checks GitHub Releases in the background after client startup. It never blocks the game or downloads and installs updates automatically. GitHub is tried first; if it is unavailable, these public proxy fallbacks are tried in order:

* `ghfast.top`
* `gh-proxy.com`
* `gh.3w.pm`

These are third-party services and their availability may change. The check requests only public version information; it does not upload account, player, server, HWID, seed, or configuration data. Save the startup preference with:

```text
.wavexin check-update true
.wavexin check-update false
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
