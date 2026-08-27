package org.leavesmc.leaves.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.LeavesConfig;

@Normal
class GlobalConfigManagerTest {

    @Test
    void preservesUnknownSourceSettingsAndRemovesOnlyGeneratedUnmanagedPaths() {
        final YamlConfiguration previous = LeavesConfig.config;
        try {
            final YamlConfiguration config = new YamlConfiguration();
            config.set("settings.future-extension.quoted-value", "0012");
            config.set("unknown-top-level.retained", true);
            final Set<String> sourceKeys = Set.copyOf(config.getKeys(true));

            config.set("settings.generated-extension.value", 42);
            LeavesConfig.config = config;
            GlobalConfigManager.clearGeneratedUnmanagedConfig(sourceKeys);

            assertEquals("0012", config.getString("settings.future-extension.quoted-value"));
            assertTrue(config.getBoolean("unknown-top-level.retained"));
            assertFalse(config.contains("settings.generated-extension", true));
        } finally {
            LeavesConfig.config = previous;
        }
    }
}
