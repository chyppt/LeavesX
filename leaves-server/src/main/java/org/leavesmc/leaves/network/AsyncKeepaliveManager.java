package org.leavesmc.leaves.network;

import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.util.Util;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.LeavesLogger;
import org.leavesmc.leaves.bot.ServerBotPacketListenerImpl;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AsyncKeepaliveManager {

    private static final Logger LOGGER = LeavesLogger.LOGGER;
    private static final Set<ServerCommonPacketListenerImpl> ACTIVE_LISTENERS = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Leaves Async Keepalive");
        thread.setDaemon(true);
        return thread;
    });

    static {
        if (LeavesConfig.mics.asyncKeepalive.enable) {
            EXECUTOR.scheduleAtFixedRate(AsyncKeepaliveManager::tickAll, 1L, 1L, TimeUnit.SECONDS);
        }
    }

    private AsyncKeepaliveManager() {
    }

    public static void register(ServerCommonPacketListenerImpl listener) {
        if (!LeavesConfig.mics.asyncKeepalive.enable || !shouldManage(listener)) {
            return;
        }
        ACTIVE_LISTENERS.add(listener);
    }

    public static void unregister(ServerCommonPacketListenerImpl listener) {
        ACTIVE_LISTENERS.remove(listener);
    }

    /** Fake players have no remote client and therefore cannot acknowledge keepalive challenges. */
    static boolean shouldManage(ServerCommonPacketListenerImpl listener) {
        return !(listener instanceof ServerBotPacketListenerImpl);
    }

    private static void tickAll() {
        long currentTimeNs = System.nanoTime();
        long currentTimeMs = Util.getMillis();

        for (ServerCommonPacketListenerImpl listener : ACTIVE_LISTENERS) {
            try {
                listener.keepConnectionAliveAsync(currentTimeNs, currentTimeMs);
                if (!listener.connection.isConnected()) {
                    ACTIVE_LISTENERS.remove(listener);
                }
            } catch (Throwable throwable) {
                ACTIVE_LISTENERS.remove(listener);
                LOGGER.error("Failed to run async keepalive for connection {}", listener.connection.getRemoteAddress(), throwable);
            }
        }
    }
}
