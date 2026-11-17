package org.leavesx.leavesx.network;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoop;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.papermc.paper.util.MCUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leavesx.leavesx.config.LeavesXRuntime;

/** 使用真实 EventLoop 和本地连接，覆盖非 flush 方块包与 ACK、事务包的写出顺序。 */
@Normal
class BlockAcknowledgementOrderingTest {
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void blockWritesAndAcknowledgementsKeepOrderAcrossTheEventLoop(final boolean consolidate, final boolean wrapLoop) throws Exception {
        final var setting = LeavesXRuntime.class.getDeclaredField("networkFlushConsolidation");
        setting.setAccessible(true);
        final boolean previous = setting.getBoolean(null);
        final DefaultEventLoopGroup loops = new DefaultEventLoopGroup(2);
        Channel server = null;
        Channel client = null;
        Channel accepted = null;
        try {
            setting.setBoolean(null, consolidate);
            final Connection connection = new Connection(PacketFlow.SERVERBOUND);
            final PacketListener listener = mock(PacketListener.class);
            when(listener.protocol()).thenReturn(ConnectionProtocol.PLAY);
            final var listenerField = Connection.class.getDeclaredField("packetListener");
            listenerField.setAccessible(true);
            listenerField.set(connection, listener);
            connection.isPending = false;

            final CompletableFuture<Channel> ready = new CompletableFuture<>();
            final LinkedBlockingQueue<Object> received = new LinkedBlockingQueue<>();
            server = new ServerBootstrap().group(loops).channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(final LocalChannel channel) {
                        channel.pipeline().addLast(connection);
                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(final ChannelHandlerContext context) {
                                // Connection 先向后传播 active，再初始化字段；下一任务才可安全发送。
                                context.executor().execute(() -> ready.complete(context.channel()));
                                context.fireChannelActive();
                            }
                        });
                    }
                }).bind(new LocalAddress("leavesx-block-ack-" + consolidate + "-" + wrapLoop)).sync().channel();
            client = new Bootstrap().group(loops).channel(LocalChannel.class)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(final ChannelHandlerContext context, final Object packet) {
                        received.add(packet);
                    }
                }).connect(server.localAddress()).sync().channel();
            accepted = ready.get(5, TimeUnit.SECONDS);
            final AtomicInteger wrappedTasks = new AtomicInteger();
            if (wrapLoop) {
                final EventLoop actualLoop = accepted.eventLoop();
                final EventLoop decoratedLoop = mock(EventLoop.class, org.mockito.AdditionalAnswers.delegatesTo(actualLoop));
                org.mockito.Mockito.doAnswer(call -> {
                    wrappedTasks.incrementAndGet();
                    actualLoop.execute(call.getArgument(0));
                    return null;
                }).when(decoratedLoop).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
                final Channel decoratedChannel = mock(Channel.class, org.mockito.AdditionalAnswers.delegatesTo(accepted));
                org.mockito.Mockito.doReturn(decoratedLoop).when(decoratedChannel).eventLoop();
                // 缓存的原始循环仍在连接内；模拟插件随后包装调度器，不得绕过它。
                connection.channel = decoratedChannel;
            }

            // 让提交线程走生产主线程的分支；实际写出由独立的 Netty EventLoop 执行。
            try (final var mainThread = mockStatic(MCUtil.class, CALLS_REAL_METHODS)) {
                mainThread.when(MCUtil::isMainThread).thenReturn(true);
                for (int sequence = 0; sequence < 128; sequence++) {
                    final var block = new ClientboundBlockUpdatePacket(new BlockPos(109, 69, -103), Blocks.STONE.defaultBlockState());
                    final var acknowledgement = new ClientboundBlockChangedAckPacket(sequence);
                    final var transaction = new ClientboundPingPacket(-sequence - 1);
                    connection.send(block, null, false);
                    connection.send(acknowledgement);
                    connection.send(transaction);
                    assertSame(block, received.poll(5, TimeUnit.SECONDS));
                    assertSame(acknowledgement, received.poll(5, TimeUnit.SECONDS));
                    assertSame(transaction, received.poll(5, TimeUnit.SECONDS));
                }
            }
            assertTrue(received.isEmpty());
            if (wrapLoop) org.junit.jupiter.api.Assertions.assertEquals(128 * 3, wrappedTasks.get(),
                "方块更新、ACK 和 ping 都必须走当前插件包装后的调度器");
        } finally {
            if (client != null) client.close().syncUninterruptibly();
            if (accepted != null) accepted.close().syncUninterruptibly();
            if (server != null) server.close().syncUninterruptibly();
            loops.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            setting.setBoolean(null, previous);
        }
    }
}
