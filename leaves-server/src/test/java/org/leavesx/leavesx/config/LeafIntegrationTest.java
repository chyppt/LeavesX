package org.leavesx.leavesx.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;

@Normal
class LeafIntegrationTest {
    @TempDir Path directory;

    @AfterEach
    void reset() { LeavesXRuntime.publish(LeavesXConfig.safeDefaults()); }

    @Test
    void upgradeKeepsValuesAndSortsPerformanceBeforePresentation() throws Exception {
        final Path file = directory.resolve("leavesx.yml");
        Files.writeString(file, "config-version: 76\nmessages:\n  join: '&e<player>来了'\nasync:\n  pathfinding:\n    enabled: false\n");
        final var config = LeavesXConfigLoader.load(file);
        assertEquals("&e<player>来了", config.joinMessage());
        assertFalse(config.asyncSettings().pathfindingEnabled());
        assertTrue(config.extensions().lithiumEnabled());
        assertFalse(config.extensions().secureSeed());
        final String yaml = Files.readString(file);
        assertTrue(yaml.indexOf("config-version:") < yaml.indexOf("performance:"));
        assertTrue(yaml.indexOf("performance:") < yaml.indexOf("async:"));
        assertTrue(yaml.indexOf("parallelism:") < yaml.indexOf("fakeplayer:"));
        assertTrue(yaml.indexOf("diagnostics:") < yaml.indexOf("messages:"));
        assertEquals(config, LeavesXConfigLoader.load(file));
    }

    @Test
    void lithiumSwitchDisablesAllIntegratedHotPaths() {
        final var node = CommentedConfigurationNode.root();
        node.node("performance", "lithium", "enabled").raw(false);
        LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
        assertFalse(LeavesXRuntime.lithiumIdleSwingOptimization());
        assertFalse(LeavesXRuntime.lithiumIdleGlideOptimization());
        assertFalse(LeavesXRuntime.hopperSlotStateCacheOptimization());
    }

    @Test
    void recursivePluginSlotChangesMatchOriginalSynchronization() {
        assertEquals(runMenu(false, false), runMenu(true, false));
        assertEquals(runMenu(false, true), runMenu(true, true));
    }

    private List<String> runMenu(final boolean reuse, final boolean failListener) {
        final var node = CommentedConfigurationNode.root();
        node.node("performance", "container-copy-reuse").raw(reuse);
        LeavesXRuntime.publish(LeavesXConfigLoader.fromNode(node, Path.of("test.yml")));
        final var menu = new ProbeMenu();
        menu.addSlotListener(new ContainerListener() {
            private boolean changed;
            @Override public void dataChanged(AbstractContainerMenu owner, int id, int value) {}
            @Override public void slotChanged(AbstractContainerMenu owner, int slot, ItemStack stack) {
                if (this.changed || slot != 0 || stack.isEmpty()) return;
                this.changed = true;
                menu.inventory.setItem(1, new ItemStack(Items.DIAMOND, 3));
                if (failListener) throw new IllegalStateException("plugin test");
                owner.broadcastChanges();
            }
        });
        menu.inventory.setItem(0, new ItemStack(Items.APPLE, 2));
        if (failListener) assertThrows(IllegalStateException.class, menu::broadcastChanges);
        else menu.broadcastChanges();
        menu.broadcastChanges();
        return menu.sent;
    }

    private static final class ProbeMenu extends AbstractContainerMenu {
        final SimpleContainer inventory = new SimpleContainer(2);
        final List<String> sent = new ArrayList<>();
        ProbeMenu() {
            super(null, 0);
            this.addSlot(new Slot(this.inventory, 0, 0, 0));
            this.addSlot(new Slot(this.inventory, 1, 0, 0));
        }
        @Override public org.bukkit.inventory.InventoryView getBukkitView() { return null; }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public void synchronizeSlotToRemote(int index, ItemStack current, Supplier<ItemStack> copy) {
            // Observe the exact copy consumed after the listener. Recursion must never substitute another slot's stack.
            this.sent.add(index + ":" + copy.get());
            assertSame(copy.get(), copy.get());
        }
    }
}
