package net.minecraft.world.level;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.MobCategory;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.runtime.LeavesXComputeExecutor;

/** 与独立计数对照，覆盖分片切断连续区块、切回旧区块和输入有效长度。 */
@Normal
public class MobSpawnBatchLocalityTest {
    @AfterEach
    void shutdown() {
        LeavesXComputeExecutor.shutdown();
    }

    @Test
    void repeatedAndRevisitedChunksMatchReferenceAcrossPartitions() {
        final int size = 8_191;
        final int categories = MobCategory.values().length;
        final long[] keys = new long[size + 17];
        final byte[] ordinals = new byte[keys.length];
        for (final boolean clustered : new boolean[] {true, false}) {
            final Map<Long, int[]> expected = new HashMap<>();
            for (int index = 0; index < size; index++) {
                // 包括 0、极端 long 和重复出现的区块键；不能使用特殊 key 当未初始化哨兵。
                keys[index] = switch ((clustered ? index / 257 : index) % 5) {
                    case 0 -> 0L;
                    case 1 -> Long.MIN_VALUE;
                    case 2 -> Long.MAX_VALUE;
                    case 3 -> ChunkPos.pack(-17, 31);
                    default -> ChunkPos.pack(17, -31);
                };
                ordinals[index] = (byte) (index % categories);
                expected.computeIfAbsent(keys[index], ignored -> new int[categories])[ordinals[index]]++;
            }
            // 尾部不属于快照有效范围，即使类别非法也不得读取。
            java.util.Arrays.fill(ordinals, size, ordinals.length, (byte) 255);
            for (final int workers : new int[] {0, 1, 3}) {
                LeavesXComputeExecutor.shutdown();
                if (workers > 0) LeavesXComputeExecutor.configure(workers, 64, true, workers == 1);
                final var actual = MobSpawnCountAccumulator.count(
                    new MobSpawnCountAccumulator.Inputs(keys, ordinals, size), workers > 0, 128);
                assertEquals(expected.size(), actual.size());
                expected.forEach((key, counts) -> assertArrayEquals(counts, actual.get(key.longValue())));
            }
        }
    }

    @Test
    void emptySnapshotProducesNoWorkerTask() {
        LeavesXComputeExecutor.configure(2, 64);
        assertEquals(0, MobSpawnCountAccumulator.count(MobSpawnCountAccumulator.Inputs.EMPTY, true, 128).size());
        assertEquals(0L, LeavesXComputeExecutor.metrics().invocations());
    }
}
