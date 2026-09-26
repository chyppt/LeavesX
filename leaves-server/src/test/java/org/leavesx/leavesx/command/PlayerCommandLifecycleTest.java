package org.leavesx.leavesx.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.PaperCommands;
import java.lang.reflect.Field;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.command.bot.PlayerCommand;
import org.leavesx.leavesx.config.LeavesXRuntime;

@Normal
class PlayerCommandLifecycleTest {
    @Test
    void conflictCheckWorksAfterLeavesCommandInvalidatesPublicAccess() throws Exception {
        final Field dispatcher = PaperCommands.class.getDeclaredField("dispatcher");
        final Field invalid = PaperCommands.class.getDeclaredField("invalid");
        final Field initialized = LeavesConfig.class.getDeclaredField("initialized");
        final Field enabled = LeavesXRuntime.class.getDeclaredField("carpetPlayerCommand");
        for (final Field field : new Field[]{dispatcher, invalid, initialized, enabled}) field.setAccessible(true);
        final Object previousDispatcher = dispatcher.get(PaperCommands.INSTANCE);
        final boolean previousInvalid = invalid.getBoolean(PaperCommands.INSTANCE);
        final boolean previousInitialized = initialized.getBoolean(null);
        final boolean previousEnabled = enabled.getBoolean(null);
        final CommandDispatcher<CommandSourceStack> commands = new CommandDispatcher<>();
        final var existing = commands.register(LiteralArgumentBuilder.<CommandSourceStack>literal("player"));
        try {
            dispatcher.set(PaperCommands.INSTANCE, commands);
            PaperCommands.INSTANCE.invalidate();
            initialized.setBoolean(null, true);
            enabled.setBoolean(null, true);
            assertThrows(IllegalStateException.class, () -> PaperCommands.INSTANCE.getDispatcher());
            assertDoesNotThrow(() -> PlayerCommand.syncRegistration(true));
            assertSame(existing, commands.getRoot().getChild("player"));
            assertThrows(IllegalStateException.class, () -> PaperCommands.INSTANCE.getDispatcher());
        } finally {
            dispatcher.set(PaperCommands.INSTANCE, previousDispatcher);
            invalid.setBoolean(PaperCommands.INSTANCE, previousInvalid);
            initialized.setBoolean(null, previousInitialized);
            enabled.setBoolean(null, previousEnabled);
        }
    }
}
