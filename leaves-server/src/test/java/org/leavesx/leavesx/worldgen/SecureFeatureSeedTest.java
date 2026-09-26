package org.leavesx.leavesx.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@Normal
class SecureFeatureSeedTest {
    @AfterEach
    void reset() { SecureFeatureSeed.configureNewWorlds(false); }

    @Test
    void legacyWorldsStayVanillaWhenFeatureIsEnabled() {
        SecureFeatureSeed.configureNewWorlds(true);
        final var legacy = WorldOptions.CODEC.codec().parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"seed\":42}")).getOrThrow();
        assertTrue(legacy.leavesX$featureSeed().isEmpty());
        assertTrue(legacy.withStructures(false).leavesX$featureSeed().isEmpty());
        assertTrue(new WorldOptions(42, true, false).leavesX$featureSeed().isPresent());
    }

    @Test
    void secretSurvivesReloadAndSwitchOffWithoutChangingWorldGeneration() {
        SecureFeatureSeed.configureNewWorlds(true);
        final var original = new WorldOptions(42, true, false);
        final var saved = WorldOptions.CODEC.codec().encodeStart(JsonOps.INSTANCE, original).getOrThrow();
        SecureFeatureSeed.configureNewWorlds(false);
        final var restored = WorldOptions.CODEC.codec().parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(original.leavesX$featureSeed().orElseThrow().encode(), restored.leavesX$featureSeed().orElseThrow().encode());
        assertEquals(saved, WorldOptions.CODEC.codec().encodeStart(JsonOps.INSTANCE, restored).getOrThrow());
        saved.getAsJsonObject().addProperty("leavesx_secure_seed_v1", "invalid");
        assertTrue(WorldOptions.CODEC.codec().parse(JsonOps.INSTANCE, saved).error().isPresent());
    }

    @Test
    void independentWorldsRemainDeterministicOnConcurrentWorkers() {
        final var first = SecureFeatureSeed.create().forDimension("minecraft:overworld");
        final var second = SecureFeatureSeed.create().forDimension("minecraft:overworld");
        final long[] expected = IntStream.range(0, 128).mapToLong(i -> first.random("decoration", i, -i, 7).nextLong()).toArray();
        CompletableFuture.allOf(IntStream.range(0, 128).mapToObj(i -> CompletableFuture.runAsync(() -> {
            assertEquals(expected[i], first.random("decoration", i, -i, 7).nextLong());
            assertNotEquals(expected[i], second.random("decoration", i, -i, 7).nextLong());
            assertNotEquals(expected[i], first.random("slime", i, -i, 7).nextLong());
        })).toArray(CompletableFuture[]::new)).join();
    }

    @Test
    void streamReseedAndForkRetainIndependentState() {
        final var context = SecureFeatureSeed.create().forDimension("minecraft:overworld");
        final var random = context.random("probe", 3, -5, 0);
        random.setSeed(55);
        final long expected = random.nextLong();
        random.nextGaussian();
        random.setSeed(55);
        assertEquals(expected, random.nextLong());
        final var fork = random.fork();
        assertNotEquals(random.nextLong(), fork.nextLong());
        assertThrows(IllegalArgumentException.class, () -> random.nextInt(0));
    }
}
