# TowerSpeedPlugin

A collaborative, shared **LiveSplit-style Speedrun Timer** plugin built for **Paper / Purpur 26.3** servers using **Java 25**.

Designed specifically for **co-op runs** (such as tower climbing, parkour challenges, or speedruns) where all players play together and share a single synchronised timer.

---

## Features

* **LiveSplit Sidebar Scoreboard**:
  * Clean, zero-flicker display on the Minecraft sidebar.
  * Shows previous splits with time and delta comparison vs the Server Record (`-0:12.4` in green or `+0:03.2` in red).
  * Highlights the current active split segment in real time (`▶ Split Name 02:14.2`).
  * Displays upcoming split targets.
  * Live total elapsed time, Server Record (PB), and overall delta.
* **Shared Co-op Experience**:
  * One timer for the entire server/team. When any player starts, pauses, resets, or hits a split, it synchronizes across all players.
  * Individual players can toggle the sidebar scoreboard on/off for themselves (`/timer toggle`) without affecting teammates.
* **Global Server Records & Gold Splits**:
  * Automatically detects and saves the fastest overall run per category to `records.json`.
  * Tracks **Gold Splits** (the fastest time the team has ever completed an individual segment).
  * Plays celebration sound cues, titles, and launches fireworks when setting a new Server Record (PB).
* **Audio-Visual Cues**:
  * 3-second countdown with ticking sounds and large title numbers before starting.
  * Distinct sound effects for splits, gold splits, pauses, resets, and record finishes.
  * Action Bar split notifications (`[Split 2] Floor 20 - 05:22.1 (-00:14.2)`).

---

## Commands & Permissions

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/timer start [instant]` | Starts the shared timer (with 3s countdown or instantly) | `towerspeed.timer` (default: all) |
| `/split [name]` or `/timer split` | Records the next split (with optional custom name) | `towerspeed.timer` (default: all) |
| `/timer pause` | Pauses the running timer | `towerspeed.timer` (default: all) |
| `/timer resume` | Resumes the paused timer | `towerspeed.timer` (default: all) |
| `/timer reset` | Resets the timer back to 00:00.0 | `towerspeed.timer` (default: all) |
| `/timer toggle` | Toggles the sidebar scoreboard on/off for yourself | None (all players) |
| `/timer category <name>` | Switches active category (e.g. `tower_climb`, `any_percent`) | `towerspeed.timer` |
| `/timer records` | Displays the current server PB, team members, and gold splits | None (all players) |
| `/timer reload` | Reloads `config.yml` | `towerspeed.admin` (default: OP) |

---

## Configuration (`config.yml`)

You can define multiple categories with custom split checkpoints:

```yaml
active-category: "tower_climb"
update-interval-ticks: 2
countdown-seconds: 3
play-sounds: true
celebration-fireworks: true

categories:
  tower_climb:
    display-name: "<gold><bold>Tower Climb</bold></gold>"
    splits:
      - "Floor 10"
      - "Floor 20"
      - "Floor 30"
      - "Floor 40"
      - "Summit"

  any_percent:
    display-name: "<aqua><bold>Any% Co-op</bold></aqua>"
    splits:
      - "Enter Nether"
      - "Fortress"
      - "Bastion"
      - "Stronghold"
      - "End Fight"
```

---

## Building the Plugin

### Using IntelliJ IDEA
1. Open this folder in IntelliJ IDEA: **File → Open → `c:\Documentos\code\TowerSpeedPlugin`**.
2. IntelliJ will detect the Gradle project and import dependencies automatically using **JDK 25**.
3. Open the Gradle tool window on the right side: **Tasks → build → build** (or **jar**).
4. The output file will be in `build/libs/TowerSpeedPlugin-1.0.0.jar`.

### Installing on your Server
1. Copy `TowerSpeedPlugin-1.0.0.jar` into your server's `plugins/` directory.
2. Start or restart your Paper or Purpur 26.3 server.
