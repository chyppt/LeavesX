package org.leavesx.leavesx.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 下载属于阻塞 I/O；使用四个虚拟工作线程，避免改变 Paper 的并发下载数量。 */
public final class LeavesXDownloadExecutor {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(LeavesXDownloadExecutor.class);
    private static volatile boolean enabled;

    private LeavesXDownloadExecutor() {}

    /** 只在启动配置阶段设置，重载不能替换正在使用的下载池。 */
    public static void configure(final boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static ExecutorService createVirtualPool() {
        return Executors.newFixedThreadPool(4, Thread.ofVirtual().name("LeavesX Download - ", 0)
            .uncaughtExceptionHandler((thread, failure) ->
                LOGGER.error("LeavesX 下载任务异常：{}", thread.getName(), failure))
            .factory());
    }
}
