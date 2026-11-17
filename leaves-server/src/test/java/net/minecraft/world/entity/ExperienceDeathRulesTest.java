package net.minecraft.world.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.sensing.Sensing;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.level.gamerules.GameRules;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXRuntime;

/** 直接执行 AI 调度和经验资格判定；不把此测试等同于完整猪灵塔复现。 */
@Normal
public class ExperienceDeathRulesTest {
    @Test
    void denseGoalDeferralStillRunsCustomAiAndKeepsPlayerReward() throws Exception {
        for (final boolean dense : new boolean[]{false, true}) {
            final ServerLevel level = mock(ServerLevel.class);
            final GameRules rules = mock(GameRules.class);
            when(level.getGameRules()).thenReturn(rules);
            when(rules.get(GameRules.MOB_DROPS)).thenReturn(true);
            final Mob mob = mock(ZombifiedPiglin.class);
            mob.aware = true;
            mob.tickCount = 4;
            mob.lastHurtByPlayerMemoryTime = 100;
            when(mob.getId()).thenReturn(2);
            when(mob.level()).thenReturn(level);
            when(mob.shouldDropExperience()).thenReturn(true);
            when(mob.getExperienceReward(any(), any())).thenReturn(5);
            mob.goalSelector = mock(GoalSelector.class);
            mob.targetSelector = mock(GoalSelector.class);
            mob.navigation = mock(PathNavigation.class);
            mob.moveControl = mock(MoveControl.class);
            mob.lookControl = mock(LookControl.class);
            mob.jumpControl = mock(JumpControl.class);
            setMobField(mob, "sensing", mock(Sensing.class));
            // 使用已判定为密集的缓存，只隔离调度，不用假密度查询代替场景复现。
            setMobField(mob, "leavesXDenseMonsterAiActive", true);
            setMobField(mob, "leavesXDenseMonsterAiNextCheckTick", Long.MAX_VALUE);
            doCallRealMethod().when(mob).serverAiStep();
            doCallRealMethod().when(mob).getExpReward(any(), any());
            try (var runtime = mockStatic(LeavesXRuntime.class)) {
                runtime.when(() -> LeavesXRuntime.allowStartupAiStep(false, false)).thenReturn(true);
                runtime.when(LeavesXRuntime::denseMonsterAiThrottling).thenReturn(dense);
                runtime.when(LeavesXRuntime::denseMonsterAiGoalIntervalTicks).thenReturn(4);
                mob.serverAiStep();
                verify(mob).customServerAiStep(level);
                verify(mob.navigation).tick();
                verify(mob.goalSelector, times(dense ? 0 : 1)).tick();
                verify(mob.targetSelector, times(dense ? 0 : 1)).tick();
                verify(mob.goalSelector, times(dense ? 1 : 0)).tickRunningGoals(false);
                verify(mob.targetSelector, times(dense ? 1 : 0)).tickRunningGoals(false);
                assertEquals(100, mob.lastHurtByPlayerMemoryTime);
                assertEquals(5, mob.getExpReward(level, mock(ServerPlayer.class)));
            }
        }
    }

    @Test
    void directPlayerDamageRecordsRewardEligibilityWithoutGoalSelection() {
        final LivingEntity victim = mock(ZombifiedPiglin.class);
        final ServerLevel level = mock(ServerLevel.class);
        final GameRules rules = mock(GameRules.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final DamageSource damage = mock(DamageSource.class);
        when(victim.level()).thenReturn(level);
        when(level.getGameRules()).thenReturn(rules);
        when(rules.get(GameRules.MOB_DROPS)).thenReturn(true);
        when(damage.getEntity()).thenReturn(player);
        when(victim.shouldDropExperience()).thenReturn(true);
        when(victim.getExperienceReward(level, player)).thenReturn(5);
        doCallRealMethod().when(victim).resolvePlayerResponsibleForDamage(damage);
        doCallRealMethod().when(victim).setLastHurtByPlayer(player, 100);
        doCallRealMethod().when(victim).getExpReward(level, player);
        victim.resolvePlayerResponsibleForDamage(damage);
        assertEquals(100, victim.lastHurtByPlayerMemoryTime);
        assertEquals(5, victim.getExpReward(level, player));
        // 经验消费标记仍然生效，不为了诊断无条件制造经验。
        when(victim.wasExperienceConsumed()).thenReturn(true);
        assertEquals(0, victim.getExpReward(level, player));
    }

    private static void setMobField(final Mob mob, final String name, final Object value) throws Exception {
        final Field field = Mob.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mob, value);
    }
}
