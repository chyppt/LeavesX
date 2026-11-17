package org.leavesmc.leaves.protocol.rei.display;

import net.minecraft.resources.Identifier;
import org.leavesmc.leaves.protocol.rei.ingredient.EntryIngredient;

import java.util.List;

/** A vanilla-category cooking display backed by a provider snapshot. */
public final class ProviderCookingDisplay extends CookingDisplay {
    private final Identifier serializerId;

    public ProviderCookingDisplay(List<EntryIngredient> inputs, List<EntryIngredient> outputs,
                                  Identifier location, Identifier serializerId, float experience, double cookTime) {
        super(inputs, outputs, location, experience, cookTime);
        this.serializerId = serializerId;
    }

    @Override
    public Identifier getSerializerId() {
        return serializerId;
    }
}
