package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.leavesx.leavesx.config.ConfigurationTestSupport.publish;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;

/** 复用不得提前释放仍在写入的片段，也不得把前一批失败或计数带入新查询。 */
@Normal
class SnapshotBatchReuseTest {
    @AfterEach
    void restore() {
        LeavesXComputeExecutor.shutdown();
        publish(LeavesXConfig.safeDefaults());
        Thread.interrupted();
    }

    @Test
    void disablingReuseDuringANestedQueryDoesNotReleaseTheOuterLease() {
        publish(LeavesXConfig.safeDefaults());
        var outer = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_TRACKING);
        try (outer) {
            var disabled = org.spongepowered.configurate.CommentedConfigurationNode.root();
            try { disabled.node("async", "compute", "kernels", "snapshot-batch-reuse").set(false); }
            catch (org.spongepowered.configurate.serialize.SerializationException error) { throw new AssertionError(error); }
            publish(disabled);
            try (var first = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_TRACKING);
                 var second = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
                assertNotSame(first, second);
                assertNotSame(outer, first);
            }
            publish(LeavesXConfig.safeDefaults());
            try (var nested = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
                assertNotSame(outer, nested);
            }
        }
        try (var next = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            assertSame(outer, next);
        }
    }

    @Test
    void cancellationAndWorkerClaimCannotExecuteTheSameFragmentTwice() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(1, 64, true, true);
        try (var callers = java.util.concurrent.Executors.newFixedThreadPool(2);
             var compute = org.mockito.Mockito.mockStatic(LeavesXComputeExecutor.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            AtomicReference<Runnable> task = new AtomicReference<>();
            compute.when(() -> LeavesXComputeExecutor.canExecuteSnapshot(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)).thenReturn(true);
            compute.when(() -> LeavesXComputeExecutor.tryExecuteSnapshot(
                org.mockito.ArgumentMatchers.eq(LeavesXComputeExecutor.Workload.ENTITY_TRACKING),
                org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.any()
            )).thenAnswer(invocation -> { task.set(invocation.getArgument(1)); return true; });
            for (int round = 0; round < 64; round++) {
                AtomicInteger writes = new AtomicInteger();
                try (var batch = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
                    batch.submit(1, writes::incrementAndGet);
                    var runnable = (LeavesXComputeExecutor.SnapshotTask) task.get();
                    var started = new java.util.concurrent.CyclicBarrier(3);
                    var run = callers.submit(() -> { await(started); runnable.run(); });
                    var cancel = callers.submit(() -> { await(started); runnable.cancel(new IllegalStateException("cancelled before claim")); });
                    await(started);
                    run.get(5, TimeUnit.SECONDS);
                    cancel.get(5, TimeUnit.SECONDS);
                    boolean complete = batch.finish();
                    assertEquals(complete ? 1 : 0, writes.get());
                }
            }
        }
    }

    @Test
    void completedContainerReusesStorageAndRecordsEachInvocationOnce() {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(1, 64, true, true);
        LeavesXSnapshotBatch first;
        AtomicInteger writes = new AtomicInteger();
        try (var batch = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            first = batch;
            batch.submit(3, writes::incrementAndGet);
            assertTrue(batch.finish());
            assertTrue(batch.finish());
        }
        try (var batch = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
            assertSame(first, batch);
            batch.submit(7, writes::incrementAndGet);
            assertTrue(batch.finish());
        }
        assertEquals(2, writes.get());
        var metrics = LeavesXComputeExecutor.metrics();
        assertEquals(2, metrics.invocations());
        assertEquals(10, metrics.workerItems() + metrics.callerItems());
    }

    @Test
    void finishWithoutCloseAndNestedQueriesKeepSeparateBarriers() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(1, 64, true, true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        try (var outer = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.AI_TARGET_SELECT)) {
            outer.submit(1, () -> { started.countDown(); await(release); result.incrementAndGet(); });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                try (var inner = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.AI_TARGET_SELECT)) {
                    assertNotSame(outer, inner);
                    inner.submit(2, () -> result.addAndGet(10));
                    assertTrue(inner.finish());
                }
                assertEquals(10, result.get());
            } finally { release.countDown(); }
            assertTrue(outer.finish());
            try (var stillNested = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.AI_TARGET_SELECT)) {
                assertNotSame(outer, stillNested, "finish 不代表调用者已经归还容器");
            }
        }
        assertEquals(11, result.get());
    }

    @Test
    void failureIsNotReusedAndDoesNotKeepThePreviousExceptionAlive() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.configure(1, 64, true, true);
        var failed = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.AI_TARGET_SORT);
        try (failed) {
            failed.submit(1, () -> { throw new IllegalStateException("expected failure before reuse"); });
            assertFalse(failed.finish());
        }
        var failure = LeavesXSnapshotBatch.class.getDeclaredField("failure");
        failure.setAccessible(true);
        assertNull(failure.get(failed), "归还的线程缓存不应持有异常中的实体或插件对象");
        try (var fresh = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.AI_TARGET_SORT)) {
            assertSame(failed, fresh);
            fresh.submit(2, () -> {});
            assertTrue(fresh.finish());
        }
        assertEquals(1, LeavesXComputeExecutor.metrics().failedInvocations());
        assertEquals(1, LeavesXComputeExecutor.metrics().fullRecomputationRequests());
    }

    @Test
    void differentWorkloadsAndOwnersNeverShareALiveContainer() throws Exception {
        publish(LeavesXConfig.safeDefaults());
        try (var first = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_TRACKING);
             var other = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_ACTIVATION)) {
            assertNotSame(first, other);
            AtomicReference<LeavesXSnapshotBatch> foreign = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread owner = new Thread(() -> {
                try (var batch = LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.ENTITY_TRACKING)) {
                    foreign.set(batch);
                    assertThrows(IllegalStateException.class, first::finish);
                    batch.submit(1, () -> {});
                } catch (Throwable error) { failure.set(error); }
            }, "snapshot-reuse-test");
            owner.start();
            owner.join(5000);
            assertFalse(owner.isAlive());
            assertNull(failure.get());
            assertNotSame(first, foreign.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static void await(java.util.concurrent.CyclicBarrier barrier) {
        try { barrier.await(5, TimeUnit.SECONDS); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
