package com.towerspeed.timer;

import com.towerspeed.TowerSpeedPlugin;
import com.towerspeed.scoreboard.LiveSplitScoreboard;
import com.towerspeed.storage.RecordStorage;
import com.towerspeed.util.TimeFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;

/**
 * Coordinates the shared co-op speedrun timer, countdowns, splits, and records.
 */
public class TimerManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final TowerSpeedPlugin plugin;
    private final RecordStorage recordStorage;
    private final LiveSplitScoreboard scoreboard;

    private final Map<String, Category> categories = new HashMap<>();
    private Category currentCategory;

    private TimerState state = TimerState.IDLE;
    private long startTime = 0;
    private long elapsedBeforePause = 0;
    private final List<Split> recordedSplits = new ArrayList<>();
    private RunRecord lastFinishedRecord = null;

    private BukkitTask tickTask;
    private BukkitTask countdownTask;

    public TimerManager(TowerSpeedPlugin plugin, RecordStorage recordStorage, LiveSplitScoreboard scoreboard) {
        this.plugin = plugin;
        this.recordStorage = recordStorage;
        this.scoreboard = scoreboard;
        loadFromConfig();
    }

    public void loadFromConfig() {
        categories.clear();
        var sec = plugin.getConfig().getConfigurationSection("categories");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String displayName = sec.getString(key + ".display-name", key);
                List<String> splits = sec.getStringList(key + ".splits");
                categories.put(key.toLowerCase(), new Category(key.toLowerCase(), displayName, splits));
            }
        }

        String activeKey = plugin.getConfig().getString("active-category", "tower_climb").toLowerCase();
        this.currentCategory = categories.getOrDefault(activeKey, new Category("default", "<gold>Speedrun</gold>", List.of("Finish")));
    }

    public void startTicking() {
        long interval = plugin.getConfig().getLong("update-interval-ticks", 2);
        this.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> scoreboard.update(this), interval, interval);
    }

    public void stopTicking() {
        if (tickTask != null && !tickTask.isCancelled()) {
            tickTask.cancel();
        }
        if (countdownTask != null && !countdownTask.isCancelled()) {
            countdownTask.cancel();
        }
    }

    public synchronized void start(CommandSender initiator, boolean instant) {
        if (state == TimerState.RUNNING || state == TimerState.COUNTDOWN) {
            initiator.sendMessage(MM.deserialize("<red>Timer is already running or counting down!</red>"));
            return;
        }

        int countdownSec = instant ? 0 : plugin.getConfig().getInt("countdown-seconds", 3);
        if (countdownSec <= 0) {
            beginRunning(initiator.getName());
            return;
        }

        state = TimerState.COUNTDOWN;
        broadcast(MM.deserialize("<yellow>[Timer] Starting countdown in " + countdownSec + "s (by " + initiator.getName() + ")...</yellow>"));

        final int[] remaining = {countdownSec};
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state != TimerState.COUNTDOWN) {
                countdownTask.cancel();
                return;
            }

            if (remaining[0] > 0) {
                Component titleComp = MM.deserialize("<gold><bold>" + remaining[0] + "</bold></gold>");
                Title title = Title.title(titleComp, Component.empty(),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(700), Duration.ofMillis(200)));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(title);
                    if (plugin.getConfig().getBoolean("play-sounds", true)) {
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    }
                }
                remaining[0]--;
            } else {
                countdownTask.cancel();
                beginRunning(initiator.getName());
            }
        }, 0L, 20L);
    }

    private void beginRunning(String starter) {
        state = TimerState.RUNNING;
        startTime = System.currentTimeMillis();
        elapsedBeforePause = 0;
        recordedSplits.clear();
        lastFinishedRecord = null;

        Component goTitle = MM.deserialize("<green><bold>GO!</bold></green>");
        Title title = Title.title(goTitle, MM.deserialize("<gray>Co-op Timer Started</gray>"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1000), Duration.ofMillis(300)));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            if (plugin.getConfig().getBoolean("play-sounds", true)) {
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }
        }
        broadcast(MM.deserialize("<green><bold>[Timer]</bold> Run started by <yellow>" + starter + "</yellow>! Good luck team!</green>"));
    }

    public synchronized void split(CommandSender initiator, String customSplitName) {
        if (state != TimerState.RUNNING) {
            initiator.sendMessage(MM.deserialize("<red>Cannot record a split: the timer is not currently running!</red>"));
            return;
        }

        long currentElapsed = getElapsedTime();
        long previousCumulative = recordedSplits.isEmpty() ? 0 : recordedSplits.get(recordedSplits.size() - 1).cumulativeTimeMs();
        long segmentTime = currentElapsed - previousCumulative;

        int splitIndex = recordedSplits.size();
        List<String> splitNames = currentCategory.splitNames();
        String name;

        if (customSplitName != null && !customSplitName.isBlank()) {
            name = customSplitName;
        } else if (splitIndex < splitNames.size()) {
            name = splitNames.get(splitIndex);
        } else {
            name = "Split #" + (splitIndex + 1);
        }

        Split newSplit = new Split(name, currentElapsed, segmentTime);
        recordedSplits.add(newSplit);

        // Check delta vs server record
        RunRecord bestRun = recordStorage.getBestRun(currentCategory.id());
        Split pbSplit = (bestRun != null) ? bestRun.getSplit(splitIndex) : null;
        boolean isGold = recordStorage.isGoldSegment(currentCategory.id(), name, segmentTime);

        StringBuilder sb = new StringBuilder("<gold>[Split " + (splitIndex + 1) + "]</gold> <yellow>" + name + "</yellow> - <white>" + TimeFormatter.formatTime(currentElapsed) + "</white>");
        if (pbSplit != null) {
            long delta = currentElapsed - pbSplit.cumulativeTimeMs();
            String deltaStr = TimeFormatter.formatDelta(delta);
            if (isGold) {
                sb.append(" <gold><bold>(").append(deltaStr).append(" ★ GOLD)</bold></gold>");
            } else if (delta < 0) {
                sb.append(" <green>(").append(deltaStr).append(")</green>");
            } else {
                sb.append(" <red>(").append(deltaStr).append(")</red>");
            }
        } else if (isGold) {
            sb.append(" <gold><bold>★ GOLD SEGMENT</bold></gold>");
        }

        Component splitMsg = MM.deserialize(sb.toString());
        broadcast(splitMsg);

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendActionBar(splitMsg);
            if (plugin.getConfig().getBoolean("play-sounds", true)) {
                p.playSound(p.getLocation(), isGold ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, isGold ? 1.5f : 1.2f);
            }
        }

        // Check if final split reached
        if (recordedSplits.size() >= splitNames.size()) {
            finishRun();
        }
    }

    public synchronized void pause(CommandSender initiator) {
        if (state != TimerState.RUNNING) {
            initiator.sendMessage(MM.deserialize("<red>Timer is not running, cannot pause.</red>"));
            return;
        }
        elapsedBeforePause += System.currentTimeMillis() - startTime;
        state = TimerState.PAUSED;
        broadcast(MM.deserialize("<gold>[Timer] Paused by " + initiator.getName() + " at " + TimeFormatter.formatTime(elapsedBeforePause) + ".</gold>"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.getConfig().getBoolean("play-sounds", true)) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.8f);
            }
        }
    }

    public synchronized void resume(CommandSender initiator) {
        if (state != TimerState.PAUSED) {
            initiator.sendMessage(MM.deserialize("<red>Timer is not paused.</red>"));
            return;
        }
        startTime = System.currentTimeMillis();
        state = TimerState.RUNNING;
        broadcast(MM.deserialize("<green>[Timer] Resumed by " + initiator.getName() + "!</green>"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.getConfig().getBoolean("play-sounds", true)) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f);
            }
        }
    }

    public synchronized void reset(CommandSender initiator) {
        if (state == TimerState.COUNTDOWN && countdownTask != null) {
            countdownTask.cancel();
        }
        state = TimerState.IDLE;
        startTime = 0;
        elapsedBeforePause = 0;
        recordedSplits.clear();
        lastFinishedRecord = null;
        broadcast(MM.deserialize("<red>[Timer] Reset by " + initiator.getName() + ".</red>"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.getConfig().getBoolean("play-sounds", true)) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_SNARE, 1f, 0.5f);
            }
        }
    }

    public synchronized void unsplit(CommandSender initiator) {
        if (state == TimerState.IDLE || state == TimerState.COUNTDOWN) {
            initiator.sendMessage(MM.deserialize("<red>Timer is not currently running!</red>"));
            return;
        }

        if (recordedSplits.isEmpty()) {
            initiator.sendMessage(MM.deserialize("<red>No splits have been recorded yet to undo!</red>"));
            return;
        }

        if (state == TimerState.FINISHED) {
            // Revert run completion and remove the saved run record if it made the leaderboard
            if (lastFinishedRecord != null) {
                List<RunRecord> topRuns = recordStorage.getTopRuns(currentCategory.id());
                int index = topRuns.indexOf(lastFinishedRecord);
                if (index != -1) {
                    recordStorage.deleteRecord(currentCategory.id(), index + 1);
                }
                lastFinishedRecord = null;
            }
            state = TimerState.RUNNING;
            startTime = System.currentTimeMillis();
        }

        Split removed = recordedSplits.remove(recordedSplits.size() - 1);
        broadcast(MM.deserialize("<yellow>[Timer] Split <white>" + removed.name() + "</white> undone by " + initiator.getName() + ".</yellow>"));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendActionBar(MM.deserialize("<yellow>Undid split: <white>" + removed.name() + "</white></yellow>"));
            if (plugin.getConfig().getBoolean("play-sounds", true)) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1.2f);
            }
        }
    }

    private void finishRun() {
        long totalElapsed = !recordedSplits.isEmpty()
                ? recordedSplits.get(recordedSplits.size() - 1).cumulativeTimeMs()
                : getElapsedTime();
        elapsedBeforePause = totalElapsed;
        state = TimerState.FINISHED;

        List<String> teamPlayerNames = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        RunRecord record = new RunRecord(
                currentCategory.id(),
                totalElapsed,
                new ArrayList<>(recordedSplits),
                teamPlayerNames,
                System.currentTimeMillis()
        );
        this.lastFinishedRecord = record;

        RunRecord previousPB = recordStorage.getBestRun(currentCategory.id());
        int rank = recordStorage.processCompletedRun(record);

        broadcast(MM.deserialize("<gray>======================================</gray>"));
        broadcast(MM.deserialize("<green><bold>★ RUN FINISHED! ★</bold></green>"));
        broadcast(MM.deserialize("<white>Total Time: </white><yellow><bold>" + TimeFormatter.formatTime(totalElapsed) + "</bold></yellow>"));

        if (rank == 1) {
            String deltaStr = previousPB != null ? " (" + TimeFormatter.formatDelta(totalElapsed - previousPB.totalTimeMs()) + ")" : "";
            broadcast(MM.deserialize("<gold><bold>★ NEW SERVER RECORD / PB! ★</bold></gold><green>" + deltaStr + "</green>"));

            Title victoryTitle = Title.title(
                    MM.deserialize("<gold><bold>★ NEW RECORD! ★</bold></gold>"),
                    MM.deserialize("<yellow>" + TimeFormatter.formatTime(totalElapsed) + deltaStr + "</yellow>"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(3000), Duration.ofMillis(500))
            );

            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(victoryTitle);
                if (plugin.getConfig().getBoolean("play-sounds", true)) {
                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                }
                if (plugin.getConfig().getBoolean("celebration-fireworks", true)) {
                    spawnFirework(p);
                }
            }
        } else if (rank > 1 && rank <= 5) {
            broadcast(MM.deserialize("<yellow><bold>★ TOP 5 FINISH! Rank #" + rank + " on the Leaderboard! ★</bold></yellow>"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (plugin.getConfig().getBoolean("play-sounds", true)) {
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                }
            }
        } else {
            if (previousPB != null) {
                long diff = totalElapsed - previousPB.totalTimeMs();
                broadcast(MM.deserialize("<gray>Missed PB by: <red>+" + TimeFormatter.formatDelta(diff) + "</red></gray>"));
            }
        }
        broadcast(MM.deserialize("<gray>======================================</gray>"));
    }

    private void spawnFirework(Player player) {
        Firework fw = (Firework) player.getWorld().spawnEntity(player.getLocation(), EntityType.FIREWORK_ROCKET);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .withColor(Color.YELLOW, Color.ORANGE, Color.LIME)
                .withFade(Color.WHITE)
                .with(FireworkEffect.Type.BALL_LARGE)
                .trail(true)
                .flicker(true)
                .build());
        meta.setPower(1);
        fw.setFireworkMeta(meta);
    }

    public synchronized void setCategory(String categoryKey, CommandSender sender) {
        Category cat = categories.get(categoryKey.toLowerCase());
        if (cat == null) {
            sender.sendMessage(MM.deserialize("<red>Unknown category: " + categoryKey + ". Available: " + String.join(", ", categories.keySet()) + "</red>"));
            return;
        }
        if (state == TimerState.RUNNING || state == TimerState.COUNTDOWN) {
            sender.sendMessage(MM.deserialize("<red>Cannot change category while a run is active! Reset first.</red>"));
            return;
        }
        this.currentCategory = cat;
        reset(sender);
        broadcast(MM.deserialize("<yellow>[Timer] Category set to: " + cat.displayName() + "</yellow>"));
    }

    public long getElapsedTime() {
        if (state == TimerState.RUNNING) {
            return elapsedBeforePause + (System.currentTimeMillis() - startTime);
        }
        return elapsedBeforePause;
    }

    public TimerState getState() {
        return state;
    }

    public Category getCurrentCategory() {
        return currentCategory;
    }

    public List<Split> getRecordedSplits() {
        return Collections.unmodifiableList(recordedSplits);
    }

    public RecordStorage getRecordStorage() {
        return recordStorage;
    }

    public Map<String, Category> getCategories() {
        return Collections.unmodifiableMap(categories);
    }

    public void broadcast(Component component) {
        Bukkit.broadcast(component);
    }
}
