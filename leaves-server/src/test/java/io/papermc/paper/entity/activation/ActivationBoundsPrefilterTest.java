package io.papermc.paper.entity.activation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Arrays;
import java.util.Random;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;

/** 原始逐玩家精确判断是对照；预筛选必须保留每个实体的结果与严格边界。 */
@Normal
public class ActivationBoundsPrefilterTest {
    @AfterEach
    void stop() {
        LeavesXComputeExecutor.shutdown();
    }

    @Test
    void spatialPlayerOrderingPreservesEveryActivationDecision() {
        final Random random = new Random(0x5350415449414CL);
        final int boxes = ActivationType.values().length + 1;
        for (final int players : new int[] {8, 9, 32, 257}) {
            final int size = 2_049;
            final double[] entities = new double[size * 6];
            final byte[] types = new byte[size];
            final double[] bounds = new double[players * boxes * 6];
            final double[] scratch = new double[bounds.length];
            final double[] groups = new double[((players + 3) / 4) * boxes * 6];
            final int[] order = new int[players];
            final int[] orderScratch = new int[players];
            final boolean[] expected = new boolean[size];
            final boolean[] actual = new boolean[size];
            for (int entity = 0; entity < size; entity++) {
                final int offset = entity * 6;
                final double x = random.nextDouble(-2_048, 2_048);
                final double z = random.nextDouble(-2_048, 2_048);
                entities[offset] = x;
                entities[offset + 1] = -64;
                entities[offset + 2] = z;
                entities[offset + 3] = x + 0.6;
                entities[offset + 4] = -62;
                entities[offset + 5] = z + 0.6;
                types[entity] = (byte)(entity % (boxes - 1));
            }
            for (int player = 0; player < players; player++) {
                final int source = (players - player - 1) * 96;
                for (int box = 0; box < boxes; box++) {
                    final int offset = (player * boxes + box) * 6;
                    final int range = 8 + box * 3;
                    bounds[offset] = source - range;
                    bounds[offset + 1] = -128;
                    bounds[offset + 2] = source - range;
                    bounds[offset + 3] = source + range;
                    bounds[offset + 4] = 320;
                    bounds[offset + 5] = source + range;
                }
            }
            ActivationRangeSnapshot.evaluateWorldRange(entities, types, bounds, expected, players, 0, size);
            ActivationRangeSnapshot.snapshotActivationGroups(bounds, groups, players);
            ActivationRangeSnapshot.evaluateWorldSnapshot(entities, types, bounds, null, groups, actual,
                size, players, 256, 64);
            assertArrayEquals(expected, actual, "原玩家顺序，players=" + players);
            ActivationRangeSnapshot.spatiallyOrderPlayerBounds(bounds, scratch, order, orderScratch, players);
            ActivationRangeSnapshot.snapshotActivationGroups(bounds, groups, players);
            Arrays.fill(actual, false);
            ActivationRangeSnapshot.evaluateWorldSnapshot(entities, types, bounds, null, groups, actual,
                size, players, 256, 64);
            assertArrayEquals(expected, actual, "空间分组后，players=" + players);
            for (int player = 1; players >= 16 && player < players; player++) {
                org.junit.jupiter.api.Assertions.assertTrue(Double.compare(
                    bounds[(player - 1) * boxes * 6], bounds[player * boxes * 6]) <= 0);
            }
            // 非有限框只能影响它自己的精确判断，不能令空间分组漏掉其他正常玩家。
            bounds[0] = Double.NaN;
            bounds[boxes * 6 + 3] = Double.POSITIVE_INFINITY;
            ActivationRangeSnapshot.evaluateWorldRange(entities, types, bounds, expected, players, 0, size);
            ActivationRangeSnapshot.spatiallyOrderPlayerBounds(bounds, scratch, order, orderScratch, players);
            ActivationRangeSnapshot.snapshotActivationGroups(bounds, groups, players);
            ActivationRangeSnapshot.evaluateWorldSnapshot(entities, types, bounds, null, groups, actual,
                size, players, 256, 64);
            assertArrayEquals(expected, actual, "非有限玩家框，players=" + players);
        }
    }

    @Test
    void matchesOriginalForClusteredAndSeparatedPlayersIncludingNonFiniteBounds() {
        final Random random = new Random(0x556E696F6EL);
        final int boxes = ActivationType.values().length + 1;
        final int size = 2_049;
        final double[] entities = new double[size * 6];
        final byte[] types = new byte[size];
        for (int index = 0; index < size; index++) {
            final int offset = index * 6;
            for (int axis = 0; axis < 3; axis++) {
                entities[offset + axis] = random.nextDouble() * 1024.0 - 512.0;
                entities[offset + axis + 3] = entities[offset + axis] + random.nextDouble() * 3.0;
            }
            types[index] = index % 11 == 0 ? ActivationRangeSnapshot.SKIP_ENTITY
                : index % 17 == 0 ? ActivationRangeSnapshot.DEFAULT_ACTIVATION_ENTITY
                : (byte)(index % (boxes - 1));
        }
        for (final int players : new int[] {1, 4, 10, 64}) {
            final double[] bounds = new double[players * boxes * 6];
            final double[] unions = new double[boxes * 6];
            final double[] groups = new double[((players + 3) / 4) * boxes * 6];
            for (int round = 0; round < 8; round++) {
                for (int player = 0; player < players; player++) {
                    for (int box = 0; box < boxes; box++) {
                        final int offset = (player * boxes + box) * 6;
                        for (int axis = 0; axis < 3; axis++) {
                            final double origin = round % 2 == 0 ? player * 64.0 : player;
                            bounds[offset + axis] = origin - 16.0 - box * 4.0;
                            bounds[offset + axis + 3] = origin + 16.0 + box * 4.0;
                        }
                    }
                }
                if (round >= 2) {
                    bounds[round % bounds.length] = round == 2 ? Double.NaN : Double.POSITIVE_INFINITY;
                }
                ActivationRangeSnapshot.snapshotActivationUnions(bounds, unions, players);
                ActivationRangeSnapshot.snapshotActivationGroups(bounds, groups, players);
                final boolean[] expected = new boolean[size];
                final boolean[] actual = new boolean[size];
                Arrays.fill(actual, true);
                ActivationRangeSnapshot.evaluateWorldRange(entities, types, bounds, expected, players, 0, size);
                for (final boolean parallel : new boolean[] {false, true}) {
                    if (parallel) LeavesXComputeExecutor.configure(4, 64, true, true);
                    else LeavesXComputeExecutor.shutdown();
                    ActivationRangeSnapshot.evaluateWorldSnapshot(entities, types, bounds, unions,
                        players >= 8 ? groups : null, actual,
                        size, players, 256, 64);
                    assertArrayEquals(expected, actual, "players=" + players + ", round=" + round);
                }
            }
        }
    }

    @Test
    void touchingUnionFacesRemainInactive() {
        final int boxes = ActivationType.values().length + 1;
        final double[] bounds = new double[4 * boxes * 6];
        for (int box = 0; box < 4 * boxes; box++) {
            Arrays.fill(bounds, box * 6, box * 6 + 3, -16.0);
            Arrays.fill(bounds, box * 6 + 3, box * 6 + 6, 16.0);
        }
        final double[] unions = new double[boxes * 6];
        ActivationRangeSnapshot.snapshotActivationUnions(bounds, unions, 4);
        final double[] entities = {16.0, -1.0, -1.0, 17.0, 1.0, 1.0,
            Math.nextDown(16.0), -1.0, -1.0, 17.0, 1.0, 1.0};
        final boolean[] actual = {true, false};
        ActivationRangeSnapshot.evaluateWorldRange(entities, new byte[] {0, 0}, bounds, unions, null,
            actual, 4, 0, 2);
        assertArrayEquals(new boolean[] {false, true}, actual);
    }

    @Test
    void typeRangeLargerThanSimulationCappedQueryDoesNotActivate() {
        final int boxes = ActivationType.values().length + 1;
        final double[] bounds = new double[boxes * 6];
        // maxBB 被模拟距离截断为 ±24，怪物框 ±64；Paper 的空间查询本来不会访问 x=40 的实体。
        Arrays.fill(bounds, 0, 3, -24.0);
        Arrays.fill(bounds, 3, 6, 24.0);
        for (int box = 1; box < boxes; box++) {
            Arrays.fill(bounds, box * 6, box * 6 + 3, -64.0);
            Arrays.fill(bounds, box * 6 + 3, box * 6 + 6, 64.0);
        }
        final double[] entities = {40.0, 0.0, 0.0, 41.0, 2.0, 1.0, 10.0, 0.0, 0.0, 11.0, 2.0, 1.0};
        final byte[] types = {(byte)ActivationType.MONSTER.ordinal(), (byte)ActivationType.MONSTER.ordinal()};
        final boolean[] actual = {true, false};
        ActivationRangeSnapshot.evaluateWorldRange(entities, types, bounds, actual, 1, 0, 2);
        assertArrayEquals(new boolean[] {false, true}, actual);
    }
}
