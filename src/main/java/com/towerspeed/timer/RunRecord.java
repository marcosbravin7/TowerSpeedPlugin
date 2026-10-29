package com.towerspeed.timer;

import java.util.List;

/**
 * Persisted record for a finished run.
 *
 * @param categoryId         Category identifier
 * @param totalTimeMs        Total run completion time
 * @param splits             List of all achieved splits
 * @param playerNames        List of players in co-op who completed the run
 * @param recordedAtEpochMs  Epoch timestamp of run completion
 */
public record RunRecord(
        String categoryId,
        long totalTimeMs,
        List<Split> splits,
        List<String> playerNames,
        long recordedAtEpochMs
) {
    public Split getSplit(int index) {
        if (index >= 0 && index < splits.size()) {
            return splits.get(index);
        }
        return null;
    }
}
