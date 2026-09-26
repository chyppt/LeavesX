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
        assertTrue(node.node("performance", "use-virtual-thread", "async-chat-executor").getBoolean());
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
}
