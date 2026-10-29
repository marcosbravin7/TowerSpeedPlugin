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
* **Audio-Visual Cues**:
  * 3-second countdown with ticking sounds and large title numbers before starting.
  * Distinct sound effects for splits, gold splits, pauses, resumes, and record finishes.
  * Real-time Action Bar split notifications (`[Split 2] Floor 20 - 05:22.1 (-00:14.2)`).

---

## Commands & Permissions

| Command | Aliases | Description | Permission |
| :--- | :--- | :--- | :--- |
| `/timer start [instant]` | | Starts the shared timer (with 3s countdown or instantly) | `towerspeed.timer` (default: all) |
| `/split [name]` | `/timer split` | Records the next split (or custom-named split) | `towerspeed.timer` (default: all) |
| `/timer unsplit` | `/unsplit`, `/undo` | Undoes the last recorded split and restores segment | `towerspeed.timer` (default: all) |
| `/timer pause` | | Pauses the running timer | `towerspeed.timer` (default: all) |
| `/timer resume` | | Resumes the paused timer | `towerspeed.timer` (default: all) |
| `/timer reset` | | Resets the timer back to 00:00.0 | `towerspeed.timer` (default: all) |
| `/timer toggle` | | Toggles the sidebar scoreboard on/off for yourself | None (all players) |
| `/timer category <name>` | `/timer cat` | Switches active category (e.g. `tower_climb`, `any_percent`) | `towerspeed.timer` (default: all) |
| `/timer records [cat]` | `/timer pb`, `/timer leaderboard` | Displays the Top 5 leaderboard with times and dates | None (all players) |
| `/timer records view <#>` | | View detailed split times for a specific ranked run | None (all players) |
| `/timer records delete <#>` | `/timer records remove` | Deletes an accidental record and recalculates PB | `towerspeed.timer` (default: all) |
| `/timer records clear [cat]` | | Clears all leaderboard runs and gold splits for a category | `towerspeed.admin` (default: OP) |
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
4. Find the compiled file in `build/libs/TowerSpeedPlugin-1.0.0.jar`.

### Installing on your Server
1. Copy `TowerSpeedPlugin-1.0.0.jar` into your server's `plugins/` folder.
2. Start or restart your Paper or Purpur 26.3 server.
