package com.towerspeed.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.concurrent.TimeUnit;

public final class TimeFormatter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private TimeFormatter() {}

    /**
     * Formats elapsed time into MM:SS.t (or HH:MM:SS.t if >= 1 hour).
     */
    public static String formatTime(long millis) {
        if (millis < 0) {
            millis = 0;
        }

        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        long tenths = (millis % 1000) / 100;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d.%d", hours, minutes, seconds, tenths);
        } else {
            return String.format("%02d:%02d.%d", minutes, seconds, tenths);
        }
    }

    /**
     * Formats split delta (difference vs personal best / server record).
     * e.g., -0:12.4 or +0:03.1
     */
    public static String formatDelta(long deltaMillis) {
        long absMillis = Math.abs(deltaMillis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(absMillis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(absMillis) % 60;
        long tenths = (absMillis % 1000) / 100;

        String sign = deltaMillis < 0 ? "-" : "+";
        return String.format("%s%02d:%02d.%d", sign, minutes, seconds, tenths);
    }

    /**
     * Returns an Adventure Component colored according to delta (green for ahead, red for behind, gold for gold split).
     */
    public static Component formatDeltaComponent(long deltaMillis, boolean isGold) {
        if (isGold) {
            return MM.deserialize("<gold><bold>" + formatDelta(deltaMillis) + " ★</bold></gold>");
        }
        if (deltaMillis < 0) {
            return MM.deserialize("<green>" + formatDelta(deltaMillis) + "</green>");
        } else if (deltaMillis > 0) {
            return MM.deserialize("<red>" + formatDelta(deltaMillis) + "</red>");
        } else {
            return MM.deserialize("<gray>±00:00.0</gray>");
        }
    }
}
