package org.leavesx.leavesx.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.damage.CraftDamageSource;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.entity.CraftExperienceOrb;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 测试真实事件边界与采样隔离，不用诊断计数替代猪灵塔的实机复现。 */
@Normal
public class ExperienceDiagnosticsTest {
    @AfterEach
    void stop() { ExperienceDiagnostics.stop(); }

    @Test
    void disabledSamplingDoesNotReadEntities() {
        ExperienceDiagnostics.stop();
        final LivingEntity victim = mock(LivingEntity.class);
        final DamageSource source = mock(DamageSource.class);
        final EntityDeathEvent event = mock(EntityDeathEvent.class);
        assertNull(ExperienceDiagnostics.beforeDeath(victim, source, event));
        ExperienceDiagnostics.beforeDrop(victim);
        verifyNoInteractions(victim, source, event);
    }

    @Test
    void scopeExpiresAndDoesNotFollowOtherWorldsOrPositions() {
        final AtomicLong clock = new AtomicLong();
        ExperienceDiagnostics.start(Level.NETHER, 0, 64, 0, 10, clock::get);
        final ServerLevel nether = level(Level.NETHER);
        assertNotNull(ExperienceDiagnostics.capture(nether, new Vec3(64, 64, 0)));
        assertNull(ExperienceDiagnostics.capture(nether, new Vec3(65, 64, 0)));
        assertNull(ExperienceDiagnostics.capture(level(Level.OVERWORLD), new Vec3(0, 64, 0)));
        assertThrows(IllegalStateException.class, () -> ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60));
        clock.set(TimeUnit.SECONDS.toNanos(10));
        assertNull(ExperienceDiagnostics.capture(nether, new Vec3(0, 64, 0)));
        assertFalse(ExperienceDiagnostics.report().running());
    }

    @Test
    void invalidDurationOrPositionCannotStartSampling() {
        for (final int seconds : new int[]{0, 9, 301, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                () -> ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, seconds));
        }
        assertThrows(IllegalArgumentException.class,
            () -> ExperienceDiagnostics.start(Level.NETHER, Double.NaN, 0, 0, 60));
        assertThrows(IllegalArgumentException.class,
            () -> ExperienceDiagnostics.start(Level.NETHER, 0, Double.POSITIVE_INFINITY, 0, 60));
    }

    @Test
    void recordsActualPluginEventChangeWithoutChangingTheResult() {
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
        final ServerLevel level = level(Level.NETHER);
        final LivingEntity victim = victim(level);
        final CraftLivingEntity craft = mock(CraftLivingEntity.class);
        final CraftWorld world = mock(CraftWorld.class);
        when(victim.getBukkitEntity()).thenReturn(craft);
        when(craft.getWorld()).thenReturn(world);
        when(world.getHandle()).thenReturn(level);
        when(victim.getExpReward(any(), any())).thenReturn(5);
        when(victim.getExperienceReward(any(), any())).thenReturn(5);
        when(victim.getSoundSource()).thenReturn(net.minecraft.sounds.SoundSource.HOSTILE);
        final var attribute = mock(net.minecraft.world.entity.ai.attributes.AttributeInstance.class);
        when(victim.getAttribute(any())).thenReturn(attribute);
        when(attribute.getValue()).thenReturn(20.0);
        final var maxHealth = mock(org.bukkit.attribute.AttributeInstance.class);
        when(craft.getAttribute(any())).thenReturn(maxHealth);
        when(maxHealth.getValue()).thenReturn(20.0);
        final DamageSource damage = mock(DamageSource.class);
        when(damage.getMsgId()).thenReturn("player");
        final PluginManager plugins = mock(PluginManager.class);
        doAnswer(call -> { ((EntityDeathEvent) call.getArgument(0)).setDroppedExp(0); return null; })
            .when(plugins).callEvent(any(EntityDeathEvent.class));
        final Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(plugins);
        try (var bukkit = mockStatic(Bukkit.class); var damageBridge = mockConstruction(CraftDamageSource.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            final var event = CraftEventFactory.callEntityDeathEvent(level, victim, damage, new ArrayList<>(), false);
            assertEquals(0, event.getDroppedExp());
            assertEquals(0, victim.expToDrop);
            verify(plugins, times(1)).callEvent(event);
            assertEquals(5, row(ExperienceDiagnostics.Stage.EVENT_REDUCED).input());
            assertEquals(0, row(ExperienceDiagnostics.Stage.EVENT_REDUCED).output());
        }
    }

    @Test
    void deathCancellationAndConsumedExperienceRemainDistinct() {
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
        final LivingEntity victim = victim(level(Level.NETHER));
        final EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getDroppedExp()).thenReturn(5);
        final var trace = ExperienceDiagnostics.beforeDeath(victim, mock(DamageSource.class), event);
        when(event.isCancelled()).thenReturn(true);
        ExperienceDiagnostics.afterDeath(trace, event);
        victim.expToDrop = 5;
        when(victim.wasExperienceConsumed()).thenReturn(true);
        ExperienceDiagnostics.beforeDrop(victim);
        assertEquals(1, row(ExperienceDiagnostics.Stage.DEATH_CANCELLED).calls());
        assertEquals(5, row(ExperienceDiagnostics.Stage.CONSUMED).input());
        assertEquals(5, victim.expToDrop);
        verify(victim, never()).skipDropExperience();
    }

    @Test
    void spawnEventCancellationIsObservedWithoutOverridingPluginChoice() {
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
        final ExperienceOrb orb = mock(ExperienceOrb.class);
        final CraftExperienceOrb craft = mock(CraftExperienceOrb.class);
        final Server server = mock(Server.class);
        final PluginManager plugins = mock(PluginManager.class);
        final ServerLevel nether = level(Level.NETHER);
        when(orb.level()).thenReturn(nether);
        when(orb.getBukkitEntity()).thenReturn(craft);
        when(orb.getValue()).thenReturn(5);
        orb.count = 3;
        when(craft.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(plugins);
        doAnswer(call -> { ((EntitySpawnEvent) call.getArgument(0)).setCancelled(true); return null; })
            .when(plugins).callEvent(any(EntitySpawnEvent.class));
        final EntitySpawnEvent event = CraftEventFactory.callEntitySpawnEvent(orb);
        assertTrue(event.isCancelled());
        assertEquals(15, row(ExperienceDiagnostics.Stage.SPAWN_CANCELLED).input());
        assertEquals(0, row(ExperienceDiagnostics.Stage.SPAWN_CANCELLED).output());
        verify(plugins).callEvent(event);
    }

    @Test
    void nonOperatorCannotStartStopOrReadDiagnostics() {
        final var sender = mock(org.bukkit.command.CommandSender.class);
        org.leavesx.leavesx.command.ExperienceDiagnosticCommand.execute(sender, new String[]{"xp", "start"});
        verify(sender).isOp();
        verifyNoMoreInteractions(sender);
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
        org.leavesx.leavesx.command.ExperienceDiagnosticCommand.execute(sender, new String[]{"xp", "stop"});
        assertTrue(ExperienceDiagnostics.report().running());
    }

    @Test
    void stoppedSessionCannotWriteIntoANewSession() {
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
        final var old = ExperienceDiagnostics.capture(level(Level.NETHER), Vec3.ZERO);
        ExperienceDiagnostics.stop();
        ExperienceDiagnostics.start(Level.OVERWORLD, 0, 0, 0, 60);
        ExperienceDiagnostics.awarded(old, null, 5, null, true);
        assertTrue(ExperienceDiagnostics.report().rows().isEmpty());
    }

    @Test
    void denseSamplingRetainsBoundedDetailsAndCountsAllAwards() {
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
        final var session = ExperienceDiagnostics.capture(level(Level.NETHER), Vec3.ZERO);
        final ExperienceOrb orb = mock(ExperienceOrb.class);
        when(orb.getUUID()).thenReturn(UUID.randomUUID());
        for (int i = 0; i < 10_000; i++) ExperienceDiagnostics.awarded(session, null, 5, orb, false);
        final var report = ExperienceDiagnostics.report();
        assertEquals(1, report.rows().size());
        assertEquals(10_000, report.rows().getFirst().calls());
        assertEquals(50_000, report.rows().getFirst().input());
        assertEquals(0, report.rows().getFirst().output());
        verify(orb, times(1)).getUUID();
    }

    static ServerLevel level(final net.minecraft.resources.ResourceKey<Level> key) {
        final ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(key);
        return level;
    }

    private static LivingEntity victim(final ServerLevel level) {
        final LivingEntity victim = mock(LivingEntity.class);
        when(victim.level()).thenReturn(level);
        when(victim.getUUID()).thenReturn(UUID.randomUUID());
        victim.lastHurtByPlayerMemoryTime = 100;
        return victim;
    }

    static ExperienceDiagnostics.Row row(final ExperienceDiagnostics.Stage stage) {
        return ExperienceDiagnostics.report().rows().stream().filter(row -> row.stage() == stage).findFirst().orElseThrow();
    }
}
