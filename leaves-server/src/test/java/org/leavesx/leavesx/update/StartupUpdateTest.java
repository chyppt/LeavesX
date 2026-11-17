package org.leavesx.leavesx.update;

import org.junit.jupiter.api.Test;
import org.bukkit.support.environment.Normal;
import org.leavesx.leavesx.config.LeavesXConfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Normal
class StartupUpdateTest {
    @Test
    void reloadDoesNotRepeatStartupCheck() throws Exception {
        verifyStartupCheck(true);
    }

    @Test
    void enablingCheckOnReloadWaitsForNextStartup() throws Exception {
        verifyStartupCheck(false);
    }

    private static void verifyStartupCheck(boolean enabledAtStartup) throws Exception {
        final var configured = LeavesXUpdateService.class.getDeclaredField("configured");
        configured.setAccessible(true);
        final boolean previous = configured.getBoolean(null);
        // 仅运行配置入口，拦截实际网络请求，避免测试访问 GitHub。
        try (var service = mockStatic(LeavesXUpdateService.class)) {
            service.when(() -> LeavesXUpdateService.configure(any())).thenCallRealMethod();
            configured.setBoolean(null, false);
            final var startup = mock(LeavesXConfig.ExtensionSettings.class);
            when(startup.updateCheck()).thenReturn(enabledAtStartup);
            LeavesXUpdateService.configure(startup);
            LeavesXUpdateService.configure(LeavesXConfig.ExtensionSettings.defaults());
            LeavesXUpdateService.configure(LeavesXConfig.ExtensionSettings.defaults());
            service.verify(() -> LeavesXUpdateService.request(false), times(enabledAtStartup ? 1 : 0));
        } finally {
            configured.setBoolean(null, previous);
        }
    }
}
