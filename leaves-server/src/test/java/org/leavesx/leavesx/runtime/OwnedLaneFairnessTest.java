package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;

/** 忙碌的 UUID 必须让出执行权给其他 UUID，不能独占保存工作线程。 */
@Normal
class OwnedLaneFairnessTest {
    @Test
    void busyLaneYieldsAfterBoundedBatchAndKeepsFifo() throws Exception {
        final var workload = LeavesXAsyncRuntime.Workload.PATHFINDING;
        LeavesXAsyncRuntime.configure(new LeavesXConfig.AsyncSettings(
            false, 64, false, true, 1, 512, 30, false, 64, false, 1, 64));
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var order = new ArrayList<Integer>();
        final var tasks = new ArrayList<CompletableFuture<Void>>();
        try {
            tasks.add(LeavesXAsyncRuntime.submitKeyedOrRun(workload, "busy", () -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) {
                final int index = i;
                tasks.add(LeavesXAsyncRuntime.submitKeyedOrRun(workload, "busy", () -> order.add(index)));
            }
            tasks.add(LeavesXAsyncRuntime.submitKeyedOrRun(workload, "other", () -> order.add(-1)));
            release.countDown();
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            final int otherPosition = order.indexOf(-1);
            assertTrue(otherPosition >= 0 && otherPosition < 8, "another lane must run before the next busy batch");
            order.remove(Integer.valueOf(-1));
            assertEquals(100, order.size());
            for (int i = 0; i < 100; i++) assertEquals(i, order.get(i));
        } finally {
            release.countDown();
            LeavesXAsyncRuntime.shutdown();
        }
    }
}
