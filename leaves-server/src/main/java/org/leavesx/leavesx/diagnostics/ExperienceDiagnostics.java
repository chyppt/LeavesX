package org.leavesx.leavesx.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.event.entity.EntityDeathEvent;
import org.jspecify.annotations.Nullable;
import org.leavesx.leavesx.config.LeavesXRuntime;

/** 限时观察经验链路；关闭时不读取实体，不修改事件，不持有世界或实体引用。 */
public final class ExperienceDiagnostics {
    public static final int RADIUS = 64;
    private static final int MAX_SNAPSHOT_ORBS = 4096;
    private static volatile @Nullable Session current;

    private ExperienceDiagnostics() {}

    public enum Stage {
        DEATH("死亡事件"), NO_REWARD("事件前经验为零"), EVENT_REDUCED("事件后经验减少"), DEATH_CANCELLED("死亡被取消"),
        CONSUMED("掉落前经验已被消费"), DROP("进入经验掉落"),
        MERGED("并入已有经验球"), ADDED("新增经验球成功"), REJECTED("新增经验球失败或已移除"),
        SPAWN_CANCELLED("经验球生成事件取消"), SPAWN_MERGED("生成时吸收附近旧球"),
        TRACK_CANCELLED("玩家追踪事件取消"), PAIRING("生成包已交给玩家连接"),
        REMOVED("经验球被移除"), TOUCH("触发经验球接触"), COOLDOWN("接触时仍有拾取冷却"),
        PICKUP_CANCELLED("拾取事件取消"),
        PICKUP("拾取后传给玩家"), PICKUP_REDUCED("修补或经验事件扣减");

        private final String label;
        Stage(final String label) { this.label = label; }
        public String label() { return this.label; }
    }

    /** 输入、输出均为经验点；同一经验会经过多个阶段，不能把各行相加。 */
    public record Row(Stage stage, long calls, long input, long output, String sample) {}
    public record Report(boolean running, int seconds, ResourceKey<Level> world, double x, double y, double z,
                         List<Row> rows, @Nullable OrbSnapshot snapshot) {}
    /** 定时读取的存量，不是累计新增数；只保存数值和少量文本，不保留实体或连接。 */
    public record OrbSnapshot(long elapsedMillis, int orbs, long experience, boolean truncated,
                              int noTracker, int noHumanViewers, double mergeRadius,
                              List<String> samples, List<String> players) {}
    public record DeathTrace(Session session, int before, String detail) {}

    public static synchronized void start(final ResourceKey<Level> world, final double x, final double y,
                                          final double z, final int seconds) {
        start(world, x, y, z, seconds, System::nanoTime);
    }

    // 注入单调时钟供到期测试使用，避免测试依赖真实等待。
    static synchronized void start(final ResourceKey<Level> world, final double x, final double y, final double z,
                                   final int seconds, final LongSupplier clock) {
        if (seconds < 10 || seconds > 300) throw new IllegalArgumentException("采样时间必须为 10 到 300 秒。");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("坐标必须是有限数字。");
        }
        if (current != null && current.running()) throw new IllegalStateException("已有经验诊断，请先执行 /leavesx xp stop。");
        current = new Session(world, x, y, z, seconds, clock);
    }

    public static @Nullable Report report() {
        final Session session = current;
        return session == null ? null : session.report();
    }

    public static void stop() {
        final Session session = current;
        if (session != null) session.stop();
    }

    /** 只在当前世界、固定半径和采样窗口内返回会话，不访问任何区块。 */
    public static @Nullable Session capture(final Level level, final Vec3 position) {
        final Session session = current;
        return session != null && session.running() && session.contains(level, position.x, position.y, position.z)
            ? session : null;
    }

    private static @Nullable Session capture(final Entity entity) {
        final Session session = current;
        return session != null && session.running()
            && session.contains(entity.level(), entity.getX(), entity.getY(), entity.getZ()) ? session : null;
    }

    public static @Nullable DeathTrace beforeDeath(final LivingEntity victim, final DamageSource source,
                                                   final EntityDeathEvent event) {
        final Session session = capture(victim);
        if (session == null) return null;
        return new DeathTrace(session, event.getDroppedExp(), describe(victim) + " 伤害=" + source.getMsgId()
            + " 直接玩家伤害=" + (source.getEntity() instanceof Player)
            + " 玩家伤害记忆=" + victim.lastHurtByPlayerMemoryTime + " 已消费=" + victim.wasExperienceConsumed()
            + aiState(victim));
    }

    public static void afterDeath(final @Nullable DeathTrace trace, final EntityDeathEvent event) {
        if (trace == null) return;
        final Session session = trace.session();
        final int after = event.getDroppedExp();
        session.record(Stage.DEATH, trace.before(), after, trace::detail);
        if (trace.before() <= 0) session.record(Stage.NO_REWARD, trace.before(), after, trace::detail);
        if (event.isCancelled()) {
            session.record(Stage.DEATH_CANCELLED, after, 0, trace::detail);
        } else if (after < trace.before()) {
            session.record(Stage.EVENT_REDUCED, trace.before(), after, trace::detail);
        }
    }

    /** 在 ENTITY_DIE 游戏事件后观察，覆盖幽匿催发体消费经验的原有路径。 */
    public static void beforeDrop(final LivingEntity victim) {
        final Session session = capture(victim);
        if (session == null) return;
        final boolean consumed = victim.wasExperienceConsumed();
        session.record(consumed ? Stage.CONSUMED : Stage.DROP, victim.expToDrop, consumed ? 0 : victim.expToDrop,
            () -> describe(victim) + " 玩家伤害记忆=" + victim.lastHurtByPlayerMemoryTime);
    }

    public static void awarded(final @Nullable Session session, final @Nullable Entity source, final int value,
                               final @Nullable ExperienceOrb orb, final boolean added) {
        if (session == null) return;
        final boolean merged = orb == null;
        final boolean accepted = merged || (added && !orb.isRemoved());
        session.record(merged ? Stage.MERGED : accepted ? Stage.ADDED : Stage.REJECTED, value, accepted ? value : 0,
            () -> "来源=" + (source == null ? "无" : describe(source))
                + (orb == null ? "，使用已有球" : " 球=" + describe(orb) + " value=" + orb.getValue()
                    + " count=" + orb.count + " add=" + added + " removed=" + orb.isRemoved()));
    }

    /** 生成事件可能因上游兼容路径被调用多次，因此这里只统计取消调用，不当作实体个数。 */
    public static void spawnEvent(final Entity entity, final boolean cancelled) {
        if (!cancelled || !(entity instanceof ExperienceOrb orb)) return;
        final Session session = capture(orb);
        if (session != null) session.record(Stage.SPAWN_CANCELLED, (long) orb.getValue() * orb.count, 0,
            () -> describe(orb));
    }

    public static void pickupCancelled(final ExperienceOrb orb) {
        final Session session = capture(orb);
        if (session != null) session.record(Stage.PICKUP_CANCELLED, orb.getValue(), 0, () -> describe(orb));
    }

    /** 记录接触入口，区分未碰到玩家、拾取冷却与插件取消。 */
    public static void touched(final ExperienceOrb orb, final Player player) {
        final Session session = capture(orb);
        if (session == null) return;
        final long value = (long) orb.getValue() * orb.count;
        final Supplier<String> detail = () -> describe(orb) + " 玩家=" + player.getUUID()
            + " 拾取冷却=" + player.takeXpDelay;
        session.record(Stage.TOUCH, value, value, detail);
        if (player.takeXpDelay != 0) session.record(Stage.COOLDOWN, value, value, detail);
    }

    /** 这里只证明生成包已交给连接；不能证明插件未拦截或客户端已渲染。 */
    public static void pairingSubmitted(final Entity entity, final ServerPlayer player, final List<? extends Packet<?>> packets) {
        if (!(entity instanceof ExperienceOrb orb)) return;
        final Session session = capture(orb);
        if (session == null) return;
        if (packets.stream().noneMatch(packet -> packet instanceof ClientboundAddEntityPacket)) return;
        final long value = (long) orb.getValue() * orb.count;
        session.record(Stage.PAIRING, value, value, () -> describe(orb) + " 玩家=" + player.getUUID()
            + " 假人=" + (player instanceof org.leavesmc.leaves.bot.ServerBot));
    }

    public static void trackingCancelled(final Entity entity, final ServerPlayer player) {
        if (!(entity instanceof ExperienceOrb orb)) return;
        final Session session = capture(orb);
        if (session != null) session.record(Stage.TRACK_CANCELLED, 0, 0,
            () -> describe(orb) + " 玩家=" + player.getUUID());
    }

    public static void removed(final ExperienceOrb orb, final Entity.RemovalReason reason,
                               final org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause cause) {
        final Session session = capture(orb);
        if (session == null) return;
        session.record(Stage.REMOVED, (long) orb.getValue() * orb.count, 0,
            () -> describe(orb) + " 原因=" + reason + " Bukkit原因=" + cause + " 已存活Tick=" + orb.tickCount);
    }

    /** Paper/Spigot 在生成时另有一层合并，不受 LeavesX 两个经验查询开关控制。 */
    public static void mergedDuringSpawn(final ExperienceOrb target, final ExperienceOrb source, final int transferredValue) {
        final Session session = capture(target);
        if (session == null) return;
        final long value = (long) transferredValue * source.count;
        session.record(Stage.SPAWN_MERGED, value, value, () -> "旧球=" + describe(source)
            + " 已存活Tick=" + source.tickCount + " 新球=" + describe(target));
    }

    /** 仅在世界自己的 Tick 尾部采集，每秒最多一次；不在命令线程遍历 PWT 世界。 */
    public static void sampleWorld(final ServerLevel level) {
        final Session session = current;
        if (session == null || !session.running() || !session.world.equals(level.dimension())) return;
        final long elapsed = session.clock.getAsLong() - session.started;
        if (elapsed < session.nextSnapshotNanos) return;
        session.nextSnapshotNanos = elapsed + TimeUnit.SECONDS.toNanos(1);
        final List<ExperienceOrb> orbs = new ArrayList<>();
        level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(ExperienceOrb.class),
            new AABB(session.x - RADIUS, session.y - RADIUS, session.z - RADIUS,
                session.x + RADIUS, session.y + RADIUS, session.z + RADIUS),
            orb -> !orb.isRemoved() && session.contains(level, orb.getX(), orb.getY(), orb.getZ()),
            orbs, MAX_SNAPSHOT_ORBS + 1);
        final int size = Math.min(orbs.size(), MAX_SNAPSHOT_ORBS);
        long experience = 0;
        int noTracker = 0, noHumanViewers = 0;
        final List<String> samples = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            final ExperienceOrb orb = orbs.get(i);
            experience += (long) orb.getValue() * orb.count;
            final var tracker = orb.moonrise$getTrackedEntity();
            int viewers = 0;
            if (tracker == null) noTracker++;
            else for (final var connection : tracker.seenBy) {
                if (!(connection.getPlayer() instanceof org.leavesmc.leaves.bot.ServerBot)) viewers++;
            }
            if (viewers == 0) noHumanViewers++;
            if (samples.size() < 5) samples.add(describe(orb) + " value=" + orb.getValue() + " count=" + orb.count
                + " 已存活Tick=" + orb.tickCount + " 隐身=" + orb.isInvisible()
                + " 追踪器=" + (tracker != null) + " 已登记真人观察者=" + viewers + " 速度=" + orb.getDeltaMovement());
        }
        final List<String> players = new ArrayList<>();
        for (final ServerPlayer player : level.players()) {
            if (players.size() == 5) break;
            if (player instanceof org.leavesmc.leaves.bot.ServerBot
                || !session.contains(level, player.getX(), player.getY(), player.getZ())) continue;
            players.add(describe(player) + " 拾取冷却=" + player.takeXpDelay + " 旁观=" + player.isSpectator());
        }
        session.snapshot = new OrbSnapshot(TimeUnit.NANOSECONDS.toMillis(elapsed), size, experience,
            orbs.size() > MAX_SNAPSHOT_ORBS, noTracker, noHumanViewers, level.spigotConfig.expMerge,
            List.copyOf(samples), List.copyOf(players));
    }

    public static void pickedUp(final ExperienceOrb orb, final Player player, final int beforeMending,
                                final int afterMending, final int granted) {
        final Session session = capture(orb);
        if (session == null) return;
        final Supplier<String> detail = () -> describe(orb) + " 玩家=" + player.getUUID()
            + " 修补前=" + beforeMending + " 修补后=" + afterMending + " 事件后=" + granted;
        session.record(Stage.PICKUP, beforeMending, granted, detail);
        if (granted < beforeMending) session.record(Stage.PICKUP_REDUCED, beforeMending, granted, detail);
    }

    private static String aiState(final LivingEntity victim) {
        if (!(victim instanceof Mob mob)) return "";
        // 只读现有状态，不重新执行密度查询、寻路或仇恨计算。
        return " AI启用=" + !mob.isNoAi() + " aware=" + mob.aware + " 有目标=" + (mob.getTarget() != null)
            + " 密集降频开关=" + LeavesXRuntime.denseMonsterAiThrottling()
            + (mob instanceof ZombifiedPiglin piglin ? " 猪灵愤怒=" + piglin.isAngry() : "");
    }

    private static String describe(final Entity entity) {
        return entity.getType() + " UUID=" + entity.getUUID() + " 坐标["
            + (int) Math.floor(entity.getX()) + "," + (int) Math.floor(entity.getY()) + ","
            + (int) Math.floor(entity.getZ()) + "]";
    }

    /** 只锁计数更新，绝不持锁调用插件；PWT 的不同世界及重入事件不能串用会话。 */
    public static final class Session {
        private final ResourceKey<Level> world;
        private final double x, y, z;
        private final int seconds;
        private final LongSupplier clock;
        private final long started;
        private final long duration;
        private volatile boolean stopped;
        private long nextSnapshotNanos;
        private volatile @Nullable OrbSnapshot snapshot;
        private final long[] calls = new long[Stage.values().length];
        private final long[] inputs = new long[Stage.values().length];
        private final long[] outputs = new long[Stage.values().length];
        private final String[] samples = new String[Stage.values().length];

        private Session(final ResourceKey<Level> world, final double x, final double y, final double z,
                        final int seconds, final LongSupplier clock) {
            this.world = java.util.Objects.requireNonNull(world);
            this.x = x; this.y = y; this.z = z;
            this.seconds = seconds;
            this.clock = clock;
            this.started = clock.getAsLong();
            this.duration = TimeUnit.SECONDS.toNanos(seconds);
        }

        private boolean running() {
            return !this.stopped && this.clock.getAsLong() - this.started < this.duration;
        }

        private boolean contains(final Level level, final double ex, final double ey, final double ez) {
            final double dx = ex - this.x, dy = ey - this.y, dz = ez - this.z;
            return this.world.equals(level.dimension()) && dx * dx + dy * dy + dz * dz <= RADIUS * RADIUS;
        }

        private synchronized void stop() { this.stopped = true; }

        private synchronized void record(final Stage stage, final long input, final long output,
                                         final Supplier<String> detail) {
            if (!this.running()) return;
            final int index = stage.ordinal();
            this.calls[index]++;
            this.inputs[index] += input;
            this.outputs[index] += output;
            // 每个阶段只保留第一条明细。高密度塔持续采样不会无限增长或逐只写日志。
            if (this.samples[index] == null) this.samples[index] = detail.get();
        }

        private synchronized Report report() {
            final List<Row> rows = new ArrayList<>();
            for (final Stage stage : Stage.values()) {
                final int i = stage.ordinal();
                if (this.calls[i] != 0) rows.add(new Row(stage, this.calls[i], this.inputs[i], this.outputs[i], this.samples[i]));
            }
            return new Report(this.running(), this.seconds, this.world, this.x, this.y, this.z, List.copyOf(rows), this.snapshot);
        }
    }
}
