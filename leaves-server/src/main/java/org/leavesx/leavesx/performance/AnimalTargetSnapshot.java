package org.leavesx.leavesx.performance;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;
import org.leavesx.leavesx.runtime.LeavesXSnapshotBatch;

/** 只并行处理原版农场动物目标的坐标归约；资格判断和实体访问保留在当前线程。 */
public final class AnimalTargetSnapshot {
    private static final int MAX_CACHED_CANDIDATES = 16_384;
    // 与执行器最大分片数一致，结果容量不随候选数量或配置热重载变化。
    private static final int MAX_PARTITION_RESULTS = 64;
    private static final int MIN_PIPELINE_CANDIDATES = 2_048;
    private static final ThreadLocal<Buffer> BUFFERS = ThreadLocal.withInitial(Buffer::new);

    private AnimalTargetSnapshot() {}

    static boolean supportsVanillaFarmAnimal(final Class<?> type) {
        return type == net.minecraft.world.entity.animal.chicken.Chicken.class
            || type == net.minecraft.world.entity.animal.cow.Cow.class
            || type == net.minecraft.world.entity.animal.sheep.Sheep.class
            || type == net.minecraft.world.entity.animal.pig.Pig.class;
    }

    /** 自定义动物可能覆盖位置、年龄或距离；整个查询回到原循环，不能混用两套判断。 */
    public static boolean canSnapshot(final Animal source, final List<? extends Animal> candidates) {
        final Class<?> type = source.getClass();
        if (!supportsVanillaFarmAnimal(type)) return false;
        if (!LeavesXComputeExecutor.shouldAttempt(LeavesXComputeExecutor.Workload.AI_TARGET_SELECT,
            candidates.size(), LeavesXRuntime.aiTargetSearchMinimumCandidates(),
            LeavesXRuntime.aiTargetSearchMinimumItemsPerWorker())) return false;
        for (final Animal candidate : candidates) {
            if (candidate.getClass() != type) return false;
        }
        return true;
    }

    /**
     * 按原版循环顺序筛选候选并记录位置，之后只把不可变数值交给工作线程。
     * 严格比较模式保留繁殖目标的首个平局；非严格模式保留幼体跟随目标的末个平局及 NaN 行为。
     */
    @SuppressWarnings("unchecked")
    public static <T extends LivingEntity> @Nullable Match<T> findNearest(
        final List<? extends T> candidates,
        final Predicate<? super T> eligible,
        final LivingEntity source,
        final boolean lastTieWins
    ) {
        final Buffer cached = BUFFERS.get();
        final Buffer buffer = cached.inUse ? new Buffer() : cached;
        buffer.inUse = true;
        int count = 0;
        final int size = candidates.size();
        try (final LeavesXSnapshotBatch pipeline = size > MIN_PIPELINE_CANDIDATES
            && LeavesXRuntime.asyncSettings().aiSnapshotPipeline()
            ? LeavesXSnapshotBatch.acquire(LeavesXComputeExecutor.Workload.AI_TARGET_SELECT) : null) {
            buffer.ensureCapacity(size);
            final double[] coordinates = buffer.coordinates;
            final double[] distances = buffer.distances;
            final int[] nearestByPartition = buffer.nearestByPartition;
            final int rangeSize = Math.max((int)(((long)size + MAX_PARTITION_RESULTS - 1) / MAX_PARTITION_RESULTS),
                LeavesXSnapshotBatch.partitionSize(size, MIN_PIPELINE_CANDIDATES));
            int submitted = 0;
            for (final T candidate : candidates) {
                if (!eligible.test(candidate)) continue;
                final Vec3 position = candidate.position();
                final int offset = count * 6;
                buffer.coordinates[offset] = position.x;
                buffer.coordinates[offset + 1] = position.y;
                buffer.coordinates[offset + 2] = position.z;
                // 原版先读取候选位置，再读取自身坐标；自定义资格逻辑若移动动物也保留采样顺序。
                buffer.coordinates[offset + 3] = source.getX();
                buffer.coordinates[offset + 4] = source.getY();
                buffer.coordinates[offset + 5] = source.getZ();
                buffer.entities[count] = candidate;
                count++;
                if (pipeline != null && count % rangeSize == 0) {
                    submitRange(pipeline, coordinates, distances, nearestByPartition, submitted++,
                        count - rangeSize, count, lastTieWins);
                }
            }

            if (count == 0) return null;
            final int completed;
            if (submitted > 0) {
                if (submitted * rangeSize < count) {
                    submitRange(pipeline, coordinates, distances, nearestByPartition, submitted,
                        submitted * rangeSize, count, lastTieWins);
                    submitted++;
                }
                completed = pipeline.finish() ? submitted : 0;
            } else {
                completed = LeavesXComputeExecutor.invokeRangesWithCount(
                    LeavesXComputeExecutor.Workload.AI_TARGET_SELECT, count,
                    LeavesXRuntime.aiTargetSearchMinimumCandidates(), LeavesXRuntime.aiTargetSearchMinimumItemsPerWorker(),
                    (partition, start, end) -> calculate(coordinates, distances, nearestByPartition, partition, start, end, lastTieWins));
            }
            if (completed == 0) calculate(coordinates, distances, nearestByPartition, 0, 0, count, lastTieWins);

            double best = Double.MAX_VALUE;
            int nearest = -1;
            final int partitions = Math.max(1, completed);
            boolean hasNaN = false;
            for (int partition = 0; partition < partitions; partition++) {
                if (nearestByPartition[partition] == -2) { hasNaN = true; break; }
            }
            if (hasNaN) {
                // 跟随逻辑的 NaN 会重置比较状态；保留原逐项语义，不错误地跨分片归约。
                for (int index = 0; index < count; index++) {
                    // 只有 NaN 路径需要按原顺序重算；只读已采集坐标，不重放资格判断。
                    final double distance = distanceSquared(coordinates, index);
                    if (lastTieWins ? !(distance > best) : distance < best) {
                        best = distance;
                        nearest = index;
                    }
                }
            } else {
                for (int partition = 0; partition < partitions; partition++) {
                    final int index = nearestByPartition[partition];
                    if (index < 0) continue;
                    final double distance = distances[partition];
                    if (lastTieWins ? !(distance > best) : distance < best) {
                        best = distance;
                        nearest = index;
                    }
                }
            }
            return nearest < 0 ? null : new Match<>((T) buffer.entities[nearest], best);
        } finally {
            // 复用存储但不跨查询持有实体；此缓冲属于调用线程，工作线程只接收数值数组。
            for (int index = 0; index < count; index++) buffer.entities[index] = null;
            buffer.inUse = false;
            // 保留日常查询的复用收益，释放异常大查询的数值数组。
            final int cacheLimit = Math.min(MAX_CACHED_CANDIDATES,
                LeavesXRuntime.asyncSettings().kernels().cacheMaxCandidates());
            if (buffer == cached && (cacheLimit == 0 || buffer.entities.length > cacheLimit)) BUFFERS.remove();
        }
    }

    private static void submitRange(final LeavesXSnapshotBatch pipeline, final double[] coordinates,
                                    final double[] distances, final int[] nearestByPartition, final int partition,
                                    final int start, final int end, final boolean lastTieWins) {
        // 不捕获动物或资格判断；每片只读自己已经冻结的坐标、写自己的结果槽位。
        pipeline.submit(end - start, () -> calculate(coordinates, distances, nearestByPartition, partition, start, end, lastTieWins));
    }

    private static void calculate(
        final double[] coordinates, final double[] distances, final int[] nearestByPartition,
        final int partition, final int start, final int end, final boolean lastTieWins
    ) {
        double best = Double.MAX_VALUE;
        int nearest = -1;
        boolean hasNaN = false;
        for (int index = start; index < end; index++) {
            final double distance = distanceSquared(coordinates, index);
            hasNaN |= Double.isNaN(distance);
            if (lastTieWins ? !(distance > best) : distance < best) {
                best = distance;
                nearest = index;
            }
        }
        distances[partition] = best;
        nearestByPartition[partition] = hasNaN ? -2 : nearest;
    }

    private static double distanceSquared(final double[] coordinates, final int index) {
        final int offset = index * 6;
        final double dx = coordinates[offset + 3] - coordinates[offset];
        final double dy = coordinates[offset + 4] - coordinates[offset + 1];
        final double dz = coordinates[offset + 5] - coordinates[offset + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    public record Match<T extends LivingEntity>(T entity, double distanceSquared) {}

    private static final class Buffer {
        private LivingEntity[] entities = new LivingEntity[0];
        private double[] coordinates = new double[0];
        private final double[] distances = new double[MAX_PARTITION_RESULTS];
        private final int[] nearestByPartition = new int[MAX_PARTITION_RESULTS];
        private boolean inUse;

        private void ensureCapacity(final int requested) {
            if (this.entities.length >= requested) return;
            final int cacheLimit = Math.min(MAX_CACHED_CANDIDATES,
                LeavesXRuntime.asyncSettings().kernels().cacheMaxCandidates());
            int capacity = Math.max(requested, Math.max(64, this.entities.length + (this.entities.length >>> 1)));
            // 超限输入仍完整采集；这里只避免日常扩容触发不必要的缓存淘汰。
            if (cacheLimit > 0 && requested <= cacheLimit) capacity = Math.min(capacity, cacheLimit);
            this.entities = new LivingEntity[capacity];
            this.coordinates = new double[Math.multiplyExact(capacity, 6)];
        }
    }
}
