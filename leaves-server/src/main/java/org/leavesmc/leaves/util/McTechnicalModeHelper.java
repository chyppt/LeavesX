package org.leavesmc.leaves.util;

import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.configuration.WorldConfiguration;
import io.papermc.paper.configuration.type.number.IntOr;
import org.leavesmc.leaves.LeavesConfig;

import java.util.Map;

public final class McTechnicalModeHelper {

    private McTechnicalModeHelper() {
    }

    public static void doMcTechnicalModeIf() {
        if (LeavesConfig.modify.mcTechnicalMode) {
            doMcTechnicalMode();
        }
    }

    public static void doMcTechnicalMode() {
        // Restore the exact update/collision semantics required by technical redstone machines. These assignments
        // happen after the complete Leaves document has loaded, so legacy user values cannot override them later in
        // the same bootstrap cycle.
        LeavesConfig.modify.noTNTPlaceUpdate = false;
        LeavesConfig.modify.noBlockUpdateCommand = false;
        LeavesConfig.modify.oldMC.updater.instantBlockUpdaterReintroduced = false;
        LeavesConfig.modify.oldMC.updater.cceUpdateSuppression = false;
        LeavesConfig.modify.oldMC.updater.soundUpdateSuppression = false;
        LeavesConfig.modify.oldMC.updater.redstoneIgnoreUpwardsUpdate = false;
        LeavesConfig.modify.oldMC.updater.oldBlockRemoveBehaviour = false;
        LeavesConfig.performance.skipEntityMoveIfMovementIsZero = false;
        LeavesConfig.performance.skipNegligiblePlanarMovementMultiplication = false;
        LeavesConfig.fix.collisionBehavior = LeavesConfig.FixConfig.CollisionBehavior.VANILLA;

        GlobalConfiguration.get().unsupportedSettings.allowPistonDuplication = true;
        GlobalConfiguration.get().unsupportedSettings.allowHeadlessPistons = true;
        GlobalConfiguration.get().unsupportedSettings.allowPermanentBlockBreakExploits = true;
        GlobalConfiguration.get().unsupportedSettings.allowUnsafeEndPortalTeleportation = true;
        GlobalConfiguration.get().unsupportedSettings.skipTripwireHookPlacementValidation = true;
        GlobalConfiguration.get().packetLimiter.allPackets = new GlobalConfiguration.PacketLimiter.PacketLimit(GlobalConfiguration.get().packetLimiter.allPackets.interval(),
            5000.0, GlobalConfiguration.get().packetLimiter.allPackets.action());
        GlobalConfiguration.get().packetLimiter.overrides = Map.of();
        GlobalConfiguration.get().itemValidation.resolveSelectorsInBooks = true;
        GlobalConfiguration.get().scoreboards.saveEmptyScoreboardTeams = true;
    }

    public static void onWorldConfigCreate(WorldConfiguration config) {
        if (LeavesConfig.isInitialized() && LeavesConfig.modify.mcTechnicalMode) {
            config.misc.redstoneImplementation = WorldConfiguration.Misc.RedstoneImplementation.VANILLA;
            // Apply directly so repeated configuration reloads cannot accumulate callbacks.
            config.entities.spawning.maxArrowDespawnInvulnerability = IntOr.Disabled.DISABLED;
        }
    }
}
