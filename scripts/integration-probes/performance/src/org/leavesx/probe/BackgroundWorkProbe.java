package org.leavesx.probe;

import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.LongJumpToPreferredBlock;
import net.minecraft.world.entity.ai.behavior.LongJumpToRandomPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;
import org.leavesx.leavesx.config.LeavesXRuntime;
import org.leavesx.leavesx.performance.ParallelMobCollision;
import org.leavesx.leavesx.runtime.LeavesXAsyncRuntime;

/** Uses the real server scheduler, AI classes and player storage; never installs in a production server. */
final class BackgroundWorkProbe {
    static void verify(final JavaPlugin plugin, final World world) throws Exception {
        final ServerLevel level = ((CraftWorld) world).getHandle();
        final Mob goat = EntityType.GOAT.create(level, EntitySpawnReason.COMMAND);
        final Mob frog = EntityType.FROG.create(level, EntitySpawnReason.COMMAND);
        if (goat == null || frog == null) throw new AssertionError("probe entities");
        goat.setPos(40, 80, 40);
        frog.setPos(41, 80, 41);
        final long before = LeavesXAsyncRuntime.metrics(LeavesXAsyncRuntime.Workload.AI_CANDIDATES).submittedTasks();
        checkJump(level, goat, new LongJumpToRandomPos<Mob>(UniformInt.of(10, 20), 5, 5, 3.5F,
            mob -> SoundEvents.GOAT_LONG_JUMP, (mob, pos) -> true), 5, 5);
        checkJump(level, frog, new LongJumpToPreferredBlock<Mob>(UniformInt.of(10, 20), 2, 4, 3.5F,
            mob -> SoundEvents.FROG_LONG_JUMP, BlockTags.FROG_PREFER_JUMP_TO, 0.5F, (mob, pos) -> true), 2, 4);
        final long submitted = LeavesXAsyncRuntime.metrics(LeavesXAsyncRuntime.Workload.AI_CANDIDATES).submittedTasks() - before;
        if (LeavesXRuntime.configuration().extensions().asyncAiCandidates() && submitted != 2) {
            throw new AssertionError("two actual background AI tasks, got " + submitted);
        }

        final AABB box = new AABB(0, 0, 0, 1, 2, 1);
        final Vec3 motion = new Vec3(3, -0.0, 1);
        final List<AABB> obstacles = java.util.Collections.nCopies(8_192, new AABB(2, 0, 0, 3, 2, 1));
        if (!CollisionUtil.performAABBCollisions(motion, box, obstacles)
            .equals(ParallelMobCollision.collide(goat, motion, box, List.of(), obstacles))) {
            throw new AssertionError("live collision calculation differs");
        }

        final UUID id = UUID.fromString("e58a04f8-7f33-4b4a-bf07-1e2d17fb15de");
        final var storage = level.getServer().getPlayerList().playerIo;
        final CompoundTag data = new CompoundTag();
        data.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        data.putInt("leavesx-probe", 1);
        storage.save("BackgroundProbe", id, id.toString(), data);
        data.putInt("leavesx-probe", 2);
        storage.save("BackgroundProbe", id, id.toString(), data);
        data.putInt("leavesx-probe", 3);
        // load() must await this UUID's compression and write lane.
        final CompoundTag loaded = storage.load(new NameAndId(id, "BackgroundProbe")).orElseThrow();
        if (loaded.getIntOr("leavesx-probe", 0) != 2) throw new AssertionError("ordered detached player save");
        plugin.getLogger().info("BACKGROUND_WORK_PROBE_PASS: AI background tasks=" + submitted
            + ", collision comparison, detached compressed saves and read-after-write");
    }

    private static void checkJump(final ServerLevel level, final Mob body, final LongJumpToRandomPos<Mob> behavior,
                                  final int height, final int width) throws Exception {
        final Method start = behavior.getClass().getDeclaredMethod("start", ServerLevel.class, Mob.class, long.class);
        start.setAccessible(true);
        start.invoke(behavior, level, body, 100L);
        final Method canContinue = LongJumpToRandomPos.class.getDeclaredMethod("canStillUse", ServerLevel.class, Mob.class, long.class);
        canContinue.setAccessible(true);
        if (!Boolean.TRUE.equals(canContinue.invoke(behavior, level, body, 101L))) throw new AssertionError("AI timing changed");
        final var candidates = LongJumpToRandomPos.class.getDeclaredField("jumpCandidates");
        candidates.setAccessible(true);
        final BlockPos origin = body.blockPosition();
        final var expected = new ArrayList<LongJumpToRandomPos.PossibleJump>();
        for (final BlockPos pos : BlockPos.betweenClosed(origin.offset(-width, -height, -width), origin.offset(width, height, width))) {
            if (!pos.equals(origin)) expected.add(new LongJumpToRandomPos.PossibleJump(pos.immutable(), (int)Math.ceil(origin.distSqr(pos))));
        }
        if (!expected.equals(candidates.get(behavior))) throw new AssertionError("candidate order or weights changed");
    }
}
