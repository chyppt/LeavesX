package org.leavesx.leavesx.runtime;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Normal
class SleepStatusRegressionTest {
    @Test
    void sleepDurationAdvancesWithoutAnotherPlayerListUpdate() {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.isSleeping()).thenReturn(true);
        List<ServerPlayer> players = List.of(player);
        SleepStatus status = new SleepStatus();
        status.update(players);
        assertTrue(status.areEnoughSleeping(100));
        assertFalse(status.areEnoughDeepSleeping(100, players));

        // 时间流逝不会触发睡眠玩家列表更新。
        when(player.isSleepingLongEnough()).thenReturn(true);
        assertTrue(status.areEnoughDeepSleeping(100, players));
        when(player.isSleepingLongEnough()).thenReturn(false);
        assertFalse(status.areEnoughDeepSleeping(100, players));
    }

    @Test
    void fauxSleepStillRequiresOneRealDeepSleeperAndRespectsPercentage() {
        ServerPlayer real = mock(ServerPlayer.class);
        ServerPlayer faux = mock(ServerPlayer.class);
        ServerPlayer awake = mock(ServerPlayer.class);
        when(real.isSleeping()).thenReturn(true);
        faux.fauxSleeping = true;
        List<ServerPlayer> players = List.of(real, faux, awake);
        SleepStatus status = new SleepStatus();
        status.update(players);
        assertFalse(status.areEnoughDeepSleeping(50, players));
        when(real.isSleepingLongEnough()).thenReturn(true);
        assertTrue(status.areEnoughDeepSleeping(50, players));
        assertFalse(status.areEnoughDeepSleeping(100, players));
    }
}
