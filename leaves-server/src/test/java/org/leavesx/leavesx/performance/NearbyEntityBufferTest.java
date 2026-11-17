package org.leavesx.leavesx.performance;

import static org.junit.jupiter.api.Assertions.*;
import static org.leavesx.leavesx.config.ConfigurationTestSupport.publish;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerEntityGetter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.spongepowered.configurate.CommentedConfigurationNode;

/** 验证复用原始查询列表不会改变判断顺序、排除自身、限制数量或覆盖重入数据。 */
@Normal
public class NearbyEntityBufferTest {
    @Test
    void largeThenSmallQueryStillReleasesThePeakStorageOnClose() {
        final Fixture fixture = new Fixture();
        final Level.LeavesXEntityQueryBuffer buffer = Level.leavesX$acquireEntityQueryBuffer();
        try (buffer) {
            fixture.fill(java.util.Collections.nCopies(16_385, fixture.source));
            assertEquals(16_385, buffer.queryOfClass(fixture.level, Chicken.class, null,
                fixture.box, candidate -> true).size());
            fixture.fill(List.of(fixture.source));
            assertEquals(List.of(fixture.source), buffer.queryOfClass(fixture.level, Chicken.class, null,
                fixture.box, candidate -> true));
        }
        try (Level.LeavesXEntityQueryBuffer replacement = Level.leavesX$acquireEntityQueryBuffer()) {
            assertNotSame(buffer, replacement, "后续小查询不得隐藏同次借用中已分配的大数组");
        }
    }

    @Test
    void oversizedQueryReleasesOnlyItsOwnPooledBuffer() {
        final Fixture fixture = new Fixture();
        fixture.fill(java.util.Collections.nCopies(16_385, fixture.source));
        final Level.LeavesXEntityQueryBuffer outer = Level.leavesX$acquireEntityQueryBuffer();
        try (outer) {
            try (Level.LeavesXEntityQueryBuffer nested = Level.leavesX$acquireEntityQueryBuffer()) {
                assertEquals(16_385, nested.queryOfClass(fixture.level, Chicken.class, null,
                    fixture.box, candidate -> true).size());
            }
            // 嵌套大查询关闭后，外层仍被占用，不能把它重新交给另一个查询。
            try (Level.LeavesXEntityQueryBuffer nested = Level.leavesX$acquireEntityQueryBuffer()) {
                assertNotSame(outer, nested);
            }
        }
        try (Level.LeavesXEntityQueryBuffer reused = Level.leavesX$acquireEntityQueryBuffer()) {
            assertSame(outer, reused);
            assertEquals(16_385, reused.queryOfClass(fixture.level, Chicken.class, null,
                fixture.box, candidate -> true).size());
        }
        try (Level.LeavesXEntityQueryBuffer replacement = Level.leavesX$acquireEntityQueryBuffer()) {
            assertNotSame(outer, replacement);
        }
    }

    @AfterEach
    void restore() {
        publish(LeavesXConfig.safeDefaults());
    }

    @Test
    void fullQueryAndPredicateOrderRemainIdenticalAndReturnedListSurvivesBufferReuse() {
        final Fixture fixture = new Fixture();
        final Chicken first = mock(Chicken.class);
        final Chicken second = mock(Chicken.class);
        fixture.fill(List.of(fixture.source, first, second));
        when(fixture.conditions.test(fixture.level, fixture.source, first)).thenReturn(true);
        when(fixture.conditions.test(fixture.level, fixture.source, second)).thenAnswer(ignored -> {
            try (Level.LeavesXEntityQueryBuffer nested = Level.leavesX$acquireEntityQueryBuffer()) {
                assertEquals(List.of(fixture.source, first, second), nested.queryOfClass(
                    fixture.level, Chicken.class, null, fixture.box, candidate -> true));
            }
            return true;
        });
        final List<Chicken> result = fixture.query();
        assertEquals(List.of(first, second), result);
        final var calls = inOrder(fixture.conditions);
        calls.verify(fixture.conditions).test(fixture.level, fixture.source, fixture.source);
        calls.verify(fixture.conditions).test(fixture.level, fixture.source, first);
        calls.verify(fixture.conditions).test(fixture.level, fixture.source, second);
        fixture.fill(List.of());
        assertTrue(fixture.query().isEmpty());
        assertEquals(List.of(first, second), result);
    }

    @Test
    void villagerSourceAndDisabledSwitchUseOriginalQuery() {
        final Fixture fixture = new Fixture();
        final Villager villager = mock(Villager.class);
        doReturn(List.of()).when(fixture.level).getEntitiesOfClass(eq(Chicken.class), same(fixture.box), any());
        assertTrue(fixture.level.getNearbyEntities(Chicken.class, fixture.conditions, villager, fixture.box).isEmpty());
        verify(fixture.level).getEntitiesOfClass(eq(Chicken.class), same(fixture.box), any());
        clearInvocations(fixture.level);
        final var node = CommentedConfigurationNode.root();
        node.node("performance", "entities", "queries").raw(false);
        publish(node);
        assertTrue(fixture.query().isEmpty());
        verify(fixture.level).getEntitiesOfClass(eq(Chicken.class), same(fixture.box), any());
    }

    @Test
    void nearestQueryPreservesTiesAndNestedQueries() {
        final Fixture fixture = new Fixture();
        final Chicken first = mock(Chicken.class);
        final Chicken second = mock(Chicken.class);
        fixture.fill(List.of(first, second));
        doCallRealMethod().when(fixture.level).getNearestEntity(eq(Chicken.class), same(fixture.conditions),
            any(LivingEntity.class), anyDouble(), anyDouble(), anyDouble(), same(fixture.box));
        doCallRealMethod().when(fixture.level).getNearestEntity(anyList(), same(fixture.conditions),
            any(LivingEntity.class), anyDouble(), anyDouble(), anyDouble());
        when(first.distanceToSqr(0.0, 0.0, 0.0)).thenReturn(4.0);
        when(second.distanceToSqr(0.0, 0.0, 0.0)).thenReturn(4.0);
        when(fixture.conditions.test(fixture.level, fixture.source, first)).thenReturn(true);
        when(fixture.conditions.test(fixture.level, fixture.source, second)).thenAnswer(ignored -> {
            try (Level.LeavesXEntityQueryBuffer nested = Level.leavesX$acquireEntityQueryBuffer()) {
                assertEquals(List.of(first, second), nested.queryOfClass(
                    fixture.level, Chicken.class, null, fixture.box, candidate -> true));
            }
            return true;
        });
        assertSame(first, fixture.level.getNearestEntity(Chicken.class, fixture.conditions, fixture.source,
            0.0, 0.0, 0.0, fixture.box));
        final var calls = inOrder(fixture.conditions);
        calls.verify(fixture.conditions).test(fixture.level, fixture.source, first);
        calls.verify(fixture.conditions).test(fixture.level, fixture.source, second);
        verify(fixture.level, never()).getEntitiesOfClass(eq(Chicken.class), same(fixture.box), any());
    }

    @Test
    void nearestQueryFallsBackForVillagersAndDisabledOptimization() {
        final Fixture fixture = new Fixture();
        doCallRealMethod().when(fixture.level).getNearestEntity(eq(Chicken.class), same(fixture.conditions),
            any(LivingEntity.class), anyDouble(), anyDouble(), anyDouble(), same(fixture.box));
        doReturn(List.of()).when(fixture.level).getEntitiesOfClass(eq(Chicken.class), same(fixture.box), any());
        fixture.level.getNearestEntity(Chicken.class, fixture.conditions, mock(Villager.class),
            0.0, 0.0, 0.0, fixture.box);
        verify(fixture.level).getEntitiesOfClass(eq(Chicken.class), same(fixture.box), any());
        clearInvocations(fixture.level);
        final var node = CommentedConfigurationNode.root();
        node.node("performance", "entities", "queries").raw(false);
        publish(node);
        fixture.level.getNearestEntity(Chicken.class, fixture.conditions, fixture.source,
            0.0, 0.0, 0.0, fixture.box);
        verify(fixture.level).getEntitiesOfClass(eq(Chicken.class), same(fixture.box), any());
        verify(fixture.level, never()).leavesX$getEntitiesOfClass(any(), any(), any(), any(), anyList());
    }

    @Test
    void customGetterUsesItsOwnQueryEvenWhenItReturnsAnotherLevel() {
        publish(LeavesXConfig.safeDefaults());
        final ServerEntityGetter getter = mock(ServerEntityGetter.class, CALLS_REAL_METHODS);
        final ServerLevel level = mock(ServerLevel.class);
        final AABB box = new AABB(0, 0, 0, 1, 1, 1);
        when(getter.getLevel()).thenReturn(level);
        when(getter.getEntitiesOfClass(eq(Chicken.class), same(box), any())).thenReturn(List.of());
        assertTrue(getter.getNearbyEntities(Chicken.class, mock(TargetingConditions.class), mock(Chicken.class), box).isEmpty());
        verify(getter).getEntitiesOfClass(eq(Chicken.class), same(box), any());
    }

    private static final class Fixture {
        private final ServerLevel level = mock(ServerLevel.class);
        private final Chicken source = mock(Chicken.class);
        private final TargetingConditions conditions = mock(TargetingConditions.class);
        private final AABB box = new AABB(-8, -8, -8, 8, 8, 8);

        private Fixture() {
            publish(LeavesXConfig.safeDefaults());
            when(this.level.getLevel()).thenReturn(this.level);
            when(this.level.getNearbyEntities(eq(Chicken.class), same(this.conditions), any(LivingEntity.class), same(this.box)))
                .thenCallRealMethod();
        }

        private void fill(final List<Chicken> candidates) {
            doAnswer(invocation -> {
                assertNull(invocation.getArgument(1));
                final List<? super Chicken> into = invocation.getArgument(4);
                into.addAll(candidates);
                return null;
            }).when(this.level).leavesX$getEntitiesOfClass(eq(Chicken.class), nullable(Entity.class), same(this.box),
                any(Predicate.class), anyList());
        }

        private List<Chicken> query() {
            return this.level.getNearbyEntities(Chicken.class, this.conditions, this.source, this.box);
        }
    }
}
