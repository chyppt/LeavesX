package org.leavesx.probe;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;
import org.leavesx.leavesx.diagnostics.ExperienceDiagnostics;

/** 仅用于隔离测试服：验证实际生成、两类合并、存量采样和报告，不代表客户端渲染验证。 */
public final class ExperienceVisibilityProbe extends JavaPlugin {
    private static final AABB AREA = new AABB(-16, 90, -16, 32, 110, 32);
    private ServerLevel level;

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                final var world = Bukkit.getWorlds().getFirst();
                world.setChunkForceLoaded(0, 0, true);
                this.level = ((CraftWorld) world).getHandle();
                ExperienceDiagnostics.start(this.level.dimension(), 0, 100, 0, 60);
                final var behavior = this.level.paperConfig().entities.behavior;
                final int previousLimit = behavior.experienceMergeMaxValue;
                final double previousRadius = this.level.spigotConfig.expMerge;
                try {
                    this.level.spigotConfig.expMerge = 3;
                    behavior.experienceMergeMaxValue = 10;
                    add(0, 7);
                    add(0, 8);
                    check(stage(ExperienceDiagnostics.Stage.SPAWN_MERGED).input() == 2, "限值合并记录实际转移 2 点");
                    behavior.experienceMergeMaxValue = -1;
                    add(8, 2);
                    add(8, 3);
                    check(stage(ExperienceDiagnostics.Stage.SPAWN_MERGED).input() == 4, "完整合并额外转移 2 点");
                    ExperienceOrb.award(this.level, new Vec3(8, 100, 8), 5);
                } finally {
                    behavior.experienceMergeMaxValue = previousLimit;
                    this.level.spigotConfig.expMerge = previousRadius;
                }
                // 固定测试球位置，排除物理运动离开采样范围；生产路径没有此操作。
                for (final ExperienceOrb orb : this.level.getEntities(EntityType.EXPERIENCE_ORB, AREA, entity -> true)) {
                    orb.setNoGravity(true);
                    orb.setDeltaMovement(Vec3.ZERO);
                }
                Bukkit.getScheduler().runTaskLater(this, this::verify, 30L);
            } catch (Throwable failure) {
                fail(failure);
            }
        }, 20L);
    }

    private void add(final double x, final int value) {
        final ExperienceOrb orb = new ExperienceOrb(this.level, new Vec3(x, 100, 8), Vec3.ZERO, value,
            org.bukkit.entity.ExperienceOrb.SpawnReason.CUSTOM, null, null);
        check(this.level.addFreshEntity(orb), "经验球加入实际世界");
    }

    private void verify() {
        try {
            final var report = ExperienceDiagnostics.report();
            final var snapshot = report.snapshot();
            check(snapshot != null && snapshot.orbs() > 0, "世界 Tick 自动发布存量快照");
            check(snapshot.experience() == 25, "两类合并及奖励后保留 25 点经验");
            check(snapshot.noTracker() == 0, "存活经验球已建立追踪器");
            check(stage(ExperienceDiagnostics.Stage.REMOVED).calls() > 0, "合并移除到达诊断入口");
            final long awarded = report.rows().stream()
                .filter(row -> row.stage() == ExperienceDiagnostics.Stage.ADDED || row.stage() == ExperienceDiagnostics.Stage.MERGED)
                .mapToLong(ExperienceDiagnostics.Row::output).sum();
            check(awarded == 5, "奖励入口记录新增或并入已有球的 5 点经验");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "leavesx xp report");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "leavesx xp stop");
            check(!ExperienceDiagnostics.report().running(), "stop 只停止采样");
            for (final ExperienceOrb orb : this.level.getEntities(EntityType.EXPERIENCE_ORB, AREA, entity -> true)) {
                orb.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.PLUGIN);
            }
            getLogger().info("EXPERIENCE_VISIBILITY_PROBE_PASS: 生成、限值合并、完整合并、25 点存量、追踪器、移除及报告");
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static ExperienceDiagnostics.Row stage(final ExperienceDiagnostics.Stage stage) {
        return ExperienceDiagnostics.report().rows().stream().filter(row -> row.stage() == stage).findFirst().orElseThrow();
    }

    private static void check(final boolean valid, final String message) {
        if (!valid) throw new AssertionError(message);
    }

    private void fail(final Throwable failure) {
        ExperienceDiagnostics.stop();
        getLogger().log(java.util.logging.Level.SEVERE, "EXPERIENCE_VISIBILITY_PROBE_FAIL", failure);
    }
}
