package com.towerspeed.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.towerspeed.timer.RunRecord;
import com.towerspeed.timer.Split;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages persistence of global server speedrun records and gold splits.
 */
public class RecordStorage {

    private final File dataFile;
    private final Logger logger;
    private final Gson gson;

    private Map<String, RunRecord> bestRuns = new HashMap<>();
    private Map<String, Map<String, Long>> goldSegments = new HashMap<>();

    public RecordStorage(File dataFolder, Logger logger) {
        this.dataFile = new File(dataFolder, "records.json");
        this.logger = logger;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        load();
    }

    public synchronized RunRecord getBestRun(String categoryId) {
        return bestRuns.get(categoryId);
    }

    public synchronized Long getGoldSegment(String categoryId, String splitName) {
        Map<String, Long> categoryGolds = goldSegments.get(categoryId);
        if (categoryGolds == null) return null;
        return categoryGolds.get(splitName);
    }

    public synchronized boolean isNewRecord(String categoryId, long totalTimeMs) {
        RunRecord currentBest = bestRuns.get(categoryId);
        return currentBest == null || totalTimeMs < currentBest.totalTimeMs();
    }

    public synchronized boolean isGoldSegment(String categoryId, String splitName, long segmentMs) {
        Long currentGold = getGoldSegment(categoryId, splitName);
        return currentGold == null || segmentMs < currentGold;
    }

    /**
     * Updates records with a finished run, saving both overall PB and individual gold splits.
     */
    public synchronized boolean processCompletedRun(RunRecord record) {
        boolean isNewPB = isNewRecord(record.categoryId(), record.totalTimeMs());

        // Check and update individual gold segments
        Map<String, Long> categoryGolds = goldSegments.computeIfAbsent(record.categoryId(), k -> new HashMap<>());
        for (Split split : record.splits()) {
            Long existingGold = categoryGolds.get(split.name());
            if (existingGold == null || split.segmentTimeMs() < existingGold) {
                categoryGolds.put(split.name(), split.segmentTimeMs());
            }
        }

        if (isNewPB) {
            bestRuns.put(record.categoryId(), record);
        }

        save();
        return isNewPB;
    }

    public synchronized void resetCategory(String categoryId) {
        bestRuns.remove(categoryId);
        goldSegments.remove(categoryId);
        save();
    }

    private void load() {
        if (!dataFile.exists()) {
            return;
        }

        try (Reader reader = new InputStreamReader(new FileInputStream(dataFile), StandardCharsets.UTF_8)) {
            Type containerType = new TypeToken<StorageContainer>() {}.getType();
            StorageContainer container = gson.fromJson(reader, containerType);
            if (container != null) {
                if (container.bestRuns != null) this.bestRuns = container.bestRuns;
                if (container.goldSegments != null) this.goldSegments = container.goldSegments;
            }
            logger.info("Loaded speedrun records for " + bestRuns.size() + " categories.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load records.json", e);
        }
    }

    public synchronized void save() {
        try {
            if (!dataFile.getParentFile().exists()) {
                dataFile.getParentFile().mkdirs();
            }
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(dataFile), StandardCharsets.UTF_8)) {
                StorageContainer container = new StorageContainer();
                container.bestRuns = this.bestRuns;
                container.goldSegments = this.goldSegments;
                gson.toJson(container, writer);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save records.json", e);
        }
    }

    private static class StorageContainer {
        Map<String, RunRecord> bestRuns = new HashMap<>();
        Map<String, Map<String, Long>> goldSegments = new HashMap<>();
    }
}
