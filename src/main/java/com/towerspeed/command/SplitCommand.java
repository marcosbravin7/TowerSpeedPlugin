package com.towerspeed.command;

import com.towerspeed.timer.TimerManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Convenient shortcut command (/split) to quickly hit the next split during runs.
 */
public class SplitCommand implements BasicCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final TimerManager timer;

    public SplitCommand(TimerManager timer) {
        this.timer = timer;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission("towerspeed.timer")) {
            sender.sendMessage(MM.deserialize("<red>You do not have permission to control the timer.</red>"));
            return;
        }

        String customName = args.length > 0 ? String.join(" ", Arrays.copyOfRange(args, 0, args.length)) : null;
        timer.split(sender, customName);
    }

    @Override
    public @Nullable String permission() {
        return "towerspeed.timer";
    }
}
