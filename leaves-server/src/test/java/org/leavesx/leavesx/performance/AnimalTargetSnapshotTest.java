package org.leavesx.leavesx.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.leavesx.leavesx.config.ConfigurationTestSupport.publish;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;

/** 验证动物目标快照只并行数值归约，候选判定和引用访问留在原线程。 */
@Normal
public class AnimalTargetSnapshotTest {
    @Test
    void populationPeakReleasesTheCachedArraysWithoutLosingTheSelectedAnimal() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(2, 64, true, false);
        final Chicken candidate = mock(Chicken.class);
        when(candidate.position()).thenReturn(new Vec3(1.0, 0.0, 0.0));
        final var field = AnimalTargetSnapshot.class.getDeclaredField("BUFFERS");
        field.setAccessible(true);
        final ThreadLocal<?> buffers = (ThreadLocal<?>) field.get(null);
        final Object before = buffers.get();
        assertSame(candidate, AnimalTargetSnapshot.findNearest(
            java.util.Collections.nCopies(8_192, candidate), ignored -> true, mock(Chicken.class), false).entity());
        for (final String name : new String[] {"distances", "nearestByPartition"}) {
            final var resultField = before.getClass().getDeclaredField(name);
            resultField.setAccessible(true);
            assertTrue(java.lang.reflect.Array.getLength(resultField.get(before)) <= 64,
                "Only partition winners should be retained");
        }
        final var result = AnimalTargetSnapshot.findNearest(
            java.util.Collections.nCopies(16_385, candidate), ignored -> true, mock(Chicken.class), false);
        assertSame(candidate, result.entity());
        org.junit.jupiter.api.Assertions.assertNotSame(before, buffers.get());
    }

    @AfterEach
    void restore() {
        LeavesXComputeExecutor.shutdown();
        publish(LeavesXConfig.safeDefaults());
    }

    @Test
    void zeroCacheLimitReleasesStorageAfterCompletingAnimalSelection() throws Exception {
        final var node = org.spongepowered.configurate.CommentedConfigurationNode.root();
        node.node("async", "compute", "cache-max-candidates").set(0);
        publish(node);
        LeavesXComputeExecutor.configure(1, 64, true, true);
        final Chicken candidate = mock(Chicken.class);
        when(candidate.position()).thenReturn(new Vec3(1, 0, 0));
        final var field = AnimalTargetSnapshot.class.getDeclaredField("BUFFERS");
        field.setAccessible(true);
        final ThreadLocal<?> buffers = (ThreadLocal<?>) field.get(null);
        buffers.remove();
        final Object buffer = buffers.get();
        assertSame(candidate, AnimalTargetSnapshot.findNearest(
            List.of(candidate), ignored -> true, mock(Chicken.class), false).entity());
        org.junit.jupiter.api.Assertions.assertNotSame(buffer, buffers.get());
        final var entities = buffer.getClass().getDeclaredField("entities");
        entities.setAccessible(true);
        for (final Object entity : (Object[]) entities.get(buffer)) org.junit.jupiter.api.Assertions.assertNull(entity);
    }

    @Test
    void filtersOnCallerAndUsesWorkersOnlyForPrimitiveDistanceCalculation() {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(2, 64, true, false);
        final Thread caller = Thread.currentThread();
        final List<Chicken> candidates = new ArrayList<>(512);
        Chicken nearest = null;
        for (int index = 0; index < 512; index++) {
            final Chicken candidate = mock(Chicken.class);
            final int distance = 512 - index;
            when(candidate.position()).thenAnswer(ignored -> {
                assertSame(caller, Thread.currentThread());
                return new Vec3(distance, 0.0, 0.0);
            });
            candidates.add(candidate);
            if (index == 511) nearest = candidate;
        }

        assertTrue(AnimalTargetSnapshot.canSnapshot(candidates.getFirst(), candidates));
        final List<Chicken> evaluated = new ArrayList<>(candidates.size());
        final AnimalTargetSnapshot.Match<Chicken> result = AnimalTargetSnapshot.findNearest(
            candidates,
            candidate -> {
                assertSame(caller, Thread.currentThread());
                evaluated.add(candidate);
                return true;
            },
            mock(Chicken.class), false
        );

        assertEquals(candidates, evaluated);
        assertSame(nearest, result.entity());
        assertTrue(LeavesXComputeExecutor.metrics().workerItems() > 0L);
    }

    @Test
    void preservesFirstAndLastTieRulesAndNaNBehavior() {
        final Chicken first = at(2.0);
        final Chicken equal = at(-2.0);
        final Chicken nan = at(Double.NaN);
        final Chicken source = mock(Chicken.class);
        final List<Chicken> candidates = List.of(first, equal, nan);

        final var firstMatch = AnimalTargetSnapshot.findNearest(candidates, ignored -> true, source, false);
        final var lastMatch = AnimalTargetSnapshot.findNearest(candidates, ignored -> true, source, true);
        final var nanFirst = AnimalTargetSnapshot.findNearest(List.of(nan, first, equal), ignored -> true,
            source, true);

        assertSame(first, firstMatch.entity());
        assertSame(nan, lastMatch.entity());
        // 原版 !(distance > best) 在 best 为 NaN 后仍接受下一个有限距离。
        assertSame(equal, nanFirst.entity());
        assertEquals(4.0, nanFirst.distanceSquared());
        assertFalse(AnimalTargetSnapshot.supportsVanillaFarmAnimal(net.minecraft.world.entity.npc.villager.Villager.class));
    }

    @Test
    void multiplePartitionsKeepTieRulesAndNaNResets() {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(3, 64, true, false);
        final Chicken source = mock(Chicken.class);
        for (final boolean lastTieWins : new boolean[]{false, true}) {
            for (final int nanIndex : new int[]{-1, 0, 128, 256, 511}) {
                final List<Chicken> candidates = new ArrayList<>();
                Chicken expected = null;
                double best = Double.MAX_VALUE;
                for (int index = 0; index < 512; index++) {
                    final double x = index == nanIndex ? Double.NaN : index % 13 == 0 ? Double.POSITIVE_INFINITY : (index % 17) + 1.0;
                    final Chicken candidate = at(x);
                    candidates.add(candidate);
                    final double distance = x * x;
                    if (lastTieWins ? !(distance > best) : distance < best) { best = distance; expected = candidate; }
                }
                final var result = AnimalTargetSnapshot.findNearest(candidates, ignored -> true, source, lastTieWins);
                assertSame(expected, result.entity(), "lastTieWins=" + lastTieWins + ", NaN=" + nanIndex);
                assertEquals(Double.doubleToRawLongBits(best), Double.doubleToRawLongBits(result.distanceSquared()));
            }
        }
        assertTrue(LeavesXComputeExecutor.metrics().workerItems() > 0);
    }

    @Test
    void samplesSourceAfterEachEligibilityCheckWithoutReadingEntitiesOnWorkers() {
        final Chicken source = mock(Chicken.class);
        final Chicken first = at(5);
        final Chicken second = at(10);
        final double[] sourceX = {0};
        when(source.getX()).thenAnswer(ignored -> sourceX[0]);
        final var result = AnimalTargetSnapshot.findNearest(List.of(first, second), candidate -> {
            if (candidate == second) sourceX[0] = 10;
            return true;
        }, source, false);
        assertSame(second, result.entity());
        assertEquals(0.0, result.distanceSquared());
    }

    @Test
    void nestedEligibilityQueryAndThrownPredicateDoNotContaminateLaterQueries() {
        final Chicken source = mock(Chicken.class);
        final Chicken first = at(4);
        final Chicken second = at(3);
        final Chicken nested = at(1);
        final var result = AnimalTargetSnapshot.findNearest(List.of(first, second), candidate -> {
            assertSame(nested, AnimalTargetSnapshot.findNearest(List.of(nested), ignored -> true, source, false).entity());
            return true;
        }, source, false);
        assertSame(second, result.entity());
        assertThrows(IllegalStateException.class, () -> AnimalTargetSnapshot.findNearest(
            List.of(first, second), candidate -> {
                if (candidate == second) throw new IllegalStateException("测试资格判断异常");
                return true;
            }, source, false));
        assertSame(nested, AnimalTargetSnapshot.findNearest(List.of(nested), ignored -> true, source, false).entity());
    }

    @Test
    void pipelinePreservesFirstAndLastTiesAndNanResetAcrossRangeBoundaries() {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(3, 64, true, true);
        final Thread owner = Thread.currentThread();
        final Chicken source = mock(Chicken.class);
        when(source.getX()).thenAnswer(ignored -> { assertSame(owner, Thread.currentThread()); return 0.0; });
        final Chicken far = at(8);
        final Chicken first = at(1);
        final Chicken equal = at(-1);
        final Chicken nan = at(Double.NaN);
        when(far.position()).thenAnswer(ignored -> { assertSame(owner, Thread.currentThread()); return new Vec3(8, 0, 0); });
        for (final int size : new int[] {2_048, 2_049, 4_097}) {
            final List<Chicken> candidates = new ArrayList<>(java.util.Collections.nCopies(size, far));
            candidates.set(1, first);
            candidates.set(size - 1, equal);
            final java.util.function.Predicate<Chicken> eligible = candidate -> {
                assertSame(owner, Thread.currentThread());
                return true;
            };
            assertSame(first, AnimalTargetSnapshot.findNearest(candidates, eligible, source, false).entity());
            assertSame(equal, AnimalTargetSnapshot.findNearest(candidates, eligible, source, true).entity());
            candidates.set(size / 2, nan);
            assertSame(first, AnimalTargetSnapshot.findNearest(candidates, eligible, source, false).entity());
            assertSame(equal, AnimalTargetSnapshot.findNearest(candidates, eligible, source, true).entity());
            candidates.set(size - 1, nan);
            assertSame(nan, AnimalTargetSnapshot.findNearest(candidates, eligible, source, true).entity());
        }
    }

    @Test
    void pipelineKeepsMovingSourceSamplingAndNestedQueriesIsolated() {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(3, 64, true, true);
        final Chicken source = mock(Chicken.class);
        final Chicken first = at(1);
        final Chicken tail = at(2);
        final Chicken nested = at(20);
        final var origin = new java.util.concurrent.atomic.AtomicReference<>(0.0);
        when(source.getX()).thenAnswer(ignored -> origin.get());
        final List<Chicken> candidates = new ArrayList<>(java.util.Collections.nCopies(4_096, first));
        candidates.add(tail);
        final var result = AnimalTargetSnapshot.findNearest(candidates, candidate -> {
            if (candidate == tail) {
                assertSame(nested, AnimalTargetSnapshot.findNearest(List.of(nested), ignored -> true, source, false).entity());
                origin.set(100.0);
            }
            return true;
        }, source, false);
        assertSame(first, result.entity());
        assertEquals(1.0, result.distanceSquared());
    }

    @Test
    void pipelinePredicateFailureAndInterruptionNeverLeaveWritersUsingRecycledArrays() {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(2, 64, true, true);
        final Chicken source = mock(Chicken.class);
        final Chicken first = at(4);
        final Chicken tail = at(1);
        final List<Chicken> candidates = new ArrayList<>(java.util.Collections.nCopies(4_096, first));
        candidates.add(tail);
        assertThrows(IllegalStateException.class, () -> AnimalTargetSnapshot.findNearest(candidates, candidate -> {
            if (candidate == tail) throw new IllegalStateException("测试采集中断");
            return true;
        }, source, false));
        try {
            assertSame(tail, AnimalTargetSnapshot.findNearest(candidates, candidate -> {
                if (candidate == tail) Thread.currentThread().interrupt();
                return true;
            }, source, false).entity());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertSame(tail, AnimalTargetSnapshot.findNearest(List.of(tail), ignored -> true, source, false).entity());
    }

    private static Chicken at(final double x) {
        final Chicken chicken = mock(Chicken.class);
        when(chicken.position()).thenReturn(new Vec3(x, 0.0, 0.0));
        return chicken;
    }
}
