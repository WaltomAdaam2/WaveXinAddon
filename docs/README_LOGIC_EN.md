# WaveXinAddon Feature Logic Guide

This page describes implementation details and notable behavior for the public ClickGUI modules. It is not a complete settings reference; the in-game module settings remain authoritative.

### Better Elytra Fly

- Adjusts horizontal and vertical movement while gliding according to movement keys, view direction, and speed settings. `Flight Speed` retains its existing saved-setting key while displaying as Initial Speed, with a default of 1.8; `Descent Speed` keeps its existing range.
- The standalone `Speed Acceleration` group is disabled by default, preserving fixed-speed behavior. When enabled, speed rises only during active gliding by the configured per-second amount, never exceeds its cap, and resets to Initial Speed when a new glide starts.
- With `Reset After Lagback` enabled, a server position correction resets speed to Initial Speed for exactly five seconds before ramping resumes. With it disabled, corrections do not change speed.
- The module's `Elytra Replace` setting group can independently enable automatic replacement. When the equipped elytra reaches the configured remaining-durability threshold, it finds a spare elytra above that threshold and equips it in the chest slot.
- Replacement can be limited to active gliding. Missing-spare warnings are rate-limited to prevent chat spam.
- The Inventory Tweaks compatibility option temporarily disables that module during replacement and restores it after the configured delay.

### Elytra Fly Path

- Uses Target X and Target Z to calculate a two-dimensional direction, then adjusts view and movement while gliding toward the target.
- With `Nether Pos Calculation` enabled, the entered X and Z are each divided by 8 before they become the actual target coordinates.
- Arrival uses `Arrival Distance`. When both automatic stopping and automatic disconnect are enabled, the module stops before disconnecting.
- The module can take off automatically and warns when started outside its recommended altitude. Its final speed uses the same optional ramping controls as Better Elytra Fly.
- The optional Xaero waypoint creates `Elytra Path` with initials `EP` on activation. It uses converted X/Z when Nether conversion is enabled and the player's activation Y. Deactivation only attempts to remove the exact object created by that activation; missing or unsupported Xaero APIs issue one warning without stopping flight.

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

### Container Recorder

- Container Recorder is an independent persistent module. It checks loaded chunks within its `Scan Radius` around the player (default: 4 chunks) and records a coordinate and count only when the selected container types meet `Container Threshold`.
- It retains thrown-ender-pearl detection, record files, Xaero waypoints, the vanilla achievement toast, and the challenge-complete sound. When enabled manually, it operates independently of every scan module.
- Base Finder Normal Scan, Base Finder Spiral Scan, and End Gateway Finder each expose `Start Container Recorder`. A scan requests the recorder only after it really starts; concurrent requests keep it active until the last scan ends. A recorder the player enabled manually remains on after scans end.

### Base Finder

- `Normal Scan` scans outward in rings from its starting chunk, moves between targets, and can wait for chunk loading. It prints one concise message after each completed ring. The Restart fields remain editable Meteor settings. With `Resume Previous Scan` enabled, the entered ring, route, origin, and checkpoint values are used directly; Base Finder saves the current checkpoint once when the module is disabled and synchronizes these fields without per-tick refresh; disabling it while returning to a saved checkpoint preserves that checkpoint instead of writing the intermediate position. Disabling it from the settings screen or a keybind uses the same flow. The Restart reset button clears saved Normal Scan restart data.
- `Spiral Scan` has an independent spiral route, step size, segment count, and rendering settings. Auto-walk continuously aims at the current target chunk center, so edge drift is corrected before advancing to the next segment. With `Lock View` enabled, the visible view locks toward the current target; with it disabled, auto-navigation still uses temporary steering and restores the player's view each tick. Optional sprint and screen pause are available. It does not reuse the Normal Scan checkpoint flow.
- A traversed chunk is marked visited on the same game tick, and visited color takes priority over current-path color, so it turns green without waiting for the next turn. Normal render defaults to 128 chunks, allows up to 256 chunks, and preloads 10 rings by default up to a limit of 20. While returning to a saved checkpoint, that chunk uses a separate configurable highlight color that defaults to `#E0B0FF`; normal route colors resume immediately after arrival.
- Both scan modes can start the independent Container Recorder with their own `Start Container Recorder` setting. Ordinary target-center correction is normal movement and does not write warn logs; `BaseFinderDebug` warn logs are reserved for missing player/world state, current-chunk waits, and other diagnostic states.
- Xaero waypoints are optional. Xaero Minimap is checked only when the option is enabled; if it is unavailable, the option turns off with a chat warning while normal container recording remains available. Base waypoint names support a number, prefix, and suffix, with area-radius and per-area limits used for deduplication. `Area Radius` defaults to 5 and `Waypoints per Area` defaults to 3. `Record Thrown Pearl` creates unlimited `Pearl 1`, `Pearl 2` waypoints with `P1`, `P2` aliases, uses the same waypoint color setting, and does not count against the base waypoints-per-area limit. Successful creation messages keep the WaveXin prefix and render the waypoint name in bold using Xaero's actual 0–15 color mapping. A random color ID is generated once and reused for both the waypoint and its chat message.

### End Gateway Finder

- The module starts only in the End and predicts End return gateways locally from the entered world seed. `Generation Version` selects 1.12, 1.20.4, or both placement rules; predictions never replace confirming the actual blocks in loaded chunks.
- `Rolling Radius (Chunks)` is a circular work radius centered on the player. It defaults to 1,000 chunks and ranges from 8 to 100,000. One background thread processes 32x32-chunk tasks from the center outward, so the route can start with the first usable candidate instead of waiting for the whole range. When the player comes within 100 blocks of the current work-circle edge, the center advances to the player and reuses overlapping task results from the current game session.
- The rolling cache stays in memory and is isolated by world instance, seed, and generation version. Disabling and re-enabling the module can reuse the same session cache, while changing world, seed, or generation version cannot mix results. A dedicated top-right progress toast remains visible while the module is enabled and dynamically reports status, completed/total chunks, percentage, and the current workspace's gateway count. Completing the current range or temporarily exhausting candidates leaves the module enabled to await new results or the next range advance.
- Base Finder, Elytra Fly Path, and End Gateway Finder are mutually exclusive. If one is active, either of the others refuses to start and reports the conflict in chat. While enabled, Base Finder and End Gateway Finder suppress player WASD, jump, and sneak input like Elytra Fly Path, then restore physical key states when disabled.
- It supports four route algorithms, dwell time, automatic movement, per-seed persisted visited gateways, and rendering. Default colors are orange for the current target, green for 1.12 predictions, red for 1.20.4 predictions, and blue for completed gateways; all are editable.
- After it has confirmed the End and started scanning, `Start Container Recorder` can request the independent Container Recorder.

### Litematica Printer

- Printer is a semi-automatic builder: it does not automatically path, mine, or scaffold terrain for the player. After the player moves into position, the planner selects only loaded projection targets that are within interaction range, have a real six-direction support neighbor, and can currently be interacted with correctly. The old Baritone and mining adapters remain in the source tree but are disconnected from the active runtime path.
- The placement executor combines three established behavior models: Scaffold-style continuous support-face placement, Alien Client Surround-style packet multi-place and silent hotbar switching, and Mio Surround-style nearby support prioritization. These behaviors are independently implemented; WaveXinAddon does not load Alien or Mio at runtime and does not embed either client wholesale.
- `PrinterBatchPlanner` is the single decision source for both highlighting and the next placement. A batch defaults to at most three targets and uses stable coordinates, player distance, material availability, and real support to determine order. Green is the exact batch the executor will attempt, yellow marks targets waiting for their retry interval, and red marks states requiring manual correction. Directional or shape-sensitive blocks cannot rely on an unconfirmed same-tick virtual support.
- Placement compares the exact target `BlockState`. Hoppers derive their clicked face from the required output direction. Paired chests are placed as one logical unit using facing and `LEFT/RIGHT` state, and a provisional single chest is not marked complete. Incorrect orientations or malformed chest pairs that cannot be repaired safely are highlighted in red for manual correction instead of causing random block breaking.
- When placing against containers or other interactive supports, Printer temporarily sneaks on the server and sends a temporary yaw/pitch only when the current view cannot produce the target state. Server rotation is restored immediately after interaction and the local camera is never changed. Container screens opened by Printer are closed only inside a short guard window; manual interaction and restock screens are unaffected.
- Build materials are searched from left to right in the hotbar first. With `Allow Inventory Pull`, a full stack is moved from inventory only when the hotbar has none of the required material, following inventory order from left to right and top to bottom instead of performing a temporary swap for every placement.
- `.sel`, `.sel 1`, and `.sel 2` select the restock cuboid; `.sel c` clears both corners, its renderer, and region-bound container cache. Restock demand comes from the active Litematica layer/range or the currently actionable area, prioritizes nearby targets and higher remaining demand, and takes whole stacks from containers. It never returns player items automatically. If manual inventory cleanup is required, the module disables and resumes from the session cache after reactivation.
- Projection targets and observed containers use a session cache bound to the world, projection fingerprint, region, and state summary. Disabling the module preserves progress; leaving the world or game clears it. On activation, loaded projection chunks and supply containers are scanned immediately, while unloaded portions are recorded as the player approaches. Cache data never replaces final loaded-state confirmation.
- `Debug Log` creates one `meteor-client/wavexin/printer/yyyy-MM-dd-N.log` file per Minecraft process; module toggles only flush it. It records planner decisions, support sources, exact states, temporary rotation, screen interception, restocking, cache decisions, and audit results.
- `Maximum Projection Volume` defaults to 10,000,000 and has a hard limit of 500,000,000. Raising the cap only permits larger bounds; scanning remains constrained by per-tick budgets, chunk loading, and memory. Liquids, entities, waterlogged states, and structures that cannot be placed reliably are reported after other actionable blocks have been handled.

### Bilingual Implementation

- WaveXin visible text uses Minecraft-native `assets/wavexin/lang/*.json` resources. By default it follows the client language for Simplified Chinese or the English fallback. `.wavexin lang Simplified Chinese` and `.wavexin lang English` save an override for WaveXinAddon visible text only; they do not change Minecraft, Meteor, or other addons.
- Translation affects display only. `Module.name`, `Setting.name`, `SettingGroup.name`, enum constants, NBT, and config values keep their original identifiers, so changing language does not rewrite saved settings.
- ClickGUI module cards, module screens, setting groups, setting titles and descriptions, enum dropdowns, custom buttons, search results, and the Active Modules HUD display current-language text through WaveXin-specific i18n helpers. Non-WaveXin Meteor modules keep upstream behavior.
- Chat messages, warnings, debug state, disconnect reasons, and default entity labels use the same translation layer while preserving Java Formatter placeholders and Meteor chat style tokens.
- `verifyWaveXinTranslations` validates `en_us`/`zh_cn` key equality, the explicit expected-key registry, static Java keys, dead keys, placeholders, Meteor tokens, mojibake, and invalid values. `testWaveXinI18nBehavior` covers fallback formatting, keySegment normalization, and null enum fallback.
