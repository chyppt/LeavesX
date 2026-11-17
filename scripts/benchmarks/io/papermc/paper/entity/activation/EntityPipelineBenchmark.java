package io.papermc.paper.entity.activation;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import org.leavesx.leavesx.performance.EntityTrackingRangeBatch;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;
import org.leavesx.leavesx.runtime.LeavesXSnapshotBatch;

/** 比较同一批数值的准备加计算耗时；不包含世界查询、插件事件或网络，不能外推整服 MSPT。 */
public final class EntityPipelineBenchmark {
    private static volatile int consumed;

    public static void main(final String[] args) {
        final int workers = Integer.parseInt(args[0]);
        final int order = Integer.parseInt(args[1]);
        System.out.printf("BENCH_ENV java=%s processors=%d workers=%d%n",
            Runtime.version(), Runtime.getRuntime().availableProcessors(), workers);
        LeavesXComputeExecutor.configure(workers, 64, true, true);
        try {
            for (final int size : new int[] {1, 512, 4096, 16384}) {
                for (final int players : new int[] {5, 64}) {
                    final Tracking fixture = new Tracking(size, players);
                    fixture.run(false);
                    final boolean[][] expected = Arrays.stream(fixture.results).map(boolean[]::clone).toArray(boolean[][]::new);
                    fixture.run(true);
                    if (!Arrays.deepEquals(expected, fixture.results)) throw new AssertionError("tracking results differ");
                    measure("tracking", size, players, order, fixture::run);
                    final Activation activation = new Activation(size, players);
                    activation.run(false);
                    final boolean[] expectedActivation = activation.results.clone();
                    activation.run(true);
                    if (!Arrays.equals(expectedActivation, activation.results)) throw new AssertionError("activation results differ");
                    measure("activation", size, players, order, activation::run);
                }
            }
        } finally {
            LeavesXComputeExecutor.shutdown();
        }
        System.out.println("BENCH_DONE checksum=" + consumed);
    }

    private static void measure(final String kind, final int size, final int players, final int order,
                                final java.util.function.Consumer<Boolean> operation) {
        for (int mode = 0; mode < 2; mode++) {
            final boolean pipeline = (mode + order) % 2 == 0;
            long deadline = System.nanoTime() + 300_000_000L;
            do { operation.accept(pipeline); } while (System.nanoTime() < deadline);
            final double[] samples = new double[5];
            final var before = LeavesXComputeExecutor.metrics();
            for (int window = 0; window < samples.length; window++) {
                final long start = System.nanoTime();
                deadline = start + 150_000_000L;
                int calls = 0;
                do { operation.accept(pipeline); calls++; } while (System.nanoTime() < deadline);
                samples[window] = (System.nanoTime() - start) / (double) calls;
            }
            Arrays.sort(samples);
            final var after = LeavesXComputeExecutor.metrics();
            System.out.printf(Locale.ROOT, "BENCH_RESULT,%s,%d,%d,%s,%.3f,%.3f,%d%n",
                kind, size, players, pipeline ? "pipeline" : "baseline", samples[2] / 1000.0,
                samples[4] / 1000.0, after.workerItems() - before.workerItems());
        }
    }

    private static final class Tracking {
        final int size;
        final double[][] source;
        final double[] positions;
        final double[] views;
        final int[] indices;
        final boolean[][] results;
        final EntityTrackingRangeBatch range = new EntityTrackingRangeBatch();

        Tracking(final int size, final int players) {
            this.size = size;
            this.source = new double[size][3];
            this.positions = new double[players * 3];
            this.views = new double[players];
            this.indices = new int[players];
            this.results = new boolean[size][players];
            final Random random = new Random(14811);
            for (final double[] entity : this.source) for (int axis = 0; axis < 3; axis++) entity[axis] = random.nextDouble(-512, 512);
            for (int index = 0; index < this.positions.length; index++) this.positions[index] = random.nextDouble(-512, 512);
            Arrays.fill(this.views, 128.0);
            for (int index = 0; index < players; index++) this.indices[index] = index;
        }

        void run(final boolean pipelined) {
            this.range.prepare(this.size, this.positions, this.views);
            try (var batch = pipelined ? new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_TRACKING) : null) {
                int published = 0;
                final int rowsPerBatch = LeavesXSnapshotBatch.partitionSize(this.size, EntityTrackingRangeBatch.PIPELINE_ROWS);
                for (int row = 0; row < this.size; row++) {
                    final double[] point = this.source[row];
                    this.range.setRow(row, point[0], point[1], point[2], 128.0, 64.0,
                        this.indices, this.indices.length, this.results[row]);
                    if (batch != null && (row + 1 - published == rowsPerBatch || row + 1 == this.size)) {
                        this.range.submitRows(batch, published, row + 1, this.positions, this.views);
                        published = row + 1;
                    }
                }
                if (batch != null) {
                    if (!batch.finish()) this.range.compute(0, this.range.comparisonCount());
                } else if (LeavesXComputeExecutor.invokeRangesWithCount(LeavesXComputeExecutor.Workload.ENTITY_TRACKING,
                    this.range.comparisonCount(), 256, 64, (part, start, end) -> this.range.compute(start, end)) == 0) {
                    this.range.compute(0, this.range.comparisonCount());
                }
            }
            consumed = this.results[this.size - 1][0] ? 1 : 0;
            this.range.clear();
        }
    }

    private static final class Activation {
        final int size;
        final int players;
        final double[][] source;
        final double[] bounds;
        final byte[] types;
        final double[] playerBounds;
        final double[] unions;
        final double[] groups;
        final boolean[] results;

        Activation(final int size, final int players) {
            this.size = size;
            this.players = players;
            this.source = new double[size][6];
            this.bounds = new double[size * 6];
            this.types = new byte[size];
            this.playerBounds = new double[players * 8 * 6];
            this.unions = players < 8 ? new double[8 * 6] : null;
            this.groups = players >= 8 ? new double[((players + 3) / 4) * 8 * 6] : null;
            this.results = new boolean[size];
            final Random random = new Random(65443);
            for (final double[] entity : this.source) {
                for (int axis = 0; axis < 3; axis++) {
                    entity[axis] = random.nextDouble(-512, 512);
                    entity[axis + 3] = entity[axis] + 1.0;
                }
            }
            for (int player = 0; player < players; player++) {
                final double x = random.nextDouble(-512, 512);
                for (int box = 0; box < 8; box++) {
                    final int offset = (player * 8 + box) * 6;
                    this.playerBounds[offset] = x - 16;
                    this.playerBounds[offset + 1] = -256;
                    this.playerBounds[offset + 2] = -16;
                    this.playerBounds[offset + 3] = x + 16;
                    this.playerBounds[offset + 4] = 256;
                    this.playerBounds[offset + 5] = 16;
                }
            }
        }

        void run(final boolean pipelined) {
            if (this.groups != null) ActivationRangeSnapshot.snapshotActivationGroups(this.playerBounds, this.groups, this.players);
            else ActivationRangeSnapshot.snapshotActivationUnions(this.playerBounds, this.unions, this.players);
            try (var batch = pipelined ? new LeavesXSnapshotBatch(LeavesXComputeExecutor.Workload.ENTITY_ACTIVATION) : null) {
                int published = 0;
                final int entitiesPerBatch = LeavesXSnapshotBatch.partitionSize(this.size, 512);
                for (int entity = 0; entity < this.size; entity++) {
                    System.arraycopy(this.source[entity], 0, this.bounds, entity * 6, 6);
                    if (batch != null && (entity + 1 - published == entitiesPerBatch || entity + 1 == this.size)) {
                        ActivationRangeSnapshot.submitWorldRange(batch, this.bounds, this.types, this.playerBounds,
                            this.unions, this.groups, this.results, this.players, published, entity + 1);
                        published = entity + 1;
                    }
                }
                if (batch != null) {
                    if (!batch.finish()) ActivationRangeSnapshot.evaluateWorldRange(this.bounds, this.types,
                        this.playerBounds, this.unions, this.groups, this.results, this.players, 0, this.size);
                } else ActivationRangeSnapshot.evaluateWorldSnapshot(this.bounds, this.types, this.playerBounds,
                    this.unions, this.groups, this.results, this.size, this.players, 2048, 512);
            }
            consumed = this.results[this.size - 1] ? 1 : 0;
        }
    }
}
