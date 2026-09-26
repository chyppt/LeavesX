package org.leavesx.leavesx.random;

import net.minecraft.core.BlockPos;

/** Tick-thread-only, bounded reuse of immutable positions. Random tick callbacks may retain these objects. */
public final class RandomTickPositionCache {
    private final BlockPos[] positions = new BlockPos[1024];

    public BlockPos get(final int x, final int y, final int z) {
        final int slot = it.unimi.dsi.fastutil.HashCommon.mix((x * 31 + y) * 31 + z) & (this.positions.length - 1);
        final BlockPos cached = this.positions[slot];
        if (cached != null && cached.getX() == x && cached.getY() == y && cached.getZ() == z) return cached;
        final BlockPos position = new BlockPos(x, y, z);
        this.positions[slot] = position;
        return position;
    }
}
