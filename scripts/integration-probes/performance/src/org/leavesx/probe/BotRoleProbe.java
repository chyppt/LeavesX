package org.leavesx.probe;

import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.bot.BotList;
import org.leavesmc.leaves.bot.BotUtil;
import org.leavesmc.leaves.bot.MojangAPI;
import org.leavesmc.leaves.event.bot.BotRemoveEvent;
import org.leavesx.leavesx.config.LeavesXRuntime;

/** End-to-end builder/creation check; cached empty skin avoids external network dependency in the fixture. */
final class BotRoleProbe {
    static void verify(final JavaPlugin plugin, final Runnable success) throws Exception {
        final String rawName = "111";
        final String fullName = BotUtil.getFullName(rawName);
        final String directName = BotUtil.getFullName("112");
        final var previous = BotList.INSTANCE.getBotByName(fullName);
        if (previous != null) BotList.INSTANCE.removeBot(previous, BotRemoveEvent.RemoveReason.INTERNAL, null, false, false);
        final var previousDirect = BotList.INSTANCE.getBotByName(directName);
        if (previousDirect != null) BotList.INSTANCE.removeBot(previousDirect, BotRemoveEvent.RemoveReason.INTERNAL, null, false, false);
        final var cacheField = MojangAPI.class.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked") final Map<String, String[]> cache = (Map<String, String[]>) cacheField.get(null);
        cache.put(rawName, null);
        cache.put("112", null);
        LeavesConfig.modify.fakeplayer.useSkinCache = true;
        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "bot create 111 111 minecraft:overworld 40 65 40 测试")) {
            throw new AssertionError("console creation command rejected");
        }
        final int[] attempts = {0};
        final boolean[] directStarted = {false};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            final var bot = BotList.INSTANCE.getBotByName(fullName);
            ++attempts[0];
            if (bot == null && attempts[0] < 100) return;
            if (bot == null || !bot.createState.role().equals("测试") || !bot.getScoreboardName().equals(fullName)) {
                task.cancel();
                plugin.getLogger().severe("BOT_ROLE_PROBE_FAIL: creation or role storage");
                return;
            }
            if (bot.listName.getString().contains("[测试]") != LeavesXRuntime.fakeplayerRoleEnabled()) {
                task.cancel();
                plugin.getLogger().severe("BOT_ROLE_PROBE_FAIL: TAB role wrapper");
                return;
            }
            if (!directStarted[0]) {
                bot.getBukkitEntity().addAttachment(plugin, "bukkit.command.bot", true);
                Bukkit.dispatchCommand(bot.getBukkitEntity(), "bot create 112 测试");
                directStarted[0] = true;
                return;
            }
            final var direct = BotList.INSTANCE.getBotByName(directName);
            if (direct == null && attempts[0] < 100) return;
            task.cancel();
            if (direct == null || !direct.createState.role().equals("测试")
                || direct.listName.getString().contains("[测试]") != LeavesXRuntime.fakeplayerRoleEnabled()) {
                plugin.getLogger().severe("BOT_ROLE_PROBE_FAIL: player direct Chinese command");
                return;
            }
            BotList.INSTANCE.removeBot(direct, BotRemoveEvent.RemoveReason.INTERNAL, null, false, false);
            BotList.INSTANCE.removeBot(bot, BotRemoveEvent.RemoveReason.INTERNAL, null, false, false);
            plugin.getLogger().info("BOT_ROLE_PROBE_PASS: three-character names, player direct Chinese role, console coordinates, TAB wrapper, removal");
            success.run();
        }, 1L, 1L);
    }
}
