package org.leavesmc.leaves.recipeviewer;

import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.leavesmc.leaves.api.recipeviewer.RecipeViewerBridge;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;

/** LeavesX 内部的可选配方快照服务；不读取 NMS 或第三方插件对象。 */
public final class RecipeViewerBridgeService implements RecipeViewerBridge {
    public static final RecipeViewerBridgeService INSTANCE = new RecipeViewerBridgeService();

    private final Map<String, Registration> registrations = new HashMap<>();
    private long generation;

    private RecipeViewerBridgeService() {
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public String capabilitiesJson() {
        return "{\"apiVersion\":1,\"snapshotSchema\":1,\"reiDisplay\":true,\"jeiVanilla\":true,"
            + "\"exactInput\":false,\"reiInputStacks\":true,\"jeiApproximateInputs\":true,\"clientCatalog\":false}";
    }

    @Override
    public void registerProvider(final Plugin owner, final String providerId, final Supplier<String> snapshotJson) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(snapshotJson, "snapshotJson");
        final String id = validId(providerId);
        onServerThread(() -> {
            requireEnabled(owner);
            final Registration current = registrations.get(id);
            if (current != null && current.owner() != owner) {
                throw new IllegalStateException("Recipe provider is already owned: " + id);
            }
            registrations.put(id, new Registration(owner, snapshotJson));
        });
    }

    @Override
    public void refresh(final Plugin owner, final String providerId) {
        Objects.requireNonNull(owner, "owner");
        final String id = validId(providerId);
        onServerThread(() -> {
            final Registration registration = registrations.get(id);
            if (registration == null || registration.owner() != owner) {
                throw new IllegalStateException("Recipe provider is not owned by the caller: " + id);
            }
            requireEnabled(owner);
            final String snapshot = registration.snapshot().get();
            if (snapshot == null || snapshot.length() > 8 * 1024 * 1024) {
                throw new IllegalArgumentException("Recipe snapshot is null or exceeds 8 MiB: " + id);
            }
            // Reject a malformed whole generation before replacing the last usable snapshot.
            final com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(snapshot).getAsJsonObject();
            if (!root.has("schemaVersion") || root.get("schemaVersion").getAsInt() != 1
                    || !root.has("recipes") || !root.get("recipes").isJsonArray()
                    || root.getAsJsonArray("recipes").size() > 10_000
                    || (root.has("source") && !id.equals(root.get("source").getAsString()))) {
                throw new IllegalArgumentException("Invalid recipe snapshot schema/source: " + id);
            }
            registration.snapshotValue = snapshot;
            generation++;
            org.leavesmc.leaves.protocol.rei.REIServerProtocol.onRecipeViewerProviderRefresh();
            org.leavesmc.leaves.protocol.jei.JEIRecipeSyncProtocol.onRecipeViewerProviderRefresh();
        });
    }

    @Override
    public void unregisterProvider(final Plugin owner, final String providerId) {
        Objects.requireNonNull(owner, "owner");
        final String id = validId(providerId);
        onServerThread(() -> {
            final Registration registration = registrations.get(id);
            if (registration != null && registration.owner() == owner) {
                registrations.remove(id);
                generation++;
                org.leavesmc.leaves.protocol.rei.REIServerProtocol.onRecipeViewerProviderRefresh();
                org.leavesmc.leaves.protocol.jei.JEIRecipeSyncProtocol.onRecipeViewerProviderRefresh();
            }
        });
    }

    public void clear() {
        registrations.clear();
        generation++;
        org.leavesmc.leaves.protocol.rei.REIServerProtocol.onRecipeViewerProviderRefresh();
        org.leavesmc.leaves.protocol.jei.JEIRecipeSyncProtocol.onRecipeViewerProviderRefresh();
    }

    /** 返回已通过大小校验的不可变快照；协议层在自己的线程安全边界内消费。 */
    public synchronized Map<String, String> snapshots() {
        final Map<String, String> result = new HashMap<>();
        registrations.forEach((id, registration) -> {
            if (registration.snapshotValue != null) result.put(id, registration.snapshotValue);
        });
        return Collections.unmodifiableMap(result);
    }

    public synchronized long generation() {
        return generation;
    }

    private static void onServerThread(final Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        Bukkit.getScheduler().runTask(MinecraftInternalPlugin.INSTANCE, action);
    }

    private static void requireEnabled(final Plugin owner) {
        if (!owner.isEnabled()) {
            throw new IllegalStateException("Recipe provider owner is disabled: " + owner.getName());
        }
    }

    private static String validId(final String providerId) {
        Objects.requireNonNull(providerId, "providerId");
        if (!providerId.matches("[a-z0-9_.-]+:[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid recipe provider id: " + providerId);
        }
        return providerId;
    }

    private static final class Registration {
        private final Plugin owner;
        private final Supplier<String> snapshot;
        private String snapshotValue;

        private Registration(final Plugin owner, final Supplier<String> snapshot) {
            this.owner = owner;
            this.snapshot = snapshot;
        }

        private Plugin owner() { return owner; }
        private Supplier<String> snapshot() { return snapshot; }
    }
}
