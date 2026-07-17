# WaveXinAddon Feature Logic Guide

**Language / 语言:** 中文 | [English](#english)

## 中文

本页说明 ClickGUI 中公开模块的实现方式与需要注意的行为。它不是完整的设置手册；实际可用选项以游戏内模块设置为准。

### Better Elytra Fly

- 在鞘翅飞行期间接管水平与垂直运动计算，根据移动按键、视角和速度设置调整速度。
- `Auto Start` 会在满足条件时正常开始滑翔；`Auto Stop` 会在停止移动等条件下结束辅助飞行。
- `Speed Limit` 限制最终速度，`No Drag` 移除本模块模拟的阻力。
- 可选的鞘翅替换会寻找耐久高于阈值的备用鞘翅并装备到胸甲栏；可暂时暂停 Meteor 的 InventoryTweaks，避免两个库存操作同时执行。

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

- 私信和公共聊天仍按各自设置与完整消息格式过滤。
- `Hide Death Messages` 不再检查死亡关键词或死亡句式。
- 该选项只检查服务器的 `GameMessageS2CPacket`：同一条消息同时含 Minecraft 亮绿 `§a` 和亮红 `§c` 时才会隐藏。
- 同时兼容服务端直接发送的旧式颜色码与 Text 组件实际样式；普通玩家聊天不会使用这条颜色组合规则。

### Base Finder

- `Normal Scan` 从起始区块向外按 ring 扫描，在目标区块间移动并可等待区块加载；每完成一个完整 ring 输出一次简洁进度。关闭时会保存当前断点，`Start From Previous Scan` 可恢复 Normal Scan 的位置、ring 和路线。
- `Spiral Scan` 使用独立的螺旋路线、步长、段数和渲染设置；可选自动走路、冲刺、视角锁定和打开界面时暂停。它不复用 Normal Scan 的断点逻辑。
- 两种扫描共享容器记录：扫描到已选择容器数量达到阈值的区块时，记录坐标与容器数量。
- Xaero 路径点为可选功能。仅在开启该选项时检查 Xaero Minimap；缺失时会关闭该选项并给出聊天警告，普通容器记录仍可用。路径点名称可使用数字、前缀和后缀，并按区域半径与每区域上限去重。

## English

This page describes implementation details and notable behavior for the public ClickGUI modules. It is not a complete settings reference; the in-game module settings remain authoritative.

### Better Elytra Fly

- Adjusts horizontal and vertical movement while gliding according to movement keys, view direction, and speed settings.
- `Auto Start` uses the normal client glide-start flow when its conditions are met; `Auto Stop` ends assisted flight under its configured conditions.
- `Speed Limit` caps the final speed, while `No Drag` removes the drag simulated by this module.
- Optional elytra replacement finds a spare elytra above the durability threshold and equips it in the chest slot. It can temporarily pause Meteor InventoryTweaks to prevent competing inventory actions.

### Simple Elytra Fly Path

- Uses Target X and Target Z to calculate a two-dimensional direction, then adjusts view and movement while gliding toward the target.
- With `Nether Pos Calculation` enabled, the entered X and Z are each divided by 8 before they become the actual target coordinates.
- Arrival uses `Arrival Distance`. When both automatic stopping and automatic disconnect are enabled, the module stops before disconnecting.
- The module can take off automatically and warns when started outside its recommended altitude. Its own settings determine the final flight speed.

### ChickenNametags And SnifferNametags

- Render projected custom nametags for chickens or sniffers during the 2D render stage.
- Nametags can show entity name, health, and distance, with configurable range, scale, background, and text colors.
- Only matching entity types inside the configured range are rendered; entity data and server state are not modified.

### Auto Login

- When server restriction is enabled, automation runs only for supported 2b2t.xin addresses.
- Listens for title, subtitle, and chat text, and only handles `/l` for offline accounts after detecting a login prompt. Microsoft accounts never send that command.
- Accounts are stored by current account name. Offline passwords are encrypted and are never echoed in chat, status output, or the saved-account list.
- After login success, a state machine handles Daily Flower check-in, game joining, and follow-up right-click actions. Delays, retries, and connection resets prevent duplicate commands and stale-screen interactions.

### Chat Filter

- Private and public chat continue to use their own settings and complete message formats.
- `Hide Death Messages` no longer checks death keywords or death-text patterns.
- It only checks server `GameMessageS2CPacket` messages: a message is hidden only when it contains both Minecraft bright green `§a` and bright red `§c`.
- Both raw legacy formatting codes and actual Text component styles are supported. This color-pair rule does not apply to normal player chat.

### Base Finder

- `Normal Scan` scans outward in rings from its starting chunk, moves between targets, and can wait for chunk loading. It prints one concise message after each completed ring. On disable it saves the current checkpoint; `Start From Previous Scan` restores the Normal Scan position, ring, and route.
- `Spiral Scan` has an independent spiral route, step size, segment count, and rendering settings. Optional auto-walk, sprint, view lock, and screen pause are available. It does not reuse the Normal Scan checkpoint flow.
- Both scan modes share container recording: a chunk is recorded when its selected-container count reaches the threshold.
- Xaero waypoints are optional. Xaero Minimap is checked only when the option is enabled; if it is unavailable, the option turns off with a chat warning while normal container recording remains available. Waypoint names support a number, prefix, and suffix, with area-radius and per-area limits used for deduplication.