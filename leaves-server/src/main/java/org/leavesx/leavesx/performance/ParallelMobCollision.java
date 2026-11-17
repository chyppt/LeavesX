package org.leavesx.leavesx.performance;

import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;

/** 只把不可变 AABB 算术移出主线程；收集形状和应用位移仍在 Tick 线程执行。 */
public final class ParallelMobCollision {
    private static final int MIN_BOXES = 8_192;
    private static final int MIN_BOXES_PER_WORKER = 2_048;
    private static final int MAX_CACHED_BOXES = 32_768;
    // 执行器最大分片数；结果数组不随热重载线程数变化，避免旧任务写入越界。
    private static final int MAX_PARTITION_RESULTS = 64;
    private static final ThreadLocal<Buffer> BUFFERS = ThreadLocal.withInitial(Buffer::new);

    private ParallelMobCollision() {}

    public static Vec3 collide(final Entity entity, final Vec3 movement, final AABB box,
                               final List<VoxelShape> voxels, final List<AABB> boxes) {
        if (!(entity instanceof Mob) || entity instanceof Villager || !voxels.isEmpty()
            || !LeavesXRuntime.configuration().extensions().parallelMobCollisions()
            || (boxes.size() < MIN_BOXES && !LeavesXComputeExecutor.lowLoadWorkersEnabled()) || !Double.isFinite(movement.x)
            || !Double.isFinite(movement.y) || !Double.isFinite(movement.z)
            || !LeavesXComputeExecutor.shouldAttempt(LeavesXComputeExecutor.Workload.MOB_COLLISION, boxes.size(), MIN_BOXES)) {
            return CollisionUtil.performCollisions(movement, box, voxels, boxes);
        }
        final Buffer cached = BUFFERS.get();
        // 自定义碰撞回调理论上可能重入；正在使用的线程缓存不能被内层查询覆盖。
        final Buffer buffer = cached.inUse ? new Buffer() : cached;
        buffer.inUse = true;
        try {
            return collideSnapshot(movement, box, boxes, buffer);
        } finally {
            buffer.release(boxes.size());
            if (buffer == cached && buffer.boxes.length > MAX_CACHED_BOXES) BUFFERS.remove();
        }
    }

    private static Vec3 collideSnapshot(final Vec3 movement, final AABB box, final List<AABB> boxes, final Buffer buffer) {
        final AABB[] snapshot = buffer.capture(boxes);
        final int size = boxes.size();
        if (LeavesXComputeExecutor.lowLoadWorkersEnabled() && size < MIN_BOXES) {
            // 低负载时把同一个实体的完整碰撞步骤作为一个纯数值任务提交；三个轴仍按原版顺序计算，
            // 但不会为每个轴各自创建一次线程任务。
            final Vec3[] result = buffer.result;
            result[0] = null;
            final int completed = LeavesXComputeExecutor.invokeRangesWithCount(
                LeavesXComputeExecutor.Workload.MOB_COLLISION,
                size,
                MIN_BOXES,
                MIN_BOXES_PER_WORKER,
                (partition, start, end) -> result[0] = CollisionUtil.performAABBCollisions(
                    movement,
                    box,
                    Arrays.asList(snapshot).subList(0, size)
                )
            );
            return completed > 0 && result[0] != null
                ? result[0]
                : CollisionUtil.performAABBCollisions(movement, box, boxes);
        }
        return computeSnapshot(movement, box, snapshot, size, buffer.limits, boxes);
    }

    static Vec3 compute(final Vec3 movement, final AABB box, final List<AABB> boxes) {
        return computeSnapshot(movement, box, boxes.toArray(AABB[]::new), boxes.size(),
            new double[MAX_PARTITION_RESULTS], boxes);
    }

    private static Vec3 computeSnapshot(
        final Vec3 movement,
        AABB box,
        final AABB[] snapshot,
        final int size,
        final double[] limits,
        final List<AABB> original
    ) {
        double x = movement.x;
        double y = movement.y;
        double z = movement.z;
        if (y != 0.0) {
            y = axis(Direction.Axis.Y, box, y, snapshot, size, limits, original);
            if (y != 0.0) box = CollisionUtil.offsetY(box, y);
        }
        final boolean xSmaller = Math.abs(x) < Math.abs(z);
        if (xSmaller && z != 0.0) {
            z = axis(Direction.Axis.Z, box, z, snapshot, size, limits, original);
            if (z != 0.0) box = CollisionUtil.offsetZ(box, z);
        }
        if (x != 0.0) {
            x = axis(Direction.Axis.X, box, x, snapshot, size, limits, original);
            if (!xSmaller && x != 0.0) box = CollisionUtil.offsetX(box, x);
        }
        if (!xSmaller && z != 0.0) z = axis(Direction.Axis.Z, box, z, snapshot, size, limits, original);
        return new Vec3(x, y, z);
    }

    private static double axis(final Direction.Axis axis, final AABB box, final double movement,
                               final AABB[] snapshot, final int size, final double[] limits,
                               final List<AABB> original) {
        final boolean positive = movement > 0.0;
        final int partitions = LeavesXComputeExecutor.invokeRangesWithCount(
            LeavesXComputeExecutor.Workload.MOB_COLLISION, size, MIN_BOXES, MIN_BOXES_PER_WORKER,
            (partition, start, end) -> {
                double limit = movement;
                for (int i = start; i < end; i++) {
                    limit = switch (axis) {
                        case X -> CollisionUtil.collideX(snapshot[i], box, limit);
                        case Y -> CollisionUtil.collideY(snapshot[i], box, limit);
                        case Z -> CollisionUtil.collideZ(snapshot[i], box, limit);
                    };
                    // 接触误差可能反转极小位移，使归并依赖顺序；整条并行轴直接回退，复现原版串行顺序（包括带符号零）。
                    if (!Double.isFinite(limit) || (positive ? limit <= 0.0 : limit >= 0.0)) {
                        limit = Double.NaN;
                        break;
                    }
                }
                limits[partition] = limit;
            });
        if (partitions > 0) {
            double result = movement;
            for (int i = 0; i < partitions; i++) {
                if (Double.isNaN(limits[i])) return sequential(axis, box, movement, original);
                result = positive ? Math.min(result, limits[i]) : Math.max(result, limits[i]);
            }
            return result;
        }
        return sequential(axis, box, movement, original);
    }

    private static double sequential(final Direction.Axis axis, final AABB box, final double movement, final List<AABB> boxes) {
        return switch (axis) {
            case X -> CollisionUtil.performAABBCollisionsX(box, movement, boxes);
            case Y -> CollisionUtil.performAABBCollisionsY(box, movement, boxes);
            case Z -> CollisionUtil.performAABBCollisionsZ(box, movement, boxes);
        };
    }

    /** 调用线程独占的碰撞快照；执行器返回前保证所有工作线程已结束写入。 */
    private static final class Buffer {
        private AABB[] boxes = new AABB[0];
        private final double[] limits = new double[MAX_PARTITION_RESULTS];
        private final Vec3[] result = new Vec3[1];
        private boolean inUse;

        private AABB[] capture(final List<AABB> source) {
            final int size = source.size();
            if (this.boxes.length < size) {
                this.boxes = new AABB[Math.max(size, Math.max(64, this.boxes.length + (this.boxes.length >>> 1)))];
            }
            for (int i = 0; i < size; i++) this.boxes[i] = source.get(i);
            return this.boxes;
        }

        private void release(final int size) {
            // 只清除本次写入范围，避免长期持有已卸载区块的碰撞盒引用。
            Arrays.fill(this.boxes, 0, Math.min(size, this.boxes.length), null);
            this.result[0] = null;
            this.inUse = false;
        }
    }
}
