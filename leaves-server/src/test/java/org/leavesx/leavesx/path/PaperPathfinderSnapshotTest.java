package org.leavesx.leavesx.path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.destroystokyo.paper.entity.PaperPathfinder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.bukkit.Location;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

@Normal
class PaperPathfinderSnapshotTest {
    @Test void failedPendingSearchStillReturnsNullToPlugins() {
        final Mob mob = mock(Mob.class);
        final PathNavigation navigation = mock(PathNavigation.class);
        when(mob.getNavigation()).thenReturn(navigation);
        final var pending = new LeavesXAsyncPath(Set.of(BlockPos.ZERO), CompletableFuture.completedFuture(null));
        when(navigation.createPath(1.0, 2.0, 3.0, 0)).thenReturn(pending);
        when(navigation.getPath()).thenReturn(pending);
        final var api = new PaperPathfinder(mob);
        assertNull(api.findPath(new Location(null, 1, 2, 3)));
        assertNull(api.getCurrentPath());
        assertFalse(api.hasPath());
    }

    @Test void publicNodeListUsesTheSameMutableBackingAsTheResolvedPath() {
        final Path resolved = new Path(new ArrayList<>(List.of(new Node(1, 2, 3), new Node(4, 5, 6))), BlockPos.ZERO, true);
        final var pending = new LeavesXAsyncPath(Set.of(BlockPos.ZERO), CompletableFuture.completedFuture(resolved));
        assertEquals(resolved.nodes, pending.nodes);
        assertTrue(resolved.sameAs(pending));
        pending.nodes.remove(1);
        assertEquals(1, resolved.getNodeCount());
        resolved.replaceNode(0, new Node(7, 8, 9));
        assertEquals(new BlockPos(7, 8, 9), pending.nodes.getFirst().asBlockPos());
    }
}
