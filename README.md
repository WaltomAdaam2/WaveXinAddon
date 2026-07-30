# WaveXinAddon

**语言 / Language:** 中文 | [English](README_EN.md)

WaveXinAddon 是一个为 **2b2t.xin** 服务器设计的 Meteor Client Addon (彗星段扩展)，主要提供鞘翅飞行、鞘翅自动更换、路径飞行、自动登录、自动答题、自动每日小红花（月卡）、基地扫描、聊天过滤、神龟药水快捷投掷和实体名牌显示等辅助功能。

<p align="center">
  <img src="assets/wavexin_readme_preview.png" alt="WaveXinAddon Preview" width="850">
</p>

## 功能

* 鞘翅飞行 (Better Elytra Fly)：提供可调水平/垂直飞行控制、自动开始与停止、速度限制、无阻力，以及低耐久时自动更换备用鞘翅。
* 鞘翅路径飞行 (Elytra Fly Path)：自动飞向目标 X/Z 坐标，支持下界坐标换算、自动起飞、到达停止和自动断开。
* 鸡标签名显示 (Chicken Nametags)：为范围内的鸡显示可调名称、生命值和距离标签。
* 嗅探兽标签名显示 (Sniffer Nametags)：为范围内的嗅探兽显示可调名称、生命值和距离标签。
* 自动登录 (Auto Login)：自动处理 2b2t.xin 支持的登录、答题、每日小红花签到和加入流程，并加密保存离线账号密码。
* 聊天过滤 (Chat Filter)：分别过滤 MSG 私聊、公共聊天和死亡消息，使用独立白名单，并默认保留自己发送的公共聊天。
* 神龟药水投掷 (Turtle Potion Thrower)：通过快捷键一次性投掷普通、长效或增强喷溅型神龟药水，支持副手、快捷栏、Quick Swap、槽位恢复和通知。
* 基地狩猎扫图 (Base Finder)：提供普通/螺旋扫描、断点恢复、即时已访问区块渲染、容器与末影珍珠记录，以及带区域限额和真实颜色提示的可选 Xaero 路径点。

更多实现逻辑与注意事项请参阅 [功能逻辑说明](docs/README_LOGIC.md)。Minecraft 1.21.1 的双版本移植步骤与验收标准参阅 [1.21.1 兼容实施计划](docs/MC_1_21_1_PORT_PLAN.md)。

## 环境要求

* Minecraft 1.21.11
* Java 21
* Fabric Loader
* Fabric API
* Meteor Client

### 可选组件

Base Finder 的 Xaero 路径点功能可能会使用以下组件：

* Xaero's Minimap
* Xaero's World Map
* XaeroPlus

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

将构建出或从 Release 中下载的 WaveXinAddon `.jar` 文件放入 Minecraft 的 `mods` 文件夹中，并确保同时安装：

* Fabric API
* Meteor Client

## 配置文件

WaveXinAddon 的配置文件会自动保存到：

```text
meteor-client/wavexin/
```

## Base Finder 推荐配置

默认配置：

```text
Chunk Load Radius = 5
Chunk Wait Distance = 4
Render Distance 建议 2
Simulation Distance 建议 5
```

或者建议：

```text
Chunk Load Radius = 8
Chunk Wait Distance = 6
Render Distance 建议 5
Simulation Distance 建议 7
```

如果不会调整，请按照默认配置或者推荐配置来，否则容易卡死。

## 说明

本项目主要为 2b2t.xin 使用场景设计。请自行确认服务器规则，并自行承担使用风险。

## Credits

WaveXinAddon was originally inspired by [EasyAddon](https://github.com/IDhammaI/easyaddon) by [IDhammaI](https://github.com/IDhammaI).

Spiral Scan is inspired by [WTmbp](https://github.com/2698269088/WTmbp) by [2698269088](https://github.com/2698269088).

WaveXinAddon is now **independently** modified and maintained by [WaltomAdaam](https://github.com/WaltomAdaam2).

## License

See [LICENSE](LICENSE).

WaveXinAddon is licensed under the GNU General Public License v3.0.
Copyright (C) 2026 WaltomAdaam2.
