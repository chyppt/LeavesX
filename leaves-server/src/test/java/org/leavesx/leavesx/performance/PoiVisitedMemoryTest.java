package org.leavesx.leavesx.performance;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.bukkit.support.environment.Normal;

/** 查询范围只影响容量，不影响已访问标记；大范围提前命中时不预分配整幅位图。 */
@Normal
public class PoiVisitedMemoryTest {
    @Test
    void standardVillagerSearchKeepsTheSmallFlatArray() {
        final var visited = PoiVisitedSectionSet.create(-3, -4, -3, 3, 19, 3, true, true);
        assertTrue(visited.usesBitSet());
        assertEquals(19, visited.allocatedWordCount());
        assertTrue(visited.mark(0, 0, 0));
        assertFalse(visited.mark(0, 0, 0));
    }

    @Test
    void largeSparseSearchAllocatesOnlyTouchedPages() {
        final var visited = PoiVisitedSectionSet.create(0, 0, 0, 127, 63, 127, true, true);
        assertTrue(visited.usesBitSet());
        assertEquals(0, visited.allocatedWordCount());
        assertTrue(visited.mark(64, 32, 64));
        assertEquals(64, visited.allocatedWordCount());
        assertFalse(visited.mark(64, 32, 64));
        assertEquals(64, visited.allocatedWordCount());
        assertTrue(visited.mark(0, 0, 0));
        assertTrue(visited.mark(127, 63, 127));
        assertEquals(192, visited.allocatedWordCount());
    }

    @Test
    void pagedMarksMatchHashFallbackAcrossNegativeCoordinatesAndPageEdges() {
        final var paged = PoiVisitedSectionSet.create(-32, -4, -32, 31, 19, 31, true, true);
        final var reference = PoiVisitedSectionSet.create(-32, -4, -32, 31, 19, 31, true, false);
        final Random random = new Random(78123);
        for (int i = 0; i < 100_000; i++) {
            final int x = random.nextInt(64) - 32;
            final int y = random.nextInt(24) - 4;
            final int z = random.nextInt(64) - 32;
            assertEquals(reference.mark(x, y, z), paged.mark(x, y, z));
        }
        for (int z = -32; z <= 31; z++) for (int x = -32; x <= 31; x++) for (int y = -4; y <= 19; y++) {
            assertEquals(reference.mark(x, y, z), paged.mark(x, y, z));
            assertFalse(paged.mark(x, y, z));
        }
        assertEquals(1536, paged.allocatedWordCount());
    }

    @Test
    void partialLastPageAndDisabledRepresentationKeepAllMarks() {
        final var visited = PoiVisitedSectionSet.create(0, 0, 0, 4096, 0, 0, true, true);
        assertTrue(visited.mark(4096, 0, 0));
        assertEquals(1, visited.allocatedWordCount());
        assertFalse(visited.mark(4096, 0, 0));
        assertFalse(PoiVisitedSectionSet.create(0, 0, 0, 4096, 0, 0, false, true).usesBitSet());
    }
}
