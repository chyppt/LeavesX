package org.leavesmc.leaves.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.bot.ServerBotPacketListenerImpl;

@Normal
final class AsyncKeepaliveManagerTest {

    @Test
    void managesRemotePlayerConnections() {
        assertTrue(AsyncKeepaliveManager.shouldManage(mock(ServerCommonPacketListenerImpl.class)));
    }

    @Test
    void skipsServerBotsThatCannotAcknowledgeKeepalives() {
        assertFalse(AsyncKeepaliveManager.shouldManage(mock(ServerBotPacketListenerImpl.class)));
    }
}
