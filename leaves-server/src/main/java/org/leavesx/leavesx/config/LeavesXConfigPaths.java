package org.leavesx.leavesx.config;

import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.ConfigurationNode;

/** 单向别名：只写入整理后的结构，同时继续接受旧配置文件。 */
final class LeavesXConfigPaths {
    // 按旧结构到新结构依次迁移，避免同一项跨多版改名时依赖 Map 的遍历顺序。
    private static final List<Map.Entry<String, String>> ALIASES = List.of(
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
        Map.entry("async.virtual-chat", "performance.use-virtual-thread.async-chat-executor"),
        Map.entry("parallelism", "async.compute"),
        Map.entry("regionized-ticking", "async.world-ticking"),
        Map.entry("performance.use-virtual-thread", "async.virtual-threads"),
        Map.entry("performance.compute", "async.compute.kernels"),
        Map.entry("performance.memory.compute-cache-max-candidates", "async.compute.cache-max-candidates"),
        Map.entry("async.low-load-workers", "async.compute.low-load-workers"),
        Map.entry("async.shared-compute-workers", "async.compute.shared-workers"),
        Map.entry("async.ai-candidates", "async.compute.ai-candidates"),
        Map.entry("async.animals", "async.compute.animals"),
        Map.entry("async.isolate-chunk-packing", "async.chunk-send.isolate-packing"),
        Map.entry("performance.chunk", "performance.chunks"),
        Map.entry("performance.reduce-packets", "performance.network.reduce-packets"),
        Map.entry("performance.cache-biome", "performance.memory.biome-cache"),
        Map.entry("performance.optimize-player-movement", "performance.entities.player-movement"),
        Map.entry("performance.optimized-powered-rails", "performance.blocks.powered-rails"),
        Map.entry("performance.optimize-random-tick", "performance.blocks.random-tick"),
        Map.entry("performance.faster-random-generator", "performance.random-generator")
    );

    private LeavesXConfigPaths() {}

    static ConfigurationNode normalized(final ConfigurationNode source) {
        for (final Map.Entry<String, String> alias : ALIASES) {
            if (!source.node((Object[]) alias.getKey().split("\\.")).virtual()) {
                final ConfigurationNode copy = source.copy();
                migrate(copy);
                return copy;
            }
        }
        return source;
    }

    static boolean migrate(final ConfigurationNode root) {
        boolean changed = false;
        for (final Map.Entry<String, String> alias : ALIASES) {
            final Object[] oldPath = alias.getKey().split("\\.");
            final ConfigurationNode source = root.node(oldPath);
            if (source.virtual()) continue;
            final ConfigurationNode target = root.node((Object[]) alias.getValue().split("\\."));
            // 新路径和旧路径同时设置时以新路径为准；旧子树中的其他键继续保留。
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
