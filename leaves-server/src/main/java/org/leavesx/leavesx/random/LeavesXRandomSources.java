package org.leavesx.leavesx.random;

import ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom;
import ca.spottedleaf.moonrise.common.util.ThreadUnsafeRandom;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.BitRandomSource;

/** Startup-only selection; seeded world generation and entity shared random are deliberately unchanged. */
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
