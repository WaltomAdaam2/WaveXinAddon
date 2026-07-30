# WaveXinAddon 1.21.1 兼容与代码审计实施计划

## 1. 目标与交付物

目标不是让同一个 JAR 同时加载在 Minecraft 1.21.11 和 1.21.1，而是在同一个仓库中维护同一套功能，发布两个经过独立构建和测试的 JAR：

- `wave-xin-addon-<version>+mc1.21.11.jar`
- `wave-xin-addon-<version>+mc1.21.1.jar`

Minecraft、Yarn、Meteor 和 Mixin 内部 API 在两个版本之间存在差异。强行制作通用 JAR 会把兼容判断带入大量运行时代码，并让 Mixin 在启动阶段产生不可恢复的崩溃风险。因此采用“共享行为与数据格式，隔离版本适配代码”的方案。

## 2. 已在当前代码完成的调整

1. Normal Scan 中，玩家经过区块的当个游戏刻就把区块记为 visited；渲染优先级改为：恢复断点 > 已访问 > 当前路径 > 未访问目标。这样已走过区块无需等到转向后才变绿。
2. Xaero 路径点颜色改为 Xaero 实际使用的 0–15 颜色顺序。随机颜色编号只生成一次，同一个编号同时用于 Xaero 路径点和聊天提示。
3. 旧配置中的 `Orange`、`Lime`、`Cyan`、`Light Blue`、`Magenta`、`Pink`、`Light Gray`、`Brown` 等名称仍可读取，并迁移到最接近的 Xaero 标准颜色。新配置使用稳定的 enum 标识保存。
4. `Area Radius` 默认值改为 5；`Waypoints per Area` 保持默认 3。
5. README 中贡献者 `2698269088` 增加 GitHub 主页链接。
6. 中英文 README 的模块名称和功能说明按当前实际功能更新；中文 README 与中文逻辑文档统一使用“中文名 (English Name)”格式。
7. 新增 visited 渲染优先级和 Xaero 颜色编号回归测试。

## 3. 代码审计结果

### P0：用户可见错误

- **Visited 颜色延迟**：当前路径颜色优先于 visited 颜色，导致一整段路径到转角后才变绿。已修复并加测试。
- **Xaero 聊天颜色不一致**：项目原来的自定义颜色表与 Xaero 0–15 编号顺序不同。路径点收到一个编号，聊天却按另一套颜色解释。已修复并加测试。

### P1：下一轮应优先处理

- **Normal Scan 渲染复杂度过高**：当前实现每帧遍历玩家周围 `(2R+1)^2` 个区块。默认 R=128 时约检查 66,000 个区块/帧，R=256 时超过 263,000 个区块/帧。应改为缓存当前可见环与路径段，只在玩家区块、环数、路线或设置变化时重建待渲染列表。
- **`visitedChunks` 长时间扫描会持续增长**：普通扫描越久，`HashSet<ChunkPos>` 占用越大。应使用区块 long key 的 primitive set，并按已经不可能进入渲染范围的已完成环做压缩或持久化摘要。
- **Xaero 反射每次创建路径点都重新查类、构造器和方法**：这会产生额外开销，也让错误散落在 BaseFinder 内。应提取 `XaeroWaypointBridge`，首次使用时解析并缓存反射句柄；1.21.11 和 1.21.1 各自提供适配实现。
- **核心 Mixin 对版本变化过于敏感**：`wavexin.mixins.json` 为 required，且包含 Meteor GUI 控件和 Minecraft 网络/移动方法注入。1.21.1 必须使用独立的 Mixin 配置和类，不能直接复用 1.21.11 target。
- **Auto Login 有静默吞掉异常的位置**：部分 `catch (Exception ignored)` 会隐藏账号配置或界面状态识别失败。应记录不含密码的限频 debug/warn，并保留正常降级行为。
- **未注册模块仍参与编译**：`CommandScannerXin` 和 `HighwayWalkerXin` 当前不在入口注册，但仍扩大每个 Minecraft 版本的移植面。1.21.1 初版应从版本构建中排除，确认要恢复后再单独适配。

### P2：结构优化

- `BaseFinder.java` 和 `AutoLogin.java` 过大。等双版本行为稳定后，再按“扫描状态机 / 渲染 / 容器记录 / Xaero 桥接”和“账号存储 / 文本识别 / 流程状态机”拆分；不要在移植同时做大重构。
- 路径点区域限额只记住本次启用期间创建的位置。重新启用后可能在已有 Xaero 路径点附近重复创建。后续可选择读取当前 waypoint set 或保存 WaveXin 自己的区域索引。
- 为发布流程增加双版本构建、产物命名、测试报告和最小启动测试，避免只凭“编译通过”发布。

## 4. 1.21.1 技术基线

以 EasyAddon 的 1.21.1 分支和 Meteor Client 官方 1.21.1 代码为基线：

- Minecraft `1.21.1`
- Yarn `1.21.1+build.3`（EasyAddon 已验证；若映射冲突再退回 Meteor 官方当时使用的 build.1）
- Fabric Loader `0.15.11`
- Fabric Loom `1.7-SNAPSHOT`
- Gradle `8.10`
- Java `21`
- Meteor Client `0.5.8`

EasyAddon 只用于确认依赖组合、旧 API 名称和 1.21.1 行为入口。不能整体复制其 BaseFinder，因为其中也存在“当前路径颜色覆盖 visited 颜色”等已知旧逻辑。

## 5. 仓库与版本组织

### 第一阶段：先用长期维护分支完成可运行移植

- `main`：Minecraft 1.21.11
- `mc-1.21.1`：Minecraft 1.21.1

行为修复、纯逻辑、翻译、文档和测试先提交到 `main`，再 cherry-pick 到 `mc-1.21.1`。版本 API 修复只留在对应分支。这样可以先获得一个真实可启动的 1.21.1 版本，不需要在移植过程中同时重排全部目录。

### 第二阶段：两个版本都稳定后再抽共享层

把不依赖 Minecraft/Meteor API 的内容移动到共享模块：

- BaseFinder 路线和恢复计算
- ScanProgressManager 的数据模型与计算
- Chat Filter 文本匹配
- Auto Login 流程状态机与安全账号数据模型
- Turtle Potion 的选择计划
- i18n key、资源和行为测试

版本适配层保留：

- Minecraft 实体、物品组件和网络包访问
- Meteor GUI 控件与设置工厂
- 所有 Mixin target 与 accessor
- 2D/3D 渲染调用
- Xaero waypoint API/反射桥接

只有在两个版本的差异点数量稳定后，才决定使用 Gradle 多项目或 Stonecutter 预处理。移植一开始就引入预处理会把“API 修复”和“构建系统改造”混在一起，增加定位难度。

## 6. 实施阶段

### Phase A：冻结 1.21.11 行为

- 合入本文件第 2 节的调整。
- 运行全部现有行为测试、翻译校验和 `clean build`。
- 保存一份 BaseFinder 正常扫描、螺旋扫描、恢复、容器记录、随机 Xaero 颜色的手动测试记录。
- 产出基准 JAR，后续每个 1.21.1 模块都和它对照。

### Phase B：创建最小 1.21.1 构建

1. 从完成 Phase A 的提交创建 `mc-1.21.1`。
2. 替换 Gradle/Minecraft/Meteor 版本。
3. 暂时只保留 Addon 入口、分类、基础数据路径和一个空模块，先证明：
   - Gradle 可解析依赖；
   - Fabric 能启动；
   - Meteor 能发现 addon；
   - ClickGUI 可显示分类；
   - 无 Mixin apply error。
4. 将 1.21.1 的 `fabric.mod.json` Minecraft 依赖限制为精确的 `1.21.1`。

### Phase C：按风险从低到高恢复模块

每恢复一个模块，都必须完成“编译、启动、启用/停用、保存/重载设置、对应行为测试”后再进入下一个模块。

1. Chat Filter
2. Auto Login 的纯文本识别和账号存储
3. Base Finder 的路线计算、Normal Scan 与恢复
4. Base Finder 的 Spiral Scan、容器记录和渲染
5. Better Elytra Fly
6. Elytra Fly Path
7. Chicken/Sniffer Nametags
8. Turtle Potion Thrower
9. Xaero waypoint integration
10. 双语 ClickGUI UI Mixin

`CommandScannerXin` 和 `HighwayWalkerXin` 不进入首个 1.21.1 发布包，因为它们没有注册为公开模块。

### Phase D：逐类处理版本差异

#### Minecraft/Yarn

- 核对 Player/ClientPlayer 的 `travel`、`move`、yaw/headYaw/bodyYaw 方法。
- 核对标题、字幕、聊天和命令建议网络包及 handler 方法签名。
- 核对 potion data component、PotionContentsComponent、物品使用返回值和 inventory swap API。
- 核对 2D 投影、矩阵、窗口缩放和实体名称/生命值访问。

#### Meteor 0.5.8

- 核对 `Module`、`Setting`、`SettingGroup` 的字段和标题/描述结构。
- 核对 `SettingsWidgetFactory.registerCustomFactory`、`WDropdown`、`WIntEdit` 和 `WTextBox` 是否存在同名入口。
- 核对 `ChatUtils` 前缀、`InvUtils` quick swap、render event 和 renderer box API。
- UI Mixin 若不能稳定匹配，1.21.1 首版先保留英文内部 ID 与模块功能，随后单独恢复完整双语界面；不得让非关键 UI 翻译导致客户端启动失败。

#### Xaero

- 新建 `XaeroWaypointBridge` 接口。
- 1.21.11 实现保留当前 class-name 候选。
- 1.21.1 实现按对应 Xaero Minimap 版本解析 session、world、set、waypoint constructor 和 save/changed-time 方法。
- 颜色编号统一由共享 `XaeroWaypointColor` 产生；桥接层只接收最终 `colorId`，不能自行再次随机。

### Phase E：CI 与发布

每个版本执行：

- `clean build`
- 全部行为测试
- `verifyWaveXinTranslations`
- 检查 JAR 中 `fabric.mod.json` 的 Minecraft 范围
- 检查 JAR 名包含 Minecraft 版本
- 使用对应 Meteor 和 Fabric Loader 做最小客户端启动测试

Release 同时上传两个 JAR，并在文件名和说明中明确版本。禁止把 1.21.11 JAR 标记成可用于 1.21.1，或反过来。

## 7. 验收清单

### 两个版本都必须通过

- 8 个公开模块都能在 ClickGUI 中显示并保存设置。
- Normal Scan 玩家进入新区块后立即变为 visited 颜色。
- Resume checkpoint 始终优先使用独立颜色。
- 随机 Xaero 路径点的聊天名称颜色与实际路径点一致，连续创建可得到不同随机颜色但每个单独路径点内部一致。
- `Area Radius = 5`、`Waypoints per Area = 3` 为新配置默认值；旧配置不会被强制覆盖。
- Auto Login 不打印或记录明文密码。
- Turtle Potion Thrower 在副手、主手、快捷栏和背包 quick swap 四种场景都能恢复原槽位。
- 启用/停用飞行或扫描模块后，前进键、冲刺、yaw/pitch 和库存状态不会残留。
- 缺少 Xaero 时 Base Finder 仍可记录容器，且只关闭 Xaero 功能。
- 中文和英文 key 集合完全一致，README 名称与 ClickGUI 名称一致。

### 1.21.1 额外验收

- 使用 Meteor 0.5.8 启动无 Mixin apply error。
- 不依赖 1.21.11 的 class、method 或 data component。
- UI Mixin 即使失配也不能影响核心模块启动；最终正式发布前必须恢复并测试完整双语 UI。

## 8. 不应采用的方案

- 不制作声明同时支持两个版本的单一 JAR。
- 不把 ViaFabric 当作 addon 二进制兼容方案；它解决的是连接不同协议服务器，不会让 1.21.11 编译出的 Mixin 和 Meteor API 在 1.21.1 客户端中可用。
- 不直接覆盖复制 EasyAddon 的整个 `BaseFinderXin.java`；它缺少当前项目的恢复、容器记录、双语、状态清理和测试，并保留部分旧 bug。
- 不在移植阶段同时大规模重写 BaseFinder/AutoLogin 架构。
- 不为了“编译通过”删除状态恢复、安全存储或失败清理逻辑。
