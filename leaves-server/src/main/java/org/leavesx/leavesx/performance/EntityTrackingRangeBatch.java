package org.leavesx.leavesx.performance;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.runtime.LeavesXSnapshotBatch;

/** 按实体与玩家的比较次数分片；工作线程拿到的数据中没有实体、玩家或区块引用。 */
public final class EntityTrackingRangeBatch {
    private static final int COMPONENTS = 5;
    private static final int MAX_CACHED_ROWS = 16_384;
    public static final int PIPELINE_ROWS = 128;
    private static final int COMPARISONS_PER_TASK = 1024;
    private static final int[] NO_PLAYERS = new int[0];
    private static final boolean[] NO_RESULTS = new boolean[0];
    private static final LongAdder SNAPSHOT_RECOMPUTATIONS = new LongAdder();
    private static final LongAdder SLOT_RECOMPUTATIONS = new LongAdder();
    private double[] entityData = new double[0];
    private int[][] playerIndices = new int[0][];
    private boolean[][] results = new boolean[0][];
    private int[] offsets = new int[1];
    private double[] playerPositions;
    private double[] playerViewDistances;
    private int preparedPlayerCount;
    private double uniformView = Double.NaN;
    private boolean optimizedRows;
    private int rows;

    /** 提交前发现实体、玩家或区块输入已变化，只重算当前实体。 */
    public static void recordSnapshotRecomputation() {
        SNAPSHOT_RECOMPUTATIONS.increment();
    }

    /** 插件事件改变追踪列表槽位后，按 Paper 原循环处理当前实体。 */
    public static void recordSlotRecomputation() {
        SLOT_RECOMPUTATIONS.increment();
    }

    public static long snapshotRecomputations() {
        return SNAPSHOT_RECOMPUTATIONS.sum();
    }

    public static long slotRecomputations() {
        return SLOT_RECOMPUTATIONS.sum();
    }

    public void prepare(final int rows, final double[] playerPositions, final double[] playerViewDistances) {
        this.prepare(rows, playerPositions, playerViewDistances, playerViewDistances.length);
    }

    public void prepare(final int rows, final double[] playerPositions, final double[] playerViewDistances, final int playerCount) {
        if (rows < 0) throw new IllegalArgumentException("Negative row count");
        if (this.playerIndices.length < rows) {
            final int cacheLimit = Math.min(MAX_CACHED_ROWS,
                LeavesXRuntime.asyncSettings().kernels().cacheMaxCandidates());
            int capacity = Math.max(rows, Math.max(64, this.playerIndices.length + (this.playerIndices.length >>> 1)));
            // 预算仅控制完成后的保留容量，不截断本批追踪行。
            if (cacheLimit > 0 && rows <= cacheLimit) capacity = Math.min(capacity, cacheLimit);
            this.entityData = new double[Math.multiplyExact(capacity, COMPONENTS)];
            this.playerIndices = new int[capacity][];
            this.results = new boolean[capacity][];
            this.offsets = new int[Math.addExact(capacity, 1)];
        }
        this.rows = rows;
        this.offsets[0] = 0;
        this.playerPositions = playerPositions;
        this.playerViewDistances = playerViewDistances;
        this.preparedPlayerCount = playerCount;
        this.optimizedRows = (long)rows * playerCount >= 256
            && LeavesXRuntime.asyncSettings().kernels().trackerRowKernel();
        this.uniformView = this.optimizedRows ? uniformViewDistance(playerViewDistances, playerCount) : Double.NaN;
    }

    /** 行按实体原顺序写入；结果数组由该实体所有，但不同数值分片只写互不重叠的区间。 */
    public void setRow(final int row, final double x, final double y, final double z, final double range,
                       final double rangeY, final int[] players, final int count, final boolean[] result) {
        if (row < 0 || row >= this.rows || count < 0 || count > players.length || count > result.length) {
            throw new IllegalArgumentException("Invalid tracker row");
        }
        final int offset = row * COMPONENTS;
        this.entityData[offset] = x;
        this.entityData[offset + 1] = y;
        this.entityData[offset + 2] = z;
        this.entityData[offset + 3] = range;
        this.entityData[offset + 4] = rangeY;
        this.playerIndices[row] = players;
        this.results[row] = result;
        this.offsets[row + 1] = Math.addExact(this.offsets[row], count);
    }

    public int comparisonCount() {
        return this.offsets[this.rows];
    }

    public void setEmptyRow(final int row) {
        if (row < 0 || row >= this.rows) throw new IllegalArgumentException("Invalid tracker row");
        // 空行不会进入数值内核，无需重写五个坐标字段，也不持有旧结果数组。
        this.playerIndices[row] = NO_PLAYERS;
        this.results[row] = NO_RESULTS;
        this.offsets[row + 1] = this.offsets[row];
    }

    public void compute(final int start, final int end) {
        if (start < 0 || end < start || end > this.comparisonCount()) throw new IllegalArgumentException("Invalid comparison range");
        compute(this.entityData, this.playerIndices, this.results, this.offsets,
            this.playerPositions, this.playerViewDistances, this.uniformView, 0, this.rows, start, end);
    }

    /**
     * 只发布已捕获的行与玩家数值数组；后续追加行或扩容玩家帧不会改变已发布片段。
     * 结果由不同片段独占，调用者必须先结束整个流水批次，再清空或重新 prepare。
     */
    public void submitRows(final LeavesXSnapshotBatch batch, final int fromRow, final int toRow,
                           final double[] positions, final double[] views) {
        this.submitRows(batch, fromRow, toRow, positions, views, views.length);
    }

    public void submitRows(final LeavesXSnapshotBatch batch, final int fromRow, final int toRow,
                           final double[] positions, final double[] views, final int playerCount) {
        if (fromRow < 0 || toRow < fromRow || toRow > this.rows) throw new IllegalArgumentException("Invalid row range");
        final double[] data = this.entityData;
        final int[][] players = this.playerIndices;
        final boolean[][] output = this.results;
        final int[] prefix = this.offsets;
        final int limit = prefix[toRow];
        final long comparisons = (long)limit - prefix[fromRow];
        if (comparisons == 0) return;
        // 只检查当前帧中的玩家；预留容量的零值不应把统一视距误判为混合视距。
        final double uniform = !this.optimizedRows ? Double.NaN
            : views == this.playerViewDistances && playerCount == this.preparedPlayerCount
                ? this.uniformView : uniformViewDistance(views, playerCount);
        final int taskSize = LeavesXSnapshotBatch.partitionSize((long)this.rows * views.length, COMPARISONS_PER_TASK);
        for (int from = prefix[fromRow]; from < limit;) {
            final int start = from;
            // 行数与比较数分别取整可能留下极短尾片，合并尾片避免再唤醒一个线程。
            final int remaining = limit - start;
            final int end = (long)remaining <= (long)taskSize + (taskSize >>> 1) ? limit : start + taskSize;
            batch.submit(end - start, () -> compute(data, players, output, prefix,
                positions, views, uniform, fromRow, toRow, start, end));
            from = end;
        }
    }

    private static void compute(final double[] entityData, final int[][] playerIndices,
                                final boolean[][] results, final int[] offsets,
                                final double[] positions, final double[] views, final double uniformView,
                                final int fromRow, final int toRow, final int start, final int end) {
        if (start == end) return;
        int low = fromRow;
        int high = toRow;
        // 找到首个非空行，重复前缀代表没有附近玩家的实体。
        while (low < high) {
            final int middle = (low + high) >>> 1;
            if (offsets[middle + 1] <= start) low = middle + 1;
            else high = middle;
        }
        for (int row = low; row < toRow && offsets[row] < end; row++) {
            // 空行没有比较任务，不读取坐标和玩家数组，仍保留原行布局及分片边界。
            if (offsets[row] == offsets[row + 1]) continue;
            final int from = Math.max(start, offsets[row]) - offsets[row];
            final int to = Math.min(end, offsets[row + 1]) - offsets[row];
            final int entityOffset = row * COMPONENTS;
            final double x = entityData[entityOffset];
            final double y = entityData[entityOffset + 1];
            final double z = entityData[entityOffset + 2];
            final double range = entityData[entityOffset + 3];
            final double rangeY = entityData[entityOffset + 4];
            final double rangeYSquared = rangeY * rangeY;
            final boolean sharedView = !Double.isNaN(uniformView);
            final double horizontal = sharedView ? Math.min(range, uniformView) : 0.0;
            final double sharedHorizontalSquared = horizontal * horizontal;
            final int[] players = playerIndices[row];
            final boolean[] result = results[row];
            if (sharedView) {
                // 将统一视距分支移到比较循环外，混合视距路径仍逐项执行原公式。
                for (int index = from; index < to; index++) {
                    final int playerOffset = players[index] * 3;
                    final double dx = positions[playerOffset] - x;
                    final double dz = positions[playerOffset + 2] - z;
                    boolean visible = dx * dx + dz * dz <= sharedHorizontalSquared;
                    if (visible && rangeY >= 0.0) {
                        final double dy = positions[playerOffset + 1] - y;
                        visible = dy * dy <= rangeYSquared;
                    }
                    result[index] = visible;
                }
                continue;
            }
            for (int index = from; index < to; index++) {
                final int player = players[index];
                final int playerOffset = player * 3;
                final double dx = positions[playerOffset] - x;
                final double dz = positions[playerOffset + 2] - z;
                final double horizontalLimit = Math.min(range, views[player]);
                final double horizontalSquared = horizontalLimit * horizontalLimit;
                boolean visible = dx * dx + dz * dz <= horizontalSquared;
                if (visible && rangeY >= 0.0) {
                    final double dy = positions[playerOffset + 1] - y;
                    visible = dy * dy <= rangeYSquared;
                }
                result[index] = visible;
            }
        }
    }

    /** 混合视距及 NaN 留给逐玩家公式，负值、无穷值和带符号零仍保持 Math.min 的语义。 */
    private static double uniformViewDistance(final double[] views, final int count) {
        if (count < 0 || count > views.length) throw new IllegalArgumentException("Invalid player count");
        if (count == 0) return Double.NaN;
        final double first = views[0];
        final long bits = Double.doubleToRawLongBits(first);
        for (int index = 1; index < count; index++) {
            if (Double.doubleToRawLongBits(views[index]) != bits) return Double.NaN;
        }
        return first;
    }

    public void clear() {
        final int cacheLimit = Math.min(MAX_CACHED_ROWS,
            LeavesXRuntime.asyncSettings().kernels().cacheMaxCandidates());
        if (cacheLimit == 0 || this.playerIndices.length > cacheLimit) {
            // 世界持有该批次；峰值之后同时释放数值与引用数组，避免长期保留整批容量。
            this.entityData = new double[0];
            this.playerIndices = new int[0][];
            this.results = new boolean[0][];
            this.offsets = new int[1];
        } else {
            Arrays.fill(this.playerIndices, 0, this.rows, null);
            Arrays.fill(this.results, 0, this.rows, null);
        }
        this.playerPositions = null;
        this.playerViewDistances = null;
        this.preparedPlayerCount = 0;
        this.uniformView = Double.NaN;
        this.optimizedRows = false;
        this.rows = 0;
        this.offsets[0] = 0;
    }
}
