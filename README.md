# WaveXinAddon

**语言 / Language:** 中文 | [English](README_EN.md)

WaveXinAddon 是一个为 **2b2t.xin** 服务器设计的 Meteor Client Addon，主要提供鞘翅飞行、路径飞行、自动登录、自动答题、实体名牌显示等辅助功能。

## 功能

* ElytraFlyXin：鞘翅飞行辅助
* ElytraReplace：自动更换低耐久鞘翅
* SimpleElytraFlyPath：简单坐标路径飞行
* AutoAnswerXin：自动答题
* ChickenNametags：鸡实体名牌显示
* SnifferNametags：嗅探兽实体名牌显示
* AutoLoginXin：2b2t.xin 自动登录与排队辅助
* BaseFinderXin：区块搜索 / 基地寻找辅助
* NetherElytraPath：下界鞘翅路径飞行辅助

## 环境要求

* Minecraft 1.21.11
* Java 21
* Fabric Loader
* Fabric API
* Meteor Client
* Baritone

## 构建方法

Windows：

```powershell
.\gradlew.bat clean build --stacktrace --console=plain
```

Linux / macOS：

```bash
./gradlew clean build --stacktrace --console=plain
```

构建完成后，mod 文件会生成在：

```text
build/libs/
```

请使用普通 jar 文件，不要使用 `sources` 或 `dev` jar。

## 安装方法

将构建出的 WaveXinAddon jar 文件放入 Minecraft 的 `mods` 文件夹中，并确保同时安装：

* Fabric API
* Meteor Client
* Baritone

## 题库文件

AutoAnswerXin 的题库文件位于：

```text
src/main/resources/assets/wavexin/questions.json
```

题库格式：

```json
{
  "问题文本": "用于匹配答案选项的正则表达式"
}
```

## 说明

本项目主要为 2b2t.xin 使用场景设计。请自行确认服务器规则，并自行承担使用风险。

## Credits

WaveXinAddon was originally inspired by EasyAddon by IDhammaI.

WaveXinAddon is now independently modified and maintained by WaltomAdaam.

## License

See [LICENSE](LICENSE).

WaveXinAddon is licensed under the GNU General Public License v3.0.
Copyright (C) 2026 WaltomAdaam2.