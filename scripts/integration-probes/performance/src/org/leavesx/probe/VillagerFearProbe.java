package org.leavesx.probe;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftVillager;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable controlled fear fixture, not an iron-farm throughput benchmark. */
final class VillagerFearProbe {
    static void verify(final JavaPlugin plugin, final World world, final Runnable next) {
        run(plugin, world, EntityType.ZOMBIE, () -> run(plugin, world, EntityType.PILLAGER, next));
    }

    private static void run(final JavaPlugin plugin, final World world, final EntityType type, final Runnable next) {
        final List<org.bukkit.entity.Entity> fixtures = new ArrayList<>();
        try {
            // Loading blocks alone does not supply entity-ticking tickets in an empty test server.
            for (int cx = 11; cx <= 13; cx++) for (int cz = 11; cz <= 13; cz++) {
                world.setChunkForceLoaded(cx, cz, true);
            }
            for (int x = 187; x <= 213; x++) for (int z = 187; z <= 213; z++) {
                world.getBlockAt(x, 80, z).setType(Material.STONE, false);
                for (int y = 81; y <= 84; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
                world.getBlockAt(x, 85, z).setType(Material.STONE, false);
            }
            final var level = ((CraftWorld) world).getHandle();
            final List<net.minecraft.world.entity.npc.villager.Villager> villagers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                final var bukkit = (org.bukkit.entity.Villager) world.spawnEntity(new Location(world, 200.5, 81, 199.5 + i), EntityType.VILLAGER);
                fixtures.add(bukkit);
                bukkit.setAdult();
                bukkit.setInvulnerable(true);
                bukkit.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0);
                final var villager = ((CraftVillager) bukkit).getHandle();
                // Supply the sleep prerequisite explicitly; this test covers sensing and panic-to-golem,
                // while actual bed acquisition/sleep remains a separate scenario.
                villager.getBrain().setMemory(MemoryModuleType.LAST_SLEPT, level.getGameTime());
                villager.getBrain().eraseMemory(MemoryModuleType.GOLEM_DETECTED_RECENTLY);
                villagers.add(villager);
            }
            final Mob hostile = (Mob) world.spawnEntity(new Location(world, 203.5, 81, 200.5), type);
            fixtures.add(hostile);
            hostile.setAI(false);
            hostile.setInvulnerable(true);
            final int[] checks = {0};
            final boolean[] fearSeen = {false};
            Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                try {
                    checks[0]++;
                    fearSeen[0] |= villagers.stream().anyMatch(v -> v.getBrain().hasMemoryValue(MemoryModuleType.NEAREST_HOSTILE)
                        && v.getBrain().isActive(Activity.PANIC));
                    final var golems = world.getNearbyEntities(new Location(world, 200, 81, 200), 18, 8, 18,
                        e -> e.getType() == EntityType.IRON_GOLEM);
                    if (fearSeen[0] && !golems.isEmpty()) {
                        golems.forEach(org.bukkit.entity.Entity::remove);
                        fixtures.forEach(org.bukkit.entity.Entity::remove);
                        task.cancel();
                        plugin.getLogger().info("VILLAGER_FEAR_PROBE_PASS: " + type + "; hostile sensor, PANIC, iron golem; sleep prerequisite supplied");
                        next.run();
                    } else if (checks[0] >= 80) {
                        throw new AssertionError(type + " fear=" + fearSeen[0] + ", golems=" + golems.size()
                            + ", ticks=" + villagers.getFirst().tickCount
                            + ", hostileMemory=" + villagers.getFirst().getBrain().hasMemoryValue(MemoryModuleType.NEAREST_HOSTILE));
                    }
                } catch (Throwable failure) {
                    task.cancel();
                    fixtures.forEach(org.bukkit.entity.Entity::remove);
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "VILLAGER_FEAR_PROBE_FAIL", failure);
                }
            }, 10L, 5L);
        } catch (Throwable failure) {
            fixtures.forEach(org.bukkit.entity.Entity::remove);
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "VILLAGER_FEAR_PROBE_FAIL", failure);
        }
    }
}
