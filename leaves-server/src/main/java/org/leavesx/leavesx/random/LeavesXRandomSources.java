package org.leavesx.leavesx.random;

import ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom;
import ca.spottedleaf.moonrise.common.util.ThreadUnsafeRandom;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.BitRandomSource;

/** 只在启动时选择；带种子的世界生成和实体共享随机源有意保持原版不变。 */
public final class LeavesXRandomSources {
    private static volatile boolean faster;

    private LeavesXRandomSources() {}

    public static void configure(final boolean enabled) {
        faster = enabled;
    }

    public static RandomSource worldEvents(final long seed) {
        return faster ? new FasterRandomSource(seed) : new ThreadUnsafeRandom(seed);
    }

    public static BitRandomSource randomTicks(final long seed) {
        return faster ? new FasterRandomSource(seed) : new SimpleThreadUnsafeRandom(seed);
    }
}
