package org.leavesx.leavesx.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

/** 执行真实附着判定，只替换世界输入；不把静态源码检查当成行为回归。 */
@VanillaFeature
class ShulkerAttachmentTest {
    @Test
    void allFaceCombinationsKeepFirstMatchAndQueryOrder() throws ReflectiveOperationException {
        final Method search = Shulker.class.getDeclaredMethod("findAttachableSurface", BlockPos.class);
        search.setAccessible(true);
        final BlockPos target = new BlockPos(8, 80, -8);
        final Direction[] directions = Direction.values();
        final ServerLevel level = mock(ServerLevel.class);
        final Shulker shulker = mock(Shulker.class, CALLS_REAL_METHODS);
        doReturn(level).when(shulker).level();
        doReturn(1.0F).when(shulker).getScale();
        when(level.getBlockState(target)).thenReturn(Blocks.AIR.defaultBlockState());
        when(level.noCollision(eq(shulker), any(AABB.class))).thenReturn(true);
        final List<Direction> visited = new ArrayList<>();
        final int[] available = {0};
        when(level.loadedAndEntityCanStandOnFace(any(BlockPos.class), eq(shulker), any(Direction.class)))
            .thenAnswer(call -> {
                final Direction face = call.<Direction>getArgument(2).getOpposite();
                assertEquals(target.relative(face), call.getArgument(0));
                visited.add(face);
                return (available[0] & (1 << face.ordinal())) != 0;
            });

        // 六面共有 64 种可附着组合；连续改变输入，顺便检查没有复用旧世界查询结果。
        for (int mask = 0; mask < 1 << directions.length; mask++) {
            available[0] = mask;
            visited.clear();
            Direction expected = null;
            final List<Direction> expectedVisits = new ArrayList<>();
            for (Direction direction : directions) {
                expectedVisits.add(direction);
                if ((mask & (1 << direction.ordinal())) != 0) {
                    expected = direction;
                    break;
                }
            }
            assertEquals(expected, search.invoke(shulker, target), "附着组合 " + mask);
            assertEquals(expectedVisits, visited, "查询顺序 " + mask);
        }

        // 即使有支撑面，实时碰撞不通过时也不能附着。
        available[0] = 63;
        when(level.noCollision(eq(shulker), any(AABB.class))).thenReturn(false);
        assertNull(search.invoke(shulker, target));
        when(level.getBlockState(target)).thenReturn(Blocks.STONE.defaultBlockState());
        visited.clear();
        assertNull(search.invoke(shulker, target));
        assertEquals(List.of(), visited);
    }
}
