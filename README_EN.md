# WaveXinAddon

**Language / 语言:** [中文](README.md) | English

WaveXinAddon is a Meteor Client addon designed for the **2b2t.xin** server. It provides elytra flight, path flight, auto login, auto answer, entity nametags, and other utility modules.

## Features

* ElytraFlyXin: Elytra flight helper
* ElytraReplace: Automatically replaces low-durability elytras
* SimpleElytraFlyPath: Simple coordinate-based elytra path flight
* AutoAnswerXin: Automatic quiz answer helper
* ChickenNametags: Chicken entity nametags
* SnifferNametags: Sniffer entity nametags
* AutoLoginXin: 2b2t.xin auto login and queue helper
* BaseFinderXin: Chunk search / base finding helper
* NetherElytraPath: Nether elytra path flight helper

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

## Question Database

The question database for AutoAnswerXin is located at:

```text
src/main/resources/assets/wavexin/questions.json
```

Format:

```json
{
  "question text": "regex used to match the answer option"
}
```

## Notice

This project is mainly designed for the 2b2t.xin use case. Please check server rules yourself and use this project at your own risk.

## Credits

WaveXinAddon was originally inspired by EasyAddon by IDhammaI.

WaveXinAddon is now independently modified and maintained by WaltomAdaam.

## License

See [LICENSE](LICENSE).

WaveXinAddon is licensed under the GNU General Public License v3.0.
Copyright (C) 2026 WaltomAdaam2.