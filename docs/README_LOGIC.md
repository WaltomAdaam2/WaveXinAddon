# WaveXinAddon 功能逻辑说明

本页说明 ClickGUI 中公开模块的实现方式与需要注意的行为。它不是完整的设置手册；实际可用选项以游戏内模块设置为准。

### Better Elytra Fly

- 在鞘翅飞行期间接管水平与垂直运动计算，根据移动按键、视角和速度设置调整速度。
- `Auto Start` 会在满足条件时正常开始滑翔；`Auto Stop` 会在停止移动等条件下结束辅助飞行。
- `Speed Limit` 限制最终速度，`No Drag` 移除本模块模拟的阻力。
- 模块内的 `Elytra Replace` 设置组可单独启用自动更换：当已装备鞘翅的剩余耐久达到阈值时，从背包中寻找剩余耐久高于阈值的备用鞘翅并装备到胸甲栏。
- 自动更换可限制为仅滑翔时执行；找不到备用鞘翅时使用冷却警告，避免每个游戏刻刷屏。
- Inventory Tweaks 兼容选项会在更换期间暂时关闭该模块，并在设置的延迟后恢复，避免库存操作冲突。

### Simple Elytra Fly Path

- 使用 Target X 和 Target Z 计算二维目标方向，并在滑翔时自动调整视角与移动速度向目标前进。
- `Nether Pos Calculation` 启用后会将输入的 X、Z 分别除以 8，再作为实际目标坐标。
- 到达范围由 `Arrival Distance` 判定；若同时启用自动停止和自动断开，会先停止模块，再执行断开。
- 模块可自动起飞，并会在推荐高度外启动时给出提示；最终飞行速度由本模块设置控制。

### ChickenNametags 与 SnifferNametags

- 在 2D 渲染阶段为鸡或嗅探兽投影自定义名称牌。
- 名称牌可显示实体名称、生命值和距离，并受渲染范围、缩放、背景和文字颜色设置控制。
- 仅绘制匹配实体类型且处于设置范围内的实体，不修改实体数据或服务端状态。

### Auto Login

- 只在启用服务器限制且当前地址匹配 2b2t.xin 支持地址时运行自动化流程。
- 监听标题、副标题和聊天文本，检测登录提示后才处理离线账号的 `/l`；Microsoft 账号不会发送该命令。
- 账户记录按当前账户名保存。离线账号密码以加密形式保存，不会在聊天、状态或账户列表中回显。
- 登录成功后按状态机处理每日小红花签到、加入游戏和后续右击操作；每个阶段有延迟、重试和连接重置保护，避免重复发送或操作旧界面。

### Chat Filter

- MSG 私聊、公共聊天和死亡消息分别按各自设置过滤，过滤判断使用完整消息文本。
- `MSG Allowlist` 和 `Public Message Allowlist` 使用独立玩家列表；某个玩家加入其中一个列表不会同步到另一个列表。
- 白名单使用和 Meteor Friends 页面类似的列表输入：已有玩家按行显示，右侧 `-` 删除，底部输入框配合 `+` 添加。
- `Hide Death Messages` 使用最朴素的死亡公告格式匹配，例如自杀、自爆、环境死亡、玩家击杀、射杀、炸死、推下悬崖或虚空等中英文格式；它不再依赖颜色组合，因此不会因为服务器其他彩色消息误过滤。
- `Show Own Public Messages` 默认启用。启用公共聊天过滤时，当前玩家自己发送的 public message 会继续显示；关闭该选项后才按普通 public 过滤处理。

### Turtle Potion Thrower

- 模块使用 Meteor 自带 Bind 触发；按下绑定键时执行一次投掷，然后自动关闭，不保持常驻监听状态。
- 只查找喷溅型神龟药水，并接受普通、长效和增强三种神龟药水；普通饮用药水和其他喷溅药水不会被使用。
- `Quick Swap` 默认启用。目标药水在背包内时会临时换到当前快捷栏槽位、右键投掷，再按 Meteor 原版 quick swap 逻辑换回。
- 关闭 `Quick Swap` 后只使用快捷栏内的目标药水；找不到可用药水或无法切换槽位时，`Notify` 默认会在聊天栏按 WaveXin 警告格式提示，同时 warn 级调试日志会记录到游戏 log。

### Base Finder

- `Normal Scan` 从起始区块向外按 ring 扫描，在目标区块间移动并可等待区块加载；每完成一个完整 ring 输出一次简洁进度。关闭时会保存当前断点，`Start From Previous Scan` 可恢复 Normal Scan 的位置、ring 和路线。
- `Spiral Scan` 使用独立的螺旋路线、步长、段数和渲染设置；可选自动走路、冲刺、视角锁定和打开界面时暂停。它不复用 Normal Scan 的断点逻辑。
- 两种扫描共享容器记录：扫描到已选择容器数量达到阈值的区块时，记录坐标与容器数量。
- Xaero 路径点为可选功能。仅在开启该选项时检查 Xaero Minimap；缺失时会关闭该选项并给出聊天警告，普通容器记录仍可用。路径点名称可使用数字、前缀和后缀，并按区域半径与每区域上限去重。创建成功的聊天提示会保留 WaveXin 前缀，并将路径点名称加粗、使用实际写入 Xaero 的颜色显示。

### 双语实现

- WaveXin 可见文本使用 Minecraft 原生 `assets/wavexin/lang/*.json` 资源；当客户端语言是简体中文时读取 `zh_cn`，英文和其他语言使用 `en_us` 兜底。
- 翻译只影响显示文本；`Module.name`、`Setting.name`、`SettingGroup.name`、enum 常量、NBT 和配置值都保持原始标识符，因此切换语言不会改写已保存设置。
- ClickGUI 的模块卡片、模块页面、设置组、设置标题与描述、enum 下拉框、自定义按钮、搜索结果和 Active Modules HUD 通过 WaveXin 专用 i18n 辅助方法显示当前语言文案；非 WaveXin 的 Meteor 模块保持上游行为。
- 聊天消息、警告、调试状态、断开原因和实体默认名称使用相同翻译层，并保留 Java Formatter 占位符和 Meteor 聊天样式 token。
- `verifyWaveXinTranslations` 会校验 `en_us`/`zh_cn` key 集合、显式 expected-key registry、静态 Java key、dead key、占位符、Meteor token、mojibake 和非法值；`testWaveXinI18nBehavior` 覆盖 fallback 格式化、keySegment 和 null enum 兜底。
