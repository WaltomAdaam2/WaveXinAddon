# WaveXinAddon

**Language / 语言:** [中文](README.md) | English

WaveXinAddon is a Meteor Client addon designed for the **2b2t.xin** server. It provides elytra flight, automatic elytra replacement, path flight, auto login, auto answer, automatic Daily Flower (monthly pass) check-ins, base scanning, chat filtering, turtle-potion throwing, entity nametags, and other utility modules.

<p align="center">
  <img src="assets/wavexin_readme_preview.png" alt="WaveXinAddon Preview" width="850">
</p>

## Features

* Better Elytra Fly: Elytra flight helper with optional automatic replacement for nearly broken elytra
* Simple Elytra Fly Path: Simple coordinate-based elytra path flight
* ChickenNametags: Chicken entity nametags
* SnifferNametags: Sniffer entity nametags
* Auto Login: 2b2t.xin auto login, auto join, and daily flower check-in
* Chat Filter: Private message, public chat, and death message filtering, with separate MSG / public allowlists; public chat is recognized only as `<player> message`, with a default bypass for your own public messages
* Base Finder: Normal / spiral scanning, container recording, target-chunk center locking, spiral auto-walk view locking or temporary steering, and optional Xaero waypoints; created-waypoint chat names are bolded with the actual Xaero waypoint color
* Turtle Potion Thrower: Throws a splash turtle potion from a bind, with offhand support, original-slot restore, Quick Swap, and Notify

For implementation details and feature-specific notes, see the [Feature Logic Guide](docs/README_LOGIC_EN.md).

## Requirements

* Minecraft 1.21.11
* Java 21
* Fabric Loader
* Fabric API
* Meteor Client

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

Spiral Scan is inspired by [WTmbp](https://github.com/2698269088/WTmbp) by 2698269088.

WaveXinAddon is now **independently** modified and maintained by [WaltomAdaam](https://github.com/WaltomAdaam2).

## License

See [LICENSE](LICENSE).

WaveXinAddon is licensed under the GNU General Public License v3.0.
Copyright (C) 2026 WaltomAdaam2.
