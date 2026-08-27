package org.leavesmc.leaves.command.leaves.subcommands;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.LeavesLogger;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.leaves.LeavesSubcommand;
import org.leavesmc.leaves.util.McTechnicalModeHelper;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.GREEN;
import static net.kyori.adventure.text.format.NamedTextColor.RED;

public class ReloadCommand extends LeavesSubcommand {
    public ReloadCommand() {
        super("reload");
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) throws CommandSyntaxException {
        CommandSender sender = context.getSender();
        try {
            LeavesConfig.reload();
            // Keep Paper's unsupported piston settings aligned with the locked Leaves technical-mode setting.
            McTechnicalModeHelper.doMcTechnicalModeIf();
        } catch (final RuntimeException exception) {
            LeavesLogger.LOGGER.error("Failed to reload Leaves configuration", exception);
            sender.sendMessage(text("Leaves 配置重载失败，请查看控制台日志。", RED));
            return false;
        }

        sender.sendMessage(text("Leaves 配置重载完成（LeavesX 请使用 /leavesx reload）", GREEN));
        Bukkit.getOnlinePlayers().stream()
            .filter(player -> player.hasPermission("leaves.command.config.notify") && player != sender)
            .forEach(player -> player.sendMessage(text("Leaves 配置已重载", GREEN)));
        return true;
    }
}
