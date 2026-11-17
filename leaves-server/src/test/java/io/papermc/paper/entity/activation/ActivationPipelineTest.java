package io.papermc.paper.entity.activation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfigLoader;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;
import org.spongepowered.configurate.CommentedConfigurationNode;

/** 从真实采集入口验证扩容、激活边界和所有者线程约束，不只测试数值内核。 */
@Normal
public class ActivationPipelineTest {
    @AfterEach
    void restore() throws Exception {
        LeavesXComputeExecutor.shutdown();
        final var publish = LeavesXRuntime.class.getDeclaredMethod("publish", org.leavesx.leavesx.config.LeavesXConfig.class);
        publish.setAccessible(true);
        publish.invoke(null, org.leavesx.leavesx.config.LeavesXConfig.safeDefaults());
    }

    @Test
    void worldPipelineMatchesSerialWithUnderestimatedEntityCount() throws Exception {
        final Thread owner = Thread.currentThread();
        final Entity near = entity(Entity.class, ActivationType.VILLAGER, 15.0, owner);
        final Entity touching = entity(Entity.class, ActivationType.MONSTER, 17.0, owner);
        final Entity distant = entity(Entity.class, ActivationType.ANIMAL, 1000.0, owner);
        final Entity marker = entity(Marker.class, ActivationType.MISC, 0.0, owner);
        final Entity defaultEntity = entity(Entity.class, ActivationType.MISC, 30.0, owner);
        defaultActivation(defaultEntity);
        final Entity[] unique = {near, touching, distant, marker, defaultEntity};
        final List<Entity> entities = new ArrayList<>();
        for (int index = 0; index < 4097; index++) entities.add(unique[index % unique.length]);
        final ServerLevel world = mock(ServerLevel.class);
        when(world.getEntityCount()).thenReturn(2048); // 触发已发布输出数组的扩容复制。
        when(world.getAllEntities()).thenReturn(entities);
        final Player first = player(0.0, false, owner);
        final Player second = player(-100.0, false, owner);
        final Player spectator = player(1000.0, true, owner);
        for (final boolean pipeline : new boolean[] {false, true}) {
            configure(pipeline);
            for (final Entity entity : unique) entity.activatedTick = 0;
            assertTrue(ActivationRangeSnapshot.activateWorld(world, List.of(first, second, spectator), true, false,
                10L, 256, 32, 16, 16, 16, 16, 16, 16, 16));
            assertEquals(10L, near.activatedTick);
            assertEquals(0L, touching.activatedTick, "仅接触激活框边界不能激活");
            assertEquals(0L, distant.activatedTick, "旁观者不得扩展激活范围");
            assertEquals(0L, marker.activatedTick);
            assertEquals(10L, defaultEntity.activatedTick);
            assertTrue(LeavesXComputeExecutor.metrics().workerItems() > 0);
        }
    }

    @Test
    void completedAndFailedActivationReleaseEntityReferencesBeforeWorkspaceReuse() throws Exception {
        configure(true);
        final Thread owner = Thread.currentThread();
        final Entity near = entity(Entity.class, ActivationType.MONSTER, 0, owner);
        final ServerLevel world = mock(ServerLevel.class);
        when(world.getEntityCount()).thenReturn(2_048);
        final List<Entity> entities = new ArrayList<>(java.util.Collections.nCopies(2_049, near));
        when(world.getAllEntities()).thenReturn(entities);
        final List<Player> players = List.of(player(0, false, owner), player(10, false, owner));
        final var workspaceField = ActivationRangeSnapshot.class.getDeclaredField("WORKSPACE");
        workspaceField.setAccessible(true);
        final ThreadLocal<?> workspaces = (ThreadLocal<?>)workspaceField.get(null);
        assertTrue(ActivationRangeSnapshot.activateWorld(world, players, false, false,
            10, 256, 32, 16, 16, 16, 16, 16, 16, 16));
        assertReleased(workspaces.get());
        final Entity failing = entity(Entity.class, ActivationType.MONSTER, 0, owner);
        when(failing.getBoundingBox()).thenThrow(new IllegalStateException("capture failure"));
        entities.set(entities.size() - 1, failing);
        assertThrows(IllegalStateException.class, () -> ActivationRangeSnapshot.activateWorld(world, players, false, false,
            11, 256, 32, 16, 16, 16, 16, 16, 16, 16));
        assertReleased(workspaces.get());
        entities.set(entities.size() - 1, near);
        assertTrue(ActivationRangeSnapshot.activateWorld(world, players, false, false,
            12, 256, 32, 16, 16, 16, 16, 16, 16, 16));
        assertEquals(12L, near.activatedTick);
        assertReleased(workspaces.get());
    }

    private static void assertReleased(final Object workspace) throws Exception {
        final var entitiesField = workspace.getClass().getDeclaredField("entities");
        entitiesField.setAccessible(true);
        for (final Object entity : (Object[])entitiesField.get(workspace)) assertNull(entity);
    }

    @Test
    void playerCandidatePipelineMatchesPaperActivationRules() throws Exception {
        final Thread owner = Thread.currentThread();
        final Entity near = entity(Entity.class, ActivationType.VILLAGER, 15.0, owner);
        final Entity touching = entity(Entity.class, ActivationType.MONSTER, 16.0, owner);
        final Entity defaultEntity = entity(Entity.class, ActivationType.ANIMAL, 1000.0, owner);
        defaultActivation(defaultEntity);
        final Entity marker = entity(Marker.class, ActivationType.MISC, 0.0, owner);
        final List<Entity> candidates = new ArrayList<>();
        final Entity[] unique = {near, touching, defaultEntity, marker};
        for (int index = 0; index < 1027; index++) candidates.add(unique[index % unique.length]);
        final AABB[] original = new AABB[ActivationType.values().length];
        try {
            for (final ActivationType type : ActivationType.values()) {
                original[type.ordinal()] = type.boundingBox;
                type.boundingBox = new AABB(-16, -16, -16, 16, 16, 16);
            }
            for (final boolean pipeline : new boolean[] {false, true}) {
                configure(pipeline);
                for (final Entity entity : unique) entity.activatedTick = 0L;
                assertTrue(ActivationRangeSnapshot.activateEntities(candidates, false, 10L));
                assertEquals(10L, near.activatedTick);
                assertEquals(0L, touching.activatedTick);
                assertEquals(10L, defaultEntity.activatedTick);
                assertEquals(0L, marker.activatedTick);
            }
        } finally {
            for (final ActivationType type : ActivationType.values()) type.boundingBox = original[type.ordinal()];
        }
    }

    private static void configure(final boolean pipeline) throws Exception {
        final var node = CommentedConfigurationNode.root();
        node.node("parallelism", "entity-activation", "snapshot-pipeline").set(pipeline);
        final var load = LeavesXConfigLoader.class.getDeclaredMethod("fromNode", org.spongepowered.configurate.ConfigurationNode.class, Path.class);
        load.setAccessible(true);
        final Object config = load.invoke(null, node, Path.of("activation-test.yml"));
        final var publish = LeavesXRuntime.class.getDeclaredMethod("publish", config.getClass());
        publish.setAccessible(true);
        publish.invoke(null, config);
        LeavesXComputeExecutor.configure(4, 64, true, true);
    }

    private static Entity entity(final Class<? extends Entity> kind, final ActivationType type,
                                 final double x, final Thread owner) throws Exception {
        final Entity entity = mock(kind);
        final var field = Entity.class.getField("activationType");
        field.setAccessible(true);
        field.set(entity, type);
        when(entity.getBoundingBox()).thenAnswer(invocation -> {
            assertSame(owner, Thread.currentThread(), "工作线程不得读取实体状态");
            return new AABB(x, -1, -1, x + 1, 1, 1);
        });
        return entity;
    }

    private static void defaultActivation(final Entity entity) throws Exception {
        final var field = Entity.class.getField("defaultActivationState");
        field.setAccessible(true);
        field.setBoolean(entity, true);
    }

    private static Player player(final double x, final boolean spectator, final Thread owner) {
        final Player player = mock(Player.class);
        when(player.isSpectator()).thenReturn(spectator);
        when(player.getBoundingBox()).thenAnswer(invocation -> {
            assertSame(owner, Thread.currentThread());
            return new AABB(x - 1, -1, -1, x + 1, 1, 1);
        });
        return player;
    }
}
