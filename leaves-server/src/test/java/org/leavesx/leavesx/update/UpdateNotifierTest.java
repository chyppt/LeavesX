package org.leavesx.leavesx.update;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.presentation.LeavesXPlayerPresentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Normal
class UpdateNotifierTest {
    @Test
    void onlyOperatorsWithCachedUpdateReceiveMessageWithoutNetworkRequest() {
        final Player player = mock(Player.class);
        final PlayerJoinEvent event = new PlayerJoinEvent(player, Component.empty());
        try (var service = mockStatic(LeavesXUpdateService.class);
             var runtime = mockStatic(LeavesXRuntime.class);
             var presentation = mockStatic(LeavesXPlayerPresentation.class)) {
            runtime.when(LeavesXRuntime::configuration).thenReturn(LeavesXConfig.safeDefaults());
            presentation.when(() -> LeavesXPlayerPresentation.withCorePrefix(any())).thenAnswer(call -> call.getArgument(0));
            final LeavesXUpdateNotifier listener = new LeavesXUpdateNotifier();
            service.when(LeavesXUpdateService::availableVersion).thenReturn("1.4.0");
            listener.onJoin(event);
            verify(player, never()).sendMessage(any(Component.class));
            when(player.isOp()).thenReturn(true);
            service.when(LeavesXUpdateService::availableVersion).thenReturn(null);
            listener.onJoin(event);
            verify(player, never()).sendMessage(any(Component.class));
            service.when(LeavesXUpdateService::availableVersion).thenReturn("1.4.0");
            listener.onJoin(event);
            verify(player).sendMessage(any(Component.class));
            final var config = mock(LeavesXConfig.class);
            when(config.extensions()).thenReturn(mock(LeavesXConfig.ExtensionSettings.class));
            runtime.when(LeavesXRuntime::configuration).thenReturn(config);
            listener.onJoin(event);
            verify(player, times(1)).sendMessage(any(Component.class));
            service.verify(() -> LeavesXUpdateService.request(anyBoolean()), never());
        }
    }
}
