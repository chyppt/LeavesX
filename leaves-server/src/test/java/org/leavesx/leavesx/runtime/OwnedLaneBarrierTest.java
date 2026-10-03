package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;

@Normal
class OwnedLaneBarrierTest {
    private static final LeavesXAsyncRuntime.Workload WORKLOAD = LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE;
    private static final LeavesXConfig.AsyncSettings SETTINGS = new LeavesXConfig.AsyncSettings(
        false, 64, false, false, 1, 64, 30, true, 64, false, 1, 64);

    @AfterEach
    void shutdown() {
        LeavesXAsyncRuntime.shutdown();
    }

    @Test
    void idleBarriersDoNotSubmitEmptyTasks() {
        LeavesXAsyncRuntime.configure(SETTINGS);
        final long submitted = LeavesXAsyncRuntime.metrics(WORKLOAD).submittedTasks();
        final long waits = org.leavesx.leavesx.diagnostics.OwnerThreadWaits.snapshot(
            org.leavesx.leavesx.diagnostics.OwnerThreadWaits.Site.ORDERED_BARRIER).samples();

        LeavesXAsyncRuntime.awaitKey(WORKLOAD, "idle");
        LeavesXAsyncRuntime.awaitOwner(WORKLOAD, new Object());
        LeavesXAsyncRuntime.awaitOrdered(WORKLOAD);

        assertEquals(submitted, LeavesXAsyncRuntime.metrics(WORKLOAD).submittedTasks());
        assertEquals(0, LeavesXAsyncRuntime.metrics(WORKLOAD).ownedLanes());
        assertEquals(0L, LeavesXAsyncRuntime.recentBarrierWaitTiming(WORKLOAD).samples());
        assertEquals(waits, org.leavesx.leavesx.diagnostics.OwnerThreadWaits.snapshot(
            org.leavesx.leavesx.diagnostics.OwnerThreadWaits.Site.ORDERED_BARRIER).samples());
    }

    @Test
    void idlePlayerDoesNotWaitForAnotherPlayersSaturatedSaveQueue() throws Exception {
        LeavesXAsyncRuntime.configure(SETTINGS);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final List<CompletableFuture<Void>> saves = new ArrayList<>();
        Thread waiter = null;
        try {
            saves.add(LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "busy", () -> {
                started.countDown();
                await(release);
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            final int capacity = SETTINGS.playerDataSaveQueueSize() + executor().getMaximumPoolSize();
            for (int index = 1; index < capacity; index++) {
                saves.add(LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "busy", () -> { }));
            }

            final var completed = new CompletableFuture<Void>();
            waiter = startBarrier("idle", completed);
            completed.get(2, TimeUnit.SECONDS);

            assertFalse(saves.getFirst().isDone(), "The unrelated save must still be blocked");
            assertEquals(capacity, LeavesXAsyncRuntime.metrics(WORKLOAD).submittedTasks());
        } finally {
            release.countDown();
            if (waiter != null) waiter.join(5_000);
            CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void barrierCoversCapturedTailButNotLaterSubmissions() throws Exception {
        LeavesXAsyncRuntime.configure(SETTINGS);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var laterStarted = new CountDownLatch(1);
        final var releaseLater = new CountDownLatch(1);
        final var barrier = new CompletableFuture<Void>();
        Thread waiter = null;
        try {
            final var first = LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "player", () -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            final var second = LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "player", () -> { });
            waiter = startBarrier(new String("player"), barrier);
            awaitWaiting(waiter);
            assertFalse(barrier.isDone());
            final var later = LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "player", () -> {
                laterStarted.countDown();
                await(releaseLater);
            });

            release.countDown();
            barrier.get(5, TimeUnit.SECONDS);
            assertTrue(laterStarted.await(5, TimeUnit.SECONDS));
            assertTrue(first.isDone());
            assertTrue(second.isDone());
            assertFalse(later.isDone(), "A later save is outside this barrier's captured tail");
            assertEquals(3, LeavesXAsyncRuntime.metrics(WORKLOAD).submittedTasks());
            assertEquals(1L, LeavesXAsyncRuntime.recentBarrierWaitTiming(WORKLOAD).samples());
        } finally {
            release.countDown();
            releaseLater.countDown();
            if (waiter != null) waiter.join(5_000);
        }
    }

    @Test
    void cancellingPublicFutureDoesNotCompleteBarrierBeforeWriteFinishes() throws Exception {
        LeavesXAsyncRuntime.configure(SETTINGS);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var barrier = new CompletableFuture<Void>();
        Thread waiter = null;
        try {
            final var save = LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "player", () -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(save.cancel(false));
            waiter = startBarrier("player", barrier);
            awaitWaiting(waiter);
            assertFalse(barrier.isDone());

            release.countDown();
            barrier.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            if (waiter != null) waiter.join(5_000);
        }
    }

    @Test
    void barrierWaitsForCompletionCallbacksAndCapacityRelease() throws Exception {
        LeavesXAsyncRuntime.configure(SETTINGS);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var callbackStarted = new CountDownLatch(1);
        final var releaseCallback = new CountDownLatch(1);
        final var barrier = new CompletableFuture<Void>();
        Thread waiter = null;
        try {
            final var save = LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "player", () -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            save.thenRun(() -> {
                callbackStarted.countDown();
                await(releaseCallback);
            });
            release.countDown();
            assertTrue(callbackStarted.await(5, TimeUnit.SECONDS));
            assertTrue(save.isDone());
            waiter = startBarrier("player", barrier);
            awaitWaiting(waiter);
            assertFalse(barrier.isDone(), "Public future completion is not the end of the lane task");

            releaseCallback.countDown();
            barrier.get(5, TimeUnit.SECONDS);
            assertEquals(0, LeavesXAsyncRuntime.metrics(WORKLOAD).activeThreads());
            assertEquals(0, LeavesXAsyncRuntime.metrics(WORKLOAD).queuedTasks());
        } finally {
            release.countDown();
            releaseCallback.countDown();
            if (waiter != null) waiter.join(5_000);
        }
    }

    @Test
    void closingRuntimeStillAllowsWaitingForPreviouslyAdmittedSaves() throws Exception {
        LeavesXAsyncRuntime.configure(SETTINGS);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var barrier = new CompletableFuture<Void>();
        Thread waiter = null;
        try {
            final var first = LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "player", () -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            final var second = LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "player", () -> { });
            closeAdmission();
            executor().shutdown();
            waiter = startBarrier("player", barrier);
            awaitWaiting(waiter);
            assertFalse(barrier.isDone());

            release.countDown();
            barrier.get(5, TimeUnit.SECONDS);
            CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            if (waiter != null) waiter.join(5_000);
        }
    }

    @Test
    void boundedAdmissionWaitIsVisibleForItsWorkload() throws Exception {
        LeavesXAsyncRuntime.configure(SETTINGS);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final List<CompletableFuture<Void>> saves = new ArrayList<>();
        Thread producer = null;
        try {
            saves.add(LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "busy", () -> {
                started.countDown();
                await(release);
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            final int capacity = SETTINGS.playerDataSaveQueueSize() + executor().getMaximumPoolSize();
            for (int index = 1; index < capacity; index++) {
                saves.add(LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "busy", () -> { }));
            }

            final var submitted = new CompletableFuture<Void>();
            producer = Thread.ofPlatform().name("owned-lane-admission-test").start(() -> {
                try {
                    LeavesXAsyncRuntime.submitKeyedOrRun(WORKLOAD, "later", () -> { }).join();
                    submitted.complete(null);
                } catch (final Throwable failure) {
                    submitted.completeExceptionally(failure);
                }
            });
            awaitWaiting(producer);
            assertFalse(submitted.isDone(), "Capacity must remain bounded while the earlier task is running");

            release.countDown();
            submitted.get(10, TimeUnit.SECONDS);
            assertEquals(1L, LeavesXAsyncRuntime.recentAdmissionWaitTiming(WORKLOAD).samples());
            assertTrue(LeavesXAsyncRuntime.recentAdmissionWaitTiming(WORKLOAD).totalNanos() > 0L);
            assertEquals(0L, LeavesXAsyncRuntime.recentAdmissionWaitTiming(
                LeavesXAsyncRuntime.Workload.PATHFINDING).samples());
        } finally {
            release.countDown();
            if (producer != null) producer.join(10_000);
            CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        }
    }

    private static Thread startBarrier(final Object key, final CompletableFuture<Void> completion) {
        return Thread.ofPlatform().name("save-barrier-test").start(() -> {
            try {
                LeavesXAsyncRuntime.awaitKey(WORKLOAD, key);
                completion.complete(null);
            } catch (final Throwable failure) {
                completion.completeExceptionally(failure);
            }
        });
    }

    private static void awaitWaiting(final Thread thread) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING
            && thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(thread.isAlive(), "Barrier must wait for the existing lane task");
        assertTrue(thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING);
    }

    private static ThreadPoolExecutor executor() throws ReflectiveOperationException {
        final var statesField = LeavesXAsyncRuntime.class.getDeclaredField("executors");
        statesField.setAccessible(true);
        final Object state = ((Map<?, ?>) statesField.get(null)).get(WORKLOAD);
        final var executorField = state.getClass().getDeclaredField("executor");
        executorField.setAccessible(true);
        return (ThreadPoolExecutor) executorField.get(state);
    }

    private static void closeAdmission() throws ReflectiveOperationException {
        final var statesField = LeavesXAsyncRuntime.class.getDeclaredField("executors");
        statesField.setAccessible(true);
        final Object state = ((Map<?, ?>) statesField.get(null)).get(WORKLOAD);
        final var closeMethod = state.getClass().getDeclaredMethod("closeOwnedAdmission");
        closeMethod.setAccessible(true);
        closeMethod.invoke(state);
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
