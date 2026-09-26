package org.leavesx.leavesx.performance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.leavesx.leavesx.config.ConfigurationTestSupport.publish;

import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;
import org.spongepowered.configurate.CommentedConfigurationNode;

@Normal
class ParallelMobCollisionTest {
    private static final AABB BOX = new AABB(0, 0, 0, 1, 2, 1);

    @AfterEach void cleanup() {
        LeavesXComputeExecutor.shutdown();
        publish(LeavesXConfig.safeDefaults());
    }

    @Test void finiteAndContactCasesMatchSerialBitForBit() {
        LeavesXComputeExecutor.configure(4, 64, true);
        final Random random = new Random(8124);
        final double[] edges = {-1E-7, -1E-9, -0.0, 0.0, 1E-9, 1E-7};
        for (int scenario = 0; scenario < 100; scenario++) {
            final List<AABB> boxes = new ArrayList<>();
            for (int i = 0; i < 8_192; i++) {
                final double x = random.nextDouble() * 20 - 10;
                final double y = random.nextDouble() * 20 - 10;
                final double z = random.nextDouble() * 20 - 10;
                boxes.add(new AABB(x, y, z, x + random.nextDouble(), y + random.nextDouble(), z + random.nextDouble()));
            }
            final double edge = edges[scenario % edges.length];
            boxes.add(new AABB(1 + edge, -2, -2, 2 + edge, 3, 3));
            boxes.add(new AABB(-1 + edge, -2, -2, edge, 3, 3));
            final Vec3 movement = new Vec3(random.nextDouble() * 4 - 2, random.nextDouble() * 4 - 2, random.nextDouble() * 4 - 2);
            sameBits(CollisionUtil.performAABBCollisions(movement, BOX, boxes), ParallelMobCollision.compute(movement, BOX, boxes));
        }
        assertTrue(LeavesXComputeExecutor.metrics().parallelInvocations() > 0, "exercise workers, not only fallback");
    }

    @Test void excludedEntitiesShapesAndDisabledPoolKeepTheOriginalPath() {
        final var config = CommentedConfigurationNode.root();
        config.node("parallelism", "mob-collision-math").raw(true);
        publish(config);
        LeavesXComputeExecutor.configure(4, 64, true);
        final List<AABB> boxes = java.util.Collections.nCopies(8_192, new AABB(2, 0, 0, 3, 2, 1));
        final Vec3 movement = new Vec3(3, -0.0, 1);
        for (Entity entity : List.of(mock(Entity.class), mock(Villager.class))) {
            sameBits(CollisionUtil.performAABBCollisions(movement, BOX, boxes),
                ParallelMobCollision.collide(entity, movement, BOX, List.of(), boxes));
        }
        final var voxels = List.of(Shapes.block());
        sameBits(CollisionUtil.performCollisions(movement, BOX, voxels, boxes),
            ParallelMobCollision.collide(mock(Zombie.class), movement, BOX, voxels, boxes));
        assertEquals(0, LeavesXComputeExecutor.metrics().parallelInvocations());
        LeavesXComputeExecutor.shutdown();
        sameBits(CollisionUtil.performAABBCollisions(movement, BOX, boxes), ParallelMobCollision.compute(movement, BOX, boxes));
    }

    @Test void nonFiniteObstaclesAndSignedZeroUseEquivalentArithmetic() {
        LeavesXComputeExecutor.configure(4, 64, true);
        for (double coordinate : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1E-9, 1E-9}) {
            final List<AABB> boxes = new ArrayList<>(java.util.Collections.nCopies(8_192, new AABB(4, 4, 4, 5, 5, 5)));
            boxes.add(new AABB(coordinate, 0, 0, coordinate, 3, 1));
            for (Vec3 movement : List.of(new Vec3(2, -0.0, 0.0), new Vec3(-2, 1E-9, -1E-9), new Vec3(-0.0, 0.0, -0.0))) {
                sameBits(CollisionUtil.performAABBCollisions(movement, BOX, boxes), ParallelMobCollision.compute(movement, BOX, boxes));
            }
        }
    }

    @Test void enabledLargeMobQueryActuallyReachesWorkers() {
        final var config = CommentedConfigurationNode.root();
        config.node("parallelism", "mob-collision-math").raw(true);
        publish(config);
        LeavesXComputeExecutor.configure(4, 64, true);
        final List<AABB> boxes = java.util.Collections.nCopies(8_192, new AABB(2, 0, 0, 3, 2, 1));
        final Vec3 movement = new Vec3(3, 0, 0);
        sameBits(CollisionUtil.performAABBCollisions(movement, BOX, boxes),
            ParallelMobCollision.collide(mock(Zombie.class), movement, BOX, List.of(), boxes));
        assertTrue(LeavesXComputeExecutor.metrics().parallelInvocations() > 0);
    }

    private static void sameBits(final Vec3 expected, final Vec3 actual) {
        assertEquals(Double.doubleToLongBits(expected.x), Double.doubleToLongBits(actual.x), "x");
        assertEquals(Double.doubleToLongBits(expected.y), Double.doubleToLongBits(actual.y), "y");
        assertEquals(Double.doubleToLongBits(expected.z), Double.doubleToLongBits(actual.z), "z");
    }
}
