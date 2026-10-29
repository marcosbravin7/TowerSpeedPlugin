package com.towerspeed.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.towerspeed.timer.CategoryRecords;
import com.towerspeed.timer.RunRecord;
import com.towerspeed.timer.Split;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages human-readable JSON persistence for Top 5 runs and gold splits per category.
 */
public class RecordStorage {

    private static final int MAX_TOP_RUNS = 5;

    private final File recordsFolder;
    private final Logger logger;
    private final Gson gson;

    private final Map<String, CategoryRecords> categoryRecordsMap = new HashMap<>();

    public RecordStorage(File dataFolder, Logger logger) {
        this.recordsFolder = new File(dataFolder, "records");
        this.logger = logger;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadAll();
    }

    public synchronized RunRecord getBestRun(String categoryId) {
        CategoryRecords cr = categoryRecordsMap.get(categoryId.toLowerCase());
        return cr != null ? cr.getBestRun() : null;
    }

    public synchronized List<RunRecord> getTopRuns(String categoryId) {
        CategoryRecords cr = categoryRecordsMap.get(categoryId.toLowerCase());
        if (cr == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(cr.getTopRuns());
    }

    public synchronized Long getGoldSegment(String categoryId, String splitName) {
        CategoryRecords cr = categoryRecordsMap.get(categoryId.toLowerCase());
        return cr != null ? cr.getGoldSegment(splitName) : null;
    }

    public synchronized boolean isNewRecord(String categoryId, long totalTimeMs) {
        RunRecord currentBest = getBestRun(categoryId);
        return currentBest == null || totalTimeMs < currentBest.totalTimeMs();
    }

    public synchronized boolean isGoldSegment(String categoryId, String splitName, long segmentMs) {
        Long currentGold = getGoldSegment(categoryId, splitName);
        return currentGold == null || segmentMs < currentGold;
    }

    /**
     * Processes a finished run.
     *
     * @param record The completed run record
     * @return 1 if new PB (#1), 2-5 if made leaderboard, -1 if did not make top 5
     */
    public synchronized int processCompletedRun(RunRecord record) {
        if (record.totalTimeMs() <= 0) {
            logger.warning("Ignored invalid run with totalTimeMs <= 0");
            return -1;
        }

        String catId = record.categoryId().toLowerCase();
        CategoryRecords cr = categoryRecordsMap.computeIfAbsent(catId, CategoryRecords::new);

        List<RunRecord> runs = cr.getTopRuns();
        runs.add(record);
        runs.sort(Comparator.comparingLong(RunRecord::totalTimeMs));

        int rank = runs.indexOf(record) + 1; // 1-indexed

        if (runs.size() > MAX_TOP_RUNS) {
            // Trim to max top runs
            while (runs.size() > MAX_TOP_RUNS) {
                runs.remove(runs.size() - 1);
            }
        }

        // Check if our run made the cut
        if (rank > MAX_TOP_RUNS) {
            rank = -1;
        }

        // Update gold segments from this run
        for (Split s : record.splits()) {
            Long currentGold = cr.getGoldSegment(s.name());
            if (currentGold == null || s.segmentTimeMs() < currentGold) {
                cr.getGoldSegments().put(s.name(), s.segmentTimeMs());
            }
        }

        saveCategory(catId);
        return rank;
    }

    /**
     * Deletes a record from the leaderboard by rank (1-5).
     *
     * @param categoryId Category ID
     * @param rank       1-indexed rank (1 is PB)
     * @return The removed RunRecord, or null if rank was invalid
     */
    public synchronized RunRecord deleteRecord(String categoryId, int rank) {
        String catId = categoryId.toLowerCase();
        CategoryRecords cr = categoryRecordsMap.get(catId);
        if (cr == null) return null;

        List<RunRecord> runs = cr.getTopRuns();
        int index = rank - 1;
        if (index < 0 || index >= runs.size()) {
            return null;
        }

        RunRecord removed = runs.remove(index);
        cr.recalculateGoldSegments();
        saveCategory(catId);
        return removed;
    }

    /**
     * Clears all leaderboard runs and gold splits for a category.
     */
    public synchronized void clearCategory(String categoryId) {
        String catId = categoryId.toLowerCase();
        categoryRecordsMap.remove(catId);
        File file = getCategoryFile(catId);
        if (file.exists()) {
            file.delete();
        }
    }

    private File getCategoryFile(String categoryId) {
        return new File(recordsFolder, categoryId.toLowerCase() + ".json");
    }

    public synchronized void loadAll() {
        categoryRecordsMap.clear();

        if (!recordsFolder.exists()) {
            recordsFolder.mkdirs();
        }

        // Migrate legacy records.json in parent folder if it exists
        File legacyFile = new File(recordsFolder.getParentFile(), "records.json");
        if (legacyFile.exists()) {
            migrateLegacyFile(legacyFile);
        }

        File[] files = recordsFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        if (files == null) return;

        for (File file : files) {
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                CategoryRecords cr = gson.fromJson(reader, CategoryRecords.class);
                if (cr != null && cr.getCategoryId() != null) {
                    cr.getTopRuns().removeIf(r -> r.totalTimeMs() <= 0);
                    categoryRecordsMap.put(cr.getCategoryId().toLowerCase(), cr);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to load record file: " + file.getName(), e);
            }
        }

        logger.info("Loaded records for " + categoryRecordsMap.size() + " categories.");
    }

    public synchronized void saveCategory(String categoryId) {
        String catId = categoryId.toLowerCase();
        CategoryRecords cr = categoryRecordsMap.get(catId);
        if (cr == null) return;

        if (!recordsFolder.exists()) {
            recordsFolder.mkdirs();
        }

        File targetFile = getCategoryFile(catId);
        File tempFile = new File(recordsFolder, catId + ".json.tmp");

        try {
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8)) {
                gson.toJson(cr, writer);
            }
            Files.move(tempFile.toPath(), targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to atomically save records for: " + catId, e);
        }
    }

    public synchronized void save() {
        for (String catId : categoryRecordsMap.keySet()) {
            saveCategory(catId);
        }
    }

    private void migrateLegacyFile(File legacyFile) {
        try {
            logger.info("Migrating legacy records.json to records/ folder...");
            try (Reader reader = new InputStreamReader(new FileInputStream(legacyFile), StandardCharsets.UTF_8)) {
                LegacyContainer container = gson.fromJson(reader, LegacyContainer.class);
                if (container != null && container.bestRuns != null) {
                    for (Map.Entry<String, RunRecord> entry : container.bestRuns.entrySet()) {
                        String catId = entry.getKey().toLowerCase();
                        CategoryRecords cr = categoryRecordsMap.computeIfAbsent(catId, CategoryRecords::new);
                        cr.getTopRuns().add(entry.getValue());
                        if (container.goldSegments != null && container.goldSegments.containsKey(entry.getKey())) {
                            cr.getGoldSegments().putAll(container.goldSegments.get(entry.getKey()));
                        }
                        saveCategory(catId);
                    }
                }
            }
            File backup = new File(legacyFile.getParentFile(), "records.json.migrated");
            legacyFile.renameTo(backup);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to migrate legacy records.json", e);
        }
    }

    private static class LegacyContainer {
        Map<String, RunRecord> bestRuns;
        Map<String, Map<String, Long>> goldSegments;
    }
}
