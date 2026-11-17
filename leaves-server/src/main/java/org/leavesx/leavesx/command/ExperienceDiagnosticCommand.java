package org.leavesx.leavesx.command;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.jspecify.annotations.Nullable;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.diagnostics.ExperienceDiagnostics;

/** OP 手动开启的区域经验诊断；不更改任何玩法开关。 */
public final class ExperienceDiagnosticCommand {
    private static final String USAGE = "用法：/leavesx xp start [10-300秒] [世界 x y z] | report | stop";

    private ExperienceDiagnosticCommand() {}

    public static void execute(final CommandSender sender, final String[] args) {
        if (!sender.isOp()) return;
        final String action = args.length < 2 ? "report" : args[1].toLowerCase(Locale.ROOT);
        if (action.equals("start")) {
            start(sender, args);
        } else if ((action.equals("report") || action.equals("stop")) && args.length <= 2) {
            if (action.equals("stop")) ExperienceDiagnostics.stop();
            report(sender);
        } else {
            sender.sendMessage(Component.text(USAGE, NamedTextColor.YELLOW));
        }
    }

    private static void start(final CommandSender sender, final String[] args) {
        if (args.length != 2 && args.length != 3 && args.length != 7) {
            sender.sendMessage(Component.text(USAGE, NamedTextColor.YELLOW));
            return;
        }
        try {
            final int seconds = args.length >= 3 ? Integer.parseInt(args[2]) : 60;
            final Location center;
            if (args.length == 7) {
                final var world = Bukkit.getWorlds().stream()
                    .filter(candidate -> candidate.getName().equals(args[3]) || candidate.getKey().toString().equals(args[3]))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("找不到已加载世界：" + args[3]));
                final double x = Double.parseDouble(args[4]), y = Double.parseDouble(args[5]), z = Double.parseDouble(args[6]);
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                    throw new IllegalArgumentException("坐标必须是有限数字。");
                }
                center = new Location(world, x, y, z);
            } else if (sender instanceof Player player) {
                center = player.getLocation();
            } else {
                throw new IllegalArgumentException("控制台需指定区域，例如：/leavesx xp start 60 world 100 64 200");
            }
            ExperienceDiagnostics.start(((CraftWorld) center.getWorld()).getHandle().dimension(),
                center.getX(), center.getY(), center.getZ(), seconds);
            output(sender, "已开启经验诊断：" + center.getWorld().getKey() + " 中心[" + center.getBlockX() + ","
                + center.getBlockY() + "," + center.getBlockZ() + "] 半径 " + ExperienceDiagnostics.RADIUS
                + " 格，" + seconds + " 秒后停止采样。正常操作猪灵塔后执行 /leavesx xp report。");
            output(sender, settings());
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage(Component.text(exception.getMessage(), NamedTextColor.RED));
        }
    }

    private static void report(final CommandSender sender) {
        final var report = ExperienceDiagnostics.report();
        if (report == null) {
            sender.sendMessage(Component.text("尚未采样，请在猪灵塔击杀位置执行 /leavesx xp start 60。", NamedTextColor.YELLOW));
            return;
        }
        output(sender, "----- 经验掉落诊断（" + (report.running() ? "采样中" : "已停止") + "）-----");
        output(sender, "区域：" + report.world().identifier() + " [" + (int) Math.floor(report.x()) + ","
            + (int) Math.floor(report.y()) + "," + (int) Math.floor(report.z()) + "] 半径 " + ExperienceDiagnostics.RADIUS
            + "，设定时长 " + report.seconds() + " 秒。");
        output(sender, "当前开关：" + settings());
        if (report.rows().isEmpty()) output(sender, "未捕获到区域内的死亡或经验处理，请确认维度、坐标及采样时机。");
        final var rows = new EnumMap<ExperienceDiagnostics.Stage, ExperienceDiagnostics.Row>(ExperienceDiagnostics.Stage.class);
        for (final var row : report.rows()) rows.put(row.stage(), row);
        for (final var stage : ExperienceDiagnostics.Stage.values()) {
            final var row = rows.get(stage);
            if (row == null) {
                output(sender, stage.label() + "：0 次");
            } else {
                output(sender, stage.label() + "：" + row.calls() + " 次，经验输入 " + row.input() + " / 输出 " + row.output());
                output(sender, "  首条样本：" + row.sample());
            }
        }
        snapshot(sender, report.snapshot());
        output(sender, "区域内各阶段独立累计，不能相加或直接当作经验守恒账本；同一生成事件可能被上游调用多次。");
        output(sender, "新增成功表示已加入世界，生成包提交表示已交给连接，两者都不能证明客户端已显示；包仍可能被插件过滤。");
        output(sender, "移除包含正常合并、拾取和卸载；已登记观察者不保证追踪事件通过。以上计数不能单独判定经验丢失。");
        output(sender, "死亡事件监听插件：" + listeners(EntityDeathEvent.getHandlerList()));
        output(sender, "生成事件监听插件：" + listeners(EntitySpawnEvent.getHandlerList()));
        output(sender, "追踪事件监听插件：" + listeners(io.papermc.paper.event.player.PlayerTrackEntityEvent.getHandlerList()));
        output(sender, "合并事件监听插件：" + listeners(com.destroystokyo.paper.event.entity.ExperienceOrbMergeEvent.getHandlerList()));
        output(sender, "移除事件监听插件：" + listeners(org.bukkit.event.entity.EntityRemoveEvent.getHandlerList()));
        output(sender, "拾取事件监听插件：" + listeners(com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent.getHandlerList()));
        output(sender, "经验修改事件监听插件：" + listeners(PlayerExpChangeEvent.getHandlerList()));
    }

    private static void snapshot(final CommandSender sender, final ExperienceDiagnostics.@Nullable OrbSnapshot snapshot) {
        if (snapshot == null) {
            output(sender, "尚无区域存量快照，请等待目标世界至少完成一次 Tick。");
            return;
        }
        output(sender, "最近区域存量（采样第 " + snapshot.elapsedMillis() / 1000.0 + " 秒）："
            + snapshot.orbs() + " 个球 / " + snapshot.experience() + " 点经验"
            + (snapshot.truncated() ? "（达到采样上限，仅显示前 4096 个球）" : ""));
        output(sender, "  无追踪器 " + snapshot.noTracker() + "，无已登记真人观察者 " + snapshot.noHumanViewers()
            + "；Spigot 生成合并半径 " + snapshot.mergeRadius() + "（独立于 LeavesX 经验优化开关）。");
        for (final String sample : snapshot.samples()) output(sender, "  存活球：" + sample);
        for (final String player : snapshot.players()) output(sender, "  附近玩家：" + player);
        if (snapshot.players().isEmpty()) output(sender, "  快照时区域内没有真人玩家。");
    }

    private static String settings() {
        return "密集怪物降频=" + LeavesXRuntime.denseMonsterAiThrottling()
            + "，经验查询优化=" + LeavesXRuntime.experienceOrbMergeOptimization()
            + "，经验合并参数优化=" + LeavesXRuntime.experienceOrbMergeParametersOptimization()
            + "，旧猪灵经验=" + LeavesConfig.modify.oldMC.oldZombiePiglinDrop;
    }

    private static String listeners(final HandlerList handlers) {
        final String names = Arrays.stream(handlers.getRegisteredListeners())
            .map(listener -> listener.getPlugin().getName()).distinct().sorted().collect(Collectors.joining(", "));
        return names.isEmpty() ? "无" : names;
    }

    private static void output(final CommandSender sender, final String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.GRAY));
        // 玩家可以直接提供服务端日志，控制台执行时不重复输出。
        if (sender instanceof Player) MinecraftServer.LOGGER.info("[LeavesX XP] {}", text);
    }
}
