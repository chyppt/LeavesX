package org.leavesx.leavesx.diagnostics;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Bounded cumulative measurements of explicit blocking sites; does not infer CPU time. */
public final class OwnerThreadWaits {
    public enum Site { PATH_RESULT, ORDERED_BARRIER, ADMISSION, COMPUTE_RANGES, COMPUTE_BATCHES }
    private static final AsyncTaskTiming[] TIMINGS = java.util.Arrays.stream(Site.values())
        .map(ignored -> new AsyncTaskTiming()).toArray(AsyncTaskTiming[]::new);
    private static final AsyncTaskTiming[] MAIN_TIMINGS = java.util.Arrays.stream(Site.values())
        .map(ignored -> new AsyncTaskTiming()).toArray(AsyncTaskTiming[]::new);

    private OwnerThreadWaits() {}

    public static <T> T join(final Site site, final CompletableFuture<T> future) {
        if (future.isDone()) return future.join();
        return measure(site, future::join);
    }

    public static <T> T measure(final Site site, final Supplier<T> action) {
        final var server = net.minecraft.server.MinecraftServer.getServer();
        final boolean mainThread = server != null && server.isSameThread();
        final long start = System.nanoTime();
        try { return action.get(); }
        finally {
            final long elapsed = System.nanoTime() - start;
            TIMINGS[site.ordinal()].record(elapsed);
            if (mainThread) MAIN_TIMINGS[site.ordinal()].record(elapsed);
        }
    }

    /** Measures only the latch wait, excluding useful ranges executed by the helping caller. */
    public static void await(final Site site, final java.util.concurrent.CountDownLatch latch,
                             final long timeoutNanos) throws InterruptedException {
        if (latch.getCount() == 0L) return;
        final var server = net.minecraft.server.MinecraftServer.getServer();
        final boolean mainThread = server != null && server.isSameThread();
        final long start = System.nanoTime();
        try {
            if (timeoutNanos > 0L) latch.await(timeoutNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            else latch.await();
        } finally {
            final long elapsed = System.nanoTime() - start;
            TIMINGS[site.ordinal()].record(elapsed);
            if (mainThread) MAIN_TIMINGS[site.ordinal()].record(elapsed);
        }
    }

    public static AsyncTaskTiming.Snapshot snapshot(final Site site) {
        return TIMINGS[site.ordinal()].snapshot();
    }

    public static AsyncTaskTiming.Snapshot mainThreadSnapshot(final Site site) {
        return MAIN_TIMINGS[site.ordinal()].snapshot();
    }

    public static AsyncTaskTiming.Snapshot recentSnapshot(final Site site) {
        return TIMINGS[site.ordinal()].recentSnapshot();
    }

    public static AsyncTaskTiming.Snapshot recentMainThreadSnapshot(final Site site) {
        return MAIN_TIMINGS[site.ordinal()].recentSnapshot();
    }
}
