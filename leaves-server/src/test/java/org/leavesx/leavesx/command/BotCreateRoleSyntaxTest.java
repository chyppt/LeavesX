package org.leavesx.leavesx.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.bot.BotUtil;
import org.leavesmc.leaves.command.CommandNode;
import org.leavesmc.leaves.command.bot.subcommands.CreateCommand;

@Normal
class BotCreateRoleSyntaxTest {
    private CommandDispatcher<CommandSourceStack> dispatcher() throws Exception {
        final var dispatcher = new CommandDispatcher<CommandSourceStack>();
        final var compile = CommandNode.class.getDeclaredMethod("compile");
        compile.setAccessible(true);
        @SuppressWarnings("unchecked") final var create =
            (com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?>) compile.invoke(new CreateCommand());
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("bot")
            .then(create));
        return dispatcher;
    }

    @Test void playerEntersChineseRoleDirectlyWithoutQuotesOrLiteral() throws Exception {
        final var dispatcher = this.dispatcher();
        final var source = mock(CommandSourceStack.class);
        final var player = mock(Player.class);
        when(source.getSender()).thenReturn(player);
        when(player.hasPermission(anyString())).thenReturn(true);
        for (String role : new String[]{"测试", "&a刷铁", "roleplay", "英文 role"}) {
            final String input = "bot create 111 " + role;
            final var parsed = dispatcher.parse(input, source);
            assertFalse(parsed.getReader().canRead(), parsed.getExceptions().toString());
            final var context = parsed.getContext().build(input);
            assertNotNull(context.getCommand());
            assertEquals("111", context.getArgument("name", String.class));
            assertEquals(role, context.getArgument("作用", String.class));
            assertThrows(IllegalArgumentException.class, () -> context.getArgument("skin_name", String.class));
        }
    }

    @Test void explicitSkinAndOldRoleAliasRemainUnambiguous() throws Exception {
        final var dispatcher = this.dispatcher();
        final var source = mock(CommandSourceStack.class);
        final var player = mock(Player.class);
        when(source.getSender()).thenReturn(player);
        when(player.hasPermission(anyString())).thenReturn(true);
        final String input = "bot create 111 skin Notch 测试";
        final var parsed = dispatcher.parse(input, source);
        assertFalse(parsed.getReader().canRead());
        final var context = parsed.getContext().build(input);
        assertEquals("Notch", context.getArgument("skin_name", String.class));
        assertEquals("测试", context.getArgument("作用", String.class));
        final var alias = dispatcher.parse("bot create 111 role 测试", source);
        assertFalse(alias.getReader().canRead());
        assertEquals("测试", alias.getContext().build("bot create 111 role 测试").getArgument("作用", String.class));
    }

    @Test void consoleCanParseDirectRoleInsteadOfTreatingItAsASkin() throws Exception {
        final var source = mock(CommandSourceStack.class);
        final var console = mock(ConsoleCommandSender.class);
        when(source.getSender()).thenReturn(console);
        when(console.hasPermission(anyString())).thenReturn(true);
        final String input = "bot create 111 测试";
        final var parsed = this.dispatcher().parse(input, source);
        assertFalse(parsed.getReader().canRead());
        assertEquals("测试", parsed.getContext().build(input).getArgument("作用", String.class));
    }

    @Test void sharedNameValidationAcceptsThreeCharacterAccountsOnlyWithinValidBounds() {
        assertTrue(BotUtil.isValidAccountName("111"));
        assertTrue(BotUtil.isValidAccountName("abcdefghijklmnop"));
        for (String name : new String[]{"", "11", "abcdefghijklmnopq", "测试1", "a b", "a&b"}) {
            assertFalse(BotUtil.isValidAccountName(name), name);
        }
    }
}
