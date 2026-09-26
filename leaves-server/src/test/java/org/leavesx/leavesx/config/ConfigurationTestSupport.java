package org.leavesx.leavesx.config;

import java.nio.file.Path;
import org.spongepowered.configurate.ConfigurationNode;

/** Allows behavioral tests in other packages to set configuration without widening the production API. */
public final class ConfigurationTestSupport {
    private ConfigurationTestSupport() {}

    public static void publish(final LeavesXConfig config) {
        LeavesXRuntime.publish(config);
    }

    public static void publish(final ConfigurationNode node) {
        LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
    }
}
