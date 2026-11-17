package org.leavesx.leavesx.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

@Normal
class LeafConfigurationLayoutTest {
    @TempDir Path directory;

    @Test
    void oldPathsMigrateWithoutChangingChoicesOrUnknownValues() throws Exception {
        final Path file = this.directory.resolve("leavesx.yml");
        Files.writeString(file, """
            # 服主自己的说明
            config-version: 77
            performance:
              ai-optimizations: false
              optimized-varint: false
              dense-monster-ai:
                enabled: true
                nearby-radius: 12
                custom-field: keep
              chunk-unloading:
                maximum-per-tick: 731
              container-copy-reuse: false
            async:
              virtual-chat: true
            messages:
              join: '&e<player>来了'
            """);
        final LeavesXConfig config = LeavesXConfigLoader.load(file);
        assertFalse(config.aiOptimizations());
        assertFalse(config.optimizedVarInt());
        assertFalse(config.extensions().containerCopyReuse());
        assertTrue(config.extensions().virtualChat());
        assertEquals(731, config.chunkUnloadMaximumPerTick());
        final var node = YamlConfigurationLoader.builder().path(file).build().load();
        assertTrue(node.node("performance", "ai-optimizations").virtual());
        assertFalse(node.node("performance", "ai", "enabled").getBoolean(true));
        assertEquals("keep", node.node("performance", "ai", "dense-monsters", "custom-field").getString());
        assertTrue(node.node("async", "virtual-threads", "async-chat-executor").getBoolean());
        assertEquals("&e<player>来了", node.node("messages", "join").getString());
        final String first = Files.readString(file);
        assertTrue(first.startsWith("# 服主自己的说明"));
        LeavesXConfigLoader.load(file);
        assertEquals(first, Files.readString(file), "migration is idempotent");
    }

    @Test
    void newPathWinsAndInvalidOldValueCannotSilentlyBecomeDefault() throws Exception {
        final Path file = this.directory.resolve("conflict.yml");
        Files.writeString(file, "config-version: 77\nperformance:\n  ai-optimizations: false\n  ai:\n    enabled: true\n");
        assertTrue(LeavesXConfigLoader.load(file).aiOptimizations());
        Files.writeString(file, "config-version: 77\nperformance:\n  ai-optimizations: wrong\n");
        final String invalid = Files.readString(file);
        assertThrows(LeavesXConfigException.class, () -> LeavesXConfigLoader.load(file));
        assertEquals(invalid, Files.readString(file));
    }

    @Test
    void networkBufferSwitchPersistsAndDoesNotOverwriteExistingChoices() throws Exception {
        final Path file = this.directory.resolve("network.yml");
        Files.writeString(file, "performance:\n  network:\n    buffer-optimizations: false\n    custom-field: keep\n");
        assertFalse(LeavesXConfigLoader.load(file).extensions().networkBufferOptimizations());
        final String written = Files.readString(file);
        assertTrue(written.contains("减少字符串编码和 Java 压缩的临时缓冲区"));
        assertTrue(written.contains("custom-field: keep"));
        assertFalse(LeavesXConfigLoader.load(file).extensions().networkBufferOptimizations());
        assertEquals(written, Files.readString(file));
        assertTrue(LeavesXConfigLoader.load(this.directory.resolve("default-network.yml"))
            .extensions().networkBufferOptimizations());
    }

    @Test
    void computeSettingsMoveTogetherWithoutLosingChoicesOrExtensions() throws Exception {
        final Path file = this.directory.resolve("compute-layout.yml");
        Files.writeString(file, """
            # 自己的配置说明
            config-version: 93
            parallelism:
              workers: 6
              ai-target-search:
                enabled: false
                custom-option: keep
              failure-protection:
                failure-threshold: 7
            performance:
              compute:
                ai-nearest-reduction: false
                custom-kernel: keep
              memory:
                compute-cache-max-candidates: 4096
              use-virtual-thread:
                download-pool: false
            async:
              low-load-workers: false
              shared-compute-workers: false
              isolate-chunk-packing: false
            """);
        final LeavesXConfig config = LeavesXConfigLoader.load(file);
        assertEquals(6, config.computeWorkers());
        assertFalse(config.parallelAiTargetSearch());
        assertEquals(7, config.asyncSettings().failureProtection().failureThreshold());
        assertFalse(config.asyncSettings().kernels().nearestReduction());
        assertEquals(4096, config.asyncSettings().kernels().cacheMaxCandidates());
        assertFalse(config.asyncSettings().lowLoadWorkers());
        assertFalse(config.asyncSettings().sharedComputeWorkers());
        assertFalse(config.extensions().isolateChunkPacking());
        assertFalse(config.extensions().virtualDownloadPool());
        final var node = YamlConfigurationLoader.builder().path(file).build().load();
        assertTrue(node.node("parallelism").virtual());
        assertTrue(node.node("performance", "compute").virtual());
        assertTrue(node.node("performance", "use-virtual-thread").virtual());
        assertTrue(node.node("async", "low-load-workers").virtual());
        assertTrue(node.node("async", "shared-compute-workers").virtual());
        assertFalse(node.node("async", "compute", "ai-target-search", "enabled").getBoolean(true));
        assertEquals("keep", node.node("async", "compute", "ai-target-search", "custom-option").getString());
        assertEquals("keep", node.node("async", "compute", "kernels", "custom-kernel").getString());
        assertEquals(4096, node.node("async", "compute", "cache-max-candidates").getInt());
        final String first = Files.readString(file);
        assertTrue(first.startsWith("# 自己的配置说明"));
        assertEquals(config, LeavesXConfigLoader.load(file));
        assertEquals(first, Files.readString(file));
    }

    @Test
    void consolidatedValuesWinOverLegacyValuesAndOldInvalidValuesStayRejected() throws Exception {
        final Path file = this.directory.resolve("new-compute.yml");
        Files.writeString(file, """
            config-version: 93
            parallelism:
              workers: 3
              failure-protection:
                enabled: true
                custom-field: retained
            async:
              low-load-workers: true
              compute:
                workers: 7
                low-load-workers: false
                failure-protection:
                  enabled: false
            """);
        final LeavesXConfig config = LeavesXConfigLoader.load(file);
        assertEquals(7, config.computeWorkers());
        assertFalse(config.asyncSettings().lowLoadWorkers());
        assertFalse(config.asyncSettings().failureProtection().enabled());
        final var node = YamlConfigurationLoader.builder().path(file).build().load();
        assertEquals("retained", node.node("async", "compute", "failure-protection", "custom-field").getString());
        assertTrue(node.node("parallelism").virtual());
        Files.writeString(file, "config-version: 93\nparallelism:\n  workers: broken\n");
        final String invalid = Files.readString(file);
        assertThrows(LeavesXConfigException.class, () -> LeavesXConfigLoader.load(file));
        assertEquals(invalid, Files.readString(file));
    }

    @Test
    void newFilesContainOnlyGroupedPathsAndReloadDoesNotRewriteThem() throws Exception {
        final Path file = this.directory.resolve("new-defaults.yml");
        final LeavesXConfig config = LeavesXConfigLoader.load(file);
        final var node = YamlConfigurationLoader.builder().path(file).build().load();
        assertTrue(node.node("parallelism").virtual());
        assertTrue(node.node("regionized-ticking").virtual());
        assertTrue(node.node("performance", "compute").virtual());
        assertTrue(node.node("async", "compute", "ai-target-search", "snapshot-pipeline").getBoolean());
        assertTrue(node.node("async", "compute", "kernels", "tracker-row-kernel").getBoolean());
        assertEquals("off", node.node("async", "world-ticking", "mode").getString());
        final String first = Files.readString(file);
        assertTrue(first.indexOf("config-version:") < first.indexOf("performance:"));
        assertTrue(first.indexOf("performance:") < first.indexOf("async:"));
        assertEquals(config, LeavesXConfigLoader.load(file));
        assertEquals(first, Files.readString(file));
        try (final var files = Files.list(this.directory)) {
            assertEquals(java.util.Set.of("new-defaults.yml"), files
                .map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void successiveAliasesAlwaysPreferTheMoreRecentPath() throws Exception {
        final Path file = this.directory.resolve("successive-aliases.yml");
        Files.writeString(file, """
            config-version: 77
            async:
              virtual-chat: true
            performance:
              use-virtual-thread:
                async-chat-executor: false
            """);
        assertFalse(LeavesXConfigLoader.load(file).extensions().virtualChat());
        final String first = Files.readString(file);
        assertFalse(LeavesXConfigLoader.load(file).extensions().virtualChat());
        assertEquals(first, Files.readString(file));
        final var node = YamlConfigurationLoader.builder().path(file).build().load();
        assertTrue(node.node("async", "virtual-chat").virtual());
        assertTrue(node.node("performance", "use-virtual-thread").virtual());
        assertFalse(node.node("async", "virtual-threads", "async-chat-executor").getBoolean(true));
    }
}
