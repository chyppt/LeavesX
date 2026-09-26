package org.leavesmc.leaves.command.bot;

import java.util.List;
import java.util.Locale;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.bot.BotCreateState;
import org.leavesmc.leaves.bot.BotList;
import org.leavesmc.leaves.bot.BotUtil;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.bot.agent.actions.AbstractBotAction;
import org.leavesmc.leaves.bot.agent.actions.ServerAttackAction;
import org.leavesmc.leaves.bot.agent.actions.ServerBreakBlockAction;
import org.leavesmc.leaves.bot.agent.actions.ServerJumpAction;
import org.leavesmc.leaves.bot.agent.actions.ServerLookAction;
import org.leavesmc.leaves.bot.agent.actions.ServerMoveAction;
import org.leavesmc.leaves.bot.agent.actions.ServerSneakAction;
import org.leavesmc.leaves.bot.agent.actions.ServerUseItemAutoAction;
import org.leavesmc.leaves.entity.bot.action.MoveAction.MoveDirection;
import org.leavesmc.leaves.event.bot.BotActionStopEvent;
import org.leavesmc.leaves.event.bot.BotCreateEvent;
import org.leavesmc.leaves.event.bot.BotRemoveEvent;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.RED;
import static org.leavesmc.leaves.command.bot.BotCommandLocale.message;

/** Main-thread-only adapters from Carpet command terms to Leaves' existing fakeplayer actions. */
final class CarpetPlayerCommandSupport {

    private CarpetPlayerCommandSupport() {
    }

    static @Nullable ServerBot findBot(final String name) {
        final ServerBot fullNameMatch = BotList.INSTANCE.getBotByName(BotUtil.getFullName(name));
        return fullNameMatch != null ? fullNameMatch : BotList.INSTANCE.getBotByName(name);
    }

    static @Nullable ServerBot createBot(
        final CommandSender sender,
        final String rawName,
        final Location location
    ) {
        final String fullName = BotUtil.getFullName(rawName);
        if (!isCreateLegal(sender, fullName) || !BotUtil.isRoleLegal("")) {
            if (!BotUtil.isRoleLegal("")) {
                sender.sendMessage(text(message(
                    "A fakeplayer role is required by the current LeavesX configuration.",
                    "当前 LeavesX 配置要求填写假人作用，请使用 /bot create。"
                ), RED));
            }
            return null;
        }
        return BotCreateState.builder(rawName, location)
            .createReason(BotCreateEvent.CreateReason.COMMAND)
            .skinName(rawName)
            .creator(sender)
            .build()
            .createNow();
    }

    private static boolean isCreateLegal(final CommandSender sender, final String fullName) {
        if (!BotUtil.isValidAccountName(fullName)) {
            sender.sendMessage(text(message(
                "Fakeplayer names must be 3-16 characters using only letters, numbers, and underscores.",
                "假人名称必须为 3-16 个字符，且只能包含字母、数字和下划线。"
            ), RED));
            return false;
        }
        if (Bukkit.getPlayerExact(fullName) != null || BotList.INSTANCE.getBotByName(fullName) != null) {
            sender.sendMessage(text(message("This fakeplayer is already online.", "该假人已在线。"), RED));
            return false;
        }
        if (BotUtil.isNameForbidden(fullName)) {
            sender.sendMessage(text(message("This fakeplayer name is forbidden.", "服务器禁止使用该假人名称。"), RED));
            return false;
        }
        if (BotList.INSTANCE.bots.size() >= LeavesConfig.modify.fakeplayer.limit) {
            sender.sendMessage(text(message("Fakeplayer limit reached.", "假人数量已达到上限。"), RED));
            return false;
        }
        return true;
    }

    static boolean removeBot(final ServerBot bot, final CommandSender sender) {
        final boolean removed = BotList.INSTANCE.removeBot(
            bot,
            BotRemoveEvent.RemoveReason.COMMAND,
            sender,
            false,
            false
        );
        if (!removed) {
            sender.sendMessage(text(message("A plugin cancelled fakeplayer removal.", "插件取消了假人移除。"), RED));
        }
        return removed;
    }

    static boolean saveBot(final ServerBot bot, final CommandSender sender) {
        if (!LeavesConfig.modify.fakeplayer.canManualSaveAndLoad) {
            sender.sendMessage(text(message("Manual fakeplayer saving is disabled.", "Leaves 配置未启用手动保存假人。"), RED));
            return false;
        }
        return BotList.INSTANCE.removeBot(bot, BotRemoveEvent.RemoveReason.COMMAND, sender, true, false);
    }

    static boolean startMove(final ServerBot bot, final MoveDirection direction, final boolean sprint) {
        stopAction(bot, "move");
        final ServerMoveAction action = new ServerMoveAction();
        action.setDirection(direction);
        final boolean scheduled = bot.addBotAction(action, null);
        if (scheduled) {
            bot.setSprinting(sprint);
        }
        return scheduled;
    }

    static boolean look(final ServerBot bot, final Vector target) {
        stopAction(bot, "look");
        final ServerLookAction action = new ServerLookAction();
        action.setPos(target);
        return bot.addBotAction(action, null);
    }

    static boolean lookCardinal(final ServerBot bot, final String direction) {
        final Vector offset = switch (direction.toLowerCase(Locale.ROOT)) {
            case "north" -> new Vector(0, 0, -1);
            case "south" -> new Vector(0, 0, 1);
            case "east" -> new Vector(1, 0, 0);
            case "west" -> new Vector(-1, 0, 0);
            case "up" -> new Vector(0, 1, 0);
            case "down" -> new Vector(0, -1, 0);
            default -> null;
        };
        return offset != null && look(bot, bot.getLocation().toVector().add(offset));
    }

    static boolean scheduleJump(final ServerBot bot, final int interval, final int count) {
        stopAction(bot, "jump");
        final ServerJumpAction action = new ServerJumpAction();
        action.setDoIntervalTick(interval);
        action.setDoNumber(count);
        return bot.addBotAction(action, null);
    }

    static boolean setSneaking(final ServerBot bot, final boolean sneaking) {
        stopAction(bot, "sneak");
        if (!sneaking) {
            bot.setShiftKeyDown(false);
            return true;
        }
        return bot.addBotAction(new ServerSneakAction(), null);
    }

    static boolean setSprinting(final ServerBot bot, final boolean sprinting) {
        bot.setSprinting(sprinting);
        return true;
    }

    static boolean scheduleAttack(final ServerBot bot, final int interval, final int count) {
        stopAction(bot, "attack");
        stopAction(bot, "break");
        final ServerAttackOrBreakAction action = new ServerAttackOrBreakAction();
        action.setDoIntervalTick(interval);
        action.setDoNumber(count);
        return bot.addBotAction(action, null);
    }

    static boolean scheduleUse(final ServerBot bot, final int interval, final int count) {
        stopAction(bot, "use");
        stopAction(bot, "use_auto");
        final ServerUseItemAutoAction action = new ServerUseItemAutoAction();
        action.setDoIntervalTick(interval);
        action.setDoNumber(count);
        return bot.addBotAction(action, null);
    }

    static boolean stopAll(final ServerBot bot) {
        final List<AbstractBotAction<?>> actions = List.copyOf(bot.getBotActions());
        actions.forEach(action -> action.stop(bot, BotActionStopEvent.Reason.COMMAND));
        bot.getBotActions().clear();
        bot.setSprinting(false);
        bot.setShiftKeyDown(false);
        return true;
    }

    static boolean dismount(final ServerBot bot) {
        if (!bot.isPassenger()) {
            return false;
        }
        bot.removeVehicle();
        return true;
    }

    static boolean selectHotbar(final ServerBot bot, final int slot) {
        bot.getInventory().setSelectedSlot(slot);
        bot.updateItemInHand(InteractionHand.MAIN_HAND);
        return true;
    }

    static boolean swapHands(final ServerBot bot) {
        final ItemStack mainHand = bot.getItemInHand(InteractionHand.MAIN_HAND);
        final ItemStack offHand = bot.getItemInHand(InteractionHand.OFF_HAND);
        bot.setItemInHand(InteractionHand.MAIN_HAND, offHand);
        bot.setItemInHand(InteractionHand.OFF_HAND, mainHand);
        bot.updateItemInHand(InteractionHand.MAIN_HAND);
        bot.updateItemInHand(InteractionHand.OFF_HAND);
        return true;
    }

    static boolean drop(final ServerBot bot, final boolean entireStack) {
        final boolean dropped = bot.drop(entireStack);
        bot.updateItemInHand(InteractionHand.MAIN_HAND);
        return dropped;
    }

    static boolean dropAll(final ServerBot bot) {
        bot.dropAll(false);
        return true;
    }

    static @Nullable Location resolveSpawnLocation(
        final CommandSender sender,
        final @Nullable World world,
        final @Nullable Location explicit
    ) {
        if (explicit != null) {
            return explicit;
        }
        if (sender instanceof org.bukkit.entity.Entity entity) {
            return entity.getLocation();
        }
        return world == null ? null : world.getSpawnLocation();
    }

    private static void stopAction(final ServerBot bot, final String name) {
        bot.getBotActions().removeIf(action -> {
            if (!action.getName().equals(name)) {
                return false;
            }
            action.stop(bot, BotActionStopEvent.Reason.COMMAND);
            return true;
        });
    }

    private static final class ServerAttackOrBreakAction extends ServerAttackAction {

        private final ServerBreakBlockAction breakAction = new ServerBreakBlockAction();

        @Override
        public boolean doTick(final @NotNull ServerBot bot) {
            return super.doTick(bot) || this.breakAction.doTick(bot);
        }
    }
}
