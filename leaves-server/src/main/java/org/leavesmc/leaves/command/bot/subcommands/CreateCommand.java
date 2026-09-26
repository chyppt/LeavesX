package org.leavesmc.leaves.command.bot.subcommands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.FinePositionResolver;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.bot.BotCreateState;
import org.leavesmc.leaves.bot.BotList;
import org.leavesmc.leaves.bot.BotUtil;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.LiteralNode;
import org.leavesmc.leaves.command.bot.BotSubcommand;
import org.leavesmc.leaves.event.bot.BotCreateEvent;

import static net.kyori.adventure.text.Component.text;
import static org.leavesmc.leaves.command.bot.BotCommandLocale.message;

public class CreateCommand extends BotSubcommand {

    public CreateCommand() {
        super("create");
        children(NameArgument::new);
    }

    protected static boolean handleCreateCommand(@NotNull CommandContext context) throws CommandSyntaxException {
        CommandSender sender = context.getSender();

        String rawName = context.getArgument(NameArgument.class);
        String fullName = BotUtil.getFullName(rawName);
        if (!canCreate(sender, fullName)) { // Check full name
            return false;
        }
        String skinName = context.getArgumentOrDefault(SkinNameArgument.class,
            context.getArgumentOrDefault(LegacySkinNameArgument.class, rawName));
        String role = context.getArgumentOrDefault(RoleValueArgument.class, "");
        if (!BotUtil.isRoleLegal(role)) {
            sender.sendMessage(text(message(
                "The fakeplayer role is invalid for the current LeavesX configuration.",
                "假人作用不符合当前 LeavesX 配置。"
            ), NamedTextColor.RED));
            return false;
        }

        World world;
        try {
            world = context.getArgument(WorldArgument.class);
        } catch (IllegalArgumentException e) {
            if (!(sender instanceof Entity entity)) {
                sender.sendMessage(text(message(
                    "Must specify world and location when executed by console",
                    "控制台执行时必须指定世界和坐标"
                ), NamedTextColor.RED));
                return false;
            }
            world = entity.getWorld();
        }

        Location location = Bukkit.getWorlds().getFirst().getSpawnLocation();
        FinePositionResolver positionResolver = context.getArgumentOrDefault(LocationArgument.class, null);
        if (positionResolver != null) {
            Vector vec3 = positionResolver.resolve(context.getSource()).toVector();
            location = new Location(world, vec3.getX(), vec3.getY(), vec3.getZ());
        } else if (sender instanceof Entity entity) {
            location = entity.getLocation();
        }

        BotCreateState.Builder builder = BotCreateState
            .builder(rawName, location)
            .createReason(BotCreateEvent.CreateReason.COMMAND)
            .skinName(skinName)
            .creator(sender)
            .role(role);
        builder.spawnWithSkin(bot -> sender.sendMessage(
            org.leavesx.leavesx.presentation.LeavesXPlayerPresentation.withCorePrefix(text(message(
                "Successfully created bot ",
                "已成功创建假人 "
            )).append(bot.displayName()))
        ));

        return true;
    }

    private static boolean canCreate(CommandSender sender, @NotNull String name) {
        BotList botList = BotList.INSTANCE;
        if (!BotUtil.isValidAccountName(name)) {
            sender.sendMessage(text(message(
                "This name is illegal, bot name must be 3-16 characters and contain only letters, numbers, and underscores.",
                "假人名称不合法：必须为 3-16 个字符，且只能包含字母、数字和下划线。"
            ), NamedTextColor.RED));
            return false;
        }

        if (Bukkit.getPlayerExact(name) != null || botList.getBotByName(name) != null) {
            sender.sendMessage(text(message("This bot is already in server", "该假人已在服务器中"), NamedTextColor.RED));
            return false;
        }

        if (BotUtil.isNameForbidden(name)) {
            sender.sendMessage(text(message(
                "This name is not allowed in this server",
                "服务器禁止使用该假人名称"
            ), NamedTextColor.RED));
            return false;
        }

        if (botList.bots.size() >= LeavesConfig.modify.fakeplayer.limit) {
            sender.sendMessage(text(message("Bot number limit exceeded", "假人数量已达到上限"), NamedTextColor.RED));
            return false;
        }

        return true;
    }

    private static class NameArgument extends ArgumentNode<String> {
        private NameArgument() {
            super("name", StringArgumentType.word());
            // The direct form is /bot create <name> <role>. Keep only the old console coordinate branch ambiguous;
            // it must include a world and position before it can execute.
            children(LegacySkinNameArgument::new, RoleValueArgument::new, RoleLiteral::new, SkinLiteral::new);
        }

        @Override
        protected boolean execute(CommandContext context) throws CommandSyntaxException {
            return handleCreateCommand(context);
        }
    }

    /** Compatibility alias; the role literal is no longer needed for the normal command. */
    private static class RoleLiteral extends LiteralNode {
        private RoleLiteral() {
            super("role");
            children(RoleValueArgument::new);
        }
    }

    private static class RoleValueArgument extends ArgumentNode<String> {
        private RoleValueArgument() {
            // Brigadier word() accepts only ASCII. The final role consumes the remaining text, including Chinese
            // and legacy color characters; BotUtil applies the configured length/Chinese restrictions afterward.
            super("作用", StringArgumentType.greedyString());
        }

        @Override
        protected boolean execute(CommandContext context) throws CommandSyntaxException {
            return handleCreateCommand(context);
        }
    }

    private static class SkinLiteral extends LiteralNode {
        private SkinLiteral() {
            super("skin");
            children(SkinNameArgument::new);
        }
    }

    private static class LegacySkinNameArgument extends ArgumentNode<String> {
        private LegacySkinNameArgument() {
            super("legacy_skin_name", StringArgumentType.word());
            children(WorldArgument::new);
        }

        @Override
        public boolean requires(@NotNull CommandSourceStack source) {
            return source.getSender() instanceof ConsoleCommandSender;
        }
    }

    private static class SkinNameArgument extends ArgumentNode<String> {
        private SkinNameArgument() {
            super("skin_name", StringArgumentType.word());
            children(WorldArgument::new, RoleValueArgument::new);
        }

        @Override
        protected boolean execute(CommandContext context) throws CommandSyntaxException {
            return handleCreateCommand(context);
        }
    }

    private static class WorldArgument extends ArgumentNode<World> {
        private WorldArgument() {
            super("world", ArgumentTypes.world());
            children(LocationArgument::new);
        }

        @Override
        public boolean requires(@NotNull CommandSourceStack source) {
            return source.getSender() instanceof ConsoleCommandSender;
        }
    }

    private static class LocationArgument extends ArgumentNode<FinePositionResolver> {
        private LocationArgument() {
            super("location", ArgumentTypes.finePosition());
            children(RoleValueArgument::new);
        }

        @Override
        protected boolean execute(CommandContext context) throws CommandSyntaxException {
            return handleCreateCommand(context);
        }
    }
}
