package org.leavesx.leavesx.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.LeavesConfig;
import org.mockito.ArgumentCaptor;

/** 两个发送入口必须使用相同的聊天转换，并保留网络完成回调。 */
@Normal
class ChatPacketConversionTest {
    @Test
    void ordinarySendConvertsUnsignedChat() throws Exception {
        check(true, false);
    }

    @Test
    void callbackSendAlsoConvertsUnsignedChat() throws Exception {
        check(true, true);
    }

    @Test
    void signingEnabledPreservesOriginalPacketAndCallback() throws Exception {
        check(false, true);
    }

    private void check(boolean noChatSign, boolean withCallback) throws Exception {
        final boolean previous = LeavesConfig.mics.noChatSign;
        try {
            LeavesConfig.mics.noChatSign = noChatSign;
            final ServerGamePacketListenerImpl listener = mock(ServerGamePacketListenerImpl.class, CALLS_REAL_METHODS);
            final Connection connection = mock(Connection.class);
            final var field = ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
            field.setAccessible(true);
            field.set(listener, connection);
            final ClientboundPlayerChatPacket packet = mock(ClientboundPlayerChatPacket.class);
            final ChatType.Bound type = mock(ChatType.Bound.class);
            final Component content = Component.literal("chat regression");
            when(packet.unsignedContent()).thenReturn(content);
            when(packet.chatType()).thenReturn(type);
            when(type.decorate(content)).thenReturn(content);
            final ChannelFutureListener callback = withCallback ? mock(ChannelFutureListener.class) : null;
            if (withCallback) listener.send(packet, callback);
            else listener.send(packet);

            final ArgumentCaptor<Packet<?>> sent = ArgumentCaptor.forClass(Packet.class);
            verify(connection).send(sent.capture(), eq(callback), eq(true));
            if (noChatSign) {
                final ClientboundSystemChatPacket converted = assertInstanceOf(ClientboundSystemChatPacket.class, sent.getValue());
                assertEquals(content, converted.content());
                assertEquals(false, converted.overlay());
            } else {
                assertSame(packet, sent.getValue());
            }
        } finally {
            LeavesConfig.mics.noChatSign = previous;
        }
    }
}
