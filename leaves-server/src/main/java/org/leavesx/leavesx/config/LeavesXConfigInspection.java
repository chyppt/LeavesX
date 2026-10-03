package org.leavesx.leavesx.config;

import java.nio.file.Files;
import java.nio.file.Path;

/** 只读检查不会调用可能迁移并保存文件的 load()。 */
public final class LeavesXConfigInspection {
    private LeavesXConfigInspection() {}

    /** 说明已知前置条件和兼容性门控，不修改管理员的配置选择。 */
    public static java.util.List<String> compatibilityWarnings(final LeavesXConfig config) {
        final var warnings = new java.util.ArrayList<String>();
        if (config.extensions().asyncWaterPathfinding() && !config.asyncSettings().pathfindingEnabled()) {
            warnings.add("水路快照寻路已开启，但异步寻路总开关关闭，计算不会进入寻路线程池。");
        }
        if (config.extensions().asyncPlayerCompression() && !config.asyncSettings().playerDataSaveEnabled()) {
            warnings.add("玩家存档压缩已开启，但异步存档关闭，压缩与写入保持调用线程执行。");
        }
        if (config.regionTickingSettings().mode() != LeavesXConfig.RegionTickingSettings.Mode.OFF) {
            warnings.add("世界/区域并行配置受技术玩法兼容门控保护，实际世界 Tick 保持串行，独立快照计算不受影响。");
        }
        if (!config.villagerCompatibilityMode()) {
            warnings.add("村民兼容模式已关闭；生电服建议开启，以保留村民原有感知与活动路径。");
        }
        if (config.animalIdleGoalThrottling() || config.denseMonsterAiThrottling()) {
            warnings.add("已开启 AI 降频：这会改变目标检查时机，不属于行为完全等价的优化。");
        }
        return java.util.List.copyOf(warnings);
    }

    public static LeavesXConfig read(final Path path) {
        if (!Files.isRegularFile(path)) throw new LeavesXConfigException("配置文件不存在或不是普通文件");
        try {
            return LeavesXConfigLoader.fromNode(LeavesXConfigWriter.orderedLoader().path(path).build().load(), path);
        } catch (final LeavesXConfigException exception) {
            throw exception;
        } catch (final Exception exception) {
            throw new LeavesXConfigException("无法检查配置文件", exception);
        }
    }

    /** 未知键只给出提示；保留扩展内容，不重写正在检查的文件。 */
    public static java.util.List<String> unknownKeys(final Path path) {
        try {
            final var source = LeavesXConfigPaths.normalized(LeavesXConfigWriter.orderedLoader().path(path).build().load());
            final var config = LeavesXConfigLoader.fromNode(source, path);
            final var schema = org.spongepowered.configurate.CommentedConfigurationNode.root();
            LeavesXConfigWriter.write(schema, config);
            final var unknown = new java.util.ArrayList<String>();
            collectUnknown(source, schema, "", unknown);
            return java.util.List.copyOf(unknown);
        } catch (final LeavesXConfigException exception) {
            throw exception;
        } catch (final Exception exception) {
            throw new LeavesXConfigException("无法检查未知配置项", exception);
        }
    }

    private static void collectUnknown(final org.spongepowered.configurate.ConfigurationNode source,
                                       final org.spongepowered.configurate.ConfigurationNode schema,
                                       final String prefix, final java.util.List<String> unknown) {
        // 列表保存的是值而不是键；限制输出长度，避免损坏配置刷屏。
        if (!source.isMap() || unknown.size() >= 32) return;
        for (final var entry : source.childrenMap().entrySet()) {
            if (unknown.size() >= 32) break;
            final String key = String.valueOf(entry.getKey());
            final String path = prefix.isEmpty() ? key : prefix + "." + key;
            final var expected = schema.node(entry.getKey());
            if (expected.virtual()) unknown.add(path);
            else collectUnknown(entry.getValue(), expected, path, unknown);
        }
    }
}
