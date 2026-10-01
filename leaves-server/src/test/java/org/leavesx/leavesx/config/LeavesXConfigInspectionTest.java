package org.leavesx.leavesx.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Normal
class LeavesXConfigInspectionTest {
    @TempDir Path directory;

    @Test
    void inspectionDoesNotMigrateOrRewriteConfiguration() throws Exception {
        final Path path = directory.resolve("leavesx.yml");
        final String input = "# preserve this comment\nasync:\n  pathfinding:\n    enabled: false\n";
        Files.writeString(path, input);
        assertFalse(LeavesXConfigInspection.read(path).asyncSettings().pathfindingEnabled());
        assertEquals(input, Files.readString(path));
    }

    @Test
    void missingFileIsNotCreated() {
        final Path path = directory.resolve("missing.yml");
        assertThrows(LeavesXConfigException.class, () -> LeavesXConfigInspection.read(path));
        assertFalse(Files.exists(path));
    }

    @Test
    void unknownKeyCheckAcceptsAliasesAndPreservesFile() throws Exception {
        final Path path = directory.resolve("leavesx.yml");
        final String input = "performance:\n  optimized-varint: true\nasync:\n  pathfinding:\n    enabled: true\n    enabeld: false\n";
        Files.writeString(path, input);
        assertEquals(java.util.List.of("async.pathfinding.enabeld"), LeavesXConfigInspection.unknownKeys(path));
        assertEquals(input, Files.readString(path));
    }

    @Test
    void disabledParentSwitchesExplainInactiveChildrenWithoutRewriting() throws Exception {
        final Path path = directory.resolve("leavesx.yml");
        final String input = "async:\n  pathfinding:\n    enabled: false\n    water-snapshot: true\n"
            + "  playerdata-save:\n    enabled: false\n    compression: true\n";
        Files.writeString(path, input);
        final var warnings = LeavesXConfigInspection.compatibilityWarnings(LeavesXConfigInspection.read(path));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("水路快照")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("存档压缩")), warnings.toString());
        assertEquals(input, Files.readString(path));
    }

    @Test
    void emittedSchemaHasNoUnknownKeys() throws Exception {
        final Path path = directory.resolve("leavesx.yml");
        Files.writeString(path, "async:\n  pathfinding:\n    enabled: true\n");
        LeavesXConfigLoader.load(path);
        assertTrue(LeavesXConfigInspection.unknownKeys(path).isEmpty());
    }
}
