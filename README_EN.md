# WaveXinAddon

**Language / 语言:** [中文](README.md) | English

WaveXinAddon is a Meteor Client addon designed for the **2b2t.xin** server. It provides elytra flight, path flight, auto login, auto answer, automatic Daily Flower (monthly pass) check-ins, base scanning, chat filtering, entity nametags, and other utility modules.

<p align="center">
  <img src="assets/wavexin_readme_preview.png" alt="WaveXinAddon Preview" width="850">
</p>

## Features

* Better Elytra Fly: Elytra flight helper
* Simple Elytra Fly Path: Simple coordinate-based elytra path flight
* ChickenNametags: Chicken entity nametags
* SnifferNametags: Sniffer entity nametags
* Auto Login: 2b2t.xin auto login, auto join, and daily flower check-in
* Chat Filter: Private message, public chat, and death message filtering
* Base Finder: Normal / spiral scanning, container recording, and optional Xaero waypoints

## Requirements

* Minecraft 1.21.11
* Java 21
* Fabric Loader
* Fabric API
* Meteor Client
* Baritone

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

Place the built WaveXinAddon jar into your Minecraft `mods` folder together with:

* Fabric API
* Meteor Client
* Baritone

## Configuration

WaveXinAddon configuration files are automatically saved in:

```text
meteor-client/wavexin/
```

## Notice

This project is mainly designed for the 2b2t.xin use case. Please check server rules yourself and use this project at your own risk.

## Credits

WaveXinAddon was originally inspired by [EasyAddon](https://github.com/IDhammaI/easyaddon) by IDhammaI.

Spiral Scan is inspired by [WTmbp](https://github.com/2698269088/WTmbp) by 2698269088.

WaveXinAddon is now **independently** modified and maintained by WaltomAdaam.

## License

See [LICENSE](LICENSE).

WaveXinAddon is licensed under the GNU General Public License v3.0.
Copyright (C) 2026 WaltomAdaam2.