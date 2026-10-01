package org.leavesx.leavesx.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

@Normal
class OwnerThreadWaitsTest {
    @Test
    void openLatchDoesNotCountAndInterruptedWaitPreservesException() throws Exception {
        final var site = OwnerThreadWaits.Site.COMPUTE_BATCHES;
        final long before = OwnerThreadWaits.snapshot(site).samples();
        OwnerThreadWaits.await(site, new java.util.concurrent.CountDownLatch(0), 0L);
        assertEquals(before, OwnerThreadWaits.snapshot(site).samples());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                () -> OwnerThreadWaits.await(site, new java.util.concurrent.CountDownLatch(1), 0L));
            assertEquals(before + 1, OwnerThreadWaits.snapshot(site).samples());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void completedFutureDoesNotCountAsBlocking() {
        final var site = OwnerThreadWaits.Site.PATH_RESULT;
        final long before = OwnerThreadWaits.snapshot(site).samples();
        assertEquals(42, OwnerThreadWaits.join(site, CompletableFuture.completedFuture(42)));
        assertEquals(before, OwnerThreadWaits.snapshot(site).samples());
    }

    @Test
    void failedActionStillRecordsElapsedTimeAndPreservesFailure() {
        final var site = OwnerThreadWaits.Site.ADMISSION;
        final long before = OwnerThreadWaits.snapshot(site).samples();
        final var failure = new IllegalStateException("test");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> OwnerThreadWaits.measure(site, () -> { throw failure; })));
        assertEquals(before + 1, OwnerThreadWaits.snapshot(site).samples());
    }
}
