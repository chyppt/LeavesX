package org.leavesx.probe;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.structure.Structure;
import org.bukkit.util.Vector;

/**
 * Live-server regression probe for piston-based TNT duplication.
 *
 * <p>The fixture is a real, unmodified OSC TNT duper. The probe records TNT at the
 * Bukkit spawn boundary, then extends its fuse and removes it after sampling so the
 * fixture can be exercised repeatedly without destroying the test world. It never
 * changes piston, redstone, collision, or TNT block state during an active cycle.</p>
 */
public final class LeavesXTntDuplicationProbe extends JavaPlugin implements Listener {

    private static final String FIXTURE_RESOURCE = "/osc-tnt-duper.nbt";
    private static final int BASE_X = 0;
    private static final int BASE_Y = 100;
    private static final int BASE_Z = 0;
    private static final int SIZE_X = 17;
    private static final int SIZE_Y = 12;
    private static final int SIZE_Z = 7;
    private static final int EXPECTED_TNT_BLOCKS = 10;
    private static final int LEVER_X = 9;
    private static final int LEVER_Y = 11;
    private static final int LEVER_Z = 1;
    private static final double EPSILON = 1.0E-6D;
    private static final int EXPECTED_ACTIVE_SPAWNS = 10;

    private final List<SpawnSnapshot> spawns = new ArrayList<>();
    private final Set<UUID> trackedTnt = new HashSet<>();
    private int explosions;
    private int firstTickSamples;
    private int movingFirstTickSamples;
    private World world;
    private int initialTntBlocks;

    @Override
    public void onEnable() {
        this.getLogger().info(
            "LX_PISTON_SETTING startup="
                + this.readPaperBoolean("allowPistonDuplication")
                + " headless=" + this.readPaperBoolean("allowHeadlessPistons")
        );
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, this::prepareFixture, 40L);
    }

    private void prepareFixture() {
        this.world = Bukkit.getWorlds().getFirst();
        this.world.getChunkAt(BASE_X >> 4, BASE_Z >> 4).addPluginChunkTicket(this);
        this.clearFixtureVolume();

        try (InputStream input = this.getClass().getResourceAsStream(FIXTURE_RESOURCE)) {
            if (input == null) {
                this.fail("fixture-resource-missing");
                return;
            }
            final Structure structure = Bukkit.getStructureManager().loadStructure(input);
            structure.place(
                new Location(this.world, BASE_X, BASE_Y, BASE_Z),
                false,
                StructureRotation.NONE,
                Mirror.NONE,
                0,
                1.0F,
                new Random(0L)
            );
        } catch (IOException exception) {
            this.getLogger().severe("Unable to load TNT fixture: " + exception.getMessage());
            this.fail("fixture-load-failed");
            return;
        }

        Bukkit.getScheduler().runTaskLater(this, this::beginCycles, 40L);
    }

    private void beginCycles() {
        this.initialTntBlocks = this.countFixtureTntBlocks();
        this.getLogger().info("LX_TNT_BASELINE blocks=" + this.initialTntBlocks);
        if (this.initialTntBlocks != EXPECTED_TNT_BLOCKS) {
            this.fail("unexpected-baseline-tnt-count");
            return;
        }

        // Structure placement can legitimately trigger the armed machine. Remove those primed entities before
        // resetting every counter so only complete cycles started below contribute to the result.
        for (final TNTPrimed existingTnt : this.world.getEntitiesByClass(TNTPrimed.class)) {
            if (this.isNearFixture(existingTnt.getLocation())) {
                existingTnt.remove();
            }
        }
        this.spawns.clear();
        this.trackedTnt.clear();
        this.explosions = 0;
        this.firstTickSamples = 0;
        this.movingFirstTickSamples = 0;

        // The saved OSC fixture is armed. A full off/on transition starts its redstone clock.
        if (!this.setLeverPowered(false)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> this.setLeverPowered(true), 20L);
        Bukkit.getScheduler().runTaskLater(this, () -> this.setLeverPowered(false), 240L);
        // TNT keeps its vanilla fuse and physics. The explosion event below only prevents fixture damage.
        Bukkit.getScheduler().runTaskLater(this, this::finish, 360L);
    }

    private boolean setLeverPowered(final boolean powered) {
        final Block leverBlock = this.world.getBlockAt(BASE_X + LEVER_X, BASE_Y + LEVER_Y, BASE_Z + LEVER_Z);
        final BlockData blockData = leverBlock.getBlockData();
        if (!(blockData instanceof Switch lever)) {
            this.fail("fixture-lever-missing-" + leverBlock.getType().getKey());
            return false;
        }
        lever.setPowered(powered);
        leverBlock.setBlockData(lever, true);
        this.getLogger().info("LX_TNT_LEVER powered=" + powered);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntitySpawn(final EntitySpawnEvent event) {
        if (this.world == null || event.getEntityType() != EntityType.TNT || event.getEntity().getWorld() != this.world) {
            return;
        }
        final Location location = event.getLocation();
        if (!this.isNearFixture(location)) {
            return;
        }

        final TNTPrimed tnt = (TNTPrimed)event.getEntity();
        final Vector velocity = tnt.getVelocity();
        final SpawnSnapshot snapshot = new SpawnSnapshot(
            location.getX(),
            location.getY(),
            location.getZ(),
            velocity.getX(),
            velocity.getY(),
            velocity.getZ(),
            tnt.getFuseTicks(),
            event.isCancelled()
        );
        this.spawns.add(snapshot);
        this.trackedTnt.add(tnt.getUniqueId());
        this.getLogger().info("LX_TNT_SPAWN " + snapshot.describe());

        final Location spawnLocation = location.clone();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!tnt.isValid()) {
                return;
            }
            this.firstTickSamples++;
            if (tnt.getLocation().distanceSquared(spawnLocation) > EPSILON) {
                this.movingFirstTickSamples++;
            }
            this.getLogger().info(
                "LX_TNT_TICK1 pos=" + describeLocation(tnt.getLocation())
                    + " velocity=" + describeVector(tnt.getVelocity())
                    + " fuse=" + tnt.getFuseTicks()
            );
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(final EntityExplodeEvent event) {
        if (this.world == null
            || event.getEntityType() != EntityType.TNT
            || event.getLocation().getWorld() != this.world
            || !this.trackedTnt.remove(event.getEntity().getUniqueId())) {
            return;
        }
        this.explosions++;
        // Preserve the machine while retaining vanilla fuse, movement, collision and explosion position.
        event.setCancelled(true);
        this.getLogger().info("LX_TNT_EXPLODE pos=" + describeLocation(event.getLocation()));
    }

    private void finish() {
        final int finalTntBlocks = this.countFixtureTntBlocks();
        final long validInitialMotion = this.spawns.stream().filter(SpawnSnapshot::hasVanillaInitialMotion).count();
        final boolean passed = this.initialTntBlocks == EXPECTED_TNT_BLOCKS
            && finalTntBlocks == EXPECTED_TNT_BLOCKS
            && this.spawns.size() >= EXPECTED_ACTIVE_SPAWNS
            && validInitialMotion == this.spawns.size()
            && this.spawns.stream().noneMatch(SpawnSnapshot::cancelled)
            && this.firstTickSamples == this.spawns.size()
            && this.movingFirstTickSamples == this.firstTickSamples
            && this.explosions > 0;
        this.getLogger().info(
            "LX_TNT_RESULT status=" + (passed ? "PASS" : "FAIL")
                + " initial-blocks=" + this.initialTntBlocks
                + " final-blocks=" + finalTntBlocks
                + " spawns=" + this.spawns.size()
                + " vanilla-motion=" + validInitialMotion
                + " tick1=" + this.firstTickSamples
                + " moving-tick1=" + this.movingFirstTickSamples
                + " explosions=" + this.explosions
        );
        Bukkit.shutdown();
    }

    private static String describeLocation(final Location location) {
        return String.format(Locale.ROOT, "%.6f,%.6f,%.6f", location.getX(), location.getY(), location.getZ());
    }

    private static String describeVector(final Vector vector) {
        return String.format(Locale.ROOT, "%.9f,%.9f,%.9f", vector.getX(), vector.getY(), vector.getZ());
    }

    private int countFixtureTntBlocks() {
        int count = 0;
        for (int x = 0; x < SIZE_X; ++x) {
            for (int y = 0; y < SIZE_Y; ++y) {
                for (int z = 0; z < SIZE_Z; ++z) {
                    if (this.world.getBlockAt(BASE_X + x, BASE_Y + y, BASE_Z + z).getType() == Material.TNT) {
                        ++count;
                    }
                }
            }
        }
        return count;
    }

    private void clearFixtureVolume() {
        for (int x = -2; x < SIZE_X + 2; ++x) {
            for (int y = -2; y < SIZE_Y + 2; ++y) {
                for (int z = -2; z < SIZE_Z + 2; ++z) {
                    this.world.getBlockAt(BASE_X + x, BASE_Y + y, BASE_Z + z).setType(Material.AIR, false);
                }
            }
        }
    }

    private boolean isNearFixture(final Location location) {
        return location.getX() >= BASE_X - 2.0D
            && location.getX() <= BASE_X + SIZE_X + 2.0D
            && location.getY() >= BASE_Y - 8.0D
            && location.getY() <= BASE_Y + SIZE_Y + 2.0D
            && location.getZ() >= BASE_Z - 2.0D
            && location.getZ() <= BASE_Z + SIZE_Z + 2.0D;
    }

    private void fail(final String reason) {
        this.getLogger().severe("LX_TNT_RESULT status=FAIL reason=" + reason);
        Bukkit.shutdown();
    }

    private boolean readPaperBoolean(final String fieldName) {
        try {
            final Object configuration = Class.forName("io.papermc.paper.configuration.GlobalConfiguration")
                .getMethod("get")
                .invoke(null);
            final Object unsupported = configuration.getClass().getField("unsupportedSettings").get(configuration);
            return unsupported.getClass().getField(fieldName).getBoolean(unsupported);
        } catch (ReflectiveOperationException exception) {
            this.getLogger().warning("Unable to inspect Paper piston setting: " + exception.getMessage());
            return false;
        }
    }

    private record SpawnSnapshot(
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ,
        int fuse,
        boolean cancelled
    ) {

        private boolean hasVanillaInitialMotion() {
            final double horizontalSpeedSquared = this.velocityX * this.velocityX + this.velocityZ * this.velocityZ;
            return Math.abs(horizontalSpeedSquared - 0.0004D) <= EPSILON
                && Math.abs(this.velocityY - 0.2D) <= EPSILON
                && this.fuse == 80;
        }

        private String describe() {
            return String.format(
                Locale.ROOT,
                "pos=%.6f,%.6f,%.6f velocity=%.9f,%.9f,%.9f fuse=%d cancelled=%s",
                this.x,
                this.y,
                this.z,
                this.velocityX,
                this.velocityY,
                this.velocityZ,
                this.fuse,
                this.cancelled
            );
        }
    }
}
