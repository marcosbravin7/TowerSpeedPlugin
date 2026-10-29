package com.towerspeed.command;

import com.towerspeed.TowerSpeedPlugin;
import com.towerspeed.scoreboard.LiveSplitScoreboard;
import com.towerspeed.timer.Category;
import com.towerspeed.timer.RunRecord;
import com.towerspeed.timer.Split;
import com.towerspeed.timer.TimerManager;
import com.towerspeed.util.TimeFormatter;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.*;

public class TimerCommand implements BasicCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final TowerSpeedPlugin plugin;
    private final TimerManager timer;
    private final LiveSplitScoreboard scoreboard;

    public TimerCommand(TowerSpeedPlugin plugin, TimerManager timer, LiveSplitScoreboard scoreboard) {
        this.plugin = plugin;
        this.timer = timer;
        this.scoreboard = scoreboard;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();

        if (args.length == 0) {
            sendHelp(sender);
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "start" -> {
                if (!sender.hasPermission("towerspeed.timer")) {
                    sender.sendMessage(MM.deserialize("<red>You do not have permission to control the timer.</red>"));
                    return;
                }
                boolean instant = args.length > 1 && args[1].equalsIgnoreCase("instant");
                timer.start(sender, instant);
            }
            case "split" -> {
                if (!sender.hasPermission("towerspeed.timer")) {
                    sender.sendMessage(MM.deserialize("<red>You do not have permission to control the timer.</red>"));
                    return;
                }
                String customName = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : null;
                timer.split(sender, customName);
            }
            case "unsplit", "undo" -> {
                if (!sender.hasPermission("towerspeed.timer")) {
                    sender.sendMessage(MM.deserialize("<red>No permission.</red>"));
                    return;
                }
                timer.unsplit(sender);
            }
            case "pause" -> {
                if (!sender.hasPermission("towerspeed.timer")) {
                    sender.sendMessage(MM.deserialize("<red>No permission.</red>"));
                    return;
                }
                timer.pause(sender);
            }
            case "resume" -> {
                if (!sender.hasPermission("towerspeed.timer")) {
                    sender.sendMessage(MM.deserialize("<red>No permission.</red>"));
                    return;
                }
                timer.resume(sender);
            }
            case "reset" -> {
                if (!sender.hasPermission("towerspeed.timer")) {
                    sender.sendMessage(MM.deserialize("<red>No permission.</red>"));
                    return;
                }
                timer.reset(sender);
            }
            case "toggle" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(MM.deserialize("<red>Only players can toggle scoreboard visibility.</red>"));
                    return;
                }
                boolean visible = scoreboard.togglePlayer(player);
                player.sendMessage(MM.deserialize("<yellow>[Timer] Scoreboard visibility: " + (visible ? "<green>Visible</green>" : "<red>Hidden</red>") + "</yellow>"));
            }
            case "category", "cat" -> {
                if (args.length < 2) {
                    sender.sendMessage(MM.deserialize("<yellow>Current category: " + timer.getCurrentCategory().displayName() + "</yellow>"));
                    sender.sendMessage(MM.deserialize("<gray>Usage: /timer category <name></gray>"));
                    return;
                }
                timer.setCategory(args[1], sender);
            }
            case "record", "records", "leaderboard", "pb" -> {
                handleRecords(sender, args);
            }
            case "reload" -> {
                if (!sender.hasPermission("towerspeed.admin")) {
                    sender.sendMessage(MM.deserialize("<red>No permission to reload plugin.</red>"));
                    return;
                }
                plugin.reloadConfig();
                timer.loadFromConfig();
                timer.getRecordStorage().loadAll();
                sender.sendMessage(MM.deserialize("<green>[Timer] Configuration and records reloaded!</green>"));
            }
            default -> sendHelp(sender);
        }
    }

    private void handleRecords(CommandSender sender, String[] args) {
        if (args.length == 1) {
            showLeaderboard(sender, timer.getCurrentCategory());
            return;
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "view" -> {
                if (args.length < 3) {
                    sender.sendMessage(MM.deserialize("<gray>Usage: /timer records view <rank#></gray>"));
                    return;
                }
                try {
                    int rank = Integer.parseInt(args[2]);
                    showRecordDetails(sender, timer.getCurrentCategory(), rank);
                } catch (NumberFormatException e) {
                    sender.sendMessage(MM.deserialize("<red>Rank must be a number between 1 and 5.</red>"));
                }
            }
            case "delete", "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage(MM.deserialize("<gray>Usage: /timer records delete <rank#> [category]</gray>"));
                    return;
                }

                int rank;
                Category category = timer.getCurrentCategory();

                try {
                    rank = Integer.parseInt(args[2]);
                    if (args.length >= 4) {
                        Category specified = timer.getCategories().get(args[3].toLowerCase());
                        if (specified != null) category = specified;
                    }
                } catch (NumberFormatException e) {
                    // Try: /timer records delete <category> <rank#>
                    Category specified = timer.getCategories().get(args[2].toLowerCase());
                    if (specified != null && args.length >= 4) {
                        category = specified;
                        try {
                            rank = Integer.parseInt(args[3]);
                        } catch (NumberFormatException ex) {
                            sender.sendMessage(MM.deserialize("<red>Invalid rank number.</red>"));
                            return;
                        }
                    } else {
                        sender.sendMessage(MM.deserialize("<red>Invalid command format. Usage: /timer records delete <rank#></red>"));
                        return;
                    }
                }

                RunRecord deleted = timer.getRecordStorage().deleteRecord(category.id(), rank);
                if (deleted != null) {
                    sender.sendMessage(MM.deserialize("<yellow>[Timer] Successfully deleted rank #" + rank + " ("
                            + TimeFormatter.formatTime(deleted.totalTimeMs()) + ") from " + category.displayName() + ".</yellow>"));
                    RunRecord newPB = timer.getRecordStorage().getBestRun(category.id());
                    if (newPB != null) {
                        timer.broadcast(MM.deserialize("<yellow>[Timer] Server PB for " + category.displayName() + " is now: <green>"
                                + TimeFormatter.formatTime(newPB.totalTimeMs()) + "</green></yellow>"));
                    } else {
                        timer.broadcast(MM.deserialize("<yellow>[Timer] No records remaining for " + category.displayName() + ".</yellow>"));
                    }
                } else {
                    sender.sendMessage(MM.deserialize("<red>Could not find record rank #" + rank + " for " + category.displayName() + ".</red>"));
                }
            }
            case "clear" -> {
                Category cat = timer.getCurrentCategory();
                if (args.length >= 3) {
                    Category specified = timer.getCategories().get(args[2].toLowerCase());
                    if (specified != null) cat = specified;
                }
                timer.getRecordStorage().clearCategory(cat.id());
                timer.broadcast(MM.deserialize("<yellow>[Timer] Cleared all records for category: " + cat.displayName() + ".</yellow>"));
            }
            default -> {
                // Check if args[1] is a category name
                Category specified = timer.getCategories().get(action);
                if (specified != null) {
                    showLeaderboard(sender, specified);
                } else {
                    sender.sendMessage(MM.deserialize("<red>Unknown records action or category: " + action + "</red>"));
                    sender.sendMessage(MM.deserialize("<gray>Usage: /timer records [view|delete|clear|<category>]</gray>"));
                }
            }
        }
    }

    private void showLeaderboard(CommandSender sender, Category category) {
        List<RunRecord> runs = timer.getRecordStorage().getTopRuns(category.id());

        sender.sendMessage(MM.deserialize("<gray>======================================</gray>"));
        sender.sendMessage(MM.deserialize("<gold><bold>★ TOP 5 LEADERBOARD: </bold></gold>" + category.displayName()));

        if (runs.isEmpty()) {
            sender.sendMessage(MM.deserialize("<gray>No completed runs recorded yet for this category.</gray>"));
        } else {
            for (int i = 0; i < runs.size(); i++) {
                RunRecord r = runs.get(i);
                int rank = i + 1;
                String rankColor = rank == 1 ? "<gold><bold>#1</bold></gold>" : "<yellow>#" + rank + "</yellow>";
                String dateStr = DATE_FORMAT.format(new Date(r.recordedAtEpochMs()));
                String teamStr = r.playerNames().isEmpty() ? "Solo" : String.join(", ", r.playerNames());

                sender.sendMessage(MM.deserialize(rankColor + " <white><bold>" + TimeFormatter.formatTime(r.totalTimeMs())
                        + "</bold></white> <gray>(" + dateStr + ")</gray> <aqua>[" + teamStr + "]</aqua>"));
            }
            sender.sendMessage(MM.deserialize("<gray>Tip: Use <yellow>/timer records view <#></yellow> to view splits</gray>"));
            sender.sendMessage(MM.deserialize("<gray>Tip: Use <yellow>/timer records delete <#></yellow> to remove a run</gray>"));
        }
        sender.sendMessage(MM.deserialize("<gray>======================================</gray>"));
    }

    private void showRecordDetails(CommandSender sender, Category category, int rank) {
        List<RunRecord> runs = timer.getRecordStorage().getTopRuns(category.id());
        int index = rank - 1;
        if (index < 0 || index >= runs.size()) {
            sender.sendMessage(MM.deserialize("<red>Rank #" + rank + " does not exist in the leaderboard.</red>"));
            return;
        }

        RunRecord record = runs.get(index);
        sender.sendMessage(MM.deserialize("<gray>======================================</gray>"));
        sender.sendMessage(MM.deserialize("<gold><bold>★ RUN #" + rank + ": </bold></gold>" + category.displayName()));
        sender.sendMessage(MM.deserialize("<white>Total Time: </white><yellow><bold>" + TimeFormatter.formatTime(record.totalTimeMs()) + "</bold></yellow>"));
        sender.sendMessage(MM.deserialize("<white>Date: </white><gray>" + DATE_FORMAT.format(new Date(record.recordedAtEpochMs())) + "</gray>"));
        sender.sendMessage(MM.deserialize("<white>Team: </white><aqua>" + String.join(", ", record.playerNames()) + "</aqua>"));
        sender.sendMessage(MM.deserialize("<white>Splits:</white>"));

        for (Split s : record.splits()) {
            Long gold = timer.getRecordStorage().getGoldSegment(category.id(), s.name());
            boolean isGold = gold != null && s.segmentTimeMs() <= gold;
            String goldTag = isGold ? " <gold>(GOLD)</gold>" : "";
            sender.sendMessage(MM.deserialize("  <gray>• " + s.name() + ":</gray> <white>" + TimeFormatter.formatTime(s.cumulativeTimeMs())
                    + "</white> <gray>[segment: " + TimeFormatter.formatTime(s.segmentTimeMs()) + "]</gray>" + goldTag));
        }
        sender.sendMessage(MM.deserialize("<gray>======================================</gray>"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<gold><bold>=== TowerSpeed Co-op Timer Commands ===</bold></gold>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer start [instant]</yellow> <gray>- Start run (with 3s countdown or instant)</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer split [name]</yellow> <gray>(or /split) - Record next split</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer unsplit</yellow> <gray>(or /unsplit) - Undo the last split</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer pause</yellow> <gray>- Pause the running timer</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer resume</yellow> <gray>- Resume the paused timer</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer reset</yellow> <gray>- Reset run</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer toggle</yellow> <gray>- Toggle sidebar scoreboard for yourself</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer category <name></yellow> <gray>- Switch run category</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer records</yellow> <gray>- View Top 5 leaderboard</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer records view <#></yellow> <gray>- View split breakdown for a run</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer records delete <#></yellow> <gray>- Delete an accidental record</gray>"));
        if (sender.hasPermission("towerspeed.admin")) {
            sender.sendMessage(MM.deserialize("<yellow>/timer reload</yellow> <gray>- Reload configuration and records</gray>"));
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length <= 1) {
            List<String> subs = new ArrayList<>(List.of("start", "split", "unsplit", "pause", "resume", "reset", "toggle", "category", "records", "leaderboard"));
            if (source.getSender().hasPermission("towerspeed.admin")) {
                subs.add("reload");
            }
            return filter(subs, args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            return filter(List.of("instant"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("category") || args[0].equalsIgnoreCase("cat"))) {
            return filter(new ArrayList<>(timer.getCategories().keySet()), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("records") || args[0].equalsIgnoreCase("record") || args[0].equalsIgnoreCase("leaderboard"))) {
            List<String> options = new ArrayList<>(List.of("view", "delete", "clear"));
            options.addAll(timer.getCategories().keySet());
            return filter(options, args[1]);
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("records") || args[0].equalsIgnoreCase("record") || args[0].equalsIgnoreCase("leaderboard"))) {
            if (args[1].equalsIgnoreCase("view") || args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("remove")) {
                List<String> ranks = List.of("1", "2", "3", "4", "5");
                List<String> options = new ArrayList<>(ranks);
                options.addAll(timer.getCategories().keySet());
                return filter(options, args[2]);
            }
            if (args[1].equalsIgnoreCase("clear")) {
                return filter(new ArrayList<>(timer.getCategories().keySet()), args[2]);
            }
        }
        if (args.length == 4 && (args[0].equalsIgnoreCase("records") || args[0].equalsIgnoreCase("record"))) {
            if (args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("remove")) {
                return filter(List.of("1", "2", "3", "4", "5"), args[3]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(lower)).toList();
    }

    @Override
    public @Nullable String permission() {
        return "towerspeed.timer";
    }
}
