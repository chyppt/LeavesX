package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig.ComputeSchedulingSettings;

/** 低优先级生成计算只能使用非预留线程，拒绝时不得派发半份任务。 */
@Normal
public class ComputeSchedulingTest {
    private static final LeavesXComputeExecutor.Workload BACKGROUND = LeavesXComputeExecutor.Workload.WORLDGEN_NOISE;
    private static final LeavesXComputeExecutor.Workload REALTIME = LeavesXComputeExecutor.Workload.ENTITY_TRACKING;

    @AfterEach
    void stopPool() {
        LeavesXComputeExecutor.shutdown();
    }

    @org.junit.jupiter.api.RepeatedTest(32)
    void aSubmittedReservationMustNotHideAnotherIdleWorker() throws Exception {
        LeavesXComputeExecutor.configure(2, 16, true, true);
        final var poolField = LeavesXComputeExecutor.class.getDeclaredField("executor");
        poolField.setAccessible(true);
        final var actual = (java.util.concurrent.ThreadPoolExecutor)poolField.get(null);
        final var observed = org.mockito.Mockito.spy(actual);
        poolField.set(null, observed);
        final CountDownLatch workerStarted = new CountDownLatch(1);
        final CountDownLatch submissionPaused = new CountDownLatch(1);
        final CountDownLatch releaseSubmission = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final AtomicInteger submissions = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> {
            final Object result = call.callRealMethod();
            if (submissions.getAndIncrement() == 0) {
                assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
                submissionPaused.countDown();
                assertTrue(releaseSubmission.await(5, TimeUnit.SECONDS));
            }
            return result;
        }).when(observed).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        try (var callers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            final var first = callers.submit(() -> LeavesXComputeExecutor.tryExecuteSnapshot(() -> {
                workerStarted.countDown();
                try {
                    assertTrue(releaseWorker.await(5, TimeUnit.SECONDS));
                    writes.incrementAndGet();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }, ignored -> fail("已执行的片段不得被排队取消")));
            try {
                assertTrue(submissionPaused.await(5, TimeUnit.SECONDS));
                assertEquals(1, LeavesXComputeExecutor.metrics().activeWorkers());
                assertEquals(0, LeavesXComputeExecutor.metrics().queuedTasks());
                assertTrue(LeavesXComputeExecutor.canExecuteSnapshot(REALTIME),
                    "两线程池只运行一个片段，提交交接不能重复占用另一个名额");
                final CountDownLatch secondFinished = new CountDownLatch(1);
                assertTrue(LeavesXComputeExecutor.tryExecuteSnapshot(REALTIME,
                    () -> { writes.incrementAndGet(); secondFinished.countDown(); },
                    ignored -> fail("空闲线程上的任务不得被取消")));
                assertTrue(secondFinished.await(5, TimeUnit.SECONDS));
            } finally {
                releaseSubmission.countDown();
                releaseWorker.countDown();
            }
            assertTrue(first.get(5, TimeUnit.SECONDS));
        }
        actual.shutdown();
        assertTrue(actual.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, LeavesXComputeExecutor.metrics().reservedTasks());
        assertEquals(0, LeavesXComputeExecutor.metrics().queuedTasks());
        assertEquals(2, writes.get());
    }

    @Test
    void rejectedReservedTaskRunsInlineAndReleasesItsPermit() throws Exception {
        LeavesXComputeExecutor.configure(1, 16, true, true);
        final var field = LeavesXComputeExecutor.class.getDeclaredField("executor");
        field.setAccessible(true);
        final var actual = (java.util.concurrent.ThreadPoolExecutor)field.get(null);
        final var observed = org.mockito.Mockito.spy(actual);
        field.set(null, observed);
        org.mockito.Mockito.doAnswer(call -> {
            actual.shutdownNow();
            return call.callRealMethod();
        }).when(observed).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        final AtomicInteger runs = new AtomicInteger();
        try (var batch = LeavesXSnapshotBatch.acquire(REALTIME)) {
            batch.submit(1, runs::incrementAndGet);
            assertTrue(batch.finish());
            assertEquals(1, runs.get(), "拒绝时必须由调用线程完成整片计算");
        }
        assertEquals(0, LeavesXComputeExecutor.metrics().reservedTasks());
        assertEquals(0, LeavesXComputeExecutor.metrics().queuedTasks());
    }

    @Test
    void rejectedHandoffReleasesTheReservationAfterShutdown() throws Exception {
        LeavesXComputeExecutor.configure(2, 16, true, true);
        final var field = LeavesXComputeExecutor.class.getDeclaredField("executor");
        field.setAccessible(true);
        final var actual = (java.util.concurrent.ThreadPoolExecutor)field.get(null);
        final var observed = org.mockito.Mockito.spy(actual);
        field.set(null, observed);
        org.mockito.Mockito.doAnswer(call -> {
            actual.shutdownNow();
            return call.callRealMethod();
        }).when(observed).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        assertFalse(LeavesXComputeExecutor.tryExecuteSnapshot(() -> fail("关闭后的任务不能执行"),
            ignored -> fail("提交前拒绝应交回调用者")));
        assertEquals(0, LeavesXComputeExecutor.metrics().reservedTasks());
        assertEquals(0, LeavesXComputeExecutor.metrics().queuedTasks());
        assertEquals(1, LeavesXComputeExecutor.metrics().rejectedTasks());
    }

    @Test
    void shutdownDrainsQueuedCountersWithoutExecutingThePendingTasks() throws Exception {
        LeavesXComputeExecutor.configure(1, 16, true, true);
        final var field = LeavesXComputeExecutor.class.getDeclaredField("executor");
        field.setAccessible(true);
        final var pool = (java.util.concurrent.ThreadPoolExecutor)field.get(null);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger unexpected = new AtomicInteger();
        pool.execute(() -> {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int index = 0; index < 16; index++) pool.execute(unexpected::incrementAndGet);
            assertEquals(16, LeavesXComputeExecutor.metrics().queuedTasks());
            assertEquals(16, pool.shutdownNow().size());
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, LeavesXComputeExecutor.metrics().queuedTasks());
            assertEquals(0, LeavesXComputeExecutor.metrics().activeWorkers());
            assertEquals(0, LeavesXComputeExecutor.metrics().reservedTasks());
            assertEquals(0, unexpected.get());
        } finally { release.countDown(); }
    }

    @org.junit.jupiter.api.RepeatedTest(16)
    void concurrentSubmittersCannotClaimMoreThanTheConfiguredWorkers() throws Exception {
        LeavesXComputeExecutor.configure(4, 16, true, true);
        final CountDownLatch started = new CountDownLatch(4);
        final CountDownLatch release = new CountDownLatch(1);
        final java.util.concurrent.CyclicBarrier submitTogether = new java.util.concurrent.CyclicBarrier(8);
        try (var callers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            final var attempts = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 8; index++) attempts.add(callers.submit(() -> {
                submitTogether.await(5, TimeUnit.SECONDS);
                return LeavesXComputeExecutor.tryExecuteSnapshot(() -> {
                    started.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                }, ignored -> fail("运行中的片段不得被取消"));
            }));
            try {
                int accepted = 0;
                for (var attempt : attempts) if (attempt.get(5, TimeUnit.SECONDS)) accepted++;
                assertEquals(4, accepted);
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertEquals(4, LeavesXComputeExecutor.metrics().activeWorkers());
                assertEquals(0, LeavesXComputeExecutor.metrics().queuedTasks());
                assertEquals(0, LeavesXComputeExecutor.metrics().reservedTasks());
                assertFalse(LeavesXComputeExecutor.canExecuteSnapshot(REALTIME));
            } finally { release.countDown(); }
        }
    }

    @Test
    void backgroundWorkCannotTakeTheLastReservedRealtimeWorker() throws Exception {
        LeavesXComputeExecutor.configure(2, 16, true, true);
        LeavesXComputeExecutor.configureScheduling(new ComputeSchedulingSettings(1, 0));
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        assertTrue(LeavesXComputeExecutor.tryExecuteSnapshot(() -> {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }, ignored -> fail("running blocker cannot be cancelled")));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            final AtomicInteger calls = new AtomicInteger();
            assertFalse(LeavesXComputeExecutor.shouldAttempt(BACKGROUND, 1_024, 128, 64));
            assertEquals(0, LeavesXComputeExecutor.invokeRangesWithCount(BACKGROUND, 1_024, 128, 64,
                (part, start, end) -> calls.incrementAndGet()));
            assertEquals(0, calls.get(), "rejected background work must stay entirely on its caller");
            assertTrue(LeavesXComputeExecutor.shouldAttempt(REALTIME, 1, 256, 64));
            assertEquals(1, LeavesXComputeExecutor.invokeRangesWithCount(REALTIME, 1, 256, 64, (part, start, end) -> {}));
        } finally {
            release.countDown();
        }
    }

    @Test
    void disablingTheReservationRestoresBackgroundParallelism() {
        LeavesXComputeExecutor.configure(2, 16, true);
        LeavesXComputeExecutor.configureScheduling(new ComputeSchedulingSettings(0, 0));
        assertTrue(LeavesXComputeExecutor.invokeRangesWithCount(BACKGROUND, 1_024, 128, 64, (part, start, end) -> {}) > 1);
    }

    @Test
    void automaticReservationKeepsSmallPoolsAvailableForGeneration() {
        LeavesXComputeExecutor.configure(2, 16, true);
        LeavesXComputeExecutor.configureScheduling(ComputeSchedulingSettings.defaults());
        assertTrue(LeavesXComputeExecutor.shouldAttempt(BACKGROUND, 1_024, 128, 64));
    }

    @Test
    void reconfigurationRestoresAutomaticScheduling() {
        LeavesXComputeExecutor.configure(4, 16, true);
        LeavesXComputeExecutor.configureScheduling(new ComputeSchedulingSettings(3, 1));
        LeavesXComputeExecutor.configure(4, 16, true);
        assertTrue(LeavesXComputeExecutor.invokeRangesWithCount(BACKGROUND, 2_048, 128, 64, (part, start, end) -> {}) > 1);
    }
}
