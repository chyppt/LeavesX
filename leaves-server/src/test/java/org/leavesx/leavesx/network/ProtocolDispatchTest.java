package org.leavesx.leavesx.network;

import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.protocol.core.IdentifierSelector;
import org.leavesmc.leaves.protocol.core.LeavesProtocolManager;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Normal
class ProtocolDispatchTest {
    @Test
    void networkThreadQueuesPacketBeforeInvokingAnyLeavesHandler() throws Exception {
        final PacketProcessor processor = mock(PacketProcessor.class);
        final var listener = listener(processor);
        final var packet = packet();
        try (var handlers = mockStatic(LeavesProtocolManager.class)) {
            assertThrows(RunningOnDifferentThreadException.class, () -> listener.handleCustomPayload(packet));
            verify(processor).scheduleIfPossible(listener, packet);
            handlers.verifyNoInteractions();
        }
    }

    @Test
    void consumedInboundBufferIsReleasedOnSuccessAndFailure() throws Exception {
        for (boolean failing : new boolean[] {false, true}) {
            final PacketProcessor processor = mock(PacketProcessor.class);
            when(processor.isSameThread()).thenReturn(true);
            final var listener = listener(processor);
            final var selector = mock(IdentifierSelector.class);
            final AtomicReference<ByteBuf> received = new AtomicReference<>();
            try (var handlers = mockStatic(LeavesProtocolManager.class);
                 var utilities = mockStatic(ProtocolUtils.class, CALLS_REAL_METHODS)) {
                utilities.when(() -> ProtocolUtils.createSelector(listener)).thenReturn(selector);
                handlers.when(() -> LeavesProtocolManager.handleBytebuf(eq(selector), any(), any())).thenAnswer(call -> {
                    received.set(call.getArgument(2));
                    assertEquals(1, received.get().refCnt());
                    if (failing) throw new IllegalStateException("Injected handler failure");
                    return true;
                });
                if (failing) assertThrows(IllegalStateException.class, () -> listener.handleCustomPayload(packet()));
                else assertDoesNotThrow(() -> listener.handleCustomPayload(packet()));
                assertNotNull(received.get());
                assertEquals(0, received.get().refCnt());
                verify(processor, never()).scheduleIfPossible(any(), any());
            }
        }
    }

    @Test
    void outboundEncoderFailureReleasesOwnedBuffer() {
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.registryAccess()).thenReturn(RegistryHelper.registryAccess().freeze());
        final AtomicReference<RegistryFriendlyByteBuf> encoded = new AtomicReference<>();
        try (var servers = mockStatic(MinecraftServer.class, CALLS_REAL_METHODS)) {
            servers.when(MinecraftServer::getServer).thenReturn(server);
            assertThrows(IllegalArgumentException.class, () -> ProtocolUtils.sendBytebufPacket(
                mock(ServerPlayer.class), Identifier.parse("jei:recipe_transfer_result"), buffer -> {
                    encoded.set(buffer);
                    buffer.writeByte(1);
                    throw new IllegalArgumentException("Injected encoder failure");
                }));
            assertEquals(0, encoded.get().refCnt());
        }
    }

    private static ServerboundCustomPayloadPacket packet() {
        return new ServerboundCustomPayloadPacket(new DiscardedPayload(Identifier.parse("jei:recipe_transfer"), new byte[] {1}));
    }

    private static ServerCommonPacketListenerImpl listener(PacketProcessor processor) throws Exception {
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.packetProcessor()).thenReturn(processor);
        // 构造器会启动心跳跟踪；只跳过这个无关生命周期，分发逻辑仍使用真实实现。
        final var listener = mock(ServerCommonPacketListenerImpl.class, CALLS_REAL_METHODS);
        final Field field = ServerCommonPacketListenerImpl.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(listener, server);
        return listener;
    }
}
