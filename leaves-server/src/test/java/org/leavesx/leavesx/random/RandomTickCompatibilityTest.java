package org.leavesx.leavesx.random;

import static org.junit.jupiter.api.Assertions.*;

import ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@Normal
class RandomTickCompatibilityTest {
    @AfterEach void reset() { LeavesXRandomSources.configure(false); }

    @Test
    void skippingSamplesIsBitExactAndPreservesGaussianCache() {
        for (long seed : new long[]{0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            for (int count : new int[]{-1, 0, 1, 3, 17, 4096, 65537}) {
                final var expected = new SimpleThreadUnsafeRandom(seed);
                final var actual = new SimpleThreadUnsafeRandom(seed);
                assertEquals(expected.nextGaussian(), actual.nextGaussian());
                for (int i = 0; i < count; i++) expected.nextInt();
                actual.consumeCount(count);
                assertEquals(expected.nextInt(), actual.nextInt());
                assertEquals(expected.nextGaussian(), actual.nextGaussian());
                assertEquals(expected.nextLong(), actual.nextLong());
            }
        }
    }

    @Test
    void fastRuntimeRandomDoesNotReplaceSlimeOrSeededWorldgenRandom() {
        final long vanilla = net.minecraft.util.RandomSource.create(42).nextLong();
        final long slime = WorldgenRandom.seedSlimeChunk(12, -34, 42, 123).nextLong();
        LeavesXRandomSources.configure(true);
        assertInstanceOf(FasterRandomSource.class, LeavesXRandomSources.randomTicks(42));
        assertEquals(vanilla, net.minecraft.util.RandomSource.create(42).nextLong());
        assertEquals(slime, WorldgenRandom.seedSlimeChunk(12, -34, 42, 123).nextLong());
        final var random = new FasterRandomSource(42);
        final long expected = random.nextLong();
        random.setSeed(42);
        assertEquals(expected, random.nextLong());
        assertThrows(IllegalArgumentException.class, () -> random.nextInt(0));
    }

    @Test
    void randomTickPositionsRemainImmutableWhenEvicted() {
        final var cache = new RandomTickPositionCache();
        final BlockPos retained = cache.get(-16, 70, 31);
        assertSame(retained, cache.get(-16, 70, 31));
        for (int i = 0; i < 10000; i++) cache.get(i, i & 127, -i);
        assertEquals(new BlockPos(-16, 70, 31), retained);
        assertFalse(retained instanceof BlockPos.MutableBlockPos);
    }
}
