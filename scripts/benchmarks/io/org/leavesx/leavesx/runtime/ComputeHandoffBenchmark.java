package org.leavesx.leavesx.runtime;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 同 JVM 交替对照真实提交入口；基线只重命名类，不修改原准入与计数算法。 */
public final class ComputeHandoffBenchmark {
    private static volatile long consumed;

    public static void main(String[] args) throws Exception {
        int workers = Integer.parseInt(args[0]);
        int order = Integer.parseInt(args[1]);
        System.out.printf(Locale.ROOT, "BENCH_ENV java=%s processors=%d workers=%d suite=compute-handoff%n",
            Runtime.version(), Runtime.getRuntime().availableProcessors(), workers);
        HandoffBaselineExecutor.configure(workers, 64, true, true);
        LeavesXComputeExecutor.configure(workers, 64, true, true);
        try {
            for (int producers : new int[] {1, 4}) {
                for (int tasks : new int[] {1, 16, 128}) {
                    for (int items : new int[] {1, 4096}) measure(producers, tasks, items, order);
                }
            }
        } finally {
            HandoffBaselineExecutor.shutdown();
            LeavesXComputeExecutor.shutdown();
        }
        System.out.println("BENCH_DONE checksum=" + consumed);
    }

    private static void measure(int producers, int tasks, int items, int order) throws Exception {
        try (ExecutorService callers = Executors.newFixedThreadPool(producers)) {
            Group[] groups = new Group[producers];
            Arrays.setAll(groups, ignored -> new Group(tasks, items));
            Operation[] operations = {
                new Operation(groups, callers, work -> HandoffBaselineExecutor.tryExecuteSnapshot(work,
                    failure -> { throw new AssertionError(failure); })),
                new Operation(groups, callers, work -> LeavesXComputeExecutor.tryExecuteSnapshot(work,
                    failure -> { throw new AssertionError(failure); }))
            };
            for (Operation operation : operations) operation.run();
            double[][] samples = new double[2][11];
            for (int warm = 0; warm < 6; warm++) window(operations[(warm + order) & 1], 60_000_000L);
            for (int sample = 0; sample < 11; sample++) {
                for (int turn = 0; turn < 2; turn++) {
                    int variant = (sample + order + turn) & 1;
                    samples[variant][sample] = window(operations[variant], 35_000_000L);
                }
            }
            for (int variant = 0; variant < 2; variant++) {
                Arrays.sort(samples[variant]);
                System.out.printf(Locale.ROOT, "BENCH_RESULT,compute-handoff,%d,%d,%d,%s,%.3f,%.3f%n",
                    producers, tasks, items, variant == 0 ? "previous" : "current", samples[variant][5], samples[variant][10]);
            }
        }
    }

    private interface Dispatcher { boolean submit(Runnable work); }

    private record Operation(Group[] groups, ExecutorService callers, Dispatcher dispatcher) implements Runnable {
        @Override
        public void run() {
            try {
                if (groups.length == 1) {
                    consumed = groups[0].run(dispatcher);
                } else {
                    var submitted = new java.util.ArrayList<java.util.concurrent.Future<Integer>>(groups.length);
                    for (Group group : groups) submitted.add(callers.submit(() -> group.run(dispatcher)));
                    long sum = 0;
                    for (var result : submitted) sum += result.get(5, TimeUnit.SECONDS);
                    consumed = sum;
                }
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class Group {
        private final Work[] work;
        private final int[] output;
        private final int[] expected;

        private Group(int count, int items) {
            output = new int[count];
            expected = new int[count];
            work = new Work[count];
            Arrays.setAll(work, index -> new Work(index, items, output));
            for (int index = 0; index < count; index++) {
                int value = index;
                for (int item = 0; item < items; item++) value = value * 31 + item;
                expected[index] = value;
            }
        }

        private int run(Dispatcher dispatcher) throws InterruptedException {
            CountDownLatch done = new CountDownLatch(work.length);
            for (Work next : work) {
                next.done = done;
                if (!dispatcher.submit(next)) next.run();
            }
            if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("提交屏障超时");
            for (int index = 0; index < work.length; index++) {
                if (output[index] != expected[index]) throw new AssertionError("计算结果不一致");
            }
            return Arrays.hashCode(output);
        }
    }

    private static final class Work implements Runnable {
        private final int index;
        private final int items;
        private final int[] output;
        private CountDownLatch done;

        private Work(int index, int items, int[] output) { this.index = index; this.items = items; this.output = output; }

        @Override
        public void run() {
            int value = index;
            for (int item = 0; item < items; item++) value = value * 31 + item;
            output[index] = value;
            done.countDown();
        }
    }

    private static double window(Runnable operation, long duration) {
        long count = 0;
        long started = System.nanoTime();
        long ended;
        do { operation.run(); count++; ended = System.nanoTime(); } while (ended - started < duration);
        return (ended - started) / (count * 1000.0);
    }
}
