package org.leavesx.probe;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftVillager;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerReplenishTradeEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Runs only in the dedicated disposable integration server. */
public final class AiTradeProbe extends JavaPlugin implements Listener {
    private org.bukkit.entity.Villager bukkitVillager;
    private Villager villager;
    private int replenishments;
    private boolean cancelRestock;

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, this::prepare, 20L);
    }

    private void prepare() {
        try {
            var world = Bukkit.getWorlds().getFirst();
            world.getChunkAt(0, 0).load();
            this.checkSeed(world);
            if (this.getConfig().contains("villager")) {
                var loaded = Bukkit.getEntity(UUID.fromString(this.getConfig().getString("villager")));
                check(loaded instanceof CraftVillager, "villager survived restart");
                final Villager restored = ((CraftVillager)loaded).getHandle();
                check(restored.leavesX$isTradeOnly(), "trade-only NBT survived restart");
                restored.leavesX$setTradeOnly(false);
                check(!restored.leavesX$isTradeOnly() && !restored.isNoAi(), "AI re-enabled");
                this.getLogger().info("AI_TRADE_RESTART_PASS: persisted flag and AI restored");
                return;
            }
            world.setTime(4000);
            world.getBlockAt(0, 64, 0).setType(Material.STONE);
            world.getBlockAt(1, 65, 0).setType(Material.LECTERN);
            this.bukkitVillager = (org.bukkit.entity.Villager)world.spawnEntity(new Location(world, 0.5, 65, 0.5), EntityType.VILLAGER);
            this.bukkitVillager.setProfession(org.bukkit.entity.Villager.Profession.LIBRARIAN);
            this.bukkitVillager.setAdult();
            this.bukkitVillager.setGravity(false);
            this.villager = ((CraftVillager)this.bukkitVillager).getHandle();
            this.villager.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(((CraftWorld)world).getHandle().dimension(), new BlockPos(1, 65, 0)));
            this.villager.leavesX$setTradeOnly(true);
            check(!this.villager.isNoAi() && this.villager.leavesX$isTradeOnly(), "trade-only toggle");
            this.villager.tickCount = 20;
            this.bukkitVillager.setRestocksToday(0);
            final var offer = this.villager.getOffers().getFirst();
            offer.increaseUses();
            this.cancelRestock = true;
            this.villager.leavesX$tickTradeOnly(this.villager.level().getMinecraftWorld());
            check(offer.getUses() > 0 && this.replenishments > 0, "cancelled Bukkit restock event");
            this.cancelRestock = false;
            this.bukkitVillager.setRestocksToday(0);
            this.villager.leavesX$tickTradeOnly(this.villager.level().getMinecraftWorld());
            check(offer.getUses() == 0, "trade-only restock");
            this.bukkitVillager.setCustomName("LeavesXTradeProbe");
            this.bukkitVillager.setPersistent(true);
            this.getConfig().set("villager", this.bukkitVillager.getUniqueId().toString());
            this.saveConfig();
            this.getLogger().info("AI_TRADE_PROBE_PASS: restock, plugin cancellation, toggle");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
        } catch (Throwable failure) {
            this.getLogger().log(java.util.logging.Level.SEVERE, "AI_TRADE_PROBE_FAIL", failure);
        }
    }

    @EventHandler public void replenish(VillagerReplenishTradeEvent event) {
        if (event.getEntity() == this.bukkitVillager) {
            this.replenishments++;
            event.setCancelled(this.cancelRestock);
        }
    }

    private void checkSeed(org.bukkit.World world) throws Exception {
        final var level = ((CraftWorld)world).getHandle();
        final boolean expected = Boolean.getBoolean("leavesx.probe.secure");
        check((level.leavesX$secureSeed != null) == expected, "world seed mode");
        if (!expected) return;
        // Keep only a digest in the probe record; never print the world secret to the console.
        final String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(level.worldGenSettings.options().leavesX$featureSeed().orElseThrow().encode()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        final boolean restarting = this.getConfig().contains("seed-digest");
        if (restarting) check(digest.equals(this.getConfig().getString("seed-digest")), "persisted world seed");
        else this.getConfig().set("seed-digest", digest);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                check(world.getChunkAt(x, z).isSlimeChunk()
                    == level.leavesX$secureSeed.isSlimeChunk(x, z, level.spigotConfig.slimeSeed), "Bukkit slime chunk agrees");
            }
        }
        final var located = world.locateNearestStructure(new Location(world, 0, 64, 0),
            org.bukkit.generator.structure.Structure.VILLAGE_PLAINS, 100, false);
        check(located != null, "secure structure locate");
        final String position = located.getLocation().getBlockX() + "," + located.getLocation().getBlockZ();
        if (restarting) check(position.equals(this.getConfig().getString("village")), "structure locate stable on restart");
        else this.getConfig().set("village", position);
        world.getChunkAt(located.getLocation()).load();
        this.getLogger().info("SECURE_SEED_PROBE_PASS: seed mode, persisted digest, slime API, structure locate " + position);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
