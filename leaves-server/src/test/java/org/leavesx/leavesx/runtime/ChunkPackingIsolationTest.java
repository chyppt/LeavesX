package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;

@Normal
class ChunkPackingIsolationTest {
    @Test
    void floodedChunkQueueDoesNotStarvePathfinding() throws Exception {
        assumeTrue(Runtime.getRuntime().availableProcessors() >= 4, "Requires multiple compute lanes");
        LeavesXComputeExecutor.shutdown();
        LeavesXAsyncRuntime.configure(new LeavesXConfig.AsyncSettings(
            true, 256, true, true, 2, 1_024, 60, true, 1_024, true, 2, 1_024), true);
        final CountDownLatch release = new CountDownLatch(1);
        final var pending = new ArrayList<CompletableFuture<Void>>();
        try {
            assertTrue(LeavesXAsyncRuntime.enabled(LeavesXAsyncRuntime.Workload.CHUNK_SEND));
            for (int i = 0; i < 64; i++) {
                pending.add(LeavesXAsyncRuntime.submitOrRun(LeavesXAsyncRuntime.Workload.CHUNK_SEND, () -> {
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }));
            }
            final String thread = LeavesXAsyncRuntime.submitValueOrRun(LeavesXAsyncRuntime.Workload.PATHFINDING,
                () -> Thread.currentThread().getName()).get(3, TimeUnit.SECONDS);
            assertTrue(thread.startsWith("LeavesX Async Compute"), thread);
            assertEquals(0, LeavesXAsyncRuntime.metrics(LeavesXAsyncRuntime.Workload.PATHFINDING).callerRuns());
        } finally {
            release.countDown();
            try {
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            } finally {
                LeavesXAsyncRuntime.shutdown();
            }
        }
    }
}
