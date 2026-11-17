package org.leavesx.leavesx.performance;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.jspecify.annotations.Nullable;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;
import org.leavesx.leavesx.runtime.LeavesXSnapshotBatch;

/** 普通目标搜索的数值部分；选择器、视线和实体引用始终留在调用线程。 */
public final class TargetDistanceSnapshot {
    private static final int MAX_CACHED_CANDIDATES = 16_384;
    // LeavesXComputeExecutor 的硬上限；不使用可热重载的当前线程数决定结果容量。
    private static final int MAX_PARTITION_RESULTS = 64;
    private static final int MIN_PIPELINE_CANDIDATES = 2_048;
    private static final ThreadLocal<Buffer> BUFFERS = ThreadLocal.withInitial(Buffer::new);
    private static final ClassValue<Boolean> VANILLA_DISTANCE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            try {
                return type.getMethod("distanceToSqr", double.class, double.class, double.class).getDeclaringClass() == Entity.class;
            } catch (final NoSuchMethodException failure) {
                return false;
            }
        }
    };

    private TargetDistanceSnapshot() {}

    @SuppressWarnings("unchecked")
    public static <T extends LivingEntity> @Nullable T findNearest(
        final List<? extends T> entities, final ServerLevel level, final TargetingConditions conditions,
        final @Nullable LivingEntity source, final double x, final double y, final double z
    ) {
        final int size = entities.size();
        // 先检查调度资格，低负载或关闭并行时不额外遍历整张候选表。
        if (!LeavesXComputeExecutor.shouldAttempt(LeavesXComputeExecutor.Workload.AI_TARGET_SELECT,
            size, LeavesXRuntime.aiTargetSearchMinimumCandidates(), LeavesXRuntime.aiTargetSearchMinimumItemsPerWorker())) {
            return findSynchronously(entities, level, conditions, source, x, y, z);
        }
        boolean supported = true;
        Class<?> firstType = null;
        Class<?> previousType = null;
        for (int i = 0; i < size; i++) {
            final Class<?> candidateType = entities.get(i).getClass();
            // 保留首类与最近异类，避免同种或两种交错候选反复查类缓存；自定义距离仍完整回退。
            if (candidateType != firstType && candidateType != previousType) {
                if (!VANILLA_DISTANCE.get(candidateType)) {
                    supported = false;
                    break;
                }
                if (firstType == null) firstType = candidateType;
                else previousType = candidateType;
            }
        }
        if (!supported) {
            return findSynchronously(entities, level, conditions, source, x, y, z);
        }

        final Buffer cached = BUFFERS.get();
        // 选择器可能重入目标查询；外层正在采集的数据不能被内层覆盖。
        final Buffer buffer = cached.inUse ? new Buffer() : cached;
        buffer.inUse = true;
        int accepted = 0;
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
            // 候选引用只在原线程保存；选择器替换列表内容时仍返回当时接受的同一个实体。
            for (final T candidate : entities) {
                if (conditions.test(level, source, candidate)) {
                    // 在原版计算距离的位置采集，后续选择器即使移动前一个候选也不改变它的距离。
                    final int offset = accepted * 3;
                    buffer.coordinates[offset] = candidate.getX();
                    buffer.coordinates[offset + 1] = candidate.getY();
                    buffer.coordinates[offset + 2] = candidate.getZ();
                    buffer.entities[accepted] = candidate;
                    accepted++;
                    if (pipeline != null && accepted % rangeSize == 0) {
                        submitRange(pipeline, coordinates, distances, nearestByPartition, submitted++,
                            accepted - rangeSize, accepted, x, y, z);
                    }
                }
            }
            final int count = accepted;
            if (count == 0) return null;
            final int partitions;
            if (submitted > 0) {
                if (submitted * rangeSize < count) {
                    submitRange(pipeline, coordinates, distances, nearestByPartition, submitted,
                        submitted * rangeSize, count, x, y, z);
                    submitted++;
                }
                partitions = pipeline.finish() ? submitted : 0;
            } else {
                partitions = LeavesXComputeExecutor.invokeRangesWithCount(
                    LeavesXComputeExecutor.Workload.AI_TARGET_SELECT, count,
                    LeavesXRuntime.aiTargetSearchMinimumCandidates(), LeavesXRuntime.aiTargetSearchMinimumItemsPerWorker(),
                    (partition, start, end) -> compute(coordinates, distances, nearestByPartition, partition, start, end, x, y, z));
            }
            // 只重算冻结的坐标，不能再次运行可能有插件副作用的资格判断。
            if (partitions == 0) compute(coordinates, distances, nearestByPartition, 0, 0, count, x, y, z);

            double best = -1.0;
            int nearest = -1;
            // 分片已完成距离计算和局部归约；原线程只按原顺序合并分片赢家。
            for (int partition = 0; partition < Math.max(1, partitions); partition++) {
                final int i = nearestByPartition[partition];
                if (i < 0) continue;
                final double distance = distances[partition];
                // 原版使用严格小于与 -1 哨兵；不能改成 Double.compare，否则 NaN、平局行为会变化。
                if (best == -1.0 || distance < best) {
                    best = distance;
                    nearest = i;
                }
            }
            return nearest < 0 ? null : (T) buffer.entities[nearest];
        } finally {
            // 复用引用数组减少每次查询的列表分配，但不跨查询保留实体或世界。
            java.util.Arrays.fill(buffer.entities, 0, accepted, null);
            buffer.inUse = false;
            // 极端刷怪峰值允许临时扩容，但不能让每个线程永久保留峰值数组。
            final int cacheLimit = Math.min(MAX_CACHED_CANDIDATES,
                LeavesXRuntime.asyncSettings().kernels().cacheMaxCandidates());
            if (buffer == cached && (cacheLimit == 0 || buffer.entities.length > cacheLimit)) BUFFERS.remove();
        }
    }

    private static void submitRange(final LeavesXSnapshotBatch pipeline, final double[] coordinates,
                                    final double[] distances, final int[] nearestByPartition, final int partition,
                                    final int start, final int end, final double x, final double y, final double z) {
        // 只发布已采集完成的前缀，工作线程不捕获实体、选择器或正在追加的引用数组。
        pipeline.submit(end - start, () -> compute(coordinates, distances, nearestByPartition, partition, start, end, x, y, z));
    }

    private static <T extends LivingEntity> @Nullable T findSynchronously(
        final List<? extends T> entities, final ServerLevel level, final TargetingConditions conditions,
        final @Nullable LivingEntity source, final double x, final double y, final double z
    ) {
        double best = -1.0;
        T nearest = null;
        for (final T candidate : entities) {
            if (conditions.test(level, source, candidate)) {
                final double distance = candidate.distanceToSqr(x, y, z);
                if (best == -1.0 || distance < best) {
                    best = distance;
                    nearest = candidate;
                }
            }
        }
        return nearest;
    }

    private static void compute(final double[] coordinates, final double[] distances,
                                final int[] nearestByPartition, final int partition,
                                final int start, final int end, final double x, final double y, final double z) {
        double best = -1.0;
        int nearest = -1;
        for (int i = start; i < end; i++) {
            final int offset = i * 3;
            final double dx = coordinates[offset] - x;
            final double dy = coordinates[offset + 1] - y;
            final double dz = coordinates[offset + 2] - z;
            final double distance = dx * dx + dy * dy + dz * dz;
            // 全表第一个 NaN 必须胜出；后续 NaN 原版严格比较不会接受。
            // 非首片不能把片首 NaN 当赢家，否则会掩盖片内后续有效距离。
            if ((i == 0 || !Double.isNaN(distance)) && (best == -1.0 || distance < best)) {
                best = distance;
                nearest = i;
            }
        }
        distances[partition] = best;
        nearestByPartition[partition] = nearest;
    }

    /** 引用数组仅供调用线程临时使用，工作线程只接收坐标与距离数组。 */
    private static final class Buffer {
        private LivingEntity[] entities = new LivingEntity[0];
        private double[] coordinates = new double[0];
        private final double[] distances = new double[MAX_PARTITION_RESULTS];
        private final int[] nearestByPartition = new int[MAX_PARTITION_RESULTS];
        private boolean inUse;

        private void ensureCapacity(final int size) {
            if (this.entities.length >= size) return;
            final int cacheLimit = Math.min(MAX_CACHED_CANDIDATES,
                LeavesXRuntime.asyncSettings().kernels().cacheMaxCandidates());
            int capacity = Math.max(size, Math.max(64, this.entities.length + (this.entities.length >>> 1)));
            // 不让几何扩容越过保留上限，否则正常查询也会反复分配并丢弃数组。
            if (cacheLimit > 0 && size <= cacheLimit) capacity = Math.min(capacity, cacheLimit);
            this.coordinates = new double[Math.multiplyExact(capacity, 3)];
            this.entities = new LivingEntity[capacity];
        }
    }
}
