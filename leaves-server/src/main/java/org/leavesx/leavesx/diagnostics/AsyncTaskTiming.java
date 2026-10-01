package org.leavesx.leavesx.diagnostics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.LongSupplier;

/** Cumulative wall-clock task timing. Concurrent task durations are not CPU time or tick time. */
public final class AsyncTaskTiming {
    private final LongAdder samples = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final AtomicLong maximumNanos = new AtomicLong();
    private static final int WINDOW_SECONDS = 60;
    private final AtomicReferenceArray<Bucket> recent = new AtomicReferenceArray<>(WINDOW_SECONDS);
    private final LongSupplier clock;
    private final long origin;

    public AsyncTaskTiming() {
        this(System::nanoTime);
    }

    AsyncTaskTiming(final LongSupplier clock) {
        this.clock = clock;
        this.origin = clock.getAsLong();
    }

    public void record(final long nanos) {
        final long elapsed = Math.max(0L, nanos);
        this.totalNanos.add(elapsed);
        this.maximumNanos.accumulateAndGet(elapsed, Math::max);
        this.samples.increment();
        final long second = (this.clock.getAsLong() - this.origin) / 1_000_000_000L;
        final int index = (int) Math.floorMod(second, (long) WINDOW_SECONDS);
        Bucket bucket = this.recent.get(index);
        while (bucket == null || bucket.second < second) {
            final Bucket replacement = new Bucket(second);
            if (this.recent.compareAndSet(index, bucket, replacement)) {
                bucket = replacement;
                break;
            }
            bucket = this.recent.get(index);
        }
        // A writer delayed for a full window must not overwrite a newer bucket.
        if (bucket.second == second) {
            bucket.total.add(elapsed);
            bucket.maximum.accumulateAndGet(elapsed, Math::max);
            bucket.count.increment();
        }
    }

    /** Live counters are approximate while workers update them. */
    public Snapshot snapshot() {
        return new Snapshot(this.samples.sum(), this.totalNanos.sum(), this.maximumNanos.get());
    }

    /** Current second and previous 59 seconds, attributed to task completion; live reads are approximate. */
    public Snapshot recentSnapshot() {
        final long second = (this.clock.getAsLong() - this.origin) / 1_000_000_000L;
        long count = 0L;
        long total = 0L;
        long maximum = 0L;
        for (int index = 0; index < WINDOW_SECONDS; index++) {
            final Bucket bucket = this.recent.get(index);
            if (bucket != null && bucket.second <= second && second - bucket.second < WINDOW_SECONDS) {
                count += bucket.count.sum();
                total += bucket.total.sum();
                maximum = Math.max(maximum, bucket.maximum.get());
            }
        }
        return new Snapshot(count, total, maximum);
    }

    private static final class Bucket {
        private final long second;
        private final LongAdder count = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final AtomicLong maximum = new AtomicLong();

        private Bucket(final long second) {
            this.second = second;
        }
    }

    public record Snapshot(long samples, long totalNanos, long maximumNanos) {
        public double averageMillis() {
            return this.samples == 0 ? 0.0 : this.totalNanos / (double) this.samples / 1_000_000.0;
        }
    }
}
