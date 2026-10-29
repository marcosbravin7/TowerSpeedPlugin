package com.towerspeed;

import com.towerspeed.command.SplitCommand;
import com.towerspeed.command.TimerCommand;
import com.towerspeed.scoreboard.LiveSplitScoreboard;
import com.towerspeed.storage.RecordStorage;
import com.towerspeed.timer.TimerManager;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class TowerSpeedPlugin extends JavaPlugin implements Listener {

    private RecordStorage recordStorage;
    private LiveSplitScoreboard scoreboard;
    private TimerManager timerManager;

    @Override
    public void onEnable() {
        // Save default config.yml if not already present
        saveDefaultConfig();

        // Initialize components
        this.recordStorage = new RecordStorage(getDataFolder(), getLogger());
        this.scoreboard = new LiveSplitScoreboard();
        this.timerManager = new TimerManager(this, recordStorage, scoreboard);

        // Register event listener for player join/quit
        getServer().getPluginManager().registerEvents(this, this);

        // Register modern Paper Brigadier commands via LifecycleEvents
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register("timer", "Control the shared co-op speedrun timer", List.of("t"), new TimerCommand(this, timerManager, scoreboard));
            commands.register("split", "Quick command to trigger next split", List.of(), new SplitCommand(timerManager));
        });

        // Register scoreboards for all currently online players (e.g. on /reload)
        for (Player player : Bukkit.getOnlinePlayers()) {
            scoreboard.registerPlayer(player);
        }

        // Start scoreboard ticker
        timerManager.startTicking();

        getLogger().info("TowerSpeedPlugin enabled! Co-op LiveSplit timer is active.");
    }

    @Override
    public void onDisable() {
        if (timerManager != null) {
            timerManager.stopTicking();
        }
        if (recordStorage != null) {
            recordStorage.save();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        getLogger().info("TowerSpeedPlugin disabled.");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        scoreboard.registerPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        scoreboard.unregisterPlayer(event.getPlayer());
    }

    public TimerManager getTimerManager() {
        return timerManager;
    }

    public LiveSplitScoreboard getLiveSplitScoreboard() {
        return scoreboard;
    }

    public RecordStorage getRecordStorage() {
        return recordStorage;
    }
}
