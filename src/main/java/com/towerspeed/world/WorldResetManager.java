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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
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

    private final TowerSpeedPlugin plugin;
    private final NamespacedKey activeWorldUidKey;
    private final NamespacedKey hardcoreDeadWorldUidKey;
    private final Map<UUID, SavedSpeedrunState> savedRunStates = new HashMap<>();

    // Each reset increments worldIndex and uses a fresh directory (e.g. speedrun_1, speedrun_2)
    // so Windows file locks on old region/level.dat files can never cause Bukkit to reuse the old seed.
    private int worldIndex = 0;
    private String activeOverworldName = "speedrun_1";
    private String activeNetherName = "speedrun_1_nether";
    private String activeEndName = "speedrun_1_the_end";

    private boolean resetInProgress = false;
    private boolean worldFreshlyPrepared = false;

    // Throttled chunk pre-generation state
    private boolean pregenInProgress = false;
    private int pregenTotalChunks = 0;
    private int pregenCompletedChunks = 0;
    private int pregenInFlight = 0;
    private long pregenSessionId = 0;
    private @Nullable BukkitTask pregenTask = null;

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
    }

    /**
     * Called on plugin startup to clean up any leftover unloaded speedrun world folders
     * from a previous session and begin background pre-generation of the initial speedrun world.
     */
    public void initializeOnStartup() {
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
        File container = Bukkit.getWorldContainer();
        File[] files = container.listFiles();
        if (files == null) return;

        for (File dir : files) {
            if (dir.isDirectory() && dir.getName().startsWith(SPEEDRUN_PREFIX)) {
                if (Bukkit.getWorld(dir.getName()) == null) {
                    deleteDirectoryWithRetry(dir.toPath(), 3, 100L);
                }
            }
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
        String name = world.getName();
        return name.equals(activeOverworldName)
                || name.equals(activeNetherName)
                || name.equals(activeEndName)
                || name.startsWith(SPEEDRUN_PREFIX);
    }

    public World getLobbyWorld() {
        return Bukkit.getWorlds().getFirst();
    }

    public Location getLobbySpawn() {
        return getLobbyWorld().getSpawnLocation();
    }

    /**
     * Executes /timer worldreset:
     * 1. Cancels any active pre-generation and locks state to prevent concurrent resets or starts.
     * 2. Resets the timer and evacuates all players to the lobby in SURVIVAL mode.
     * 3. Unloads the previous speedrun dimensions after chunk tickets release.
     * 4. Immediately generates a brand-new speedrun_<N> Overworld with a guaranteed fresh random seed,
     *    while deleting the old world directories asynchronously in the background.
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

        List<String> oldWorldNames = List.of(activeEndName, activeNetherName, activeOverworldName);

        // Disable auto-save and spawn chunk retention on existing speedrun worlds
        for (String worldName : oldWorldNames) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                cancelEnderPearlsInWorld(world);
                world.setAutoSave(false);
                world.setKeepSpawnInMemory(false);
                world.removePluginChunkTickets(plugin);
            }
        }

        // Wait 2 ticks so Paper processes player teleports and releases player chunk tickets before unloading
        Bukkit.getScheduler().runTaskLater(plugin, () -> unloadOldAndCreateNewWorld(oldWorldNames, 0), 2L);
    }

    private void unloadOldAndCreateNewWorld(List<String> oldWorldNames, int attempt) {
        // Ensure no player snuck into or remained in the speedrun worlds (including dead players on the respawn screen)
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead() || isSpeedrunWorld(player.getWorld())) {
                evacuatePlayerToLobby(player);
            }
        }

        boolean allUnloaded = true;
        for (String worldName : oldWorldNames) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                boolean unloaded = Bukkit.unloadWorld(world, false);
                if (!unloaded) {
                    allUnloaded = false;
                    plugin.getLogger().warning("Could not unload world '" + worldName + "' on attempt " + (attempt + 1));
                }
            }
        }

        if (!allUnloaded && attempt < 3) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> unloadOldAndCreateNewWorld(oldWorldNames, attempt + 1), 5L);
            return;
        }

        // Schedule asynchronous deletion of the old world directories after 40 ticks (2 seconds)
        // so Paper's Moonrise RegionFile I/O threads have completely closed all .mca handles on Windows.
        File worldContainer = Bukkit.getWorldContainer();
        List<Path> dirsToDelete = oldWorldNames.stream()
                .map(name -> new File(worldContainer, name).toPath())
                .toList();

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            for (Path dir : dirsToDelete) {
                if (Files.exists(dir)) {
                    deleteDirectoryWithRetry(dir, 8, 300L);
                }
            }
        }, 40L);

        // Immediately create the new Overworld in a fresh directory (speedrun_<next>) on the main thread
        try {
            World newOverworld = createNextSpeedrunOverworld();
            if (newOverworld != null) {
                resetInProgress = false;
                startSpawnPreGeneration(newOverworld, true);
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

                overworld.getChunkAtAsync(cx, cz, true).whenComplete((chunk, throwable) -> {
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
     * are saved at the lobby spawn.
     */
    public void shutdownCleanup() {
        cancelPreGeneration();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isSpeedrunWorld(player.getWorld())) {
                evacuatePlayerToLobby(player);
            }
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
     * Advances to a fresh world directory name (speedrun_1, speedrun_2, ...) and creates a new Overworld
     * with a guaranteed new random seed.
     */
    private @Nullable World createNextSpeedrunOverworld() {
        File container = Bukkit.getWorldContainer();
        do {
            worldIndex++;
            activeOverworldName = SPEEDRUN_PREFIX + "_" + worldIndex;
            activeNetherName = activeOverworldName + "_nether";
            activeEndName = activeOverworldName + "_the_end";
        } while (new File(container, activeOverworldName).exists() || Bukkit.getWorld(activeOverworldName) != null);

        long seed = ThreadLocalRandom.current().nextLong();
        World lobby = getLobbyWorld();
        boolean hardcore = lobby.isHardcore();

        plugin.getLogger().info("Creating new speedrun Overworld '" + activeOverworldName + "' with seed: " + seed);

        WorldCreator creator = new WorldCreator(activeOverworldName)
                .environment(World.Environment.NORMAL)
                .seed(seed)
                .hardcore(hardcore)
                .keepSpawnLoaded(TriState.FALSE);
        World world = Bukkit.createWorld(creator);
        if (world != null) {
            world.setTime(0L);
            world.setStorm(false);
            world.setThundering(false);
            world.setDifficulty(lobby.getDifficulty());
            world.setHardcore(hardcore);
        }
        return world;
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

        boolean hardcore = overworld.isHardcore();
        WorldCreator creator = new WorldCreator(activeNetherName)
                .environment(World.Environment.NETHER)
                .seed(overworld.getSeed())
                .hardcore(hardcore)
                .keepSpawnLoaded(TriState.FALSE);
        nether = Bukkit.createWorld(creator);
        if (nether != null) {
            nether.setDifficulty(overworld.getDifficulty());
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

        boolean hardcore = overworld.isHardcore();
        WorldCreator creator = new WorldCreator(activeEndName)
                .environment(World.Environment.THE_END)
                .seed(overworld.getSeed())
                .hardcore(hardcore)
                .keepSpawnLoaded(TriState.FALSE);
        theEnd = Bukkit.createWorld(creator);
        if (theEnd != null) {
            theEnd.setDifficulty(overworld.getDifficulty());
            theEnd.setHardcore(hardcore);
        }
        return theEnd;
    }

    /**
     * Evacuates a player to the lobby world in SURVIVAL mode and clears any run-specific tags.
     */
    public void evacuatePlayerToLobby(Player player) {
        if (player.isDead()) {
            player.spigot().respawn();
        }
        if (player.isSleeping()) {
            player.wakeup(false);
        }
        player.leaveVehicle();
        cancelEnderPearlsByPlayer(player);

        player.setGameMode(GameMode.SURVIVAL);
        player.setFallDistance(0f);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setVelocity(new Vector(0, 0, 0));
        restoreFullHealthAndFood(player);

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

    private void deleteDirectoryWithRetry(Path rootPath, int maxAttempts, long delayMs) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (!Files.exists(rootPath)) {
                    return;
                }
                try (Stream<Path> walk = Files.walk(rootPath)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
                }
                if (!Files.exists(rootPath)) {
                    return;
                }
            } catch (IOException ignored) {
            }

            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (Files.exists(rootPath)) {
            plugin.getLogger().warning("Some files in " + rootPath + " could not be deleted after " + maxAttempts + " attempts (will be cleaned up on next startup).");
        }
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
        if (resetInProgress || pregenInProgress || plugin.getTimerManager().getState() == TimerState.IDLE) {
            event.setRespawnLocation(getLobbySpawn());
            return;
        }

        World overworld = getActiveOverworld();
        if (overworld == null) {
            return;
        }

        Player player = event.getPlayer();

        // In Hardcore mode, spawn the spectating player near a living teammate in the speedrun worlds (or at speedrun spawn)
        if (overworld.isHardcore()) {
            event.setRespawnLocation(findLivingTeammateOrSpawn(player, overworld));
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
