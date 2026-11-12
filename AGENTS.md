# AGENTS.md — TowerSpeedPlugin Context & Architecture Guide

## Project Overview
**TowerSpeedPlugin** is a collaborative, shared **LiveSplit-style Co-op Speedrun Timer & Multi-World Manager** built for **Paper / Purpur (1.21.3+ / 26.3)** using **Java 25**.

It provides a synchronized server-wide timer, LiveSplit sidebar scoreboard, Top 5 leaderboards with Gold segment tracking, and an in-game multi-world speedrun reset system (`/timer worldreset`) that requires no server restarts.

---

## Tech Stack & Build
- **Java Version**: Java 25 (`toolchain` and `release` set to `25` in `build.gradle.kts`)
- **Server API**: `io.papermc.paper:paper-api:1.21.3-R0.1-SNAPSHOT`
- **Text / UI**: Kyori Adventure (`MiniMessage`, `Title`, `BossBar`, `Component`)
- **Command API**: Paper Brigadier `BasicCommand` registered via `LifecycleEvents.COMMANDS`
- **Build Command**: `.\gradlew.bat build` → outputs `build/libs/TowerSpeedPlugin-1.0.0.jar`

---

## Package & File Structure

```text
src/main/
├── java/com/towerspeed/
│   ├── TowerSpeedPlugin.java              # Main plugin entrypoint, lifecycle & listener registration
│   ├── command/
│   │   ├── TimerCommand.java              # /timer (/t) subcommands & tab completion
│   │   ├── SplitCommand.java              # /split shortcut command
│   │   └── UnsplitCommand.java            # /unsplit (/undo) shortcut command
│   ├── scoreboard/
│   │   └── LiveSplitScoreboard.java       # Per-player zero-flicker sidebar scoreboard & Lobby BossBar
│   ├── storage/
│   │   └── RecordStorage.java             # Atomic JSON persistence in plugins/TowerSpeedPlugin/records/
│   ├── timer/
│   │   ├── TimerManager.java              # Shared timer state machine, countdowns, splits & celebration
│   │   ├── TimerState.java                # IDLE, COUNTDOWN, RUNNING, PAUSED, FINISHED
│   │   ├── Category.java                  # Category definition (id, displayName, splitNames)
│   │   ├── CategoryRecords.java           # Top 5 runs + Gold segment map per category
│   │   ├── RunRecord.java                 # Completed run record (time, date, players, splits)
│   │   └── Split.java                     # Individual split record (cumulativeTimeMs, segmentTimeMs)
│   ├── util/
│   │   └── TimeFormatter.java             # Formats ms into MM:SS.d / HH:MM:SS.d and signed deltas
│   └── world/
│       └── WorldResetManager.java         # Multi-world speedrun reset, throttled pre-gen, portals & Hardcore
└── resources/
    ├── config.yml                         # Categories, timer settings, and chunk pre-gen settings
    └── paper-plugin.yml                   # Paper plugin manifest & permissions
```

---

## Recent Feature Implementation: Multi-World `/timer worldreset`, Pre-Gen, Lobby BossBar & Hardcore Support

### 1. Multi-World Architecture & Rotating Directories (`WorldResetManager.java`)
- **Lobby vs. Speedrun Worlds**:
  - The server's primary world (`Bukkit.getWorlds().getFirst()`, typically `world`) acts as the **Lobby**.
  - Runs take place in dedicated secondary worlds: `speedrun_<N>` (Overworld), `speedrun_<N>_nether` (Nether), and `speedrun_<N>_the_end` (The End).
- **Why World Names Rotate (`speedrun_1`, `speedrun_2`, ...)**:
  - On Windows, Paper's asynchronous `Moonrise` chunk I/O system holds file locks on `level.dat` and `.mca` region files briefly after `Bukkit.unloadWorld(..., false)` returns `true`.
  - If the same folder name (`speedrun`) is reused immediately, `level.dat` may still be locked on disk, causing `Bukkit.createWorld()` to load the old `level.dat` (and reuse the old seed) instead of generating a new seed.
  - Incrementing `worldIndex` (`speedrun_1`, `speedrun_2`, ...) guarantees the target folder never exists on disk when `Bukkit.createWorld()` runs, forcing a 100% fresh `level.dat` and random seed every time.
  - The unloaded `speedrun_<N-1>` directories are deleted asynchronously 40 ticks (2 seconds) later with retries, and any leftover unloaded `speedrun*` folders are swept on server startup (`initializeOnStartup()`).

### 2. Throttled Spiral Spawn Chunk Pre-Generation (`WorldResetManager.java`)
- **Zero-Lag Background Pre-Gen**:
  - All speedrun dimensions are created with `.keepSpawnLoaded(TriState.FALSE)` so `Bukkit.createWorld()` does not freeze the main thread synchronously generating spawn chunks.
  - Both on server startup and after `/timer worldreset`, `startSpawnPreGeneration(World, boolean)` builds a list of chunk offsets within `pregen-chunk-radius` (default `8` = `17×17` = `289` chunks) sorted radially outward (`dx*dx + dz*dz`) from the new world's spawn chunk.
  - A repeating scheduler task dispatches at most `pregen-max-concurrent` (default `2`) asynchronous `world.getChunkAtAsync(cx, cz, true)` requests at a time so players in the lobby experience zero TPS drop.
- **Memory Retention & Start Guard**:
  - Chunks within the inner 5-chunk radius around spawn receive a plugin chunk ticket (`addPluginChunkTicket`) upon completion so they remain resident in RAM while players wait in the lobby.
  - When `/timer start` finishes its countdown (`markRunBegan()`), those temporary plugin chunk tickets are removed so normal player chunk management takes over.
  - Running `/timer start` while `pregenInProgress` is `true` is blocked with a message showing the current completion percentage.

### 3. On-Demand Nether/End & Portal Linking (`WorldResetManager.java`)
- `speedrun_<N>_nether` and `speedrun_<N>_the_end` are **not** generated during `/timer worldreset`. They are created on-demand in `onPlayerPortal` / `onEntityPortal` the first time a player or entity enters a Nether or End portal, inheriting the Overworld's seed, difficulty, and Hardcore setting.
- Handles `PlayerPortalEvent` and `EntityPortalEvent`:
  - **Overworld ↔ Nether**: 8:1 coordinate scaling with portal search/creation radii.
  - **Overworld → The End**: Generates the standard vanilla 5×5 obsidian platform at `(98..102, 48, -2..2)`, clears air at `y = 49..51`, and teleports to `(100.5, 49.0, 0.5)`.
  - **The End → Overworld**: Routes players jumping into the End exit fountain back to their bed/anchor in the `speedrun` world or the `speedrun` Overworld spawn (instead of the default lobby world).

### 4. Starting Runs, Player Resets & Join/Leave Synchronization
- **Full Vanilla Speedrun Reset (`resetPlayerForRun`)**:
  - When `/timer start` is executed after a world reset (or when a new player joins an active run), players are teleported to `speedrun_<N>` spawn and reset:
    - Clears inventory, armor, offhand, cursor item, and Ender Chest.
    - Restores full health (`Attribute.MAX_HEALTH`), food (`20`), saturation (`5.0f`), air, and clears fire/freeze/fall distance/velocity.
    - Clears XP and all active potion effects.
    - Clears bed/anchor respawn location and revokes all advancements (`Bukkit.advancementIterator()`).
    - Sets `GameMode.SURVIVAL`.
- **Reconnect vs. New Run Tracking (`active_world_uid` PDC)**:
  - Stores the active `speedrun_<N>` Overworld's `UUID` in each participant's `PersistentDataContainer` (`NamespacedKey(plugin, "active_world_uid")`).
  - If a player disconnects and reconnects during the **same** active run, their `active_world_uid` matches `getActiveOverworld().getUID()`, so they keep their inventory, advancements, and position.
  - If a player joins late or logs back in after `/timer worldreset` started a new world, their `active_world_uid` does not match, so they automatically receive a full reset and teleport to the active run's spawn (or to the lobby if the timer is `IDLE`).
- **Safety Edge Cases**:
  - Revives dead players (`player.spigot().respawn()`), wakes sleeping players, dismounts vehicles, and removes in-flight `EnderPearl` projectiles before teleporting or unloading worlds.

### 5. Hardcore Mode & `/timer lobby`
- `createNextSpeedrunOverworld()`, `getOrCreateNether()`, and `getOrCreateTheEnd()` inherit `getLobbyWorld().isHardcore()`.
- When a player dies in a Hardcore speedrun world (`onPlayerDeath`), they are notified that they can click **Spectate World** to watch teammates or run `/timer lobby` to return to the lobby.
- `onPlayerRespawn` spawns Hardcore spectators directly at a living teammate's location in the speedrun worlds (falling back to the Overworld spawn).
- `/timer lobby` (and `evacuatePlayerToLobby`) teleports the player to the lobby spawn and restores `GameMode.SURVIVAL`.

### 6. Lobby BossBar & Auto-Hidden Split Scoreboard (`LiveSplitScoreboard.java`)
- Players in the **Lobby** have their sidebar scoreboard hidden (`objective.setDisplaySlot(null)`) and see a top-of-screen Adventure `BossBar`:
  - `Lobby - Resetting World...` (`RED`, `0%`)
  - `Lobby - Generating World (XX%)` (`YELLOW`, `0%–100%` live chunk pre-gen progress)
  - `Lobby - Ready (/timer start)` (`GREEN`, `100%`)
  - `Lobby - Run in Progress (MM:SS.d)` / `Lobby - Run Paused (MM:SS.d)` (`BLUE` / `YELLOW`, `100%`)
- As soon as a player enters a `speedrun_*` world, the BossBar hides and the LiveSplit sidebar scoreboard is displayed (`DisplaySlot.SIDEBAR`).

---

## Commands & Permissions Summary

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/timer start [instant]` | Teleports players from lobby to pre-generated `speedrun` world, resets player state, and starts 3s countdown | `towerspeed.timer` (default: `true`) |
| `/timer split [name]` / `/split` | Records next split (or custom split) | `towerspeed.timer` |
| `/timer unsplit` / `/unsplit` | Undoes last split | `towerspeed.timer` |
| `/timer pause` / `/timer resume` | Pauses or resumes the active timer | `towerspeed.timer` |
| `/timer reset` | Resets the timer to `00:00.0` in the current world | `towerspeed.timer` |
| `/timer lobby` | Teleports the caller back to the lobby world and restores `SURVIVAL` mode | `towerspeed.timer` |
| `/timer toggle` | Toggles sidebar scoreboard visibility for the caller | All players |
| `/timer category <name>` | Switches active speedrun category | `towerspeed.timer` |
| `/timer records [view\|delete\|clear]` | Views or manages Top 5 leaderboard records | `towerspeed.timer` (`clear` requires `towerspeed.admin`) |
| `/timer worldreset` | Resets timer, sends everyone to lobby, unloads/deletes old speedrun worlds, and pre-generates a new seed | `towerspeed.admin` (default: `op`) |
| `/timer reload` | Reloads `config.yml` and record JSON files | `towerspeed.admin` (default: `op`) |
