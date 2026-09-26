package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

/** A dead worker must not leave the tick thread waiting for ranges that nobody can complete. */
@Normal
class ComputeCompletionSafetyTest {

    @Test
    void finishedBatchesWithUnaccountedRangesFailInsteadOfWaitingForever() throws Exception {
        final Object invocation = invocation(1, 0);
        final CountDownLatch rangesDone = (CountDownLatch) field(invocation, "workersDone");
        final var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            final var wait = executor.submit(() -> {
                awaitWithHelping(invocation);
                return null;
            });
            wait.get(3L, TimeUnit.SECONDS);
            assertNotNull(field(invocation, "failure"), "An orphaned range must request complete recomputation");
        } finally {
            // The old implementation ignores interrupts while joining; release its latch even when the test fails.
            rangesDone.countDown();
            executor.shutdown();
            executor.awaitTermination(5L, TimeUnit.SECONDS);
        }
    }

    @Test
    void fullyCompletedRangesAreNotReportedAsFailure() throws Exception {
        final Object invocation = invocation(0, 0);
        awaitWithHelping(invocation);
        assertNull(field(invocation, "failure"));
    }

    @Test
    void doesNotRecomputeWhileAWorkerBatchCanStillWriteResults() throws Exception {
        final Object invocation = invocation(1, 1);
        final CountDownLatch rangesDone = (CountDownLatch) field(invocation, "workersDone");
        final CountDownLatch batchesDone = (CountDownLatch) field(invocation, "workerBatchesDone");
        final CountDownLatch started = new CountDownLatch(1);
        final var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            final var wait = executor.submit(() -> {
                started.countDown();
                awaitWithHelping(invocation);
                return null;
            });
            assertTrue(started.await(3L, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> wait.get(100L, TimeUnit.MILLISECONDS));
            rangesDone.countDown();
            batchesDone.countDown();
            wait.get(3L, TimeUnit.SECONDS);
            assertNull(field(invocation, "failure"));
        } finally {
            rangesDone.countDown();
            batchesDone.countDown();
            executor.shutdown();
            executor.awaitTermination(5L, TimeUnit.SECONDS);
        }
    }

    private static Object invocation(final int ranges, final int batches) throws Exception {
        final Class<?> type = Class.forName(LeavesXComputeExecutor.class.getName() + "$RangeInvocation");
        final var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        final LeavesXComputeExecutor.RangeTask noOp = (task, start, end) -> {};
        return constructor.newInstance(noOp, null, ranges, batches);
    }

    private static void awaitWithHelping(final Object invocation) throws Exception {
        final Method method = invocation.getClass().getDeclaredMethod(
            "awaitWorkersWithHelping", AtomicInteger.class, int.class, int.class, int.class, int.class
        );
        method.setAccessible(true);
        method.invoke(invocation, new AtomicInteger(1), 1, 1, 2, 1);
    }

    private static Object field(final Object target, final String name) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
