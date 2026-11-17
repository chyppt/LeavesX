package org.leavesx.probe.neko;

import cn.dg32z.neko.api.IHandler;
import cn.dg32z.neko.api.PlayerAPI;
import cn.dg32z.neko.api.events.NekoFlagEvent;
import cn.dg32z.neko.api.events.NekoGhostBlockEvent;
import cn.dg32z.neko.api.events.NekoPositionUpdateEvent;
import cn.dg32z.neko.api.events.NekoPredictionEvent;
import cn.dg32z.neko.api.events.NekoRegisterPlayerEvent;
import cn.dg32z.neko.api.events.NekoResyncBlockEvent;
import cn.dg32z.neko.api.events.NekoSetbackEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** 独立诊断插件；不调用检测设置器，不取消事件，不读授权配置。 */
public final class NekoMovementProbe extends JavaPlugin implements Listener {
    private final Set<Class<?>> inspected = ConcurrentHashMap.newKeySet();
    private final Set<MovementTrace> discovered = ConcurrentHashMap.newKeySet();
    private final Set<MovementTrace> stacked = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor metadata = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64), Thread.ofPlatform().daemon().name("LeavesX-Neko-Origin").factory());
    private volatile MovementTrace trace;
    private volatile boolean warned;
    private cn.dg32z.libs.com.github.retrooper.packetevents.event.EventManager packetEvents;
    private MovementWireProbe wireProbe;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        registerWireProbe();
        getServer().getScheduler().runTaskTimer(this, () -> {
            MovementTrace current = trace;
            if (current != null) {
                if (System.nanoTime() >= current.deadline) stopTrace();
                else registerWireProbe();
            }
        }, 20, 20);
        var neko = getServer().getPluginManager().getPlugin("NekoAntiCheat");
        if (neko != null) {
            inspect(neko.getClass());
            getLogger().info("只读探针已加载；Neko 插件声明版本=" + neko.getDescription().getVersion());
        }
    }

    /** 每次采样确认当前实例，兼容 Neko 延迟初始化或替换自己的 PacketEvents。 */
    boolean registerWireProbe() {
        try {
            var api = cn.dg32z.libs.com.github.retrooper.packetevents.PacketEvents.getAPI();
            var current = api == null ? null : api.getEventManager();
            if (current == packetEvents && wireProbe != null) return true;
            unregisterWireProbe();
            if (current == null) return false;
            packetEvents = current;
            wireProbe = new MovementWireProbe(() -> trace);
            current.registerListener(wireProbe);
            return true;
        } catch (RuntimeException | LinkageError error) {
            unregisterWireProbe();
            getLogger().warning("包序观察不可用，继续采集公开事件：" + error.getClass().getSimpleName());
            return false;
        }
    }

    private void unregisterWireProbe() {
        try {
            if (packetEvents != null && wireProbe != null) packetEvents.unregisterListener(wireProbe);
        } catch (RuntimeException | LinkageError error) {
            getLogger().warning("停止包序观察时出现异常：" + error.getClass().getSimpleName());
        } finally {
            packetEvents = null;
            wireProbe = null;
        }
    }

    @Override
    public void onDisable() {
        unregisterWireProbe();
        MovementTrace current = trace;
        stopTrace();
        metadata.shutdown();
        try {
            if (current != null && !current.awaitClosed(1000)) getLogger().warning("采样文件仍在结束写入。");
            if (!metadata.awaitTermination(2, TimeUnit.SECONDS)) metadata.shutdownNow();
        } catch (InterruptedException error) {
            metadata.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) { sender.sendMessage("仅 OP 或控制台可用。"); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
            stopTrace();
            sender.sendMessage("Neko 采样已停止，文件在 plugins/LeavesXNekoProbe/。");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            MovementTrace current = trace;
            sender.sendMessage(current == null ? "Neko 采样未运行。" : "采样中，已触发=" + current.triggered()
                + "，包序观察=" + (wireProbe != null) + "，丢弃记录=" + current.dropped()
                + "，" + current.wireCounts() + "，写入错误=" + current.failure() + "，文件=" + current.path);
            return true;
        }
        if (args.length < 2 || args.length > 3 || !args[0].equalsIgnoreCase("start")) return false;
        var target = getServer().getPlayerExact(args[1]);
        if (target == null) { sender.sendMessage("目标玩家必须在线。"); return true; }
        int seconds = 60;
        try {
            if (args.length == 3) seconds = Integer.parseInt(args[2]);
        } catch (NumberFormatException error) {
            sender.sendMessage("时长填写 10 到 180 秒。"); return true;
        }
        if (seconds < 10 || seconds > 180) { sender.sendMessage("时长填写 10 到 180 秒。"); return true; }
        stopTrace();
        try {
            String filename = Instant.now().toEpochMilli() + "-" + target.getUniqueId() + ".log";
            Path path = getDataFolder().toPath().resolve(filename);
            boolean observing = registerWireProbe();
            // 元信息直接写入文件头，不能随首次异常前的环形历史一起被覆盖。
            trace = new MovementTrace(target.getUniqueId(), seconds, path,
                "probe-version=" + getDescription().getVersion() + " wire-observer=" + observing
                    + " server=" + getServer().getMinecraftVersion());
            sender.sendMessage("开始只读采样 " + target.getName() + "，持续 " + seconds + " 秒；请复现移动误判。");
            if (!observing) sender.sendMessage("包序观察尚未就绪，本次只能记录预测事件，无法核对方块更新与 ACK 顺序。");
        } catch (IOException error) {
            sender.sendMessage("无法创建记录：" + error.getClass().getSimpleName());
        }
        return true;
    }

    private void stopTrace() {
        MovementTrace current = trace;
        trace = null;
        if (current == null) return;
        current.close();
        discovered.remove(current);
        stacked.remove(current);
        getLogger().info("采样结束：" + current.path + "；丢弃=" + current.dropped() + "；写入错误=" + current.failure());
    }

    private MovementTrace selected(PlayerAPI player) {
        MovementTrace current = trace;
        if (current == null || !current.player.equals(player.getUniqueId())) return null;
        if (discovered.add(current)) {
            inspect(player.getClass());
            // 仅在 Neko 事件回调线程读取当前用户的检测集合，不从主线程遍历内部玩家表。
            var checks = player.getChecks();
            if (checks != null) for (IHandler check : checks) if (movement(check)) inspectCheck(check);
        }
        return current;
    }

    private static boolean movement(IHandler check) {
        return check != null && ("MovementValidation".equalsIgnoreCase(check.getCheckName())
            || "MovementValidation".equalsIgnoreCase(check.getConfigName()));
    }

    private static String state(PlayerAPI player) {
        return " txSent=" + player.getLastTransactionSent() + " txReceived=" + player.getLastTransactionReceived()
            + " txPing=" + player.getTransactionPing() + " chunks=" + player.getLoadedChunks()
            + " ground=" + player.isOnGround() + " teleporting=" + player.isTeleporting()
            + " lagging=" + player.isMoveLagging() + " world=" + player.getBukkitWorldUID();
    }

    private void inspectCheck(IHandler check) {
        Class<?> type = check.getClass();
        for (int depth = 0; depth < 4 && type != null && type != Object.class; depth++, type = type.getSuperclass()) inspect(type);
    }

    private void inspect(Class<?> type) {
        if (inspected.size() >= 64 || !inspected.add(type)) return;
        try {
            metadata.execute(() -> {
                try {
                    ClassOrigin.write(type, getDataFolder().toPath().resolve("classes"));
                } catch (IOException | RuntimeException | LinkageError error) {
                    getLogger().warning("类来源记录失败：" + type.getName() + " " + error.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException error) {
            inspected.remove(type);
        }
    }

    private void anomaly(MovementTrace current, String text) {
        current.append(text, true);
        if (!stacked.add(current)) return;
        StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).forEach(frame -> {
            if (frame.getClassName().startsWith("cn.dg32z.neko.") || frame.getClassName().startsWith("net.nyana.")) {
                inspect(frame.getDeclaringClass());
                current.append("stack=" + frame, true);
            }
        });
    }

    private void observe(Runnable action) {
        if (trace == null) return;
        try { action.run(); }
        catch (RuntimeException | LinkageError error) {
            if (!warned) {
                warned = true;
                getLogger().warning("Neko 事件读取失败：" + error.getClass().getSimpleName() + "；未改动检测状态。");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void register(NekoRegisterPlayerEvent event) {
        observe(() -> selected(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void position(NekoPositionUpdateEvent event) {
        observe(() -> {
            MovementTrace current = selected(event.getPlayer());
            if (current == null) return;
            current.append("position from=" + event.getFromX() + "," + event.getFromY() + "," + event.getFromZ()
                + " to=" + event.getToX() + "," + event.getToY() + "," + event.getToZ()
                + " packetGround=" + event.isOnGround() + " teleport=" + event.isTeleport()
                + " cancelled=" + event.isCancelled() + state(event.getPlayer()), false);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void prediction(NekoPredictionEvent event) {
        observe(() -> {
            MovementTrace current = selected(event.getPlayer());
            if (current == null || !movement(event.getCheck())) return;
            inspectCheck(event.getCheck());
            // NaN 或非零偏差均保留；不把某个猜测阈值写回 Neko。
            current.append("prediction offset=" + event.getOffset() + " pointThree=" + event.isPointThree()
                + " cancelled=" + event.isCancelled() + state(event.getPlayer()), false);
            if (event.getOffset() != 0.0) anomaly(current, "prediction checkClass=" + event.getCheck().getClass().getName()
                + " offset=" + event.getOffset() + " vl=" + event.getViolations());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void flag(NekoFlagEvent event) {
        observe(() -> {
            MovementTrace current = selected(event.getPlayer());
            if (current == null || !movement(event.getCheck())) return;
            // 预测与实际 flag 使用各自的 HandlerList，不能从非零 offset 推定已经触发违规。
            anomaly(current, "flag check=" + event.getCheck().getCheckName()
                + " checkClass=" + event.getCheck().getClass().getName()
                + " vl=" + event.getViolations() + " cancelled=" + event.isCancelled()
                + state(event.getPlayer()));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void setback(NekoSetbackEvent event) {
        observe(() -> {
            MovementTrace current = selected(event.getPlayer());
            if (current != null) anomaly(current, "setback check=" + event.getCheckName() + " reason=" + event.getReason()
                + " type=" + event.getType() + " source=" + event.getSource()
                + " cancelled=" + event.isCancelled() + state(event.getPlayer()));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void resync(NekoResyncBlockEvent event) {
        observe(() -> {
            MovementTrace current = selected(event.getPlayer());
            if (current != null) anomaly(current, "resync min=" + event.getMinX() + "," + event.getMinY() + "," + event.getMinZ()
                + " max=" + event.getMaxX() + "," + event.getMaxY() + "," + event.getMaxZ()
                + " seq=" + event.getSequence() + " reason=" + event.getReason() + state(event.getPlayer()));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void ghost(NekoGhostBlockEvent event) {
        observe(() -> {
            MovementTrace current = selected(event.getPlayer());
            if (current == null) return;
            if (event.getCheck() != null) inspectCheck(event.getCheck());
            String detail = "ghost type=" + event.getType() + " check="
                + (event.getCheck() == null ? "null" : event.getCheck().getCheckName())
                + " position=" + event.getGhostPos() + state(event.getPlayer());
            if (movement(event.getCheck())) anomaly(current, detail);
            else current.append(detail, false);
        });
    }
}
