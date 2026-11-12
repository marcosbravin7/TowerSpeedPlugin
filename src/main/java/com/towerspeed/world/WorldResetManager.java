package com.towerspeed.world;

import com.towerspeed.TowerSpeedPlugin;
import com.towerspeed.timer.TimerManager;
import com.towerspeed.timer.TimerState;
import net.kyori.adventure.text.Component;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
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
 * safe world unloading/deletion, player state resets, portal linking, and join/leave synchronization.
 */
public class WorldResetManager implements Listener {

    public static final String SPEEDRUN_OVERWORLD = "speedrun";
    public static final String SPEEDRUN_NETHER = "speedrun_nether";
    public static final String SPEEDRUN_THE_END = "speedrun_the_end";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final TowerSpeedPlugin plugin;
    private final NamespacedKey activeWorldUidKey;

    private boolean resetInProgress = false;
    private boolean worldFreshlyPrepared = false;

    public WorldResetManager(TowerSpeedPlugin plugin) {
        this.plugin = plugin;
        this.activeWorldUidKey = new NamespacedKey(plugin, "active_world_uid");
    }

    /**
     * Called on plugin startup to clean up any leftover unloaded speedrun world folders
     * from a previous session so the first run starts clean.
     */
    public void initializeOnStartup() {
        if (Bukkit.getWorld(SPEEDRUN_OVERWORLD) == null) {
            File container = Bukkit.getWorldContainer();
            for (String name : List.of(SPEEDRUN_OVERWORLD, SPEEDRUN_NETHER, SPEEDRUN_THE_END)) {
                File dir = new File(container, name);
                if (dir.exists()) {
                    deleteDirectoryWithRetry(dir.toPath(), 3);
                }
            }
        }
    }

    public boolean isResetInProgress() {
        return resetInProgress;
    }

    public boolean isSpeedrunWorld(@Nullable World world) {
        if (world == null) return false;
        String name = world.getName();
        return name.equals(SPEEDRUN_OVERWORLD)
                || name.equals(SPEEDRUN_NETHER)
                || name.equals(SPEEDRUN_THE_END);
    }

    public World getLobbyWorld() {
        return Bukkit.getWorlds().getFirst();
    }

    public Location getLobbySpawn() {
        return getLobbyWorld().getSpawnLocation();
    }

    /**
     * Executes /timer worldreset:
     * 1. Locks state to prevent concurrent resets or starts.
     * 2. Resets the timer and evacuates all players to the lobby.
     * 3. Unloads speedrun dimensions after chunk tickets release.
     * 4. Deletes world folders asynchronously and creates a fresh Overworld on the main thread.
     */
    public void triggerWorldReset(CommandSender initiator, TimerManager timerManager) {
        if (resetInProgress) {
            initiator.sendMessage(MM.deserialize("<red>[Timer] A world reset is already in progress! Please wait...</red>"));
            return;
        }

        resetInProgress = true;
        worldFreshlyPrepared = false;

        // Reset the timer if it isn't already idle
        if (timerManager.getState() != TimerState.IDLE) {
            timerManager.reset(initiator);
        }

        Bukkit.broadcast(MM.deserialize("<gold><bold>[Timer]</bold> <yellow>" + initiator.getName()
                + "</yellow> initiated a world reset! Returning to lobby...</gold>"));

        Title resettingTitle = Title.title(
                MM.deserialize("<gold><bold>RESETTING WORLD...</bold></gold>"),
                MM.deserialize("<gray>Please wait in the lobby</gray>"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(3000), Duration.ofMillis(300))
        );

        // Evacuate all online players to the lobby immediately
        for (Player player : Bukkit.getOnlinePlayers()) {
            evacuatePlayerToLobby(player);
            player.showTitle(resettingTitle);
        }

        // Disable auto-save and spawn chunk retention on existing speedrun worlds
        for (String worldName : List.of(SPEEDRUN_THE_END, SPEEDRUN_NETHER, SPEEDRUN_OVERWORLD)) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                cancelEnderPearlsInWorld(world);
                world.setAutoSave(false);
                world.setKeepSpawnInMemory(false);
                world.removePluginChunkTickets(plugin);
            }
        }

        // Wait 2 ticks so Paper processes player teleports and releases player chunk tickets before unloading
        Bukkit.getScheduler().runTaskLater(plugin, () -> unloadAndRegenerateWorlds(0), 2L);
    }

    private void unloadAndRegenerateWorlds(int attempt) {
        // Ensure no player snuck into or remained in the speedrun worlds (including dead players on the respawn screen)
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead() || isSpeedrunWorld(player.getWorld())) {
                evacuatePlayerToLobby(player);
            }
        }

        boolean allUnloaded = true;
        for (String worldName : List.of(SPEEDRUN_THE_END, SPEEDRUN_NETHER, SPEEDRUN_OVERWORLD)) {
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
            Bukkit.getScheduler().runTaskLater(plugin, () -> unloadAndRegenerateWorlds(attempt + 1), 5L);
            return;
        }

        File worldContainer = Bukkit.getWorldContainer();
        List<Path> dirsToDelete = List.of(
                new File(worldContainer, SPEEDRUN_OVERWORLD).toPath(),
                new File(worldContainer, SPEEDRUN_NETHER).toPath(),
                new File(worldContainer, SPEEDRUN_THE_END).toPath()
        );

        // Delete world directories asynchronously to avoid blocking the main thread on disk I/O
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (Path dir : dirsToDelete) {
                if (Files.exists(dir)) {
                    deleteDirectoryWithRetry(dir, 6);
                }
            }

            // Return to the main server thread to generate the new Overworld
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled()) return;
                try {
                    World newOverworld = createSpeedrunOverworld();
                    if (newOverworld != null) {
                        worldFreshlyPrepared = true;
                        resetInProgress = false;

                        Title readyTitle = Title.title(
                                MM.deserialize("<green><bold>WORLD READY!</bold></green>"),
                                MM.deserialize("<yellow>Use /timer start to begin</yellow>"),
                                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(400))
                        );

                        Bukkit.broadcast(MM.deserialize("<green><bold>[Timer]</bold> New speedrun world generated! Run <yellow>/timer start</yellow> when everyone is ready.</green>"));
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            p.showTitle(readyTitle);
                            if (plugin.getConfig().getBoolean("play-sounds", true)) {
                                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
                            }
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
            });
        });
    }

    /**
     * Called by TimerManager right when /timer start is executed.
     * Ensures the speedrun Overworld exists, teleports players from the lobby into the speedrun spawn,
     * and performs a full vanilla speedrun reset on players starting the new world.
     */
    public boolean prepareWorldAndPlayersForStart(CommandSender initiator) {
        if (resetInProgress) {
            initiator.sendMessage(MM.deserialize("<red>[Timer] Cannot start the timer while a world reset is in progress!</red>"));
            return false;
        }

        World overworld = Bukkit.getWorld(SPEEDRUN_OVERWORLD);
        if (overworld == null) {
            Bukkit.broadcast(MM.deserialize("<yellow>[Timer] Generating speedrun world for first run, please wait...</yellow>"));
            overworld = createSpeedrunOverworld();
            if (overworld == null) {
                initiator.sendMessage(MM.deserialize("<red>[Timer] Could not create speedrun world!</red>"));
                return false;
            }
            worldFreshlyPrepared = true;
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
     * Keeping worldFreshlyPrepared true until GO! ensures that if a countdown is cancelled
     * via /timer reset before GO!, the next /timer start still re-teleports and resets everyone at spawn.
     */
    public void markRunBegan() {
        this.worldFreshlyPrepared = false;
    }

    /**
     * Called on plugin disable/server shutdown so players in temporary speedrun worlds
     * are saved at the lobby spawn.
     */
    public void shutdownCleanup() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isSpeedrunWorld(player.getWorld())) {
                evacuatePlayerToLobby(player);
            }
        }
    }

    private @Nullable World createSpeedrunOverworld() {
        long seed = ThreadLocalRandom.current().nextLong();
        WorldCreator creator = new WorldCreator(SPEEDRUN_OVERWORLD)
                .environment(World.Environment.NORMAL)
                .seed(seed);
        World world = Bukkit.createWorld(creator);
        if (world != null) {
            world.setTime(0L);
            world.setStorm(false);
            world.setThundering(false);
            world.setDifficulty(getLobbyWorld().getDifficulty());
        }
        return world;
    }

    public @Nullable World getOrCreateNether() {
        World nether = Bukkit.getWorld(SPEEDRUN_NETHER);
        if (nether != null) {
            return nether;
        }
        World overworld = Bukkit.getWorld(SPEEDRUN_OVERWORLD);
        if (overworld == null) {
            return null;
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendActionBar(MM.deserialize("<gold>Generating Nether dimension...</gold>"));
        }

        WorldCreator creator = new WorldCreator(SPEEDRUN_NETHER)
                .environment(World.Environment.NETHER)
                .seed(overworld.getSeed())
                .keepSpawnLoaded(TriState.FALSE);
        nether = Bukkit.createWorld(creator);
        if (nether != null) {
            nether.setDifficulty(overworld.getDifficulty());
        }
        return nether;
    }

    public @Nullable World getOrCreateTheEnd() {
        World theEnd = Bukkit.getWorld(SPEEDRUN_THE_END);
        if (theEnd != null) {
            return theEnd;
        }
        World overworld = Bukkit.getWorld(SPEEDRUN_OVERWORLD);
        if (overworld == null) {
            return null;
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendActionBar(MM.deserialize("<light_purple>Generating The End dimension...</light_purple>"));
        }

        WorldCreator creator = new WorldCreator(SPEEDRUN_THE_END)
                .environment(World.Environment.THE_END)
                .seed(overworld.getSeed())
                .keepSpawnLoaded(TriState.FALSE);
        theEnd = Bukkit.createWorld(creator);
        if (theEnd != null) {
            theEnd.setDifficulty(overworld.getDifficulty());
        }
        return theEnd;
    }

    /**
     * Evacuates a player to the lobby world and clears any run-specific tags so they are clean for the next start.
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

        player.setFallDistance(0f);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setVelocity(new Vector(0, 0, 0));
        restoreFullHealthAndFood(player);

        player.getPersistentDataContainer().remove(activeWorldUidKey);

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

        // Record the active speedrun world UID on the player so mid-run reconnects preserve state
        player.getPersistentDataContainer().set(
                activeWorldUidKey,
                PersistentDataType.STRING,
                speedrunOverworld.getUID().toString()
        );
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

    private void deleteDirectoryWithRetry(Path rootPath, int maxAttempts) {
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
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (Files.exists(rootPath)) {
            plugin.getLogger().warning("Some files in " + rootPath + " could not be deleted after " + maxAttempts + " attempts.");
        }
    }

    // =========================================================================
    // Event Handlers: Join, Quit, Respawn, and Portal Linking
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        TimerManager timerManager = plugin.getTimerManager();
        TimerState state = timerManager.getState();

        // If a world reset is running or no run is active (IDLE), keep everyone in the lobby
        if (resetInProgress || state == TimerState.IDLE) {
            evacuatePlayerToLobby(player);
            return;
        }

        World overworld = Bukkit.getWorld(SPEEDRUN_OVERWORLD);
        if (overworld == null) {
            evacuatePlayerToLobby(player);
            return;
        }

        // If a run is active (COUNTDOWN, RUNNING, PAUSED) or FINISHED:
        if (isPlayerPreparedForWorld(player, overworld)) {
            // Player is reconnecting to the SAME active run they were already part of.
            // Only teleport them to speedrun spawn if Paper placed them in the lobby (e.g. if their dimension unloaded).
            if (!isSpeedrunWorld(player.getWorld())) {
                player.teleport(overworld.getSpawnLocation());
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

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (resetInProgress || plugin.getTimerManager().getState() == TimerState.IDLE) {
            event.setRespawnLocation(getLobbySpawn());
            return;
        }

        World overworld = Bukkit.getWorld(SPEEDRUN_OVERWORLD);
        if (overworld == null) {
            return;
        }

        // If the player has a valid bed or respawn anchor inside the speedrun worlds, honor it
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
            if (fromWorld.getName().equals(SPEEDRUN_OVERWORLD)) {
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
            } else if (fromWorld.getName().equals(SPEEDRUN_NETHER)) {
                World overworld = Bukkit.getWorld(SPEEDRUN_OVERWORLD);
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
            if (fromWorld.getName().equals(SPEEDRUN_OVERWORLD)) {
                World theEnd = getOrCreateTheEnd();
                if (theEnd == null) {
                    event.setCancelled(true);
                    return;
                }
                buildEndObsidianPlatform(theEnd);
                event.setTo(new Location(theEnd, 100.5, 49.0, 0.5, 90.0f, 0.0f));
                event.setCanCreatePortal(false);
            } else if (fromWorld.getName().equals(SPEEDRUN_THE_END)) {
                // Returning from The End exit portal to the speedrun Overworld
                World overworld = Bukkit.getWorld(SPEEDRUN_OVERWORLD);
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
            if (fromWorld.getName().equals(SPEEDRUN_OVERWORLD)) {
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
            } else if (fromWorld.getName().equals(SPEEDRUN_NETHER)) {
                World overworld = Bukkit.getWorld(SPEEDRUN_OVERWORLD);
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
            if (fromWorld.getName().equals(SPEEDRUN_OVERWORLD)) {
                World theEnd = getOrCreateTheEnd();
                if (theEnd == null) {
                    event.setCancelled(true);
                    return;
                }
                buildEndObsidianPlatform(theEnd);
                event.setTo(new Location(theEnd, 100.5, 49.0, 0.5, 90.0f, 0.0f));
                event.setCanCreatePortal(false);
            } else if (fromWorld.getName().equals(SPEEDRUN_THE_END)) {
                World overworld = Bukkit.getWorld(SPEEDRUN_OVERWORLD);
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
