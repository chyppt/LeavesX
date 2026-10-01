package org.leavesx.leavesx.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;
import static org.junit.jupiter.api.Assertions.*;

@Normal
public class JeiConfigurationTest {
    @TempDir Path directory;

    @Test
    void defaultsOffAndRoundTripsIndependently() throws Exception {
        final var node = CommentedConfigurationNode.root();
        node.raw(java.util.Map.of());
        final var disabled = LeavesXConfigLoader.fromNode(node, Path.of("test.yml"));
        assertFalse(disabled.extensions().jeiRecipeSync());
        assertFalse(disabled.extensions().jeiRecipeTransfer());
        node.node("features", "just-enough-items", "recipe-sync").raw(true);
        final var enabled = LeavesXConfigLoader.fromNode(node, Path.of("test.yml"));
        assertTrue(enabled.extensions().jeiRecipeSync());
        assertFalse(enabled.extensions().jeiRecipeTransfer());
        final var written = CommentedConfigurationNode.root();
        LeavesXConfigWriter.write(written, enabled);
        assertEquals(enabled, LeavesXConfigLoader.fromNode(written, Path.of("test.yml")));
        assertTrue(LeavesXConfigWriter.comments().get("features.just-enough-items.recipe-sync").contains("Just Enough Items"));
    }

    @Test
    void schemaUpgradeAddsCommentedDefaultsWithoutOverwritingExistingSettings() throws Exception {
        final Path path = directory.resolve("leavesx.yml");
        Files.writeString(path, "config-version: 81\nfeatures:\n  quick-open-shulker-boxes: true\n");
        final var config = LeavesXConfigLoader.load(path);
        final String output = Files.readString(path);
        assertTrue(config.quickOpenShulkerBoxes());
        assertFalse(config.extensions().jeiRecipeSync());
        assertFalse(config.extensions().jeiRecipeTransfer());
        assertTrue(output.contains("Just Enough Items"));
        assertTrue(output.indexOf("config-version: 82") < output.indexOf("features:"));
        assertTrue(LeavesXConfigInspection.unknownKeys(path).isEmpty());
    }
}
