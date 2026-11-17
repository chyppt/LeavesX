package io.papermc.paper.entity.activation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.leavesx.leavesx.config.KernelBenchmarkConfiguration;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;

/** 同一进程交替开关激活范围预筛选；包含数组准备成本，不用于推算整服 MSPT。 */
final class KernelBenchmark {
    private static volatile long consumed;

    static void main(final String[] args) {
        final int workers = Integer.parseInt(args[0]);
        final int order = Integer.parseInt(args[1]);
        final boolean lowLoad = Boolean.parseBoolean(args[2]);
        System.out.printf(Locale.ROOT, "BENCH_ENV java=%s processors=%d workers=%d order=%d low-load=%s suite=kernels%n",
            Runtime.version(), Runtime.getRuntime().availableProcessors(), workers, order, lowLoad);
        LeavesXComputeExecutor.configure(workers, 64, true, lowLoad);
        try {
            final List<Runnable> cases = new ArrayList<>();
            for (final int size : new int[] {245, 2_048, 8_192}) {
                for (final int players : new int[] {2, 10, 32}) {
                    cases.add(() -> activation(size, players, false, order));
                    cases.add(() -> activation(size, players, true, order));
                }
            }
            Collections.shuffle(cases, new Random(order));
            for (final Runnable operation : cases) operation.run();
        } finally {
            KernelBenchmarkConfiguration.restore();
            LeavesXComputeExecutor.shutdown();
        }
        System.out.println("BENCH_DONE checksum=" + consumed);
    }

    private static void configure(final boolean enabled) {
        KernelBenchmarkConfiguration.configure(enabled);
    }

    private static void activation(final int size, final int players, final boolean spread, final int order) {
        final Random random = new Random(0x416374L + size);
        final int boxes = ActivationType.values().length + 1;
        final double[] entities = new double[size * 6];
        final byte[] types = new byte[size];
        final double[] bounds = new double[players * boxes * 6];
        final double[] unions = new double[boxes * 6];
        final double[] groups = new double[((players + 3) / 4) * boxes * 6];
        final boolean[] results = new boolean[size];
        for (int entity = 0; entity < size; entity++) {
            for (int axis = 0; axis < 3; axis++) {
                entities[entity * 6 + axis] = random.nextDouble() * 512.0 - 256.0;
                entities[entity * 6 + axis + 3] = entities[entity * 6 + axis] + 1.0;
            }
            types[entity] = (byte)(entity % (boxes - 1));
        }
        for (int player = 0; player < players; player++) {
            for (int box = 0; box < boxes; box++) {
                final int offset = (player * boxes + box) * 6;
                for (int axis = 0; axis < 3; axis++) {
                    final double center = axis == 0 ? player * (spread ? 96.0 : 4.0) : 0.0;
                    bounds[offset + axis] = center - 32.0;
                    bounds[offset + axis + 3] = center + 32.0;
                }
            }
        }
        final boolean[] expected = new boolean[size];
        ActivationRangeSnapshot.evaluateWorldRange(entities, types, bounds, expected, players, 0, size);
        final Runnable operation = () -> {
            final boolean prefilter = LeavesXRuntime.activationBoundsPrefilter() && players >= 4;
            final boolean grouped = prefilter && players >= 8;
            if (grouped) ActivationRangeSnapshot.snapshotActivationGroups(bounds, groups, players);
            else if (prefilter) ActivationRangeSnapshot.snapshotActivationUnions(bounds, unions, players);
            ActivationRangeSnapshot.evaluateWorldSnapshot(entities, types, bounds,
                prefilter && !grouped ? unions : null,
                grouped ? groups : null,
                results, size, players, 2_048, 512);
            consumed = results[size / 2] ? 1 : 0;
        };
        configure(true);
        operation.run();
        if (!Arrays.equals(expected, results)) throw new AssertionError("activation mismatch");
        compare("activation", size, players, spread ? "spread" : "clustered", order, operation);
    }

    private static void compare(final String workload, final int size, final int players,
                                final String distribution, final int order, final Runnable operation) {
        // 两种变体都预热后再计时，交替顺序减少 JIT、频率变化和运行先后的偏差。
        for (int round = 0; round < 2; round++) {
            configure((round + order) % 2 == 0);
            runWindow(operation, 400_000_000L);
        }
        final double[][] samples = new double[2][11];
        for (int window = 0; window < 11; window++) {
            for (int round = 0; round < 2; round++) {
                final int enabled = (window + round + order) % 2;
                configure(enabled == 1);
                samples[enabled][window] = runWindow(operation, 40_000_000L);
            }
        }
        for (int enabled = 0; enabled < 2; enabled++) {
            Arrays.sort(samples[enabled]);
            System.out.printf(Locale.ROOT, "BENCH_RESULT,%s,%d,%d,%s,%s,%.3f,%.3f,0,0%n",
                workload, size, players, distribution, enabled == 1 ? "kernel" : "baseline",
                samples[enabled][5] / 1_000.0, samples[enabled][10] / 1_000.0);
        }
    }

    private static double runWindow(final Runnable operation, final long duration) {
        final long start = System.nanoTime();
        final long deadline = start + duration;
        int count = 0;
        do {
            for (int index = 0; index < 8; index++) operation.run();
            count += 8;
        } while (System.nanoTime() < deadline);
        return (System.nanoTime() - start) / (double)count;
    }
}
