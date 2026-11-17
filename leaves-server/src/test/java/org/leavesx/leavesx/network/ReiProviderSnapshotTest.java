package org.leavesx.leavesx.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.protocol.rei.display.CraftingDisplay;
import org.leavesmc.leaves.protocol.rei.display.CookingDisplay;
import org.leavesmc.leaves.protocol.rei.display.Display;
import org.leavesmc.leaves.protocol.rei.payload.DisplaySyncPayload;
import org.leavesmc.leaves.protocol.rei.provider.RecipeViewerReiSnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Normal
class ReiProviderSnapshotTest {
    private static final String SHAPED = """
        {"schemaVersion":1,"source":"craftengine:content","recipes":[{
        "id":"farmersdelight:cabbage_recipe","type":"shaped","width":2,"height":2,
        "inputs":[{"slot":3,"choices":["AA=="]}],"outputs":["AA=="]}]}
        """;

    @Test
    void providerSmithingPreservesEmptyTemplateAndAdditionSlots() {
        try (var bytes = mockStatic(org.bukkit.inventory.ItemStack.class, CALLS_REAL_METHODS)) {
            bytes.when(() -> org.bukkit.inventory.ItemStack.deserializeBytes(any(byte[].class)))
                    .thenReturn(org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(new ItemStack(Items.PAPER, 3)));
            String json = """
                {"schemaVersion":1,"recipes":[{"id":"test:smithing","type":"smithing",
                "inputs":[{"slot":0,"empty":true},{"slot":1,"choices":["AA=="]},{"slot":2,"empty":true}],
                "outputs":["AA=="]}]}
                """;
            var displays = RecipeViewerReiSnapshot.parse(json, "craftengine:content");
            assertEquals(1, displays.size());
            assertTrue(RecipeViewerReiSnapshot.parse(json.replace("\"slot\":2", "\"slot\":0"), "craftengine:content").isEmpty());
            assertTrue(RecipeViewerReiSnapshot.parse(json.replace("\"slot\":1,\"choices\":[\"AA==\"]", "\"slot\":1,\"empty\":true"), "craftengine:content").isEmpty());
            var smithing = (org.leavesmc.leaves.protocol.rei.display.SmithingDisplay) displays.getFirst();
            assertEquals("minecraft:default/smithing", smithing.getSerializerId().toString());
            assertTrue(smithing.getInputEntries().get(0).isEmpty());
            assertFalse(smithing.getInputEntries().get(1).isEmpty());
            assertTrue(smithing.getInputEntries().get(2).isEmpty());
        }
    }

    @Test
    void sparseShapeRetainsSlotsAndCompleteInputComponentsOnWire() {
        ItemStack stack = customStack();
        try (var bytes = mockStatic(org.bukkit.inventory.ItemStack.class, CALLS_REAL_METHODS)) {
            bytes.when(() -> org.bukkit.inventory.ItemStack.deserializeBytes(any(byte[].class)))
                    .thenReturn(CraftItemStack.asCraftMirror(stack));
            var displays = RecipeViewerReiSnapshot.parse(SHAPED, "craftengine:content");
            assertEquals(1, displays.size());
            CraftingDisplay shaped = (CraftingDisplay) displays.getFirst();
            assertEquals(2, shaped.getWidth());
            assertEquals(2, shaped.getHeight());
            assertTrue(shaped.getInputEntries().subList(0, 3).stream().allMatch(entry -> entry.isEmpty()));
            assertEquals(stack.get(DataComponents.CUSTOM_DATA), shaped.getInputEntries().get(3).get(0).get(DataComponents.CUSTOM_DATA));
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryHelper.registryAccess());
            try {
                DisplaySyncPayload.STREAM_CODEC.encode(buffer,
                        new DisplaySyncPayload(DisplaySyncPayload.SyncType.SET, displays, 42));
                assertEquals(1, buffer.readVarInt()); // SET (not APPEND)
                assertEquals(1, buffer.readVarInt());
                assertTrue(buffer.readBoolean());
                var entry = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(buffer.readByteArray()), RegistryHelper.registryAccess());
                try {
                    assertEquals("minecraft:default/crafting/shaped", entry.readIdentifier().toString());
                    assertEquals(4, entry.readVarInt());
                    for (int i = 0; i < 3; i++) assertEquals(0, entry.readVarInt());
                    readStack(entry, stack);
                    assertEquals(1, entry.readVarInt()); // one output ingredient
                    readStack(entry, stack);
                    assertTrue(entry.readBoolean());
                    assertEquals("farmersdelight:cabbage_recipe", entry.readIdentifier().toString());
                    assertEquals(2, entry.readInt());
                    assertEquals(2, entry.readInt());
                    assertFalse(entry.isReadable());
                } finally { entry.release(); }
                assertEquals(42, buffer.readLong());
                assertFalse(buffer.isReadable());
            } finally { buffer.release(); }
        }
    }

    @Test
    void emptyCraftingMatrixIsNotAValidDisplay() {
        try (var bytes = mockStatic(org.bukkit.inventory.ItemStack.class, CALLS_REAL_METHODS)) {
            bytes.when(() -> org.bukkit.inventory.ItemStack.deserializeBytes(any(byte[].class)))
                    .thenReturn(CraftItemStack.asCraftMirror(new ItemStack(Items.STONE)));
            assertTrue(RecipeViewerReiSnapshot.parse(SHAPED.replace(
                    "[{\"slot\":3,\"choices\":[\"AA==\"]}]", "[]"), "craftengine:content").isEmpty());
        }
    }

    @Test
    void cookingTypesKeepTheirOriginalSerializerAndParameters() {
        try (var bytes = mockStatic(org.bukkit.inventory.ItemStack.class, CALLS_REAL_METHODS)) {
            bytes.when(() -> org.bukkit.inventory.ItemStack.deserializeBytes(any(byte[].class)))
                    .thenReturn(CraftItemStack.asCraftMirror(new ItemStack(Items.STONE)));
            for (String type : List.of("smelting", "blasting", "smoking", "campfire", "stonecutting", "shapeless")) {
                String json = "{\"schemaVersion\":1,\"recipes\":[{\"id\":\"farmersdelight:test\",\"type\":\"" + type
                        + "\",\"inputs\":[{\"choices\":[\"AA==\"]}],\"outputs\":[\"AA==\"],\"experience\":0.5,\"cookingTimeTicks\":100}]}";
                var displays = RecipeViewerReiSnapshot.parse(json, "craftengine:content");
                assertEquals(1, displays.size(), type);
                String path = type.equals("shapeless") ? "crafting/shapeless" : type.equals("stonecutting") ? "stone_cutting" : type;
                assertEquals("minecraft:default/" + path, displays.getFirst().getSerializerId().toString());
                if (displays.getFirst() instanceof CookingDisplay cooking) {
                    assertEquals(0.5F, cooking.getXp()); assertEquals(100, cooking.getCookTime());
                    var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryHelper.registryAccess());
                    try {
                        Display.dispatchCodec().encode(wire, cooking);
                        assertEquals(cooking.getSerializerId(), wire.readIdentifier());
                        for (int ingredient = 0; ingredient < 2; ingredient++) {
                            assertEquals(1, wire.readVarInt()); // list of ingredients
                            assertEquals(1, wire.readVarInt()); // list of item entries
                            assertEquals("minecraft:item", wire.readIdentifier().toString());
                            assertFalse(ItemStack.OPTIONAL_STREAM_CODEC.decode(wire).isEmpty());
                        }
                        assertTrue(wire.readBoolean());
                        assertEquals("farmersdelight:test", wire.readIdentifier().toString());
                        assertEquals(0.5F, wire.readFloat());
                        assertEquals(100, wire.readDouble());
                        assertFalse(wire.isReadable());
                    } finally { wire.release(); }
                }
            }
        }
    }

    @Test
    void malformedSlotAndUnknownTypesAreIsolated() {
        assertTrue(RecipeViewerReiSnapshot.parse(SHAPED.replace("\"slot\":3", "\"slot\":4"), "craftengine:content").isEmpty());
        assertTrue(RecipeViewerReiSnapshot.parse(SHAPED.replace("\"shaped\"", "\"unknown\""), "craftengine:content").isEmpty());
        assertThrows(RuntimeException.class, () -> RecipeViewerReiSnapshot.parse("{broken", "craftengine:content"));
    }

    @Test
    void providerOverridesNativeDisplayWithoutDuplicatingIds() {
        var id = Identifier.parse("farmersdelight:test");
        var nativeDisplay = new org.leavesmc.leaves.protocol.rei.display.ShapelessDisplay(List.of(), List.of(), id);
        var provider = new org.leavesmc.leaves.protocol.rei.display.ShapelessDisplay(List.of(), List.of(), id);
        var secondProvider = new org.leavesmc.leaves.protocol.rei.display.ShapelessDisplay(List.of(), List.of(), id);
        var merged = RecipeViewerReiSnapshot.merge(List.of(nativeDisplay), List.of(provider, secondProvider));
        assertEquals(1, merged.size());
        assertSame(provider, merged.getFirst());
    }

    @Test
    void absentProvidersLeaveOriginalDisplaysAndTheirOrderUntouched() {
        List<Display> vanilla = List.of(new org.leavesmc.leaves.protocol.rei.display.ShapelessDisplay(
                List.of(), List.of(), Identifier.parse("minecraft:test")));
        assertSame(vanilla, RecipeViewerReiSnapshot.merge(vanilla, List.of()));
    }

    @Test
    void repeatedStackBytesAreDecodedOncePerSnapshot() {
        try (var bytes = mockStatic(org.bukkit.inventory.ItemStack.class, CALLS_REAL_METHODS)) {
            bytes.when(() -> org.bukkit.inventory.ItemStack.deserializeBytes(any(byte[].class)))
                    .thenReturn(CraftItemStack.asCraftMirror(new ItemStack(Items.STONE)));
            assertEquals(1, RecipeViewerReiSnapshot.parse(SHAPED, "craftengine:content").size());
            bytes.verify(() -> org.bukkit.inventory.ItemStack.deserializeBytes(any(byte[].class)), times(1));
        }
    }

    @Test
    void malformedProviderRefreshPreservesItsLastUsableSnapshot() {
        var owner = mock(org.bukkit.plugin.Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        var current = new java.util.concurrent.atomic.AtomicReference<>("{\"schemaVersion\":1,\"recipes\":[]}");
        var service = org.leavesmc.leaves.recipeviewer.RecipeViewerBridgeService.INSTANCE;
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(true);
            service.registerProvider(owner, "fixture:rei", current::get);
            try {
                service.refresh(owner, "fixture:rei");
                String valid = service.snapshots().get("fixture:rei");
                current.set("{bad");
                assertThrows(RuntimeException.class, () -> service.refresh(owner, "fixture:rei"));
                assertEquals(valid, service.snapshots().get("fixture:rei"));
            } finally { service.unregisterProvider(owner, "fixture:rei"); }
        }
    }

    private static ItemStack customStack() {
        ItemStack stack = new ItemStack(Items.PAPER, 3);
        CompoundTag data = new CompoundTag(); data.putString("ce_test", "farmersdelight:cabbage");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        stack.set(DataComponents.ITEM_NAME, Component.literal("Cabbage"));
        stack.set(DataComponents.ITEM_MODEL, Identifier.parse("farmersdelight:cabbage"));
        return stack;
    }

    private static void readStack(RegistryFriendlyByteBuf buffer, ItemStack expected) {
        assertEquals(1, buffer.readVarInt());
        assertEquals("minecraft:item", buffer.readIdentifier().toString());
        ItemStack actual = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
        assertEquals(expected.getCount(), actual.getCount());
        assertEquals(expected.get(DataComponents.CUSTOM_DATA), actual.get(DataComponents.CUSTOM_DATA));
        assertEquals(expected.get(DataComponents.ITEM_NAME), actual.get(DataComponents.ITEM_NAME));
        assertEquals(expected.get(DataComponents.ITEM_MODEL), actual.get(DataComponents.ITEM_MODEL));
    }
}
