package org.leavesx.leavesx.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.leavesx.leavesx.diagnostics.OwnerThreadWaits;

/**
 * 边采集边提交独立的数值片段；提交不等待上一片段，结束后才允许采用结果。
 * 调用者只能提交不可变输入和互不重叠的输出；异常时须在 finish 返回后完整重算。
 */
public final class PreviousSnapshotBatch implements AutoCloseable {
    private static final int MAX_PENDING_TASKS = 64;
    private final Thread owner = Thread.currentThread();
    private final LeavesXComputeExecutor.Workload workload;
    private final List<Task> pending = new ArrayList<>();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicInteger maximumWorkers = new AtomicInteger();
    private int items;
    private long callerItems;
    private long workerItems;
    private long callerTasks;
    private long workerTasks;
    private long callerNanos;
    private boolean successful = true;
    private Throwable failure;
    private boolean finished;

    public PreviousSnapshotBatch(final LeavesXComputeExecutor.Workload workload) {
        this.workload = Objects.requireNonNull(workload, "workload");
    }

    /** 控制每批任务数，避免密集场景把数值计算拆成数百次跨线程唤醒。 */
    public static int partitionSize(final long items, final int minimum) {
        final int partitions = Math.max(1, LeavesXComputeExecutor.workerCount()) * 2;
        return (int)Math.min(Integer.MAX_VALUE, Math.max(minimum, (items + partitions - 1) / partitions));
    }

    /** 没有空闲线程时调用者完成这一整个片段，同时让其他片段继续运行。 */
    public void submit(final int size, final Runnable computation) {
        this.checkOwner();
        if (this.finished) throw new IllegalStateException("Snapshot batch already finished");
        if (size <= 0) throw new IllegalArgumentException("Snapshot size must be positive");
        Objects.requireNonNull(computation, "computation");
        final long started = System.nanoTime();
        try {
            if (this.pending.size() == MAX_PENDING_TASKS) {
                this.reapCompleted();
                if (this.pending.size() == MAX_PENDING_TASKS) this.drain();
            }
            this.items = Math.addExact(this.items, size);
            final Task task = new Task(size, computation, this.activeWorkers, this.maximumWorkers);
            this.pending.add(task);
            try {
                if (!LeavesXComputeExecutor.tryExecuteSnapshot(this.workload, task::runOnWorker, task::cancel)) {
                    task.run(false);
                }
            } catch (final Throwable submissionFailure) {
                // 提交本身异常也必须结束未领取任务，不能在 close 等待一个永远不会执行的片段。
                this.successful = false;
                if (this.failure == null) this.failure = submissionFailure;
                task.cancel(submissionFailure);
            }
        } finally {
            this.callerNanos += System.nanoTime() - started;
        }
    }

    /** 扩容输出缓冲区前等待已发布片段；失败状态保留到整批结束。 */
    public void flush() {
        this.checkOwner();
        final long started = System.nanoTime();
        try {
            this.drain();
        } finally {
            this.callerNanos += System.nanoTime() - started;
        }
    }

    public boolean finish() {
        this.checkOwner();
        if (!this.finished) {
            this.flush();
            this.finished = true;
            if (this.items > 0) {
                LeavesXComputeExecutor.recordSnapshotBatch(this.workload, this.items, this.callerTasks,
                    this.workerTasks, this.callerItems, this.workerItems, this.maximumWorkers.get(),
                    this.callerNanos, this.successful, this.failure);
            }
        }
        return this.successful;
    }

    @Override
    public void close() {
        // 即使后续采集抛异常，也必须先停止全部写入，再让调用者清理或复用缓冲区。
        this.finish();
    }

    /** 只回收已结束的任务句柄，不等待慢片段；输出缓冲仍保留到 flush 或 finish 的完整屏障之后。 */
    private void reapCompleted() {
        int retained = 0;
        final int count = this.pending.size();
        for (int index = 0; index < count; index++) {
            final Task task = this.pending.get(index);
            if (task.completed.getCount() == 0L) {
                this.recordCompleted(task);
            } else {
                this.pending.set(retained++, task);
            }
        }
        this.pending.subList(retained, count).clear();
    }

    /** 结果与异常在完成闩锁之后读取；每个任务只在这里累计一次。 */
    private void recordCompleted(final Task task) {
        this.successful &= task.failure == null;
        if (this.failure == null) this.failure = task.failure;
        if (!task.executed) return;
        if (task.worker) {
            this.workerItems += task.size;
            this.workerTasks++;
        } else {
            this.callerItems += task.size;
            this.callerTasks++;
        }
    }

    private void drain() {
        if (!this.pending.isEmpty() && Thread.currentThread().isInterrupted()) this.successful = false;
        boolean interrupted = false;
        for (final Task task : this.pending) {
            while (task.completed.getCount() != 0L) {
                try {
                    OwnerThreadWaits.await(OwnerThreadWaits.Site.COMPUTE_BATCHES, task.completed, 0L);
                } catch (final InterruptedException interruption) {
                    interrupted = true;
                    this.successful = false;
                    if (this.failure == null) this.failure = interruption;
                }
            }
            this.recordCompleted(task);
        }
        this.pending.clear();
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void checkOwner() {
        if (Thread.currentThread() != this.owner) throw new IllegalStateException("Snapshot batch used by a different owner");
    }

    private static final class Task {
        private final int size;
        private Runnable computation;
        private final AtomicInteger active;
        private final AtomicInteger maximum;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CountDownLatch completed = new CountDownLatch(1);
        private Throwable failure;
        private boolean worker;
        private boolean executed;

        private Task(final int size, final Runnable computation, final AtomicInteger active, final AtomicInteger maximum) {
            this.size = size;
            this.computation = computation;
            this.active = active;
            this.maximum = maximum;
        }

        private void runOnWorker() {
            this.run(true);
        }

        private void run(final boolean worker) {
            if (!this.claimed.compareAndSet(false, true)) return;
            this.executed = true;
            this.worker = worker;
            if (worker) this.maximum.accumulateAndGet(this.active.incrementAndGet(), Math::max);
            try {
                this.computation.run();
            } catch (final Throwable failure) {
                this.failure = failure;
            } finally {
                this.computation = null;
                if (worker) this.active.decrementAndGet();
                this.completed.countDown();
            }
        }

        private void cancel(final Throwable failure) {
            if (this.claimed.compareAndSet(false, true)) {
                this.failure = failure;
                this.computation = null;
                this.completed.countDown();
            }
        }
    }
}
