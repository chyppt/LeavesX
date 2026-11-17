package org.leavesx.leavesx.performance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.poi_lookup.PoiAccess;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.*;
import net.minecraft.world.level.LevelHeightAccessor;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.ConfigurationTestSupport;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.spongepowered.configurate.CommentedConfigurationNode;

/** 在真实区段扫描入口比较加载、谓词顺序和占用变化，不运行村民 AI。 */
@Normal
public class PoiCountingTest {
    @AfterEach
    void restore() { ConfigurationTestSupport.publish(LeavesXConfig.safeDefaults()); }

    @Test
    void countAndThresholdKeepLoadsAndPredicateCallsForEveryOccupancy() throws Exception {
        final Fixture fixture = new Fixture();
        for (final PoiManager.Occupancy occupancy : PoiManager.Occupancy.values()) {
            for (final int max : new int[] {1, 2, Integer.MAX_VALUE}) {
                for (final boolean load : new boolean[] {false, true}) {
                    final List<PoiRecord> expected = new ArrayList<>();
                    PoiAccess.findAnyPoiRecords(fixture.manager, fixture.filter, (Predicate<BlockPos>) null,
                        BlockPos.ZERO, 32, 1024, occupancy, load, max, expected);
                    final List<String> reference = List.copyOf(fixture.calls);
                    fixture.calls.clear();
                    assertEquals(expected.size(), PoiAccess.countPoiRecords(fixture.manager, fixture.filter,
                        BlockPos.ZERO, 32, 1024, occupancy, load, max));
                    assertEquals(reference, fixture.calls);
                    fixture.calls.clear();
                }
            }
        }
    }

    @Test
    void reloadSwitchPreservesCountsAndThresholds() throws Exception {
        final Fixture fixture = new Fixture();
        for (final boolean enabled : new boolean[] {false, true, false}) {
            final var node = CommentedConfigurationNode.root();
            node.node("performance", "ai", "acquire-poi-candidates").set(enabled);
            ConfigurationTestSupport.publish(node);
            assertEquals(2, fixture.manager.getCountInRange(fixture.filter, BlockPos.ZERO, 32, PoiManager.Occupancy.ANY));
            assertTrue(fixture.manager.leavesX$hasAtLeastInRange(fixture.filter, BlockPos.ZERO, 32, PoiManager.Occupancy.ANY, 2));
            assertFalse(fixture.manager.leavesX$hasAtLeastInRange(fixture.filter, BlockPos.ZERO, 32, PoiManager.Occupancy.ANY, 3));
            fixture.calls.clear();
            assertTrue(fixture.manager.leavesX$hasAtLeastInRange(fixture.filter, BlockPos.ZERO, 32, PoiManager.Occupancy.ANY, 0));
            assertTrue(fixture.calls.isEmpty());
        }
    }

    @Test
    void repeatedCallsObservePoiRemovalAndOccupancyImmediately() throws Exception {
        final Fixture fixture = new Fixture();
        assertEquals(1, PoiAccess.countPoiRecords(fixture.manager, fixture.filter, BlockPos.ZERO, 32,
            1024, PoiManager.Occupancy.IS_OCCUPIED, true, Integer.MAX_VALUE));
        fixture.records.clear();
        assertEquals(0, PoiAccess.countPoiRecords(fixture.manager, fixture.filter, BlockPos.ZERO, 32,
            1024, PoiManager.Occupancy.IS_OCCUPIED, true, Integer.MAX_VALUE));
    }

    private static final class Fixture {
        final PoiManager manager = mock(PoiManager.class, CALLS_REAL_METHODS);
        final List<String> calls = new ArrayList<>();
        final LinkedHashSet<PoiRecord> records = new LinkedHashSet<>();
        final Predicate<Holder<PoiType>> filter = type -> { calls.add("type"); return true; };

        Fixture() throws Exception {
            ConfigurationTestSupport.publish(LeavesXConfig.safeDefaults());
            final Field height = net.minecraft.world.level.chunk.storage.SectionStorage.class.getDeclaredField("levelHeightAccessor");
            height.setAccessible(true);
            height.set(manager, LevelHeightAccessor.create(-64, 384));
            final PoiSection section = new PoiSection(() -> {});
            final Holder<PoiType> type = Holder.direct(new PoiType(java.util.Set.of(), 1, 1));
            final PoiRecord occupied = mock(PoiRecord.class);
            when(occupied.getPos()).thenReturn(new BlockPos(-1, 0, -1));
            when(occupied.isOccupied()).thenReturn(true);
            when(occupied.hasSpace()).thenReturn(false);
            final PoiRecord available = mock(PoiRecord.class);
            when(available.getPos()).thenReturn(new BlockPos(-2, 0, -1));
            when(available.hasSpace()).thenReturn(true);
            records.add(occupied);
            records.add(available);
            section.byType.put(type, records);
            final long key = CoordinateUtils.getChunkSectionKey(-1, 0, -1);
            doAnswer(call -> { long pos = call.getArgument(0); calls.add("get:" + pos);
                return pos == key ? Optional.of(section) : Optional.empty(); }).when(manager).get(anyLong());
            doAnswer(call -> { long pos = call.getArgument(0); calls.add("load:" + pos);
                return pos == key ? Optional.of(section) : Optional.empty(); }).when(manager).getOrLoad(anyLong());
        }
    }
}
