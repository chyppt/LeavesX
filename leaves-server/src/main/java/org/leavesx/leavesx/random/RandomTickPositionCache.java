package org.leavesx.leavesx.random;

import net.minecraft.core.BlockPos;

/** 仅 Tick 线程使用的有界不可变坐标复用；随机 Tick 回调可能保存这些对象。 */
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
