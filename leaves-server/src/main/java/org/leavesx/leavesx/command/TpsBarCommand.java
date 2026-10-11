package org.leavesx.leavesx.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.diagnostics.LeavesXWorldTickMetrics;

/** 玩家主动订阅的 BossBar；每秒读取当前世界，离线或关闭配置后释放引用。 */
public final class TpsBarCommand extends Command {
    private static final java.util.Map<java.util.UUID, net.kyori.adventure.bossbar.BossBar> BARS = new java.util.HashMap<>();
    public TpsBarCommand() { super("tpsbar"); setPermission("leavesx.command.tpsbar"); }
    @Override public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!sender.isOp()) return true;
        if (!(sender instanceof Player player)) { sender.sendMessage("/tpsbar 只能由玩家使用。"); return true; }
        if (!LeavesXRuntime.tpsBarEnabled()) { player.sendMessage(Component.text("TPS 条已被配置关闭。", NamedTextColor.YELLOW)); return true; }
        final var previous = BARS.remove(player.getUniqueId());
        if (previous != null) {
            player.hideBossBar(previous);
        } else {
            final var bar = net.kyori.adventure.bossbar.BossBar.bossBar(Component.text("LeavesX 性能"), 1.0F,
                net.kyori.adventure.bossbar.BossBar.Color.GREEN, net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
            BARS.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
            update();
        }
        return true;
    }

    public static void update() {
        if (BARS.isEmpty()) return;
        final Runtime runtime = Runtime.getRuntime();
        final long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        final boolean perWorld = LeavesXWorldTickMetrics.displayWorldMetrics();
        final double globalTps = Math.clamp(org.bukkit.Bukkit.getTPS()[0], 0.0, 20.0);
        final double globalMspt = net.minecraft.server.MinecraftServer.getServer().getAverageTickTimeNanos() / 1_000_000.0;
        final var iterator = BARS.entrySet().iterator();
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            final Player player = org.bukkit.Bukkit.getPlayer(entry.getKey());
            if (player == null || !LeavesXRuntime.tpsBarEnabled() || !player.isOp()) {
                if (player != null) player.hideBossBar(entry.getValue());
                iterator.remove();
                continue;
            }
            final var worldSnapshot = perWorld
                ? LeavesXWorldTickMetrics.snapshot(((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle().level())
                : LeavesXWorldTickMetrics.Snapshot.EMPTY;
            final String scope = perWorld ? player.getWorld().getName() : "全服";
            if (perWorld && !worldSnapshot.available()) {
                // 不把全服数值冒充尚未就绪的世界数据。
                entry.getValue().name(Component.text(scope + " TPS / MSPT 采样中"))
                    .progress(0.0F).color(net.kyori.adventure.bossbar.BossBar.Color.WHITE);
                continue;
            }
            final double tps = perWorld ? worldSnapshot.tps() : globalTps;
            final double mspt = perWorld ? worldSnapshot.mspt() : globalMspt;
            entry.getValue().name(Component.text(String.format(java.util.Locale.ROOT,
                "%s TPS %.1f | MSPT %.1f ms | 全服内存 %d MiB", scope, tps, mspt, used)))
                .progress((float)(tps / 20.0))
                .color(tps >= 18 ? net.kyori.adventure.bossbar.BossBar.Color.GREEN
                    : tps >= 15 ? net.kyori.adventure.bossbar.BossBar.Color.YELLOW : net.kyori.adventure.bossbar.BossBar.Color.RED);
        }
    }
}
