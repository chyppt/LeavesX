package org.leavesx.leavesx.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.leavesx.leavesx.config.ConfigurationTestSupport.publish;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.LongJumpToRandomPos;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leavesx.leavesx.config.LeavesXConfig;

@Normal
class AsyncJumpCandidatesTest {
    @AfterEach void cleanup() {
        LeavesXAsyncRuntime.shutdown();
        publish(LeavesXConfig.safeDefaults());
    }

    private static void configure() {
        publish(LeavesXConfig.safeDefaults());
        LeavesXComputeExecutor.shutdown();
        LeavesXAsyncRuntime.configure(new LeavesXConfig.AsyncSettings(false, 64, false, true, 2, 64, 60, false, 64, false, 0, 64));
    }

    @Test void workersPreserveCandidateOrderAndResolveBeforeTheFirstAiDecision() throws Exception {
        configure();
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            LeavesXAsyncRuntime.trySubmitValue(LeavesXAsyncRuntime.Workload.AI_CANDIDATES, () -> {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("blocked worker timeout"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                return null;
            });
        }
        final var behavior = behavior();
        final var body = body(new BlockPos(-19, 68, 32));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            invoke("start", behavior, body);
            final var pending = (CompletableFuture<?>) field("leavesX$pendingCandidates").get(behavior);
            assertNotNull(pending);
            assertFalse(pending.isDone());
        } finally { release.countDown(); }
        assertEquals(true, invoke("canStillUse", behavior, body));
        assertEquals(expected(body.blockPosition()), field("jumpCandidates").get(behavior));
        assertNull(field("leavesX$pendingCandidates").get(behavior));
        verify(body, never()).getRandom();
        assertTrue(LeavesXAsyncRuntime.metrics(LeavesXAsyncRuntime.Workload.AI_CANDIDATES).submittedTasks() > 0);
    }

    @Test void failedPreparationRebuildsOneCompleteOriginalList() throws Exception {
        configure();
        final var behavior = behavior();
        final var body = body(new BlockPos(4, 71, -7));
        invoke("start", behavior, body);
        field("leavesX$pendingCandidates").set(behavior, CompletableFuture.failedFuture(new IllegalStateException("injected candidate failure")));
        assertEquals(true, invoke("canStillUse", behavior, body));
        assertEquals(expected(body.blockPosition()), field("jumpCandidates").get(behavior));
        assertNull(field("leavesX$pendingCandidates").get(behavior));
    }

    @Test void unknownSubclassesAndDisabledExecutorsRetainSynchronousStart() throws Exception {
        configure();
        final var custom = new LongJumpToRandomPos<Mob>(UniformInt.of(10, 20), 5, 5, 3.5F,
            mob -> SoundEvents.GOAT_LONG_JUMP, (mob, pos) -> true) {};
        final var body = body(BlockPos.ZERO);
        invoke("start", custom, body);
        assertNull(field("leavesX$pendingCandidates").get(custom));
        assertEquals(expected(BlockPos.ZERO), field("jumpCandidates").get(custom));
        LeavesXAsyncRuntime.shutdown();
        final var ordinary = behavior();
        invoke("start", ordinary, body);
        assertNull(field("leavesX$pendingCandidates").get(ordinary));
        assertEquals(expected(BlockPos.ZERO), field("jumpCandidates").get(ordinary));
    }

    private static LongJumpToRandomPos<Mob> behavior() {
        return new LongJumpToRandomPos<>(UniformInt.of(10, 20), 5, 5, 3.5F,
            mob -> SoundEvents.GOAT_LONG_JUMP, (mob, pos) -> true);
    }

    private static Mob body(final BlockPos position) {
        final Mob mob = mock(Mob.class);
        when(mob.blockPosition()).thenReturn(position);
        when(mob.position()).thenReturn(Vec3.atBottomCenterOf(position));
        return mob;
    }

    private static Object invoke(final String name, final LongJumpToRandomPos<?> behavior, final Mob mob) throws Exception {
        final Method method = LongJumpToRandomPos.class.getDeclaredMethod(name, ServerLevel.class, Mob.class, long.class);
        method.setAccessible(true);
        return method.invoke(behavior, mock(ServerLevel.class), mob, 100L);
    }

    private static Field field(final String name) throws Exception {
        final Field field = LongJumpToRandomPos.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static List<LongJumpToRandomPos.PossibleJump> expected(final BlockPos origin) {
        final List<LongJumpToRandomPos.PossibleJump> candidates = new ArrayList<>();
        for (final BlockPos position : BlockPos.betweenClosed(origin.offset(-5, -5, -5), origin.offset(5, 5, 5))) {
            if (!position.equals(origin)) candidates.add(new LongJumpToRandomPos.PossibleJump(position.immutable(), (int)Math.ceil(origin.distSqr(position))));
        }
        return candidates;
    }
}
