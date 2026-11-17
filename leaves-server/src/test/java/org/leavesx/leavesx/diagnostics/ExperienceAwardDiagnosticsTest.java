package org.leavesx.leavesx.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftExperienceOrb;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerExpCooldownChangeEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesx.leavesx.config.LeavesXRuntime;

/** 使用真实 awardWithDirection/playerTouch，验证采样不改变经验分拆、合并与事件处理。 */
@Normal
public class ExperienceAwardDiagnosticsTest {
    @AfterEach
    void stop() { ExperienceDiagnostics.stop(); }

    @Test
    void bothQuerySwitchesPreserveDenseAwardTotal() {
        for (final boolean queries : new boolean[]{false, true}) {
            for (final boolean parameters : new boolean[]{false, true}) {
                ExperienceDiagnostics.stop();
                ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
                final var level = ExperienceDiagnosticsTest.level(Level.NETHER);
                when(level.getRandom()).thenReturn(RandomSource.create(1234L));
                final ExperienceOrb existing = mock(ExperienceOrb.class);
                existing.count = 1;
                when(level.leavesX$getFirstEntity(any(EntityTypeTest.class), any(net.minecraft.world.phys.AABB.class), any())).thenReturn(existing);
                when(level.getEntities(any(EntityTypeTest.class), any(net.minecraft.world.phys.AABB.class), any())).thenReturn(List.of(existing));
                final GlobalConfiguration config = new GlobalConfiguration();
                config.misc = config.new Misc();
                try (var runtime = mockStatic(LeavesXRuntime.class); var global = mockStatic(GlobalConfiguration.class)) {
                    runtime.when(LeavesXRuntime::experienceOrbMergeOptimization).thenReturn(queries);
                    runtime.when(LeavesXRuntime::experienceOrbMergeParametersOptimization).thenReturn(parameters);
                    global.when(GlobalConfiguration::get).thenReturn(config);
                    // 每次奖励 1 点，避免用固定返回同一个球的测试替身伪造不同面值合并。
                    for (int i = 0; i < 1000; i++) ExperienceOrb.award(level, Vec3.ZERO, 1);
                    assertEquals(1001, existing.count);
                    assertEquals(1000, ExperienceDiagnosticsTest.row(ExperienceDiagnostics.Stage.MERGED).output());
                    verify(level, never()).addFreshEntity(any());
                }
            }
        }
    }

    @Test
    void recordsFailedAddsWithoutRetryingOrManufacturingExperience() {
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
        final var level = ExperienceDiagnosticsTest.level(Level.NETHER);
        when(level.getRandom()).thenReturn(RandomSource.create(1234L));
        when(level.getEntities(any(EntityTypeTest.class), any(net.minecraft.world.phys.AABB.class), any())).thenReturn(List.of());
        final GlobalConfiguration config = new GlobalConfiguration();
        config.misc = config.new Misc();
        final List<Integer> constructedValues = new ArrayList<>();
        try (var runtime = mockStatic(LeavesXRuntime.class); var global = mockStatic(GlobalConfiguration.class);
             var orbs = mockConstruction(ExperienceOrb.class, (orb, context) -> {
                 final int value = (int) context.arguments().get(3);
                 constructedValues.add(value);
                 when(orb.getValue()).thenReturn(value);
                 orb.count = 1;
             })) {
            global.when(GlobalConfiguration::get).thenReturn(config);
            ExperienceOrb.award(level, Vec3.ZERO, 5);
            assertEquals(5, constructedValues.stream().mapToInt(Integer::intValue).sum());
            assertEquals(5, ExperienceDiagnosticsTest.row(ExperienceDiagnostics.Stage.REJECTED).input());
            assertEquals(0, ExperienceDiagnosticsTest.row(ExperienceDiagnostics.Stage.REJECTED).output());
            verify(level, times(constructedValues.size())).addFreshEntity(any());
        }
    }

    @Test
    void pickupEventReductionIsObservedInNormalAndFastModes() {
        final var previous = LeavesConfig.modify.fastAbsorbPredicate;
        try {
            for (final boolean fast : new boolean[]{false, true}) {
                ExperienceDiagnostics.stop();
                ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
                LeavesConfig.modify.fastAbsorbPredicate = player -> fast;
                try (var bukkit = mockStatic(Bukkit.class); var events = mockStatic(CraftEventFactory.class);
                     var enchantments = mockStatic(EnchantmentHelper.class)) {
                    bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
                    final ServerPlayer player = mock(ServerPlayer.class);
                    final CraftPlayer craft = mock(CraftPlayer.class);
                    when(player.getBukkitEntity()).thenReturn(craft);
                    final ExperienceOrb orb = mock(ExperienceOrb.class);
                    when(orb.getBukkitEntity()).thenReturn(mock(CraftExperienceOrb.class));
                    final var pickupLevel = ExperienceDiagnosticsTest.level(Level.NETHER);
                    when(orb.level()).thenReturn(pickupLevel);
                    when(orb.getValue()).thenReturn(5);
                    orb.count = 3;
                    doCallRealMethod().when(orb).playerTouch(player);
                    events.when(() -> CraftEventFactory.callPlayerXpCooldownEvent(eq(player), anyInt(), any()))
                        .thenAnswer(call -> new PlayerExpCooldownChangeEvent(craft, call.getArgument(1), call.getArgument(2)));
                    events.when(() -> CraftEventFactory.callPlayerExpChangeEvent(player, orb, 5))
                        .thenReturn(new PlayerExpChangeEvent(craft, 0));
                    enchantments.when(() -> EnchantmentHelper.getRandomItemWith(any(), eq(player), any()))
                        .thenReturn(Optional.empty());
                    orb.playerTouch(player);
                    verify(player, times(fast ? 3 : 1)).giveExperiencePoints(0);
                    assertEquals(fast ? 0 : 2, orb.count);
                    assertEquals(fast ? 15 : 5, ExperienceDiagnosticsTest.row(ExperienceDiagnostics.Stage.PICKUP_REDUCED).input());
                    assertEquals(0, ExperienceDiagnosticsTest.row(ExperienceDiagnostics.Stage.PICKUP).output());
                }
            }
        } finally {
            LeavesConfig.modify.fastAbsorbPredicate = previous;
        }
    }

    @Test
    void cancelledPickupDoesNotConsumeOrbOrGrantExperience() {
        ExperienceDiagnostics.start(Level.NETHER, 0, 0, 0, 60);
        final var previous = LeavesConfig.modify.fastAbsorbPredicate;
        try (var bukkit = mockStatic(Bukkit.class)) {
            final PluginManager plugins = mock(PluginManager.class);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            doAnswer(call -> {
                ((com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent) call.getArgument(0)).setCancelled(true);
                return null;
            }).when(plugins).callEvent(any(com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent.class));
            final ServerPlayer player = mock(ServerPlayer.class);
            when(player.getBukkitEntity()).thenReturn(mock(CraftPlayer.class));
            for (final boolean fast : new boolean[]{false, true}) {
                LeavesConfig.modify.fastAbsorbPredicate = candidate -> fast;
                final ExperienceOrb orb = mock(ExperienceOrb.class);
                final var nether = ExperienceDiagnosticsTest.level(Level.NETHER);
                when(orb.level()).thenReturn(nether);
                when(orb.getBukkitEntity()).thenReturn(mock(CraftExperienceOrb.class));
                when(orb.getValue()).thenReturn(5);
                orb.count = 3;
                doCallRealMethod().when(orb).playerTouch(player);
                orb.playerTouch(player);
                assertEquals(3, orb.count);
                verify(player, never()).giveExperiencePoints(anyInt());
            }
            assertEquals(2, ExperienceDiagnosticsTest.row(ExperienceDiagnostics.Stage.PICKUP_CANCELLED).calls());
        } finally {
            LeavesConfig.modify.fastAbsorbPredicate = previous;
        }
    }
}
