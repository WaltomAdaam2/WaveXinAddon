# WaveXinAddon Feature Logic Guide

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

### Bilingual Implementation

- WaveXin visible text uses Minecraft-native `assets/wavexin/lang/*.json` resources. Simplified Chinese clients read `zh_cn`; English and all other languages fall back through `en_us`.
- Translation affects display only. `Module.name`, `Setting.name`, `SettingGroup.name`, enum constants, NBT, and config values keep their original identifiers, so changing language does not rewrite saved settings.
- ClickGUI module cards, module screens, setting groups, setting titles and descriptions, enum dropdowns, custom buttons, search results, and the Active Modules HUD display current-language text through WaveXin-specific i18n helpers. Non-WaveXin Meteor modules keep upstream behavior.
- Chat messages, warnings, debug state, disconnect reasons, and default entity labels use the same translation layer while preserving Java Formatter placeholders and Meteor chat style tokens.
- `verifyWaveXinTranslations` validates `en_us`/`zh_cn` key equality, the explicit expected-key registry, static Java keys, dead keys, placeholders, Meteor tokens, mojibake, and invalid values. `testWaveXinI18nBehavior` covers fallback formatting, keySegment normalization, and null enum fallback.
