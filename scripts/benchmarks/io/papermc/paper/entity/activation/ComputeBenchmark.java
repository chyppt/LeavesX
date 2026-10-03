package io.papermc.paper.entity.activation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.leavesx.leavesx.performance.StableDistanceSorter;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;

/**
 * Local cost comparison using the production implementations, with no world or plugin code on worker threads.
 * This is a warmed timing probe, not JMH or a prediction of whole-server MSPT. Run several JVM forks.
 */
class ComputeBenchmark {
    private static volatile long consumed;
    private static final int WINDOWS = 11;
    private static final long WARMUP_NANOS = 300_000_000L;
    private static final long WINDOW_NANOS = 25_000_000L;

    public static void main(final String[] args) {
        final int workers = Integer.parseInt(args[0]);
        final int orderSeed = Integer.parseInt(args[1]);
        System.out.printf(Locale.ROOT, "BENCH_ENV java=%s processors=%d workers=%d order=%d%n",
            Runtime.version(), Runtime.getRuntime().availableProcessors(), workers, orderSeed);
        LeavesXComputeExecutor.configure(workers, 64);
        try {
            final List<Runnable> cases = new ArrayList<>();
            for (final int size : new int[] {
                82, 192, 245, 512, 2_048, 8_192, 32_768, 65_536, 131_072, 262_144, 524_288
            }) {
                for (final boolean integral : new boolean[] {true, false}) {
                    cases.add(() -> sorting(size, integral));
                }
            }
            for (final int size : new int[] {245, 2_048, 8_192, 32_768}) {
                for (final int players : new int[] {2, 10}) {
                    cases.add(() -> activation(size, players));
                }
            }
            Collections.shuffle(cases, new Random(orderSeed));
            for (final Runnable testCase : cases) testCase.run();
        } finally {
            LeavesXComputeExecutor.shutdown();
        }
        System.out.println("BENCH_DONE checksum=" + consumed);
    }

    private static void sorting(final int size, final boolean integral) {
        final Random random = new Random(0x4C656176657358L + size);
        final double[] x = new double[size];
        final double[] y = new double[size];
        final double[] z = new double[size];
        for (int index = 0; index < size; index++) {
            x[index] = random.nextInt(65) - 32;
            y[index] = random.nextInt(17) - 8;
            z[index] = random.nextInt(65) - 32;
            if (!integral) {
                x[index] += random.nextDouble();
                y[index] += random.nextDouble();
                z[index] += random.nextDouble();
            }
        }
        final int[] expected = StableDistanceSorter.sort(x, y, z, 0.0, 0.0, 0.0, false, 256);
        final int[] actual = StableDistanceSorter.sort(x, y, z, 0.0, 0.0, 0.0, true, 256);
        if (!Arrays.equals(expected, actual)) throw new AssertionError("sort differential mismatch");
        final Runnable serial = () -> consume(StableDistanceSorter.sort(x, y, z, 0.0, 0.0, 0.0, false, 256));
        final Runnable parallel = () -> consume(StableDistanceSorter.sort(x, y, z, 0.0, 0.0, 0.0, true, 256));
        final String distribution = integral ? "integer" : "fractional";
        measure("sort", size, 0, distribution, "serial", serial);
        measure("sort", size, 0, distribution, "configured", parallel);
    }

    private static void activation(final int size, final int players) {
        final Random random = new Random(0x41414242L + size);
        final double[] bounds = new double[size * 6];
        final byte[] types = new byte[size];
        final int boxesPerPlayer = ActivationType.values().length + 1;
        final double[] playerBounds = new double[players * boxesPerPlayer * 6];
        for (int index = 0; index < size; index++) {
            final int offset = index * 6;
            final double x = random.nextDouble() * 512 - 256;
            final double y = random.nextDouble() * 64;
            final double z = random.nextDouble() * 512 - 256;
            bounds[offset] = x;
            bounds[offset + 1] = y;
            bounds[offset + 2] = z;
            bounds[offset + 3] = x + 0.6;
            bounds[offset + 4] = y + 1.8;
            bounds[offset + 5] = z + 0.6;
            types[index] = (byte) (index % ActivationType.values().length);
        }
        for (int player = 0; player < players; player++) {
            final double centerX = player * 32 - 128;
            for (int box = 0; box < boxesPerPlayer; box++) {
                final int offset = (player * boxesPerPlayer + box) * 6;
                playerBounds[offset] = centerX - 32;
                playerBounds[offset + 1] = -64;
                playerBounds[offset + 2] = -32;
                playerBounds[offset + 3] = centerX + 32;
                playerBounds[offset + 4] = 320;
                playerBounds[offset + 5] = 32;
            }
        }
        final boolean[] expected = new boolean[size];
        final boolean[] actual = new boolean[size];
        ActivationRangeSnapshot.evaluateWorldRange(bounds, types, playerBounds, expected, players, 0, size);
        ActivationRangeSnapshot.evaluateWorldSnapshot(bounds, types, playerBounds, actual, size, players, 2_048, 512);
        if (!Arrays.equals(expected, actual)) throw new AssertionError("activation differential mismatch");
        final Runnable serial = () -> {
            ActivationRangeSnapshot.evaluateWorldRange(bounds, types, playerBounds, actual, players, 0, size);
            consumed = actual[size / 2] ? 1 : 0;
        };
        final Runnable configured = () -> {
            ActivationRangeSnapshot.evaluateWorldSnapshot(bounds, types, playerBounds, actual, size, players, 2_048, 512);
            consumed = actual[size / 2] ? 1 : 0;
        };
        // Deliberately force a smaller batch for comparison only; production defaults are left unchanged.
        final Runnable forced = () -> {
            ActivationRangeSnapshot.evaluateWorldSnapshot(bounds, types, playerBounds, actual, size, players, 1, 64);
            consumed = actual[size / 2] ? 1 : 0;
        };
        measure("activation", size, players, "aabb", "serial", serial);
        measure("activation", size, players, "aabb", "configured", configured);
        measure("activation", size, players, "aabb", "forced", forced);
    }

    private static void consume(final int[] order) {
        consumed = order[0] * 31L + order[order.length / 2] * 17L + order[order.length - 1];
    }

    private static void measure(final String workload, final int size, final int players,
                                final String distribution, final String mode, final Runnable operation) {
        long deadline = System.nanoTime() + WARMUP_NANOS;
        do { operation.run(); } while (System.nanoTime() < deadline);
        final var before = LeavesXComputeExecutor.metrics();
        final double[] samples = new double[WINDOWS];
        for (int window = 0; window < WINDOWS; window++) {
            final long start = System.nanoTime();
            deadline = start + WINDOW_NANOS;
            int calls = 0;
            do {
                for (int index = 0; index < 8; index++) operation.run();
                calls += 8;
            } while (System.nanoTime() < deadline);
            samples[window] = (System.nanoTime() - start) / (double) calls;
        }
        final var after = LeavesXComputeExecutor.metrics();
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT,
            "BENCH_RESULT,%s,%d,%d,%s,%s,%.3f,%.3f,%d,%d%n",
            workload, size, players, distribution, mode,
            samples[WINDOWS / 2] / 1_000.0, samples[WINDOWS - 1] / 1_000.0,
            after.parallelInvocations() - before.parallelInvocations(),
            after.workerItems() - before.workerItems());
    }
}
