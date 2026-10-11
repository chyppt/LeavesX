package org.leavesx.leavesx.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.support.environment.Normal;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.spongepowered.configurate.CommentedConfigurationNode;

/** 直接执行绊线钩更新，检查更新中拆钩后的回填和开关隔离。 */
@Normal
class TripwireHookDuplicationTest {
    @TempDir Path directory;

    @Test
    void disabledByDefaultAndAddedToExistingConfig() throws Exception {
        final Path path = directory.resolve("leavesx.yml");
        Files.writeString(path, "features:\n  quick-open-shulker-boxes: true\n");
        final LeavesXConfig config = LeavesXConfigLoader.load(path);
        assertFalse(config.extensions().tripwireHookDuplication());
        assertTrue(Files.readString(path).contains("tripwire-hook-duplication: false"));
        assertTrue(Files.readString(path).contains("quick-open-shulker-boxes: true"));
    }

    @Test
    void canEnableAndDisableOnReload() throws Exception {
        final LeavesXConfig previous = LeavesXRuntime.configuration();
        final Path path = directory.resolve("leavesx.yml");
        try {
            Files.writeString(path, "features:\n  tripwire-hook-duplication: true\n");
            LeavesXRuntime.publish(LeavesXConfigLoader.load(path));
            assertTrue(LeavesXRuntime.tripwireHookDuplication());
            Files.writeString(path, "features:\n  tripwire-hook-duplication: false\n");
            LeavesXRuntime.publish(LeavesXConfigLoader.load(path));
            assertFalse(LeavesXRuntime.tripwireHookDuplication());
        } finally {
            LeavesXRuntime.publish(previous);
        }
    }

    @Test
    void removedHookStaysRemovedWhenDisabled() throws Exception {
        checkUpdate(false, false, false, false);
    }

    @Test
    void removedHookIsRestoredWhenEnabledWithoutPaperOverride() throws Exception {
        checkUpdate(true, false, false, true);
    }

    @Test
    void existingHookStillUpdatesWithEitherSetting() throws Exception {
        checkUpdate(false, true, false, true);
        checkUpdate(true, true, false, true);
    }

    @Test
    void explicitDestructionDoesNotRestoreEmitter() throws Exception {
        checkUpdate(true, false, true, false);
    }

    @Test
    void pluginCanStillCancelBothHookUpdates() throws Exception {
        final LeavesXConfig previous = LeavesXRuntime.configuration();
        final CommentedConfigurationNode node = CommentedConfigurationNode.root();
        node.node("features", "tripwire-hook-duplication").set(true);
        final Level level = mock(Level.class);
        final BlockPos emitter = new BlockPos(0, 64, 0);
        final BlockPos receiver = emitter.relative(Direction.EAST);
        final BlockState hook = Blocks.TRIPWIRE_HOOK.defaultBlockState()
            .setValue(TripWireHookBlock.FACING, Direction.EAST).setValue(TripWireHookBlock.POWERED, true);
        when(level.getBlockState(receiver)).thenReturn(hook.setValue(TripWireHookBlock.FACING, Direction.WEST));
        try (MockedStatic<CraftEventFactory> events = mockStatic(CraftEventFactory.class)) {
            // false 表示插件拒绝电平变化；兼容开关不能越过这一边界。
            events.when(() -> CraftEventFactory.callBinaryRedstoneChange(level, emitter, false)).thenReturn(false);
            events.when(() -> CraftEventFactory.callBinaryRedstoneChange(level, receiver, false)).thenReturn(false);
            LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, directory.resolve("leavesx.yml")));
            TripWireHookBlock.calculateState(level, emitter, hook, false, false, -1, null);
            events.verify(() -> CraftEventFactory.callBinaryRedstoneChange(level, emitter, false));
            events.verify(() -> CraftEventFactory.callBinaryRedstoneChange(level, receiver, false));
            verify(level, never()).setBlock(any(BlockPos.class), any(BlockState.class), anyInt());
        } finally {
            LeavesXRuntime.publish(previous);
        }
    }

    private void checkUpdate(final boolean enabled, final boolean present, final boolean destroying,
                             final boolean expectReplacement) throws Exception {
        final LeavesXConfig previous = LeavesXRuntime.configuration();
        final CommentedConfigurationNode node = CommentedConfigurationNode.root();
        node.node("features", "tripwire-hook-duplication").set(enabled);
        final GlobalConfiguration paper = new GlobalConfiguration();
        paper.unsupportedSettings = paper.new UnsupportedSettings();
        final BlockPos emitter = new BlockPos(0, 64, 0);
        final BlockPos receiver = emitter.relative(Direction.EAST);
        final BlockState hook = Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(TripWireHookBlock.FACING, Direction.EAST);
        final Level level = mock(Level.class);
        when(level.enabledFeatures()).thenReturn(FeatureFlags.VANILLA_SET);
        // 模拟接收端邻居更新已拆掉发射端；无需改动真正的世界或红石调度器。
        when(level.getBlockState(emitter)).thenReturn(present ? hook : Blocks.AIR.defaultBlockState());
        when(level.getBlockState(receiver)).thenReturn(hook.setValue(TripWireHookBlock.FACING, Direction.WEST));
        try (MockedStatic<GlobalConfiguration> globals = mockStatic(GlobalConfiguration.class)) {
            globals.when(GlobalConfiguration::get).thenReturn(paper);
            LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, directory.resolve("leavesx.yml")));
            TripWireHookBlock.calculateState(level, emitter, hook, destroying, false, -1, null);
            verify(level, times(expectReplacement ? 1 : 0)).setBlock(eq(emitter), eq(hook), eq(Block.UPDATE_ALL));
            assertFalse(paper.unsupportedSettings.skipTripwireHookPlacementValidation);
        } finally {
            LeavesXRuntime.publish(previous);
        }
    }
}
