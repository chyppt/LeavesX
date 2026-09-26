package org.leavesx.leavesx.worldgen;

/** Caches the deterministic zoom corner, never a biome Holder or chunk reference. */
public final class BiomeCornerCache {
    private static final int MASK = 1023;
    private final int[] x = new int[MASK + 1];
    private final int[] y = new int[MASK + 1];
    private final int[] z = new int[MASK + 1];
    private final byte[] corners = new byte[MASK + 1];

    private static int index(final int x, final int y, final int z) {
        return it.unimi.dsi.fastutil.HashCommon.mix((x * 31 + y) * 31 + z) & MASK;
    }

    public int get(final int x, final int y, final int z) {
        final int slot = index(x, y, z);
        return this.x[slot] == x && this.y[slot] == y && this.z[slot] == z ? this.corners[slot] - 1 : -1;
    }

    public void put(final int x, final int y, final int z, final int corner) {
        final int slot = index(x, y, z);
        this.x[slot] = x;
        this.y[slot] = y;
        this.z[slot] = z;
        this.corners[slot] = (byte)(corner + 1);
    }
}
