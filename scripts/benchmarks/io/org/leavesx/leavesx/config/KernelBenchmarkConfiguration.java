package org.leavesx.leavesx.config;

import java.nio.file.Path;
import org.spongepowered.configurate.CommentedConfigurationNode;

/** 只在按需基准中发布内核开关，不给生产 API 增加测试入口。 */
public final class KernelBenchmarkConfiguration {
    private KernelBenchmarkConfiguration() {}

    public static void configure(final boolean enabled) {
        final var node = CommentedConfigurationNode.root();
        node.node("performance", "compute", "activation-bounds-prefilter").raw(enabled);
        LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("benchmark.yml")));
    }

    public static void restore() {
        LeavesXRuntime.publish(LeavesXConfig.safeDefaults());
    }

    public static void parallelMerge(final boolean enabled) {
        final var node = CommentedConfigurationNode.root();
        node.node("parallelism", "ai-target-search", "parallel-merge").raw(enabled);
        LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("benchmark.yml")));
    }

    public static void numericKernels(final boolean enabled) {
        final var node = CommentedConfigurationNode.root();
        node.node("performance", "compute", "ai-distance-histograms").raw(enabled);
        node.node("performance", "compute", "ai-nearest-reduction").raw(enabled);
        node.node("performance", "compute", "tracker-row-kernel").raw(enabled);
        node.node("performance", "compute", "spatial-activation-groups").raw(enabled);
        // 两个变体使用相同缓存上限，避免把回收策略的差异算作算法收益。
        node.node("performance", "memory", "compute-cache-max-candidates").raw(1_048_576);
        LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("benchmark.yml")));
    }
}
