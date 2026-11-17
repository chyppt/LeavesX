package org.leavesx.leavesx.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.spongepowered.configurate.CommentedConfigurationNode;

@Normal
class BlockDestructionPacketTest {
    @AfterEach void reset() { LeavesXRuntime.publish(LeavesXConfig.safeDefaults()); }

    @Test
    void recipientsReceiveSameDataAndMutablePositionCannotChangeSharedPacket() throws Exception {
        final ServerLevel world = world();
        final ServerPlayer first = player(world, 1, 0);
        final ServerPlayer second = player(world, 2, 0);
        final ServerPlayer distant = player(world, 3, 32);
        when(world.getServer().getPlayerList().getPlayers()).thenReturn(List.of(first, second, distant));
        final var position = new BlockPos.MutableBlockPos(0, 0, 0);
        world.destroyBlockProgress(99, position, 4);
        final var firstPacket = packet(first);
        final var secondPacket = packet(second);
        assertSame(firstPacket, secondPacket);
        assertEquals(99, firstPacket.getId());
        assertEquals(4, firstPacket.getProgress());
        position.set(100, 100, 100);
        assertEquals(BlockPos.ZERO, firstPacket.getPos());
        verify(distant.connection, never()).send(any(Packet.class));

        final var node = CommentedConfigurationNode.root();
        node.node("performance", "reduce-packets", "reuse-block-destruction").set(false);
        LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
        clearInvocations(first.connection, second.connection);
        world.destroyBlockProgress(99, BlockPos.ZERO, 4);
        assertNotSame(packet(first), packet(second));
    }

    @Test
    void noRecipientsStillFiresOriginalPluginEvent() throws Exception {
        final ServerLevel world = world();
        when(world.getServer().getPlayerList().getPlayers()).thenReturn(List.of());
        final Entity breaker = mock(Entity.class);
        when(breaker.getBukkitEntity()).thenReturn(mock(org.bukkit.craftbukkit.entity.CraftEntity.class));
        when(world.getEntity(99)).thenReturn(breaker);
        final PluginManager manager = mock(PluginManager.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            world.destroyBlockProgress(99, BlockPos.ZERO, 4);
            verify(manager).callEvent(any(io.papermc.paper.event.block.BlockBreakProgressUpdateEvent.class));
        }
    }

    private static ServerLevel world() throws Exception {
        final ServerLevel world = mock(ServerLevel.class);
        doCallRealMethod().when(world).destroyBlockProgress(anyInt(), any(BlockPos.class), anyInt());
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.getPlayerList()).thenReturn(mock(PlayerList.class));
        final Field field = ServerLevel.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(world, server);
        when(world.getServer()).thenReturn(server);
        return world;
    }

    private static ServerPlayer player(final ServerLevel world, final int id, final double x) {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(world);
        when(player.getId()).thenReturn(id);
        when(player.getX()).thenReturn(x);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        return player;
    }

    private static ClientboundBlockDestructionPacket packet(final ServerPlayer player) {
        final var capture = ArgumentCaptor.forClass(ClientboundBlockDestructionPacket.class);
        verify(player.connection).send(capture.capture());
        return capture.getValue();
    }
}
