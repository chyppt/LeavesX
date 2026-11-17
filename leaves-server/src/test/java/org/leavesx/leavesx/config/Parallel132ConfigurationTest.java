package org.leavesx.leavesx.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;

/** 验证新开关可关闭、迁移幂等，且普通目标查询不依赖另一项排序开关。 */
@Normal
public class Parallel132ConfigurationTest {
    @TempDir Path directory;

    @AfterEach
    void restore() {
        LeavesXRuntime.publish(LeavesXConfig.safeDefaults());
    }

    @Test
    void snapshotContainerReuseCanReloadAndKeepsExistingOptions() throws Exception {
        final Path file = this.directory.resolve("batch-reuse.yml");
        Files.writeString(file, """
            config-version: 94
            async:
              compute:
                kernels:
                  snapshot-batch-reuse: false
                  tracker-player-lists: false
                  extension: keep
            """);
        final LeavesXConfig config = LeavesXConfigLoader.load(file);
        assertFalse(config.asyncSettings().kernels().snapshotBatchReuse());
        assertFalse(config.asyncSettings().kernels().trackerPlayerLists());
        assertTrue(LeavesXConfig.safeDefaults().asyncSettings().kernels().snapshotBatchReuse());
        assertEquals(config, LeavesXConfigLoader.load(file));
        assertTrue(Files.readString(file).contains("extension: keep"));
        assertTrue(LeavesXConfigBootstrap.restartRequiredChanges(LeavesXConfig.safeDefaults(), config).isEmpty());
        LeavesXRuntime.publish(config);
        assertFalse(LeavesXRuntime.asyncSettings().kernels().snapshotBatchReuse());
    }

    @Test
    void emptyTrackingFastPathCanReloadWithoutOverwritingExistingSettings() throws Exception {
        final Path file = this.directory.resolve("empty-tracking.yml");
        Files.writeString(file, """
            config-version: 94
            async:
              compute:
                kernels:
                  tracker-row-kernel: false
                  tracker-empty-range-fastpath: false
                  tracker-player-lists: false
            """);
        final LeavesXConfig loaded = LeavesXConfigLoader.load(file);
        assertFalse(loaded.asyncSettings().kernels().trackerEmptyRangeFastPath());
        assertFalse(loaded.asyncSettings().kernels().trackerPlayerLists());
        assertFalse(loaded.asyncSettings().kernels().trackerRowKernel());
        assertEquals(LeavesXConfig.CURRENT_CONFIG_VERSION, loaded.configVersion());
        assertEquals(loaded, LeavesXConfigLoader.load(file));
        assertTrue(LeavesXConfigBootstrap.restartRequiredChanges(LeavesXConfig.safeDefaults(), loaded).isEmpty());
        assertTrue(Files.readString(file).contains("不改变可见性结果"));
        LeavesXRuntime.publish(loaded);
        assertFalse(LeavesXRuntime.asyncSettings().kernels().trackerEmptyRangeFastPath());
    }

    @Test
    void numericKernelsReloadRoundTripAndValidateMemoryLimit() throws Exception {
        assertEquals(new LeavesXConfig.ComputeKernelSettings(true, true, true, true, true, true, 32_768),
            LeavesXConfig.safeDefaults().asyncSettings().kernels());
        final Path file = this.directory.resolve("kernels.yml");
        Files.writeString(file, """
            config-version: 92
            performance:
              compute:
                ai-distance-histograms: false
                ai-nearest-reduction: false
                tracker-row-kernel: false
                spatial-activation-groups: false
              memory:
                compute-cache-max-candidates: 0
            """);
        final LeavesXConfig loaded = LeavesXConfigLoader.load(file);
        assertEquals(new LeavesXConfig.ComputeKernelSettings(false, false, false, true, true, false, 0), loaded.asyncSettings().kernels());
        assertEquals(loaded, LeavesXConfigLoader.load(file));
        assertTrue(LeavesXConfigBootstrap.restartRequiredChanges(LeavesXConfig.safeDefaults(), loaded).isEmpty());
        LeavesXRuntime.publish(loaded);
        assertEquals(loaded.asyncSettings().kernels(), LeavesXRuntime.asyncSettings().kernels());
        assertTrue(Files.readString(file).contains("不限制实体数"));
        for (final int invalid : new int[] {-1, 1_048_577}) {
            final var node = CommentedConfigurationNode.root();
            node.node("performance", "memory", "compute-cache-max-candidates").set(invalid);
            assertThrows(LeavesXConfigException.class, () -> LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
        }
    }

    @Test
    void entityPipelinesHaveIndependentReloadableSwitches() throws Exception {
        final LeavesXConfig defaults = LeavesXConfigLoader.load(this.directory.resolve("pipelines-default.yml"));
        assertTrue(defaults.asyncSettings().trackerSnapshotPipeline());
        assertTrue(defaults.asyncSettings().activationSnapshotPipeline());
        assertTrue(defaults.asyncSettings().aiSnapshotPipeline());
        assertTrue(defaults.asyncSettings().aiParallelMerge());
        assertTrue(defaults.asyncSettings().combinedSpawnCounting());
        final Path file = this.directory.resolve("pipelines.yml");
        Files.writeString(file, """
            async:
              entity-tracker:
                snapshot-pipeline: false
            parallelism:
              entity-activation:
                snapshot-pipeline: false
              ai-target-search:
                snapshot-pipeline: false
                parallel-merge: false
              mob-spawning:
                combined-counting: false
            """);
        final LeavesXConfig disabled = LeavesXConfigLoader.load(file);
        assertFalse(disabled.asyncSettings().trackerSnapshotPipeline());
        assertFalse(disabled.asyncSettings().activationSnapshotPipeline());
        assertFalse(disabled.asyncSettings().aiSnapshotPipeline());
        assertFalse(disabled.asyncSettings().aiParallelMerge());
        assertFalse(disabled.asyncSettings().combinedSpawnCounting());
        assertEquals(disabled, LeavesXConfigLoader.load(file));
        assertTrue(LeavesXConfigBootstrap.restartRequiredChanges(defaults, disabled).isEmpty());
        LeavesXRuntime.publish(disabled);
        assertFalse(LeavesXRuntime.asyncSettings().trackerSnapshotPipeline());
        assertFalse(LeavesXRuntime.asyncSettings().activationSnapshotPipeline());
        assertFalse(LeavesXRuntime.asyncSettings().aiSnapshotPipeline());
        assertFalse(LeavesXRuntime.asyncSettings().aiParallelMerge());
        assertFalse(LeavesXRuntime.asyncSettings().combinedSpawnCounting());
    }

    @Test
    void noiseSamplingDefaultsOnAndCanBeDisabledAtReload() throws Exception {
        LeavesXRuntime.publish(LeavesXConfig.safeDefaults());
        assertTrue(LeavesXRuntime.parallelNoiseSampling());
        final var node = CommentedConfigurationNode.root();
        node.node("parallelism", "chunk-generation", "noise-sampling").set(false);
        final LeavesXConfig disabled = LeavesXConfigLoader.fromNode(node, Path.of("test.yml"));
        LeavesXRuntime.publish(disabled);
        assertFalse(LeavesXRuntime.parallelNoiseSampling());
    }

    @Test
    void failureProtectionIsDetailedReloadableAndRoundTrips() throws Exception {
        final Path file = this.directory.resolve("protection.yml");
        Files.writeString(file, """
            config-version: 89
            parallelism:
              failure-protection:
                enabled: false
                failure-threshold: 7
                cooldown-seconds: 45
                extension: keep
            """);
        final LeavesXConfig defaults = LeavesXConfig.safeDefaults();
        final LeavesXConfig loaded = LeavesXConfigLoader.load(file);
        assertEquals(new LeavesXConfig.ComputeFailureSettings(false, 7, 45), loaded.asyncSettings().failureProtection());
        assertTrue(defaults.asyncSettings().failureProtection().enabled());
        assertTrue(defaults.asyncSettings().sameExecutionSettings(loaded.asyncSettings()));
        assertEquals(loaded, LeavesXConfigLoader.load(file));
        assertTrue(Files.readString(file).contains("extension: keep"));
        assertTrue(Files.readString(file).contains("单次失败后的完整同步重算"));
        assertTrue(LeavesXConfigBootstrap.restartRequiredChanges(defaults, loaded).isEmpty());
    }

    @Test
    void failureProtectionRejectsInvalidLimits() throws Exception {
        for (final int threshold : new int[] {0, 101}) {
            final var node = CommentedConfigurationNode.root();
            node.node("parallelism", "failure-protection", "failure-threshold").set(threshold);
            assertThrows(LeavesXConfigException.class, () -> LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
        }
        for (final int seconds : new int[] {0, 3_601}) {
            final var node = CommentedConfigurationNode.root();
            node.node("parallelism", "failure-protection", "cooldown-seconds").set(seconds);
            assertThrows(LeavesXConfigException.class, () -> LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
        }
    }

    @Test
    void schedulingSettingsAreReloadableAndValidated() throws Exception {
        final Path file = this.directory.resolve("scheduling.yml");
        Files.writeString(file, """
            config-version: 90
            parallelism:
              scheduling:
                realtime-reserved-workers: 2
                low-priority-queue-limit: 12
            """);
        final LeavesXConfig loaded = LeavesXConfigLoader.load(file);
        assertEquals(new LeavesXConfig.ComputeSchedulingSettings(2, 12), loaded.asyncSettings().scheduling());
        assertTrue(LeavesXConfigBootstrap.restartRequiredChanges(LeavesXConfig.safeDefaults(), loaded).isEmpty());
        assertTrue(Files.readString(file).contains("实时计算保留"));
        for (final int value : new int[] {-2, 16}) {
            final var node = CommentedConfigurationNode.root();
            node.node("parallelism", "scheduling", "realtime-reserved-workers").set(value);
            assertThrows(LeavesXConfigException.class, () -> LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
        }
    }

    @Test
    void trackerEventCompatibilityDefaultsOnAndCanBeDisabledWithoutRestart() throws Exception {
        final LeavesXConfig defaults = LeavesXConfigLoader.load(this.directory.resolve("tracker-default.yml"));
        assertTrue(defaults.asyncSettings().trackerEventListeners());
        final Path file = this.directory.resolve("tracker.yml");
        Files.writeString(file, """
            config-version: 91
            async:
              entity-tracker:
                event-listeners: false
            """);
        final LeavesXConfig disabled = LeavesXConfigLoader.load(file);
        assertFalse(disabled.asyncSettings().trackerEventListeners());
        assertTrue(defaults.asyncSettings().sameExecutionSettings(disabled.asyncSettings()));
        assertTrue(LeavesXConfigBootstrap.restartRequiredChanges(defaults, disabled).isEmpty());
        assertTrue(Files.readString(file).contains("event-listeners: false"));
        assertTrue(Files.readString(file).contains("按原实体顺序在主线程执行"));
    }

    @Test
    void noiseSamplingMigrationPreservesExplicitDisableAndRoundTrips() throws Exception {
        final Path file = this.directory.resolve("noise.yml");
        Files.writeString(file, """
            config-version: 88
            parallelism:
              chunk-generation:
                noise-sampling: false
            """);
        final LeavesXConfig loaded = LeavesXConfigLoader.load(file);
        assertFalse(loaded.parallelNoiseSampling());
        assertEquals(LeavesXConfig.CURRENT_CONFIG_VERSION, loaded.configVersion());
        assertEquals(loaded, LeavesXConfigLoader.load(file));
        assertTrue(Files.readString(file).contains("noise-sampling: false"));
    }

    @Test
    void migrationRetainsExplicitlyDisabledSwitches() throws Exception {
        final Path file = this.directory.resolve("leavesx.yml");
        Files.writeString(file, """
            config-version: 83
            async:
              shared-compute-workers: false
              low-load-workers: false
              entity-tracker:
                comparison-sharding: false
                custom-field: keep
            parallelism:
              ai-target-search:
                nearest-query: false
            """);
        final LeavesXConfig first = LeavesXConfigLoader.load(file);
        assertFalse(first.asyncSettings().sharedComputeWorkers());
        assertFalse(first.asyncSettings().trackerComparisonSharding());
        assertFalse(first.asyncSettings().lowLoadWorkers());
        assertFalse(first.optimizationEnabled(LeavesXOptimization.NEAREST_QUERY_DISTANCE_SNAPSHOT));
        final String saved = Files.readString(file);
        assertTrue(saved.contains("custom-field: keep"));
        assertTrue(saved.contains("增加调度与等待开销"));
        assertEquals(LeavesXConfig.CURRENT_CONFIG_VERSION, first.configVersion());
        assertEquals(first, LeavesXConfigLoader.load(file));
        assertEquals(saved, Files.readString(file));
    }

    @Test
    void defaultsAndRestartNoticeCoverTheNewStartupSettings() throws Exception {
        final LeavesXConfig previous = LeavesXConfigLoader.load(this.directory.resolve("default.yml"));
        assertTrue(previous.asyncSettings().lowLoadWorkers());
        assertTrue(previous.asyncSettings().sharedComputeWorkers());
        assertTrue(previous.asyncSettings().trackerComparisonSharding());
        final var node = CommentedConfigurationNode.root();
        node.node("async", "low-load-workers").set(false);
        final LeavesXConfig next = LeavesXConfigLoader.fromNode(node, Path.of("test.yml"));
        assertTrue(LeavesXConfigBootstrap.restartRequiredChanges(previous, next).contains("async"));
    }

    @Test
    void animalSnapshotSwitchesDefaultOnAndReloadWithoutRestart() throws Exception {
        final LeavesXConfig previous = LeavesXConfigLoader.load(this.directory.resolve("animals.yml"));
        assertTrue(previous.asyncSettings().animalBreeding());
        assertTrue(previous.asyncSettings().animalFollowParent());

        final var node = CommentedConfigurationNode.root();
        node.node("async", "animals", "breeding").set(false);
        node.node("async", "animals", "follow-parent").set(false);
        final LeavesXConfig next = LeavesXConfigLoader.fromNode(node, Path.of("test.yml"));
        assertFalse(next.asyncSettings().animalBreeding());
        assertFalse(next.asyncSettings().animalFollowParent());
        assertFalse(LeavesXConfigBootstrap.restartRequiredChanges(previous, next).contains("async"));
    }

    @Test
    void activationPrefilterIsSwitchableAndDoesNotRequirePoolRestart() throws Exception {
        final LeavesXConfig previous = LeavesXConfig.safeDefaults();
        final Path file = this.directory.resolve("kernels.yml");
        Files.writeString(file, """
            config-version: 85
            performance:
              compute:
                activation-bounds-prefilter: false
            """);
        final LeavesXConfig next = LeavesXConfigLoader.load(file);
        assertFalse(next.asyncSettings().activationBoundsPrefilter());
        assertFalse(LeavesXConfigBootstrap.restartRequiredChanges(previous, next).contains("async"));
        assertEquals(next, LeavesXConfigLoader.load(file));
        LeavesXRuntime.publish(next);
        assertFalse(LeavesXRuntime.activationBoundsPrefilter());
    }

    @Test
    void nearestQuerySwitchIsIndependentAndRetainsVillagerProtection() throws Exception {
        final var node = CommentedConfigurationNode.root();
        node.node(LeavesXOptimization.NEAREST_ENTITY_DISTANCE_SORT.nodePath()).set(false);
        LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
        assertTrue(LeavesXRuntime.nearestQueryDistanceSnapshot());
        final boolean previous = LeavesXRuntime.enterVillagerCompatibilityContext();
        try {
            assertFalse(LeavesXRuntime.nearestQueryDistanceSnapshot());
        } finally {
            LeavesXRuntime.exitVillagerCompatibilityContext(previous);
        }
        node.node(LeavesXOptimization.NEAREST_QUERY_DISTANCE_SNAPSHOT.nodePath()).set(false);
        LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
        assertFalse(LeavesXRuntime.nearestQueryDistanceSnapshot());
    }
}
