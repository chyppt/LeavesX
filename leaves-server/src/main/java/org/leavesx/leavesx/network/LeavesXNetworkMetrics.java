package org.leavesx.leavesx.network;

import java.util.concurrent.atomic.AtomicLong;

/** Cumulative encoding counters, not delivery acknowledgements or a pending-queue gauge. */
public final class LeavesXNetworkMetrics {
    private static final AtomicLong submitted = new AtomicLong();
    private static final AtomicLong completed = new AtomicLong();
    private static final AtomicLong fallback = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();

    private LeavesXNetworkMetrics() {
    }

    static void recordSubmitted() { submitted.incrementAndGet(); }
    static void recordCompleted() { completed.incrementAndGet(); }
    static void recordFallback() { fallback.incrementAndGet(); }
    static void recordFailed() { failed.incrementAndGet(); }

    public static Snapshot snapshot() {
        return new Snapshot(submitted.get(), completed.get(), fallback.get(), failed.get());
    }

    /**
     * Requests include caller-thread work. Completion means publication of bytes, not network delivery.
     * Fallback counts failed/cancelled encodings retried once; admission fallback is reported by the worker pool.
     * Disconnected consumers need not publish, so submitted - completed must not be used as queue depth.
     */
    public record Snapshot(long submitted, long completed, long fallback, long failed) {
    }
}
