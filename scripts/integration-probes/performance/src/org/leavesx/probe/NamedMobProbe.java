package org.leavesx.probe;

import com.mojang.authlib.GameProfile;
import io.papermc.paper.event.player.PlayerNameEntityEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** Exercises real name-tag interaction and chunk persistence in a disposable server. */
final class NamedMobProbe implements Listener {
    private static final List<EntityType> TYPES = List.of(
        EntityType.ZOMBIE, EntityType.PILLAGER, EntityType.VILLAGER, EntityType.ENDERMITE);
    private final JavaPlugin plugin;
    private boolean cancelNaming;
    private int nameEvents;

    NamedMobProbe(final JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void verify(final World world) {
        final var level = ((CraftWorld) world).getHandle();
        world.getChunkAt(2, 2).getEntities(); // Also wait for saved entity data to load on restart.
        world.setChunkForceLoaded(2, 2, true);
        final ServerPlayer player = new ServerPlayer(level.getServer(), level,
            new GameProfile(UUID.randomUUID(), "NameTagProbe"), ClientInformation.createDefault());
        player.setPos(10_000, 65, 10_000);

        final boolean restarting = this.plugin.getConfig().contains("named-mobs");
        final List<String> saved = this.plugin.getConfig().getStringList("named-mobs");
        final List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < TYPES.size(); i++) {
            final Mob mob;
            final String name = "LeavesX-name-" + TYPES.get(i).name();
            if (restarting) {
                final var restored = Bukkit.getEntity(UUID.fromString(saved.get(i)));
                check(restored instanceof CraftLivingEntity && restored.getType() == TYPES.get(i),
                    "named mob survived restart: " + TYPES.get(i));
                mob = (Mob) ((CraftLivingEntity) restored).getHandle();
            } else {
                final var entity = (org.bukkit.entity.Mob) world.spawnEntity(
                    new Location(world, 33 + i * 4, 65, 33), TYPES.get(i));
                entity.setInvulnerable(true);
                entity.setGravity(false);
                entity.setRemoveWhenFarAway(true); // Plugin-spawn defaults must not pre-set persistence.
                mob = (Mob) ((CraftLivingEntity) entity).getHandle();
                check(!mob.isPersistenceRequired(), "untagged fixture");
                final ItemStack tag = namedTag(name);
                tag.interactLivingEntity(player, mob, InteractionHand.MAIN_HAND);
                check(tag.isEmpty(), "name tag consumed");
                saved.add(entity.getUniqueId().toString());
            }
            check(mob.isPersistenceRequired() && mob.hasCustomName()
                && name.equals(mob.getCustomName().getString()), "name and persistence: " + TYPES.get(i));
            mobs.add(mob);
        }

        // The synthetic player participates only in this synchronous distance check, never in a world tick.
        level.players().add(player);
        try {
            final Zombie ordinary = new Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, level);
            ordinary.setPos(40, 65, 40);
            ordinary.persistenceRequired = false;
            ordinary.checkDespawn();
            check(ordinary.isRemoved(), "unnamed control despawns beyond the hard distance");
            for (int attempt = 0; attempt < 2_048; attempt++) {
                for (final Mob mob : mobs) {
                    mob.setNoActionTime(601);
                    mob.checkDespawn();
                    check(!mob.isRemoved() && mob.isPersistenceRequired(), "named mob retained beyond despawn distance");
                }
            }
        } finally {
            level.players().remove(player);
        }

        final Zombie cancelled = new Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, level);
        final ItemStack cancelledTag = namedTag("cancelled");
        this.cancelNaming = true;
        try {
            cancelledTag.interactLivingEntity(player, cancelled, InteractionHand.MAIN_HAND);
        } finally {
            this.cancelNaming = false;
        }
        check(cancelledTag.getCount() == 1 && !cancelled.hasCustomName() && !cancelled.isPersistenceRequired(),
            "Paper naming event cancellation preserved");
        check(this.nameEvents > 0, "Paper naming event fired");
        this.plugin.getConfig().set("named-mobs", saved);
        this.plugin.saveConfig();
        this.plugin.getLogger().info((restarting ? "NAMED_MOB_RESTART_PASS" : "NAMED_MOB_PROBE_PASS")
            + ": zombie, pillager, villager, endermite; name tags, far-distance retention, plugin cancellation");
    }

    @EventHandler
    public void onName(final PlayerNameEntityEvent event) {
        this.nameEvents++;
        if (this.cancelNaming) event.setCancelled(true);
    }

    private static ItemStack namedTag(final String name) {
        final ItemStack tag = new ItemStack(Items.NAME_TAG);
        tag.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return tag;
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }
}
