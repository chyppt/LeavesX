package org.leavesx.probe.neko;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;

import cn.dg32z.libs.com.github.retrooper.packetevents.PacketEvents;
import cn.dg32z.libs.com.github.retrooper.packetevents.PacketEventsAPI;
import cn.dg32z.libs.com.github.retrooper.packetevents.event.PacketSendEvent;
import cn.dg32z.libs.com.github.retrooper.packetevents.event.EventManager;
import cn.dg32z.libs.com.github.retrooper.packetevents.manager.server.ServerVersion;
import cn.dg32z.libs.com.github.retrooper.packetevents.protocol.packettype.PacketType;
import cn.dg32z.libs.com.github.retrooper.packetevents.protocol.player.User;
import cn.dg32z.libs.com.github.retrooper.packetevents.settings.PacketEventsSettings;
import cn.dg32z.libs.io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MovementWireProbeTest {
    @TempDir java.nio.file.Path temporary;
    private PacketEventsAPI<?> previousApi;

    @BeforeEach
    void preparePacketEvents() {
        previousApi = PacketEvents.getAPI();
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(api.getNettyManager()).thenReturn(new NettyManagerImpl());
        when(api.getSettings()).thenReturn(new PacketEventsSettings().customResourceProvider(
            name -> PacketEvents.class.getClassLoader().getResourceAsStream(name)));
        PacketEvents.setAPI(api);
    }

    @AfterEach
    void restorePacketEvents() {
        PacketEvents.setAPI(previousApi);
    }

    @Test
    void retriesADeferredApiAndDoesNotRegisterTheSameObserverTwice() {
        NekoMovementProbe plugin = mock(NekoMovementProbe.class, CALLS_REAL_METHODS);
        PacketEvents.setAPI(null);
        assertFalse(plugin.registerWireProbe());
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        EventManager manager = mock(EventManager.class);
        when(api.getEventManager()).thenReturn(manager);
        PacketEvents.setAPI(api);
        assertTrue(plugin.registerWireProbe());
        assertTrue(plugin.registerWireProbe());
        verify(manager, times(1)).registerListener(any(MovementWireProbe.class));
    }

    @Test
    void replacesTheObserverWhenNekoReplacesThePacketApi() {
        NekoMovementProbe plugin = mock(NekoMovementProbe.class, CALLS_REAL_METHODS);
        EventManager first = mock(EventManager.class);
        EventManager second = mock(EventManager.class);
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        when(api.getEventManager()).thenReturn(first, second, null);
        PacketEvents.setAPI(api);
        assertTrue(plugin.registerWireProbe());
        assertTrue(plugin.registerWireProbe());
        verify(first).unregisterListener(any(MovementWireProbe.class));
        verify(second).registerListener(any(MovementWireProbe.class));
        assertFalse(plugin.registerWireProbe());
        verify(second).unregisterListener(any(MovementWireProbe.class));
    }

    @Test
    void registrationFailureCannotLeaveAFalseReadyState() {
        NekoMovementProbe plugin = mock(NekoMovementProbe.class, CALLS_REAL_METHODS);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("NekoProbeTest"));
        EventManager manager = mock(EventManager.class);
        doThrow(new IllegalStateException("registration unavailable")).doAnswer(call -> call.getArgument(0))
            .when(manager).registerListener(any(MovementWireProbe.class));
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        when(api.getEventManager()).thenReturn(manager);
        PacketEvents.setAPI(api);
        assertFalse(plugin.registerWireProbe());
        verify(manager).unregisterListener(any(MovementWireProbe.class));
        assertTrue(plugin.registerWireProbe());
        verify(manager, times(2)).registerListener(any(MovementWireProbe.class));
    }

    @Test
    void monitorCopiesOnlyPacketBytesAndDoesNotChangePacketEvent() throws Exception {
        UUID playerId = UUID.randomUUID();
        User user = mock(User.class);
        when(user.getUUID()).thenReturn(playerId);
        ByteBuf payload = Unpooled.buffer().writeByte(37);
        PacketSendEvent event = mock(PacketSendEvent.class);
        when(event.getUser()).thenReturn(user);
        when(event.getPacketType()).thenReturn(PacketType.Play.Server.ACKNOWLEDGE_BLOCK_CHANGES);
        when(event.getServerVersion()).thenReturn(ServerVersion.V_26_1_2);
        when(event.getByteBuf()).thenReturn(payload);
        when(event.isCancelled()).thenReturn(false);
        when(event.needsReEncode()).thenReturn(false);

        var file = temporary.resolve("wire.log");
        MovementTrace trace = new MovementTrace(playerId, 60, file);
        try {
            try (trace) {
                new MovementWireProbe(() -> trace).onPacketSend(event);
                assertEquals(0, payload.readerIndex());
                assertEquals(1, payload.refCnt());
                verify(event, never()).setCancelled(anyBoolean());
                verify(event, never()).markForReEncode(anyBoolean());
            }
            assertTrue(trace.awaitClosed(5000));
            assertTrue(Files.readString(file).contains("type=ACKNOWLEDGE_BLOCK_CHANGES blockSequence=37"));
            assertTrue(Files.readString(file).contains("wire-out=1 wire-in=0"));
        } finally {
            payload.release();
        }
    }

    @Test
    void skipsOtherPlayersBeforeReadingOrCopyingTheirPackets() throws Exception {
        PacketSendEvent event = mock(PacketSendEvent.class);
        User user = mock(User.class);
        when(user.getUUID()).thenReturn(UUID.randomUUID());
        when(event.getUser()).thenReturn(user);
        MovementTrace trace = new MovementTrace(UUID.randomUUID(), 60, temporary.resolve("other-player.log"));
        try (trace) {
            new MovementWireProbe(() -> trace).onPacketSend(event);
            verify(event, never()).getByteBuf();
            verify(event, never()).getPacketType();
        }
        assertTrue(trace.awaitClosed(5000));
    }

    @Test
    void malformedPacketOnlyProducesAnObservationAndPreservesTheConnection() throws Exception {
        UUID playerId = UUID.randomUUID();
        User user = mock(User.class);
        when(user.getUUID()).thenReturn(playerId);
        PacketSendEvent event = mock(PacketSendEvent.class);
        when(event.getUser()).thenReturn(user);
        when(event.getPacketType()).thenReturn(PacketType.Play.Server.BLOCK_CHANGE);
        when(event.getServerVersion()).thenReturn(ServerVersion.V_26_1_2);
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        when(event.getByteBuf()).thenReturn(payload);
        var file = temporary.resolve("malformed.log");
        MovementTrace trace = new MovementTrace(playerId, 60, file);
        try {
            try (trace) {
                new MovementWireProbe(() -> trace).onPacketSend(event);
                assertEquals(0, payload.readerIndex());
                verify(event, never()).setCancelled(anyBoolean());
                verify(event, never()).markForReEncode(anyBoolean());
            }
            assertTrue(trace.awaitClosed(5000));
            assertTrue(Files.readString(file).contains("payload=unavailable(IllegalArgumentException)"));
        } finally {
            payload.release();
        }
    }
}
