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
            case "record", "records", "pb" -> {
                showRecords(sender);
            }
            case "reload" -> {
                if (!sender.hasPermission("towerspeed.admin")) {
                    sender.sendMessage(MM.deserialize("<red>No permission to reload plugin.</red>"));
                    return;
                }
                plugin.reloadConfig();
                timer.loadFromConfig();
                sender.sendMessage(MM.deserialize("<green>[Timer] Configuration reloaded!</green>"));
            }
            default -> sendHelp(sender);
        }
    }

    private void showRecords(CommandSender sender) {
        Category cat = timer.getCurrentCategory();
        RunRecord record = timer.getRecordStorage().getBestRun(cat.id());

        sender.sendMessage(MM.deserialize("<gray>======================================</gray>"));
        sender.sendMessage(MM.deserialize("<gold><bold>★ SERVER RECORD: </bold></gold>" + cat.displayName()));
        if (record == null) {
            sender.sendMessage(MM.deserialize("<gray>No completed run recorded yet for this category.</gray>"));
        } else {
            sender.sendMessage(MM.deserialize("<white>Total Time: </white><yellow><bold>" + TimeFormatter.formatTime(record.totalTimeMs()) + "</bold></yellow>"));
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            sender.sendMessage(MM.deserialize("<white>Date: </white><gray>" + sdf.format(new Date(record.recordedAtEpochMs())) + "</gray>"));
            sender.sendMessage(MM.deserialize("<white>Team: </white><aqua>" + String.join(", ", record.playerNames()) + "</aqua>"));
            sender.sendMessage(MM.deserialize("<white>Splits:</white>"));
            for (Split s : record.splits()) {
                Long gold = timer.getRecordStorage().getGoldSegment(cat.id(), s.name());
                boolean isGold = gold != null && s.segmentTimeMs() <= gold;
                String goldTag = isGold ? " <gold>(GOLD)</gold>" : "";
                sender.sendMessage(MM.deserialize("  <gray>• " + s.name() + ":</gray> <white>" + TimeFormatter.formatTime(s.cumulativeTimeMs())
                        + "</white> <gray>[segment: " + TimeFormatter.formatTime(s.segmentTimeMs()) + "]</gray>" + goldTag));
            }
        }
        sender.sendMessage(MM.deserialize("<gray>======================================</gray>"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<gold><bold>=== TowerSpeed Co-op Timer Commands ===</bold></gold>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer start [instant]</yellow> <gray>- Start run (with 3s countdown or instant)</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer split [name]</yellow> <gray>(or /split) - Record next split</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer pause</yellow> <gray>- Pause the running timer</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer resume</yellow> <gray>- Resume the paused timer</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer reset</yellow> <gray>- Reset run</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer toggle</yellow> <gray>- Toggle sidebar scoreboard for yourself</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer category <name></yellow> <gray>- Switch run category</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/timer records</yellow> <gray>- View server PB & split records</gray>"));
        if (sender.hasPermission("towerspeed.admin")) {
            sender.sendMessage(MM.deserialize("<yellow>/timer reload</yellow> <gray>- Reload configuration</gray>"));
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length <= 1) {
            List<String> subs = new ArrayList<>(List.of("start", "split", "pause", "resume", "reset", "toggle", "category", "records"));
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
