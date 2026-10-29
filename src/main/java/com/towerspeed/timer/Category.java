package com.towerspeed.timer;

import java.util.List;

/**
 * Represents a run category (e.g. Tower Climb, Any% Co-op).
 *
 * @param id          Unique identifier key (e.g. "tower_climb")
 * @param displayName Formatted MiniMessage display name
 * @param splitNames  Ordered list of split checkpoints
 */
public record Category(String id, String displayName, List<String> splitNames) {
}
