package org.leavesx.probe;

import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftVillager;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

/** Natural job-site acquisition, replenishment and sleeping; no Brain memory is injected. */
final class VillagerScheduleProbe {
    private VillagerScheduleProbe() {}

    static void verify(final JavaPlugin plugin, final World world, final Runnable next) {
        final long previousTime = world.getFullTime();
        try {
            world.setChunkForceLoaded(38, 38, true);
            for (int x = 609; x <= 620; x++) for (int z = 609; z <= 620; z++) {
                world.getBlockAt(x, 89, z).setType(Material.STONE, false);
                for (int y = 90; y <= 93; y++) {
                    world.getBlockAt(x, y, z).setType(x == 609 || x == 620 || z == 609 || z == 620 ? Material.GLASS : Material.AIR, false);
                }
                world.getBlockAt(x, 94, z).setType(Material.STONE, false);
            }
            world.getBlockAt(615, 90, 614).setType(Material.LECTERN, false);
            world.getBlockAt(614, 90, 617).setBlockData(Bukkit.createBlockData("minecraft:red_bed[facing=east,part=foot]"), false);
            world.getBlockAt(615, 90, 617).setBlockData(Bukkit.createBlockData("minecraft:red_bed[facing=east,part=head]"), false);
            world.setTime(4000);
            final var bukkit = (org.bukkit.entity.Villager) world.spawnEntity(new Location(world, 614.5, 90, 614.5), EntityType.VILLAGER);
            bukkit.setAdult();
            bukkit.setInvulnerable(true);
            final var villager = ((CraftVillager) bukkit).getHandle();
            final int[] phase = {0}, checks = {0};
            final net.minecraft.world.item.trading.MerchantOffer[] usedOffer = {null};
            Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                try {
                    if (++checks[0] > 180) throw new AssertionError("natural villager schedule timed out in phase " + phase[0]);
                    if (phase[0] == 0 && villager.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE)
                        && !villager.getOffers().isEmpty()) {
                        usedOffer[0] = villager.getOffers().getFirst();
                        usedOffer[0].increaseUses();
                        bukkit.setRestocksToday(0);
                        phase[0] = 1;
                        checks[0] = 0;
                    } else if (phase[0] == 1 && usedOffer[0].getUses() == 0) {
                        world.setTime(14000);
                        phase[0] = 2;
                        checks[0] = 0;
                    } else if (phase[0] == 2 && villager.isSleeping()
                        && villager.getBrain().hasMemoryValue(MemoryModuleType.LAST_SLEPT)) {
                        task.cancel();
                        bukkit.remove();
                        world.setChunkForceLoaded(38, 38, false);
                        world.setFullTime(previousTime);
                        plugin.getLogger().info("VILLAGER_SCHEDULE_PROBE_PASS: natural job site, trade restock, bed acquisition and sleep");
                        next.run();
                    }
                } catch (Throwable failure) {
                    task.cancel();
                    bukkit.remove();
                    world.setChunkForceLoaded(38, 38, false);
                    world.setFullTime(previousTime);
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "VILLAGER_SCHEDULE_PROBE_FAIL", failure);
                }
            }, 10L, 5L);
        } catch (Throwable failure) {
            world.setFullTime(previousTime);
            world.setChunkForceLoaded(38, 38, false);
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "VILLAGER_SCHEDULE_PROBE_FAIL", failure);
        }
    }
}
