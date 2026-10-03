package org.leavesx.leavesx.network;

import java.util.concurrent.atomic.AtomicLong;

/** 累计编码计数，不代表送达确认，也不是待处理队列长度。 */
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
     * 请求数包含调用线程执行的工作；完成表示字节已发布，不表示网络已送达。
     * 回退数统计失败或取消后重试一次的编码；准入回退由工作线程池另行统计。
     * 断开的消费者可能不会发布结果，因此 submitted - completed 不能当作队列深度。
     */
    public record Snapshot(long submitted, long completed, long fallback, long failed) {
    }
}
