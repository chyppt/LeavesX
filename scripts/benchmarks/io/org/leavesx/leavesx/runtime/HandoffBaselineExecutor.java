package org.leavesx.leavesx.runtime;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs LeavesX pure-computation work that never reads or writes live world state. */
public final class HandoffBaselineExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(HandoffBaselineExecutor.class);
    private static final int MAX_QUEUED_TASKS = 128;
    /** Keep two scheduler lanes per worker so a slow range cannot strand the rest of the pool. */
    private static final int PARTITIONS_PER_LANE = 2;
    /** Maximum overflow ranges claimed by one worker at a time. */
    private static final int MAX_RANGES_PER_CLAIM = 4;
    private static final int MAX_PARTITIONS = 64;
    /** Cap automatic pure-compute workers while leaving headroom for the tick, network and GC threads. */
    private static final int MAX_AUTOMATIC_WORKERS = 24;
    /** Do not create ranges smaller than this when an idle pool is deliberately over-partitioned. */
    private static final int MIN_ADAPTIVE_ITEMS_PER_TASK = 32;
    /** 中等距离排序整批交给一个线程，避免分片后再次派发归并；更大的批次仍分片。 */
    private static final int MAX_SINGLE_WORKER_SORT_ITEMS = 2_048;
    /** Hard safety cap for callers that use the runtime API directly instead of the validated YAML config. */
    private static final int MAX_RUNTIME_WORKERS = 64;
    /** A short spin closes the common sub-millisecond tail without parking the server thread. */
    private static final int WAIT_SPIN_LIMIT = 32;
    /** Timed latch waits are deliberately shorter than one tick so a worker tail cannot add a full millisecond. */
    private static final long WAIT_PARK_NANOS = TimeUnit.MICROSECONDS.toNanos(250L);
    private static final int MAX_WINDOW_SAMPLES = 301;
    private static final Object WINDOW_SAMPLE_LOCK = new Object();
    private static final ArrayDeque<CounterSample> WINDOW_SAMPLES = new ArrayDeque<>(MAX_WINDOW_SAMPLES);
    private static volatile ComputeThreadPoolExecutor executor;
    private static volatile int workerCount = 1;
    private static volatile int minimumItemsPerWorker = 64;
    private static volatile boolean prestartWorkers = true;
    private static volatile boolean lowLoadWorkers;
    private static final AtomicLong lowLoadDispatchCount = new AtomicLong();
    private static final AtomicLong invocationCount = new AtomicLong();
    private static final AtomicLong parallelInvocationCount = new AtomicLong();
    private static final AtomicLong belowFeatureThresholdFallbackCount = new AtomicLong();
    private static final AtomicLong insufficientPartitionFallbackCount = new AtomicLong();
    private static final AtomicLong unavailableParallelismFallbackCount = new AtomicLong();
    private static final AtomicLong failedInvocationCount = new AtomicLong();
    private static final AtomicLong backpressureCallerRunCount = new AtomicLong();
    /** Invocations that requested more worker slots than could be reserved atomically. */
    private static final AtomicLong slotReservationShortageCount = new AtomicLong();
    /** Invocations forced to the caller because the shared queue was already occupied. */
    private static final AtomicLong queueContentionFallbackCount = new AtomicLong();
    /** Highest number of accepted-but-not-started batches observed in the bounded queue. */
    private static final AtomicLong maximumQueuedTaskCount = new AtomicLong();
    /** Number of worker submissions rejected by shutdown or queue saturation. */
    private static final AtomicLong rejectedTaskCount = new AtomicLong();
    /** Number of timed waits used while the caller helped a worker tail. */
    private static final AtomicLong timedWorkerWaitCount = new AtomicLong();
    /** Invocations completed entirely on the caller because no compute worker was immediately available. */
    private static final AtomicLong callerOnlyInvocationCount = new AtomicLong();
    /** Failed or skipped attempts that explicitly requested one clean full caller-thread recomputation. */
    private static final AtomicLong fullRecomputationRequestCount = new AtomicLong();
    /** Invocations whose reserved worker submission raced shutdown or bounded-queue rejection. */
    private static final AtomicLong submissionFallbackInvocationCount = new AtomicLong();
    private static final AtomicReference<FallbackEvent> lastFallbackEvent = new AtomicReference<>();
    // These counters describe where immutable ranges actually ran. They make a short-lived worker burst visible in
    // /leavesx threads even after the pool has gone idle again.
    private static final AtomicLong callerRangeCount = new AtomicLong();
    private static final AtomicLong workerRangeCount = new AtomicLong();
    private static final AtomicLong callerItemCount = new AtomicLong();
    private static final AtomicLong callerHelpRangeCount = new AtomicLong();
    private static final AtomicLong workerItemCount = new AtomicLong();
    private static final AtomicLong workerBatchCount = new AtomicLong();
    private static final AtomicLong maximumConcurrentWorkerCount = new AtomicLong();
    private static final AtomicLong adaptiveAdmissionCount = new AtomicLong();
    private static final AtomicLong adaptivePartitionInvocationCount = new AtomicLong();
    private static final AtomicLong adaptivePartitionCount = new AtomicLong();
    /** Marks code running on a LeavesX worker so nested pure computations can help inline instead of deadlocking. */
    private static final ThreadLocal<Boolean> COMPUTE_WORKER_CONTEXT = new ThreadLocal<>();
    private static final WorkloadCounters[] WORKLOAD_COUNTERS = createWorkloadCounters();
    private static volatile org.leavesx.leavesx.config.LeavesXConfig.ComputeFailureSettings failureProtection =
        org.leavesx.leavesx.config.LeavesXConfig.ComputeFailureSettings.defaults();
    private static volatile org.leavesx.leavesx.config.LeavesXConfig.ComputeSchedulingSettings schedulingSettings =
        org.leavesx.leavesx.config.LeavesXConfig.ComputeSchedulingSettings.defaults();

    private HandoffBaselineExecutor() {
    }

    /** 原子发布保护策略；普通重载不清除已有暂停，也不重建线程池。 */
    public static void configureFailureProtection(
        final org.leavesx.leavesx.config.LeavesXConfig.ComputeFailureSettings settings
    ) {
        failureProtection = Objects.requireNonNull(settings, "settings");
    }

    /** 原子发布调度策略；只影响后续准入，已提交任务按原生命周期完成。 */
    public static void configureScheduling(
        final org.leavesx.leavesx.config.LeavesXConfig.ComputeSchedulingSettings settings
    ) {
        schedulingSettings = Objects.requireNonNull(settings, "settings");
    }

    /** Creates the bounded worker pool during bootstrap. A configured value of zero selects a conservative default. */
    public static synchronized void configure(final int configuredWorkers) {
        configure(configuredWorkers, 64, true);
    }

    /** Creates the bounded worker pool and configures the minimum useful work assigned to each lane. */
    public static synchronized void configure(final int configuredWorkers, final int configuredMinimumItemsPerWorker) {
        configure(configuredWorkers, configuredMinimumItemsPerWorker, true);
    }

    /**
     * Creates the bounded worker pool with explicit startup behavior.
     *
     * <p>Prestarting the core workers removes the first-use thread creation pause after a server restart. The pool
     * remains bounded and daemon-backed; disabling this option restores lazy worker creation for very small hosts.</p>
     */
    public static synchronized void configure(
        final int configuredWorkers,
        final int configuredMinimumItemsPerWorker,
        final boolean configuredPrestartWorkers
    ) {
        configure(configuredWorkers, configuredMinimumItemsPerWorker, configuredPrestartWorkers, false);
    }

    /** 小任务模式只改变已有纯快照的派发，不把实体 Tick 或插件调用移动到工作线程。 */
    public static synchronized void configure(
        final int configuredWorkers,
        final int configuredMinimumItemsPerWorker,
        final boolean configuredPrestartWorkers,
        final boolean configuredLowLoadWorkers
    ) {
        shutdown();
        failureProtection = org.leavesx.leavesx.config.LeavesXConfig.ComputeFailureSettings.defaults();
        schedulingSettings = org.leavesx.leavesx.config.LeavesXConfig.ComputeSchedulingSettings.defaults();
        final int availableProcessors = Runtime.getRuntime().availableProcessors();
        final int automaticWorkers = automaticWorkerCount(availableProcessors);
        workerCount = normalizeWorkerCount(configuredWorkers, automaticWorkers);
        minimumItemsPerWorker = Math.max(16, configuredMinimumItemsPerWorker);
        prestartWorkers = configuredPrestartWorkers;
        lowLoadWorkers = configuredLowLoadWorkers;
        lowLoadDispatchCount.set(0L);
        invocationCount.set(0L);
        parallelInvocationCount.set(0L);
        belowFeatureThresholdFallbackCount.set(0L);
        insufficientPartitionFallbackCount.set(0L);
        unavailableParallelismFallbackCount.set(0L);
        failedInvocationCount.set(0L);
        backpressureCallerRunCount.set(0L);
        slotReservationShortageCount.set(0L);
        queueContentionFallbackCount.set(0L);
        maximumQueuedTaskCount.set(0L);
        rejectedTaskCount.set(0L);
        timedWorkerWaitCount.set(0L);
        callerOnlyInvocationCount.set(0L);
        fullRecomputationRequestCount.set(0L);
        submissionFallbackInvocationCount.set(0L);
        lastFallbackEvent.set(null);
        callerRangeCount.set(0L);
        workerRangeCount.set(0L);
        callerItemCount.set(0L);
        callerHelpRangeCount.set(0L);
        workerItemCount.set(0L);
        workerBatchCount.set(0L);
        maximumConcurrentWorkerCount.set(0L);
        adaptiveAdmissionCount.set(0L);
        adaptivePartitionInvocationCount.set(0L);
        adaptivePartitionCount.set(0L);
        for (final WorkloadCounters counters : WORKLOAD_COUNTERS) {
            counters.reset();
        }
        synchronized (WINDOW_SAMPLE_LOCK) {
            WINDOW_SAMPLES.clear();
            WINDOW_SAMPLES.addLast(captureCounterSample(System.nanoTime()));
        }
        executor = new ComputeThreadPoolExecutor(workerCount);
        // Keep started workers alive between sparse jobs; this avoids repeatedly paying thread startup latency.
        executor.allowCoreThreadTimeOut(false);
        if (prestartWorkers) {
            executor.prestartAllCoreThreads();
        }
    }

    public static int workerCount() {
        return workerCount;
    }

    public static boolean lowLoadWorkersEnabled() {
        final ComputeThreadPoolExecutor current = executor;
        // 停止或重建期间不进入小任务快照路径，避免只有设置开启却无线程可用。
        return lowLoadWorkers && current != null && !current.isShutdown();
    }

    /** 返回真正交给单个工作线程完成的低负载调用数，不能据此推断性能提升。 */
    public static long lowLoadDispatchCount() {
        return lowLoadDispatchCount.get();
    }

    /** Returns zero while the pool is stopped so other runtimes do not reserve CPU for stale worker settings. */
    static int configuredWorkerCount() {
        return executor == null ? 0 : workerCount;
    }

    static boolean allowsCoreThreadTimeout() {
        final ComputeThreadPoolExecutor current = executor;
        return current != null && current.allowsCoreThreadTimeOut();
    }

    static boolean prestartWorkers() {
        return prestartWorkers;
    }

    /**
     * 为独立快照计算借用一个立即可用的工作线程，不在分片任务后面排长队。
     * 调用者负责结果采用、异常回退和资源释放；此入口禁止读取实体或世界。
     */
    public static boolean tryExecuteSnapshot(final Runnable task, final Consumer<Throwable> cancelled) {
        return tryExecuteSnapshot(Workload.UNSPECIFIED, task, cancelled);
    }

    /** 仅作快照对象分配前的提示；真正提交仍会原子预留线程名额，不能依赖此提示发布结果。 */
    static boolean canExecuteSnapshot(final Workload workload) {
        final ComputeThreadPoolExecutor current = executor;
        return current != null && !current.isShutdown() && !isComputeWorkerThread()
            && !WORKLOAD_COUNTERS[workload.ordinal()].circuitOpen(System.nanoTime())
            && !lowPriorityBlocked(current, workload) && current.availableWorkerSlotCount() > 0;
    }

    /** 快照流水批次沿用同一线程预算、熔断保护和嵌套调用保护。 */
    static boolean tryExecuteSnapshot(final Workload workload, final Runnable task, final Consumer<Throwable> cancelled) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(cancelled, "cancelled");
        final ComputeThreadPoolExecutor current = executor;
        if (current == null || current.isShutdown() || isComputeWorkerThread()
            || WORKLOAD_COUNTERS[workload.ordinal()].circuitOpen(System.nanoTime())
            || lowPriorityBlocked(current, workload) || current.reserveWorkerSlots(1) == 0) {
            return false;
        }
        try {
            // 内部批次任务已经包含一次领取与取消状态；无需再套一层相同屏障。
            current.executeReserved(task instanceof SnapshotTask ? task : new SnapshotRunnable(task, cancelled));
            return true;
        } catch (final RejectedExecutionException rejection) {
            return false;
        }
    }

    /** 只在整批全部写入结束后记账；派发与调用线程接管分别统计，不把换线程当成同时并行。 */
    static void recordSnapshotBatch(
        final Workload workload, final int items, final long callerTasks, final long workerTasks,
        final long callerItems, final long workerItems, final int maximumWorkers, final long callerNanos,
        final boolean successful, final Throwable failure
    ) {
        final WorkloadCounters counters = WORKLOAD_COUNTERS[workload.ordinal()];
        invocationCount.incrementAndGet();
        counters.recordInvocation(items);
        counters.callerNanos.addAndGet(callerNanos);
        callerRangeCount.addAndGet(callerTasks);
        workerRangeCount.addAndGet(workerTasks);
        callerItemCount.addAndGet(callerItems);
        workerItemCount.addAndGet(workerItems);
        workerBatchCount.addAndGet(workerTasks);
        recordMaximumConcurrentWorkers(maximumWorkers);
        counters.recordLoad(callerTasks, workerTasks, callerItems, workerItems, maximumWorkers, workerTasks);
        if (!successful) {
            if (failure != null) LOGGER.error("LeavesX {} 快照批次失败，已等待全部任务结束，将完整重算", workload.displayName(), failure);
            failedInvocationCount.incrementAndGet();
            counters.recordFailure(System.nanoTime());
            recordFullRecomputation(workload, counters, FallbackReason.COMPUTATION_FAILED, items);
        } else if (workerTasks > 0) {
            parallelInvocationCount.incrementAndGet();
            counters.recordSuccess();
        } else {
            callerOnlyInvocationCount.incrementAndGet();
        }
    }

    static int automaticWorkerCount(final int availableProcessors) {
        return Math.max(1, Math.min(MAX_AUTOMATIC_WORKERS, availableProcessors - 2));
    }

    /**
     * Clamps direct API input to a usable fixed-pool size. YAML validation normally catches bad values first, but this
     * guard keeps reload hooks and embedded callers from creating a negative or unbounded executor.
     */
    static int normalizeWorkerCount(final int configuredWorkers, final int automaticWorkers) {
        if (configuredWorkers <= 0) {
            return Math.max(1, Math.min(MAX_RUNTIME_WORKERS, automaticWorkers));
        }
        return Math.max(1, Math.min(MAX_RUNTIME_WORKERS, configuredWorkers));
    }

    /**
     * Returns the maximum number of ranges that can make progress concurrently.
     *
     * <p>The calling tick thread owns one lane so it continues doing useful pure computation instead of waiting for
     * every worker range. A single configured worker retains the historical synchronous fallback.</p>
     */
    public static int computeLaneCount() {
        return executor != null && workerCount > 1 ? workerCount + 1 : 1;
    }

    /**
     * Returns the maximum number of deterministic partition slots a pure-computation caller may need.
     *
     * <p>Most callers keep one scratch slice per partition (for example, one radix histogram per AI range). Keeping
     * this capacity calculation beside the scheduler prevents those callers from assuming that one task always maps
     * to one physical worker.</p>
     */
    public static int computePartitionCapacity() {
        final int lanes = computeLaneCount();
        if (lanes <= 1) {
            return 1;
        }
        return Math.min(MAX_PARTITIONS, lanes * PARTITIONS_PER_LANE);
    }

    /**
     * Returns whether a configured pure-computation batch should be submitted now.
     *
     * <p>The configured threshold remains the normal admission guard. When the pool is idle, a batch down to half
     * that threshold is admitted so medium AI/entity snapshots can use otherwise idle cores. This method is only an
     * admission hint; callers still have to use {@link #invokeRangesWithCount(Workload, int, int, RangeTask)} and keep
     * all live-world access on the calling thread.</p>
     */
    public static boolean shouldAttempt(final Workload workload, final int size, final int minimumSize) {
        return shouldAttempt(workload, size, minimumSize, minimumItemsPerWorker);
    }

    /**
     * Checks the same workload-specific granularity as submission before the caller captures a coordinate snapshot.
     * This is only a hint: another invocation can reserve the available workers before the snapshot is submitted.
     */
    public static boolean shouldAttempt(
        final Workload workload,
        final int size,
        final int minimumSize,
        final int minimumItemsPerTask
    ) {
        Objects.requireNonNull(workload, "workload");
        if (size <= 0) {
            return false;
        }
        final ComputeThreadPoolExecutor current = executor;
        final boolean allowSmall = lowLoadWorkers && supportsLowLoadScheduling(workload);
        if (current == null || current.isShutdown() || (workerCount <= 1 && !allowSmall)) {
            return rejectBeforeSubmission(workload, size, FallbackReason.POOL_UNAVAILABLE);
        }
        if (WORKLOAD_COUNTERS[workload.ordinal()].circuitOpen(System.nanoTime())) {
            return rejectBeforeSubmission(workload, size, FallbackReason.CIRCUIT_OPEN);
        }
        if (lowPriorityBlocked(current, workload)) {
            return rejectBeforeSubmission(workload, size, FallbackReason.QUEUE_CONTENTION_CALLER_ONLY);
        }
        if (allowSmall) {
            if (isComputeWorkerThread() || current.availableWorkerSlotCount() > 0) return true;
            return rejectBeforeSubmission(workload, size, FallbackReason.POOL_BUSY_CALLER_ONLY);
        }
        final boolean adaptivelyAdmitted = size < minimumSize
            && canAdaptivelyAdmit(current, workload, size, minimumSize);
        if (size < minimumSize && !adaptivelyAdmitted) {
            return rejectBeforeSubmission(workload, size, FallbackReason.BELOW_THRESHOLD);
        }
        if (adaptivePartitionCountFor(
            current, workload, size, computeLaneCount(), minimumItemsPerTask, adaptivelyAdmitted || minimumSize >= 256
        ) <= 1) {
            return rejectBeforeSubmission(workload, size, FallbackReason.INSUFFICIENT_PARTITIONS);
        }
        if (!isComputeWorkerThread() && current.availableWorkerSlotCount() == 0) {
            // A later submission still tolerates contention, but an already occupied pool cannot justify copying
            // live coordinates now just to execute the same complete operation on the caller.
            return rejectBeforeSubmission(workload, size, FallbackReason.POOL_BUSY_CALLER_ONLY);
        }
        return true;
    }

    /** Records batches rejected by the cheap pre-snapshot gate so diagnostics do not misleadingly report zero work. */
    private static boolean rejectBeforeSubmission(
        final Workload workload,
        final int size,
        final FallbackReason reason
    ) {
        final WorkloadCounters counters = WORKLOAD_COUNTERS[workload.ordinal()];
        invocationCount.incrementAndGet();
        counters.recordInvocation(size);
        recordAdmissionRejection(workload, counters, reason, size);
        return false;
    }

    /** Records skipped admission once, without misclassifying it as a failed partial computation. */
    private static void recordAdmissionRejection(
        final Workload workload,
        final WorkloadCounters counters,
        final FallbackReason reason,
        final int size
    ) {
        switch (reason) {
            case BELOW_THRESHOLD -> {
                belowFeatureThresholdFallbackCount.incrementAndGet();
                counters.belowFeatureThresholdFallbacks.incrementAndGet();
            }
            case INSUFFICIENT_PARTITIONS -> {
                insufficientPartitionFallbackCount.incrementAndGet();
                counters.insufficientPartitionFallbacks.incrementAndGet();
            }
            case POOL_UNAVAILABLE, POOL_BUSY_CALLER_ONLY, CIRCUIT_OPEN -> {
                unavailableParallelismFallbackCount.incrementAndGet();
                counters.unavailableParallelismFallbacks.incrementAndGet();
            }
            case QUEUE_CONTENTION_CALLER_ONLY -> {
                queueContentionFallbackCount.incrementAndGet();
                unavailableParallelismFallbackCount.incrementAndGet();
                counters.unavailableParallelismFallbacks.incrementAndGet();
            }
            default -> throw new IllegalArgumentException("not an admission rejection: " + reason);
        }
        recordFallback(workload, reason, size);
    }

    private static boolean canAdaptivelyAdmit(
        final ComputeThreadPoolExecutor current,
        final Workload workload,
        final int size,
        final int minimumSize
    ) {
        return supportsAdaptiveScheduling(workload)
            && minimumSize >= 256
            // Adding one before dividing would overflow at Integer.MAX_VALUE and admit tiny batches.
            && size >= Math.max(MIN_ADAPTIVE_ITEMS_PER_TASK, divideCeil(minimumSize, 2))
            && isIdleForAdaptiveDispatch(current);
    }

    /**
     * Processes contiguous ranges and waits for every range before returning.
     *
     * @return {@code false} when submission or execution failed, allowing the caller to recompute synchronously
     */
    public static boolean invokeRanges(final int size, final int minimumSize, final RangeTask task) {
        return invokeRanges(Workload.UNSPECIFIED, size, minimumSize, task);
    }

    /** Processes ranges while attributing the invocation to one diagnostic workload. */
    public static boolean invokeRanges(
        final Workload workload,
        final int size,
        final int minimumSize,
        final RangeTask task
    ) {
        return invokeRangesWithCount(workload, size, minimumSize, task) > 0;
    }

    /**
     * Processes contiguous ranges and returns the exact completed partition count.
     *
     * <p>Callers that merge independently processed ranges must use this result instead of deriving boundaries from
     * the configured worker count, because adaptive partitioning may deliberately use fewer lanes.</p>
     *
     * @return completed partition count, or {@code 0} when the caller must recompute synchronously
     */
    public static int invokeRangesWithCount(final int size, final int minimumSize, final RangeTask task) {
        return invokeRangesWithCount(Workload.UNSPECIFIED, size, minimumSize, task);
    }

    /** Processes ranges and records why this specific workload ran in parallel or fell back. */
    public static int invokeRangesWithCount(
        final Workload workload,
        final int size,
        final int minimumSize,
        final RangeTask task
    ) {
        return invokeRangesWithCount(workload, size, minimumSize, minimumItemsPerWorker, task);
    }

    /**
     * Processes ranges with a workload-specific partition size.
     *
     * <p>This is intended for coarse operations whose item is already a sizeable independent range, such as one
     * merge pair. Ordinary element-wise workloads should use the overload that honors the configured global size.</p>
     */
    public static int invokeRangesWithCount(
        final Workload workload,
        final int size,
        final int minimumSize,
        final int minimumItemsPerTask,
        final RangeTask task
    ) {
        final long started = System.nanoTime();
        try {
            return invokeRangesUntimed(workload, size, minimumSize, minimumItemsPerTask, task);
        } finally {
            // 调用线程从提交到合并的完整墙钟时间，用于判断并行是否真正减轻 Tick 负担。
            if (workload != null && size > 0) WORKLOAD_COUNTERS[workload.ordinal()].callerNanos.addAndGet(System.nanoTime() - started);
        }
    }

    private static int invokeRangesUntimed(
        final Workload workload,
        final int size,
        final int minimumSize,
        final int minimumItemsPerTask,
        final RangeTask task
    ) {
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(task, "task");
        if (size <= 0) {
            return 0;
        }
        final WorkloadCounters workloadCounters = WORKLOAD_COUNTERS[workload.ordinal()];
        invocationCount.incrementAndGet();
        workloadCounters.recordInvocation(size);
        final ComputeThreadPoolExecutor current = executor;
        final boolean allowSmall = lowLoadWorkers && supportsLowLoadScheduling(workload);
        if (current == null || current.isShutdown() || (workerCount <= 1 && !allowSmall)) {
            recordAdmissionRejection(workload, workloadCounters, FallbackReason.POOL_UNAVAILABLE, size);
            return 0;
        }
        if (workloadCounters.circuitOpen(System.nanoTime())) {
            recordAdmissionRejection(workload, workloadCounters, FallbackReason.CIRCUIT_OPEN, size);
            return 0;
        }
        // 低优先级生成计算不能占用实时预留线程；拒绝发生在派发前，调用方仍执行完整原计算。
        if (lowPriorityBlocked(current, workload)) {
            recordAdmissionRejection(workload, workloadCounters, FallbackReason.QUEUE_CONTENTION_CALLER_ONLY, size);
            return 0;
        }
        if (allowSmall && workload == Workload.AI_TARGET_SORT && size <= MAX_SINGLE_WORKER_SORT_ITEMS) {
            // 距离计算与稳定排序在同一任务内完成，调用线程只采用完整结果。
            return invokeSmallOnWorker(current, workload, workloadCounters, task, size);
        }
        final boolean belowConfiguredThreshold = size < minimumSize;
        final boolean adaptivelyAdmitted = belowConfiguredThreshold
            && canAdaptivelyAdmit(current, workload, size, minimumSize);
        if (belowConfiguredThreshold && !adaptivelyAdmitted) {
            if (allowSmall) return invokeSmallOnWorker(current, workload, workloadCounters, task, size);
            recordAdmissionRejection(workload, workloadCounters, FallbackReason.BELOW_THRESHOLD, size);
            return 0;
        }
        if (adaptivelyAdmitted) {
            adaptiveAdmissionCount.incrementAndGet();
        }

        // Use a bounded amount of over-partitioning for large batches. The extra ranges are still contiguous and
        // deterministic, but give the executor enough work units to keep all workers busy when one range is slower.
        final int lanes = computeLaneCount();
        final int baselineTaskCount = partitionCountFor(size, lanes, minimumItemsPerTask);
        final int taskCount = adaptivePartitionCountFor(
            current,
            workload,
            size,
            lanes,
            minimumItemsPerTask,
            adaptivelyAdmitted || minimumSize >= 256
        );
        if (taskCount <= 1) {
            if (allowSmall) return invokeSmallOnWorker(current, workload, workloadCounters, task, size);
            recordAdmissionRejection(workload, workloadCounters, FallbackReason.INSUFFICIENT_PARTITIONS, size);
            return 0;
        }
        if (taskCount > baselineTaskCount) {
            adaptivePartitionInvocationCount.incrementAndGet();
            adaptivePartitionCount.addAndGet(taskCount - baselineTaskCount);
        }

        // A worker that invokes another pure computation must not submit work and then wait for the same bounded pool:
        // if every worker does this simultaneously, all of them would be waiting for queued children. Execute the
        // nested ranges on the current worker instead. The data contract is unchanged because the callback is still
        // pure and receives only its caller-owned snapshot.
        if (isComputeWorkerThread()) {
            return invokeInlineOnWorker(workload, workloadCounters, task, size, taskCount);
        }
        // Use overflow-safe ceiling division: a diagnostic or plugin can legally pass a near-maximal primitive array
        // size, and adding taskCount to that value would otherwise wrap into a negative range.
        final int rangeSize = divideCeil(size, taskCount);
        final int expectedTaskCount = divideCeil(size, rangeSize);
        // Never submit more batches than the pool can start immediately. A busy compute pool is shared by all
        // immutable workloads; queuing a second batch here makes the calling tick wait behind unrelated work and
        // is the main source of sudden MSPT spikes. The caller still owns the final range, so a fully occupied pool
        // makes progress synchronously without adding queue pressure.
        final int requestedWorkerTasks = Math.min(availableSlotsFor(current, workload), expectedTaskCount - 1);
        // Reserve the observed slots before submitting. Multiple snapshot producers can arrive concurrently; without
        // a reservation both would observe the same idle workers and the later invocation would enqueue behind them.
        final int workerTaskCount = current.reserveWorkerSlots(requestedWorkerTasks);
        if (workerTaskCount < requestedWorkerTasks) {
            slotReservationShortageCount.incrementAndGet();
        }
        if (workerTaskCount == 0 && expectedTaskCount > 1 && current.queuedTaskCount() > 0) {
            queueContentionFallbackCount.incrementAndGet();
        }
        final int rangesPerClaim = rangesPerClaim(expectedTaskCount, workerTaskCount);
        // The latch counts logical worker-owned ranges, not queue entries. One submitted batch may claim several
        // overflow ranges, all of which must finish before a caller can merge their private results.
        final RangeInvocation invocation = new RangeInvocation(task, workloadCounters, expectedTaskCount - 1, workerTaskCount);
        if (workerTaskCount == 0) {
            // The caller still completes the immutable ranges once, preserving the existing no-retry contract for
            // callers that merge per-partition results. Marking these as backpressure runs makes the reason visible
            // in /leavesx threads without waiting for a busy worker pool.
            unavailableParallelismFallbackCount.incrementAndGet();
            workloadCounters.unavailableParallelismFallbacks.incrementAndGet();
            callerOnlyInvocationCount.incrementAndGet();
            workloadCounters.callerOnlyInvocations.incrementAndGet();
            recordFallback(workload, FallbackReason.POOL_BUSY_CALLER_ONLY, size);
            invocation.runAllOnCaller(rangeSize, expectedTaskCount, size);
            invocation.recordLoad();
            if (invocation.failure != null) {
                LOGGER.error("LeavesX synchronous backpressure computation failed", invocation.failure);
                failedInvocationCount.incrementAndGet();
                workloadCounters.recordFailure(System.nanoTime());
                recordFullRecomputation(workload, workloadCounters, FallbackReason.COMPUTATION_FAILED, size);
                return 0;
            }
            return expectedTaskCount;
        }
        // Reserve one initial range for every submitted worker so very fast ranges cannot all be consumed by the
        // first thread that wakes up. The shared cursor starts after those guaranteed ranges and balances only the
        // remaining overflow work.
        final AtomicInteger nextTask = new AtomicInteger(workerTaskCount);
        boolean submissionFellBack = false;
        for (int workerIndex = 0; workerIndex < workerTaskCount; workerIndex++) {
            try {
                current.executeReserved(new RangeBatchRunnable(
                    invocation,
                    workerIndex,
                    nextTask,
                    rangeSize,
                    expectedTaskCount,
                    size,
                    rangesPerClaim
                ));
            } catch (final RejectedExecutionException rejection) {
                if (!submissionFellBack) {
                    submissionFellBack = true;
                    submissionFallbackInvocationCount.incrementAndGet();
                    workloadCounters.submissionFallbackInvocations.incrementAndGet();
                    recordFallback(workload, FallbackReason.SUBMISSION_REJECTED, size);
                }
                invocation.runInitialRangesOnCaller(workerIndex, workerTaskCount, rangeSize, size, true);
                invocation.runRemainingOnCaller(nextTask, rangeSize, expectedTaskCount - 1, size, true);
                current.releaseWorkerSlotReservations(workerTaskCount - workerIndex - 1);
                invocation.cancelWorkerBatches(workerTaskCount - workerIndex);
                break;
            } catch (final RuntimeException submissionFailure) {
                if (!submissionFellBack) {
                    submissionFellBack = true;
                    submissionFallbackInvocationCount.incrementAndGet();
                    workloadCounters.submissionFallbackInvocations.incrementAndGet();
                    recordFallback(workload, FallbackReason.SUBMISSION_FAILED, size);
                }
                LOGGER.error(
                    "LeavesX parallel computation could not be submitted; running remaining ranges on the main thread",
                    submissionFailure
                );
                invocation.runInitialRangesOnCaller(workerIndex, workerTaskCount, rangeSize, size, true);
                invocation.runRemainingOnCaller(nextTask, rangeSize, expectedTaskCount - 1, size, true);
                current.releaseWorkerSlotReservations(workerTaskCount - workerIndex - 1);
                invocation.cancelWorkerBatches(workerTaskCount - workerIndex);
                break;
            }
        }

        // The calling tick thread remains productive by owning the final range. Workers claim every earlier range
        // from the shared cursor; this avoids a caller/worker race that could otherwise let the caller steal all work
        // before newly submitted workers start running.
        final int callerTaskIndex = expectedTaskCount - 1;
        invocation.runOnCaller(callerTaskIndex, callerTaskIndex * rangeSize, size, false);
        // The tick thread helps with unclaimed immutable ranges while workers finish. This keeps the caller useful
        // during a slow or uneven batch without moving any live-world operation off the server thread.
        invocation.awaitWorkersWithHelping(nextTask, rangeSize, expectedTaskCount - 1, size, rangesPerClaim);
        invocation.recordLoad();
        final Throwable failure = invocation.failure;
        if (failure != null) {
            LOGGER.error("LeavesX parallel computation failed; recomputing on the main thread", failure);
        }
        final boolean completed = failure == null;
        if (completed) {
            parallelInvocationCount.incrementAndGet();
            workloadCounters.recordSuccess();
        } else {
            failedInvocationCount.incrementAndGet();
            workloadCounters.recordFailure(System.nanoTime());
            recordFullRecomputation(workload, workloadCounters, FallbackReason.COMPUTATION_FAILED, size);
        }
        return completed ? expectedTaskCount : 0;
    }

    /**
     * Selects a small claim width for the shared overflow cursor.
     *
     * <p>When a batch has only a few more ranges than workers, claiming one range at a time keeps the tail
     * distributed instead of allowing the first worker to consume the whole remainder. Larger batches claim more
     * ranges per atomic update to reduce cursor contention. LeavesX beta8 D1: the width scales continuously
     * with the mean overflow per worker instead of stepping between fixed thresholds, so very large snapshots
     * stay at the cap while medium batches converge to a fine tail without a jump.</p>
     */
    static int rangesPerClaim(final int taskCount, final int workerTaskCount) {
        if (taskCount <= 0 || workerTaskCount <= 0) {
            return 1;
        }
        final int overflowRanges = Math.max(0, taskCount - workerTaskCount - 1);
        if (overflowRanges <= workerTaskCount) {
            return 1;
        }
        // Continuous scaling: overflow-per-worker rounded down plus one, clamped to [2, MAX_RANGES_PER_CLAIM].
        // A batch with 3x overflow per worker reaches the cap; anything smaller sits proportionally between.
        final int scaled = overflowRanges / workerTaskCount + 1;
        return Math.max(2, Math.min(MAX_RANGES_PER_CLAIM, scaled));
    }

    /** Shrinks the last claim so a worker cannot strand a tiny tail while another worker is still running. */
    static int claimWidthFor(final int remainingRanges, final int maximumClaim) {
        if (remainingRanges <= 0) {
            return 1;
        }
        final int boundedMaximum = Math.max(1, Math.min(MAX_RANGES_PER_CLAIM, maximumClaim));
        return remainingRanges <= boundedMaximum * 2 ? 1 : boundedMaximum;
    }

    /** Keeps the width reserved by an atomic cursor update attached to the range that must be completed. */
    static RangeClaim claimRange(final int startInclusive, final int claimedWidth, final int limitExclusive) {
        return new RangeClaim(
            startInclusive,
            Math.min(limitExclusive, startInclusive + Math.max(1, claimedWidth))
        );
    }

    record RangeClaim(int startInclusive, int endExclusive) {
    }

    static int taskCountFor(final int size, final int availableLanes, final int minimumItems) {
        if (size <= 0 || availableLanes <= 1) {
            return 1;
        }
        final int boundedMinimumItems = Math.max(1, minimumItems);
        final int usefulLanes = Math.max(1, size / boundedMinimumItems);
        return Math.min(availableLanes, usefulLanes);
    }

    /** Returns {@code ceil(dividend / divisor)} without overflowing the dividend during addition. */
    static int divideCeil(final int dividend, final int divisor) {
        if (dividend <= 0) {
            return 0;
        }
        if (divisor <= 0) {
            throw new IllegalArgumentException("divisor must be positive");
        }
        return 1 + (dividend - 1) / divisor;
    }

    /**
     * Chooses a bounded number of deterministic ranges for one invocation.
     *
     * <p>{@link #taskCountFor(int, int, int)} describes the physical lane count and remains useful for diagnostics
     * and compatibility tests. Runtime scheduling deliberately allows two ranges per lane, but never creates more
     * ranges than the input can fill with the requested minimum work.</p>
     */
    static int partitionCountFor(final int size, final int availableLanes, final int minimumItems) {
        if (size <= 0 || availableLanes <= 1) {
            return 1;
        }
        final int boundedMinimumItems = Math.max(1, minimumItems);
        final int usefulPartitions = Math.max(1, size / boundedMinimumItems);
        final int boundedLanes = Math.min(MAX_PARTITIONS, availableLanes);
        final int partitionBudget = Math.min(
            MAX_PARTITIONS,
            boundedLanes * PARTITIONS_PER_LANE
        );
        return Math.min(partitionBudget, usefulPartitions);
    }

    /**
     * Uses finer deterministic ranges while the pool is idle, then falls back to the configured granularity under
     * contention. The scheduler never exceeds the same bounded partition budget used by scratch-buffer callers.
     */
    private static int adaptivePartitionCountFor(
        final ComputeThreadPoolExecutor current,
        final Workload workload,
        final int size,
        final int availableLanes,
        final int minimumItems,
        final boolean allowAdaptivePartitioning
    ) {
        final int baseline = partitionCountFor(size, availableLanes, minimumItems);
        if (!allowAdaptivePartitioning
            || !supportsAdaptivePartitioning(workload)
            || !isIdleForAdaptiveDispatch(current)) {
            return baseline;
        }

        // AI distance and AABB work benefit from shorter ranges on large hosts. Spawn counting deliberately retains
        // its baseline range size because every range owns a hash table that must later be merged.
        final int adaptiveMinimum = adaptiveMinimumItemsForWorkerCount(minimumItems, workerCount);
        return Math.max(baseline, partitionCountFor(size, availableLanes, adaptiveMinimum));
    }

    /**
     * Makes coarse idle snapshots finer on high-core hosts while keeping small hosts at the proven granularity.
     *
     * <p>This only affects audited primitive workloads during an idle-pool admission. It never changes callback
     * order, range boundaries after partitioning, or any live-world operation.</p>
     */
    static int adaptiveMinimumItemsForWorkerCount(final int minimumItems, final int workers) {
        final int divisor = workers >= 16 ? 8 : workers >= 8 ? 6 : workers >= 4 ? 4 : 2;
        return Math.max(MIN_ADAPTIVE_ITEMS_PER_TASK, Math.max(1, minimumItems / divisor));
    }

    /** Returns true when enough workers are free to benefit from finer ranges without queueing behind another batch. */
    private static boolean isIdleForAdaptiveDispatch(final ComputeThreadPoolExecutor current) {
        return current == executor
            // ArrayBlockingQueue.isEmpty() takes the queue lock. Admission checks run on the server tick hot path,
            // so use the executor-owned counter instead; it does not expose a mutable task to the callback.
            && current.queuedTaskCount() == 0
            && current.availableWorkerSlotCount() >= Math.max(1, (workerCount + 1) / 2);
    }

    private static boolean supportsAdaptiveScheduling(final Workload workload) {
        return switch (workload) {
            case AI_TARGET_SORT, AI_TARGET_SELECT, ENTITY_ACTIVATION, ENTITY_TRACKING, MOB_SPAWN_COUNTING -> true;
            case UNSPECIFIED, AI_TARGET_MERGE, MOB_COLLISION, WORLDGEN_NOISE, WORLDGEN_FEATURE -> false;
        };
    }

    /** 自动模式下，至少 4 个计算线程才保留一个实时名额，小服务器不牺牲生成吞吐。 */
    private static int realtimeReservedWorkers() {
        final int configured = schedulingSettings.realtimeReservedWorkers();
        final int automatic = workerCount >= 4 ? 1 : 0;
        return Math.min(Math.max(0, workerCount - 1), configured < 0 ? automatic : configured);
    }

    private static int lowPriorityQueueLimit() {
        final int configured = schedulingSettings.lowPriorityQueueLimit();
        return configured == 0 ? Math.max(2, workerCount * 2) : configured;
    }

    private static boolean isBackgroundWorkload(final Workload workload) {
        return workload.priority() != WorkloadPriority.HIGH;
    }

    private static int availableSlotsFor(final ComputeThreadPoolExecutor current, final Workload workload) {
        final int available = current.availableWorkerSlotCount();
        return isBackgroundWorkload(workload) ? Math.max(0, available - realtimeReservedWorkers()) : available;
    }

    private static boolean lowPriorityBlocked(final ComputeThreadPoolExecutor current, final Workload workload) {
        if (!isBackgroundWorkload(workload) || isComputeWorkerThread()) return false;
        return current.queuedTaskCount() > lowPriorityQueueLimit() || availableSlotsFor(current, workload) == 0;
    }

    private static boolean supportsLowLoadScheduling(final Workload workload) {
        return switch (workload) {
            case AI_TARGET_SORT, AI_TARGET_SELECT, ENTITY_ACTIVATION, ENTITY_TRACKING, MOB_SPAWN_COUNTING, MOB_COLLISION -> true;
            // 归并和未分类任务保留原准入，不能给没有收益的末尾排序再加一次派发。
            case UNSPECIFIED, AI_TARGET_MERGE, WORLDGEN_NOISE, WORLDGEN_FEATURE -> false;
        };
    }

    /** 单份快照派发给一个空闲线程；不排队等待名额，也不拆空分片。 */
    private static int invokeSmallOnWorker(
        final ComputeThreadPoolExecutor current,
        final Workload workload,
        final WorkloadCounters counters,
        final RangeTask task,
        final int size
    ) {
        if (isComputeWorkerThread()) return invokeInlineOnWorker(workload, counters, task, size, 1);
        if (current.reserveWorkerSlots(1) == 0) {
            recordAdmissionRejection(workload, counters, FallbackReason.POOL_BUSY_CALLER_ONLY, size);
            return 0;
        }
        // 单个范围不需要游标、帮助分片或两道闩锁，避免把调度成本放大到计算成本之上。
        final SmallRangeRunnable batch = new SmallRangeRunnable(task, size);
        try {
            current.executeReserved(batch);
        } catch (final RuntimeException submissionFailure) {
            batch.cancel();
            submissionFallbackInvocationCount.incrementAndGet();
            counters.submissionFallbackInvocations.incrementAndGet();
            recordFullRecomputation(workload, counters,
                submissionFailure instanceof RejectedExecutionException ? FallbackReason.SUBMISSION_REJECTED : FallbackReason.SUBMISSION_FAILED, size);
            return 0;
        }
        batch.awaitCompletion();
        if (batch.executed) {
            workerRangeCount.incrementAndGet();
            workerItemCount.addAndGet(size);
            workerBatchCount.incrementAndGet();
            recordMaximumConcurrentWorkers(1);
            counters.recordLoad(0L, 1L, 0L, size, 1, 1L);
        }
        if (batch.failure != null) {
            failedInvocationCount.incrementAndGet();
            counters.recordFailure(System.nanoTime());
            recordFullRecomputation(workload, counters, FallbackReason.COMPUTATION_FAILED, size);
            return 0;
        }
        lowLoadDispatchCount.incrementAndGet();
        parallelInvocationCount.incrementAndGet();
        counters.recordSuccess();
        return 1;
    }

    private static boolean supportsAdaptivePartitioning(final Workload workload) {
        return switch (workload) {
            case AI_TARGET_SORT, AI_TARGET_SELECT, ENTITY_ACTIVATION, ENTITY_TRACKING -> true;
            case UNSPECIFIED, AI_TARGET_MERGE, MOB_SPAWN_COUNTING, MOB_COLLISION, WORLDGEN_NOISE, WORLDGEN_FEATURE -> false;
        };
    }

    private static boolean isComputeWorkerThread() {
        return Boolean.TRUE.equals(COMPUTE_WORKER_CONTEXT.get());
    }

    /** Executes all partitions inline on a worker when a nested invocation would otherwise self-block the pool. */
    private static int invokeInlineOnWorker(
        final Workload workload,
        final WorkloadCounters workloadCounters,
        final RangeTask task,
        final int size,
        final int taskCount
    ) {
        final RangeInvocation invocation = new RangeInvocation(task, workloadCounters, 0, 0);
        final int rangeSize = (size + taskCount - 1) / taskCount;
        for (int taskIndex = 0; taskIndex < taskCount; taskIndex++) {
            final int rangeStart = taskIndex * rangeSize;
            final int rangeEnd = Math.min(size, rangeStart + rangeSize);
            invocation.runInlineWorker(taskIndex, rangeStart, rangeEnd);
        }
        invocation.recordLoad();
        final Throwable failure = invocation.failure;
        if (failure != null) {
            LOGGER.error("LeavesX nested pure computation failed; requesting synchronous recomputation", failure);
            failedInvocationCount.incrementAndGet();
            workloadCounters.recordFailure(System.nanoTime());
            recordFullRecomputation(workload, workloadCounters, FallbackReason.NESTED_COMPUTATION_FAILED, size);
            return 0;
        }
        parallelInvocationCount.incrementAndGet();
        workloadCounters.recordSuccess();
        return taskCount;
    }

    private static void recordMaximumConcurrentWorkers(final int count) {
        recordMaximum(maximumConcurrentWorkerCount, count);
    }

    /** Updates a monotonic diagnostic maximum without introducing a lock on the server tick path. */
    private static void recordMaximum(final AtomicLong maximum, final long count) {
        long current = maximum.get();
        while (count > current && !maximum.compareAndSet(current, count)) {
            current = maximum.get();
        }
    }

    private static void recordFullRecomputation(
        final Workload workload,
        final WorkloadCounters counters,
        final FallbackReason reason,
        final int size
    ) {
        fullRecomputationRequestCount.incrementAndGet();
        counters.mainThreadFallbacks.incrementAndGet();
        recordFallback(workload, reason, size);
    }

    private static void recordFallback(final Workload workload, final FallbackReason reason, final int size) {
        lastFallbackEvent.set(new FallbackEvent(System.nanoTime(), workload, reason, Math.max(0, size)));
    }

    /** Captures one bounded rolling counter sample; called once per second by the tick diagnostics. */
    public static void sampleMetrics(final long capturedNanos) {
        synchronized (WINDOW_SAMPLE_LOCK) {
            final CounterSample previous = WINDOW_SAMPLES.peekLast();
            if (previous != null && capturedNanos <= previous.capturedNanos()) {
                return;
            }
            if (WINDOW_SAMPLES.size() == MAX_WINDOW_SAMPLES) {
                WINDOW_SAMPLES.removeFirst();
            }
            WINDOW_SAMPLES.addLast(captureCounterSample(capturedNanos));
        }
    }

    /** Returns counter deltas for the requested recent window without resetting lifetime diagnostics. */
    public static WindowMetrics recentMetrics(final int requestedSeconds) {
        return recentMetrics(requestedSeconds, System.nanoTime());
    }

    /** Lets regression tests exercise a complete window without sleeping or altering the production clock. */
    static WindowMetrics recentMetrics(final int requestedSeconds, final long capturedNanos) {
        final int windowSeconds = Math.clamp(requestedSeconds, 10, 300);
        final CounterSample latest = captureCounterSample(capturedNanos);
        final CounterSample baseline;
        synchronized (WINDOW_SAMPLE_LOCK) {
            CounterSample candidate = WINDOW_SAMPLES.peekFirst();
            final long cutoff = latest.capturedNanos() - TimeUnit.SECONDS.toNanos(windowSeconds);
            for (final CounterSample sample : WINDOW_SAMPLES) {
                if (sample.capturedNanos() > cutoff) {
                    break;
                }
                candidate = sample;
            }
            baseline = candidate == null ? latest : candidate;
        }

        final List<WorkloadWindowMetrics> workloads = new ArrayList<>(Workload.values().length);
        for (final Workload workload : Workload.values()) {
            final int index = workload.ordinal();
            final WorkloadWindowMetrics metrics = new WorkloadWindowMetrics(
                workload,
                delta(latest.workloadInvocations()[index], baseline.workloadInvocations()[index]),
                delta(latest.workloadParallelInvocations()[index], baseline.workloadParallelInvocations()[index]),
                delta(latest.workloadBelowThresholdFallbacks()[index], baseline.workloadBelowThresholdFallbacks()[index]),
                delta(latest.workloadInsufficientPartitionFallbacks()[index], baseline.workloadInsufficientPartitionFallbacks()[index]),
                delta(latest.workloadUnavailableParallelismFallbacks()[index], baseline.workloadUnavailableParallelismFallbacks()[index]),
                delta(latest.workloadMainThreadFallbacks()[index], baseline.workloadMainThreadFallbacks()[index]),
                delta(latest.workloadCallerOnlyInvocations()[index], baseline.workloadCallerOnlyInvocations()[index]),
                delta(latest.workloadFailedInvocations()[index], baseline.workloadFailedInvocations()[index]),
                delta(latest.workloadSubmissionFallbacks()[index], baseline.workloadSubmissionFallbacks()[index]),
                delta(latest.workloadBackpressureRuns()[index], baseline.workloadBackpressureRuns()[index]),
                delta(latest.workloadCallerItems()[index], baseline.workloadCallerItems()[index]),
                delta(latest.workloadWorkerItems()[index], baseline.workloadWorkerItems()[index])
            );
            if (metrics.invocations() > 0L) {
                workloads.add(metrics);
            }
        }
        return new WindowMetrics(
            Math.max(0L, latest.capturedNanos() - baseline.capturedNanos()),
            delta(latest.invocations(), baseline.invocations()),
            delta(latest.parallelInvocations(), baseline.parallelInvocations()),
            delta(latest.belowThresholdFallbacks(), baseline.belowThresholdFallbacks()),
            delta(latest.insufficientPartitionFallbacks(), baseline.insufficientPartitionFallbacks()),
            delta(latest.unavailableParallelismFallbacks(), baseline.unavailableParallelismFallbacks()),
            delta(latest.mainThreadFallbacks(), baseline.mainThreadFallbacks()),
            delta(latest.callerOnlyInvocations(), baseline.callerOnlyInvocations()),
            delta(latest.failedInvocations(), baseline.failedInvocations()),
            delta(latest.submissionFallbacks(), baseline.submissionFallbacks()),
            delta(latest.queueContentionFallbacks(), baseline.queueContentionFallbacks()),
            delta(latest.rejectedTasks(), baseline.rejectedTasks()),
            delta(latest.callerItems(), baseline.callerItems()),
            delta(latest.workerItems(), baseline.workerItems()),
            List.copyOf(workloads)
        );
    }

    private static CounterSample captureCounterSample(final long capturedNanos) {
        final int workloadCount = Workload.values().length;
        final long[] workloadInvocations = new long[workloadCount];
        final long[] workloadParallelInvocations = new long[workloadCount];
        final long[] workloadBelowThresholdFallbacks = new long[workloadCount];
        final long[] workloadInsufficientPartitionFallbacks = new long[workloadCount];
        final long[] workloadUnavailableParallelismFallbacks = new long[workloadCount];
        final long[] workloadMainThreadFallbacks = new long[workloadCount];
        final long[] workloadCallerOnlyInvocations = new long[workloadCount];
        final long[] workloadFailedInvocations = new long[workloadCount];
        final long[] workloadSubmissionFallbacks = new long[workloadCount];
        final long[] workloadBackpressureRuns = new long[workloadCount];
        final long[] workloadCallerItems = new long[workloadCount];
        final long[] workloadWorkerItems = new long[workloadCount];
        for (int index = 0; index < workloadCount; index++) {
            final WorkloadCounters counters = WORKLOAD_COUNTERS[index];
            workloadInvocations[index] = counters.invocations.get();
            workloadParallelInvocations[index] = counters.parallelInvocations.get();
            workloadBelowThresholdFallbacks[index] = counters.belowFeatureThresholdFallbacks.get();
            workloadInsufficientPartitionFallbacks[index] = counters.insufficientPartitionFallbacks.get();
            workloadUnavailableParallelismFallbacks[index] = counters.unavailableParallelismFallbacks.get();
            workloadMainThreadFallbacks[index] = counters.mainThreadFallbacks.get();
            workloadCallerOnlyInvocations[index] = counters.callerOnlyInvocations.get();
            workloadFailedInvocations[index] = counters.failedInvocations.get();
            workloadSubmissionFallbacks[index] = counters.submissionFallbackInvocations.get();
            workloadBackpressureRuns[index] = counters.backpressureCallerRuns.get();
            workloadCallerItems[index] = counters.callerItems.get();
            workloadWorkerItems[index] = counters.workerItems.get();
        }
        return new CounterSample(
            capturedNanos,
            invocationCount.get(),
            parallelInvocationCount.get(),
            belowFeatureThresholdFallbackCount.get(),
            insufficientPartitionFallbackCount.get(),
            unavailableParallelismFallbackCount.get(),
            fullRecomputationRequestCount.get(),
            callerOnlyInvocationCount.get(),
            failedInvocationCount.get(),
            submissionFallbackInvocationCount.get(),
            queueContentionFallbackCount.get(),
            rejectedTaskCount.get(),
            callerItemCount.get(),
            workerItemCount.get(),
            workloadInvocations,
            workloadParallelInvocations,
            workloadBelowThresholdFallbacks,
            workloadInsufficientPartitionFallbacks,
            workloadUnavailableParallelismFallbacks,
            workloadMainThreadFallbacks,
            workloadCallerOnlyInvocations,
            workloadFailedInvocations,
            workloadSubmissionFallbacks,
            workloadBackpressureRuns,
            workloadCallerItems,
            workloadWorkerItems
        );
    }

    private static long delta(final long latest, final long baseline) {
        return Math.max(0L, latest - baseline);
    }

    /** Returns a lock-free snapshot used by the diagnostic command. */
    public static Metrics metrics() {
        final ComputeThreadPoolExecutor current = executor;
        final FallbackEvent fallback = lastFallbackEvent.get();
        final long belowFeatureThresholdFallbacks = belowFeatureThresholdFallbackCount.get();
        final long insufficientPartitionFallbacks = insufficientPartitionFallbackCount.get();
        final long unavailableParallelismFallbacks = unavailableParallelismFallbackCount.get();
        final List<WorkloadMetrics> workloads = new ArrayList<>(Workload.values().length);
        for (final Workload workload : Workload.values()) {
            final WorkloadMetrics snapshot = WORKLOAD_COUNTERS[workload.ordinal()].snapshot(workload);
            if (snapshot.invocations() > 0L || workload != Workload.UNSPECIFIED) {
                workloads.add(snapshot);
            }
        }
        return new Metrics(
            workerCount,
            current == null ? 0 : current.executingTaskCount(),
            current == null ? 0 : current.queuedTaskCount(),
            current == null ? 0 : current.reservedTaskCount(),
            current == null ? 0 : current.getLargestPoolSize(),
            current == null ? 0L : current.getCompletedTaskCount(),
            invocationCount.get(),
            parallelInvocationCount.get(),
            belowFeatureThresholdFallbacks + insufficientPartitionFallbacks + unavailableParallelismFallbacks,
            belowFeatureThresholdFallbacks,
            insufficientPartitionFallbacks,
            unavailableParallelismFallbacks,
            failedInvocationCount.get(),
            backpressureCallerRunCount.get(),
            slotReservationShortageCount.get(),
            queueContentionFallbackCount.get(),
            maximumQueuedTaskCount.get(),
            rejectedTaskCount.get(),
            timedWorkerWaitCount.get(),
            callerOnlyInvocationCount.get(),
            fullRecomputationRequestCount.get(),
            submissionFallbackInvocationCount.get(),
            fallback == null ? "无" : fallback.reason().displayName(),
            fallback == null ? null : fallback.workload(),
            fallback == null ? -1L : Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fallback.capturedNanos())),
            callerRangeCount.get(),
            workerRangeCount.get(),
            callerItemCount.get(),
            callerHelpRangeCount.get(),
            workerItemCount.get(),
            (int)Math.min(Integer.MAX_VALUE, maximumConcurrentWorkerCount.get()),
            adaptiveAdmissionCount.get(),
            adaptivePartitionInvocationCount.get(),
            adaptivePartitionCount.get(),
            workerBatchCount.get(),
            List.copyOf(workloads)
        );
    }

    /** Stops all LeavesX computation workers during normal server shutdown. */
    public static synchronized void shutdown() {
        final ComputeThreadPoolExecutor current = executor;
        executor = null;
        if (current != null) {
            for (final Runnable pending : current.shutdownNow()) {
                if (pending instanceof RangeBatchRunnable range) {
                    range.cancel();
                } else if (pending instanceof SmallRangeRunnable small) {
                    small.cancel();
                } else if (pending instanceof SnapshotRunnable snapshot) {
                    snapshot.cancel();
                } else if (pending instanceof SnapshotTask snapshot) {
                    snapshot.cancel(new CancellationException("LeavesX snapshot task cancelled before execution"));
                }
            }
        }
    }

    @FunctionalInterface
    public interface RangeTask {
        void run(int taskIndex, int startInclusive, int endExclusive);
    }

    /** Stable diagnostic categories for the shared pure-computation pool. */
    public enum Workload {
        UNSPECIFIED("其他计算"),
        AI_TARGET_SORT("AI 目标排序"),
        AI_TARGET_MERGE("AI 排序归并"),
        AI_TARGET_SELECT("AI 目标选择"),
        ENTITY_ACTIVATION("实体激活范围"),
        ENTITY_TRACKING("实体追踪计算"),
        MOB_SPAWN_COUNTING("刷怪统计"),
        MOB_COLLISION("生物碰撞数值计算"),
        WORLDGEN_NOISE("区块噪声采样", WorkloadPriority.NORMAL),
        WORLDGEN_FEATURE("区块特征计算", WorkloadPriority.LOW);

        private final String displayName;
        private final WorkloadPriority priority;

        Workload(final String displayName) {
            this(displayName, WorkloadPriority.HIGH);
        }

        Workload(final String displayName, final WorkloadPriority priority) {
            this.displayName = displayName;
            this.priority = priority;
        }

        public String displayName() {
            return this.displayName;
        }

        public WorkloadPriority priority() {
            return this.priority;
        }
    }

    /** 调度优先级只影响准入，不改变同一所有者任务的执行顺序。 */
    public enum WorkloadPriority {
        HIGH,
        NORMAL,
        LOW
    }

    /** Immutable diagnostic view of the shared pure-computation pool. */
    public record Metrics(
        int workers,
        int activeWorkers,
        int queuedTasks,
        int reservedTasks,
        int largestPoolSize,
        long completedTasks,
        long invocations,
        long parallelInvocations,
        long synchronousFallbacks,
        long belowFeatureThresholdFallbacks,
        long insufficientPartitionFallbacks,
        long unavailableParallelismFallbacks,
        long failedInvocations,
        long backpressureCallerRuns,
        long slotReservationShortages,
        long queueContentionFallbacks,
        long maximumQueuedTasks,
        long rejectedTasks,
        long timedWorkerWaits,
        long callerOnlyInvocations,
        long fullRecomputationRequests,
        long submissionFallbackInvocations,
        String lastFallbackReason,
        Workload lastFallbackWorkload,
        long lastFallbackAgeMillis,
        long callerRanges,
        long workerRanges,
        long callerItems,
        long callerHelpRanges,
        long workerItems,
        int maximumConcurrentWorkers,
        long adaptiveAdmissions,
        long adaptivePartitionInvocations,
        long adaptivePartitions,
        long workerBatches,
        List<WorkloadMetrics> workloads
    ) {
    }

    /** Immutable counters for one compute workload. */
    public record WorkloadMetrics(
        Workload workload,
        long invocations,
        long parallelInvocations,
        long synchronousFallbacks,
        long belowFeatureThresholdFallbacks,
        long insufficientPartitionFallbacks,
        long unavailableParallelismFallbacks,
        long failedInvocations,
        long backpressureCallerRuns,
        long mainThreadFallbacks,
        long callerOnlyInvocations,
        long submissionFallbackInvocations,
        long totalItems,
        long maximumItems,
        long callerRanges,
        long workerRanges,
        long callerItems,
        long workerItems,
        int maximumConcurrentWorkers,
        long workerBatches,
        long callerNanos,
        long circuitRemainingMillis
    ) {
        /** 每次调用在调用线程上的平均耗时，单位毫秒。 */
        public double averageCallerMillis() {
            return this.invocations == 0L ? 0.0D : this.callerNanos / (double)this.invocations / 1_000_000.0D;
        }
    }

    /** Immutable deltas for one recent diagnostic window. */
    public record WindowMetrics(
        long elapsedNanos,
        long invocations,
        long parallelInvocations,
        long belowThresholdFallbacks,
        long insufficientPartitionFallbacks,
        long unavailableParallelismFallbacks,
        long mainThreadFallbacks,
        long callerOnlyInvocations,
        long failedInvocations,
        long submissionFallbacks,
        long queueContentionFallbacks,
        long rejectedTasks,
        long callerItems,
        long workerItems,
        List<WorkloadWindowMetrics> workloads
    ) {
    }

    /** Per-workload deltas for the recent diagnostic window. */
    public record WorkloadWindowMetrics(
        Workload workload,
        long invocations,
        long parallelInvocations,
        long belowThresholdFallbacks,
        long insufficientPartitionFallbacks,
        long unavailableParallelismFallbacks,
        long mainThreadFallbacks,
        long callerOnlyInvocations,
        long failedInvocations,
        long submissionFallbacks,
        long backpressureRuns,
        long callerItems,
        long workerItems
    ) {
    }

    public enum FallbackReason {
        POOL_UNAVAILABLE("线程池不可用或工作线程数不足"),
        BELOW_THRESHOLD("任务规模低于并行阈值"),
        INSUFFICIENT_PARTITIONS("可用分区不足"),
        POOL_BUSY_CALLER_ONLY("线程池繁忙，完整任务由主线程执行"),
        QUEUE_CONTENTION_CALLER_ONLY("低优先级任务让路给实时实体计算"),
        SUBMISSION_REJECTED("工作任务提交被拒绝"),
        SUBMISSION_FAILED("工作任务提交异常"),
        COMPUTATION_FAILED("工作线程计算异常，已请求完整主线程重算"),
        NESTED_COMPUTATION_FAILED("嵌套计算异常，已请求完整主线程重算"),
        CIRCUIT_OPEN("连续异常后暂时关闭该类并行计算");

        private final String displayName;

        FallbackReason(final String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return this.displayName;
        }
    }

    private record FallbackEvent(long capturedNanos, Workload workload, FallbackReason reason, int inputSize) {
    }

    private record CounterSample(
        long capturedNanos,
        long invocations,
        long parallelInvocations,
        long belowThresholdFallbacks,
        long insufficientPartitionFallbacks,
        long unavailableParallelismFallbacks,
        long mainThreadFallbacks,
        long callerOnlyInvocations,
        long failedInvocations,
        long submissionFallbacks,
        long queueContentionFallbacks,
        long rejectedTasks,
        long callerItems,
        long workerItems,
        long[] workloadInvocations,
        long[] workloadParallelInvocations,
        long[] workloadBelowThresholdFallbacks,
        long[] workloadInsufficientPartitionFallbacks,
        long[] workloadUnavailableParallelismFallbacks,
        long[] workloadMainThreadFallbacks,
        long[] workloadCallerOnlyInvocations,
        long[] workloadFailedInvocations,
        long[] workloadSubmissionFallbacks,
        long[] workloadBackpressureRuns,
        long[] workloadCallerItems,
        long[] workloadWorkerItems
    ) {
    }

    private static WorkloadCounters[] createWorkloadCounters() {
        final WorkloadCounters[] counters = new WorkloadCounters[Workload.values().length];
        for (int index = 0; index < counters.length; index++) {
            counters[index] = new WorkloadCounters();
        }
        return counters;
    }

    private static final class WorkloadCounters {

        private final AtomicLong invocations = new AtomicLong();
        private final AtomicLong parallelInvocations = new AtomicLong();
        private final AtomicLong belowFeatureThresholdFallbacks = new AtomicLong();
        private final AtomicLong insufficientPartitionFallbacks = new AtomicLong();
        private final AtomicLong unavailableParallelismFallbacks = new AtomicLong();
        private final AtomicLong failedInvocations = new AtomicLong();
        private final AtomicLong backpressureCallerRuns = new AtomicLong();
        private final AtomicLong mainThreadFallbacks = new AtomicLong();
        private final AtomicLong callerOnlyInvocations = new AtomicLong();
        private final AtomicLong submissionFallbackInvocations = new AtomicLong();
        private final AtomicLong totalItems = new AtomicLong();
        private final AtomicLong maximumItems = new AtomicLong();
        private final AtomicLong callerRanges = new AtomicLong();
        private final AtomicLong workerRanges = new AtomicLong();
        private final AtomicLong callerItems = new AtomicLong();
        private final AtomicLong workerItems = new AtomicLong();
        private final AtomicLong maximumConcurrentWorkers = new AtomicLong();
        private final AtomicLong workerBatches = new AtomicLong();
        private final AtomicLong callerNanos = new AtomicLong();
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicLong circuitOpenUntil = new AtomicLong();

        /** 连续异常会暂时关闭本类并行；调用方仍走自己的完整同步路径。 */
        private boolean circuitOpen(final long now) {
            if (!failureProtection.enabled()) return false;
            while (true) {
                final long until = this.circuitOpenUntil.get();
                if (until == 0L) return false;
                if (now - until < 0L) return true;
                // 到期检查可能与另一次失败同时发生；CAS 失败后重读，不能绕过新开的暂停窗口。
                if (this.circuitOpenUntil.compareAndSet(until, 0L)) return false;
            }
        }

        private void recordFailure(final long now) {
            this.failedInvocations.incrementAndGet();
            final var settings = failureProtection;
            if (!settings.enabled()) {
                this.consecutiveFailures.set(0);
                return;
            }
            if (this.consecutiveFailures.incrementAndGet() >= settings.failureThreshold()) {
                this.consecutiveFailures.set(0);
                this.circuitOpenUntil.set(now + TimeUnit.SECONDS.toNanos(settings.cooldownSeconds()));
            }
        }

        private long circuitRemainingMillis(final long now) {
            if (!failureProtection.enabled()) return 0L;
            final long until = this.circuitOpenUntil.get();
            if (until == 0L || now - until >= 0L) return 0L;
            // 不足一毫秒也显示为暂停中；诊断只读，不改变准入状态。
            return 1L + (until - now - 1L) / 1_000_000L;
        }

        private void recordSuccess() {
            this.parallelInvocations.incrementAndGet();
            this.consecutiveFailures.set(0);
        }

        private void recordInvocation(final int size) {
            this.invocations.incrementAndGet();
            final int boundedSize = Math.max(0, size);
            this.totalItems.addAndGet(boundedSize);
            long currentMaximum = this.maximumItems.get();
            while (boundedSize > currentMaximum
                && !this.maximumItems.compareAndSet(currentMaximum, boundedSize)) {
                currentMaximum = this.maximumItems.get();
            }
        }

        private void reset() {
            this.invocations.set(0L);
            this.parallelInvocations.set(0L);
            this.belowFeatureThresholdFallbacks.set(0L);
            this.insufficientPartitionFallbacks.set(0L);
            this.unavailableParallelismFallbacks.set(0L);
            this.failedInvocations.set(0L);
            this.backpressureCallerRuns.set(0L);
            this.mainThreadFallbacks.set(0L);
            this.callerOnlyInvocations.set(0L);
            this.submissionFallbackInvocations.set(0L);
            this.totalItems.set(0L);
            this.maximumItems.set(0L);
            this.callerRanges.set(0L);
            this.workerRanges.set(0L);
            this.callerItems.set(0L);
            this.workerItems.set(0L);
            this.maximumConcurrentWorkers.set(0L);
            this.workerBatches.set(0L);
            this.callerNanos.set(0L);
            this.consecutiveFailures.set(0);
            this.circuitOpenUntil.set(0L);
        }

        private WorkloadMetrics snapshot(final Workload workload) {
            final long belowThreshold = this.belowFeatureThresholdFallbacks.get();
            final long insufficientWork = this.insufficientPartitionFallbacks.get();
            final long unavailable = this.unavailableParallelismFallbacks.get();
            return new WorkloadMetrics(
                workload,
                this.invocations.get(),
                this.parallelInvocations.get(),
                belowThreshold + insufficientWork + unavailable,
                belowThreshold,
                insufficientWork,
                unavailable,
                this.failedInvocations.get(),
                this.backpressureCallerRuns.get(),
                this.mainThreadFallbacks.get(),
                this.callerOnlyInvocations.get(),
                this.submissionFallbackInvocations.get(),
                this.totalItems.get(),
                this.maximumItems.get(),
                this.callerRanges.get(),
                this.workerRanges.get(),
                this.callerItems.get(),
                this.workerItems.get(),
                (int)Math.min(Integer.MAX_VALUE, this.maximumConcurrentWorkers.get()),
                this.workerBatches.get(),
                this.callerNanos.get(),
                this.circuitRemainingMillis(System.nanoTime())
            );
        }

        private void recordLoad(
            final long callerRangeCount,
            final long workerRangeCount,
            final long callerItemCount,
            final long workerItemCount,
            final int maximumConcurrentWorkerCount,
            final long workerBatchCount
        ) {
            this.callerRanges.addAndGet(callerRangeCount);
            this.workerRanges.addAndGet(workerRangeCount);
            this.callerItems.addAndGet(callerItemCount);
            this.workerItems.addAndGet(workerItemCount);
            this.workerBatches.addAndGet(workerBatchCount);
            long current = this.maximumConcurrentWorkers.get();
            while (maximumConcurrentWorkerCount > current
                && !this.maximumConcurrentWorkers.compareAndSet(current, maximumConcurrentWorkerCount)) {
                current = this.maximumConcurrentWorkers.get();
            }
        }
    }

    /** Coordinates one invocation without allocating a Future/list pair for every worker partition. */
    private static final class RangeInvocation {

        private final RangeTask task;
        private final WorkloadCounters workloadCounters;
        private final CountDownLatch workersDone;
        private final CountDownLatch workerBatchesDone;
        private final AtomicInteger activeWorkers = new AtomicInteger();
        private final AtomicLong workerRanges = new AtomicLong();
        private final AtomicLong workerItems = new AtomicLong();
        private final AtomicLong workerBatches = new AtomicLong();
        private long callerRanges;
        private long callerItems;
        private long callerHelpRanges;
        private final AtomicInteger maximumConcurrentWorkers = new AtomicInteger();
        private volatile Throwable failure;

        private RangeInvocation(
            final RangeTask task,
            final WorkloadCounters workloadCounters,
            final int workerRangeCount,
            final int workerBatchCount
        ) {
            this.task = task;
            this.workloadCounters = workloadCounters;
            this.workersDone = new CountDownLatch(workerRangeCount);
            this.workerBatchesDone = new CountDownLatch(workerBatchCount);
        }

        /**
         * Executes all ranges claimed by one worker submission while publishing worker metrics once.
         *
         * <p>The callback is still invoked once per deterministic range and every range decrements the latch
         * independently. Only the diagnostic counters are aggregated locally, avoiding several atomic updates per
         * range on large snapshots.</p>
         */
        private void runWorkerBatch(
            final int initialTaskIndex,
            final AtomicInteger nextTask,
            final int rangeSize,
            final int taskCount,
            final int inputSize,
            final int rangesPerClaim
        ) {
            final int active = (int)this.activeWorkers.incrementAndGet();
            long ranges = 0L;
            long items = 0L;
            this.maximumConcurrentWorkers.accumulateAndGet(active, Math::max);
            this.workerBatches.incrementAndGet();
            try {
                int taskIndex = initialTaskIndex;
                final int initialStart = taskIndex * rangeSize;
                ranges++;
                items += Math.max(0, Math.min(inputSize, initialStart + rangeSize) - initialStart);
                try {
                    this.task.run(
                        taskIndex,
                        initialStart,
                        Math.min(inputSize, initialStart + rangeSize)
                    );
                } catch (final Throwable computationFailure) {
                    this.recordFailure(computationFailure);
                } finally {
                    this.workersDone.countDown();
                }

                // Initial ranges are reserved one-per-submission before workers start. Only claim from the shared
                // cursor after that reservation; otherwise a fast worker could execute another worker's first range.
                int claimWidth = claimWidthFor(taskCount - 1 - nextTask.get(), rangesPerClaim);
                taskIndex = nextTask.getAndAdd(claimWidth);
                while (taskIndex < taskCount - 1) {
                    final RangeClaim claim = claimRange(taskIndex, claimWidth, taskCount - 1);
                    while (taskIndex < claim.endExclusive()) {
                        final int start = taskIndex * rangeSize;
                        final int end = Math.min(inputSize, start + rangeSize);
                        ranges++;
                        items += Math.max(0, end - start);
                        try {
                            this.task.run(taskIndex, start, end);
                        } catch (final Throwable computationFailure) {
                            this.recordFailure(computationFailure);
                        } finally {
                            this.workersDone.countDown();
                        }
                        taskIndex++;
                    }
                    final int remainingRanges = Math.max(0, taskCount - 1 - nextTask.get());
                    claimWidth = claimWidthFor(remainingRanges, rangesPerClaim);
                    taskIndex = nextTask.getAndAdd(claimWidth);
                }
            } catch (final Throwable unexpectedFailure) {
                // A failure outside the per-range callback guards must not strand the latch. Claim every range that
                // no worker has started yet; workers that already claimed a range still publish their own completion.
                this.recordFailure(unexpectedFailure);
                this.cancelRemainingWorkerRanges(nextTask, taskCount - 1, rangesPerClaim);
            } finally {
                this.activeWorkers.decrementAndGet();
                this.workerRanges.addAndGet(ranges);
                this.workerItems.addAndGet(items);
                this.workerBatchesDone.countDown();
            }
        }

        private void cancelRemainingWorkerRanges(
            final AtomicInteger nextTask,
            final int workerRangeLimit,
            final int rangesPerClaim
        ) {
            while (true) {
                final int remaining = workerRangeLimit - nextTask.get();
                if (remaining <= 0) {
                    return;
                }
                final int claimWidth = claimWidthFor(remaining, rangesPerClaim);
                final int taskIndex = nextTask.getAndAdd(claimWidth);
                if (taskIndex >= workerRangeLimit) {
                    return;
                }
                final int claimEnd = Math.min(workerRangeLimit, taskIndex + claimWidth);
                for (int claimedTask = taskIndex; claimedTask < claimEnd; claimedTask++) {
                    this.workersDone.countDown();
                }
            }
        }

        private void runInlineWorker(final int taskIndex, final int startInclusive, final int endExclusive) {
            final int active = (int)this.activeWorkers.incrementAndGet();
            final int itemCount = endExclusive - startInclusive;
            this.workerRanges.incrementAndGet();
            this.workerItems.addAndGet(Math.max(0, itemCount));
            this.maximumConcurrentWorkers.accumulateAndGet(active, Math::max);
            try {
                this.task.run(taskIndex, startInclusive, endExclusive);
            } catch (final Throwable computationFailure) {
                this.recordFailure(computationFailure);
            } finally {
                this.activeWorkers.decrementAndGet();
            }
        }

        private void runOnCaller(
            final int taskIndex,
            final int startInclusive,
            final int endExclusive,
            final boolean backpressure
        ) {
            if (backpressure) {
                backpressureCallerRunCount.incrementAndGet();
                this.workloadCounters.backpressureCallerRuns.incrementAndGet();
            }
            final int itemCount = endExclusive - startInclusive;
            this.callerRanges++;
            this.callerItems += Math.max(0, itemCount);
            try {
                this.task.run(taskIndex, startInclusive, endExclusive);
            } catch (final Throwable computationFailure) {
                this.recordFailure(computationFailure);
            } finally {
                if (backpressure) {
                    this.workersDone.countDown();
                }
            }
        }

        /** Executes one worker-owned range on the calling thread without classifying it as queue backpressure. */
        private void runHelpOnCaller(
            final int taskIndex,
            final int startInclusive,
            final int endExclusive
        ) {
            final int itemCount = endExclusive - startInclusive;
            this.callerRanges++;
            this.callerItems += Math.max(0, itemCount);
            this.callerHelpRanges++;
            try {
                this.task.run(taskIndex, startInclusive, endExclusive);
            } catch (final Throwable computationFailure) {
                this.recordFailure(computationFailure);
            } finally {
                this.workersDone.countDown();
            }
        }

        /** Claims every range that workers have not claimed and executes it on the calling thread. */
        private void runRemainingOnCaller(
            final AtomicInteger nextTask,
            final int rangeSize,
            final int workerTaskCount,
            final int inputSize,
            final boolean backpressure
        ) {
            while (true) {
                final int taskIndex = nextTask.getAndIncrement();
                if (taskIndex >= workerTaskCount) {
                    return;
                }
                final int start = taskIndex * rangeSize;
                final int end = Math.min(inputSize, start + rangeSize);
                this.runOnCaller(taskIndex, start, end, backpressure);
            }
        }

        /** Runs every deterministic partition on the calling thread while the worker pool is fully occupied. */
        private void runAllOnCaller(final int rangeSize, final int taskCount, final int inputSize) {
            for (int taskIndex = 0; taskIndex < taskCount; taskIndex++) {
                final int start = taskIndex * rangeSize;
                this.runOnCaller(taskIndex, start, Math.min(inputSize, start + rangeSize), true);
            }
        }

        /** Executes guaranteed initial ranges whose worker submissions were rejected. */
        private void runInitialRangesOnCaller(
            final int startTask,
            final int endTask,
            final int rangeSize,
            final int inputSize,
            final boolean backpressure
        ) {
            for (int taskIndex = startTask; taskIndex < endTask; taskIndex++) {
                final int start = taskIndex * rangeSize;
                this.runOnCaller(taskIndex, start, Math.min(inputSize, start + rangeSize), backpressure);
            }
        }

        private void awaitWorkers() {
            boolean interrupted = false;
            while (true) {
                try {
                    org.leavesx.leavesx.diagnostics.OwnerThreadWaits.await(
                        org.leavesx.leavesx.diagnostics.OwnerThreadWaits.Site.COMPUTE_RANGES, this.workersDone, 0L);
                    break;
                } catch (final InterruptedException interruption) {
                    interrupted = true;
                    this.recordFailure(interruption);
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Waits for worker ranges while claiming unstarted overflow ranges on the caller.
         *
         * <p>Workers and the caller use the same atomic cursor, so each range remains owned by exactly one thread.
         * The caller only helps ranges that were not part of the initial one-per-worker reservation.</p>
         */
        private void awaitWorkersWithHelping(
            final AtomicInteger nextTask,
            final int rangeSize,
            final int workerRangeLimit,
            final int inputSize,
            final int rangesPerClaim
        ) {
            boolean interrupted = false;
            int idleSpins = 0;
            while (this.workersDone.getCount() > 0L) {
                if (this.workerBatchesDone.getCount() == 0L) {
                    // An unexpected worker failure may leave an already-claimed range unaccounted for.
                    // Only abandon the range latch after every submitted batch has published completion: no
                    // worker can then mutate the result while the caller performs a complete recomputation.
                    if (this.workersDone.getCount() > 0L) {
                        this.recordFailure(new IllegalStateException("Compute batches finished with incomplete ranges"));
                    }
                    break;
                }
                // Avoid an atomic update when all overflow ranges have already been claimed by workers.
                final int availableTask = nextTask.get();
                if (availableTask >= workerRangeLimit) {
                    if (idleSpins++ < WAIT_SPIN_LIMIT) {
                        Thread.onSpinWait();
                        continue;
                    }
                    idleSpins = 0;
                    try {
                        timedWorkerWaitCount.incrementAndGet();
                        org.leavesx.leavesx.diagnostics.OwnerThreadWaits.await(
                            org.leavesx.leavesx.diagnostics.OwnerThreadWaits.Site.COMPUTE_RANGES, this.workersDone, WAIT_PARK_NANOS);
                    } catch (final InterruptedException interruption) {
                        interrupted = true;
                        this.recordFailure(interruption);
                    }
                    continue;
                }
                final int helpClaimWidth = claimWidthFor(workerRangeLimit - availableTask, rangesPerClaim);
                final int taskIndex = nextTask.getAndAdd(helpClaimWidth);
                if (taskIndex < workerRangeLimit) {
                    idleSpins = 0;
                    final int claimEnd = Math.min(workerRangeLimit, taskIndex + helpClaimWidth);
                    for (int claimedTask = taskIndex; claimedTask < claimEnd; claimedTask++) {
                        final int start = claimedTask * rangeSize;
                        this.runHelpOnCaller(claimedTask, start, Math.min(inputSize, start + rangeSize));
                    }
                    continue;
                }
                if (idleSpins++ < WAIT_SPIN_LIMIT) {
                    Thread.onSpinWait();
                    continue;
                }
                idleSpins = 0;
                try {
                    timedWorkerWaitCount.incrementAndGet();
                    org.leavesx.leavesx.diagnostics.OwnerThreadWaits.await(
                        org.leavesx.leavesx.diagnostics.OwnerThreadWaits.Site.COMPUTE_RANGES, this.workersDone, WAIT_PARK_NANOS);
                } catch (final InterruptedException interruption) {
                    interrupted = true;
                    this.recordFailure(interruption);
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void cancelWorker() {
            this.recordFailure(new CancellationException("LeavesX compute range cancelled"));
            this.workersDone.countDown();
        }

        /** Marks a queued batch as handled when shutdown or rejection prevents a worker from starting it. */
        private void cancelWorkerBatch() {
            this.workerBatchesDone.countDown();
        }

        private void cancelWorkerBatches(final int count) {
            for (int index = 0; index < count; index++) {
                this.cancelWorkerBatch();
            }
        }

        /** Publishes this invocation's counters once, keeping atomics out of each hot range. */
        private void recordLoad() {
            this.awaitWorkerBatches();
            final long workerRangeTotal = this.workerRanges.get();
            final long workerItemTotal = this.workerItems.get();
            callerRangeCount.addAndGet(this.callerRanges);
            callerHelpRangeCount.addAndGet(this.callerHelpRanges);
            workerRangeCount.addAndGet(workerRangeTotal);
            callerItemCount.addAndGet(this.callerItems);
            workerItemCount.addAndGet(workerItemTotal);
            final long workerBatchTotal = this.workerBatches.get();
            workerBatchCount.addAndGet(workerBatchTotal);
            final int maximumWorkers = this.maximumConcurrentWorkers.get();
            recordMaximumConcurrentWorkers(maximumWorkers);
            this.workloadCounters.recordLoad(
                this.callerRanges,
                workerRangeTotal,
                this.callerItems,
                workerItemTotal,
                maximumWorkers,
                workerBatchTotal
            );
        }

        /** Waits until worker-local counters have been published after their final logical range completes. */
        private void awaitWorkerBatches() {
            boolean interrupted = false;
            while (true) {
                try {
                    org.leavesx.leavesx.diagnostics.OwnerThreadWaits.await(
                        org.leavesx.leavesx.diagnostics.OwnerThreadWaits.Site.COMPUTE_BATCHES, this.workerBatchesDone, 0L);
                    break;
                } catch (final InterruptedException interruption) {
                    interrupted = true;
                    this.recordFailure(interruption);
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private synchronized void recordFailure(final Throwable computationFailure) {
            if (this.failure == null) {
                this.failure = computationFailure;
            }
        }
    }

    /** Bounded pool with lock-free active-task observation for hot-path admission decisions. */
    private static final class ComputeThreadPoolExecutor extends ThreadPoolExecutor {

        private final AtomicInteger executingTasks = new AtomicInteger();
        /** Number of tasks accepted by execute but not handed to beforeExecute yet. */
        private final AtomicInteger queuedTasks = new AtomicInteger();
        /** Reservations made by concurrent invocations before their queue entries are handed to workers. */
        private final AtomicInteger reservedTasks = new AtomicInteger();

        private ComputeThreadPoolExecutor(final int workers) {
            super(
                workers,
                workers,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_TASKS),
                new ThreadFactoryBuilder()
                    .setDaemon(true)
                    .setNameFormat("LeavesX Compute Worker - %d")
                    .setUncaughtExceptionHandler((thread, throwable) ->
                        LOGGER.error("Uncaught exception in {}", thread.getName(), throwable))
                    .build(),
                new AbortPolicy()
            );
        }

        @Override
        public void execute(final Runnable command) {
            final int acceptedDepth = this.queuedTasks.incrementAndGet();
            recordMaximum(maximumQueuedTaskCount, acceptedDepth);
            try {
                super.execute(command);
            } catch (final RuntimeException | Error failure) {
                // AbortPolicy and shutdown races must not leave adaptive admission permanently disabled.
                this.queuedTasks.decrementAndGet();
                rejectedTaskCount.incrementAndGet();
                throw failure;
            }
        }

        @Override
        protected void beforeExecute(final Thread thread, final Runnable task) {
            super.beforeExecute(thread, task);
            // Publish the execution state before removing the queue entry. This avoids a transient false idle slot
            // that could let a concurrent invocation over-admit another batch between the two counter updates.
            this.executingTasks.incrementAndGet();
            this.queuedTasks.decrementAndGet();
            COMPUTE_WORKER_CONTEXT.set(Boolean.TRUE);
        }

        @Override
        protected void afterExecute(final Runnable task, final Throwable failure) {
            try {
                COMPUTE_WORKER_CONTEXT.remove();
                this.executingTasks.decrementAndGet();
            } finally {
                try {
                    super.afterExecute(task, failure);
                } finally {
                    if (task instanceof SmallRangeRunnable small) {
                        // 先归还计算名额，再发布小任务完成；下一次调用不能误判刚完成的线程仍繁忙。
                        small.complete();
                    }
                }
            }
        }

        private int executingTaskCount() {
            return this.executingTasks.get();
        }

        private int queuedTaskCount() {
            return Math.max(0, this.queuedTasks.get());
        }

        private int reservedTaskCount() {
            return Math.max(0, this.reservedTasks.get());
        }

        /** Returns worker slots that are not executing, queued, or reserved by another invocation. */
        private int availableWorkerSlotCount() {
            return Math.max(
                0,
                this.getCorePoolSize() - this.executingTaskCount() - this.queuedTaskCount() - this.reservedTasks.get()
            );
        }

        /** Atomically reserves worker slots for one invocation, preventing concurrent callers from over-admitting. */
        private int reserveWorkerSlots(final int requested) {
            if (requested <= 0) {
                return 0;
            }
            while (true) {
                final int currentReservations = this.reservedTasks.get();
                final int available = Math.max(
                    0,
                    this.getCorePoolSize() - this.executingTaskCount() - this.queuedTaskCount() - currentReservations
                );
                final int granted = Math.min(requested, available);
                if (granted == 0) {
                    return 0;
                }
                if (this.reservedTasks.compareAndSet(currentReservations, currentReservations + granted)) {
                    return granted;
                }
            }
        }

        private void releaseWorkerSlotReservation() {
            this.reservedTasks.decrementAndGet();
        }

        private void releaseWorkerSlotReservations(final int count) {
            if (count > 0) {
                this.reservedTasks.addAndGet(-count);
            }
        }

        /** Submits a previously reserved task and releases its reservation even when shutdown races the submit. */
        private void executeReserved(final Runnable command) {
            try {
                this.execute(command);
            } finally {
                this.releaseWorkerSlotReservation();
            }
        }
    }

    /** 单范围任务只有一次结果发布；取消排队任务不会取消正在写结果的任务。 */
    private static final class SmallRangeRunnable implements Runnable {
        private final RangeTask task;
        private final int size;
        private final CountDownLatch completed = new CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile Throwable failure;
        private boolean executed;

        private SmallRangeRunnable(final RangeTask task, final int size) {
            this.task = task;
            this.size = size;
        }

        @Override
        public void run() {
            if (!this.claimed.compareAndSet(false, true)) return;
            this.executed = true;
            try {
                this.task.run(0, 0, this.size);
            } catch (final Throwable computationFailure) {
                this.recordFailure(computationFailure);
            }
            // 完成通知由线程池在 afterExecute 中发布，确保数值写入和执行状态都已结束。
        }

        private void complete() {
            this.completed.countDown();
        }

        private void cancel() {
            if (this.claimed.compareAndSet(false, true)) {
                this.recordFailure(new CancellationException("LeavesX small computation cancelled before execution"));
                this.completed.countDown();
            }
        }

        private void awaitCompletion() {
            boolean interrupted = false;
            for (int spin = 0; spin < WAIT_SPIN_LIMIT && this.completed.getCount() != 0L; spin++) {
                Thread.onSpinWait();
            }
            while (this.completed.getCount() != 0L) {
                try {
                    timedWorkerWaitCount.incrementAndGet();
                    org.leavesx.leavesx.diagnostics.OwnerThreadWaits.await(
                        org.leavesx.leavesx.diagnostics.OwnerThreadWaits.Site.COMPUTE_RANGES, this.completed, 0L);
                } catch (final InterruptedException interruption) {
                    interrupted = true;
                    this.recordFailure(interruption);
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private synchronized void recordFailure(final Throwable cause) {
            if (this.failure == null) this.failure = cause;
        }
    }

    /** 内部任务自行保证领取、取消和完成发布只发生一次。 */
    interface SnapshotTask extends Runnable {
        void cancel(Throwable failure);
    }

    /** 排队取消与开始执行只允许一方胜出，防止许可泄漏或重复完成回调。 */
    private static final class SnapshotRunnable implements Runnable {
        private final Runnable task;
        private final Consumer<Throwable> cancelled;
        private final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();

        private SnapshotRunnable(final Runnable task, final Consumer<Throwable> cancelled) {
            this.task = task;
            this.cancelled = cancelled;
        }

        @Override
        public void run() {
            if (this.claimed.compareAndSet(false, true)) this.task.run();
        }

        private void cancel() {
            if (this.claimed.compareAndSet(false, true)) {
                this.cancelled.accept(new CancellationException("LeavesX snapshot task cancelled before execution"));
            }
        }
    }

    /** Runs multiple contiguous ranges from one queue entry, reducing queue/lock pressure for large snapshots. */
    private static final class RangeBatchRunnable implements Runnable {

        private final RangeInvocation invocation;
        private final int initialTaskIndex;
        private final AtomicInteger nextTask;
        private final int rangeSize;
        private final int taskCount;
        private final int inputSize;
        private final int rangesPerClaim;

        private RangeBatchRunnable(
            final RangeInvocation invocation,
            final int initialTaskIndex,
            final AtomicInteger nextTask,
            final int rangeSize,
            final int taskCount,
            final int inputSize,
            final int rangesPerClaim
        ) {
            this.invocation = invocation;
            this.initialTaskIndex = initialTaskIndex;
            this.nextTask = nextTask;
            this.rangeSize = rangeSize;
            this.taskCount = taskCount;
            this.inputSize = inputSize;
            this.rangesPerClaim = rangesPerClaim;
        }

        @Override
        public void run() {
            this.invocation.runWorkerBatch(
                this.initialTaskIndex,
                this.nextTask,
                this.rangeSize,
                this.taskCount,
                this.inputSize,
                this.rangesPerClaim
            );
        }

        /** Counts every unclaimed worker range down when a queued batch is cancelled during shutdown. */
        private void cancel() {
            try {
                this.invocation.cancelWorker();
                // Cancellation must reserve the same contiguous claim width as active workers. Mixing getAndIncrement
                // with a worker's getAndAdd can steal the second range of a block already reserved by that worker.
                while (true) {
                    final int taskIndex = this.nextTask.getAndAdd(this.rangesPerClaim);
                    if (taskIndex >= this.taskCount - 1) {
                        return;
                    }
                    final int claimEnd = Math.min(this.taskCount - 1, taskIndex + this.rangesPerClaim);
                    for (int claimedTask = taskIndex; claimedTask < claimEnd; claimedTask++) {
                        this.invocation.cancelWorker();
                    }
                }
            } finally {
                this.invocation.cancelWorkerBatch();
            }
        }
    }
}
