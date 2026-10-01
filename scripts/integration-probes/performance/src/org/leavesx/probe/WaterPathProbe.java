package org.leavesx.probe;

import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.path.LeavesXAsyncPath;
import org.leavesx.leavesx.path.WaterPathfinding;

/** Disposable aquarium; checks real navigation entry points and main-thread Paper events. */
final class WaterPathProbe implements Listener {
    private final List<Mob> bodies = new ArrayList<>();
    private boolean cancel;
    private int events;

    static void verify(final JavaPlugin plugin, final World world, final Runnable complete) {
        final var probe = new WaterPathProbe();
        Bukkit.getPluginManager().registerEvents(probe, plugin);
        try { probe.run(plugin, world, complete); }
        catch (Throwable failure) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "WATER_PATH_PROBE_FAIL", failure); }
    }

    private void run(final JavaPlugin plugin, final World world, final Runnable complete) {
        final var level = ((CraftWorld) world).getHandle();
        for (int x = 5; x <= 11; x++) for (int z = 5; z <= 11; z++) world.getChunkAt(x, z).load();
        for (int x = 124; x <= 150; x++) for (int y = 100; y <= 107; y++) for (int z = 124; z <= 142; z++) {
            world.getBlockAt(x, y, z).setType(Material.WATER, false);
        }
        final boolean enabled = LeavesXRuntime.configuration().extensions().asyncWaterPathfinding();
        final var target = new BlockPos(144, 102, 134);
        final List<Path> pending = new ArrayList<>(), expected = new ArrayList<>();
        long captureNanos = 0;
        for (int i = 0; i < 8; i++) {
            // Unregistered bodies stay fixed while results are pending, so physics cannot invalidate the comparison.
            final Mob body = EntityType.DOLPHIN.create(level, EntitySpawnReason.COMMAND);
            if (body == null) throw new AssertionError("dolphin fixture");
            body.setPos(128.5, 102, 128.5 + i);
            this.bodies.add(body);
            body.getNavigation().setRequiredPathLength(32);
            final var region = new PathNavigationRegion(level, body.blockPosition().offset(-48, -48, -48), body.blockPosition().offset(48, 48, 48));
            expected.add(body.getNavigation().pathFinder.findPath(region, body, Set.of(target), 32, 0, 1));
            final long started = System.nanoTime();
            final Path path = body.getNavigation().createPath(target, 0);
            captureNanos += System.nanoTime() - started;
            check(path != null, "real path entry");
            check((path instanceof LeavesXAsyncPath) == enabled, "snapshot switch controls real navigation");
            pending.add(path);
        }
        check(this.events == 8, "Paper receives all requests");
        this.cancel = true;
        check(this.bodies.getFirst().getNavigation().createPath(target.above(), 0) == null, "Paper may cancel pathfinding");
        this.cancel = false;
        final long batchNanos = captureNanos;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                for (int i = 0; i < pending.size(); i++) {
                    final Path path = pending.get(i) instanceof LeavesXAsyncPath async ? async.resolvedPath() : pending.get(i);
                    compare(expected.get(i), path);
                    check(pending.get(i).nodes.size() == path.getNodeCount(), "public nodes expose adopted path");
                    check(this.bodies.get(i).getNavigation().moveTo(pending.get(i), 1), "path adoption initializes navigation");
                    this.bodies.get(i).getNavigation().tick();
                    this.bodies.get(i).getNavigation().stop();
                    if (enabled) check(!this.bodies.get(i).getNavigation().moveTo(pending.get(i), 1), "stopped request cannot be reused");
                }
                plugin.getLogger().info("WATER_PATH_PROBE_PASS: enabled=" + enabled + ", requests=8, owner-thread events and cancellation,"
                    + " golden paths, public nodes, navigation adoption/stop; admission total ms=" + batchNanos / 1_000_000.0);
                plugin.getLogger().info(WaterPathfinding.diagnostics());
                complete.run();
            } catch (Throwable failure) { plugin.getLogger().log(java.util.logging.Level.SEVERE, "WATER_PATH_PROBE_FAIL", failure); }
        }, 2L);
    }

    @EventHandler public void onPathfind(final EntityPathfindEvent event) {
        for (final Mob body : this.bodies) if (body.getUUID().equals(event.getEntity().getUniqueId())) {
            check(Bukkit.isPrimaryThread(), "Paper pathfinding event thread");
            this.events++;
            if (this.cancel) event.setCancelled(true);
            return;
        }
    }

    private static void compare(final Path expected, final Path actual) {
        check(expected != null && actual != null && expected.canReach() == actual.canReach(), "reachability");
        check(expected.getTarget().equals(actual.getTarget()) && expected.getNodeCount() == actual.getNodeCount(), "target and count");
        for (int i = 0; i < expected.getNodeCount(); i++) {
            check(expected.getNodePos(i).equals(actual.getNodePos(i)), "node order " + i);
            check(expected.getNode(i).g == actual.getNode(i).g, "node cost " + i);
        }
    }

    private static void check(final boolean value, final String message) { if (!value) throw new AssertionError(message); }
}
