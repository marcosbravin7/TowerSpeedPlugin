package com.towerspeed.scoreboard;

import com.towerspeed.timer.Category;
import com.towerspeed.timer.RunRecord;
import com.towerspeed.timer.Split;
import com.towerspeed.timer.TimerManager;
import com.towerspeed.timer.TimerState;
import com.towerspeed.util.TimeFormatter;
import com.towerspeed.world.WorldResetManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;

/**
 * Manages zero-flicker LiveSplit sidebar scoreboards for players in speedrun worlds
 * and a status BossBar for players waiting in the lobby.
 */
public class LiveSplitScoreboard {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int MAX_LINES = 15;

    private final WorldResetManager worldResetManager;
    private final ScoreboardManager scoreboardManager;
    private final Map<UUID, PlayerBoard> playerBoards = new HashMap<>();
    private final Set<UUID> disabledPlayers = new HashSet<>();

    private final BossBar lobbyBossBar;
    private final Set<UUID> bossBarViewers = new HashSet<>();

    public LiveSplitScoreboard(WorldResetManager worldResetManager) {
        this.worldResetManager = worldResetManager;
        this.scoreboardManager = Bukkit.getScoreboardManager();
        this.lobbyBossBar = BossBar.bossBar(
                MM.deserialize("<gold><bold>Lobby</bold></gold> <dark_gray>-</dark_gray> <green>Ready (/timer start)</green>"),
                1.0f,
                BossBar.Color.GREEN,
                BossBar.Overlay.PROGRESS
        );
    }

    public void registerPlayer(Player player) {
        boolean inLobby = !worldResetManager.isSpeedrunWorld(player.getWorld());
        if (inLobby) {
            if (bossBarViewers.add(player.getUniqueId())) {
                player.showBossBar(lobbyBossBar);
            }
        }

        if (disabledPlayers.contains(player.getUniqueId())) {
            return;
        }
        PlayerBoard board = new PlayerBoard(scoreboardManager.getNewScoreboard());
        board.setSidebarVisible(!inLobby);
        player.setScoreboard(board.scoreboard);
        playerBoards.put(player.getUniqueId(), board);
    }

    public void unregisterPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        playerBoards.remove(uuid);
        if (bossBarViewers.remove(uuid)) {
            player.hideBossBar(lobbyBossBar);
        }
    }

    public void cleanup() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(lobbyBossBar);
        }
        bossBarViewers.clear();
        playerBoards.clear();
    }

    public boolean togglePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        if (disabledPlayers.contains(uuid)) {
            disabledPlayers.remove(uuid);
            registerPlayer(player);
            return true;
        } else {
            disabledPlayers.add(uuid);
            playerBoards.remove(uuid);
            player.setScoreboard(scoreboardManager.getMainScoreboard());
            return false;
        }
    }

    /**
     * Updates the Lobby BossBar and LiveSplit sidebar scoreboards for all online players.
     */
    public void update(TimerManager timer) {
        updateLobbyBossBar(timer);

        Category category = timer.getCurrentCategory();
        RunRecord bestRun = timer.getRecordStorage().getBestRun(category.id());

        long elapsedMs = timer.getElapsedTime();
        List<Split> recordedSplits = timer.getRecordedSplits();
        int currentSplitIdx = recordedSplits.size();
        List<String> splitNames = category.splitNames();

        // Build line components (from top line 15 to bottom line 1)
        List<Component> lines = new ArrayList<>();

        lines.add(MM.deserialize("<gray><st>-------------------</st></gray>"));

        // Splits window calculation
        int totalSplits = splitNames.size();
        int maxSplitsToShow = 6;
        int startIndex = 0;
        int endIndex = Math.min(totalSplits, maxSplitsToShow);

        if (totalSplits > maxSplitsToShow) {
            startIndex = Math.max(0, currentSplitIdx - 2);
            endIndex = Math.min(totalSplits, startIndex + maxSplitsToShow);
            if (endIndex - startIndex < maxSplitsToShow) {
                startIndex = Math.max(0, endIndex - maxSplitsToShow);
            }
        }

        for (int i = startIndex; i < endIndex; i++) {
            String splitName = splitNames.get(i);
            if (splitName.length() > 10) {
                splitName = splitName.substring(0, 9) + "…";
            }

            if (i < currentSplitIdx) {
                // Completed split
                Split split = recordedSplits.get(i);
                Split pbSplit = (bestRun != null) ? bestRun.getSplit(i) : null;

                String lineText = "<gray>" + splitName + "</gray> <white>" + TimeFormatter.formatTime(split.cumulativeTimeMs()) + "</white>";
                if (pbSplit != null) {
                    long delta = split.cumulativeTimeMs() - pbSplit.cumulativeTimeMs();
                    boolean isGold = timer.getRecordStorage().isGoldSegment(category.id(), split.name(), split.segmentTimeMs());
                    String deltaStr = TimeFormatter.formatDelta(delta);
                    if (isGold) {
                        lineText += " <gold>" + deltaStr + "</gold>";
                    } else if (delta < 0) {
                        lineText += " <green>" + deltaStr + "</green>";
                    } else {
                        lineText += " <red>" + deltaStr + "</red>";
                    }
                }
                lines.add(MM.deserialize(lineText));
            } else if (i == currentSplitIdx && timer.getState() == TimerState.RUNNING) {
                // Active split currently in progress
                long segmentElapsed = elapsedMs - (recordedSplits.isEmpty() ? 0 : recordedSplits.get(recordedSplits.size() - 1).cumulativeTimeMs());
                lines.add(MM.deserialize("<yellow>▶ </yellow><white><bold>" + splitName + "</bold></white> <yellow>" + TimeFormatter.formatTime(segmentElapsed) + "</yellow>"));
            } else {
                // Future split
                Split pbSplit = (bestRun != null) ? bestRun.getSplit(i) : null;
                String target = (pbSplit != null) ? TimeFormatter.formatTime(pbSplit.cumulativeTimeMs()) : "--:--.-";
                lines.add(MM.deserialize("<dark_gray>" + splitName + " " + target + "</dark_gray>"));
            }
        }

        lines.add(MM.deserialize("<gray><st>-------------------</st></gray>"));

        // Live Total Time
        lines.add(MM.deserialize("<white>Time: </white><yellow><bold>" + TimeFormatter.formatTime(elapsedMs) + "</bold></yellow>"));

        // PB / Server Record
        if (bestRun != null) {
            lines.add(MM.deserialize("<white>Record: </white><aqua>" + TimeFormatter.formatTime(bestRun.totalTimeMs()) + "</aqua>"));
            long totalDelta = elapsedMs - bestRun.totalTimeMs();
            String deltaStr;
            if (totalDelta < 0) {
                deltaStr = "<green><bold>" + TimeFormatter.formatDelta(totalDelta) + "</bold></green>";
            } else if (totalDelta > 0) {
                deltaStr = "<red><bold>" + TimeFormatter.formatDelta(totalDelta) + "</bold></red>";
            } else {
                deltaStr = "<gray><bold>±00:00.0</bold></gray>";
            }
            lines.add(MM.deserialize("<white>Delta: </white>" + deltaStr));
        } else {
            lines.add(MM.deserialize("<white>Record: </white><dark_gray>No PB set</dark_gray>"));
            lines.add(MM.deserialize("<white>Delta: </white><dark_gray>--:--.-</dark_gray>"));
        }

        // Status
        lines.add(MM.deserialize("<white>Status: </white>" + timer.getState().getDisplayTag()));
        lines.add(MM.deserialize("<gray><st>-------------------</st></gray>"));

        Component title = MM.deserialize(category.displayName());

        // Update visibility and content for each online player based on whether they are in the lobby or speedrun world
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            boolean inLobby = !worldResetManager.isSpeedrunWorld(player.getWorld());
            PlayerBoard board = playerBoards.get(uuid);

            if (inLobby) {
                if (board != null) {
                    board.setSidebarVisible(false);
                }
                if (bossBarViewers.add(uuid)) {
                    player.showBossBar(lobbyBossBar);
                }
            } else {
                if (bossBarViewers.remove(uuid)) {
                    player.hideBossBar(lobbyBossBar);
                }
                if (board != null) {
                    board.setSidebarVisible(true);
                    board.updateLines(title, lines);
                }
            }
        }
    }

    private void updateLobbyBossBar(TimerManager timer) {
        if (worldResetManager.isResetInProgress()) {
            lobbyBossBar.name(MM.deserialize("<gold><bold>Lobby</bold></gold> <dark_gray>-</dark_gray> <red>Resetting World...</red>"));
            lobbyBossBar.color(BossBar.Color.RED);
            lobbyBossBar.progress(0.0f);
        } else if (worldResetManager.isPreGenInProgress()) {
            int pct = worldResetManager.getPreGenPercent();
            lobbyBossBar.name(MM.deserialize("<gold><bold>Lobby</bold></gold> <dark_gray>-</dark_gray> <yellow>Generating World (" + pct + "%)</yellow>"));
            lobbyBossBar.color(BossBar.Color.YELLOW);
            lobbyBossBar.progress(worldResetManager.getPreGenProgress());
        } else {
            TimerState state = timer.getState();
            if (state == TimerState.RUNNING || state == TimerState.COUNTDOWN) {
                lobbyBossBar.name(MM.deserialize("<gold><bold>Lobby</bold></gold> <dark_gray>-</dark_gray> <aqua>Run in Progress ("
                        + TimeFormatter.formatTime(timer.getElapsedTime()) + ")</aqua>"));
                lobbyBossBar.color(BossBar.Color.BLUE);
                lobbyBossBar.progress(1.0f);
            } else if (state == TimerState.PAUSED) {
                lobbyBossBar.name(MM.deserialize("<gold><bold>Lobby</bold></gold> <dark_gray>-</dark_gray> <yellow>Run Paused ("
                        + TimeFormatter.formatTime(timer.getElapsedTime()) + ")</yellow>"));
                lobbyBossBar.color(BossBar.Color.YELLOW);
                lobbyBossBar.progress(1.0f);
            } else {
                lobbyBossBar.name(MM.deserialize("<gold><bold>Lobby</bold></gold> <dark_gray>-</dark_gray> <green>Ready (/timer start)</green>"));
                lobbyBossBar.color(BossBar.Color.GREEN);
                lobbyBossBar.progress(1.0f);
            }
        }
    }

    /**
     * Internal wrapper for a player's individual flicker-free scoreboard.
     */
    private static class PlayerBoard {
        private static final String[] LINE_ENTRIES = {
                "§0§r", "§1§r", "§2§r", "§3§r", "§4§r",
                "§5§r", "§6§r", "§7§r", "§8§r", "§9§r",
                "§a§r", "§b§r", "§c§r", "§d§r", "§e§r"
        };

        private final Scoreboard scoreboard;
        private final Objective objective;
        private final Map<Integer, Team> lineTeams = new HashMap<>();
        private final String[] entryIdentifiers;
        private boolean sidebarVisible = true;

        public PlayerBoard(Scoreboard scoreboard) {
            this.scoreboard = scoreboard;
            this.objective = scoreboard.registerNewObjective("livesplit", Criteria.DUMMY, Component.text("Timer"));
            this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);

            this.entryIdentifiers = new String[MAX_LINES + 1];

            for (int i = 1; i <= MAX_LINES; i++) {
                String entry = LINE_ENTRIES[i - 1];
                entryIdentifiers[i] = entry;

                Team team = scoreboard.registerNewTeam("line_" + i);
                team.addEntry(entry);
                lineTeams.put(i, team);

                Score score = objective.getScore(entry);
                score.setScore(i);
            }
        }

        public void setSidebarVisible(boolean visible) {
            if (this.sidebarVisible == visible) {
                return;
            }
            this.sidebarVisible = visible;
            objective.setDisplaySlot(visible ? DisplaySlot.SIDEBAR : null);
        }

        public void updateLines(Component title, List<Component> contentLines) {
            objective.displayName(title);

            int totalGiven = contentLines.size();
            for (int lineIndex = 1; lineIndex <= MAX_LINES; lineIndex++) {
                Team team = lineTeams.get(lineIndex);
                String entry = entryIdentifiers[lineIndex];

                // Lines in scoreboard: line 15 is top, line 1 is bottom
                int contentIndex = totalGiven - lineIndex;
                if (contentIndex >= 0 && contentIndex < totalGiven) {
                    team.prefix(contentLines.get(contentIndex));
                    if (!objective.getScore(entry).isScoreSet()) {
                        objective.getScore(entry).setScore(lineIndex);
                    }
                } else {
                    team.prefix(Component.empty());
                    scoreboard.resetScores(entry);
                }
            }
        }
    }
}
