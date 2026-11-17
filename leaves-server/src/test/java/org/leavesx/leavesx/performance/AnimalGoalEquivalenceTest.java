package org.leavesx.leavesx.performance;

import static org.junit.jupiter.api.Assertions.*;
import static org.leavesx.leavesx.config.ConfigurationTestSupport.publish;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.ai.goal.FollowParentGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;
import org.spongepowered.configurate.CommentedConfigurationNode;

/** 从真实 Goal 入口与关闭优化的原循环对照，不使用另一份简化实现作期望值。 */
@Normal
public class AnimalGoalEquivalenceTest {
    @AfterEach
    void restore() {
        LeavesXComputeExecutor.shutdown();
        publish(LeavesXConfig.safeDefaults());
    }

    @Test
    void breedingAndFollowParentKeepOriginalSelectionAndBoundaryRules() throws Exception {
        for (final double[] positions : List.of(
            new double[]{4, -4, 9}, new double[]{Double.NaN, 5, -5},
            new double[]{5, Double.NaN, 6}, new double[]{Double.POSITIVE_INFINITY},
            new double[]{Double.POSITIVE_INFINITY, Double.NaN, Double.POSITIVE_INFINITY},
            new double[]{2.999999, 3}, new double[]{3}, new double[]{}
        )) {
            final Fixture fixture = new Fixture();
            for (final double x : positions) fixture.add(x);
            for (final boolean follow : new boolean[]{false, true}) {
                assertSame(fixture.select(follow, false), fixture.select(follow, true));
            }
        }
    }

    @Test
    void stoppedPoolRetainsCompleteOriginalCalculation() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.add(5);
        fixture.add(4);
        final Animal expected = fixture.select(false, false);
        assertEquals(0L, LeavesXComputeExecutor.metrics().invocations());
        LeavesXComputeExecutor.shutdown();
        assertSame(expected, fixture.select(false, true));
    }

    @Test
    void denseGoalQueriesUseWorkersWithoutDroppingCandidates() throws Exception {
        for (final boolean followParent : new boolean[]{false, true}) {
            final Fixture fixture = new Fixture();
            for (int index = 0; index < 512; index++) fixture.add(index % 19 - 9.0);
            final Animal expected = fixture.select(followParent, false);
            final Animal actual = fixture.select(followParent, true);
            assertSame(expected, actual);
            assertTrue(LeavesXComputeExecutor.metrics().workerItems() > 0);
        }
    }

    @Test
    void goalSubclassRetainsCustomDistanceCalls() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.add(4);
        fixture.add(8);
        final BreedGoal custom = new BreedGoal(fixture.source, 1.0) {};
        assertTrue(custom.canUse());
        verify(fixture.source, atLeastOnce()).distanceToSqr(any(Entity.class));
        assertEquals(0L, LeavesXComputeExecutor.metrics().invocations());
    }

    private static Field field(final Class<?> type, final String name) throws Exception {
        final Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static final class Fixture {
        private final Thread caller = Thread.currentThread();
        private final ServerLevel level = mock(ServerLevel.class);
        private final Chicken source = mock(Chicken.class);
        private final List<Chicken> candidates = new ArrayList<>();

        private Fixture() {
            publish(LeavesXConfig.safeDefaults());
            LeavesXComputeExecutor.configure(2, 64, true, true);
            when(this.source.level()).thenReturn(this.level);
            when(this.source.position()).thenReturn(Vec3.ZERO);
            when(this.source.getAge()).thenReturn(-1);
            when(this.source.isInLove()).thenReturn(true);
            when(this.source.getBoundingBox()).thenReturn(new AABB(-0.3, 0, -0.3, 0.3, 1, 0.3));
            when(this.source.distanceToSqr(any(Entity.class))).thenCallRealMethod();
            when(this.source.distanceToSqr(any(Vec3.class))).thenCallRealMethod();
            doReturn(this.candidates).when(this.level).getNearbyEntities(eq(Chicken.class),
                any(TargetingConditions.class), same(this.source), any(AABB.class));
            doReturn(this.candidates).when(this.level).getEntitiesOfClass(eq(Chicken.class), any(AABB.class));
            doAnswer(invocation -> {
                final List<? super Chicken> into = invocation.getArgument(4);
                into.addAll(this.candidates);
                return null;
            }).when(this.level).leavesX$getEntitiesOfClass(eq(Chicken.class), nullable(Entity.class), any(AABB.class),
                any(java.util.function.Predicate.class), anyList());
        }

        private Chicken add(final double x) {
            final Chicken candidate = mock(Chicken.class);
            when(candidate.position()).thenAnswer(ignored -> {
                assertSame(this.caller, Thread.currentThread());
                return new Vec3(x, 0, 0);
            });
            when(candidate.getAge()).thenAnswer(ignored -> {
                assertSame(this.caller, Thread.currentThread());
                return 0;
            });
            when(candidate.isPanicking()).thenAnswer(ignored -> {
                assertSame(this.caller, Thread.currentThread());
                return false;
            });
            when(this.source.canMate(candidate)).thenReturn(true);
            this.candidates.add(candidate);
            return candidate;
        }

        private Animal select(final boolean followParent, final boolean enabled) throws Exception {
            final var node = CommentedConfigurationNode.root();
            node.node("async", "animals", followParent ? "follow-parent" : "breeding").set(enabled);
            publish(node);
            if (followParent) {
                final FollowParentGoal goal = new FollowParentGoal(this.source, 1.0);
                return goal.canUse() ? (Animal) field(FollowParentGoal.class, "parent").get(goal) : null;
            }
            final BreedGoal goal = new BreedGoal(this.source, 1.0);
            return goal.canUse() ? (Animal) field(BreedGoal.class, "partner").get(goal) : null;
        }
    }
}
