package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 通过闩锁验证真实重叠执行，不用线程名称或累计派发次数推断并行。 */
@Normal
class SnapshotBatchTest {
    @AfterEach
    void stopWorkers() {
        LeavesXComputeExecutor.shutdown();
    }

    @Test
    void occupiedPoolExecutesInlineWithoutAllocatingPendingTasks() throws Exception {
        LeavesXComputeExecutor.configure(1, 64, true, true);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger writes = new AtomicInteger();
        try (var batch = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            batch.submit(1, () -> { started.countDown(); await(release); writes.incrementAndGet(); });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                for (int index = 0; index < 1000; index++) batch.submit(1, writes::incrementAndGet);
                final var pending = LeavesXSnapshotBatch.class.getDeclaredField("pending");
                pending.setAccessible(true);
                assertEquals(1, ((java.util.List<?>)pending.get(batch)).size(), "同步完成片段不应占用待完成窗口");
                assertEquals(1000, writes.get());
            } finally { release.countDown(); }
            assertTrue(batch.finish());
        }
        assertEquals(1001, writes.get());
        assertEquals(1000, LeavesXComputeExecutor.metrics().callerItems());
        assertEquals(1, LeavesXComputeExecutor.metrics().workerItems());
    }

    @Test
    void inlineFailureIsRetainedUntilAllPublishedWritersStop() throws Exception {
        LeavesXComputeExecutor.configure(1, 64, true, true);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger writes = new AtomicInteger();
        try (var batch = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            batch.submit(1, () -> { started.countDown(); await(release); writes.incrementAndGet(); });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                batch.submit(1, () -> { throw new IllegalStateException("inline failure"); });
                batch.submit(1, writes::incrementAndGet);
                assertEquals(1, writes.get());
            } finally { release.countDown(); }
            assertFalse(batch.finish());
            assertFalse(batch.finish());
        }
        assertEquals(2, writes.get());
        assertEquals(3, LeavesXComputeExecutor.metrics().callerItems() + LeavesXComputeExecutor.metrics().workerItems());
        assertEquals(1, LeavesXComputeExecutor.metrics().fullRecomputationRequests());
    }

    @org.junit.jupiter.api.RepeatedTest(64)
    void twoEntityBatchesRunWhileOwnerContinuesCapturing() throws Exception {
        LeavesXComputeExecutor.configure(2, 64, true, true);
        final CountDownLatch started = new CountDownLatch(2);
        final CountDownLatch captured = new CountDownLatch(1);
        final AtomicInteger results = new AtomicInteger();
        try (var batch = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            for (int i = 0; i < 2; i++) {
                batch.submit(1, () -> {
                    started.countDown();
                    await(captured);
                    results.incrementAndGet();
                });
            }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS), "提交不得等待前一实体的计算结束");
                assertEquals(0, results.get());
            } finally {
                captured.countDown();
            }
            assertTrue(batch.finish());
            assertEquals(2, results.get());
            assertTrue(batch.finish(), "重复结束不能重复执行任务");
        }
        assertEquals(2, results.get());
    }

    @Test
    void busyPoolLetsOwnerHelpWithoutWaitingForWorkers() throws Exception {
        LeavesXComputeExecutor.configure(1, 64, true, true);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread owner = Thread.currentThread();
        try (var batch = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_ACTIVATION)) {
            batch.submit(1, () -> { started.countDown(); await(release); });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                batch.submit(1, () -> assertSame(owner, Thread.currentThread()));
            } finally {
                release.countDown();
            }
            assertTrue(batch.finish());
        }
    }

    @Test
    void failureDoesNotReturnUntilEveryWriterHasStopped() throws Exception {
        LeavesXComputeExecutor.configure(2, 64, true, true);
        final CountDownLatch started = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger writes = new AtomicInteger();
        try (var batch = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            batch.submit(4, () -> { started.countDown(); await(release); throw new IllegalStateException("injected"); });
            batch.submit(4, () -> { started.countDown(); await(release); writes.set(4); });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertFalse(batch.finish());
            assertEquals(4, writes.get());
            writes.set(8); // 所有写入都已结束，此时才允许调用者完整重算。
            assertFalse(batch.finish());
            assertEquals(8, writes.get());
        }
        assertEquals(1, LeavesXComputeExecutor.metrics().failedInvocations());
        assertEquals(1, LeavesXComputeExecutor.metrics().fullRecomputationRequests());
    }

    @Test
    void exceptionalCaptureClosesWorkersBeforeReusingBuffers() {
        LeavesXComputeExecutor.configure(2, 64, true, true);
        final AtomicInteger writes = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> {
            try (var batch = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_ACTIVATION)) {
                batch.submit(1, writes::incrementAndGet);
                throw new IllegalArgumentException("capture failed");
            }
        });
        assertEquals(1, writes.get());
    }

    @Test
    void unavailablePoolAndNestedWorkerCannotDeadlock() throws Exception {
        try (var batch = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            final Thread owner = Thread.currentThread();
            batch.submit(1, () -> assertSame(owner, Thread.currentThread()));
            assertTrue(batch.finish());
            assertThrows(IllegalStateException.class, () -> batch.submit(1, () -> {}));
        }
        LeavesXComputeExecutor.configure(1, 64, true, true);
        try (var outer = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_ACTIVATION)) {
            outer.submit(1, () -> {
                try (var inner = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_ACTIVATION)) {
                    final Thread worker = Thread.currentThread();
                    inner.submit(1, () -> assertSame(worker, Thread.currentThread()));
                    assertTrue(inner.finish());
                }
            });
            assertTrue(outer.finish());
        }
    }

    @Test
    void boundedBatchHandlesMoreThanOneWindowWithoutLosingWork() {
        LeavesXComputeExecutor.configure(3, 64, true, true);
        final AtomicInteger sum = new AtomicInteger();
        try (var batch = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            for (int i = 0; i < 513; i++) batch.submit(1, sum::incrementAndGet);
            assertTrue(batch.finish());
        }
        assertEquals(513, sum.get());
        assertEquals(513, LeavesXComputeExecutor.metrics().callerItems() + LeavesXComputeExecutor.metrics().workerItems());
    }

    @Test
    void completedFragmentsFreeSlotsWithoutWaitingForASlowEarlierWorker() throws Exception {
        LeavesXComputeExecutor.configure(1, 64, true, true);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch captured = new CountDownLatch(1);
        final java.util.concurrent.CompletableFuture<Void> completed = new java.util.concurrent.CompletableFuture<>();
        final AtomicInteger writes = new AtomicInteger();
        final Thread producer = new Thread(() -> {
            try (var batch = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
                batch.submit(1, () -> { started.countDown(); await(release); writes.incrementAndGet(); });
                await(started);
                // 第一片仍在运行，后续片段由所属线程完成；完成片段不能占满整批窗口。
                for (int index = 0; index < 130; index++) batch.submit(1, writes::incrementAndGet);
                captured.countDown();
                assertTrue(batch.finish());
                completed.complete(null);
            } catch (final Throwable failure) {
                completed.completeExceptionally(failure);
            }
        }, "snapshot-window-producer");
        producer.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(captured.await(2, TimeUnit.SECONDS), "已经完成的片段应让出窗口，不应等待慢片段");
            assertEquals(130, writes.get());
        } finally {
            release.countDown();
            producer.join(5000);
        }
        completed.get(5, TimeUnit.SECONDS);
        assertEquals(131, writes.get());
        assertEquals(131, LeavesXComputeExecutor.metrics().callerItems() + LeavesXComputeExecutor.metrics().workerItems());
    }

    @Test
    void reapedFailureStillRejectsTheWholeBatchAndIsCountedOnce() {
        LeavesXComputeExecutor.configure(1, 64, true, true);
        final AtomicInteger completed = new AtomicInteger();
        try (var batch = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            batch.submit(1, () -> { throw new IllegalStateException("reaped failure"); });
            for (int index = 0; index < 256; index++) batch.submit(1, completed::incrementAndGet);
            assertFalse(batch.finish());
            assertFalse(batch.finish());
            assertEquals(256, completed.get());
        }
        final var metrics = LeavesXComputeExecutor.metrics();
        assertEquals(1, metrics.failedInvocations());
        assertEquals(1, metrics.fullRecomputationRequests());
        assertEquals(257, metrics.callerItems() + metrics.workerItems());
    }

    @Test
    void interruptedOwnerWaitsForWritersAndPreservesInterrupt() throws Exception {
        LeavesXComputeExecutor.configure(1, 64, true, true);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger writes = new AtomicInteger();
        try (var batch = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_ACTIVATION)) {
            batch.submit(1, () -> { started.countDown(); await(release); writes.incrementAndGet(); });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                Thread.currentThread().interrupt();
                release.countDown();
                assertFalse(batch.finish());
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(1, writes.get());
            } finally {
                release.countDown();
                Thread.interrupted();
            }
        }
    }

    @Test
    void shuttingDownPoolCannotLeaveAnUnfinishedSnapshot() throws Exception {
        LeavesXComputeExecutor.configure(1, 64, true, true);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        try (var batch = new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            batch.submit(1, () -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                    throw new AssertionError("worker unexpectedly completed");
                } catch (final InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("pool stopped", expected);
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            LeavesXComputeExecutor.shutdown();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(batch.finish());
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker timed out");
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
