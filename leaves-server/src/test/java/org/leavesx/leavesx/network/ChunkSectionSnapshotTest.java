package org.leavesx.leavesx.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.bukkit.support.environment.Normal;
import org.bukkit.support.RegistryHelper;
import org.junit.jupiter.api.Test;

@Normal
class ChunkSectionSnapshotTest {
    private static LevelChunkSection section() {
        return new LevelChunkSection(PalettedContainerFactory.create(RegistryHelper.registryAccess()), null, null, 0);
    }

    private static byte[] encodePaper(final LevelChunkSection[] sections) {
        final FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (int index = 0; index < sections.length; index++) {
                sections[index].write(output, null, index);
            }
            final byte[] result = new byte[output.readableBytes()];
            output.readBytes(result);
            return result;
        } finally {
            output.release();
        }
    }

    @Test
    void workerEncodingMatchesPaperBeforeAndAfterOwnerMutation() throws Exception {
        final LevelChunkSection section = section();
        section.setBlockState(2, 3, 4, Blocks.STONE.defaultBlockState());
        section.setBlockState(5, 6, 7, Blocks.WATER.defaultBlockState());
        final LevelChunkSection[] live = {section, section()};
        final byte[] original = encodePaper(live);
        final ChunkSectionSnapshot snapshot = ChunkSectionSnapshot.capture(live);

        section.setBlockState(2, 3, 4, Blocks.AIR.defaultBlockState());
        section.setBlockState(5, 6, 7, Blocks.LAVA.defaultBlockState());
        section.setNoiseBiome(0, 0, 0,
            RegistryHelper.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.DESERT));
        live[1] = section();
        live[1].setBlockState(1, 1, 1, Blocks.CHEST.defaultBlockState());
        assertFalse(Arrays.equals(original, encodePaper(live)));

        try (var executor = Executors.newSingleThreadExecutor()) {
            assertArrayEquals(original, executor.submit(snapshot::encode).get(5, TimeUnit.SECONDS));
        }
        // 重试必须分配新的完整结果，不受之前消费者缓冲区的影响。
        final byte[] first = snapshot.encode();
        Arrays.fill(first, (byte) 0);
        assertArrayEquals(original, snapshot.encode());
    }

    @Test
    void emptySectionArrayHasEmptyWirePayload() {
        assertEquals(0, ChunkSectionSnapshot.capture(new LevelChunkSection[0]).encode().length);
    }
}
