# WaveXinAddon Feature Logic Guide

This page describes implementation details and notable behavior for the public ClickGUI modules. It is not a complete settings reference; the in-game module settings remain authoritative.

### Better Elytra Fly

- Adjusts horizontal and vertical movement while gliding according to movement keys, view direction, and speed settings.
- `Auto Start` uses the normal client glide-start flow when its conditions are met; `Auto Stop` ends assisted flight under its configured conditions.
- `Speed Limit` caps the final speed, while `No Drag` removes the drag simulated by this module.
- The module's `Elytra Replace` setting group can independently enable automatic replacement. When the equipped elytra reaches the configured remaining-durability threshold, it finds a spare elytra above that threshold and equips it in the chest slot.
- Replacement can be limited to active gliding. Missing-spare warnings are rate-limited to prevent chat spam.
- The Inventory Tweaks compatibility option temporarily disables that module during replacement and restores it after the configured delay.

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

- MSG private messages, public chat, and death messages are filtered by separate settings using the complete message text.
- `MSG Allowlist` and `Public Message Allowlist` keep separate player lists. Adding a player to one list does not sync that player to the other list.
- The allowlist UI follows the Meteor Friends-style list input: existing players are shown as rows with a `-` button, and the bottom row has an input box plus a `+` button.
- `Hide Death Messages` uses plain death-announcement format matching, including suicide, self-explosion, environmental deaths, player kills, shots, explosions, cliff or void pushes, and common Chinese/English server formats. It no longer depends on color pairs, so other colored server messages are not hidden by that rule.
- `Show Own Public Messages` is enabled by default. When public-chat filtering is enabled, your own public messages remain visible unless this option is turned off.

### Turtle Potion Thrower

- The module is triggered by Meteor's built-in Bind. Pressing the bind throws once and then automatically disables the module instead of leaving a persistent listener active.
- It only searches for splash turtle potions, accepting normal, long, and strong Turtle Master variants. Drinkable potions and other splash potions are ignored.
- `Quick Swap` is enabled by default. If the target potion is in inventory, it is temporarily swapped into the selected hotbar slot, thrown with the normal right-click interaction, then swapped back using Meteor's original quick swap flow.
- With `Quick Swap` disabled, only hotbar potions are used. Missing potions or failed slot swaps use the normal WaveXin warning chat format when `Notify` is enabled, and warn-level debug details are recorded in the game log.

### Base Finder

- `Normal Scan` scans outward in rings from its starting chunk, moves between targets, and can wait for chunk loading. It prints one concise message after each completed ring. On disable it saves the current checkpoint; `Start From Previous Scan` restores the Normal Scan position, ring, and route.
- `Spiral Scan` has an independent spiral route, step size, segment count, and rendering settings. Optional auto-walk, sprint, view lock, and screen pause are available. It does not reuse the Normal Scan checkpoint flow.
- Both scan modes share container recording: a chunk is recorded when its selected-container count reaches the threshold.
- Xaero waypoints are optional. Xaero Minimap is checked only when the option is enabled; if it is unavailable, the option turns off with a chat warning while normal container recording remains available. Waypoint names support a number, prefix, and suffix, with area-radius and per-area limits used for deduplication. Successful creation messages keep the WaveXin prefix and render the waypoint name in bold using the actual color written to Xaero.

### Bilingual Implementation

- WaveXin visible text uses Minecraft-native `assets/wavexin/lang/*.json` resources. Simplified Chinese clients read `zh_cn`; English and all other languages fall back through `en_us`.
- Translation affects display only. `Module.name`, `Setting.name`, `SettingGroup.name`, enum constants, NBT, and config values keep their original identifiers, so changing language does not rewrite saved settings.
- ClickGUI module cards, module screens, setting groups, setting titles and descriptions, enum dropdowns, custom buttons, search results, and the Active Modules HUD display current-language text through WaveXin-specific i18n helpers. Non-WaveXin Meteor modules keep upstream behavior.
- Chat messages, warnings, debug state, disconnect reasons, and default entity labels use the same translation layer while preserving Java Formatter placeholders and Meteor chat style tokens.
- `verifyWaveXinTranslations` validates `en_us`/`zh_cn` key equality, the explicit expected-key registry, static Java keys, dead keys, placeholders, Meteor tokens, mojibake, and invalid values. `testWaveXinI18nBehavior` covers fallback formatting, keySegment normalization, and null enum fallback.
