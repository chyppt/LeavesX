package org.leavesx.leavesx.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Normal
public class SparkProtectionConfigurationTest {
    @TempDir Path directory;

    @Test
    void missingOptionDefaultsToProtectionAndWritesIt() throws Exception {
        Path file = directory.resolve("leavesx.yml");
        LeavesXConfig config = LeavesXConfigLoader.load(file);
        assertTrue(config.extensions().sparkJavaSampler());
        assertTrue(Files.readString(file).contains("java-sampler: true"));
        assertEquals(config, LeavesXConfigLoader.load(file));
    }

    @Test
    void disablingProtectionRequiresRestartAndRetainsUnknownOptions() throws Exception {
        Path file = directory.resolve("leavesx.yml");
        Files.writeString(file, "diagnostics:\n  spark:\n    java-sampler: false\n    custom: keep\n");
        LeavesXConfig config = LeavesXConfigLoader.load(file);
        assertFalse(config.extensions().sparkJavaSampler());
        assertTrue(LeavesXConfigBootstrap.restartRequiredChanges(LeavesXConfig.safeDefaults(), config)
            .contains("diagnostics.spark.java-sampler"));
        assertTrue(Files.readString(file).contains("custom: keep"));
    }
}
