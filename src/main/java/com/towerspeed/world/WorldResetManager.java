package com.towerspeed.world;

import com.towerspeed.TowerSpeedPlugin;
import com.towerspeed.timer.TimerManager;
import com.towerspeed.timer.TimerState;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.TriState;
import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Manages the speedrun multi-world lifecycle (Overworld, on-demand Nether & The End),
 * throttled background spawn chunk pre-generation, safe world unloading/deletion,
 * player state resets, Hardcore death/spectator handling, portal linking, and join/leave synchronization.
 */
public class WorldResetManager implements Listener {

    public static final String SPEEDRUN_PREFIX = "speedrun";

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int INNER_TICKET_RADIUS_SQ = 25; // Keep 5-chunk radius around spawn in RAM until run starts
    private static final int MAX_DRAIN_ATTEMPTS = 30; // 30 * 2 ticks = 3s max wait for in-flight chunk futures
    private static final int MAX_UNLOAD_ATTEMPTS = 20; // ~8.5s max retries before failing cleanly instead of leaking RAM
    private static final Pattern SPEEDRUN_DIR_PATTERN =
            Pattern.compile("^speedrun(?:_[0-9]+)?(?:_nether|_the_end)?$");

    private final TowerSpeedPlugin plugin;
    private final NamespacedKey activeWorldUidKey;
    private final NamespacedKey hardcoreDeadWorldUidKey;
    private final Map<UUID, SavedSpeedrunState> savedRunStates = new HashMap<>();

    // Each reset uses a clean slot (e.g. speedrun_1, speedrun_2) that does not exist on disk or in pendingDeletions,
    // preventing Paper from loading stale level.dat / world_gen_settings or hitting Windows Moonrise file locks.
    private String activeOverworldName = "speedrun_1";
    private String activeNetherName = "speedrun_1_nether";
    private String activeEndName = "speedrun_1_the_end";
    private final Set<Integer> blockedSlotIndices = new HashSet<>();

    private boolean resetInProgress = false;
    private boolean worldFreshlyPrepared = false;

    // Independent speedrun world settings (decoupled from the non-hardcore Peaceful lobby)
    private boolean speedrunHardcore = false;
    private Difficulty speedrunDifficulty = Difficulty.NORMAL;

    // Throttled chunk pre-generation state
    private boolean pregenInProgress = false;
    private int pregenTotalChunks = 0;
    private int pregenCompletedChunks = 0;
    private int pregenInFlight = 0;
    private long pregenSessionId = 0;
    private @Nullable BukkitTask pregenTask = null;
    private final AtomicInteger activeChunkRequests = new AtomicInteger(0);

    // Unload & background directory deletion tracking (supports both pre-26.1 root folders and Paper 26.1+ dimensions/)
    private final Set<Path> observedSpeedrunParents = ConcurrentHashMap.newKeySet();
    private final Set<Path> pendingDeletions = ConcurrentHashMap.newKeySet();
    private final Map<Path, Integer> deletionAttempts = new ConcurrentHashMap<>();
    private final AtomicBoolean deletionSweepRunning = new AtomicBoolean(false);
    private @Nullable BukkitTask pendingResetTask = null;
    private @Nullable BukkitTask deletionSweepTask = null;

    private record ChunkOffset(int dx, int dz, int distSq) {}

    private record SavedSpeedrunState(
            UUID overworldUid,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            GameMode gameMode,
            boolean hardcoreDead,
            double health,
            double absorption,
            int foodLevel,
            float saturation,
            float exhaustion,
            int fireTicks,
            int freezeTicks,
            int remainingAir,
            float fallDistance,
            int expLevel,
            float expProgress,
            int totalExp,
            List<PotionEffect> potionEffects,
            ItemStack[] inventoryContents,
            ItemStack[] armorContents,
            @Nullable ItemStack offHandItem,
            ItemStack[] enderChestContents
    ) {}

    public WorldResetManager(TowerSpeedPlugin plugin) {
        this.plugin = plugin;
        this.activeWorldUidKey = new NamespacedKey(plugin, "active_world_uid");
        this.hardcoreDeadWorldUidKey = new NamespacedKey(plugin, "hardcore_dead_world_uid");
        loadSettingsFromConfig();
    }

    public void loadSettingsFromConfig() {
        this.speedrunHardcore = plugin.getConfig().getBoolean("speedrun-hardcore", false);
        String diffStr = plugin.getConfig().getString("speedrun-difficulty", "NORMAL").toUpperCase();
        try {
            Difficulty parsed = Difficulty.valueOf(diffStr);
            this.speedrunDifficulty = (parsed == Difficulty.PEACEFUL) ? Difficulty.NORMAL : parsed;
        } catch (IllegalArgumentException e) {
            this.speedrunDifficulty = Difficulty.NORMAL;
        }
        applySettingsToLoadedSpeedrunWorlds();
        configureLobbyWorld();
    }

    public void configureLobbyWorld() {
        List<World> worlds = Bukkit.getWorlds();
        if (!worlds.isEmpty()) {
            World lobby = worlds.getFirst();
            lobby.setHardcore(false);
        }
    }

    public boolean isSpeedrunHardcore() {
        return speedrunHardcore;
    }

    public Difficulty getSpeedrunDifficulty() {
        return speedrunDifficulty;
    }

    public Difficulty getEffectiveSpeedrunDifficulty() {
        return speedrunHardcore ? Difficulty.HARD : speedrunDifficulty;
    }

    public void setSpeedrunHardcore(boolean enabled, CommandSender sender) {
        this.speedrunHardcore = enabled;
        if (enabled) {
            this.speedrunDifficulty = Difficulty.HARD;
            plugin.getConfig().set("speedrun-difficulty", Difficulty.HARD.name());
        }
        plugin.getConfig().set("speedrun-hardcore", enabled);
        plugin.saveConfig();

        applySettingsToLoadedSpeedrunWorlds();
        configureLobbyWorld();

        String statusTag = enabled ? "<red><bold>HARDCORE ON</bold></red> <gray>(Difficulty: HARD)</gray>" : "<green><bold>HARDCORE OFF</bold></green> <gray>(Difficulty: " + speedrunDifficulty.name() + ")</gray>";
        Bukkit.broadcast(MM.deserialize("<gold>[Timer]</gold> <yellow>Speedrun world mode set to: </yellow>" + statusTag + " <gray>(by " + sender.getName() + ")</gray>"));
    }

    public void setSpeedrunDifficulty(Difficulty difficulty, CommandSender sender) {
        this.speedrunDifficulty = difficulty;
        plugin.getConfig().set("speedrun-difficulty", difficulty.name());
        if (speedrunHardcore && difficulty != Difficulty.HARD) {
            this.speedrunHardcore = false;
            plugin.getConfig().set("speedrun-hardcore", false);
        }
        plugin.saveConfig();

        applySettingsToLoadedSpeedrunWorlds();
        configureLobbyWorld();

        Bukkit.broadcast(MM.deserialize("<gold>[Timer]</gold> <yellow>Speedrun world difficulty set to: <white><bold>"
                + difficulty.name() + "</bold></white></yellow> <gray>(by " + sender.getName() + ")</gray>"));
    }

    private void applySettingsToLoadedSpeedrunWorlds() {
        Difficulty effectiveDiff = getEffectiveSpeedrunDifficulty();
        for (String worldName : List.of(activeOverworldName, activeNetherName, activeEndName)) {
            World w = Bukkit.getWorld(worldName);
            if (w != null) {
                w.setHardcore(speedrunHardcore);
                w.setDifficulty(effectiveDiff);
            }
        }
    }

    /**
     * Called on plugin startup to clean up any leftover speedrun world folders
     * (supporting both pre-26.1 root directories and Paper 26.1+ dimensions/ paths)
     * and begin background pre-generation of the initial speedrun world.
     */
    public void initializeOnStartup() {
        configureLobbyWorld();

        // Handle plugin /reload gracefully: evacuate players and unload any leftover loaded speedrun worlds
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isSpeedrunWorld(player.getWorld())) {
                evacuatePlayerToLobby(player);
            }
        }
        for (World world : collectLoadedSpeedrunWorlds()) {
            Path folder = world.getWorldFolder().toPath().toAbsolutePath().normalize();
            recordObservedParent(folder);
            prepareWorldForUnload(world);
            if (Bukkit.unloadWorld(world, false)) {
                enqueueDirectoryForDeletion(folder);
            } else {
                plugin.getLogger().warning("Could not unload leftover speedrun world '" + world.getName() + "' during startup.");
            }
        }

        cleanupStaleWorldDirectories();

        // Prepare and pre-generate the initial speedrun world in the background once the server finishes booting
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!plugin.isEnabled() || resetInProgress || getActiveOverworld() != null) {
                return;
            }
            World initialWorld = createNextSpeedrunOverworld();
            if (initialWorld != null) {
                startSpawnPreGeneration(initialWorld, false);
            }
        }, 1L);
    }

    private void cleanupStaleWorldDirectories() {
        int deletedCount = 0;
        for (Path root : discoverWorldSearchRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> children = Files.list(root)) {
                List<Path> candidates = children.filter(Files::isDirectory).toList();
                for (Path dir : candidates) {
                    if (isSafeSpeedrunDirectoryToDelete(dir)) {
                        String failure = deleteDirectoryRecursively(dir);
                        if (failure == null && !Files.exists(dir)) {
                            deletedCount++;
                        } else if (Files.exists(dir)) {
                            enqueueDirectoryForDeletion(dir);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        if (deletedCount > 0) {
            plugin.getLogger().info("Cleaned up " + deletedCount + " leftover speedrun world folder(s) on startup.");
        }
        if (!pendingDeletions.isEmpty()) {
            scheduleDeletionSweep(40L);
        }
    }

    public boolean isResetInProgress() {
        return resetInProgress;
    }

    public boolean isPreGenInProgress() {
        return pregenInProgress;
    }

    public float getPreGenProgress() {
        if (pregenTotalChunks <= 0) return 0.0f;
        return Math.clamp((float) pregenCompletedChunks / (float) pregenTotalChunks, 0.0f, 1.0f);
    }

    public int getPreGenPercent() {
        return Math.round(getPreGenProgress() * 100.0f);
    }

    public @Nullable World getActiveOverworld() {
        return Bukkit.getWorld(activeOverworldName);
    }

    public boolean isSpeedrunWorld(@Nullable World world) {
        if (world == null) return false;
        World lobby = getLobbyWorldSafe();
        if (lobby != null && world.equals(lobby)) {
            return false;
        }
        String name = world.getName();
        return name.equals(activeOverworldName)
                || name.equals(activeNetherName)
                || name.equals(activeEndName)
                || SPEEDRUN_DIR_PATTERN.matcher(name).matches();
    }

    public World getLobbyWorld() {
        return Bukkit.getWorlds().getFirst();
    }

    private @Nullable World getLobbyWorldSafe() {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.getFirst();
    }

    public Location getLobbySpawn() {
        return getLobbyWorld().getSpawnLocation();
    }

    /**
     * Executes /timer worldreset:
     * 1. Cancels any active pre-generation and waits for in-flight async chunk futures to drain.
     * 2. Resets the timer and evacuates all players to the lobby in SURVIVAL mode.
     * 3. Unloads all active/stale speedrun dimensions cleanly without leaking worlds in RAM.
     * 4. Immediately generates a brand-new speedrun_<N> Overworld with a verified fresh random seed,
     *    while reliably deleting the old world directories in the background.
     */
    public void triggerWorldReset(CommandSender initiator, TimerManager timerManager) {
        if (resetInProgress) {
            initiator.sendMessage(MM.deserialize("<red>[Timer] A world reset is already in progress! Please wait...</red>"));
            return;
        }

        cancelPreGeneration();
        resetInProgress = true;
        worldFreshlyPrepared = false;
        savedRunStates.clear();

        // Reset the timer if it isn't already idle
        if (timerManager.getState() != TimerState.IDLE) {
            timerManager.reset(initiator);
        }

        Bukkit.broadcast(MM.deserialize("<gold><bold>[Timer]</bold> <yellow>" + initiator.getName()
                + "</yellow> initiated a world reset! Returning to lobby...</gold>"));

        Title resettingTitle = Title.title(
                MM.deserialize("<gold><bold>RESETTING WORLD...</bold></gold>"),
                MM.deserialize("<gray>Preparing new seed in the background</gray>"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(300))
        );

        // Evacuate all online players to the lobby immediately
        for (Player player : Bukkit.getOnlinePlayers()) {
            evacuatePlayerToLobby(player);
            player.showTitle(resettingTitle);
        }

        LinkedHashSet<String> oldWorldNames = new LinkedHashSet<>();
        LinkedHashSet<Path> capturedOldDirs = new LinkedHashSet<>();

        for (World speedrunWorld : collectLoadedSpeedrunWorlds()) {
            oldWorldNames.add(speedrunWorld.getName());
            Path folder = speedrunWorld.getWorldFolder().toPath().toAbsolutePath().normalize();
            recordObservedParent(folder);
            capturedOldDirs.add(folder);
            prepareWorldForUnload(speedrunWorld);
        }

        for (String name : List.of(activeEndName, activeNetherName, activeOverworldName)) {
            oldWorldNames.add(name);
            for (Path root : discoverWorldSearchRoots()) {
                Path candidate = root.resolve(name).toAbsolutePath().normalize();
                if (Files.exists(candidate)) {
                    capturedOldDirs.add(candidate);
                }
            }
        }

        List<String> orderedOldNames = new ArrayList<>(oldWorldNames);

        // Wait 2 ticks so Paper processes player teleports and drains any in-flight chunk generation futures
        pendingResetTask = Bukkit.getScheduler().runTaskLater(plugin,
                () -> waitForDrainAndUnload(orderedOldNames, capturedOldDirs, 0), 2L);
    }

    private void waitForDrainAndUnload(List<String> oldWorldNames, Set<Path> capturedOldDirs, int drainAttempt) {
        if (!plugin.isEnabled()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead() || isSpeedrunWorld(player.getWorld())) {
                evacuatePlayerToLobby(player);
            }
        }

        if (activeChunkRequests.get() > 0 && drainAttempt < MAX_DRAIN_ATTEMPTS) {
            pendingResetTask = Bukkit.getScheduler().runTaskLater(plugin,
                    () -> waitForDrainAndUnload(oldWorldNames, capturedOldDirs, drainAttempt + 1), 2L);
            return;
        }

        if (activeChunkRequests.get() > 0) {
            plugin.getLogger().warning("Proceeding with world unload while " + activeChunkRequests.get()
                    + " async chunk request(s) are still finishing.");
        }

        unloadOldAndCreateNewWorld(oldWorldNames, capturedOldDirs, 0);
    }

    private void unloadOldAndCreateNewWorld(List<String> oldWorldNames, Set<Path> capturedOldDirs, int attempt) {
        if (!plugin.isEnabled()) {
            return;
        }

        // Ensure no player snuck into or remained in the speedrun worlds (including dead players on the respawn screen)
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead() || isSpeedrunWorld(player.getWorld())) {
                evacuatePlayerToLobby(player);
            }
        }

        // Gather all loaded worlds matching oldWorldNames plus any stray speedrun worlds (End -> Nether -> Overworld)
        LinkedHashSet<World> worldsToUnload = new LinkedHashSet<>(collectLoadedSpeedrunWorlds());
        for (String worldName : oldWorldNames) {
            World w = Bukkit.getWorld(worldName);
            if (w != null) {
                worldsToUnload.add(w);
            }
        }

        boolean allUnloaded = true;
        StringBuilder failedDetails = new StringBuilder();
        for (World world : worldsToUnload) {
            Path folder = world.getWorldFolder().toPath().toAbsolutePath().normalize();
            recordObservedParent(folder);
            capturedOldDirs.add(folder);
            prepareWorldForUnload(world);

            boolean unloaded = Bukkit.unloadWorld(world, false);
            if (unloaded) {
                enqueueDirectoryForDeletion(folder);
            } else {
                allUnloaded = false;
                if (attempt == 0 || attempt == 5 || attempt == MAX_UNLOAD_ATTEMPTS) {
                    if (!failedDetails.isEmpty()) failedDetails.append(", ");
                    failedDetails.append(world.getName())
                            .append("(players=").append(world.getPlayers().size())
                            .append(", chunks=").append(world.getLoadedChunks().length)
                            .append(")");
                }
            }
        }

        if (!allUnloaded) {
            if (attempt < MAX_UNLOAD_ATTEMPTS) {
                if (attempt == 0 || attempt == 5) {
                    plugin.getLogger().warning("Waiting to unload world(s) on attempt " + (attempt + 1) + ": " + failedDetails);
                }
                if (!pendingDeletions.isEmpty()) {
                    scheduleDeletionSweep(40L);
                }
                long retryTicks = attempt < 6 ? 5L : 10L;
                pendingResetTask = Bukkit.getScheduler().runTaskLater(plugin,
                        () -> unloadOldAndCreateNewWorld(oldWorldNames, capturedOldDirs, attempt + 1), retryTicks);
                return;
            }

            pendingResetTask = null;
            resetInProgress = false;
            if (!pendingDeletions.isEmpty()) {
                scheduleDeletionSweep(40L);
            }
            plugin.getLogger().severe("Aborted world reset: could not unload speedrun world(s) after "
                    + (MAX_UNLOAD_ATTEMPTS + 1) + " attempts (" + failedDetails + ").");
            Bukkit.broadcast(MM.deserialize("<red>[Timer] World reset failed because an old speedrun world could not be unloaded. Check server console logs.</red>"));
            return;
        }

        pendingResetTask = null;

        // Queue all captured world folders (and any matching directories in search roots) for background deletion
        for (Path dir : capturedOldDirs) {
            enqueueDirectoryForDeletion(dir);
        }
        for (String worldName : oldWorldNames) {
            for (Path root : discoverWorldSearchRoots()) {
                Path candidate = root.resolve(worldName).toAbsolutePath().normalize();
                if (Files.exists(candidate)) {
                    enqueueDirectoryForDeletion(candidate);
                }
            }
        }
        scheduleDeletionSweep(40L);

        // Immediately create the new Overworld in a verified clean slot on the main thread
        try {
            World newOverworld = createNextSpeedrunOverworld();
            if (newOverworld != null) {
                resetInProgress = false;
                startSpawnPreGeneration(newOverworld, true);
                sweepStrayUnloadedDirectories();
                if (!pendingDeletions.isEmpty()) {
                    scheduleDeletionSweep(40L);
                }
            } else {
                resetInProgress = false;
                Bukkit.broadcast(MM.deserialize("<red>[Timer] Failed to generate the new speedrun world. Check server logs.</red>"));
            }
        } catch (Exception e) {
            resetInProgress = false;
            plugin.getLogger().severe("Error generating new speedrun world: " + e.getMessage());
            Bukkit.broadcast(MM.deserialize("<red>[Timer] Error generating new speedrun world: " + e.getMessage() + "</red>"));
        }
    }

    /**
     * Pre-generates spawn chunks in a spiral outward from the Overworld spawn location,
     * keeping at most `pregen-max-concurrent` async chunk requests in flight at any time
     * so players in the lobby experience zero TPS drop.
     */
    private void startSpawnPreGeneration(World overworld, boolean announceWhenDone) {
        cancelPreGeneration();

        int radius = Math.clamp(plugin.getConfig().getInt("pregen-chunk-radius", 8), 1, 16);
        int maxConcurrent = Math.clamp(plugin.getConfig().getInt("pregen-max-concurrent", 2), 1, 8);

        Location spawnLoc = overworld.getSpawnLocation();
        int centerChunkX = spawnLoc.getBlockX() >> 4;
        int centerChunkZ = spawnLoc.getBlockZ() >> 4;

        List<ChunkOffset> offsets = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                offsets.add(new ChunkOffset(dx, dz, dx * dx + dz * dz));
            }
        }
        // Sort radially outward from (0, 0) so the closest chunks around spawn generate first
        offsets.sort(Comparator.comparingInt(ChunkOffset::distSq));

        Deque<ChunkOffset> queue = new ArrayDeque<>(offsets);
        long sessionId = ++this.pregenSessionId;

        this.pregenInProgress = true;
        this.pregenTotalChunks = offsets.size();
        this.pregenCompletedChunks = 0;
        this.pregenInFlight = 0;

        this.pregenTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (sessionId != this.pregenSessionId || !overworld.equals(getActiveOverworld())) {
                cancelPreGeneration();
                return;
            }

            while (pregenInFlight < maxConcurrent && !queue.isEmpty()) {
                ChunkOffset offset = queue.poll();
                int cx = centerChunkX + offset.dx();
                int cz = centerChunkZ + offset.dz();
                pregenInFlight++;
                activeChunkRequests.incrementAndGet();

                overworld.getChunkAtAsync(cx, cz, true).whenComplete((chunk, throwable) -> {
                    activeChunkRequests.decrementAndGet();
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (sessionId != this.pregenSessionId) {
                            return;
                        }
                        pregenInFlight = Math.max(0, pregenInFlight - 1);
                        pregenCompletedChunks++;

                        // Keep the immediate 5-chunk radius around spawn loaded in memory until the run starts
                        if (chunk != null && offset.distSq() <= INNER_TICKET_RADIUS_SQ && overworld.equals(getActiveOverworld())) {
                            overworld.addPluginChunkTicket(cx, cz, plugin);
                        }

                        if (pregenCompletedChunks >= pregenTotalChunks && queue.isEmpty()) {
                            finishSpawnPreGeneration(announceWhenDone);
                        }
                    });
                });
            }
        }, 1L, 1L);
    }

    private void finishSpawnPreGeneration(boolean announce) {
        if (pregenTask != null && !pregenTask.isCancelled()) {
            pregenTask.cancel();
            pregenTask = null;
        }
        pregenInProgress = false;
        worldFreshlyPrepared = true;

        if (announce) {
            Title readyTitle = Title.title(
                    MM.deserialize("<green><bold>WORLD READY!</bold></green>"),
                    MM.deserialize("<yellow>Use /timer start to begin</yellow>"),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(400))
            );

            Bukkit.broadcast(MM.deserialize("<green><bold>[Timer]</bold> New speedrun world is 100% pre-generated! Run <yellow>/timer start</yellow> when everyone is ready.</green>"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(readyTitle);
                if (plugin.getConfig().getBoolean("play-sounds", true)) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
                }
            }
        } else {
            plugin.getLogger().info("Initial speedrun world spawn chunks pre-generated and ready.");
        }
    }

    private void cancelPreGeneration() {
        pregenSessionId++;
        if (pregenTask != null && !pregenTask.isCancelled()) {
            pregenTask.cancel();
            pregenTask = null;
        }
        pregenInProgress = false;
        pregenInFlight = 0;
        pregenCompletedChunks = 0;
        pregenTotalChunks = 0;
    }

    /**
     * Called by TimerManager right when /timer start is executed.
     * Blocks if the world is resetting or still pre-generating spawn chunks.
     * Otherwise teleports players from the lobby into the pre-generated speedrun spawn
     * and performs a full vanilla speedrun reset.
     */
    public boolean prepareWorldAndPlayersForStart(CommandSender initiator) {
        if (resetInProgress) {
            initiator.sendMessage(MM.deserialize("<red>[Timer] Cannot start the timer while a world reset is in progress!</red>"));
            return false;
        }

        if (pregenInProgress) {
            initiator.sendMessage(MM.deserialize("<red>[Timer] World is still pre-generating spawn chunks (<yellow>"
                    + getPreGenPercent() + "%</yellow>)! Please wait until it reaches 100%.</red>"));
            return false;
        }

        World overworld = getActiveOverworld();
        if (overworld == null) {
            initiator.sendMessage(MM.deserialize("<yellow>[Timer] Speedrun world was not loaded yet; generating now, please wait...</yellow>"));
            overworld = createNextSpeedrunOverworld();
            if (overworld == null) {
                initiator.sendMessage(MM.deserialize("<red>[Timer] Could not create speedrun world!</red>"));
                return false;
            }
            startSpawnPreGeneration(overworld, true);
            return false;
        }

        Location spawnLoc = overworld.getSpawnLocation();

        if (worldFreshlyPrepared) {
            overworld.setTime(0L);
            overworld.setStorm(false);
            overworld.setThundering(false);

            for (Player player : Bukkit.getOnlinePlayers()) {
                resetPlayerForRun(player, overworld);
                player.teleport(spawnLoc);
            }
        } else {
            // If restarting the timer in an existing world, bring in any players currently in the lobby or from an old world
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!isSpeedrunWorld(player.getWorld()) || !isPlayerPreparedForWorld(player, overworld)) {
                    resetPlayerForRun(player, overworld);
                    player.teleport(spawnLoc);
                }
            }
        }

        return true;
    }

    /**
     * Marks that the countdown completed and the run officially began.
     * Releases the temporary pre-gen chunk tickets so standard player chunk loading takes over.
     */
    public void markRunBegan() {
        this.worldFreshlyPrepared = false;
        World overworld = getActiveOverworld();
        if (overworld != null) {
            overworld.removePluginChunkTickets(plugin);
        }
    }

    /**
     * Called on plugin disable/server shutdown so players in temporary speedrun worlds
     * are saved at the lobby spawn and background tasks are stopped cleanly.
     */
    public void shutdownCleanup() {
        cancelPreGeneration();
        if (pendingResetTask != null && !pendingResetTask.isCancelled()) {
            pendingResetTask.cancel();
            pendingResetTask = null;
        }
        if (deletionSweepTask != null && !deletionSweepTask.isCancelled()) {
            deletionSweepTask.cancel();
            deletionSweepTask = null;
        }
        resetInProgress = false;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isSpeedrunWorld(player.getWorld())) {
                evacuatePlayerToLobby(player);
            }
        }
        for (World world : collectLoadedSpeedrunWorlds()) {
            prepareWorldForUnload(world);
        }
    }

    /**
     * Handles /timer lobby for a player to return to the lobby in SURVIVAL mode.
     * If a run is currently active, snapshots the player's exact speedrun state so they can rejoin via /timer join.
     */
    public void sendPlayerToLobby(Player player) {
        if (!isSpeedrunWorld(player.getWorld())) {
            player.sendMessage(MM.deserialize("<yellow>[Timer] You are already in the lobby!</yellow>"));
            return;
        }

        World overworld = getActiveOverworld();
        TimerState state = plugin.getTimerManager().getState();
        if (overworld != null && !resetInProgress && state != TimerState.IDLE) {
            savePlayerSpeedrunState(player, overworld);
            movePlayerToLobbyPreservingRunTag(player);
            player.sendMessage(MM.deserialize("<green>[Timer] Returned to the lobby. Use <yellow>/timer join</yellow> to return to the speedrun world.</green>"));
        } else {
            evacuatePlayerToLobby(player);
            player.sendMessage(MM.deserialize("<green>[Timer] Returned to the lobby.</green>"));
        }
    }

    /**
     * Handles /timer join (rejoin, return, back) for a player in the lobby to enter or return to the speedrun world.
     */
    public void sendPlayerToSpeedrun(Player player) {
        if (isSpeedrunWorld(player.getWorld())) {
            player.sendMessage(MM.deserialize("<yellow>[Timer] You are already in the speedrun world!</yellow>"));
            return;
        }

        if (resetInProgress) {
            player.sendMessage(MM.deserialize("<red>[Timer] A world reset is currently in progress! Please wait...</red>"));
            return;
        }

        if (pregenInProgress) {
            player.sendMessage(MM.deserialize("<red>[Timer] World is still pre-generating spawn chunks (<yellow>"
                    + getPreGenPercent() + "%</yellow>)! Please wait until it reaches 100%.</red>"));
            return;
        }

        TimerState state = plugin.getTimerManager().getState();
        if (state == TimerState.IDLE) {
            player.sendMessage(MM.deserialize("<yellow>[Timer] No speedrun is currently active! Use <white>/timer start</white> to begin the run.</yellow>"));
            return;
        }

        World overworld = getActiveOverworld();
        if (overworld == null) {
            player.sendMessage(MM.deserialize("<red>[Timer] The speedrun world is not currently loaded.</red>"));
            return;
        }

        if (isPlayerPreparedForWorld(player, overworld)) {
            restorePlayerToSpeedrun(player, overworld, true);
        } else if (state == TimerState.FINISHED) {
            if (player.isDead()) {
                player.spigot().respawn();
            }
            player.leaveVehicle();
            player.teleport(overworld.getSpawnLocation());
            player.setGameMode(GameMode.SPECTATOR);
            player.sendMessage(MM.deserialize("<green>[Timer] Joined the finished speedrun world as a spectator.</green>"));
        } else {
            // Late joiner entering an active run (COUNTDOWN, RUNNING, or PAUSED) for the first time
            resetPlayerForRun(player, overworld);
            player.teleport(overworld.getSpawnLocation());
            player.sendMessage(MM.deserialize("<green>[Timer] Joined the active speedrun!</green>"));
        }
    }

    private void savePlayerSpeedrunState(Player player, World overworld) {
        boolean diedInHardcore = isHardcoreDead(player, overworld)
                || (overworld.isHardcore() && (player.isDead() || player.getGameMode() == GameMode.SPECTATOR));
        if (diedInHardcore) {
            markHardcoreDead(player, overworld);
        }

        if (player.isDead()) {
            player.spigot().respawn();
        }
        if (player.isSleeping()) {
            player.wakeup(false);
        }
        player.leaveVehicle();
        cancelEnderPearlsByPlayer(player);
        player.closeInventory();

        Location loc = player.getLocation();
        World locWorld = loc.getWorld();
        String worldName = (locWorld != null && isSpeedrunWorld(locWorld)) ? locWorld.getName() : overworld.getName();

        GameMode savedMode = diedInHardcore ? GameMode.SPECTATOR : player.getGameMode();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        ItemStack clonedOffHand = (!offHand.getType().isAir()) ? offHand.clone() : null;

        SavedSpeedrunState snapshot = new SavedSpeedrunState(
                overworld.getUID(),
                worldName,
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                loc.getYaw(),
                loc.getPitch(),
                savedMode,
                diedInHardcore,
                player.getHealth(),
                player.getAbsorptionAmount(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getFireTicks(),
                player.getFreezeTicks(),
                player.getRemainingAir(),
                player.getFallDistance(),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                new ArrayList<>(player.getActivePotionEffects()),
                cloneItems(player.getInventory().getContents()),
                cloneItems(player.getInventory().getArmorContents()),
                clonedOffHand,
                cloneItems(player.getEnderChest().getContents())
        );
        savedRunStates.put(player.getUniqueId(), snapshot);
    }

    private void restorePlayerToSpeedrun(Player player, World overworld, boolean sendFeedback) {
        if (player.isDead()) {
            player.spigot().respawn();
        }
        if (player.isSleeping()) {
            player.wakeup(false);
        }
        player.leaveVehicle();
        cancelEnderPearlsByPlayer(player);
        player.closeInventory();

        SavedSpeedrunState saved = savedRunStates.remove(player.getUniqueId());
        boolean hardcoreDead = isHardcoreDead(player, overworld)
                || (saved != null && saved.overworldUid().equals(overworld.getUID())
                && (saved.hardcoreDead() || (overworld.isHardcore() && saved.gameMode() == GameMode.SPECTATOR)));

        if (saved != null && saved.overworldUid().equals(overworld.getUID())) {
            World targetWorld = Bukkit.getWorld(saved.worldName());
            if (targetWorld == null) {
                if (saved.worldName().equals(activeNetherName)) {
                    targetWorld = getOrCreateNether();
                } else if (saved.worldName().equals(activeEndName)) {
                    targetWorld = getOrCreateTheEnd();
                }
            }

            Location targetLoc;
            if (targetWorld != null && isSpeedrunWorld(targetWorld)) {
                targetLoc = new Location(targetWorld, saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch());
            } else {
                targetLoc = hardcoreDead ? findLivingTeammateOrSpawn(player, overworld) : overworld.getSpawnLocation();
            }

            player.teleport(targetLoc);

            // Restore inventory, equipment, and ender chest
            player.getInventory().setContents(cloneItems(saved.inventoryContents()));
            player.getInventory().setArmorContents(cloneItems(saved.armorContents()));
            player.getInventory().setItemInOffHand(saved.offHandItem() != null ? saved.offHandItem().clone() : null);
            player.setItemOnCursor(null);
            player.getEnderChest().setContents(cloneItems(saved.enderChestContents()));

            // Restore XP and active potion effects
            player.setLevel(saved.expLevel());
            player.setExp(saved.expProgress());
            player.setTotalExperience(saved.totalExp());
            for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                player.removePotionEffect(effect.getType());
            }
            for (PotionEffect effect : saved.potionEffects()) {
                player.addPotionEffect(effect);
            }

            if (hardcoreDead) {
                player.setGameMode(GameMode.SPECTATOR);
                player.setFireTicks(0);
                player.setFreezeTicks(0);
                player.setFallDistance(0f);
                player.setVelocity(new Vector(0, 0, 0));
                if (sendFeedback) {
                    player.sendMessage(MM.deserialize("<green>[Timer] Rejoined the speedrun world as a spectator.</green>"));
                }
            } else {
                player.setGameMode(saved.gameMode());
                AttributeInstance maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
                double maxHealth = (maxHealthAttr != null) ? maxHealthAttr.getValue() : 20.0;
                player.setHealth(Math.clamp(saved.health(), 0.5, maxHealth));
                player.setAbsorptionAmount(Math.max(0.0, saved.absorption()));
                player.setFoodLevel(saved.foodLevel());
                player.setSaturation(saved.saturation());
                player.setExhaustion(saved.exhaustion());
                player.setFireTicks(saved.fireTicks());
                player.setFreezeTicks(saved.freezeTicks());
                player.setRemainingAir(saved.remainingAir());
                player.setFallDistance(saved.fallDistance());
                player.setVelocity(new Vector(0, 0, 0));
                if (sendFeedback) {
                    player.sendMessage(MM.deserialize("<green>[Timer] Rejoined the speedrun world!</green>"));
                }
            }
        } else {
            // Fallback if no in-memory snapshot was present
            if (hardcoreDead) {
                player.teleport(findLivingTeammateOrSpawn(player, overworld));
                player.setGameMode(GameMode.SPECTATOR);
                if (sendFeedback) {
                    player.sendMessage(MM.deserialize("<green>[Timer] Rejoined the speedrun world as a spectator.</green>"));
                }
            } else {
                Location target = player.getRespawnLocation();
                if (target == null || target.getWorld() == null || !isSpeedrunWorld(target.getWorld())) {
                    target = overworld.getSpawnLocation();
                }
                player.teleport(target);
                player.setGameMode(GameMode.SURVIVAL);
                if (sendFeedback) {
                    player.sendMessage(MM.deserialize("<green>[Timer] Rejoined the speedrun world!</green>"));
                }
            }
        }
    }

    private void movePlayerToLobbyPreservingRunTag(Player player) {
        if (player.isDead()) {
            player.spigot().respawn();
        }
        if (player.isSleeping()) {
            player.wakeup(false);
        }
        player.leaveVehicle();
        cancelEnderPearlsByPlayer(player);
        player.closeInventory();

        player.setGameMode(GameMode.SURVIVAL);
        player.setFallDistance(0f);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setVelocity(new Vector(0, 0, 0));
        restoreFullHealthAndFood(player);

        Location lobbySpawn = getLobbySpawn();
        if (!player.getWorld().equals(lobbySpawn.getWorld()) || player.getLocation().distanceSquared(lobbySpawn) > 4.0) {
            player.teleport(lobbySpawn);
        }
    }

    /**
     * Picks the lowest clean speedrun slot (speedrun_1, speedrun_2, ...) that does not exist in any
     * world storage root (pre-26.1 or Paper 26.1+ dimensions/) and creates a new Overworld with a
     * verified fresh random seed.
     */
    private @Nullable World createNextSpeedrunOverworld() {
        boolean hardcore = this.speedrunHardcore;
        Difficulty difficulty = getEffectiveSpeedrunDifficulty();

        for (int attempt = 0; attempt < 10; attempt++) {
            int candidateSlot = findNextCleanSlotIndex();
            String overworldName = SPEEDRUN_PREFIX + "_" + candidateSlot;
            String netherName = overworldName + "_nether";
            String endName = overworldName + "_the_end";

            long requestedSeed = ThreadLocalRandom.current().nextLong();
            WorldCreator creator = new WorldCreator(overworldName)
                    .environment(World.Environment.NORMAL)
                    .seed(requestedSeed)
                    .hardcore(hardcore)
                    .keepSpawnLoaded(TriState.FALSE);
            World world = Bukkit.createWorld(creator);
            if (world == null) {
                return null;
            }

            Path actualFolder = world.getWorldFolder().toPath().toAbsolutePath().normalize();
            recordObservedParent(actualFolder);

            if (world.getSeed() != requestedSeed) {
                plugin.getLogger().warning("Speedrun Overworld '" + overworldName + "' at '" + actualFolder
                        + "' returned stale seed " + world.getSeed() + " instead of requested " + requestedSeed
                        + ". Unloading and retrying with next clean slot...");
                blockedSlotIndices.add(candidateSlot);
                prepareWorldForUnload(world);
                if (Bukkit.unloadWorld(world, false)) {
                    enqueueDirectoryForDeletion(actualFolder);
                    scheduleDeletionSweep(40L);
                }
                continue;
            }

            activeOverworldName = overworldName;
            activeNetherName = netherName;
            activeEndName = endName;

            world.setTime(0L);
            world.setStorm(false);
            world.setThundering(false);
            world.setDifficulty(difficulty);
            world.setHardcore(hardcore);

            plugin.getLogger().info("Created new speedrun Overworld '" + activeOverworldName + "' at '"
                    + actualFolder + "' with seed: " + world.getSeed()
                    + " (hardcore=" + hardcore + ", difficulty=" + difficulty + ")");
            return world;
        }

        plugin.getLogger().severe("Failed to create a speedrun Overworld with a fresh seed after 10 slot attempts.");
        return null;
    }

    private int findNextCleanSlotIndex() {
        int candidate = 1;
        while (isSlotInUseOrOnDisk(candidate)) {
            candidate++;
        }
        return candidate;
    }

    private boolean isSlotInUseOrOnDisk(int slotIndex) {
        if (blockedSlotIndices.contains(slotIndex)) {
            return true;
        }
        String base = SPEEDRUN_PREFIX + "_" + slotIndex;
        List<String> names = List.of(base, base + "_nether", base + "_the_end");
        List<Path> roots = discoverWorldSearchRoots();

        for (String name : names) {
            if (Bukkit.getWorld(name) != null) {
                return true;
            }
            for (Path root : roots) {
                if (Files.exists(root.resolve(name))) {
                    return true;
                }
            }
            for (Path pending : pendingDeletions) {
                Path fileName = pending.getFileName();
                if (fileName != null && fileName.toString().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    public @Nullable World getOrCreateNether() {
        World nether = Bukkit.getWorld(activeNetherName);
        if (nether != null) {
            return nether;
        }
        World overworld = getActiveOverworld();
        if (overworld == null) {
            return null;
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendActionBar(MM.deserialize("<gold>Generating Nether dimension...</gold>"));
        }

        boolean hardcore = this.speedrunHardcore;
        Difficulty difficulty = getEffectiveSpeedrunDifficulty();
        WorldCreator creator = new WorldCreator(activeNetherName)
                .environment(World.Environment.NETHER)
                .seed(overworld.getSeed())
                .hardcore(hardcore)
                .keepSpawnLoaded(TriState.FALSE);
        nether = Bukkit.createWorld(creator);
        if (nether != null) {
            recordObservedParent(nether.getWorldFolder().toPath());
            nether.setDifficulty(difficulty);
            nether.setHardcore(hardcore);
        }
        return nether;
    }

    public @Nullable World getOrCreateTheEnd() {
        World theEnd = Bukkit.getWorld(activeEndName);
        if (theEnd != null) {
            return theEnd;
        }
        World overworld = getActiveOverworld();
        if (overworld == null) {
            return null;
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendActionBar(MM.deserialize("<light_purple>Generating The End dimension...</light_purple>"));
        }

        boolean hardcore = this.speedrunHardcore;
        Difficulty difficulty = getEffectiveSpeedrunDifficulty();
        WorldCreator creator = new WorldCreator(activeEndName)
                .environment(World.Environment.THE_END)
                .seed(overworld.getSeed())
                .hardcore(hardcore)
                .keepSpawnLoaded(TriState.FALSE);
        theEnd = Bukkit.createWorld(creator);
        if (theEnd != null) {
            recordObservedParent(theEnd.getWorldFolder().toPath());
            theEnd.setDifficulty(difficulty);
            theEnd.setHardcore(hardcore);
        }
        return theEnd;
    }

    /**
     * Evacuates a player to the lobby world and clears any run-specific tags.
     * If the player was in a speedrun world or in SPECTATOR mode, restores them to SURVIVAL mode;
     * if they were already in the lobby in CREATIVE mode, preserves their CREATIVE mode.
     */
    public void evacuatePlayerToLobby(Player player) {
        boolean wasInSpeedrun = isSpeedrunWorld(player.getWorld());
        if (player.isDead()) {
            player.spigot().respawn();
        }
        if (player.isSleeping()) {
            player.wakeup(false);
        }
        player.leaveVehicle();
        cancelEnderPearlsByPlayer(player);

        if (wasInSpeedrun || player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        player.setFallDistance(0f);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setVelocity(new Vector(0, 0, 0));
        restoreFullHealthAndFood(player);

        Location respawn = player.getRespawnLocation();
        if (respawn != null && respawn.getWorld() != null && isSpeedrunWorld(respawn.getWorld())) {
            player.setRespawnLocation(null, true);
        }

        player.getPersistentDataContainer().remove(activeWorldUidKey);
        player.getPersistentDataContainer().remove(hardcoreDeadWorldUidKey);
        savedRunStates.remove(player.getUniqueId());

        Location lobbySpawn = getLobbySpawn();
        if (!player.getWorld().equals(lobbySpawn.getWorld()) || player.getLocation().distanceSquared(lobbySpawn) > 4.0) {
            player.teleport(lobbySpawn);
        }
    }

    /**
     * Performs a full vanilla speedrun reset on a player and binds them to the given speedrun Overworld UID.
     */
    public void resetPlayerForRun(Player player, World speedrunOverworld) {
        if (player.isDead()) {
            player.spigot().respawn();
        }
        if (player.isSleeping()) {
            player.wakeup(false);
        }
        player.leaveVehicle();
        cancelEnderPearlsByPlayer(player);
        player.closeInventory();

        // Clear inventory, equipment, cursor, and ender chest
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
        player.getEnderChest().clear();

        // Reset gamemode, health, food, saturation, and physical states
        player.setGameMode(GameMode.SURVIVAL);
        restoreFullHealthAndFood(player);
        player.setAbsorptionAmount(0.0);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0.0f);
        player.setRemainingAir(player.getMaximumAir());
        player.setVelocity(new Vector(0, 0, 0));

        // Reset XP and active potion effects
        player.setExp(0.0f);
        player.setLevel(0);
        player.setTotalExperience(0);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }

        // Clear bed/anchor respawn point so default respawn uses the new speedrun world
        player.setRespawnLocation(null, true);

        // Revoke all advancements
        Iterator<Advancement> advIterator = Bukkit.advancementIterator();
        while (advIterator.hasNext()) {
            Advancement adv = advIterator.next();
            AdvancementProgress progress = player.getAdvancementProgress(adv);
            for (String criteria : new ArrayList<>(progress.getAwardedCriteria())) {
                progress.revokeCriteria(criteria);
            }
        }

        // Record the active speedrun world UID on the player and clear any old Hardcore death / saved lobby state
        player.getPersistentDataContainer().set(
                activeWorldUidKey,
                PersistentDataType.STRING,
                speedrunOverworld.getUID().toString()
        );
        player.getPersistentDataContainer().remove(hardcoreDeadWorldUidKey);
        savedRunStates.remove(player.getUniqueId());
    }

    private void restoreFullHealthAndFood(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double max = (maxHealth != null) ? maxHealth.getValue() : 20.0;
        player.setHealth(max);
        player.setFoodLevel(20);
        player.setSaturation(5.0f);
        player.setExhaustion(0.0f);
    }

    private boolean isPlayerPreparedForWorld(Player player, World speedrunOverworld) {
        String storedUid = player.getPersistentDataContainer().get(activeWorldUidKey, PersistentDataType.STRING);
        return storedUid != null && storedUid.equals(speedrunOverworld.getUID().toString());
    }

    private boolean isHardcoreDead(Player player, World speedrunOverworld) {
        String storedUid = player.getPersistentDataContainer().get(hardcoreDeadWorldUidKey, PersistentDataType.STRING);
        return storedUid != null && storedUid.equals(speedrunOverworld.getUID().toString());
    }

    private void markHardcoreDead(Player player, World speedrunOverworld) {
        player.getPersistentDataContainer().set(
                hardcoreDeadWorldUidKey,
                PersistentDataType.STRING,
                speedrunOverworld.getUID().toString()
        );
    }

    private Location findLivingTeammateOrSpawn(Player player, World overworld) {
        for (Player teammate : Bukkit.getOnlinePlayers()) {
            if (!teammate.equals(player)
                    && !teammate.isDead()
                    && teammate.getGameMode() == GameMode.SURVIVAL
                    && isSpeedrunWorld(teammate.getWorld())) {
                return teammate.getLocation();
            }
        }
        return overworld.getSpawnLocation();
    }

    private static ItemStack[] cloneItems(@Nullable ItemStack[] items) {
        if (items == null) {
            return new ItemStack[0];
        }
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] != null ? items[i].clone() : null;
        }
        return copy;
    }

    private void cancelEnderPearlsInWorld(World world) {
        for (EnderPearl pearl : world.getEntitiesByClass(EnderPearl.class)) {
            pearl.remove();
        }
    }

    private void cancelEnderPearlsByPlayer(Player player) {
        for (World world : Bukkit.getWorlds()) {
            for (EnderPearl pearl : world.getEntitiesByClass(EnderPearl.class)) {
                if (player.equals(pearl.getShooter())) {
                    pearl.remove();
                }
            }
        }
    }

    private List<World> collectLoadedSpeedrunWorlds() {
        List<World> loaded = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (isSpeedrunWorld(world)) {
                loaded.add(world);
            }
        }
        // Unload The End and Nether before the Overworld
        loaded.sort(Comparator
                .comparingInt((World w) -> {
                    String name = w.getName();
                    if (name.endsWith("_the_end")) {
                        return 0;
                    }
                    if (name.endsWith("_nether")) {
                        return 1;
                    }
                    return 2;
                })
                .thenComparing(World::getName));
        return loaded;
    }

    private void prepareWorldForUnload(World world) {
        cancelEnderPearlsInWorld(world);
        world.setAutoSave(false);
        world.setKeepSpawnInMemory(false);
        world.removePluginChunkTickets(plugin);
        for (Chunk chunk : world.getForceLoadedChunks()) {
            chunk.setForceLoaded(false);
        }
    }

    private void recordObservedParent(@Nullable Path worldFolder) {
        if (worldFolder == null) {
            return;
        }
        Path parent = worldFolder.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            observedSpeedrunParents.add(parent);
        }
    }

    private List<Path> discoverWorldSearchRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        roots.add(container);
        addDimensionNamespaces(roots, container.resolve("dimensions"));
        roots.addAll(observedSpeedrunParents);

        World lobby = getLobbyWorldSafe();
        if (lobby != null) {
            Path lobbyFolder = lobby.getWorldFolder().toPath().toAbsolutePath().normalize();
            if (lobbyFolder.getParent() != null) {
                roots.add(lobbyFolder.getParent());
            }
            Path cursor = lobbyFolder;
            for (int depth = 0; depth < 4 && cursor != null; depth++) {
                addDimensionNamespaces(roots, cursor.resolve("dimensions"));
                cursor = cursor.getParent();
            }
        }

        for (World loaded : Bukkit.getWorlds()) {
            Path loadedFolder = loaded.getWorldFolder().toPath().toAbsolutePath().normalize();
            if (loadedFolder.getParent() != null) {
                roots.add(loadedFolder.getParent());
            }
            addDimensionNamespaces(roots, loadedFolder.resolve("dimensions"));
        }

        return new ArrayList<>(roots);
    }

    private void addDimensionNamespaces(Set<Path> roots, Path dimensionsDir) {
        if (!Files.isDirectory(dimensionsDir)) {
            return;
        }
        try (Stream<Path> namespaces = Files.list(dimensionsDir)) {
            namespaces.filter(Files::isDirectory)
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(roots::add);
        } catch (IOException ignored) {
        }
    }

    private boolean isSafeSpeedrunDirectoryToDelete(@Nullable Path rawDir) {
        if (rawDir == null) {
            return false;
        }
        Path dir = rawDir.toAbsolutePath().normalize();
        Path fileName = dir.getFileName();
        if (fileName == null || !SPEEDRUN_DIR_PATTERN.matcher(fileName.toString()).matches()) {
            return false;
        }

        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        if (dir.equals(container) || container.startsWith(dir)) {
            return false;
        }

        for (World loaded : Bukkit.getWorlds()) {
            Path loadedFolder = loaded.getWorldFolder().toPath().toAbsolutePath().normalize();
            if (dir.equals(loadedFolder) || loadedFolder.startsWith(dir)) {
                return false;
            }
        }
        return true;
    }

    private void enqueueDirectoryForDeletion(@Nullable Path dir) {
        if (dir == null) {
            return;
        }
        Path normalized = dir.toAbsolutePath().normalize();
        if (!isSafeSpeedrunDirectoryToDelete(normalized)) {
            return;
        }
        if (pendingDeletions.add(normalized)) {
            deletionAttempts.putIfAbsent(normalized, 0);
        }
    }

    private void sweepStrayUnloadedDirectories() {
        Set<String> loadedNames = new HashSet<>();
        for (World loaded : Bukkit.getWorlds()) {
            loadedNames.add(loaded.getName());
        }
        if (activeOverworldName != null) {
            loadedNames.add(activeOverworldName);
        }
        if (activeNetherName != null) {
            loadedNames.add(activeNetherName);
        }
        if (activeEndName != null) {
            loadedNames.add(activeEndName);
        }

        for (Path root : discoverWorldSearchRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(root)) {
                entries.filter(Files::isDirectory).forEach(entry -> {
                    String folderName = entry.getFileName().toString();
                    if (!SPEEDRUN_DIR_PATTERN.matcher(folderName).matches()) {
                        return;
                    }
                    if (loadedNames.contains(folderName)) {
                        return;
                    }
                    enqueueDirectoryForDeletion(entry);
                });
            } catch (IOException ignored) {
            }
        }
    }

    private void scheduleDeletionSweep(long delayTicks) {
        if (!plugin.isEnabled() || pendingDeletions.isEmpty()) {
            return;
        }
        if (deletionSweepRunning.get()) {
            return;
        }
        if (deletionSweepTask != null) {
            deletionSweepTask.cancel();
            deletionSweepTask = null;
        }

        long safeDelay = Math.max(1L, delayTicks);
        deletionSweepTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            deletionSweepTask = null;
            runDeletionSweep();
        }, safeDelay);
    }

    private void runDeletionSweep() {
        if (!plugin.isEnabled() || pendingDeletions.isEmpty()) {
            return;
        }
        if (!deletionSweepRunning.compareAndSet(false, true)) {
            return;
        }

        Set<Path> protectedPaths = new HashSet<>();
        Set<String> protectedNames = new HashSet<>();
        for (World loaded : Bukkit.getWorlds()) {
            protectedPaths.add(loaded.getWorldFolder().toPath().toAbsolutePath().normalize());
            protectedNames.add(loaded.getName());
        }
        if (activeOverworldName != null) {
            protectedNames.add(activeOverworldName);
        }
        if (activeNetherName != null) {
            protectedNames.add(activeNetherName);
        }
        if (activeEndName != null) {
            protectedNames.add(activeEndName);
        }

        List<Path> batch = new ArrayList<>();
        for (Path candidate : pendingDeletions) {
            Path normalized = candidate.toAbsolutePath().normalize();
            String folderName = normalized.getFileName() != null ? normalized.getFileName().toString() : "";
            if (protectedPaths.contains(normalized) || protectedNames.contains(folderName)) {
                pendingDeletions.remove(candidate);
                deletionAttempts.remove(candidate);
                continue;
            }
            batch.add(normalized);
        }

        if (batch.isEmpty()) {
            deletionSweepRunning.set(false);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Path> deleted = new ArrayList<>();
            Map<Path, String> failures = new HashMap<>();

            try {
                for (Path target : batch) {
                    if (!Files.exists(target)) {
                        deleted.add(target);
                        continue;
                    }
                    String error = deleteDirectoryRecursively(target);
                    if (error == null && !Files.exists(target)) {
                        deleted.add(target);
                    } else {
                        failures.put(target, error != null ? error : "directory still present");
                    }
                }
            } finally {
                if (plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        deletionSweepRunning.set(false);
                        finishDeletionSweepOnMainThread(deleted, failures);
                    });
                } else {
                    deletionSweepRunning.set(false);
                }
            }
        });
    }

    private void finishDeletionSweepOnMainThread(List<Path> deleted, Map<Path, String> failures) {
        for (Path path : deleted) {
            pendingDeletions.remove(path);
            deletionAttempts.remove(path);
        }

        int maxAttemptSeen = 0;
        for (Map.Entry<Path, String> entry : failures.entrySet()) {
            Path path = entry.getKey();
            int nextAttempt = deletionAttempts.getOrDefault(path, 0) + 1;
            maxAttemptSeen = Math.max(maxAttemptSeen, nextAttempt);

            if (nextAttempt >= 12) {
                plugin.getLogger().warning("Could not delete old speedrun folder " + path
                        + " after " + nextAttempt + " attempts (" + entry.getValue()
                        + "); will retry on next reset or startup.");
                pendingDeletions.remove(path);
                deletionAttempts.remove(path);
            } else {
                deletionAttempts.put(path, nextAttempt);
            }
        }

        blockedSlotIndices.removeIf(slot -> !isSlotStillPendingDeletion(slot));

        if (!pendingDeletions.isEmpty() && plugin.isEnabled()) {
            long[] backoffTicks = {40L, 60L, 100L, 200L, 300L};
            long nextDelay = backoffTicks[Math.min(Math.max(0, maxAttemptSeen - 1), backoffTicks.length - 1)];
            scheduleDeletionSweep(nextDelay);
        }
    }

    private boolean isSlotStillPendingDeletion(int slot) {
        String baseName = "speedrun_" + slot;
        Set<String> slotNames = Set.of(baseName, baseName + "_nether", baseName + "_the_end");
        for (Path pending : pendingDeletions) {
            Path fileName = pending.getFileName();
            if (fileName != null && slotNames.contains(fileName.toString())) {
                return true;
            }
        }
        return false;
    }

    private @Nullable String deleteDirectoryRecursively(Path rootPath) {
        final String[] firstError = {null};
        try {
            if (!Files.exists(rootPath)) {
                return null;
            }
            try (Stream<Path> walk = Files.walk(rootPath)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ex) {
                        if (firstError[0] == null) {
                            String rel = rootPath.relativize(path).toString();
                            firstError[0] = (rel.isEmpty() ? path.getFileName().toString() : rel)
                                    + ": " + ex.getClass().getSimpleName() + " (" + ex.getMessage() + ")";
                        }
                    }
                });
            }
        } catch (IOException ex) {
            if (firstError[0] == null) {
                firstError[0] = ex.getClass().getSimpleName() + " (" + ex.getMessage() + ")";
            }
        }
        if (!Files.exists(rootPath)) {
            return null;
        }
        return firstError[0] != null ? firstError[0] : "remaining files locked";
    }

    // =========================================================================
    // Event Handlers: Join, Quit, Death, Respawn, and Portal Linking
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        TimerManager timerManager = plugin.getTimerManager();
        TimerState state = timerManager.getState();

        // If a world reset is running or no run is active (IDLE), keep everyone in the lobby
        if (resetInProgress || pregenInProgress || state == TimerState.IDLE) {
            evacuatePlayerToLobby(player);
            return;
        }

        World overworld = getActiveOverworld();
        if (overworld == null) {
            evacuatePlayerToLobby(player);
            return;
        }

        // If a run is active (COUNTDOWN, RUNNING, PAUSED) or FINISHED:
        if (isPlayerPreparedForWorld(player, overworld)) {
            // Player is reconnecting to the SAME active run they were already part of.
            if (!isSpeedrunWorld(player.getWorld())) {
                restorePlayerToSpeedrun(player, overworld, false);
            } else if (isHardcoreDead(player, overworld)) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        } else if (state == TimerState.COUNTDOWN || state == TimerState.RUNNING || state == TimerState.PAUSED) {
            // Player is joining a new run for the first time (late joiner or logged out during an older run)
            resetPlayerForRun(player, overworld);
            player.teleport(overworld.getSpawnLocation());
            player.sendMessage(MM.deserialize("<green>[Timer] Teleported into the active speedrun!</green>"));
        } else {
            // State is FINISHED, and player wasn't part of that run
            evacuatePlayerToLobby(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cancelEnderPearlsByPlayer(player);
        if (player.isDead()) {
            player.spigot().respawn();
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (isSpeedrunWorld(player.getWorld()) && player.getWorld().isHardcore()) {
            World overworld = getActiveOverworld();
            if (overworld != null) {
                markHardcoreDead(player, overworld);
            }
            player.sendMessage(MM.deserialize("<gold>[Timer]</gold> <yellow>You died in Hardcore! Click <white><bold>Spectate World</bold></white> to watch your teammates, or use <aqua>/timer lobby</aqua> to return to the lobby.</yellow>"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        // If the player died in the lobby (or no run is active), always respawn them in the lobby (never Hardcore spectator)
        if (!isSpeedrunWorld(player.getWorld()) || resetInProgress || pregenInProgress || plugin.getTimerManager().getState() == TimerState.IDLE) {
            event.setRespawnLocation(getLobbySpawn());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!isSpeedrunWorld(player.getWorld()) && player.getGameMode() == GameMode.SPECTATOR) {
                    player.setGameMode(GameMode.SURVIVAL);
                }
            });
            return;
        }

        World overworld = getActiveOverworld();
        if (overworld == null) {
            event.setRespawnLocation(getLobbySpawn());
            return;
        }

        // In Hardcore mode, spawn the spectating player near a living teammate in the speedrun worlds (or at speedrun spawn)
        if (overworld.isHardcore() || isHardcoreDead(player, overworld)) {
            event.setRespawnLocation(findLivingTeammateOrSpawn(player, overworld));
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (isSpeedrunWorld(player.getWorld())) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
            });
            return;
        }

        // In standard Survival mode, if the player has a valid bed or respawn anchor inside the speedrun worlds, honor it
        Location respawnLoc = event.getRespawnLocation();
        if ((event.isBedSpawn() || event.isAnchorSpawn())
                && respawnLoc.getWorld() != null
                && isSpeedrunWorld(respawnLoc.getWorld())) {
            return;
        }

        // Otherwise, respawn them at the speedrun Overworld spawn instead of the default lobby world
        event.setRespawnLocation(overworld.getSpawnLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        World fromWorld = event.getFrom().getWorld();
        if (!isSpeedrunWorld(fromWorld)) {
            return;
        }

        if (resetInProgress) {
            event.setCancelled(true);
            return;
        }

        Player player = event.getPlayer();
        Location from = event.getFrom();
        PlayerTeleportEvent.TeleportCause cause = event.getCause();

        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            if (fromWorld.getName().equals(activeOverworldName)) {
                World nether = getOrCreateNether();
                if (nether == null) {
                    event.setCancelled(true);
                    return;
                }
                double x = from.getX() / 8.0;
                double z = from.getZ() / 8.0;
                double y = Math.clamp(from.getY(), 32.0, 120.0);
                event.setTo(new Location(nether, x, y, z, from.getYaw(), from.getPitch()));
                event.setCanCreatePortal(true);
                event.setSearchRadius(16);
                event.setCreationRadius(16);
            } else if (fromWorld.getName().equals(activeNetherName)) {
                World overworld = getActiveOverworld();
                if (overworld == null) {
                    event.setCancelled(true);
                    return;
                }
                double x = from.getX() * 8.0;
                double z = from.getZ() * 8.0;
                double y = Math.clamp(from.getY(), overworld.getMinHeight() + 4, overworld.getMaxHeight() - 4);
                event.setTo(new Location(overworld, x, y, z, from.getYaw(), from.getPitch()));
                event.setCanCreatePortal(true);
                event.setSearchRadius(128);
                event.setCreationRadius(16);
            }
        } else if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            if (fromWorld.getName().equals(activeOverworldName)) {
                World theEnd = getOrCreateTheEnd();
                if (theEnd == null) {
                    event.setCancelled(true);
                    return;
                }
                buildEndObsidianPlatform(theEnd);
                event.setTo(new Location(theEnd, 100.5, 49.0, 0.5, 90.0f, 0.0f));
                event.setCanCreatePortal(false);
            } else if (fromWorld.getName().equals(activeEndName)) {
                // Returning from The End exit portal to the speedrun Overworld
                World overworld = getActiveOverworld();
                if (overworld == null) {
                    event.setCancelled(true);
                    return;
                }
                Location target = player.getRespawnLocation();
                if (target == null || target.getWorld() == null || !isSpeedrunWorld(target.getWorld())) {
                    target = overworld.getSpawnLocation();
                }
                event.setTo(target);
                event.setCanCreatePortal(false);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        World fromWorld = event.getFrom().getWorld();
        if (!isSpeedrunWorld(fromWorld)) {
            return;
        }

        if (resetInProgress) {
            event.setCancelled(true);
            return;
        }

        Location from = event.getFrom();
        PortalType portalType = event.getPortalType();

        if (portalType == PortalType.NETHER) {
            if (fromWorld.getName().equals(activeOverworldName)) {
                World nether = getOrCreateNether();
                if (nether == null) {
                    event.setCancelled(true);
                    return;
                }
                double x = from.getX() / 8.0;
                double z = from.getZ() / 8.0;
                double y = Math.clamp(from.getY(), 32.0, 120.0);
                event.setTo(new Location(nether, x, y, z, from.getYaw(), from.getPitch()));
                event.setCanCreatePortal(true);
                event.setSearchRadius(16);
                event.setCreationRadius(16);
            } else if (fromWorld.getName().equals(activeNetherName)) {
                World overworld = getActiveOverworld();
                if (overworld == null) {
                    event.setCancelled(true);
                    return;
                }
                double x = from.getX() * 8.0;
                double z = from.getZ() * 8.0;
                double y = Math.clamp(from.getY(), overworld.getMinHeight() + 4, overworld.getMaxHeight() - 4);
                event.setTo(new Location(overworld, x, y, z, from.getYaw(), from.getPitch()));
                event.setCanCreatePortal(true);
                event.setSearchRadius(128);
                event.setCreationRadius(16);
            }
        } else if (portalType == PortalType.ENDER) {
            if (fromWorld.getName().equals(activeOverworldName)) {
                World theEnd = getOrCreateTheEnd();
                if (theEnd == null) {
                    event.setCancelled(true);
                    return;
                }
                buildEndObsidianPlatform(theEnd);
                event.setTo(new Location(theEnd, 100.5, 49.0, 0.5, 90.0f, 0.0f));
                event.setCanCreatePortal(false);
            } else if (fromWorld.getName().equals(activeEndName)) {
                World overworld = getActiveOverworld();
                if (overworld == null) {
                    event.setCancelled(true);
                    return;
                }
                event.setTo(overworld.getSpawnLocation());
                event.setCanCreatePortal(false);
            }
        }
    }

    /**
     * Generates the standard vanilla 5x5 obsidian platform at (98..102, 48, -2..2) in The End
     * and clears the 3-block air space above it.
     */
    private void buildEndObsidianPlatform(World endWorld) {
        for (int x = 98; x <= 102; x++) {
            for (int z = -2; z <= 2; z++) {
                Block platformBlock = endWorld.getBlockAt(x, 48, z);
                if (platformBlock.getType() != Material.OBSIDIAN) {
                    platformBlock.setType(Material.OBSIDIAN, false);
                }
                for (int y = 49; y <= 51; y++) {
                    Block airBlock = endWorld.getBlockAt(x, y, z);
                    if (!airBlock.getType().isAir()) {
                        airBlock.setType(Material.AIR, false);
                    }
                }
            }
        }
    }
}
