package org.leavesx.probe;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Real portal travel must create a destination ticket without a player or a destination force-load. */
final class PortalTicketProbe {
    private PortalTicketProbe() {}

    static void verify(final JavaPlugin plugin, final World source, final Runnable next) {
        try {
            final World destination = Bukkit.getWorlds().stream()
                .filter(world -> world.getEnvironment() == World.Environment.NETHER)
                .findFirst().orElseThrow();
            buildPortal(source, 4000, 4000);
            buildPortal(destination, 500, 500);
            source.setChunkForceLoaded(250, 250, true);
            final var item = source.dropItem(new Location(source, 4000.5, 90.5, 4000.5), new ItemStack(Material.STONE));
            item.setGravity(false);
            item.setVelocity(new org.bukkit.util.Vector());
            final var id = item.getUniqueId();
            final int[] attempts = {0};
            Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                try {
                    final var transferred = Bukkit.getEntity(id);
                    if (transferred != null && transferred.getWorld() == destination) {
                        final int chunkX = transferred.getLocation().getBlockX() >> 4;
                        final int chunkZ = transferred.getLocation().getBlockZ() >> 4;
                        if (!hasPortalTicket(destination, chunkX, chunkZ)) throw new AssertionError("destination portal ticket missing");
                        if (destination.isChunkForceLoaded(chunkX, chunkZ)) throw new AssertionError("destination must not be force loaded");
                        transferred.remove();
                        source.setChunkForceLoaded(250, 250, false);
                        task.cancel();
                        // Portal tickets expire after 300 ticks. No forced removal is used by this test.
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            try {
                                if (hasPortalTicket(destination, chunkX, chunkZ)) throw new AssertionError("portal ticket did not expire");
                                plugin.getLogger().info("PORTAL_TICKET_PROBE_PASS: real item portal travel, destination ticket, natural expiry");
                                next.run();
                            } catch (Throwable failure) {
                                plugin.getLogger().log(java.util.logging.Level.SEVERE, "PORTAL_TICKET_PROBE_FAIL", failure);
                            }
                        }, 340L);
                    } else if (++attempts[0] >= 100) {
                        throw new AssertionError("portal transfer timeout");
                    }
                } catch (Throwable failure) {
                    task.cancel();
                    source.setChunkForceLoaded(250, 250, false);
                    final var remaining = Bukkit.getEntity(id);
                    if (remaining != null) remaining.remove();
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "PORTAL_TICKET_PROBE_FAIL", failure);
                }
            }, 1L, 2L);
        } catch (Throwable failure) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "PORTAL_TICKET_PROBE_FAIL", failure);
        }
    }

    private static boolean hasPortalTicket(final World world, final int x, final int z) {
        final var tickets = ((CraftWorld) world).getHandle().moonrise$getChunkTaskScheduler()
            .chunkHolderManager.getTicketsCopy().get(new net.minecraft.world.level.ChunkPos(x, z).pack());
        return tickets != null && tickets.stream().anyMatch(ticket -> ticket.getType() == net.minecraft.server.level.TicketType.PORTAL);
    }

    private static void buildPortal(final World world, final int x, final int z) {
        for (int dx = -1; dx <= 2; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                final var block = world.getBlockAt(x + dx, 90 + dy, z);
                if (dx == -1 || dx == 2 || dy == -1 || dy == 3) block.setType(Material.OBSIDIAN, false);
                else block.setBlockData(Bukkit.createBlockData("minecraft:nether_portal[axis=x]"), false);
            }
        }
    }
}
