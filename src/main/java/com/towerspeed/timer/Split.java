package com.towerspeed.timer;

/**
 * Represents a recorded split within a run.
 *
 * @param name             The descriptive name of the split (e.g. "Floor 10")
 * @param cumulativeTimeMs Total elapsed run time when this split was hit
 * @param segmentTimeMs    Elapsed time spent exclusively on this split segment
 */
public record Split(String name, long cumulativeTimeMs, long segmentTimeMs) {
}
