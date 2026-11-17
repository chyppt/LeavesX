package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 单项有效任务必须真的交给工作线程，异常后不得留下仍能写结果的任务。 */
@Normal
class LowLoadComputeTest {
    @AfterEach void stop() { LeavesXComputeExecutor.shutdown(); }

    @Test
    void oneItemRunsOnceOnAWorkerAndIsRecorded() {
        LeavesXComputeExecutor.configure(1, 64, true, true);
        final Thread caller = Thread.currentThread();
        final AtomicReference<Thread> computedBy = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        assertTrue(LeavesXComputeExecutor.shouldAttempt(LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 1, 256, 64));
        assertEquals(1, LeavesXComputeExecutor.invokeRangesWithCount(
            LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 1, 256, 64,
            (partition, start, end) -> {
                assertEquals(0, partition);
                assertEquals(0, start);
                assertEquals(1, end);
                computedBy.set(Thread.currentThread());
                calls.incrementAndGet();
            }));
        assertNotSame(caller, computedBy.get());
        assertEquals(1, calls.get());
        assertEquals(1L, LeavesXComputeExecutor.lowLoadDispatchCount());
        assertEquals(1L, LeavesXComputeExecutor.metrics().workerItems());
        assertEquals(0L, LeavesXComputeExecutor.metrics().callerItems());
    }

    @Test
    void successiveCompletedSmallTasksKeepTheWorkerAvailable() {
        LeavesXComputeExecutor.configure(1, 64, true, true);
        final Thread caller = Thread.currentThread();
        for (int iteration = 0; iteration < 1_000; iteration++) {
            assertTrue(LeavesXComputeExecutor.shouldAttempt(
                LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 1, 256, 64),
                "上一份结果已经发布，不能仍把它计为繁忙任务");
            assertEquals(1, LeavesXComputeExecutor.invokeRangesWithCount(
                LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 1, 256, 64,
                (partition, start, end) -> assertNotSame(caller, Thread.currentThread())));
        }
        assertEquals(1_000L, LeavesXComputeExecutor.lowLoadDispatchCount());
        assertEquals(0L, LeavesXComputeExecutor.metrics().unavailableParallelismFallbacks());
    }

    @ParameterizedTest
    @EnumSource(value = LeavesXComputeExecutor.Workload.class, names = {
        "AI_TARGET_SORT", "AI_TARGET_SELECT", "ENTITY_ACTIVATION", "ENTITY_TRACKING", "MOB_SPAWN_COUNTING", "MOB_COLLISION"
    })
    void allExistingNumericWorkloadsAcceptOneItem(final LeavesXComputeExecutor.Workload workload) {
        LeavesXComputeExecutor.configure(2, 64, true, true);
        final Thread caller = Thread.currentThread();
        final AtomicReference<Thread> worker = new AtomicReference<>();
        assertTrue(LeavesXComputeExecutor.shouldAttempt(workload, 1, 2_048, 512));
        assertEquals(1, LeavesXComputeExecutor.invokeRangesWithCount(workload, 1, 2_048, 512,
            (partition, start, end) -> worker.set(Thread.currentThread())));
        assertNotSame(caller, worker.get());
        assertEquals(1L, LeavesXComputeExecutor.metrics().workerItems());
        assertEquals(0L, LeavesXComputeExecutor.metrics().callerItems());
    }

    @Test
    void disabledModeAndEmptyTasksKeepTheOriginalAdmission() {
        LeavesXComputeExecutor.configure(2, 64, true, false);
        assertFalse(LeavesXComputeExecutor.shouldAttempt(LeavesXComputeExecutor.Workload.AI_TARGET_SELECT, 1, 256, 64));
        assertEquals(0, LeavesXComputeExecutor.invokeRangesWithCount(
            LeavesXComputeExecutor.Workload.AI_TARGET_SELECT, 1, 256, 64,
            (partition, start, end) -> fail("disabled small task was submitted")));
        LeavesXComputeExecutor.configure(2, 64, true, true);
        assertEquals(0, LeavesXComputeExecutor.invokeRangesWithCount(
            LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 0, 256, 64,
            (partition, start, end) -> fail("empty task was submitted")));
        assertEquals(0L, LeavesXComputeExecutor.lowLoadDispatchCount());
    }

    @Test
    void stoppedPoolDoesNotKeepSmallSensorSnapshotModeActive() {
        LeavesXComputeExecutor.configure(1, 64, true, true);
        assertTrue(LeavesXComputeExecutor.lowLoadWorkersEnabled());
        LeavesXComputeExecutor.shutdown();
        assertFalse(LeavesXComputeExecutor.lowLoadWorkersEnabled());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 512, 2_048})
    void occupiedWorkerReturnsImmediatelyWithoutQueueing(final int size) throws Exception {
        LeavesXComputeExecutor.configure(1, 64, true, true);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        assertTrue(LeavesXComputeExecutor.tryExecuteSnapshot(() -> {
            started.countDown();
            await(release);
        }, ignored -> fail("running task must not be cancelled as queued")));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(0, LeavesXComputeExecutor.invokeRangesWithCount(
                LeavesXComputeExecutor.Workload.AI_TARGET_SORT, size, 256, 64,
                (partition, start, end) -> fail("busy pool must request a complete caller calculation")));
            assertEquals(0, LeavesXComputeExecutor.metrics().queuedTasks());
        } finally { release.countDown(); }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 512, 2_048})
    void failedTaskFinishesBeforeFullRecomputationIsRequested(final int size) {
        LeavesXComputeExecutor.configure(2, 64, true, true);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Thread> writer = new AtomicReference<>();
        assertEquals(0, LeavesXComputeExecutor.invokeRangesWithCount(
            LeavesXComputeExecutor.Workload.AI_TARGET_SORT, size, 256, 64,
            (partition, start, end) -> {
                writer.set(Thread.currentThread());
                calls.incrementAndGet();
                throw new IllegalStateException("injected single-range failure");
            }));
        assertNotSame(Thread.currentThread(), writer.get());
        assertEquals(1, calls.get());
        assertEquals(1L, LeavesXComputeExecutor.metrics().fullRecomputationRequests());
        assertEquals(0L, LeavesXComputeExecutor.lowLoadDispatchCount());
    }

    @Test
    void nestedSmallTaskUsesItsCurrentWorkerWithoutSelfBlocking() {
        LeavesXComputeExecutor.configure(1, 64, true, true);
        final AtomicInteger calls = new AtomicInteger();
        assertEquals(1, LeavesXComputeExecutor.invokeRangesWithCount(
            LeavesXComputeExecutor.Workload.AI_TARGET_SELECT, 1, 256, 64,
            (partition, start, end) -> {
                final Thread outer = Thread.currentThread();
                assertEquals(1, LeavesXComputeExecutor.invokeRangesWithCount(
                    LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 1, 256, 64,
                    (nested, from, to) -> {
                        assertSame(outer, Thread.currentThread());
                        calls.incrementAndGet();
                    }));
            }));
        assertEquals(1, calls.get());
    }

    @Test
    void interruptedCallerWaitsForTheWorkerBeforeRecomputation() throws Exception {
        LeavesXComputeExecutor.configure(1, 64, true, true);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Thread> caller = new AtomicReference<>();
        final AtomicInteger result = new AtomicInteger();
        try (var callers = Executors.newSingleThreadExecutor()) {
            final var outcome = callers.submit(() -> {
                caller.set(Thread.currentThread());
                final int partitions = LeavesXComputeExecutor.invokeRangesWithCount(
                    LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 1, 256, 64,
                    (partition, start, end) -> {
                        started.countDown();
                        await(release);
                        result.set(1);
                    });
                assertEquals(0, partitions);
                assertTrue(Thread.currentThread().isInterrupted());
                result.set(2);
                return partitions;
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                caller.get().interrupt();
                assertFalse(outcome.isDone(), "不能在工作线程仍写结果时开始重算");
            } finally {
                release.countDown();
            }
            assertEquals(0, outcome.get(5, TimeUnit.SECONDS));
        }
        assertEquals(2, result.get());
        assertEquals(1L, LeavesXComputeExecutor.metrics().fullRecomputationRequests());
    }

    @Test
    void concurrentSmallTasksNeverExecuteBothWorkerAndFallback() throws Exception {
        LeavesXComputeExecutor.configure(2, 64, true, true);
        final AtomicIntegerArray writes = new AtomicIntegerArray(400);
        try (var callers = Executors.newFixedThreadPool(4)) {
            final var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int caller = 0; caller < 4; caller++) {
                final int offset = caller * 100;
                futures.add(callers.submit(() -> {
                    for (int item = offset; item < offset + 100; item++) {
                        final int index = item;
                        final int completed = LeavesXComputeExecutor.invokeRangesWithCount(
                            LeavesXComputeExecutor.Workload.AI_TARGET_SELECT, 1, 256, 64,
                            (partition, start, end) -> writes.incrementAndGet(index));
                        if (completed == 0) writes.incrementAndGet(index);
                    }
                }));
            }
            for (final var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        for (int index = 0; index < writes.length(); index++) assertEquals(1, writes.get(index), "任务 " + index);
    }

    @Test
    void shutdownRacingSubmissionNeverLeavesASmallTaskWaiting() throws Exception {
        try (var callers = Executors.newSingleThreadExecutor()) {
            for (int round = 0; round < 40; round++) {
                LeavesXComputeExecutor.configure(1, 64, true, true);
                final AtomicInteger writes = new AtomicInteger();
                final var outcome = callers.submit(() -> {
                    final int completed = LeavesXComputeExecutor.invokeRangesWithCount(
                        LeavesXComputeExecutor.Workload.ENTITY_TRACKING, 1, 256, 64,
                        (partition, start, end) -> writes.incrementAndGet());
                    if (completed == 0) writes.incrementAndGet();
                });
                LeavesXComputeExecutor.shutdown();
                outcome.get(5, TimeUnit.SECONDS);
                assertEquals(1, writes.get());
            }
        }
    }

    private static void await(final CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker release timed out");
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
