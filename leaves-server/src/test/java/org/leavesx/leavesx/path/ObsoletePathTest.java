package org.leavesx.leavesx.path;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Path;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

@Normal
class ObsoletePathTest {
    @Test
    void obsoleteRequestDoesNotWaitOrCancelProducer() throws Exception {
        final var producer = new CompletableFuture<Path>();
        final var finished = new CompletableFuture<Boolean>();
        final Thread owner = Thread.ofPlatform().daemon().start(() -> {
            try {
                final var path = new LeavesXAsyncPath(Set.of(BlockPos.ZERO), producer,
                    (result, error) -> { throw new AssertionError("obsolete adoption"); }, () -> false);
                assertNull(path.resolvedPath());
                assertTrue(path.isProcessed());
                finished.complete(true);
            } catch (Throwable failure) { finished.completeExceptionally(failure); }
        });
        try {
            assertTrue(finished.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(producer.isDone());
        } finally {
            producer.complete(null);
            owner.join(5000);
        }
    }
}
