package org.leavesx.leavesx.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/** Allocation-only rail optimization. Writes, neighbor updates and Bukkit events stay in PoweredRailBlock. */
public final class PoweredRailSignal {
    private PoweredRailSignal() {}

    public static boolean find(final PoweredRailBlock rail, final Level level, final BlockPos pos,
                               final BlockState state, final boolean forward, final int depth) {
        return find(rail, level, pos.getX(), pos.getY(), pos.getZ(), state, forward, depth, new BlockPos.MutableBlockPos());
    }

    private static boolean find(final PoweredRailBlock rail, final Level level, int x, int y, int z,
                                final BlockState state, final boolean forward, final int depth,
                                final BlockPos.MutableBlockPos cursor) {
        if (depth >= 8) return false;
        boolean checkBelow = true;
        RailShape shape = state.getValue(PoweredRailBlock.SHAPE);
        switch (shape) {
            case NORTH_SOUTH -> z += forward ? 1 : -1;
            case EAST_WEST -> x += forward ? -1 : 1;
            case ASCENDING_EAST -> {
                x += forward ? -1 : 1;
                if (!forward) { y++; checkBelow = false; }
                shape = RailShape.EAST_WEST;
            }
            case ASCENDING_WEST -> {
                x += forward ? -1 : 1;
                if (forward) { y++; checkBelow = false; }
                shape = RailShape.EAST_WEST;
            }
            case ASCENDING_NORTH -> {
                z += forward ? 1 : -1;
                if (!forward) { y++; checkBelow = false; }
                shape = RailShape.NORTH_SOUTH;
            }
            case ASCENDING_SOUTH -> {
                z += forward ? 1 : -1;
                if (forward) { y++; checkBelow = false; }
                shape = RailShape.NORTH_SOUTH;
            }
            default -> { }
        }
        return powered(rail, level, x, y, z, forward, depth, shape, cursor)
            || checkBelow && powered(rail, level, x, y - 1, z, forward, depth, shape, cursor);
    }

    private static boolean powered(final PoweredRailBlock rail, final Level level, final int x, final int y, final int z,
                                   final boolean forward, final int depth, final RailShape direction,
                                   final BlockPos.MutableBlockPos cursor) {
        cursor.set(x, y, z);
        final BlockState state = level.getBlockState(cursor);
        if (!state.is(rail)) return false;
        final RailShape shape = state.getValue(PoweredRailBlock.SHAPE);
        if (direction == RailShape.EAST_WEST && (shape == RailShape.NORTH_SOUTH
            || shape == RailShape.ASCENDING_NORTH || shape == RailShape.ASCENDING_SOUTH)) return false;
        if (direction == RailShape.NORTH_SOUTH && (shape == RailShape.EAST_WEST
            || shape == RailShape.ASCENDING_EAST || shape == RailShape.ASCENDING_WEST)) return false;
        // No callbacks retain this cursor. Nested searches restore their own coordinates before every read.
        return state.getValue(PoweredRailBlock.POWERED) && (level.hasNeighborSignal(cursor)
            || find(rail, level, x, y, z, state, forward, depth + 1, cursor));
    }
}
