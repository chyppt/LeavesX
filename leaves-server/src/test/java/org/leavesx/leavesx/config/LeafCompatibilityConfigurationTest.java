package org.leavesx.leavesx.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Normal
class LeafCompatibilityConfigurationTest {
    @TempDir Path directory;

    @Test
    void defaultsMatchLoaderAndKeepRandomSequenceChangesOptIn() throws Exception {
        final var config = LeavesXConfigLoader.load(directory.resolve("defaults.yml"));
        assertEquals(LeavesXConfig.ExtensionSettings.defaults(), config.extensions());
        assertFalse(config.extensions().fasterRandom());
        assertFalse(config.extensions().virtualDownloadPool());
        assertTrue(config.extensions().reuseBlockDestructionPackets());
    }

    @Test
    void migrationPreservesExplicitSwitchesAndReportsStartupOnlyChanges() throws Exception {
        final Path file = directory.resolve("leavesx.yml");
        Files.writeString(file, """
            config-version: 86
            performance:
              faster-random-generator:
                enabled: true
              use-virtual-thread:
                download-pool: true
              reduce-packets:
                reuse-block-destruction: false
            """);
        final var loaded = LeavesXConfigLoader.load(file);
        assertTrue(loaded.extensions().fasterRandom());
        assertTrue(loaded.extensions().virtualDownloadPool());
        assertFalse(loaded.extensions().reuseBlockDestructionPackets());
        final var restart = LeavesXConfigBootstrap.restartRequiredChanges(LeavesXConfig.safeDefaults(), loaded);
        assertTrue(restart.contains("async.virtual-threads.download-pool"));
        assertTrue(restart.contains("performance.random-generator.enabled"));
        assertFalse(restart.contains("performance.network.reduce-packets.reuse-block-destruction"));
        final String saved = Files.readString(file);
        assertEquals(loaded, LeavesXConfigLoader.load(file));
        assertEquals(saved, Files.readString(file));
        assertTrue(saved.contains("不减少发送次数"));
    }
}
