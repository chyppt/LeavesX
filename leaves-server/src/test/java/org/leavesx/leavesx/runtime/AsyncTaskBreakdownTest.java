package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;

@Normal
class AsyncTaskBreakdownTest {
    private static final LeavesXAsyncRuntime.Workload WORKLOAD = LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE;
    private static final LeavesXConfig.AsyncSettings SETTINGS = new LeavesXConfig.AsyncSettings(
        false, 64, false, false, 1, 64, 30, true, 64, false, 1, 64);

    @AfterEach
    void shutdown() {
        LeavesXAsyncRuntime.shutdown();
    }

    @Test
    void taskBodyIsMeasuredBeforeItsCompletionCallbacksFinish() throws Exception {
        LeavesXAsyncRuntime.configure(SETTINGS);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var callbackStarted = new CountDownLatch(1);
        final var releaseCallback = new CountDownLatch(1);
        try {
            final var task = LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "player", () -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            task.thenRun(() -> {
                callbackStarted.countDown();
                await(releaseCallback);
            });
            release.countDown();
            assertTrue(callbackStarted.await(5, TimeUnit.SECONDS));
            assertEquals(1L, LeavesXAsyncRuntime.recentComputationTiming(WORKLOAD).samples());
            assertEquals(0L, LeavesXAsyncRuntime.recentTaskTiming(WORKLOAD).samples());
            releaseCallback.countDown();
            LeavesXAsyncRuntime.awaitKey(WORKLOAD, "player");
            final var body = LeavesXAsyncRuntime.recentComputationTiming(WORKLOAD);
            final var complete = LeavesXAsyncRuntime.recentTaskTiming(WORKLOAD);
            assertEquals(1L, complete.samples());
            assertTrue(complete.totalNanos() >= body.totalNanos());
        } finally {
            release.countDown();
            releaseCallback.countDown();
        }
    }

    @Test
    void failedBodyIsMeasuredAndDoesNotPoisonLaterTasksOrBarrier() throws Exception {
        LeavesXAsyncRuntime.configure(SETTINGS);
        final var failure = new IllegalStateException("injected save failure");
        final var task = LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "player", () -> { throw failure; });
        assertSame(failure, assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS)).getCause());
        final var later = LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "player", () -> { });
        LeavesXAsyncRuntime.awaitKey(WORKLOAD, "player");
        later.get(5, TimeUnit.SECONDS);
        assertEquals(2L, LeavesXAsyncRuntime.recentComputationTiming(WORKLOAD).samples());
        assertEquals(2L, LeavesXAsyncRuntime.recentTaskTiming(WORKLOAD).samples());
        assertEquals(1L, LeavesXAsyncRuntime.metrics(WORKLOAD).failedTasks());
    }

    @Test
    void rejectedTaskCountsCallerTimeWithoutEnteringWorkerBodyStatistics() throws Exception {
        LeavesXAsyncRuntime.configure(SETTINGS);
        final ThreadPoolExecutor executor = executor();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        LeavesXAsyncRuntime.submitValueOrRun(WORKLOAD, () -> 42).get(5, TimeUnit.SECONDS);
        assertEquals(1L, LeavesXAsyncRuntime.recentCallerRunTiming(WORKLOAD).samples());
        assertEquals(0L, LeavesXAsyncRuntime.recentComputationTiming(WORKLOAD).samples());
        assertEquals(0L, LeavesXAsyncRuntime.recentQueueTiming(WORKLOAD).samples());
        assertEquals(1L, LeavesXAsyncRuntime.metrics(WORKLOAD).callerRuns());
        assertEquals(1L, LeavesXAsyncRuntime.metrics(WORKLOAD).rejectedTasks());
    }

    @Test
    void ownedCallerFallbackRetainsInternalCompletionAndTiming() throws Exception {
        LeavesXAsyncRuntime.configure(SETTINGS);
        final ThreadPoolExecutor executor = executor();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "player", () -> { }).get(5, TimeUnit.SECONDS);
        LeavesXAsyncRuntime.awaitKey(WORKLOAD, "player");
        assertEquals(1L, LeavesXAsyncRuntime.recentCallerRunTiming(WORKLOAD).samples());
        assertEquals(1L, LeavesXAsyncRuntime.recentComputationTiming(WORKLOAD).samples());
        assertEquals(1L, LeavesXAsyncRuntime.recentTaskTiming(WORKLOAD).samples());
        assertEquals(0, LeavesXAsyncRuntime.metrics(WORKLOAD).ownedLanes());
    }

    private static ThreadPoolExecutor executor() throws ReflectiveOperationException {
        final var statesField = LeavesXAsyncRuntime.class.getDeclaredField("executors");
        statesField.setAccessible(true);
        final Object state = ((Map<?, ?>) statesField.get(null)).get(WORKLOAD);
        final var executorField = state.getClass().getDeclaredField("executor");
        executorField.setAccessible(true);
        return (ThreadPoolExecutor) executorField.get(state);
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("test release timeout");
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
