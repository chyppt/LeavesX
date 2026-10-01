package org.leavesx.leavesx.network;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import org.bukkit.Bukkit;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.protocol.rei.REIServerProtocol;
import org.leavesmc.leaves.protocol.rei.payload.DisplaySyncPayload;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Normal
class ReiReloadSafetyTest {
    @Test
    void recipeReloadDiscardsPreviouslyEncodedDisplays() throws Exception {
        verifyPublication(true);
    }

    @Test
    void disablingProtocolDiscardsPreviouslyEncodedDisplays() throws Exception {
        verifyPublication(false);
    }

    private static void verifyPublication(boolean reload) throws Exception {
        final Field version = field("minecraftRecipeVer");
        final Field cache = field("cachedPayloads");
        final int previousVersion = version.getInt(null);
        final Object previousCache = cache.get(null);
        final boolean previouslyEnabled = LeavesConfig.protocol.reiServerProtocol;
        final var scheduler = mock(GlobalRegionScheduler.class);
        final AtomicReference<Consumer<ScheduledTask>> queued = new AtomicReference<>();
        doAnswer(call -> {
            queued.set(call.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(scheduler).run(any(), any());
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            LeavesConfig.protocol.reiServerProtocol = true;
            REIServerProtocol.onRecipeReload();
            encode(version.getInt(null));
            assertNotNull(queued.get());
            assertNull(cache.get(null), "Encoder must not publish from its worker");
            if (reload) REIServerProtocol.onRecipeReload();
            else LeavesConfig.protocol.reiServerProtocol = false;
            queued.getAndSet(null).accept(null);
            assertNull(cache.get(null), "Obsolete/disabled completion must not publish");
            LeavesConfig.protocol.reiServerProtocol = true;
            encode(version.getInt(null));
            queued.getAndSet(null).accept(null);
            assertNotNull(cache.get(null), "Current generation must still publish");
        } finally {
            LeavesConfig.protocol.reiServerProtocol = previouslyEnabled;
            version.setInt(null, previousVersion);
            cache.set(null, previousCache);
        }
    }

    private static void encode(int version) throws Exception {
        // Run the real encoder; capture only the server-thread publication task. No test-only production hook.
        final Method encode = REIServerProtocol.class.getDeclaredMethod("encodeAndPublish",
            DisplaySyncPayload.class, RegistryAccess.class, int.class);
        encode.setAccessible(true);
        encode.invoke(null, new DisplaySyncPayload(DisplaySyncPayload.SyncType.SET, List.of(), version),
            RegistryHelper.registryAccess(), version);
    }

    private static Field field(String name) throws Exception {
        final Field field = REIServerProtocol.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
