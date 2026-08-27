package org.leavesmc.leaves.command.bot.subcommands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.bot.BotList;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.bot.BotSubcommand;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static io.papermc.paper.adventure.PaperAdventure.asAdventure;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.spaces;
import static org.leavesmc.leaves.command.bot.BotCommandLocale.message;

public class LoadCommand extends BotSubcommand {

    public LoadCommand() {
        super("load");
        children(BotNameArgument::new);
    }

    @Override
    public boolean requires(@NotNull CommandSourceStack source) {
        return LeavesConfig.modify.fakeplayer.canManualSaveAndLoad && super.requires(source);
    }

    private static class BotNameArgument extends ArgumentNode<String> {

        private BotNameArgument() {
            super("bot_name", StringArgumentType.word());
        }

        @Override
        protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
            String botName = context.getArgument(BotNameArgument.class);
            BotList botList = BotList.INSTANCE;
            CommandSender sender = context.getSender();
            if (!botList.getManualSavedBotList().contains(botName.toLowerCase(Locale.ROOT))) {
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().create();
            }
            if (botList.getBotByName(botName) != null) {
                sender.sendMessage(text(message(
                    "Bot with name " + botName + " already exists!",
                    "名为 " + botName + " 的假人已存在！"
                ), NamedTextColor.RED));
                return false;
            }

            ServerBot bot = botList.loadNewManualSavedBot(botName);
            if (bot == null) {
                sender.sendMessage(text(message(
                    "Failed to load bot, please check log",
                    "假人加载失败，请检查服务器日志"
                ), NamedTextColor.RED));
                return false;
            }
            sender.sendMessage(join(spaces(), text(message(
                "Successfully loaded bot",
                "已成功加载假人"
            ), NamedTextColor.GRAY), asAdventure(bot.getDisplayName())));
            return true;
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(CommandContext context, @NotNull SuggestionsBuilder builder) {
            BotList botList = BotList.INSTANCE;
            CompoundTag list = botList.getManualSavedBotList();
            Set<String> bots = list.keySet();
            if (bots.isEmpty()) {
                return builder
                    .suggest(
                        message("<NO SAVED BOT EXISTS>", "<没有已保存的假人>"),
                        net.minecraft.network.chat.Component.literal(message(
                            "There are no bots saved before, save one first.",
                            "没有可加载的已保存假人，请先保存一个。"
                        ))
                    )
                    .buildFuture();
            }
            bots.forEach(key -> builder.suggest(list.getCompoundOrEmpty(key).getString("name").orElseThrow()));
            return builder.buildFuture();
        }
    }
}
