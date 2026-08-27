package org.leavesmc.leaves.command.arguments;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.bot.BotList;
import org.leavesmc.leaves.bot.ServerBot;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

import static org.leavesmc.leaves.command.bot.BotCommandLocale.message;

public class BotArgumentType implements CustomArgumentType.Converted<@NotNull ServerBot, @NotNull String> {

    private BotArgumentType() {
    }

    public static @NotNull BotArgumentType bot() {
        return new BotArgumentType();
    }

    @Override
    public <S> @NotNull CompletableFuture<Suggestions> listSuggestions(com.mojang.brigadier.context.@NotNull CommandContext<S> context, @NotNull SuggestionsBuilder builder) {
        Collection<ServerBot> bots = BotList.INSTANCE.bots;
        if (bots.isEmpty()) {
            return builder
                .suggest(
                    message("<NO BOT EXISTS>", "<服务器中没有假人>"),
                    net.minecraft.network.chat.Component.literal(
                        message("There are no bots in the server, create one first.", "服务器中没有假人，请先创建一个。")
                    )
                )
                .buildFuture();
        }
        bots.stream().map(ServerBot::getScoreboardName).forEach(builder::suggest);
        return builder.buildFuture();
    }

    @Override
    public ServerBot convert(String nativeType) throws CommandSyntaxException {
        ServerBot bot = BotList.INSTANCE.getBotByName(nativeType);
        if (bot == null) {
            throw new CommandSyntaxException(
                CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument(),
                Component.literal(message(
                    "Bot with name '" + nativeType + "' does not exist",
                    "名为 '" + nativeType + "' 的假人不存在"
                ))
            );
        }
        return bot;
    }

    @Override
    public @NotNull ArgumentType<@NotNull String> getNativeType() {
        return StringArgumentType.word();
    }
}
