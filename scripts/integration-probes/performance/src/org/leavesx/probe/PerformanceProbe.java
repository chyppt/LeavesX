package org.leavesx.probe;

import net.minecraft.core.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.Powerable;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.leavesx.leavesx.config.LeavesXRuntime;

/** Disposable-server checks for scheduler ownership, biome mutation and rail event cancellation. */
public final class PerformanceProbe extends JavaPlugin implements Listener {
    private World world;
    private boolean cancelRail;
    private int redstoneEvents;

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            this.world = Bukkit.getWorlds().getFirst();
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                final boolean virtual = Thread.currentThread().isVirtual();
                Bukkit.getScheduler().runTask(this, () -> this.validate(virtual));
            });
        }, 20L);
    }

    private void validate(final boolean asyncVirtual) {
        try {
            check(Bukkit.isPrimaryThread() && !Thread.currentThread().isVirtual(), "synchronous scheduler ownership");
            check(asyncVirtual == LeavesXRuntime.configuration().extensions().virtualBukkitScheduler(), "async executor mode");
            // CraftWorld.setBiome only mutates loaded chunks; the probe must load its fixture first.
            this.world.getChunkAt(0, 0).load();
            final var level = ((CraftWorld)this.world).getHandle();
            final var manager = level.getBiomeManager();
            final BlockPos sample = new BlockPos(6, 64, 6);
            final var oldBiome = manager.leavesX$getBiomeCached(sample);
            final Biome next = this.world.getBiome(6, 64, 6) == Biome.DESERT ? Biome.PLAINS : Biome.DESERT;
            for (int x = 0; x <= 12; x += 4) for (int y = 56; y <= 72; y += 4) for (int z = 0; z <= 12; z += 4) {
                this.world.setBiome(x, y, z, next);
            }
            check(manager.getBiome(sample) != oldBiome, "biome mutation fixture");
            check(manager.getBiome(sample) == manager.leavesX$getBiomeCached(sample), "cache reflects Bukkit setBiome immediately");

            for (int x = 0; x <= 10; x++) {
                this.world.getBlockAt(x, 89, 0).setType(Material.STONE, false);
                this.world.getBlockAt(x, 90, 0).setBlockData(Bukkit.createBlockData("minecraft:powered_rail[shape=east_west,powered=false]"), false);
            }
            this.cancelRail = true;
            this.world.getBlockAt(0, 90, 1).setType(Material.REDSTONE_BLOCK);
            check(this.powered(0) && !this.powered(3), "plugin can cancel rail power");
            this.world.getBlockAt(0, 90, 1).setType(Material.AIR);
            this.cancelRail = false;
            this.world.getBlockAt(0, 90, 1).setType(Material.REDSTONE_BLOCK);
            check(this.powered(3) && !this.powered(9), "vanilla rail activation range");
            this.world.getBlockAt(0, 90, 1).setType(Material.AIR);
            for (int x = 0; x <= 10; x++) check(!this.powered(x), "rail depowering");
            check(this.redstoneEvents > 0, "Bukkit redstone events");
            new NamedMobProbe(this).verify(this.world);
            BackgroundWorkProbe.verify(this, this.world);
            BotRoleProbe.verify(this, () -> WaterPathProbe.verify(this, this.world,
                () -> this.getLogger().info("PERFORMANCE_PROBE_PASS: asyncVirtual=" + asyncVirtual
                    + ", sync main thread, biome mutation, rail cancellation/range/depowering, water pathfinding")));
        } catch (Throwable failure) {
            this.getLogger().log(java.util.logging.Level.SEVERE, "PERFORMANCE_PROBE_FAIL", failure);
        }
    }

    private boolean powered(int x) { return ((Powerable)this.world.getBlockAt(x, 90, 0).getBlockData()).isPowered(); }

    @EventHandler public void onRedstone(BlockRedstoneEvent event) {
        if (event.getBlock().getWorld() == this.world && event.getBlock().getY() == 90 && event.getBlock().getZ() == 0) {
            this.redstoneEvents++;
            if (this.cancelRail && event.getBlock().getX() == 3) event.setNewCurrent(event.getOldCurrent());
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
