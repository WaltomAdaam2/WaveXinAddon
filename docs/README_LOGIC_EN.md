# WaveXinAddon Feature Logic Guide

This page describes implementation details and notable behavior for the public ClickGUI modules. It is not a complete settings reference; the in-game module settings remain authoritative.

### Better Elytra Fly

- Adjusts horizontal and vertical movement while gliding according to movement keys, view direction, and speed settings. `Flight Speed` defaults to 2 and can be raised to 20; `Descent Speed` keeps its existing range.
- `Auto Start` uses the normal client glide-start flow when its conditions are met; `Auto Stop` ends assisted flight under its configured conditions.
- `Speed Limit` caps the final speed, while `No Drag` removes the drag simulated by this module.
- The module's `Elytra Replace` setting group can independently enable automatic replacement. When the equipped elytra reaches the configured remaining-durability threshold, it finds a spare elytra above that threshold and equips it in the chest slot.
- Replacement can be limited to active gliding. Missing-spare warnings are rate-limited to prevent chat spam.
- The Inventory Tweaks compatibility option temporarily disables that module during replacement and restores it after the configured delay.

### Elytra Fly Path

- Uses Target X and Target Z to calculate a two-dimensional direction, then adjusts view and movement while gliding toward the target.
- With `Nether Pos Calculation` enabled, the entered X and Z are each divided by 8 before they become the actual target coordinates.
- Arrival uses `Arrival Distance`. When both automatic stopping and automatic disconnect are enabled, the module stops before disconnecting.
- The module can take off automatically and warns when started outside its recommended altitude. Its own settings determine the final flight speed.

### Chicken Nametags and Sniffer Nametags

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
- `Hide Death Messages` uses plain death-announcement format matching, including suicide, bed/firework/TNT self-explosions, ender-pearl deaths, world-border suicide, environmental deaths, player kills, shots, fireballs, suffocation, falling objects, cliff or void pushes, and common Chinese/English server formats. It no longer depends on color pairs, so other colored server messages are not hidden by that rule.
- Public chat is recognized only through the verified single-line `<player> message` structure. `player: message`, command help, player lookup output, plugin status output, server announcements, and MSG private messages are not filtered as public chat. `Show Own Public Messages` is enabled by default and compares both the account name and the display name after formatting/prefix stripping.

### Turtle Potion Thrower

- The module is triggered by Meteor's built-in Bind. Pressing the bind throws once and then automatically disables the module instead of leaving a persistent listener active.
- It only searches for splash turtle potions, accepting normal, long, and strong Turtle Master variants. Drinkable potions and other splash potions are ignored.
- `Quick Swap` is enabled by default. If the target potion is already in the offhand or main hand, that hand is used directly. If it is in inventory, it is temporarily swapped into the selected hotbar slot, thrown with the normal right-click interaction, then swapped back from a `finally` block using Meteor's original quick swap flow.
- With `Quick Swap` disabled, only offhand, main-hand, or hotbar potions are used; temporary hotbar swaps restore the locally captured selected slot from a `finally` block and do not depend on Meteor's shared `swapBack()` state. Missing potions, failed swaps, rejected interactions, and restore failures always write warn-level game-log details. `Notify` only controls whether the normal WaveXin warning chat message is also shown.

### Base Finder

- `Normal Scan` scans outward in rings from its starting chunk, moves between targets, and can wait for chunk loading. It prints one concise message after each completed ring. The Restart fields remain editable Meteor settings. With `Resume Previous Scan` enabled, the entered ring, route, origin, and checkpoint values are used directly; Base Finder saves the current checkpoint once when the module is disabled and synchronizes these fields without per-tick refresh; disabling it while returning to a saved checkpoint preserves that checkpoint instead of writing the intermediate position. Disabling it from the settings screen or a keybind uses the same flow. The Restart reset button clears saved Normal Scan restart data.
- `Spiral Scan` has an independent spiral route, step size, segment count, and rendering settings. Auto-walk continuously aims at the current target chunk center, so edge drift is corrected before advancing to the next segment. With `Lock View` enabled, the visible view locks toward the current target; with it disabled, auto-navigation still uses temporary steering and restores the player's view each tick. Optional sprint and screen pause are available. It does not reuse the Normal Scan checkpoint flow.
- A traversed chunk is marked visited on the same game tick, and visited color takes priority over current-path color, so it turns green without waiting for the next turn. Normal render defaults to 128 chunks, allows up to 256 chunks, and preloads 10 rings by default up to a limit of 20. While returning to a saved checkpoint, that chunk uses a separate configurable highlight color that defaults to `#E0B0FF`; normal route colors resume immediately after arrival.
- Both scan modes share container recording: a chunk is recorded when its selected-container count reaches the threshold. `Detect Thrown Pearls` announces newly seen thrown ender pearl entities with the same warning-chat style as base detection. Ordinary target-center correction is normal movement and does not write warn logs; `BaseFinderDebug` warn logs are reserved for missing player/world state, current-chunk waits, and other diagnostic states.
- Xaero waypoints are optional. Xaero Minimap is checked only when the option is enabled; if it is unavailable, the option turns off with a chat warning while normal container recording remains available. Base waypoint names support a number, prefix, and suffix, with area-radius and per-area limits used for deduplication. `Area Radius` defaults to 5 and `Waypoints per Area` defaults to 3. `Record Thrown Pearl` creates unlimited `Pearl 1`, `Pearl 2` waypoints with `P1`, `P2` aliases, uses the same waypoint color setting, and does not count against the base waypoints-per-area limit. Successful creation messages keep the WaveXin prefix and render the waypoint name in bold using Xaero's actual 0–15 color mapping. A random color ID is generated once and reused for both the waypoint and its chat message.

### Bilingual Implementation

- WaveXin visible text uses Minecraft-native `assets/wavexin/lang/*.json` resources. Simplified Chinese clients read `zh_cn`; English and all other languages fall back through `en_us`.
- Translation affects display only. `Module.name`, `Setting.name`, `SettingGroup.name`, enum constants, NBT, and config values keep their original identifiers, so changing language does not rewrite saved settings.
- ClickGUI module cards, module screens, setting groups, setting titles and descriptions, enum dropdowns, custom buttons, search results, and the Active Modules HUD display current-language text through WaveXin-specific i18n helpers. Non-WaveXin Meteor modules keep upstream behavior.
- Chat messages, warnings, debug state, disconnect reasons, and default entity labels use the same translation layer while preserving Java Formatter placeholders and Meteor chat style tokens.
- `verifyWaveXinTranslations` validates `en_us`/`zh_cn` key equality, the explicit expected-key registry, static Java keys, dead keys, placeholders, Meteor tokens, mojibake, and invalid values. `testWaveXinI18nBehavior` covers fallback formatting, keySegment normalization, and null enum fallback.
