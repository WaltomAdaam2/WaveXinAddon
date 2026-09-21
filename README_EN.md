# WaveXinAddon

**Language / 语言:** [中文](README.md) | English

WaveXinAddon is a Meteor Client addon designed for the **2b2t.xin** server. It provides elytra flight and path navigation, semi-automatic Litematica printing, login and check-in automation, base and End-gateway scanning, container recording, chat filtering, entity nametags, and optional combat assistance.

<p align="center">
  <img src="assets/wavexin_readme_preview.png" alt="WaveXinAddon Preview" width="850">
</p>

## Features

* Better Elytra Fly: Configurable horizontal and vertical flight control with low-durability elytra replacement. Optional speed ramping increases from an initial speed to a cap, can reset after a server lagback cooldown, and can be disabled while ascending.
* Elytra Fly Path: Flies toward a target X/Z coordinate with Nether conversion, automatic takeoff, arrival stopping, optional disconnect, the same speed ramping, and an optional temporary Xaero target waypoint.
* Chicken Nametags: Renders configurable name, health, and distance labels for nearby chickens.
* Sniffer Nametags: Renders configurable name, health, and distance labels for nearby sniffers.
* Auto Login: Automates supported 2b2t.xin login, quiz, Daily Flower check-in, and join flows while encrypting offline-account passwords.
* Chat Filter: Separately filters MSG, public chat, and death messages with independent allowlists and an own-message bypass enabled by default.
* Base Finder: Normal/spiral scanning, checkpoint resume, immediate visited-chunk rendering, and optional Xaero waypoints. Either scan can link to the independent Container Recorder.
* Container Recorder: An independent recorder for selected loaded-chunk containers that meet its threshold. It supports pearl detection, record files, Xaero waypoints, vanilla achievement toasts, and the achievement sound. Each scan mode can start it automatically through its own option.
* EndBaseFinder: Locally predicts End return gateways from the world seed for 1.12, 1.20.4, or both versions. It supports fixed or rolling centers, batched calculation, session caching, persisted arrival history, a persistent progress toast, four route modes, dwell time, automatic movement, version-colored rendering, and Container Recorder integration.
* KillAura+: An optional melee module shown after local license validation. It provides independent target filters, attack timing, rotation, weapon restrictions, and several target render modes. Its license is separate from EndBaseFinder, and redeeming access never enables the module automatically.
* Litematica Printer: While the player moves manually, places projection blocks with real support inside interaction range. Green marks the next batch, yellow marks retrying blocks, and red marks states requiring manual correction. It also handles directional states, double chests, hotbar refills, whole-stack restocking from a selected region, progress/container caches, and a dedicated debug log.

For implementation details and feature-specific notes, see the [Feature Logic Guide](docs/README_LOGIC_EN.md). The completed Minecraft 1.21.1 adaptation record and acceptance criteria are documented in the [1.21.1 Compatibility Plan](docs/MC_1_21_1_PORT_PLAN.md).

1.8.0 provides separate Minecraft 1.21.11 and 1.21.1 JARs; use the matching artifact.

## Commands

The Meteor command prefix is configurable. Current public commands:

* `.sel [1|2|c]`: Select, confirm, or clear the Litematica Printer restock region.
* `.wavexin redeem <code>`: Locally redeem access to an optional module. The matching module appears immediately after success but is not enabled automatically. The submitted value is not logged or stored in plaintext.
* `.wavexin check-update true|false`: Save the startup update-check preference.
* `.wavexin lang Chinese`: Change WaveXinAddon visible text only to Simplified Chinese.
* `.wavexin lang English`: Change WaveXinAddon visible text only to English.
* `.wavexin debug <module> on|off`: Control diagnostics for `autologin`, `basefinder`, `elytraflypath`, `container`, `printer`, or `endbasefinder`.

## Requirements

* Minecraft 1.21.11 / 1.21.1 (use the matching version-specific JAR)
* Java 21
* Fabric Loader
* Fabric API
* Meteor Client

Litematica Printer also requires a Litematica build matching the active Minecraft version.

### Optional

Xaero waypoint features in Base Finder, Container Recorder, and Elytra Fly Path may use the following components:

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

This directory contains global settings, separate locally encrypted licenses, scan checkpoints, container records, seed-scoped gateway arrival history, and module diagnostic logs. Licenses validate only for the current Windows user. EndBaseFinder and KillAura+ use independent licenses, and old settings-based activation flags or old licenses are not migrated during upgrade.

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
