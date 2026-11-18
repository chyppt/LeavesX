package org.leavesx.leavesx.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;

/** 在重复类之后插入自定义 position，验证最近目标和稳定排序都整次保留虚方法语义。 */
@Normal
public class SorterTypeCacheTest {
    @AfterEach
    void shutdown() { LeavesXComputeExecutor.shutdown(); }

    @Test
    void customPositionAtTailCannotBeSkippedAfterSameOrAlternatingClasses() {
        final Entity origin = mock(Zombie.class);
        when(origin.distanceToSqr(any(Entity.class))).thenCallRealMethod();
        when(origin.distanceToSqr(any(Vec3.class))).thenCallRealMethod();
        final Entity ordinary = candidate(Zombie.class, 2.0);
        final Entity inherited = candidate(InheritedZombie.class, 3.0);
        final Entity custom = candidate(PositionZombie.class, 1.0);
        when(custom.position()).thenReturn(new Vec3(100.0, 0, 0));
        LeavesXComputeExecutor.configure(2, 64, true, true);
        for (final boolean alternate : new boolean[] {false, true}) {
            final ArrayList<Entity> values = new ArrayList<>();
            for (int index = 0; index < 512; index++) values.add(alternate && index % 2 == 1 ? inherited : ordinary);
            values.add(custom);
            assertEquals(0, StableDistanceSorter.findNearestIndex(values, origin, true, 64));
            StableDistanceSorter.sortEntitiesByDistance(values, origin, true, 64);
            assertSame(ordinary, values.get(0));
            assertSame(custom, values.get(values.size() - 1));
            assertEquals(0L, LeavesXComputeExecutor.metrics().invocations());
        }
    }

    @Test
    void aLaterQueryCannotReuseAnEarlierClassDecisionForANewOverride() {
        final Entity origin = mock(Zombie.class);
        when(origin.distanceToSqr(any(Entity.class))).thenCallRealMethod();
        when(origin.distanceToSqr(any(Vec3.class))).thenCallRealMethod();
        final Entity ordinary = candidate(Zombie.class, 4.0);
        final Entity custom = candidate(PositionZombie.class, 1.0);
        when(custom.position()).thenReturn(new Vec3(9.0, 0, 0));
        LeavesXComputeExecutor.configure(1, 64, true, true);
        assertEquals(0, StableDistanceSorter.findNearestIndex(List.of(ordinary), origin, true, 64));
        final long before = LeavesXComputeExecutor.metrics().invocations();
        assertEquals(1, StableDistanceSorter.findNearestIndex(List.of(custom, ordinary), origin, true, 64));
        assertEquals(before, LeavesXComputeExecutor.metrics().invocations());
    }

    private static Entity candidate(final Class<? extends Zombie> type, final double x) {
        final Entity entity = mock(type);
        when(entity.getX()).thenReturn(x);
        when(entity.position()).thenReturn(new Vec3(x, 0, 0));
        return entity;
    }

    private static class InheritedZombie extends Zombie {
        InheritedZombie() { super(((EntityType<Zombie>) net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("zombie"))), null); }
    }

    private static class PositionZombie extends Zombie {
        PositionZombie() { super(((EntityType<Zombie>) net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("zombie"))), null); }
        @Override public Vec3 position() { return new Vec3(100.0, 0, 0); }
    }
}
