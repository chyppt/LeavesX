package org.leavesx.leavesx.performance;

import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
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

/** Offloads only immutable AABB arithmetic. Gathering shapes and applying movement remain on the tick thread. */
public final class ParallelMobCollision {
    private static final int MIN_BOXES = 8_192;
    private static final int MIN_BOXES_PER_WORKER = 2_048;

    private ParallelMobCollision() {}

    public static Vec3 collide(final Entity entity, final Vec3 movement, final AABB box,
                               final List<VoxelShape> voxels, final List<AABB> boxes) {
        if (!(entity instanceof Mob) || entity instanceof Villager || !voxels.isEmpty()
            || !LeavesXRuntime.configuration().extensions().parallelMobCollisions()
            || boxes.size() < MIN_BOXES || !Double.isFinite(movement.x)
            || !Double.isFinite(movement.y) || !Double.isFinite(movement.z)
            || !LeavesXComputeExecutor.shouldAttempt(LeavesXComputeExecutor.Workload.MOB_COLLISION, boxes.size(), MIN_BOXES)) {
            return CollisionUtil.performCollisions(movement, box, voxels, boxes);
        }
        return compute(movement, box, boxes);
    }

    static Vec3 compute(final Vec3 movement, AABB box, final List<AABB> boxes) {
        // Copy the list; AABB coordinates themselves are final. No entity, world, or mutable shape reaches a worker.
        final AABB[] snapshot = boxes.toArray(AABB[]::new);
        double x = movement.x;
        double y = movement.y;
        double z = movement.z;
        if (y != 0.0) {
            y = axis(Direction.Axis.Y, box, y, snapshot, boxes);
            if (y != 0.0) box = CollisionUtil.offsetY(box, y);
        }
        final boolean xSmaller = Math.abs(x) < Math.abs(z);
        if (xSmaller && z != 0.0) {
            z = axis(Direction.Axis.Z, box, z, snapshot, boxes);
            if (z != 0.0) box = CollisionUtil.offsetZ(box, z);
        }
        if (x != 0.0) {
            x = axis(Direction.Axis.X, box, x, snapshot, boxes);
            if (!xSmaller && x != 0.0) box = CollisionUtil.offsetX(box, x);
        }
        if (!xSmaller && z != 0.0) z = axis(Direction.Axis.Z, box, z, snapshot, boxes);
        return new Vec3(x, y, z);
    }

    private static double axis(final Direction.Axis axis, final AABB box, final double movement,
                               final AABB[] snapshot, final List<AABB> original) {
        final double[] limits = new double[LeavesXComputeExecutor.computePartitionCapacity()];
        final boolean positive = movement > 0.0;
        final int partitions = LeavesXComputeExecutor.invokeRangesWithCount(
            LeavesXComputeExecutor.Workload.MOB_COLLISION, snapshot.length, MIN_BOXES, MIN_BOXES_PER_WORKER,
            (partition, start, end) -> {
                double limit = movement;
                for (int i = start; i < end; i++) {
                    limit = switch (axis) {
                        case X -> CollisionUtil.collideX(snapshot[i], box, limit);
                        case Y -> CollisionUtil.collideY(snapshot[i], box, limit);
                        case Z -> CollisionUtil.collideZ(snapshot[i], box, limit);
                    };
                    // Contact epsilon can reverse a tiny displacement. That makes folding order-dependent;
                    // reject the whole parallel axis and reproduce the original serial order, including signed zero.
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
}
