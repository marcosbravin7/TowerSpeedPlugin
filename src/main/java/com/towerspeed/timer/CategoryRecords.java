package com.towerspeed.timer;

import java.util.*;

/**
 * Encapsulates the top runs and gold splits for a specific category.
 */
public class CategoryRecords {

    private String categoryId;
    private List<RunRecord> topRuns = new ArrayList<>();
    private Map<String, Long> goldSegments = new HashMap<>();

    public CategoryRecords() {}

    public CategoryRecords(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public List<RunRecord> getTopRuns() {
        return topRuns;
    }

    public Map<String, Long> getGoldSegments() {
        return goldSegments;
    }

    public RunRecord getBestRun() {
        return topRuns.isEmpty() ? null : topRuns.get(0);
    }

    public Long getGoldSegment(String splitName) {
        return goldSegments.get(splitName);
    }

    /**
     * Recalculates all gold segments based on the currently remaining top runs.
     */
    public void recalculateGoldSegments() {
        goldSegments.clear();
        for (RunRecord run : topRuns) {
            for (Split s : run.splits()) {
                Long current = goldSegments.get(s.name());
                if (current == null || s.segmentTimeMs() < current) {
                    goldSegments.put(s.name(), s.segmentTimeMs());
                }
            }
        }
    }
}
