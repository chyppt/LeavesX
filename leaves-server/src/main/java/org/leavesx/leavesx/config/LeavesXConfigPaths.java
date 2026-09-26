package org.leavesx.leavesx.config;

import java.util.Map;
import org.spongepowered.configurate.ConfigurationNode;

/** One-way aliases: write only the organized schema, but keep accepting older files. */
final class LeavesXConfigPaths {
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("performance.optimized-varint", "performance.network.optimized-varint"),
        Map.entry("performance.immutable-cache-optimizations", "performance.memory.immutable-caches"),
        Map.entry("performance.resource-identifier-cache", "performance.memory.resource-identifiers"),
        Map.entry("performance.skip-empty-events", "performance.events.skip-empty-events"),
        Map.entry("performance.entity-state-optimizations", "performance.entities.state-checks"),
        Map.entry("performance.entity-query-optimizations", "performance.entities.queries"),
        Map.entry("performance.ai-optimizations", "performance.ai.enabled"),
        Map.entry("performance.villager-compatibility-mode", "performance.ai.villager-compatibility"),
        Map.entry("performance.pathfinding-optimizations", "performance.ai.pathfinding"),
        Map.entry("performance.mob-spawning-optimizations", "performance.spawning.enabled"),
        Map.entry("performance.animal-idle-goals", "performance.ai.animal-idle-goals"),
        Map.entry("performance.dense-monster-ai", "performance.ai.dense-monsters"),
        Map.entry("performance.item-merge-optimizations", "performance.entities.item-merge"),
        Map.entry("performance.block-entity-optimizations", "performance.blocks.block-entities"),
        Map.entry("performance.block-entity-ticker-removal", "performance.blocks.ticker-removal"),
        Map.entry("performance.chunk-spawn-range-optimization", "performance.spawning.chunk-range"),
        Map.entry("performance.chunk-unloading", "performance.chunks.unloading"),
        Map.entry("performance.startup-ai", "performance.startup.ai"),
        Map.entry("performance.startup-resident-bots", "fakeplayer.startup-restore"),
        Map.entry("performance.spectator-no-chunk-loading", "features.spectator-no-chunk-loading"),
        Map.entry("performance.container-copy-reuse", "performance.blocks.container-copy-reuse"),
        Map.entry("async.virtual-chat", "performance.use-virtual-thread.async-chat-executor")
    );

    private LeavesXConfigPaths() {}

    static ConfigurationNode normalized(final ConfigurationNode source) {
        for (final String path : ALIASES.keySet()) {
            if (!source.node((Object[]) path.split("\\.")).virtual()) {
                final ConfigurationNode copy = source.copy();
                migrate(copy);
                return copy;
            }
        }
        return source;
    }

    static boolean migrate(final ConfigurationNode root) {
        boolean changed = false;
        for (final Map.Entry<String, String> alias : ALIASES.entrySet()) {
            final Object[] oldPath = alias.getKey().split("\\.");
            final ConfigurationNode source = root.node(oldPath);
            if (source.virtual()) continue;
            final ConfigurationNode target = root.node((Object[]) alias.getValue().split("\\."));
            // A new-path value wins when both are explicitly set; other old subtree keys are preserved.
            target.mergeFrom(source);
            source.raw(null);
            for (int depth = oldPath.length - 1; depth > 0; depth--) {
                final ConfigurationNode parent = root.node(java.util.Arrays.copyOf(oldPath, depth));
                if (!parent.isMap() || !parent.childrenMap().isEmpty()) break;
                parent.raw(null);
            }
            changed = true;
        }
        return changed;
    }
}
