package org.leavesx.leavesx.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.leavesx.leavesx.config.ConfigurationTestSupport.publish;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ServerEntityGetter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;
import org.spongepowered.configurate.CommentedConfigurationNode;

/** 从真实目标查询入口检查单候选派发、选择器顺序及村民保护。 */
@Normal
public class TargetDistanceSnapshotTest {
    @AfterEach
    void restore() {
        LeavesXComputeExecutor.shutdown();
        publish(LeavesXConfig.safeDefaults());
    }

    @Test
    void oneCandidateUsesWorkerWhileConditionsAndCoordinatesStayOnCaller() {
        final Fixture fixture = new Fixture();
        final Zombie candidate = fixture.candidate(new AtomicReference<>(new Vec3(2.0, 1.0, 3.0)));
        when(fixture.conditions.test(fixture.level, fixture.source, candidate)).thenAnswer(ignored -> {
            assertSame(fixture.caller, Thread.currentThread());
            return true;
        });
        assertSame(candidate, fixture.find(List.of(candidate)));
        assertEquals(1L, LeavesXComputeExecutor.metrics().workerItems());
        assertEquals(0L, LeavesXComputeExecutor.metrics().callerItems());
    }

    @Test
    void rejectedCandidatesDoNotCreateEmptyWorkerTasks() {
        final Fixture fixture = new Fixture();
        final Zombie rejected = fixture.candidate(new AtomicReference<>(Vec3.ZERO));
        assertNull(fixture.find(List.of(rejected)));
        assertEquals(0L, LeavesXComputeExecutor.metrics().invocations());
    }

    @Test
    void customDistanceAfterRepeatedVanillaCandidatesFallsBackForTheWholeQuery() {
        final Fixture fixture = new Fixture();
        final Zombie ordinary = fixture.candidate(new AtomicReference<>(new Vec3(2, 0, 0)));
        final Zombie customized = mock(CustomDistanceZombie.class);
        when(customized.distanceToSqr(anyDouble(), anyDouble(), anyDouble())).thenReturn(0.0);
        when(fixture.conditions.test(fixture.level, fixture.source, ordinary)).thenReturn(true);
        when(fixture.conditions.test(fixture.level, fixture.source, customized)).thenReturn(true);
        final List<Zombie> candidates = new ArrayList<>(Collections.nCopies(512, ordinary));
        candidates.add(customized);
        assertSame(customized, fixture.find(candidates));
        assertEquals(0L, LeavesXComputeExecutor.metrics().invocations());
        org.mockito.Mockito.verify(customized).distanceToSqr(0.0, 0.0, 0.0);
    }

    private static class CustomDistanceZombie extends Zombie {
        CustomDistanceZombie() { super(((net.minecraft.world.entity.EntityType<net.minecraft.world.entity.monster.zombie.Zombie>) net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("zombie"))), null); }

        @Override
        public double distanceToSqr(final double x, final double y, final double z) {
            return 0.0;
        }
    }

    @Test
    void exceptionalPopulationPeakDoesNotRemainInTheThreadLocalCache() throws Exception {
        final Fixture fixture = new Fixture();
        final Zombie candidate = fixture.candidate(new AtomicReference<>(new Vec3(2.0, 0.0, 0.0)));
        when(fixture.conditions.test(fixture.level, fixture.source, candidate)).thenReturn(true);
        final var field = TargetDistanceSnapshot.class.getDeclaredField("BUFFERS");
        field.setAccessible(true);
        final ThreadLocal<?> buffers = (ThreadLocal<?>) field.get(null);
        final Object before = buffers.get();
        assertSame(candidate, fixture.find(java.util.Collections.nCopies(8_192, candidate)));
        assertPartitionStorageBounded(before);
        assertSame(candidate, fixture.find(java.util.Collections.nCopies(16_385, candidate)));
        org.junit.jupiter.api.Assertions.assertNotSame(before, buffers.get(),
            "A population spike must not pin its peak arrays for the lifetime of the server thread");
        assertSame(candidate, fixture.find(List.of(candidate)), "The next query must still work after releasing the cache");
    }

    private static void assertPartitionStorageBounded(final Object buffer) throws Exception {
        for (final String name : new String[] {"distances", "nearestByPartition"}) {
            final var field = buffer.getClass().getDeclaredField(name);
            field.setAccessible(true);
            assertTrue(java.lang.reflect.Array.getLength(field.get(buffer)) <= 64,
                "Partition results must not grow with candidate population");
        }
    }

    @Test
    void configuredLimitAvoidsGrowthEvictionAndCanDisableRetention() throws Exception {
        final Fixture fixture = new Fixture();
        final var node = CommentedConfigurationNode.root();
        node.node("async", "compute", "cache-max-candidates").set(80);
        publish(node);
        final Zombie candidate = fixture.candidate(new AtomicReference<>(new Vec3(2, 0, 0)));
        when(fixture.conditions.test(fixture.level, fixture.source, candidate)).thenReturn(true);
        final var field = TargetDistanceSnapshot.class.getDeclaredField("BUFFERS");
        field.setAccessible(true);
        final ThreadLocal<?> buffers = (ThreadLocal<?>) field.get(null);
        buffers.remove();
        final Object buffer = buffers.get();
        assertSame(candidate, fixture.find(Collections.nCopies(64, candidate)));
        assertSame(candidate, fixture.find(Collections.nCopies(80, candidate)));
        assertSame(buffer, buffers.get());
        final var entities = buffer.getClass().getDeclaredField("entities");
        entities.setAccessible(true);
        assertEquals(80, ((Object[]) entities.get(buffer)).length);
        for (final Object entity : (Object[]) entities.get(buffer)) assertNull(entity);

        node.node("async", "compute", "cache-max-candidates").set(0);
        publish(node);
        assertSame(candidate, fixture.find(List.of(candidate)));
        org.junit.jupiter.api.Assertions.assertNotSame(buffer, buffers.get());
    }

    @Test
    void strictComparisonRetainsFirstTieAndVanillaNanBehavior() {
        final Fixture fixture = new Fixture();
        final Zombie first = fixture.candidate(new AtomicReference<>(new Vec3(2.0, 0.0, 0.0)));
        final Zombie equal = fixture.candidate(new AtomicReference<>(new Vec3(-2.0, 0.0, 0.0)));
        final Zombie nan = fixture.candidate(new AtomicReference<>(new Vec3(Double.NaN, 0.0, 0.0)));
        for (final LivingEntity candidate : List.of(first, equal, nan)) {
            when(fixture.conditions.test(fixture.level, fixture.source, candidate)).thenReturn(true);
        }
        assertSame(first, fixture.find(List.of(first, equal, nan)));
        // 原版首个距离为 NaN 时不会被后续候选替换，不能改成排序后的最小值。
        assertSame(nan, fixture.find(List.of(nan, first, equal)));
    }

    @Test
    void selectorSideEffectsPreserveTheOriginalDistanceSamplingOrder() {
        final Fixture fixture = new Fixture();
        final AtomicReference<Vec3> firstPosition = new AtomicReference<>(new Vec3(1.0, 0.0, 0.0));
        final Zombie first = fixture.candidate(firstPosition);
        final Zombie second = fixture.candidate(new AtomicReference<>(new Vec3(2.0, 0.0, 0.0)));
        final List<LivingEntity> calls = new ArrayList<>();
        when(fixture.conditions.test(fixture.level, fixture.source, first)).thenAnswer(ignored -> {
            calls.add(first);
            return true;
        });
        when(fixture.conditions.test(fixture.level, fixture.source, second)).thenAnswer(ignored -> {
            calls.add(second);
            firstPosition.set(new Vec3(100.0, 0.0, 0.0));
            return true;
        });
        assertSame(first, fixture.find(List.of(first, second)));
        assertEquals(List.of(first, second), calls);
    }

    @Test
    void nestedSelectorQueryCannotOverwriteTheOuterSnapshot() {
        final Fixture fixture = new Fixture();
        final Zombie first = fixture.candidate(new AtomicReference<>(new Vec3(1.0, 0.0, 0.0)));
        final Zombie second = fixture.candidate(new AtomicReference<>(new Vec3(2.0, 0.0, 0.0)));
        final Zombie nested = fixture.candidate(new AtomicReference<>(new Vec3(100.0, 0.0, 0.0)));
        when(fixture.conditions.test(fixture.level, fixture.source, first)).thenReturn(true);
        when(fixture.conditions.test(fixture.level, fixture.source, nested)).thenReturn(true);
        when(fixture.conditions.test(fixture.level, fixture.source, second)).thenAnswer(ignored -> {
            assertSame(nested, fixture.find(List.of(nested)));
            return true;
        });
        assertSame(first, fixture.find(List.of(first, second)));
        assertEquals(2L, LeavesXComputeExecutor.lowLoadDispatchCount());
    }

    @Test
    void villagerSourceKeepsTheOriginalQueryEvenInLowLoadMode() {
        final Fixture fixture = new Fixture();
        final Villager villager = mock(Villager.class);
        final Zombie hostile = fixture.candidate(new AtomicReference<>(new Vec3(1.0, 0.0, 0.0)));
        when(fixture.conditions.test(fixture.level, villager, hostile)).thenReturn(true);
        assertSame(hostile, fixture.getter.getNearestEntity(List.of(hostile), fixture.conditions, villager, 0.0, 0.0, 0.0));
        assertEquals(0L, LeavesXComputeExecutor.metrics().invocations());
    }

    @Test
    void orderedPartitionReductionPreservesNaNsInfinitiesAndTies() {
        final Fixture fixture = new Fixture();
        LeavesXComputeExecutor.configure(3, 64, true, false);
        for (final int nanIndex : new int[]{-1, 0, 128, 256, 511}) {
            final List<Zombie> candidates = new ArrayList<>();
            Zombie expected = null;
            double best = -1.0;
            for (int index = 0; index < 512; index++) {
                final double x = index == nanIndex ? Double.NaN : index % 13 == 0 ? Double.POSITIVE_INFINITY : (index % 17) + 1.0;
                final Zombie candidate = fixture.candidate(new AtomicReference<>(new Vec3(x, 0.0, 0.0)));
                when(fixture.conditions.test(fixture.level, fixture.source, candidate)).thenReturn(true);
                candidates.add(candidate);
                final double distance = x * x;
                if (best == -1.0 || distance < best) { best = distance; expected = candidate; }
            }
            assertSame(expected, fixture.find(candidates), "NaN index " + nanIndex);
        }
        assertTrue(LeavesXComputeExecutor.metrics().workerItems() > 0);
    }

    @Test
    void pipelineAndLegacyPathsPreserveCrossPartitionTiesAndNanSelection() throws Exception {
        final Fixture fixture = new Fixture();
        LeavesXComputeExecutor.configure(3, 64, true, true);
        final Zombie far = fixture.candidate(new AtomicReference<>(new Vec3(5.0, 0.0, 0.0)));
        final Zombie first = fixture.candidate(new AtomicReference<>(new Vec3(1.0, 0.0, 0.0)));
        final Zombie equal = fixture.candidate(new AtomicReference<>(new Vec3(-1.0, 0.0, 0.0)));
        final Zombie nan = fixture.candidate(new AtomicReference<>(new Vec3(Double.NaN, 0.0, 0.0)));
        for (final Zombie candidate : List.of(far, first, equal, nan)) {
            when(fixture.conditions.test(fixture.level, fixture.source, candidate)).thenAnswer(ignored -> {
                assertSame(fixture.caller, Thread.currentThread());
                return true;
            });
        }
        for (final boolean enabled : new boolean[] {false, true}) {
            final var node = CommentedConfigurationNode.root();
            node.node("parallelism", "ai-target-search", "snapshot-pipeline").set(enabled);
            publish(node);
            for (final int size : new int[] {2_048, 2_049, 4_097}) {
                final List<Zombie> candidates = new ArrayList<>(Collections.nCopies(size, far));
                candidates.set(1, first);
                candidates.set(size - 1, equal);
                assertSame(first, fixture.find(candidates));
                candidates.set(size / 2, nan);
                assertSame(first, fixture.find(candidates));
                candidates.set(0, nan);
                assertSame(nan, fixture.find(candidates));
            }
        }
        assertTrue(LeavesXComputeExecutor.metrics().workerItems() > 0);
    }

    @Test
    void pipelineRetainsSamplingOrderAndAllowsReentryAfterPublishingItsFirstRange() {
        final Fixture fixture = new Fixture();
        final AtomicReference<Vec3> position = new AtomicReference<>(new Vec3(1, 0, 0));
        final Zombie earlier = fixture.candidate(position);
        final Zombie tail = fixture.candidate(new AtomicReference<>(new Vec3(2, 0, 0)));
        final Zombie nested = fixture.candidate(new AtomicReference<>(new Vec3(9, 0, 0)));
        when(fixture.conditions.test(fixture.level, fixture.source, earlier)).thenReturn(true);
        when(fixture.conditions.test(fixture.level, fixture.source, nested)).thenReturn(true);
        when(fixture.conditions.test(fixture.level, fixture.source, tail)).thenAnswer(ignored -> {
            assertSame(nested, fixture.find(List.of(nested)));
            position.set(new Vec3(100, 0, 0));
            return true;
        });
        final List<Zombie> candidates = new ArrayList<>(Collections.nCopies(4_096, earlier));
        candidates.add(tail);
        assertSame(earlier, fixture.find(candidates));
    }

    @Test
    void pipelineClosesPublishedRangesOnPredicateFailureBeforeReusingTheSnapshot() {
        final Fixture fixture = new Fixture();
        final Zombie earlier = fixture.candidate(new AtomicReference<>(new Vec3(1, 0, 0)));
        final Zombie tail = fixture.candidate(new AtomicReference<>(new Vec3(2, 0, 0)));
        when(fixture.conditions.test(fixture.level, fixture.source, earlier)).thenReturn(true);
        when(fixture.conditions.test(fixture.level, fixture.source, tail)).thenThrow(new IllegalStateException("测试筛选中断"));
        final List<Zombie> candidates = new ArrayList<>(Collections.nCopies(4_096, earlier));
        candidates.add(tail);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> fixture.find(candidates));
        when(fixture.conditions.test(fixture.level, fixture.source, earlier)).thenReturn(true);
        assertSame(earlier, fixture.find(List.of(earlier)));
    }

    @Test
    void firstFrozenRangeIsSubmittedBeforeCapturingTheTail() throws Exception {
        final Fixture fixture = new Fixture();
        final var field = LeavesXComputeExecutor.class.getDeclaredField("executor");
        field.setAccessible(true);
        final var pool = (java.util.concurrent.ThreadPoolExecutor)field.get(null);
        final Zombie first = fixture.candidate(new AtomicReference<>(new Vec3(1, 0, 0)));
        final Zombie tail = fixture.candidate(new AtomicReference<>(new Vec3(2, 0, 0)));
        when(fixture.conditions.test(fixture.level, fixture.source, first)).thenReturn(true);
        when(fixture.conditions.test(fixture.level, fixture.source, tail)).thenAnswer(ignored -> {
            assertTrue(pool.getTaskCount() > 0, "采集尾片之前，前面的冻结数据应已提交给工作线程");
            return true;
        });
        final List<Zombie> candidates = new ArrayList<>(Collections.nCopies(4_096, first));
        candidates.add(tail);
        assertSame(first, fixture.find(candidates));
        assertTrue(LeavesXComputeExecutor.metrics().workerItems() > 0);
    }

    @Test
    void rejectedSubmissionRecomputesEveryDistanceWithoutRepeatingPredicates() {
        final Fixture fixture = new Fixture();
        final Zombie first = fixture.candidate(new AtomicReference<>(new Vec3(8, 0, 0)));
        final Zombie nearest = fixture.candidate(new AtomicReference<>(new Vec3(1, 0, 0)));
        final var calls = new java.util.concurrent.atomic.AtomicInteger();
        for (final Zombie candidate : List.of(first, nearest)) {
            when(fixture.conditions.test(fixture.level, fixture.source, candidate)).thenAnswer(ignored -> {
                calls.incrementAndGet();
                return true;
            });
        }
        final List<Zombie> candidates = new ArrayList<>(Collections.nCopies(4_096, first));
        candidates.add(nearest);
        final var reject = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var ignored = org.mockito.Mockito.mockStatic(LeavesXComputeExecutor.class, invocation -> {
            if (invocation.getMethod().getName().equals("tryExecuteSnapshot") && reject.getAndSet(false)) {
                throw new java.util.concurrent.RejectedExecutionException("测试提交失败");
            }
            return invocation.callRealMethod();
        })) {
            assertSame(nearest, fixture.find(candidates));
            assertEquals(4_097, calls.get());
            assertEquals(1, LeavesXComputeExecutor.metrics().fullRecomputationRequests());
        }
    }

    private static final class Fixture {
        private final Thread caller = Thread.currentThread();
        private final ServerEntityGetter getter = mock(ServerEntityGetter.class, CALLS_REAL_METHODS);
        private final ServerLevel level = mock(ServerLevel.class);
        private final Zombie source = mock(Zombie.class);
        private final TargetingConditions conditions = mock(TargetingConditions.class);

        private Fixture() {
            publish(LeavesXConfig.safeDefaults());
            LeavesXComputeExecutor.configure(1, 64, true, true);
            when(this.getter.getLevel()).thenReturn(this.level);
        }

        private Zombie candidate(final AtomicReference<Vec3> position) {
            final Zombie candidate = mock(Zombie.class);
            when(candidate.getX()).thenAnswer(ignored -> {
                assertSame(this.caller, Thread.currentThread());
                return position.get().x;
            });
            when(candidate.getY()).thenAnswer(ignored -> {
                assertSame(this.caller, Thread.currentThread());
                return position.get().y;
            });
            when(candidate.getZ()).thenAnswer(ignored -> {
                assertSame(this.caller, Thread.currentThread());
                return position.get().z;
            });
            when(candidate.distanceToSqr(anyDouble(), anyDouble(), anyDouble())).thenCallRealMethod();
            return candidate;
        }

        private <T extends LivingEntity> T find(final List<T> candidates) {
            return this.getter.getNearestEntity(candidates, this.conditions, this.source, 0.0, 0.0, 0.0);
        }
    }
}
