package org.leavesx.leavesx.performance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

/** 覆盖相邻实体复用、输入变化和引用生命周期清理。 */
@Normal
public class TrackerPlayerIndexCacheTest {
    @Test
    void reusesOnlyMatchingIdentitySourceRevisionAndCount() {
        final TrackerPlayerIndexCache cache = new TrackerPlayerIndexCache();
        final Object chunk = new Object();
        final Object players = new Object();
        final int[] first = {2, 5, 9};
        cache.remember(chunk, players, 4L, 3, first);
        assertSame(first, cache.get(chunk, players, 4L, 3));
        assertNull(cache.get(chunk, players, 5L, 3));
        assertNull(cache.get(chunk, new Object(), 4L, 3));
        assertNull(cache.get(chunk, players, 4L, 2));
        final int[] changed = {3, 5, 9};
        cache.remember(chunk, players, 5L, 3, changed);
        assertArrayEquals(new int[] {2, 5, 9}, first, "旧快照输入不能被新版本覆盖");
        assertSame(changed, cache.get(chunk, players, 5L, 3));
        cache.clear();
        assertNull(cache.get(chunk, players, 5L, 3));
    }

    @Test
    void differentChunkReplacesOnlyTheLookupAndKeepsPreviousSnapshotArray() {
        final TrackerPlayerIndexCache cache = new TrackerPlayerIndexCache();
        final Object firstIdentity = new Object();
        final Object secondIdentity = new Object();
        final int[] first = {17};
        final int[] second = {29};
        cache.remember(firstIdentity, firstIdentity, 0L, 1, first);
        cache.remember(secondIdentity, secondIdentity, 0L, 1, second);
        assertNull(cache.get(firstIdentity, firstIdentity, 0L, 1));
        assertSame(second, cache.get(secondIdentity, secondIdentity, 0L, 1));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {17}, first);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {29}, second);
        cache.clear();
    }

    @Test
    void aListThatIsNotImmediatelyAdjacentDoesNotReuseItsOldIndices() {
        final TrackerPlayerIndexCache cache = new TrackerPlayerIndexCache();
        final Object first = new Object();
        final Object middle = new Object();
        final int[] indices = {4};
        cache.remember(first, first, 0L, 1, indices);
        cache.remember(middle, middle, 0L, 1, new int[] {9});
        assertNull(cache.get(first, first, 0L, 1));
        cache.clear();
    }

    @Test
    void disablesRepeatedLookupAfterTheInitialUnproductiveProbeWindow() {
        final TrackerPlayerIndexCache cache = new TrackerPlayerIndexCache();
        for (int index = 0; index < 16; index++) {
            final Object identity = new Object();
            assertNull(cache.get(identity, identity, 0L, 1));
            cache.remember(identity, identity, 0L, 1, new int[] {index});
        }
        final Object next = new Object();
        assertNull(cache.get(next, next, 0L, 1));
        cache.remember(next, next, 0L, 1, new int[] {17});
        assertNull(cache.get(next, next, 0L, 1));
        cache.clear();
        final Object first = new Object();
        final Object second = new Object();
        final int[] indices = {3};
        cache.remember(first, first, 0L, 1, indices);
        assertSame(indices, cache.get(first, first, 0L, 1));
        cache.remember(second, second, 0L, 1, new int[] {4});
        assertNull(cache.get(first, first, 0L, 1));
        cache.clear();
    }

    @Test
    void clearReleasesAllChunkAndPlayerListReferences() throws Exception {
        final TrackerPlayerIndexCache cache = new TrackerPlayerIndexCache();
        final Object chunk = new Object();
        final Object players = new Object();
        cache.remember(chunk, players, 1L, 1, new int[] {0});
        cache.clear();
        for (final String name : new String[] {"chunk", "players", "indices"}) {
            final var field = TrackerPlayerIndexCache.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(cache));
        }
    }
}
