<div align="center">

<img src=".github/assets/logo.png" alt="Orekas" width="180" />

# Orekas

**Ore-vein ESP for [Meteor Client](https://meteorclient.com/), powered by [orefinder.gg](https://www.orefinder.gg)'s WASM model with live-world validation and an anti-xray fallback heuristic.**

[![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square)](LICENSE)
[![Minecraft](https://img.shields.io/badge/minecraft-1.21.11-brightgreen?style=flat-square)](https://www.minecraft.net/)
[![Java](https://img.shields.io/badge/java-21-orange?style=flat-square)](https://adoptium.net/)
[![Meteor](https://img.shields.io/badge/meteor--client-%2A-red?style=flat-square)](https://meteorclient.com/)
[![Release](https://img.shields.io/github/v/release/furkankoykiran/Orekas?style=flat-square)](https://github.com/furkankoykiran/Orekas/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/furkankoykiran/Orekas/total?style=flat-square)](https://github.com/furkankoykiran/Orekas/releases)

</div>

> **⚠ Read the [Disclaimer](#disclaimer) before use — especially before enabling any DonutSMP automation module.**

---

## Features

### Ore ESP

| Module | Description |
| --- | --- |
| **OreFinder** | ESP that highlights every ore block inside a 13×13 chunk window around you. Vein centroids come from orefinder.gg's in-process WASM model and each centroid is validated against the live `ClientWorld` so only real, exposed ore blocks render. Buried clusters on AntiXray-style servers fall back to a centroid prediction box. |

### DonutSMP Utilities

Economy and safety automation modules designed for use on [DonutSMP](https://donutsmp.net/). See the disclaimer section before enabling any of these.

| Module | Description |
| --- | --- |
| **OrderSniper** | Scans the `/orders` GUI each cycle, selects matching buy orders above a minimum price, and delivers your items via batched `QUICK_MOVE` packets. Confirms delivery, logs each completed sale to chat, and integrates with **AdminList** for per-role blacklist/whitelist filtering. |
| **AutoSell** | Opens the `/sell` GUI and moves matching inventory items into it. Supports whitelist and blacklist modes. Uses inventory-identity slot mapping so it works correctly regardless of chest row count. |
| **PlayerDetection** | Tracks players entering render distance. Fires a configurable stop command, toggles selected modules, and optionally disconnects or sends a Discord webhook notification on detection. Integrates with **AdminList** to trust or specifically alert for admin players. |
| **AdminList** | Maintains a named roster of administrators or notable players. Exposes a `Role` enum (`OFF` / `BLACKLIST` / `WHITELIST`) that OrderSniper and PlayerDetection consult to decide whether to skip or target listed players. |

---

## How is Orekas different from meteor-rejects' OreSim?

[OreSim](https://github.com/AntiCope/meteor-rejects) and Orekas solve a similar problem in different ways:

- **OreSim** simulates Minecraft's vanilla world generation on-device using the world seed. On unmodified vanilla worlds it produces exact ore positions without any third-party model.
- **Orekas** delegates to orefinder.gg's pre-trained WASM model (bundled and shaded — no network calls). The model returns vein **centroids** plus confidence tiers, which Orekas then validates by scanning live blocks in the loaded `ClientWorld`.
- **Per-block validation.** ESP boxes only draw on real ore blocks the client can see. Buried clusters on AntiXray-style servers (Paper engine-mode-2 and similar) get a centroid prediction overlay using the same opaque-neighbors heuristic the server uses to decide what to hide.
- **Practical takeaway.** OreSim is more precise on vanilla worlds and fully self-contained. Orekas is more robust to anti-xray obfuscation; the trade-off is that vein positions come from a model rather than a deterministic simulation.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.11.
2. Install [Meteor Client](https://meteorclient.com/) for the same version.
3. Download the latest `orekas-addon-<version>.jar` from [Releases](https://github.com/furkankoykiran/Orekas/releases).
4. Drop the jar into your `mods/` folder alongside Meteor Client.
5. Launch Minecraft and look for the **Orekas** category in the Meteor module list.

---

## Usage

### OreFinder

Toggle **OreFinder** under the Orekas category.

| Group | Setting | Description |
| --- | --- | --- |
| Input | `world-seed` | World seed (required on multiplayer; auto-detected on single-player). |
| Input | `platform` | Minecraft edition + version selector (default: Java 1.21). |
| Input | `refresh-now` | Toggle ON to trigger an immediate search. |
| Ores | `diamond` … `coal` | Per-ore inclusion flags. Diamond is on by default. |
| Render | `shape-mode` | Outline / filled / both. |
| Render | `fill-alpha` | Alpha for the box fill (0–255). |
| Render | `nametag-for-unloaded` | Show a floating `Nx Ore` label for clusters whose chunks are not loaded yet. |
| Render | `nametag-scale` | Size of the floating labels. |
| Colors | `<ore>-color` | Per-ore-type render color. |
| Advanced | `chunk-radius` | Search radius in chunks (default 6 → 13×13 area). |
| Advanced | `vein-scan-radius` | Block radius around each centroid scanned for real ore blocks. |
| Advanced | `expansions-per-tick` | Max cluster scans per tick (default 128). Lower to reduce CPU usage. |
| Advanced | `fetch-cooldown-ms` | Minimum ms between WASM searches. |

### OrderSniper

| Setting | Description |
| --- | --- |
| `search-query` | Argument passed to `/orders <query>`. |
| `deliver-item` | The item you will deliver. Must match what the orders in the GUI are requesting. |
| `min-price` | Minimum acceptable order price (e.g. `100`, `2.5k`, `1m`). |
| `shulker-mode` | Deliver shulker boxes that contain the target item (empty shulkers are always skipped). |
| `refresh-interval` | Ticks between each GUI interaction. `0` = as fast as possible. Raise on high-latency connections. |
| `notifications` | Show status messages in chat. |
| `debug` | Print timestamped state transitions to chat for troubleshooting. |
| Blacklist | `blacklisted-players` | Orders from these players will be skipped. |
| Admin List | `role` | `BLACKLIST` skips admin orders; `WHITELIST` accepts only admin orders. |

### AutoSell

| Setting | Description |
| --- | --- |
| `mode` | `WHITELIST`: sell only listed items. `BLACKLIST`: sell everything except listed items. |
| `items` | Item list for the whitelist/blacklist. |
| `delay-ticks` | Ticks between individual sell actions. |
| `notifications` | Show status messages in chat. |
| `debug` | Print slot decisions to chat. |

### PlayerDetection

| Setting | Description |
| --- | --- |
| `whitelist` | Players to always ignore (friends, alts, etc.). |
| `debug` | Log player arrivals and trigger decisions to chat. |
| Alert | `alert-mode` | `CHAT`, `TOAST`, or `BOTH`. |
| Actions | `stop-command` | Chat command sent on detection (e.g. `#stop`). Leave blank to skip. |
| Actions | `toggle-modules` | Modules to deactivate when a player is detected. |
| Actions | `self-disable` | Deactivate this module after triggering. |
| Actions | `disconnect` | Disconnect from the server on detection. |
| Admin List | `role` | `WHITELIST` = treat admins as safe (no alert). `BLACKLIST` = always alert for admins even if in personal whitelist. |
| Webhook | `enable`, `url`, `ping-me`, `discord-id` | Discord webhook notification on detection. |

### AdminList

| Setting | Description |
| --- | --- |
| `admins` | Player names shared with other modules as a blacklist or whitelist. |
| `debug` | Log every `isAdmin()` lookup to chat. |

---

## Dimension support

| Dimension | Behaviour |
| --- | --- |
| Overworld, **Y < 0** | Active. Anti-xray heuristic targets the deepslate region servers commonly hide. |
| Overworld, Y ≥ 0 | Idle. Above Y=0 the server typically streams real blocks, so the heuristic is invalid. |
| Nether | Active. Includes Ancient Debris when enabled. |
| End | Idle. The module never renders anything in the End. |

Ancient Debris is filtered out automatically in the Overworld even if the toggle is on.

---

## FAQ

**Does it work on AntiXray-style servers?**
Yes — buried centroids are rendered as prediction boxes when the WASM thinks an ore is fully surrounded by opaque blocks (mirroring Paper engine-mode-2 logic). Per-block ESP still draws the exposed parts of any cluster the server actually streams.

**Do I need the world seed on multiplayer?**
Yes. On servers the client never receives the seed, so `world-seed` must be set manually. Single-player is auto-detected.

**Does it phone home?**
No. The orefinder.gg model runs in-process via the bundled [Chicory](https://github.com/dylibso/chicory) WASM runtime. There are no network calls at runtime.

---

## Credits

- [orefinder.gg](https://www.orefinder.gg) for the underlying WASM model.
- [Meteor Development](https://github.com/MeteorDevelopment) for Meteor Client.
- [Chicory](https://github.com/dylibso/chicory) for the pure-Java WASM runtime.
- Built by **Webrekas**.

---

## Disclaimer

**Orekas is an educational project. Use it at your own risk.**

### Ore ESP (OreFinder)

Ore ESP is detectable by anti-cheat systems and prohibited on most public servers. Use only in single-player, on servers where client-side modifications are explicitly permitted, or for educational purposes.

### DonutSMP Automation Modules

> The OrderSniper, AutoSell, PlayerDetection, and AdminList modules contain **server-specific automation** targeted at DonutSMP's economy system. Using these modules may:
>
> - Violate [DonutSMP's rules](https://donutsmp.net/) and result in a **temporary or permanent ban**.
> - Trigger anti-cheat or anti-macro systems.
> - Be considered unfair play by the server community.
>
> **These modules are provided solely for educational and research purposes.** The author and contributors of Orekas accept no responsibility for any consequences — including account bans, loss of in-game items, or other penalties — resulting from their use. You are solely responsible for understanding and complying with the rules of any server you play on.
>
> **If you are unsure whether automation is allowed on your server, do not use these modules.**

### License

[GPL-3.0-only](LICENSE).
