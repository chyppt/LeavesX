package org.leavesx.leavesx.config;

import java.nio.file.Path;
import org.spongepowered.configurate.ConfigurationNode;

/** 允许其他包中的行为测试设置配置，不扩大生产 API。 */
public final class ConfigurationTestSupport {
    private ConfigurationTestSupport() {}

    public static void publish(final LeavesXConfig config) {
        LeavesXRuntime.publish(config);
    }

    public static void publish(final ConfigurationNode node) {
        LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
    }
}
