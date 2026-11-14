# Changelog

All notable changes to **TowerSpeedPlugin** are documented in this file.

## [2.0.2] - 2026-11-14

### Fixed
- **Paper 26.1+ `dimensions/` World Storage & Deletion**:
  - Updated `WorldResetManager` to resolve actual world folders via `world.getWorldFolder()` and `discoverWorldSearchRoots()`, discovering both pre-26.1 root folders and Paper 26.1+ `dimensions/<namespace>/` paths while guarding deletion strictly to unloaded leaf `speedrun*` directories.
- **Unreliable Unloading & Accumulating Old World Folders**:
  - Drained in-flight `getChunkAtAsync` spawn pre-generation futures, prepared dimensions for unload (`setAutoSave(false)`, `setKeepSpawnInMemory(false)`, clearing chunk tickets, force-loaded chunks, and Ender Pearls), and retried `Bukkit.unloadWorld(..., false)` cleanly without leaking worlds in RAM.
  - Replaced one-shot blocking deletion retries with a non-blocking progressive-backoff background `pendingDeletions` sweeper plus startup (`cleanupStaleWorldDirectories()`) and per-reset (`sweepStrayUnloadedDirectories()`) sweeps.
- **Server Restart Stale Seed & Slot Number Reuse**:
  - Replaced monotonic `worldIndex` with clean slot selection (`findNextCleanSlotIndex()`) that checks loaded worlds, all search roots, `pendingDeletions`, and `blockedSlotIndices`, recycling lower slots cleanly (`speedrun_1`, `speedrun_2`, ...) once deleted.
  - Added post-creation `world.getSeed() == requestedSeed` verification with automatic unload and next-slot fallback if Paper returns a stale seed, and cleared speedrun bed/anchor respawn locations in `evacuatePlayerToLobby()`.

## [2.0.1] - 2026-11-12

### Fixed
- **Plugin Manifest Version Caching**:
  - Registered project version and name as inputs to `processResources` in `build.gradle.kts`. This prevents Gradle from treating resource expansion as up-to-date across version bumps, ensuring `paper-plugin.yml` always reflects the correct plugin version in the server console.

## [2.0.0] - 2026-11-12

### Added
- **Multi-World Architecture & `/timer worldreset`**:
  - Separated the server's primary world (**Lobby**) from dedicated, rotating speedrun worlds (`speedrun_<N>`, `speedrun_<N>_nether`, `speedrun_<N>_the_end`).
  - Added `/timer worldreset` to reset the timer, evacuate all players to the lobby, unload and asynchronously delete old speedrun world folders with retry handling, and generate a guaranteed fresh random seed without restarting the server.
  - Added automatic startup cleanup (`initializeOnStartup()`) to sweep any leftover unloaded `speedrun*` world folders.
- **Throttled Spiral Spawn Chunk Pre-Generation**:
  - Background spiral chunk pre-generation (`pregen-chunk-radius`, `pregen-max-concurrent`) around the speedrun Overworld spawn while players wait in the lobby, preventing server TPS drops.
  - Added plugin chunk tickets for inner spawn chunks until the run countdown completes and a start guard that blocks `/timer start` until pre-generation reaches `100%`.
- **On-Demand Nether & The End Dimensions & Portal Linking**:
  - `speedrun_<N>_nether` and `speedrun_<N>_the_end` are created on-demand when a player or entity enters a portal, inheriting the Overworld's seed, difficulty, and Hardcore setting.
  - Custom `PlayerPortalEvent` and `EntityPortalEvent` handling for 8:1 Overworld ↔ Nether coordinate scaling, 5×5 obsidian End platform generation, and End exit fountain routing back to the speedrun Overworld or player bed/anchor.
- **Full Vanilla Speedrun Player Reset & Reconnect Tracking**:
  - Automatically resets inventory, armor, offhand, cursor item, Ender Chest, health, food/saturation, XP, active potion effects, respawn locations, and advancements when starting a run on a fresh world or joining an active run for the first time.
  - Tracks participants per speedrun world via `PersistentDataContainer` (`active_world_uid`) so mid-run disconnects/reconnects preserve inventory and location.
- **Lobby & Rejoin Commands (`/timer lobby` & `/timer join`)**:
  - `/timer lobby` snapshots a participant's exact speedrun state (`SavedSpeedrunState`: dimension, coordinates, health/hunger, XP/effects, inventory/Ender Chest, and Hardcore spectator status) before teleporting them to the lobby in `SURVIVAL` mode.
  - `/timer join` (aliases: `rejoin`, `return`, `back`) returns players from the lobby to the active speedrun world, restoring their saved state (or `SPECTATOR` mode if they died in Hardcore) or resetting late joiners at spawn.
- **Independent Speedrun Hardcore & Difficulty (`/timer hardcore` & `/timer difficulty`)**:
  - Added `/timer hardcore <on|off|toggle>` (`hc`) and `/timer difficulty <easy|normal|hard>` (`diff`) for speedrun worlds, persisted in `config.yml` and applied immediately to loaded speedrun dimensions.
  - The **Lobby** world is never Hardcore (`setHardcore(false)`), allowing `/difficulty` and `/gamemode` to be changed freely in the lobby.
  - Hardcore deaths in speedrun worlds place the player into `SPECTATOR` mode at a living teammate's location while allowing them to return to the lobby via `/timer lobby` and rejoin as a spectator via `/timer join`.
- **Lobby BossBar & Auto-Hidden Sidebar Scoreboard**:
  - Automatically hides the LiveSplit sidebar scoreboard while players are in the lobby and displays a top-of-screen Adventure `BossBar` indicating world reset status, live chunk pre-generation percentage, speedrun mode (`[HARDCORE]` / `[EASY]` / `[NORMAL]` / `[HARD]`), or active run time (`(/timer join)`).

---

## [1.0.0] - Initial Release

### Added
- Shared server-wide LiveSplit-style co-op timer (`/timer start`, `split`, `unsplit`, `pause`, `resume`, `reset`).
- Per-player zero-flicker sidebar scoreboard with `/timer toggle`.
- Top 5 leaderboards and Gold segment tracking per category (`/timer records`, `view`, `delete`, `clear`) with atomic JSON persistence.
