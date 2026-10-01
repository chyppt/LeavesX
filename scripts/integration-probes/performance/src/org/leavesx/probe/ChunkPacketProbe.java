package org.leavesx.probe;

import io.netty.buffer.Unpooled;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;
import org.leavesx.leavesx.runtime.LeavesXAsyncRuntime;

/** Exercises the production packet constructor in a disposable server, not a substitute serializer. */
final class ChunkPacketProbe {
    static void verify(final JavaPlugin plugin, final World world) {
        final var level = ((CraftWorld)world).getHandle();
        final var chunk = level.getChunk(0, 0);
        if (level.chunkPacketBlockController != io.papermc.paper.antixray.ChunkPacketBlockController.NO_OPERATION_INSTANCE) {
            throw new AssertionError("Fixture requires Anti-Xray disabled");
        }
        final long before = LeavesXAsyncRuntime.metrics(LeavesXAsyncRuntime.Workload.CHUNK_SEND).submittedTasks();
        final FriendlyByteBuf expectedBuffer = new FriendlyByteBuf(Unpooled.buffer());
        final BlockPos position = new BlockPos(12, 100, 12);
        final var original = level.getBlockState(position);
        try {
            int index = 0;
            for (final var section : chunk.getSections()) section.write(expectedBuffer, null, index++);
            final byte[] expected = new byte[expectedBuffer.readableBytes()];
            expectedBuffer.readBytes(expected);
            final var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null, false);
            level.setBlock(position, original.isAir() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            final FriendlyByteBuf actualBuffer = packet.getChunkData().getReadBuffer();
            try {
                final byte[] actual = new byte[actualBuffer.readableBytes()];
                actualBuffer.readBytes(actual);
                if (!Arrays.equals(expected, actual)) throw new AssertionError("Packet observed post-capture mutation");
            } finally {
                actualBuffer.release();
            }
            if (!packet.isReady()) throw new AssertionError("Completed packet never became ready");
            if (LeavesXAsyncRuntime.metrics(LeavesXAsyncRuntime.Workload.CHUNK_SEND).submittedTasks() <= before) {
                throw new AssertionError("Packet never submitted actual worker work");
            }
            plugin.getLogger().info("CHUNK_PACKET_PROBE_PASS: production constructor, worker submission, detached bytes, readiness");
        } finally {
            expectedBuffer.release();
            level.setBlock(position, original, 2);
        }
    }
}
