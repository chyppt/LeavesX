package org.leavesx.probe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Verifies that a player stops holding a pressure plate after entering spectator mode.
 *
 * <p>Leaves fake players deliberately reject game-mode commands. The probe therefore
 * changes only the fake player's private NMS game-mode field after the plate is pressed.
 * This preserves the real pressure-plate query and scheduled-tick paths while avoiding
 * a network client dependency in the live-server regression.</p>
 */
public final class LeavesXSpectatorPlateProbe extends JavaPlugin {

    private static final String BOT_NAME = "lx_plate";
    private static final int PLATE_X = 0;
    private static final int PLATE_Y = 101;
    private static final int PLATE_Z = 0;
    private static final int RELEASE_TIMEOUT_TICKS = 60;

    private World world;
    private int spectatorTicks;
    private boolean switched;

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskTimer(this, this::sample, 1L, 1L);
    }

    private void sample() {
        this.world = this.world == null ? Bukkit.getWorlds().getFirst() : this.world;
        final Player player = this.findBot();
        if (player == null) {
            return;
        }

        final Block plate = this.world.getBlockAt(PLATE_X, PLATE_Y, PLATE_Z);
        if (plate.getType() != Material.STONE_PRESSURE_PLATE || !(plate.getBlockData() instanceof Powerable powerable)) {
            this.fail("pressure-plate-missing");
            return;
        }

        if (!this.switched) {
            if (!powerable.isPowered()) {
                return;
            }
            this.getLogger().info("LX_PLATE_PRESSED");
            try {
                this.forceSpectator(player);
            } catch (ReflectiveOperationException exception) {
                this.getLogger().severe("Unable to enter spectator mode: " + exception.getMessage());
                this.fail("spectator-transition-failed");
                return;
            }
            this.switched = true;
            return;
        }

        ++this.spectatorTicks;
        if (player.getGameMode() != GameMode.SPECTATOR) {
            this.fail("player-not-spectator");
            return;
        }
        if (this.spectatorTicks == 1) {
            this.getLogger().info("LX_BOT_SPECTATOR");
        }
        if (!powerable.isPowered()) {
            this.getLogger().info("LX_PLATE_RELEASED ticks=" + this.spectatorTicks);
            this.getLogger().info("LX_SPECTATOR_PLATE_RESULT status=PASS");
            Bukkit.shutdown();
        } else if (this.spectatorTicks >= RELEASE_TIMEOUT_TICKS) {
            this.fail("pressure-plate-still-powered");
        }
    }

    private Player findBot() {
        for (final Player player : this.world.getEntitiesByClass(Player.class)) {
            if (BOT_NAME.equals(player.getName())) {
                return player;
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void forceSpectator(final Player player) throws ReflectiveOperationException {
        final Method getHandle = player.getClass().getMethod("getHandle");
        final Object serverPlayer = getHandle.invoke(player);
        final Field gameModeField = serverPlayer.getClass().getField("gameMode");
        final Object gameModeController = gameModeField.get(serverPlayer);
        final Class<?> controllerType = gameModeController.getClass().getSuperclass();
        final Field currentModeField = controllerType.getDeclaredField("gameModeForPlayer");
        currentModeField.setAccessible(true);
        final Class<? extends Enum> gameType = (Class<? extends Enum>)currentModeField.getType();
        currentModeField.set(gameModeController, Enum.valueOf(gameType, "SPECTATOR"));
    }

    private void fail(final String reason) {
        this.getLogger().severe("LX_SPECTATOR_PLATE_RESULT status=FAIL reason=" + reason);
        Bukkit.shutdown();
    }
}
