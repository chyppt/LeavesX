package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;

@Normal
class OwnedLaneShutdownTest {
    @Test
    void interruptedShutdownRetainsWritesAndPreventsExecutorReplacement() throws Exception {
        final var workload = LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE;
        final var settings = new LeavesXConfig.AsyncSettings(false, 64, false, false, 1, 64, 30, true, 64, false, 1, 64);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<Integer> writes = Collections.synchronizedList(new ArrayList<>());
        LeavesXAsyncRuntime.configure(settings);
        try {
            final CompletableFuture<Void> first = LeavesXAsyncRuntime.submitKeyedOrRun(workload, "player", () -> {
                started.countDown();
                awaitRelease(release);
                writes.add(1);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            final CompletableFuture<Void> second = LeavesXAsyncRuntime.submitKeyedOrRun(workload, "player", () -> writes.add(2));
            final ThreadPoolExecutor original = executor(workload);

            Thread.currentThread().interrupt();
            LeavesXAsyncRuntime.shutdown();
            assertTrue(Thread.interrupted(), "Shutdown must preserve the caller's interrupt flag");
            assertFalse(LeavesXAsyncRuntime.configured());
            assertFalse(first.isDone());
            assertFalse(second.isDone());

            // 重试也要中断：原 UUID 队列仍在写入时，替换必须失败，不能启动第二代执行器。
            Thread.currentThread().interrupt();
            assertThrows(IllegalStateException.class, () -> LeavesXAsyncRuntime.configure(settings));
            assertTrue(Thread.interrupted());
            assertTrue(original == executor(workload));
            release.countDown();
            CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
            LeavesXAsyncRuntime.shutdown();
            assertEquals(List.of(1, 2), writes);
            assertFalse(LeavesXAsyncRuntime.enabled(workload));
        } finally {
            Thread.interrupted();
            release.countDown();
            LeavesXAsyncRuntime.shutdown();
        }
    }

    @Test
    void shutdownWaitsForAnAdmittedTaskDrainingOnItsCaller() throws Exception {
        final var workload = LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE;
        final var settings = new LeavesXConfig.AsyncSettings(false, 64, false, false, 1, 64, 30, true, 64, false, 1, 64);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        LeavesXAsyncRuntime.configure(settings);
        final ThreadPoolExecutor original = executor(workload);
        original.shutdown();
        assertTrue(original.awaitTermination(5, TimeUnit.SECONDS));
        final CompletableFuture<Void> producer = new CompletableFuture<>();
        final Thread submitter = Thread.ofPlatform().start(() -> {
            try {
                LeavesXAsyncRuntime.submitKeyedOrRun(workload, "player", () -> {
                    started.countDown();
                    awaitRelease(release);
                }).join();
                producer.complete(null);
            } catch (final Throwable failure) {
                producer.completeExceptionally(failure);
            }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            LeavesXAsyncRuntime.shutdown(Duration.ZERO);
            assertTrue(LeavesXAsyncRuntime.enabled(workload), "Executor termination does not imply the caller lane is drained");
            assertFalse(producer.isDone());
            release.countDown();
            producer.get(10, TimeUnit.SECONDS);
            LeavesXAsyncRuntime.shutdown();
            assertFalse(LeavesXAsyncRuntime.enabled(workload));
        } finally {
            release.countDown();
            submitter.join(10_000);
            LeavesXAsyncRuntime.shutdown();
        }
    }

    @Test
    void shutdownRejectsProducerWaitingForLaneCapacityWithoutRunningItInline() throws Exception {
        final var workload = LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE;
        final var settings = new LeavesXConfig.AsyncSettings(false, 64, false, false, 1, 64, 30, true, 64, false, 1, 64);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<CompletableFuture<Void>> admitted = new ArrayList<>();
        LeavesXAsyncRuntime.configure(settings);
        Thread submitter = null;
        try {
            admitted.add(LeavesXAsyncRuntime.submitKeyedOrRun(workload, "player", () -> {
                started.countDown();
                awaitRelease(release);
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            final int capacity = settings.playerDataSaveQueueSize() + executor(workload).getMaximumPoolSize();
            for (int i = 1; i < capacity; i++) {
                admitted.add(LeavesXAsyncRuntime.submitKeyedOrRun(workload, "player", () -> { }));
            }
            final CompletableFuture<Void> producer = new CompletableFuture<>();
            submitter = Thread.ofPlatform().start(() -> {
                try {
                    LeavesXAsyncRuntime.submitKeyedOrRun(workload, "player", () -> {
                        throw new AssertionError("Unadmitted task must not execute");
                    }).join();
                    producer.complete(null);
                } catch (final Throwable failure) {
                    producer.completeExceptionally(failure);
                }
            });
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (submitter.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(Thread.State.TIMED_WAITING, submitter.getState(), "Producer must be blocked on bounded admission");
            LeavesXAsyncRuntime.shutdown(Duration.ZERO);
            final var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> producer.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof RejectedExecutionException);
            release.countDown();
            CompletableFuture.allOf(admitted.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            LeavesXAsyncRuntime.shutdown();
            assertFalse(LeavesXAsyncRuntime.enabled(workload));
        } finally {
            release.countDown();
            if (submitter != null) submitter.join(10_000);
            LeavesXAsyncRuntime.shutdown();
        }
    }

    @Test
    void rejectedSaveDoesNotOvertakeEarlierSaveForSamePlayer() throws Exception {
        final var workload = LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE;
        final var settings = new LeavesXConfig.AsyncSettings(false, 64, false, false, 1, 64, 30, true, 64, false, 1, 64);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<Integer> writes = Collections.synchronizedList(new ArrayList<>());
        LeavesXAsyncRuntime.configure(settings);
        try {
            final CompletableFuture<Void> first = LeavesXAsyncRuntime.submitKeyedOrRun(workload, "player", () -> {
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                writes.add(1);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            final CompletableFuture<Void> second = LeavesXAsyncRuntime.submitKeyedOrRun(workload, "player", () -> writes.add(2));

            LeavesXAsyncRuntime.shutdown(Duration.ZERO);

            final CompletableFuture<Void> third = LeavesXAsyncRuntime.submitKeyedOrRun(workload, "player", () -> writes.add(3));
            assertTrue(assertThrows(CompletionException.class, third::join).getCause() instanceof RejectedExecutionException);
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            assertTrue(LeavesXAsyncRuntime.enabled(workload), "Closing generation must stay visible until accepted work finishes");
            release.countDown();
            CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2), writes);
            LeavesXAsyncRuntime.shutdown();
            LeavesXAsyncRuntime.configure(settings);
            LeavesXAsyncRuntime.submitKeyedOrRun(workload, "player", () -> writes.add(3)).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2, 3), writes);
        } finally {
            release.countDown();
            LeavesXAsyncRuntime.shutdown();
        }
    }

    @Test
    void rejectedResubmissionsDrainLargeQueueWithoutRecursionOrReordering() throws Exception {
        final var workload = LeavesXAsyncRuntime.Workload.PLAYER_DATA_SAVE;
        final var settings = new LeavesXConfig.AsyncSettings(false, 64, false, false, 1, 64, 30, true, 32768, false, 1, 64);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<Integer> executed = new ArrayList<>();
        final List<CompletableFuture<Void>> completions = new ArrayList<>();
        LeavesXAsyncRuntime.configure(settings);
        try {
            completions.add(LeavesXAsyncRuntime.submitOrdered(workload, () -> {
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 16000; i++) {
                final int sequence = i;
                completions.add(LeavesXAsyncRuntime.submitOrdered(workload, () -> executed.add(sequence)));
            }
            // 只关闭底层执行器，使现有队列拒绝所有重新提交；通过反射避免新增生产测试入口或修改 API。
            final var executor = executor(workload);
            executor.shutdown();
            release.countDown();
            CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(16000, executed.size());
            for (int i = 0; i < executed.size(); i++) assertEquals(i, executed.get(i));
            assertEquals(0, LeavesXAsyncRuntime.metrics(workload).queuedTasks());
            assertEquals(0, LeavesXAsyncRuntime.metrics(workload).ownedLanes());
            assertEquals(16001, LeavesXAsyncRuntime.queueTiming(workload).samples());
            assertEquals(16001, LeavesXAsyncRuntime.taskTiming(workload).samples());
            assertTrue(LeavesXAsyncRuntime.queueTiming(workload).totalNanos() > 0);
        } finally {
            release.countDown();
            LeavesXAsyncRuntime.shutdown();
        }
    }

    private static ThreadPoolExecutor executor(final LeavesXAsyncRuntime.Workload workload) throws ReflectiveOperationException {
        final var statesField = LeavesXAsyncRuntime.class.getDeclaredField("executors");
        statesField.setAccessible(true);
        final Object state = ((Map<?, ?>) statesField.get(null)).get(workload);
        final var executorField = state.getClass().getDeclaredField("executor");
        executorField.setAccessible(true);
        return (ThreadPoolExecutor) executorField.get(state);
    }

    private static void awaitRelease(final CountDownLatch release) {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
