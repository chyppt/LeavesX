package org.leavesmc.leaves.protocol.rei.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.leavesmc.leaves.protocol.rei.display.Display;
import org.leavesmc.leaves.protocol.rei.display.ProviderCookingDisplay;
import org.leavesmc.leaves.protocol.rei.display.ShapedDisplay;
import org.leavesmc.leaves.protocol.rei.display.ShapelessDisplay;
import org.leavesmc.leaves.protocol.rei.display.StoneCuttingDisplay;
import org.leavesmc.leaves.protocol.rei.display.SmithingDisplay;
import org.leavesmc.leaves.protocol.rei.ingredient.EntryIngredient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;

/** Converts the versioned plugin snapshot into detached REI displays. */
public final class RecipeViewerReiSnapshot {
    private static final int MAX_RECIPES = 10_000;
    private static final int MAX_CHOICES = 256;
    private static final Set<String> COOKING = Set.of("smelting", "blasting", "smoking", "campfire");

    private RecipeViewerReiSnapshot() {}

    public static List<Display> parse(String json, String providerId) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("schemaVersion") || root.get("schemaVersion").getAsInt() != 1) {
            throw new IllegalArgumentException("unsupported schema");
        }
        JsonArray recipes = root.getAsJsonArray("recipes");
        if (recipes == null || recipes.size() > MAX_RECIPES) throw new IllegalArgumentException("too many recipes");

        List<Display> result = new ArrayList<>();
        Map<String, ItemStack> decodedStacks = new HashMap<>();
        Set<String> ids = new HashSet<>();
        List<String> failures = new ArrayList<>();
        for (JsonElement element : recipes) {
            try {
                JsonObject recipe = element.getAsJsonObject();
                String rawId = recipe.get("id").getAsString();
                Identifier id = Identifier.parse(rawId);
                if (!ids.add(rawId) || "minecraft".equals(id.getNamespace())) continue;
                String type = recipe.get("type").getAsString();
                if (!type.equals("shaped") && !type.equals("shapeless")
                        && !type.equals("stonecutting") && !type.equals("smithing") && !COOKING.contains(type)) continue;
                List<EntryIngredient> inputs = inputs(recipe, type, decodedStacks);
                List<EntryIngredient> outputs = outputs(recipe, decodedStacks);
                if (inputs.isEmpty() || outputs.isEmpty()) continue;
                if (type.equals("shaped")) {
                    int width = bounded(recipe, "width", 1, 3);
                    int height = bounded(recipe, "height", 1, 3);
                    if (inputs.size() != width * height) continue;
                    result.add(new ShapedDisplay(inputs, outputs, id, width, height));
                } else if (type.equals("shapeless")) {
                    if (inputs.size() > 9) continue;
                    result.add(new ShapelessDisplay(inputs, outputs, id));
                } else if (type.equals("smithing")) {
                    if (inputs.size() != 3 || inputs.get(1).isEmpty()) continue;
                    result.add(new SmithingDisplay(inputs, outputs, SmithingDisplay.SmithingRecipeType.TRANSFORM, id));
                } else if (type.equals("stonecutting")) {
                    if (inputs.size() != 1) continue;
                    result.add(new StoneCuttingDisplay(inputs, outputs, id));
                } else if (COOKING.contains(type)) {
                    if (inputs.size() != 1) continue;
                    Identifier serializer = Identifier.fromNamespaceAndPath("minecraft", "default/" + type);
                    float xp = recipe.has("experience") ? recipe.get("experience").getAsFloat() : 0;
                    double time = recipe.has("cookingTimeTicks") ? recipe.get("cookingTimeTicks").getAsDouble() : 200;
                    if (!Float.isFinite(xp) || !Double.isFinite(time) || time < 0) continue;
                    result.add(new ProviderCookingDisplay(inputs, outputs, id, serializer, xp, time));
                }
            } catch (RuntimeException failure) {
                // A malformed optional entry cannot remove vanilla recipes; retain a bounded diagnostic sample.
                if (failures.size() < 3) failures.add(failure.toString());
            }
        }
        if (result.size() != recipes.size()) {
            org.leavesmc.leaves.LeavesLogger.LOGGER.warn(
                    "REI provider {}: accepted {}/{} recipes; unsupported/invalid entries were skipped. Samples: {}",
                    providerId, result.size(), recipes.size(), failures);
        }
        return List.copyOf(result);
    }

    private static List<EntryIngredient> inputs(JsonObject recipe, String type, Map<String, ItemStack> decodedStacks) {
        JsonArray values = recipe.getAsJsonArray("inputs");
        if (values == null || values.isEmpty() || values.size() > 9) return List.of();
        int expected = type.equals("shaped") ? bounded(recipe, "width", 1, 3) * bounded(recipe, "height", 1, 3) : type.equals("smithing") ? 3 : -1;
        List<EntryIngredient> result = new ArrayList<>();
        boolean indexed = expected > 0;
        if (type.equals("smithing") && values.size() != 3) return List.of();
        boolean[] occupied = indexed ? new boolean[expected] : null;
        EntryIngredient[] slots = indexed ? new EntryIngredient[expected] : null;
        for (int index = 0; index < values.size(); index++) {
            JsonObject slot = values.get(index).getAsJsonObject();
            int slotIndex = indexed ? bounded(slot, "slot", 0, expected - 1) : index;
            if (indexed && occupied[slotIndex]) return List.of();
            if (indexed) occupied[slotIndex] = true;
            if (slot.has("empty") && slot.get("empty").getAsBoolean()) {
                if (!type.equals("smithing") || slotIndex == 1 || slot.has("choices")) return List.of();
                continue;
            }
            if (!slot.has("choices")) return List.of();
            JsonArray choices = slot.getAsJsonArray("choices");
            if (choices.size() == 0 || choices.size() > MAX_CHOICES) return List.of();
            ItemStack[] stacks = new ItemStack[choices.size()];
            for (int i = 0; i < choices.size(); i++) stacks[i] = decodedStacks.computeIfAbsent(choices.get(i).getAsString(), RecipeViewerReiSnapshot::decode);
            EntryIngredient ingredient = EntryIngredient.of(stacks);
            if (indexed) slots[slotIndex] = ingredient;
            else result.add(ingredient);
        }
        if (indexed) {
            for (EntryIngredient slot : slots) result.add(slot == null ? EntryIngredient.empty() : slot);
        }
        if (expected >= 0 && values.size() > expected) return List.of();
        return result;
    }

    private static List<EntryIngredient> outputs(JsonObject recipe, Map<String, ItemStack> decodedStacks) {
        JsonArray values = recipe.getAsJsonArray("outputs");
        if (values == null || values.size() != 1) return List.of();
        List<EntryIngredient> result = new ArrayList<>();
        for (JsonElement value : values) result.add(EntryIngredient.of(decodedStacks.computeIfAbsent(value.getAsString(), RecipeViewerReiSnapshot::decode)));
        return result;
    }

    /** Overlay by recipe id: full provider stacks replace the lossy native view, not append a duplicate. */
    public static List<Display> merge(List<Display> vanilla, List<Display> providers) {
        if (providers.isEmpty()) return vanilla;
        Map<Identifier, Display> replacements = new LinkedHashMap<>();
        for (Display display : providers) replacements.putIfAbsent(display.getDisplayLocation(), display);
        List<Display> result = new ArrayList<>(vanilla.size() + replacements.size());
        for (Display display : vanilla) {
            if (!replacements.containsKey(display.getDisplayLocation())) result.add(display);
        }
        result.addAll(replacements.values());
        return List.copyOf(result);
    }

    private static ItemStack decode(String encoded) {
        if (encoded.length() > 100_000) throw new IllegalArgumentException("stack too large");
        org.bukkit.inventory.ItemStack bukkit = org.bukkit.inventory.ItemStack.deserializeBytes(
            Base64.getDecoder().decode(encoded));
        ItemStack stack = CraftItemStack.asNMSCopy(bukkit);
        if (stack.isEmpty()) throw new IllegalArgumentException("empty stack");
        return stack;
    }

    private static int bounded(JsonObject object, String key, int min, int max) {
        int value = object.get(key).getAsInt();
        if (value < min || value > max) throw new IllegalArgumentException("out of range");
        return value;
    }
}
