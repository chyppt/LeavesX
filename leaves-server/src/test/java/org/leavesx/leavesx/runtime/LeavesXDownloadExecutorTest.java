package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

@Normal
class LeavesXDownloadExecutorTest {
    @Test
    void blockingDownloadsUseVirtualThreadsWithoutIncreasingConcurrency() throws Exception {
        final var executor = LeavesXDownloadExecutor.createVirtualPool();
        final CountDownLatch started = new CountDownLatch(4);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            final var workers = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 4; i++) {
                workers.add(executor.submit(() -> {
                    started.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("下载任务未获释放");
                    }
                    return Thread.currentThread().isVirtual();
                }));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            final var queued = executor.submit(() -> Thread.currentThread().isVirtual());
            assertThrows(java.util.concurrent.TimeoutException.class, () -> queued.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            for (final var worker : workers) {
                assertTrue(worker.get(5, TimeUnit.SECONDS));
            }
            assertTrue(queued.get(5, TimeUnit.SECONDS));
            final var failed = executor.submit(() -> { throw new IllegalStateException("download failed"); });
            assertInstanceOf(IllegalStateException.class,
                assertThrows(java.util.concurrent.ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(42, executor.submit(() -> 42).get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> executor.execute(() -> {}));
    }
}
