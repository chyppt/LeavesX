package org.leavesmc.leaves.command.bot;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.PaperCommands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.LeavesConfig;
import org.leavesmc.leaves.LeavesLogger;
import org.leavesmc.leaves.bot.BotList;
import org.leavesmc.leaves.bot.ServerBot;
import org.leavesmc.leaves.command.ArgumentNode;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.LiteralNode;
import org.leavesmc.leaves.command.RootNode;
import org.leavesmc.leaves.entity.bot.action.MoveAction.MoveDirection;
import org.leavesx.leavesx.config.LeavesXRuntime;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.RED;
import static org.leavesmc.leaves.command.bot.BotCommandLocale.message;

/** Optional Carpet-compatible command surface backed by Leaves fakeplayer actions. */
public final class PlayerCommand extends RootNode {

    public static final PlayerCommand INSTANCE = new PlayerCommand();
    private static final String PERMISSION = "bukkit.command.player";
    private static boolean registered;

    private PlayerCommand() {
        super("player", PERMISSION);
        this.children(PlayerNameArgument::new);
    }

    /** Registers or removes only the command owned by LeavesX as configuration changes. */
    public static synchronized void syncRegistration() {
        syncRegistration(LeavesConfig.modify.fakeplayer.enable);
    }

    /** Applies a prospective Leaves fakeplayer enable value while its validator is still running. */
    public static synchronized void syncRegistration(final boolean fakeplayerEnabled) {
        final boolean shouldRegister = LeavesConfig.isInitialized()
            && fakeplayerEnabled
            && LeavesXRuntime.carpetPlayerCommand();
        if (shouldRegister == registered) {
            return;
        }
        if (shouldRegister) {
            // Leaves configuration loads outside Paper's command lifecycle context. This is a read-only ownership
            // check; actual registration still goes through RootNode.register(), which manages dispatcher validity.
            if (PaperCommands.INSTANCE.getDispatcherInternal().getRoot().getChild("player") != null) {
                LeavesLogger.LOGGER.warn("LeavesX did not register /player because another command already owns that name");
                return;
            }
            INSTANCE.register();
            registered = true;
        } else {
            INSTANCE.unregister();
            registered = false;
        }
    }

    static boolean isRegistered() {
        return registered;
    }

    private static final class PlayerNameArgument extends ArgumentNode<String> {

        private PlayerNameArgument() {
            super("name", StringArgumentType.word());
            this.children(
                SpawnNode::new,
                () -> new SimpleBotNode("kill", CarpetPlayerCommandSupport::removeBot),
                () -> new SimpleBotNode("save", CarpetPlayerCommandSupport::saveBot),
                MoveNode::new,
                LookNode::new,
                JumpNode::new,
                () -> new SimpleBotNode("sneak", (bot, ignored) -> CarpetPlayerCommandSupport.setSneaking(bot, true)),
                () -> new SimpleBotNode("unsneak", (bot, ignored) -> CarpetPlayerCommandSupport.setSneaking(bot, false)),
                () -> new SimpleBotNode("sprint", (bot, ignored) -> CarpetPlayerCommandSupport.setSprinting(bot, true)),
                () -> new SimpleBotNode("unsprint", (bot, ignored) -> CarpetPlayerCommandSupport.setSprinting(bot, false)),
                () -> new TimedBotNode("attack", CarpetPlayerCommandSupport::scheduleAttack),
                () -> new TimedBotNode("use", CarpetPlayerCommandSupport::scheduleUse),
                () -> new SimpleBotNode("stop", (bot, ignored) -> CarpetPlayerCommandSupport.stopAll(bot)),
                () -> new SimpleBotNode("dismount", (bot, ignored) -> CarpetPlayerCommandSupport.dismount(bot)),
                HotbarNode::new,
                () -> new SimpleBotNode("swaphands", (bot, ignored) -> CarpetPlayerCommandSupport.swapHands(bot)),
                () -> new SimpleBotNode("drop", (bot, ignored) -> CarpetPlayerCommandSupport.drop(bot, false)),
                () -> new SimpleBotNode("dropstack", (bot, ignored) -> CarpetPlayerCommandSupport.drop(bot, true)),
                () -> new SimpleBotNode("dropall", (bot, ignored) -> CarpetPlayerCommandSupport.dropAll(bot))
            );
        }

        @Override
        protected CompletableFuture<Suggestions> getSuggestions(
            final CommandContext context,
            final SuggestionsBuilder builder
        ) {
            BotList.INSTANCE.bots.forEach(bot -> builder.suggest(bot.getScoreboardName()));
            return builder.buildFuture();
        }
    }

    private abstract static class PlayerNode extends LiteralNode {

        private PlayerNode(final String name) {
            super(name);
        }

        protected final ServerBot target(final CommandContext context) throws CommandSyntaxException {
            final String name = context.getArgument("name", String.class);
            final ServerBot bot = CarpetPlayerCommandSupport.findBot(name);
            if (bot != null) {
                return bot;
            }
            throw new SimpleCommandExceptionType(net.minecraft.network.chat.Component.literal(
                message("Fakeplayer not found: ", "未找到假人：") + name
            )).create();
        }
    }

    @FunctionalInterface
    private interface BotOperation {
        boolean execute(ServerBot bot, CommandSender sender);
    }

    private static final class SimpleBotNode extends PlayerNode {

        private final BotOperation operation;

        private SimpleBotNode(final String name, final BotOperation operation) {
            super(name);
            this.operation = operation;
        }

        @Override
        protected boolean execute(final CommandContext context) throws CommandSyntaxException {
            return this.operation.execute(this.target(context), context.getSender());
        }
    }

    @FunctionalInterface
    private interface TimedOperation {
        boolean execute(ServerBot bot, int interval, int count);
    }

    private static final class TimedBotNode extends PlayerNode {

        private final TimedOperation operation;

        private TimedBotNode(final String name, final TimedOperation operation) {
            super(name);
            this.operation = operation;
            this.children(
                () -> new TimedModeNode("once", operation, 0, 1),
                () -> new TimedModeNode("continuous", operation, 1, -1),
                () -> new IntervalNode(operation)
            );
        }

        @Override
        protected boolean execute(final CommandContext context) throws CommandSyntaxException {
            return this.operation.execute(this.target(context), 0, 1);
        }
    }

    private static final class TimedModeNode extends PlayerNode {

        private final TimedOperation operation;
        private final int interval;
        private final int count;

        private TimedModeNode(final String name, final TimedOperation operation, final int interval, final int count) {
            super(name);
            this.operation = operation;
            this.interval = interval;
            this.count = count;
        }

        @Override
        protected boolean execute(final CommandContext context) throws CommandSyntaxException {
            return this.operation.execute(this.target(context), this.interval, this.count);
        }
    }

    private static final class IntervalNode extends LiteralNode {

        private IntervalNode(final TimedOperation operation) {
            super("interval");
            this.children(() -> new IntervalTicksArgument(operation));
        }
    }

    private static final class IntervalTicksArgument extends ArgumentNode<Integer> {

        private final TimedOperation operation;

        private IntervalTicksArgument(final TimedOperation operation) {
            super("ticks", IntegerArgumentType.integer(1));
            this.operation = operation;
        }

        @Override
        protected boolean execute(final CommandContext context) throws CommandSyntaxException {
            final ServerBot bot = targetBot(context);
            return this.operation.execute(bot, context.getArgument("ticks", Integer.class), -1);
        }
    }

    private static final class JumpNode extends PlayerNode {

        private JumpNode() {
            super("jump");
            this.children(() -> new TimedModeNode("once", CarpetPlayerCommandSupport::scheduleJump, 0, 1));
        }

        @Override
        protected boolean execute(final CommandContext context) throws CommandSyntaxException {
            return CarpetPlayerCommandSupport.scheduleJump(this.target(context), 1, -1);
        }
    }

    private static final class MoveNode extends PlayerNode {

        private MoveNode() {
            super("move");
            this.children(DirectionArgument::new);
        }

        private static final class DirectionArgument extends ArgumentNode<String> {

            private DirectionArgument() {
                super("direction", StringArgumentType.word());
            }

            @Override
            protected CompletableFuture<Suggestions> getSuggestions(
                final CommandContext context,
                final SuggestionsBuilder builder
            ) {
                for (final String value : new String[] {"forward", "backward", "left", "right", "sprint", "stop"}) {
                    builder.suggest(value);
                }
                return builder.buildFuture();
            }

            @Override
            protected boolean execute(final CommandContext context) throws CommandSyntaxException {
                final ServerBot bot = targetBot(context);
                final String direction = context.getArgument("direction", String.class).toLowerCase(Locale.ROOT);
                if (direction.equals("stop")) {
                    return CarpetPlayerCommandSupport.stopAll(bot);
                }
                final MoveDirection moveDirection = switch (direction) {
                    case "forward", "sprint" -> MoveDirection.FORWARD;
                    case "backward" -> MoveDirection.BACKWARD;
                    case "left" -> MoveDirection.LEFT;
                    case "right" -> MoveDirection.RIGHT;
                    default -> null;
                };
                if (moveDirection == null) {
                    context.getSender().sendMessage(text(message("Unknown move direction.", "未知移动方向。"), RED));
                    return false;
                }
                return CarpetPlayerCommandSupport.startMove(bot, moveDirection, direction.equals("sprint"));
            }
        }
    }

    private static final class LookNode extends PlayerNode {

        private LookNode() {
            super("look");
            this.children(LookDirectionArgument::new, LookAtNode::new);
        }

        private static final class LookDirectionArgument extends ArgumentNode<String> {

            private LookDirectionArgument() {
                super("direction", StringArgumentType.word());
            }

            @Override
            protected CompletableFuture<Suggestions> getSuggestions(
                final CommandContext context,
                final SuggestionsBuilder builder
            ) {
                for (final String value : new String[] {"north", "south", "east", "west", "up", "down"}) {
                    builder.suggest(value);
                }
                return builder.buildFuture();
            }

            @Override
            protected boolean execute(final CommandContext context) throws CommandSyntaxException {
                return CarpetPlayerCommandSupport.lookCardinal(
                    targetBot(context),
                    context.getArgument("direction", String.class)
                );
            }
        }

        private static final class LookAtNode extends LiteralNode {
            private LookAtNode() {
                super("at");
                this.children(LookXArgument::new);
            }
        }

        private static final class LookXArgument extends ArgumentNode<Double> {
            private LookXArgument() {
                super("x", DoubleArgumentType.doubleArg());
                this.children(LookYArgument::new);
            }
        }

        private static final class LookYArgument extends ArgumentNode<Double> {
            private LookYArgument() {
                super("y", DoubleArgumentType.doubleArg());
                this.children(LookZArgument::new);
            }
        }

        private static final class LookZArgument extends ArgumentNode<Double> {
            private LookZArgument() {
                super("z", DoubleArgumentType.doubleArg());
            }

            @Override
            protected boolean execute(final CommandContext context) throws CommandSyntaxException {
                return CarpetPlayerCommandSupport.look(
                    targetBot(context),
                    new Vector(
                        context.getArgument("x", Double.class),
                        context.getArgument("y", Double.class),
                        context.getArgument("z", Double.class)
                    )
                );
            }
        }
    }

    private static final class HotbarNode extends PlayerNode {

        private HotbarNode() {
            super("hotbar");
            this.children(HotbarSlotArgument::new);
        }

        private static final class HotbarSlotArgument extends ArgumentNode<Integer> {
            private HotbarSlotArgument() {
                super("slot", IntegerArgumentType.integer(1, 9));
            }

            @Override
            protected boolean execute(final CommandContext context) throws CommandSyntaxException {
                return CarpetPlayerCommandSupport.selectHotbar(
                    targetBot(context),
                    context.getArgument("slot", Integer.class) - 1
                );
            }
        }
    }

    private static final class SpawnNode extends LiteralNode {

        private SpawnNode() {
            super("spawn");
            this.children(SpawnAtNode::new, SpawnInNode::new);
        }

        @Override
        protected boolean execute(final CommandContext context) {
            return spawn(context, null, null);
        }
    }

    private static final class SpawnAtNode extends LiteralNode {
        private SpawnAtNode() {
            super("at");
            this.children(SpawnXArgument::new);
        }
    }

    private static final class SpawnXArgument extends ArgumentNode<Double> {
        private SpawnXArgument() {
            super("spawn_x", DoubleArgumentType.doubleArg());
            this.children(SpawnYArgument::new);
        }
    }

    private static final class SpawnYArgument extends ArgumentNode<Double> {
        private SpawnYArgument() {
            super("spawn_y", DoubleArgumentType.doubleArg());
            this.children(SpawnZArgument::new);
        }
    }

    private static final class SpawnZArgument extends ArgumentNode<Double> {
        private SpawnZArgument() {
            super("spawn_z", DoubleArgumentType.doubleArg());
            this.children(SpawnInNode::new);
        }

        @Override
        protected boolean execute(final CommandContext context) {
            return spawn(context, explicitLocation(context, null), null);
        }
    }

    private static final class SpawnInNode extends LiteralNode {
        private SpawnInNode() {
            super("in");
            this.children(SpawnWorldArgument::new);
        }
    }

    private static final class SpawnWorldArgument extends ArgumentNode<World> {
        private SpawnWorldArgument() {
            super("world", ArgumentTypes.world());
        }

        @Override
        protected boolean execute(final CommandContext context) {
            final World world = context.getArgument("world", World.class);
            final Double x = context.getArgumentOrDefault("spawn_x", Double.class, null);
            return spawn(context, x == null ? null : explicitLocation(context, world), world);
        }
    }

    private static boolean spawn(
        final CommandContext context,
        final Location explicit,
        final World explicitWorld
    ) {
        final CommandSender sender = context.getSender();
        final Location location = CarpetPlayerCommandSupport.resolveSpawnLocation(sender, explicitWorld, explicit);
        if (location == null) {
            sender.sendMessage(text(message(
                "Console usage requires a world, or a world and coordinates.",
                "控制台执行时必须指定世界，或同时指定世界和坐标。"
            ), RED));
            return false;
        }
        final String rawName = context.getArgument("name", String.class);
        final ServerBot bot = CarpetPlayerCommandSupport.createBot(sender, rawName, location);
        if (bot == null) {
            return false;
        }
        sender.sendMessage(text(message("Created fakeplayer ", "已创建假人 ") + bot.getScoreboardName(), GRAY));
        return true;
    }

    private static Location explicitLocation(final CommandContext context, final World world) {
        final World resolvedWorld = world != null
            ? world
            : context.getSender() instanceof org.bukkit.entity.Entity entity ? entity.getWorld() : null;
        return resolvedWorld == null ? null : new Location(
            resolvedWorld,
            context.getArgument("spawn_x", Double.class),
            context.getArgument("spawn_y", Double.class),
            context.getArgument("spawn_z", Double.class)
        );
    }

    private static ServerBot targetBot(final CommandContext context) throws CommandSyntaxException {
        final String name = context.getArgument("name", String.class);
        final ServerBot bot = CarpetPlayerCommandSupport.findBot(name);
        if (bot != null) {
            return bot;
        }
        throw new SimpleCommandExceptionType(net.minecraft.network.chat.Component.literal(
            message("Fakeplayer not found: ", "未找到假人：") + name
        )).create();
    }
}
