package org.leavesx.leavesx.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentTable;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leavesx.leavesx.config.LeavesXConfig;
import org.leavesx.leavesx.config.LeavesXConfigLoader;
import org.leavesx.leavesx.config.LeavesXRuntime;

/** 装备槽位复用与物品同引用比较不得改变原版匹配结果。 */
@Normal
public class ItemIdentityFastPathTest {
    @TempDir
    Path directory;

    @Test
    void sameStackShortcutsRemainEquivalentToComponentComparison() throws Exception {
        final ItemStack stack = new ItemStack(Items.STONE);
        final ItemStack named = stack.copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("测试"));
        final ItemStack zeroCount = stack.copy();
        zeroCount.setCount(0);
        final List<ItemStack> stacks = List.of(ItemStack.EMPTY, stack, stack.copy(), named, zeroCount,
            new ItemStack(Items.DIRT), new ItemStack(Items.STONE, 64));
        try {
            for (final boolean enabled : new boolean[] {false, true}) {
                this.configure(enabled);
                for (final ItemStack left : stacks) {
                    for (final ItemStack right : stacks) {
                        assertEquals(left.is(right.getItem()), ItemStack.isSameItem(left, right));
                        final boolean expected = left.is(right.getItem()) &&
                            (left.isEmpty() && right.isEmpty() || Objects.equals(left.getComponents(), right.getComponents()));
                        assertEquals(expected, ItemStack.isSameItemSameComponents(left, right));
                    }
                }
                assertThrows(NullPointerException.class, () -> ItemStack.isSameItem(null, null));
                assertThrows(NullPointerException.class, () -> ItemStack.isSameItemSameComponents(null, null));
                final var factory = EquipmentTable.class.getDeclaredMethod("createForAllSlots", float.class);
                factory.setAccessible(true);
                final var chances = (java.util.Map<?, ?>)factory.invoke(null, 0.25F);
                assertEquals(EquipmentSlot.VALUES.size(), chances.size());
                for (final EquipmentSlot slot : EquipmentSlot.VALUES) assertEquals(0.25F, chances.get(slot));
            }
        } finally {
            publish(LeavesXConfig.safeDefaults());
        }
    }

    @Test
    void immutableEquipmentListContainsEverySlotInEnumOrder() {
        final EquipmentSlot[] values = EquipmentSlot.values();
        assertEquals(java.util.Arrays.asList(values), EquipmentSlot.VALUES);
    }

    private void configure(final boolean enabled) throws Exception {
        final Path path = this.directory.resolve("switch-" + enabled + ".yml");
        Files.writeString(path, "performance:\n  memory:\n    immutable-caches: " + enabled + "\n");
        publish(LeavesXConfigLoader.load(path));
        assertEquals(enabled, LeavesXRuntime.immutableCacheOptimizations());
    }

    private static void publish(final LeavesXConfig config) throws Exception {
        final var method = LeavesXRuntime.class.getDeclaredMethod("publish", LeavesXConfig.class);
        method.setAccessible(true);
        method.invoke(null, config);
    }
}
