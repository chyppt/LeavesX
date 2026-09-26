package org.leavesx.leavesx.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.command.CommandSender;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

@Normal
class LeavesXOperatorAccessTest {
    @Test
    void permissionsCannotGrantNonOperatorsAccess() {
        final CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("leavesx.command.performance")).thenReturn(true);
        final LeavesXCommand command = new LeavesXCommand("leavesx");
        assertFalse(command.testPermissionSilent(sender));
        assertTrue(command.tabComplete(sender, "leavesx", new String[]{""}, null).isEmpty());
        when(sender.isOp()).thenReturn(true);
        assertTrue(command.testPermissionSilent(sender));
        assertTrue(command.tabComplete(sender, "leavesx", new String[]{"a"}, null).contains("ai"));
    }
}
