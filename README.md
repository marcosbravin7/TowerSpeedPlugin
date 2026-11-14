# TowerSpeedPlugin

A collaborative, shared **LiveSplit-style Speedrun Timer** plugin built for **Paper / Purpur 26.3** servers using **Java 25**.

Designed specifically for **co-op runs** (such as tower climbing, parkour challenges, or speedruns) where friends play together and share a single synchronized timer.

---

## Features

* **LiveSplit Sidebar Scoreboard**:
  * Clean, zero-flicker display on the Minecraft sidebar.
  * Shows previous splits with time and delta comparisons vs the Server Record (`-0:12.4` in green or `+0:03.2` in red).
  * Highlights the current active split segment in real time (`▶ Split Name 02:14.2`).
  * Displays upcoming split targets.
  * Live total elapsed time, Server Record (PB), overall delta, and state indicator (`RUNNING`, `PAUSED`, etc.).
* **Shared Co-op Experience**:
  * One shared timer for the entire server. When any player starts, pauses, resets, or hits a split, it synchronizes across all players.
  * Individual players can toggle the sidebar scoreboard on/off for themselves (`/timer toggle`) without affecting teammates.
* **Top 5 Leaderboard & Gold Splits**:
  * Automatically tracks and maintains the **Top 5 fastest runs** for each category.
  * Stores total completion time, exact date and time, and the roster of players who completed the run.
  * Tracks **Gold Splits** (the fastest time the group has ever cleared that specific segment).
  * Plays celebratory rank sounds when placing in the top 5, plus victory titles and fireworks for a new #1 Server Record!
* **Accidental Record Management**:
  * Easily inspect individual run splits via `/timer records view <#>` and delete accidental or invalid runs via `/timer records delete <#>`.
* **Multi-World Speedrun Reset (`/timer worldreset`) & Lobby Separation**:
  * Separates the permanent **Lobby** world (`world`) from dedicated, rotating **Speedrun** worlds (`speedrun_<N>`, `speedrun_<N>_nether`, `speedrun_<N>_the_end`) — reset seeds in-game with zero server restarts!
  * **Throttled Spiral Spawn Pre-Generation**: Pre-generates spawn chunks asynchronously in the background while players wait in the lobby with zero TPS drop, displaying live progress (`0%–100%`) on a Lobby BossBar.
  * **On-Demand Nether & The End**: Automatically creates and links the speedrun Nether (8:1 scaling) and The End (5×5 obsidian platform + exit portal routing) when a portal is entered.
  * **Full Vanilla Speedrun Player Reset**: Starting a run teleports players from the lobby and resets inventory, Ender Chest, health/hunger, XP, potion effects, respawn points, and advancements.
  * **Seamless Lobby & Rejoin (`/timer lobby` & `/timer join`)**: Step out to the lobby mid-run and return to your exact coordinates, dimension, health/hunger, inventory, and effects — or spectate teammates if you died in Hardcore.
  * **Independent Speedrun Hardcore & Difficulty**: Toggle Hardcore mode (`/timer hardcore`) and difficulty (`/timer difficulty`) exclusively for speedrun worlds while keeping the Lobby non-hardcore and free to use `/difficulty` and `/gamemode`.
* **Audio-Visual Cues**:
  * 3-second countdown with ticking sounds and large title numbers before starting.
  * Distinct sound effects for splits, gold splits, pauses, resumes, and record finishes.
  * Real-time Action Bar split notifications (`[Split 2] Floor 20 - 05:22.1 (-00:14.2)`).

---

## Commands & Permissions

| Command | Aliases | Description | Permission |
| :--- | :--- | :--- | :--- |
| `/timer start [instant]` | | Teleports players to the speedrun world, resets state, and starts timer | `towerspeed.timer` (default: all) |
| `/split [name]` | `/timer split` | Records the next split (or custom-named split) | `towerspeed.timer` (default: all) |
| `/timer unsplit` | `/unsplit`, `/undo` | Undoes the last recorded split and restores segment | `towerspeed.timer` (default: all) |
| `/timer pause` | | Pauses the running timer | `towerspeed.timer` (default: all) |
| `/timer resume` | | Resumes the paused timer | `towerspeed.timer` (default: all) |
| `/timer reset` | | Resets the timer back to 00:00.0 | `towerspeed.timer` (default: all) |
| `/timer lobby` | | Saves speedrun state, teleports you to the lobby, and restores Survival mode | `towerspeed.timer` (default: all) |
| `/timer join` | `rejoin`, `return`, `back` | Joins or returns to the active speedrun world from the lobby | `towerspeed.timer` (default: all) |
| `/timer toggle` | | Toggles the sidebar scoreboard on/off for yourself | None (all players) |
| `/timer category <name>` | `/timer cat` | Switches active category (e.g. `tower_climb`, `any_percent`) | `towerspeed.timer` (default: all) |
| `/timer records [cat]` | `/timer pb`, `/timer leaderboard` | Displays the Top 5 leaderboard with times and dates | None (all players) |
| `/timer records view <#>` | | View detailed split times for a specific ranked run | None (all players) |
| `/timer records delete <#>` | `/timer records remove` | Deletes an accidental record and recalculates PB | `towerspeed.timer` (default: all) |
| `/timer records clear [cat]` | | Clears all leaderboard runs and gold splits for a category | `towerspeed.admin` (default: OP) |
| `/timer hardcore <on\|off\|toggle>` | `/timer hc` | Toggles Hardcore mode for speedrun worlds (Lobby stays non-hardcore) | `towerspeed.admin` (default: OP) |
| `/timer difficulty <easy\|normal\|hard>` | `/timer diff` | Sets difficulty for speedrun worlds independent of the lobby | `towerspeed.admin` (default: OP) |
| `/timer worldreset` | | Resets the speedrun world, returns players to the lobby, and prepares a new seed | `towerspeed.admin` (default: OP) |
| `/timer reload` | | Reloads `config.yml` and all record files from disk | `towerspeed.admin` (default: OP) |

---

## Configuration (`config.yml`)

You can define and customize categories and their sequential split checkpoints in `<server-root>/plugins/TowerSpeedPlugin/config.yml`:

```yaml
# Active category on server start
active-category: "any_percent"

# Sidebar update frequency in ticks (2 ticks = 10 updates per second, smooth tenths of a second)
update-interval-ticks: 2

# Countdown before starting (in seconds; 0 for instant)
countdown-seconds: 3

# Play sound cues for countdown, splits, resets, and personal bests
play-sounds: true

# Spawn celebration fireworks for all players upon setting a new server record
celebration-fireworks: true

# Radius in chunks around the speedrun spawn to pre-generate while waiting in the lobby (8 = 17x17 = 289 chunks)
pregen-chunk-radius: 8

# Maximum number of chunks generating asynchronously at the same time (lower = less CPU/lag in lobby)
pregen-max-concurrent: 2

# Speedrun world settings (independent from the lobby world, which is never hardcore)
speedrun-hardcore: false
speedrun-difficulty: "NORMAL"

# Pre-configured categories & their sequential splits
categories:
  any_percent:
    display-name: "<aqua><bold>Any% Co-op</bold></aqua>"
    splits:
      - "Start"
      - "Village"
      - "Nether"
      - "Fortress"
      - "Stronghold"
      - "End Fight"

  no_splits:
    display-name: "<aqua><bold>Any% Co-op</bold></aqua>"
    splits:
      - "END"
```
*Run `/timer reload` in-game after editing `config.yml` to apply changes immediately.*

---

## Records Storage (`records/<category>.json`)

Records are stored in clean, human-readable JSON files located in:
```
<server-root>/plugins/TowerSpeedPlugin/records/
├── any_percent.json
└── no_splits.json
```

### Example Record File (`records/any_percent.json`)

```json
{
  "categoryId": "any_percent",
  "topRuns": [
    {
      "categoryId": "any_percent",
      "totalTimeMs": 1152300,
      "recordedAtEpochMs": 1793234500000,
      "playerNames": [ "Marcos", "Player2" ],
      "splits": [
        { "name": "Start",      "cumulativeTimeMs": 30000,   "segmentTimeMs": 30000 },
        { "name": "Village",    "cumulativeTimeMs": 150000,  "segmentTimeMs": 120000 },
        { "name": "Nether",     "cumulativeTimeMs": 320000,  "segmentTimeMs": 170000 },
        { "name": "Fortress",   "cumulativeTimeMs": 580000,  "segmentTimeMs": 260000 },
        { "name": "Stronghold", "cumulativeTimeMs": 910000,  "segmentTimeMs": 330000 },
        { "name": "End Fight",  "cumulativeTimeMs": 1152300, "segmentTimeMs": 242300 }
      ]
    }
  ],
  "goldSegments": {
    "Start": 30000,
    "Village": 110000,
    "Nether": 165000,
    "Fortress": 250000,
    "Stronghold": 320000,
    "End Fight": 242300
  }
}
```

* **Atomic Saves**: Saves are written to `.tmp` files first and then atomically swapped to prevent file corruption.
* **Manual Editing**: If you ever want to tweak or delete entries by hand, you can edit the JSON file directly in Notepad and run `/timer reload`.

---

## Building the Plugin

### Using IntelliJ IDEA
1. Open this folder in IntelliJ IDEA: **File → Open → `c:\Documentos\code\TowerSpeedPlugin`**.
2. IntelliJ will detect the Gradle project and configure JDK 25 automatically.
3. Open the Gradle tab on the right side: **Tasks → build → build** (or `jar`).
4. Find the compiled file in `build/libs/TowerSpeedPlugin-2.0.2.jar`.

### Installing on your Server
1. Copy `TowerSpeedPlugin-2.0.2.jar` into your server's `plugins/` folder.
2. Start or restart your Paper or Purpur 26.3 server.

---

## Changelog

### v2.0.2 — World Reset Cleanup & Fresh Seed Fix
* **Paper 26.1+ `dimensions/` Cleanup & No Disk Accumulation**:
  * Discovers and deletes old unloaded `speedrun_<N>` world folders across both pre-26.1 world container directories and Paper 26.1+ `dimensions/<namespace>/` folders using a non-blocking progressive-backoff background sweeper and startup cleanup.
* **Fresh Random Seeds Across Server Restarts**:
  * Drains in-flight async chunk pre-generation futures before unloading, verifies each newly created Overworld matches its requested random seed (retrying cleanly on the next slot if stale), and recycles the lowest clean `speedrun_<N>` slot once old folders are deleted.

### v2.0.1 — Build & Version Caching Fix
* **Plugin Manifest Version Caching**:
  * Registered project version and name as inputs to `processResources` in `build.gradle.kts` so `paper-plugin.yml` is reliably re-expanded when the plugin version is bumped.

### v2.0.0 — Multi-World Speedrun Manager & Lobby Separation
* **Multi-World Architecture & `/timer worldreset`**:
  * Separated the server's primary world (**Lobby**) from dedicated, rotating speedrun worlds (`speedrun_<N>`, `speedrun_<N>_nether`, `speedrun_<N>_the_end`).
  * Added `/timer worldreset` to reset the timer, evacuate players to the lobby, unload and asynchronously delete old speedrun world directories, and generate a guaranteed fresh random seed without restarting the server.
* **Throttled Spiral Spawn Chunk Pre-Generation**:
  * Speedrun worlds pre-generate spawn chunks (`pregen-chunk-radius`, `pregen-max-concurrent`) asynchronously in a spiral outward from spawn while players wait in the lobby, preventing lag spikes on run start.
  * Added start guards so `/timer start` waits until spawn chunk pre-generation reaches 100%, keeping inner spawn chunks resident in memory until the countdown completes.
* **On-Demand Nether & The End Portal Linking**:
  * `speedrun_<N>_nether` and `speedrun_<N>_the_end` are generated on-demand the first time a player or entity enters a portal, with 8:1 Nether coordinate scaling, a 5×5 obsidian End spawn platform, and End exit portal routing back to the speedrun Overworld.
* **Full Vanilla Speedrun Reset & Reconnect Tracking**:
  * `/timer start` teleports players from the lobby to the pre-generated speedrun world and resets inventory, armor, offhand, Ender Chest, health, hunger, XP, potion effects, bed/anchor respawn location, and advancements.
  * Tracks active speedrun participants via `PersistentDataContainer` (`active_world_uid`) so players reconnecting mid-run keep their inventory and position, while late joiners receive a fresh reset at spawn.
* **`/timer lobby` & `/timer join` (`rejoin`, `return`, `back`)**:
  * `/timer lobby` snapshots the player's exact speedrun state (dimension, coordinates, health/hunger, XP/effects, inventory/Ender Chest, and Hardcore spectator status) and returns them to the lobby in `SURVIVAL` mode.
  * `/timer join` returns players from the lobby to the active speedrun world, restoring their saved snapshot (or `SPECTATOR` mode if they died in Hardcore) or initializing late joiners at spawn.
* **Separated Hardcore & Difficulty (`/timer hardcore` & `/timer difficulty`)**:
  * Added `/timer hardcore <on|off|toggle>` (`hc`) and `/timer difficulty <easy|normal|hard>` (`diff`) for speedrun worlds, persisted in `config.yml` and applied immediately to loaded speedrun dimensions.
  * The **Lobby** world is never Hardcore (`setHardcore(false)`), allowing `/difficulty` and `/gamemode` to be used freely in the lobby.
  * Dying in a Hardcore speedrun world places the player in `SPECTATOR` mode at a living teammate's location while still allowing `/timer lobby` and `/timer join`.
* **Lobby BossBar & Auto-Hidden Sidebar Scoreboard**:
  * Hides the LiveSplit sidebar scoreboard while players are in the lobby and displays a top-of-screen `BossBar` showing world reset status, live chunk pre-generation progress (`0%–100%`), active speedrun mode (`[HARDCORE]` / `[NORMAL]`), or active run time.

### v1.0.0 — Initial Release
* Shared co-op LiveSplit timer (`start`, `split`, `unsplit`, `pause`, `resume`, `reset`).
* Per-player zero-flicker sidebar scoreboard with `/timer toggle`.
* Top 5 leaderboards and Gold segment tracking per category with atomic JSON storage.
