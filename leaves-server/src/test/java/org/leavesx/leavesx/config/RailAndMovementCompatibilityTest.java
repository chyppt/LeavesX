package org.leavesx.leavesx.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ca.spottedleaf.moonrise.common.misc.SingleUserAreaMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;

@Normal
class RailAndMovementCompatibilityTest {
    @AfterEach void reset() { LeavesXRuntime.publish(LeavesXConfig.safeDefaults()); }

    private static void configure(final boolean optimized) {
        final var node = CommentedConfigurationNode.root();
        node.node("performance", "optimized-powered-rails").raw(optimized);
        node.node("performance", "optimize-player-movement").raw(optimized);
        LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
    }

    @Test
    void railSignalSearchMatchesResultAndReadOrder() throws Exception {
        final var find = PoweredRailBlock.class.getDeclaredMethod("findPoweredRailSignal", Level.class,
            BlockPos.class, BlockState.class, boolean.class, int.class);
        find.setAccessible(true);
        final Level level = mock(Level.class);
        final Map<BlockPos, BlockState> states = new HashMap<>();
        final Set<BlockPos> powered = new HashSet<>();
        final List<String> reads = new ArrayList<>();
        when(level.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            final BlockPos pos = call.getArgument(0);
            reads.add("block:" + pos.toShortString());
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        });
        when(level.hasNeighborSignal(any(BlockPos.class))).thenAnswer(call -> {
            final BlockPos pos = call.getArgument(0);
            reads.add("power:" + pos.toShortString());
            return powered.contains(pos);
        });
        final BlockPos start = new BlockPos(-16, 70, -16);
        final var random = new java.util.Random(82);
        for (final var rail : List.of(Blocks.POWERED_RAIL, Blocks.ACTIVATOR_RAIL)) {
            for (int scenario = 0; scenario < 80; scenario++) {
                states.clear();
                powered.clear();
                for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) for (int dy = -2; dy <= 2; dy++) {
                    if (dx != 0 && dz != 0) continue;
                    final BlockPos pos = start.offset(dx, dy, dz);
                    final RailShape shape = scenario < 20 ? RailShape.EAST_WEST : RailShape.values()[random.nextInt(6)];
                    states.put(pos, rail.defaultBlockState().setValue(PoweredRailBlock.SHAPE, shape)
                        .setValue(PoweredRailBlock.POWERED, scenario < 20 || random.nextBoolean()));
                    if (scenario < 20 ? dx == scenario - 10 : random.nextInt(15) == 0) powered.add(pos);
                }
                for (boolean forward : new boolean[]{true, false}) {
                    configure(false);
                    reads.clear();
                    final Object expected = find.invoke(rail, level, start, states.get(start), forward, 0);
                    final List<String> expectedReads = List.copyOf(reads);
                    configure(true);
                    reads.clear();
                    assertEquals(expected, find.invoke(rail, level, start, states.get(start), forward, 0));
                    assertEquals(expectedReads, reads, "rail read/short-circuit order");
                }
            }
        }
    }

    @Test
    void stationaryMovementElisionPreservesAreaMapCallbacks() {
        assertEquals(this.moves(false), this.moves(true));
    }

    private List<String> moves(final boolean optimized) {
        configure(optimized);
        final var changes = new ArrayList<String>();
        final SingleUserAreaMap<String> area = new SingleUserAreaMap<>("player") {
            @Override protected void addCallback(String player, int x, int z) { changes.add("+" + x + "," + z); }
            @Override protected void removeCallback(String player, int x, int z) { changes.add("-" + x + "," + z); }
        };
        assertFalse(area.update(0, 0, 2));
        assertTrue(area.add(0, 0, 2));
        final var random = new java.util.Random(13);
        for (int i = 0; i < 100; i++) {
            final int x = random.nextInt(21) - 10;
            final int z = random.nextInt(21) - 10;
            final int distance = random.nextInt(5);
            area.update(x, z, distance);
            final int callbacks = changes.size();
            assertTrue(area.update(x, z, distance));
            assertEquals(callbacks, changes.size());
            assertEquals(x, area.getLastChunkX());
            assertEquals(z, area.getLastChunkZ());
            assertEquals(distance, area.getLastDistance());
        }
        assertThrows(IllegalArgumentException.class, () -> area.update(0, 0, -1));
        return changes;
    }
}
