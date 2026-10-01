package org.leavesx.leavesx.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Normal
class RestartRequiredChangesTest {
    @TempDir Path directory;

    @Test
    void startupChangesRemainPendingUntilRevertedOrRestarted() throws Exception {
        final Path file = directory.resolve("leavesx.yml");
        Files.writeString(file, "async:\n  pathfinding:\n    enabled: false\n");
        final LeavesXConfig startup = LeavesXConfigLoader.load(file);
        Files.writeString(file, "async:\n  pathfinding:\n    enabled: true\n");
        final LeavesXConfig requested = LeavesXConfigLoader.load(file);
        assertTrue(LeavesXConfigBootstrap.restartRequiredChanges(startup, requested).contains("async"));
        assertTrue(LeavesXConfigBootstrap.restartRequiredChanges(startup, LeavesXConfigLoader.load(file)).contains("async"));
        assertTrue(LeavesXConfigBootstrap.restartRequiredChanges(startup, startup).isEmpty());
    }
}
