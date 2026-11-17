package org.leavesx.leavesx.performance;

import org.jspecify.annotations.Nullable;

/** 仅复用相邻实体的相同附近玩家列表，不分配查找结构，也不跨 Tick 留引用。 */
public final class TrackerPlayerIndexCache {
    private static final int MISS_PROBE_LIMIT = 16;
    private @Nullable Object chunk;
    private @Nullable Object players;
    private long revision;
    private int count;
    private int @Nullable [] indices;
    private int misses;
    private boolean reused;
    private boolean disabled;

    public int @Nullable [] get(final Object currentChunk, final Object currentPlayers, final long currentRevision, final int currentCount) {
        if (this.disabled) return null;
        if (currentChunk == this.chunk && currentPlayers == this.players
            && currentRevision == this.revision && currentCount == this.count) {
            this.reused = true;
            return this.indices;
        }
        if (!this.reused && ++this.misses >= MISS_PROBE_LIMIT) this.disabled = true;
        return null;
    }

    /** 缓存数组由首个实体快照持有，整批工作线程结束前不会被写入。 */
    public void remember(final Object currentChunk, final Object currentPlayers, final long currentRevision,
                         final int currentCount, final int[] value) {
        if (this.disabled) return;
        if (currentCount < 0 || currentCount > value.length) throw new IllegalArgumentException("Invalid player count");
        this.chunk = currentChunk;
        this.players = currentPlayers;
        this.revision = currentRevision;
        this.count = currentCount;
        this.indices = value;
    }

    /** 先完成快照屏障，再清除区块和玩家列表引用。 */
    public void clear() {
        this.chunk = null;
        this.players = null;
        this.indices = null;
        this.count = 0;
        this.revision = 0L;
        this.misses = 0;
        this.reused = false;
        this.disabled = false;
    }
}
