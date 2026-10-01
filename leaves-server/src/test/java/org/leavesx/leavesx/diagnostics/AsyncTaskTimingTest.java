package org.leavesx.leavesx.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

@Normal
class AsyncTaskTimingTest {
    @Test
    void concurrentWritersPreserveTotalsAndMaximum() {
        final AsyncTaskTiming timing = new AsyncTaskTiming();
        assertEquals(0.0, timing.snapshot().averageMillis());
        java.util.stream.IntStream.rangeClosed(1, 10000).parallel().forEach(timing::record);
        final var snapshot = timing.snapshot();
        assertEquals(10000L, snapshot.samples());
        assertEquals(50005000L, snapshot.totalNanos());
        assertEquals(10000L, snapshot.maximumNanos());
        assertEquals(snapshot, timing.recentSnapshot());
    }

    @Test
    void windowExpiresOldMaximumWithoutResettingLifetimeCounters() {
        final var clock = new java.util.concurrent.atomic.AtomicLong();
        final AsyncTaskTiming timing = new AsyncTaskTiming(clock::get);
        timing.record(1000L);
        clock.set(59_000_000_000L);
        timing.record(20L);
        assertEquals(2L, timing.recentSnapshot().samples());
        clock.set(60_000_000_000L);
        assertEquals(new AsyncTaskTiming.Snapshot(1L, 20L, 20L), timing.recentSnapshot());
        timing.record(30L);
        assertEquals(new AsyncTaskTiming.Snapshot(2L, 50L, 30L), timing.recentSnapshot());
        clock.set(120_000_000_000L);
        assertEquals(new AsyncTaskTiming.Snapshot(0L, 0L, 0L), timing.recentSnapshot());
        assertEquals(new AsyncTaskTiming.Snapshot(3L, 1050L, 1000L), timing.snapshot());
    }
}
