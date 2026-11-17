package org.leavesx.probe.neko;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import cn.dg32z.neko.api.IHandler;
import cn.dg32z.neko.api.PlayerAPI;
import cn.dg32z.neko.api.events.NekoFlagEvent;
import cn.dg32z.neko.api.events.NekoPredictionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NekoFlagProbeTest {
    @TempDir Path temporary;

    @Test
    void predictionAndFlagUseIndependentBukkitHandlerLists() {
        assertNotSame(NekoPredictionEvent.getHandlerList(), NekoFlagEvent.getHandlerList());
    }

    @Test
    void recordsRealFlagWithoutChangingCancellationOrViolations() throws Exception {
        final var player = mock(PlayerAPI.class);
        final UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        final var check = mock(IHandler.class);
        when(check.getCheckName()).thenReturn("MovementValidation");
        when(check.getViolations()).thenReturn(12.0);
        final var event = spy(new NekoFlagEvent(player, check));
        final Path file = temporary.resolve("flag.log");
        final var trace = new MovementTrace(id, 60, file);
        try (trace) {
            trace.appendWire(true, "wire-out type=BLOCK_CHANGE position=109,69,-103 stateId=1");
            trace.appendWire(true, "wire-out type=PING transactionId=-451");
            trace.appendWire(false, "wire-in type=PONG transactionId=-451");
            fixture(trace).flag(event);
            assertTrue(trace.triggered());
            verify(event, never()).setCancelled(anyBoolean());
            verify(check, never()).setViolations(anyDouble());
            verifyNoInteractionsExceptReads(player);
        }
        assertTrue(trace.awaitClosed(5000));
        final String text = Files.readString(file);
        assertTrue(text.contains("flag check=MovementValidation"));
        assertTrue(text.contains("vl=12.0 cancelled=false"));
        assertTrue(text.indexOf("wire-in type=PONG") < text.indexOf("flag check="));
    }

    @Test
    void unrelatedChecksAndPlayersCannotTriggerTheMovementTrace() throws Exception {
        final var player = mock(PlayerAPI.class);
        final UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        final var check = mock(IHandler.class);
        when(check.getCheckName()).thenReturn("OtherCheck");
        final var trace = new MovementTrace(id, 60, temporary.resolve("unrelated.log"));
        try (trace) {
            final var plugin = fixture(trace);
            plugin.flag(new NekoFlagEvent(player, check));
            assertFalse(trace.triggered());
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(check.getCheckName()).thenReturn("MovementValidation");
            plugin.flag(new NekoFlagEvent(player, check));
            assertFalse(trace.triggered());
        }
        assertTrue(trace.awaitClosed(5000));
    }

    private static NekoMovementProbe fixture(final MovementTrace trace) throws Exception {
        final var plugin = mock(NekoMovementProbe.class, CALLS_REAL_METHODS);
        setField(plugin, "trace", trace);
        // 预先标记元信息已经检查；用例只覆盖公开事件到采样文件的路径，不读取类文件。
        setField(plugin, "discovered", new HashSet<>(Set.of(trace)));
        setField(plugin, "stacked", new HashSet<>(Set.of(trace)));
        return plugin;
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final var field = NekoMovementProbe.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void verifyNoInteractionsExceptReads(final PlayerAPI player) {
        verify(player, never()).enqueueRtTask(any(Runnable.class));
        verify(player, never()).runSafely(any(Runnable.class));
        verify(player, never()).sendMessage(anyString());
    }
}
