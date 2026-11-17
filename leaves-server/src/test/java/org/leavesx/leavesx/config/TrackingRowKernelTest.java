package org.leavesx.leavesx.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.performance.EntityTrackingRangeBatch;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;
import org.spongepowered.configurate.CommentedConfigurationNode;

/** 覆盖真实追踪行分片、空行以及非标准距离；参考公式独立于优化实现。 */
@Normal
public class TrackingRowKernelTest {
    @Test
    void emptyRowsDoNotCreateComparisonTasks() {
        for (final int capacity : new int[] {5, 1024}) {
            final EntityTrackingRangeBatch range = new EntityTrackingRangeBatch();
            final double[] positions = new double[capacity * 3];
            final double[] views = new double[capacity];
            range.prepare(8192, positions, views, 5);
            for (int row = 0; row < 1024; row++) {
                if (row % 16 == 0) range.setRow(row, 0, 0, 0, 16, -1, new int[] {0, 1, 2, 3, 4}, 5, new boolean[5]);
                else range.setEmptyRow(row);
            }
            final var pipeline = org.mockito.Mockito.mock(org.leavesx.leavesx.runtime.LeavesXSnapshotBatch.class);
            try (var partitions = org.mockito.Mockito.mockStatic(org.leavesx.leavesx.runtime.LeavesXSnapshotBatch.class)) {
                partitions.when(() -> org.leavesx.leavesx.runtime.LeavesXSnapshotBatch.partitionSize(8192L * capacity, 1024)).thenReturn(1024);
                range.submitRows(pipeline, 0, 1024, positions, views, 5);
                partitions.verify(() -> org.leavesx.leavesx.runtime.LeavesXSnapshotBatch.partitionSize(8192L * capacity, 1024));
                org.mockito.Mockito.verify(pipeline).submit(org.mockito.ArgumentMatchers.eq(320), org.mockito.ArgumentMatchers.any());
            }
            range.clear();
        }
    }

    @Test
    void uniformViewsAndUnusedCapacityKeepExactOriginalFormula() throws Exception {
        final double[] special = {-128, -0.0, 0.0, 1.0E-170, 16, Double.POSITIVE_INFINITY, Double.NaN};
        for (final boolean enabled : new boolean[] {false, true}) {
            final var node = CommentedConfigurationNode.root();
            node.node("performance", "compute", "tracker-row-kernel").set(enabled);
            LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
            for (final double view : special) {
                for (final double range : special) {
                    final double[] positions = {16, 4, 0, Math.nextUp(16.0), 0, 0, Double.NaN, 0, 0};
                    final double[] views = {view, view, view, -9_999.0};
                    final boolean[] actual = new boolean[3];
                    final boolean[] expected = new boolean[3];
                    final EntityTrackingRangeBatch batch = new EntityTrackingRangeBatch();
                    batch.prepare(128, positions, views, 3);
                    batch.setRow(0, 0, 0, 0, range, 4, new int[] {0, 1, 2}, 3, actual);
                    for (int row = 1; row < 128; row++) batch.setEmptyRow(row);
                    for (int player = 0; player < 3; player++) {
                        final double horizontal = Math.min(range, view);
                        expected[player] = positions[player * 3] * positions[player * 3] <= horizontal * horizontal
                            && positions[player * 3 + 1] * positions[player * 3 + 1] <= 16;
                    }
                    batch.compute(0, 1);
                    batch.compute(1, 3);
                    assertArrayEquals(expected, actual, "view=" + view + ", range=" + range + ", enabled=" + enabled);
                    batch.clear();
                }
            }
        }
    }

    @Test
    void publishedRowsIgnoreLaterRowsAndPlayerArrayGrowth() {
        LeavesXComputeExecutor.configure(4, 64, true, true);
        final int rows = 513;
        final int players = 1050; // 单行也超过一个比较片段，覆盖行中间的边界。
        final EntityTrackingRangeBatch range = new EntityTrackingRangeBatch();
        double[] positions = new double[3];
        double[] views = {16.0};
        range.prepare(rows, positions, views);
        final boolean[][] output = new boolean[rows][];
        final boolean[][] expected = new boolean[rows][];
        try (var pipeline = new org.leavesx.leavesx.runtime.LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            for (int row = 0; row < rows; row++) {
                if (row == 256) {
                    positions = Arrays.copyOf(positions, players * 3);
                    views = Arrays.copyOf(views, players);
                    for (int player = 1; player < players; player++) {
                        positions[player * 3] = player - 1;
                        views[player] = 16.0;
                    }
                }
                final int count = row >= 256 ? players : 1;
                final int[] indices = new int[count];
                output[row] = new boolean[count];
                expected[row] = new boolean[count];
                for (int player = 0; player < count; player++) {
                    indices[player] = player;
                    expected[row][player] = Math.abs(positions[player * 3] - row) <= 16.0;
                }
                range.setRow(row, row, 0, 0, 16.0, -1.0, indices, count, output[row]);
                if ((row + 1) % EntityTrackingRangeBatch.PIPELINE_ROWS == 0 || row + 1 == rows) {
                    final int from = row / EntityTrackingRangeBatch.PIPELINE_ROWS * EntityTrackingRangeBatch.PIPELINE_ROWS;
                    range.submitRows(pipeline, from, row + 1, positions, views);
                }
            }
            assertTrue(pipeline.finish());
        }
        for (int row = 0; row < rows; row++) assertArrayEquals(expected[row], output[row], "row=" + row);
        assertTrue(LeavesXComputeExecutor.metrics().workerItems() > 0);
        range.clear();
    }

    @Test
    void sparseRowsKeepPartitionWritesDisjointAcrossReuse() {
        final EntityTrackingRangeBatch batch = new EntityTrackingRangeBatch();
        for (final int rows : new int[] {4096, 1, 512}) {
            batch.prepare(rows, new double[] {0, 0, 0, 100, 0, 0}, new double[] {16, 16});
            final boolean[] first = {false, true};
            final boolean[] last = {false, true};
            for (int row = 0; row < rows; row++) {
                if (row == 0 || row == rows - 1) {
                    batch.setRow(row, 0, 0, 0, 16, 4, new int[] {0, 1}, 2, row == 0 ? first : last);
                } else batch.setEmptyRow(row);
            }
            batch.compute(0, 1);
            assertArrayEquals(new boolean[] {true, true}, first);
            batch.compute(1, batch.comparisonCount());
            assertArrayEquals(new boolean[] {true, false}, first);
            if (rows > 1) assertArrayEquals(new boolean[] {true, false}, last);
            batch.clear();
            batch.prepare(rows, new double[0], new double[0]);
            for (int row = 0; row < rows; row++) batch.setEmptyRow(row);
            batch.compute(0, 0);
            assertEquals(0, batch.comparisonCount());
            batch.clear();
        }
    }

    @Test
    void releasesPeakCapacityAndCanPrepareTheNextBatch() throws Exception {
        final EntityTrackingRangeBatch batch = new EntityTrackingRangeBatch();
        for (final int rows : new int[] {64, 16_385, 1}) {
            final double[] positions = {0.0, 0.0, 0.0};
            final double[] views = {16.0};
            batch.prepare(rows, positions, views);
            final boolean[] visible = new boolean[1];
            batch.setRow(0, 0.0, 0.0, 0.0, 16.0, -1.0, new int[] {0}, 1, visible);
            for (int row = 1; row < rows; row++) batch.setEmptyRow(row);
            batch.compute(0, batch.comparisonCount());
            assertArrayEquals(new boolean[] {true}, visible);
            final Object previousIndices = field(batch, "playerIndices");
            final Object previousData = field(batch, "entityData");
            batch.clear();
            assertEquals(0, batch.comparisonCount());
            assertNull(field(batch, "playerPositions"));
            assertNull(field(batch, "playerViewDistances"));
            if (rows > 16_384) {
                assertNotSame(previousIndices, field(batch, "playerIndices"));
                assertNotSame(previousData, field(batch, "entityData"));
                assertEquals(0, java.lang.reflect.Array.getLength(field(batch, "entityData")));
            } else {
                assertSame(previousIndices, field(batch, "playerIndices"));
                assertNull(((Object[]) previousIndices)[0]);
                assertNull(((Object[]) field(batch, "results"))[0]);
            }
        }
    }

    private static Object field(final EntityTrackingRangeBatch batch, final String name) throws Exception {
        final var field = EntityTrackingRangeBatch.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(batch);
    }

    @Test
    void cacheLimitCapsGrowthAndReloadOnlyReleasesFinishedBatch() throws Exception {
        final var node = CommentedConfigurationNode.root();
        node.node("async", "compute", "cache-max-candidates").set(80);
        LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
        final EntityTrackingRangeBatch batch = new EntityTrackingRangeBatch();
        for (final int rows : new int[] {64, 80, 81}) {
            batch.prepare(rows, new double[] {0, 0, 0}, new double[] {16});
            final boolean[] result = new boolean[1];
            batch.setRow(0, 0, 0, 0, 16, -1, new int[] {0}, 1, result);
            for (int row = 1; row < rows; row++) batch.setEmptyRow(row);
            batch.compute(0, batch.comparisonCount());
            assertTrue(result[0], "The cache limit must never truncate active input");
            final Object indices = field(batch, "playerIndices");
            batch.clear();
            assertEquals(rows <= 80 ? rows : 0, ((Object[]) field(batch, "playerIndices")).length);
            if (rows <= 80) assertSame(indices, field(batch, "playerIndices"));
        }
        batch.prepare(1, new double[] {0, 0, 0}, new double[] {16});
        final boolean[] result = new boolean[1];
        batch.setRow(0, 0, 0, 0, 16, -1, new int[] {0}, 1, result);
        node.node("async", "compute", "cache-max-candidates").set(0);
        LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
        batch.compute(0, 1);
        assertTrue(result[0]);
        batch.clear();
        assertEquals(0, ((double[]) field(batch, "entityData")).length);
        assertNull(field(batch, "playerPositions"));
        assertNull(field(batch, "playerViewDistances"));
    }

    @Test
    void defaultBudgetRetainsGrowthNearTheExistingSafetyCeiling() throws Exception {
        LeavesXRuntime.publish(LeavesXConfig.safeDefaults());
        final EntityTrackingRangeBatch batch = new EntityTrackingRangeBatch();
        for (final int rows : new int[] {12_000, 16_000}) {
            batch.prepare(rows, new double[0], new double[0]);
            for (int row = 0; row < rows; row++) batch.setEmptyRow(row);
            final Object indices = field(batch, "playerIndices");
            batch.clear();
            assertSame(indices, field(batch, "playerIndices"));
            assertTrue(((Object[]) indices).length <= 16_384);
        }
        final Object retained = field(batch, "playerIndices");
        batch.prepare(16_000, new double[0], new double[0]);
        assertSame(retained, field(batch, "playerIndices"));
        for (int row = 0; row < 16_000; row++) batch.setEmptyRow(row);
        batch.clear();
    }

    @AfterEach
    void restore() {
        LeavesXComputeExecutor.shutdown();
        LeavesXRuntime.publish(LeavesXConfig.safeDefaults());
    }

    @Test
    void matchesReferenceAcrossSplitRowsAndSpecialDistances() throws Exception {
        final Random random = new Random(0x526F774B65726EL);
        final double[] special = {-128.0, -0.0, 0.0, 1.0E-170, 1.0, 64.0, 128.0,
            Double.POSITIVE_INFINITY, Double.NaN};
        for (final boolean enabled : new boolean[] {false, true}) {
            final var node = CommentedConfigurationNode.root();
            node.node("performance", "compute", "tracker-row-kernel").set(enabled);
            LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
            for (int round = 0; round < 20; round++) {
                final int rows = 31;
                final int players = special.length;
                final double[] views = Arrays.copyOf(special, players);
                final double[] positions = new double[players * 3];
                for (int index = 0; index < positions.length; index++) {
                    positions[index] = random.nextInt(257) - 128;
                }
                final EntityTrackingRangeBatch batch = new EntityTrackingRangeBatch();
                batch.prepare(rows, positions, views);
                final boolean[][] actual = new boolean[rows][];
                final boolean[][] expected = new boolean[rows][];
                for (int row = 0; row < rows; row++) {
                    final int count = row % 5 == 0 ? 0 : players;
                    final int[] indices = new int[count];
                    final double x = random.nextInt(257) - 128;
                    final double y = random.nextInt(257) - 128;
                    final double z = random.nextInt(257) - 128;
                    final double range = special[row % players];
                    final double rangeY = special[(row + round) % players];
                    actual[row] = new boolean[count];
                    expected[row] = new boolean[count];
                    for (int index = 0; index < count; index++) {
                        final int player = (index + row) % players;
                        indices[index] = player;
                        final double dx = positions[player * 3] - x;
                        final double dz = positions[player * 3 + 2] - z;
                        final double horizontal = Math.min(range, views[player]);
                        boolean visible = dx * dx + dz * dz <= horizontal * horizontal;
                        if (visible && rangeY >= 0.0) {
                            final double dy = positions[player * 3 + 1] - y;
                            visible = dy * dy <= rangeY * rangeY;
                        }
                        expected[row][index] = visible;
                    }
                    batch.setRow(row, x, y, z, range, rangeY, indices, count, actual[row]);
                }
                // 非行边界分片，必须只写各自负责的比较区间。
                for (int start = 0, size = batch.comparisonCount(); start < size; start += 7) {
                    batch.compute(start, Math.min(size, start + 7));
                }
                for (int row = 0; row < rows; row++) assertArrayEquals(expected[row], actual[row]);
                batch.clear();
                assertEquals(0, batch.comparisonCount());
            }
        }
    }

    @Test
    void preservesVisibilityAtHorizontalAndVerticalBoundariesAfterBufferReuse() {
        final EntityTrackingRangeBatch batch = new EntityTrackingRangeBatch();
        final int[] players = {0, 1, 2};
        for (final double view : new double[] {16.0, 8.0, 32.0}) {
            final double[] coordinates = {16.0, 4.0, 0.0, Math.nextUp(16.0), 0.0, 0.0,
                0.0, Math.nextUp(4.0), 0.0};
            final boolean[] result = {false, true, true};
            batch.prepare(1, coordinates, new double[] {view, view, view});
            batch.setRow(0, 0.0, 0.0, 0.0, 16.0, 4.0, players, 3, result);
            LeavesXComputeExecutor.configure(1, 64, true, true);
            final int partitions = LeavesXComputeExecutor.invokeRangesWithCount(
                LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 3, 256, 64,
                (partition, start, end) -> batch.compute(start, end));
            assertEquals(1, partitions);
            assertArrayEquals(new boolean[] {view >= 16.0, false, false}, result);
            batch.clear();
            LeavesXComputeExecutor.shutdown();
        }
    }
}
