package org.leavesx.leavesx.gameplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Operator-only tool. Its persistent marker is independent of the visible item name. */
public final class EntityAiTool {
    private static final NamespacedKey KEY = new NamespacedKey("leavesx", "entity_ai_toggle");

    private EntityAiTool() {
    }

    public static void give(final CommandSender sender) {
        if (!sender.isOp()) return;
        if (!(sender instanceof Player player)) {
            sender.sendMessage("此命令只能由游戏内的 OP 使用。");
            return;
        }
        final ItemStack item = new ItemStack(Material.STICK);
        item.editMeta(meta -> {
            meta.displayName(Component.text("禁用实体AI", NamedTextColor.GOLD));
            meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        });
        if (!player.getInventory().addItem(item).isEmpty()) {
            player.sendMessage("背包已满，请留出一个空位后重试。");
            return;
        }
        player.sendMessage("已获得禁用实体AI木棍，右键生物切换；村民保留交易和补货。");
    }

    /** Called after Bukkit interaction cancellation and vanilla distance validation, on the server thread. */
    public static boolean interact(final Player player, final Entity entity, final ItemStack item) {
        if (item.getType() != Material.STICK
            || !Byte.valueOf((byte) 1).equals(item.getPersistentDataContainer().get(KEY, PersistentDataType.BYTE))) {
            return false;
        }
        if (!player.isOp()) {
            player.sendMessage("此物品仅 OP 可以使用。");
            return true;
        }
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            player.sendMessage("旁观模式下不能修改实体 AI。");
            return true;
        }
        if (!(entity instanceof Mob mob)) {
            player.sendMessage("这个实体没有可以切换的生物 AI。");
            return true;
        }
        final boolean disabled;
        if (mob instanceof Villager villager) {
            disabled = !villager.leavesX$isTradeOnly();
            villager.leavesX$setTradeOnly(disabled);
        } else {
            disabled = !mob.isNoAi();
            mob.getNavigation().stop();
            mob.setNoAi(disabled);
        }
        player.sendMessage(Component.text(disabled ? "已禁用实体 AI" : "已启用实体 AI", NamedTextColor.GREEN)
            .append(Component.text(disabled && mob instanceof Villager ? "，村民仍可交易和补货。" : "。")));
        return true;
    }
}
