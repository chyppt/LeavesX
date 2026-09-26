package org.leavesx.leavesx.path;

import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import org.jspecify.annotations.Nullable;

/** One privately owned search input. Only immutable block states and primitive mob data cross the thread boundary. */
final class WaterPathSnapshot {
    private static final int RADIUS = 24;
    private static final int SIZE = RADIUS * 2 + 1;
    private static final int AIR = 1, WATER = 2, EMPTY_FLUID = 4, WATER_PATH = 8;

    private final Body body;
    private final int minX, minY, minZ;
    private final PalettedContainer<BlockState>[] sections;
    private final int sectionX, sectionY, sectionZ;
    private final Map<BlockState, Byte> paletteFlags;
    private final BitSet reads = new BitSet(SIZE * SIZE * SIZE);

    private WaterPathSnapshot(final Body body, final PalettedContainer<BlockState>[] sections, final Map<BlockState, Byte> paletteFlags) {
        this.body = body;
        this.minX = body.start.getX() - RADIUS;
        this.minY = body.start.getY() - RADIUS;
        this.minZ = body.start.getZ() - RADIUS;
        this.sections = sections;
        this.sectionX = this.minX >> 4;
        this.sectionY = this.minY >> 4;
        this.sectionZ = this.minZ >> 4;
        this.paletteFlags = paletteFlags;
    }

    /** Returns null rather than loading a chunk to service a speculative background search. */
    @SuppressWarnings("unchecked")
    static @Nullable WaterPathSnapshot capture(final PathNavigationRegion region, final Body body) {
        // A 49-block side touches exactly four sections. Copy compressed palettes instead of 117,649 individual blocks.
        final PalettedContainer<BlockState>[] sections = new PalettedContainer[64];
        final Map<BlockState, Byte> paletteFlags = new IdentityHashMap<>();
        paletteFlags.put(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), classify(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
        final int sx = (body.start.getX() - RADIUS) >> 4;
        final int sy = (body.start.getY() - RADIUS) >> 4;
        final int sz = (body.start.getZ() - RADIUS) >> 4;
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                final var chunk = region.getChunkIfLoaded(sx + x, sz + z);
                if (chunk == null) return null;
                for (int y = 0; y < 4; y++) {
                    final int blockY = (sy + y) << 4;
                    if (!region.isOutsideBuildHeight(blockY)) {
                        final var copy = chunk.getSection(chunk.getSectionIndex(blockY)).getStates().copy();
                        final var palette = copy.leavesX$getData().palette();
                        // Global palettes can contain every registered state; avoid turning capture into a registry scan.
                        if (palette.getSize() > 512) return null;
                        for (int i = 0; i < palette.getSize(); i++) paletteFlags.computeIfAbsent(palette.valueFor(i), WaterPathSnapshot::classify);
                        sections[(y * 4 + z) * 4 + x] = copy;
                    }
                }
            }
        }
        return new WaterPathSnapshot(body, sections, paletteFlags);
    }

    private static byte classify(final BlockState state) {
        final var fluid = state.getFluidState();
        return (byte) ((state.isAir() ? AIR : 0) | (fluid.is(FluidTags.WATER) ? WATER : 0)
            | (fluid.isEmpty() ? EMPTY_FLUID : 0) | (state.isPathfindable(PathComputationType.WATER) ? WATER_PATH : 0));
    }

    /** Each instance is searched once. Reuses vanilla neighbor ordering, costs and A*, including partial paths. */
    Result search(final List<BlockPos> targets, final int visited, final float length, final int reach, final float multiplier) {
        final Path path;
        try {
            path = new PathFinder(new Evaluator(), visited).leavesX$findSnapshotPath(targets, length, reach, multiplier);
        } catch (final OutsideSnapshot outside) {
            // Treating the edge as a wall would silently change which route wins. Recompute the whole search instead.
            return new Result(null, new long[0], new BlockState[0], new byte[0], true);
        }
        final long[] positions = new long[this.reads.cardinality()];
        final BlockState[] observed = new BlockState[positions.length];
        final byte[] observedFlags = new byte[positions.length];
        int destination = 0;
        for (int index = this.reads.nextSetBit(0); index >= 0; index = this.reads.nextSetBit(index + 1)) {
            positions[destination] = BlockPos.asLong(this.minX + index % SIZE,
                this.minY + index / (SIZE * SIZE), this.minZ + index / SIZE % SIZE);
            observed[destination] = this.stateAt(this.minX + index % SIZE,
                this.minY + index / (SIZE * SIZE), this.minZ + index / SIZE % SIZE);
            observedFlags[destination] = this.paletteFlags.get(observed[destination]);
            destination++;
        }
        // The full cube can now be collected; an unobserved result retains only the cells the algorithm actually read.
        return new Result(path, positions, observed, observedFlags, false);
    }

    private int flagsAt(final int x, final int y, final int z) {
        final int dx = x - this.minX, dy = y - this.minY, dz = z - this.minZ;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= SIZE || dy >= SIZE || dz >= SIZE) throw OutsideSnapshot.INSTANCE;
        final int index = (dy * SIZE + dz) * SIZE + dx;
        this.reads.set(index);
        return this.paletteFlags.get(this.stateAt(x, y, z));
    }

    private BlockState stateAt(final int x, final int y, final int z) {
        final var section = this.sections[(((y >> 4) - this.sectionY) * 4 + (z >> 4) - this.sectionZ) * 4 + (x >> 4) - this.sectionX];
        return section == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : section.get(x & 15, y & 15, z & 15);
    }

    private final class Evaluator extends SwimNodeEvaluator {
        Evaluator() {
            super(body.breaching);
            this.entityWidth = body.width;
            this.entityDepth = body.width;
            this.entityHeight = body.height;
        }

        @Override public Node getStart() { return this.getNode(body.start); }
        @Override protected float leavesX$pathfindingMalus(final PathType type) {
            return type == PathType.WATER ? body.waterMalus : body.breachMalus;
        }
        @Override protected boolean leavesX$emptyFluid(final int x, final int y, final int z) {
            return (flagsAt(x, y, z) & EMPTY_FLUID) != 0;
        }
        @Override protected PathType getCachedBlockType(final int x, final int y, final int z) {
            // Vanilla SwimNodeEvaluator caches classifications. This cache is per search, never shared with a live context.
            return this.types.computeIfAbsent(BlockPos.asLong(x, y, z), ignored -> this.classifyVolume(x, y, z));
        }
        private final it.unimi.dsi.fastutil.longs.Long2ObjectMap<PathType> types = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

        private PathType classifyVolume(final int x, final int y, final int z) {
            int last = 0;
            for (int xx = x; xx < x + this.entityWidth; xx++) {
                for (int yy = y; yy < y + this.entityHeight; yy++) {
                    for (int zz = z; zz < z + this.entityDepth; zz++) {
                        last = flagsAt(xx, yy, zz);
                        final int below = flagsAt(xx, yy - 1, zz);
                        if ((last & (EMPTY_FLUID | AIR)) == (EMPTY_FLUID | AIR) && (below & WATER_PATH) != 0) return PathType.BREACH;
                        if ((last & WATER) == 0) return PathType.BLOCKED;
                    }
                }
            }
            return (last & WATER_PATH) != 0 ? PathType.WATER : PathType.BLOCKED;
        }
    }

    record Body(BlockPos start, int width, int height, float waterMalus, float breachMalus, boolean breaching) {
        static Body capture(final Mob mob, final boolean breaching) {
            final var box = mob.getBoundingBox();
            return new Body(new BlockPos(Mth.floor(box.minX), Mth.floor(box.minY + 0.5), Mth.floor(box.minZ)),
                Mth.floor(mob.getBbWidth() + 1.0F), Mth.floor(mob.getBbHeight() + 1.0F),
                mob.getPathfindingMalus(PathType.WATER), mob.getPathfindingMalus(PathType.BREACH), breaching);
        }
    }

    record Result(@Nullable Path path, long[] positions, BlockState[] states, byte[] flags, boolean outside) {
        boolean matches(final PathNavigationRegion region) {
            final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            final Map<BlockState, Byte> currentFlags = new IdentityHashMap<>();
            for (int i = 0; i < this.positions.length; i++) {
                if (region.getBlockStateIfLoaded(position.set(this.positions[i])) != this.states[i]) return false;
                // Datapack reload may replace fluid tags without replacing block-state identities.
                if (currentFlags.computeIfAbsent(this.states[i], WaterPathSnapshot::classify) != this.flags[i]) return false;
            }
            return true;
        }
    }

    private static final class OutsideSnapshot extends RuntimeException {
        private static final OutsideSnapshot INSTANCE = new OutsideSnapshot();
        private OutsideSnapshot() { super(null, null, false, false); }
    }
}
