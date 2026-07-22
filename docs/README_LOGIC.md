# WaveXinAddon 功能逻辑说明

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

### 双语实现

- WaveXin 可见文本使用 Minecraft 原生 `assets/wavexin/lang/*.json` 资源；当客户端语言是简体中文时读取 `zh_cn`，英文和其他语言使用 `en_us` 兜底。
- 翻译只影响显示文本；`Module.name`、`Setting.name`、`SettingGroup.name`、enum 常量、NBT 和配置值都保持原始标识符，因此切换语言不会改写已保存设置。
- ClickGUI 的模块卡片、模块页面、设置组、设置标题与描述、enum 下拉框、自定义按钮、搜索结果和 Active Modules HUD 通过 WaveXin 专用 i18n 辅助方法显示当前语言文案；非 WaveXin 的 Meteor 模块保持上游行为。
- 聊天消息、警告、调试状态、断开原因和实体默认名称使用相同翻译层，并保留 Java Formatter 占位符和 Meteor 聊天样式 token。
- `verifyWaveXinTranslations` 会校验 `en_us`/`zh_cn` key 集合、显式 expected-key registry、静态 Java key、dead key、占位符、Meteor token、mojibake 和非法值；`testWaveXinI18nBehavior` 覆盖 fallback 格式化、keySegment 和 null enum 兜底。
