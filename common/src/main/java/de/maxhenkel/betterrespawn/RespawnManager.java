package de.maxhenkel.betterrespawn;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

public class RespawnManager {

    private static final int FIND_SPAWN_ATTEMPTS = 16;

    private final Random random;

    /** Hardcore Respawn added variables **/
    private final Map<UUID, Long> lastDeathTimes = new HashMap<>();
    private static final long RESPAWN_COOLDOWN = 9 * 60 * 1000; // 9 minutes in milliseconds


    public RespawnManager() {
        random = new Random();
    }

    public void onPlayerDeath(ServerPlayer player) {
        if (!(player.getAbilities() instanceof RespawnAbilities respawnAbilities)) {
            return;
        }

        respawnAbilities.setRespawnDimension(player.getRespawnDimension());
        respawnAbilities.setRespawnPos(player.getRespawnPosition());
        respawnAbilities.setRespawnAngle(player.getRespawnAngle());
        respawnAbilities.setRespawnForced(player.isRespawnForced());


        ServerLevel respawnDimension = player.getServer().getLevel(player.getRespawnDimension());
        BlockPos respawnLocation = player.getRespawnPosition();

        if (respawnLocation != null) {
            DimensionTransition transition = player.findRespawnPositionAndUseSpawnBlock(true, DimensionTransition.DO_NOTHING);
            if (!transition.missingRespawnBlock()) {
                Vec3 spawn = transition.pos();
                if (respawnDimension == player.serverLevel() && player.blockPosition().distManhattan(new Vec3i((int) spawn.x, (int) spawn.y, (int) spawn.z)) <= BetterRespawnMod.SERVER_CONFIG.respawnBlockRange.get()) {
                    BetterRespawnMod.LOGGER.info("Player {} is within the range of its respawn block", player.getName().getString());
                    return;
                }
            }
        }

        if (player.serverLevel().dimensionType().hasCeiling() || !player.serverLevel().dimensionType().bedWorks()) {
            BetterRespawnMod.LOGGER.info("Can't respawn {} in {}", player.getName().getString(), player.serverLevel().dimension().location());
            return;
        }

        BlockPos respawnPos = findValidRespawnLocation(player.serverLevel(), player.blockPosition());

        if (respawnPos == null) {
            return;
        }

        player.setRespawnPosition(player.serverLevel().dimension(), respawnPos, 0F, true, true);
        BetterRespawnMod.LOGGER.info("Set temporary respawn location to [{}, {}, {}]", respawnPos.getX(), respawnPos.getY(), respawnPos.getZ());
    }

    public void onSetRespawnPosition(ServerPlayer player, ResourceKey<Level> dimension, @Nullable BlockPos pos, float angle, boolean forced, boolean showMessage) {
        if (forced) {
            return;
        }

        if (!(player.getAbilities() instanceof RespawnAbilities abilities)) {
            return;
        }

        if (pos == null) {
            dimension = Level.OVERWORLD;
        }

        abilities.setRespawnDimension(dimension);
        abilities.setRespawnPos(pos);
        abilities.setRespawnAngle(angle);
        abilities.setRespawnForced(forced);

        if (pos != null) {
            BetterRespawnMod.LOGGER.info("Updating the respawn location of player {} to [{}, {}, {}] in {}", player.getName().getString(), pos.getX(), pos.getY(), pos.getZ(), dimension.location());
        } else {
            BetterRespawnMod.LOGGER.info("Updating the respawn location of player {} to [NONE]", player.getName().getString());
        }

        if (pos != null && Objects.requireNonNull(player.getServer()).overworld().getBlockState(pos).getBlock() instanceof BedBlock) {
            // Player set respawn location to a bed, do not execute custom respawn logic
            return;
        }

        this.checkRecentRespawn(player);

    }


    /** Harcore respawn custom logic **/
    private void checkRecentRespawn(ServerPlayer player) {
        // Check if the player died within the last 15 minutes
        long lastDeathTime = lastDeathTimes.getOrDefault(player.getUUID(), 0L);
        long currentTime = System.currentTimeMillis();

        // only apply penalty if the player has died recently (aka lastDeathTime != 0)
        if (lastDeathTime != 0) {
            if (currentTime - lastDeathTime <= RESPAWN_COOLDOWN) {
                // Player died recently; Set health to half
                player.setHealth(player.getMaxHealth() / 2.0f);
            }
        }

        // Store the timestamp of the player's death
        lastDeathTimes.put(player.getUUID(), System.currentTimeMillis());
    }





    private boolean isAllowedBiome(ServerLevel world, BlockPos pos) {
        Biome biome = world.getBiome(pos).value();

        // Add the names of the biomes you want to exclude from respawn here
        List<String> excludedBiomes = Arrays.asList(

                Biomes.JUNGLE.toString(),
                Biomes.SPARSE_JUNGLE.toString(),
                Biomes.BAMBOO_JUNGLE.toString(),
                Biomes.SWAMP.toString(),
                Biomes.MANGROVE_SWAMP.toString(),
                Biomes.COLD_OCEAN.toString(),
                Biomes.DEEP_COLD_OCEAN.toString()

        );

        Registry<Biome> biomeRegistry = world.registryAccess().registryOrThrow(Registries.BIOME);

        /**
        // Check if the biome's registry name is in the excluded list
        ResourceLocation biomeRegistryName = world.registryAccess().lookupOrThrow(Registries.BIOME)..getKey(biome);
        if (biomeRegistryName != null && excludedBiomes.contains(biomeRegistryName.toString())) {
            BetterRespawnMod.LOGGER.info("Biome {} is excluded from respawn", biomeRegistryName);
            return false;
        }
         **/

        return true;
    }

    @Nullable
    public BlockPos findValidRespawnLocation(ServerLevel world, BlockPos deathLocation) {
        int min = BetterRespawnMod.SERVER_CONFIG.minRespawnDistance.get();
        int max = BetterRespawnMod.SERVER_CONFIG.maxRespawnDistance.get();

        BlockPos pos = null;
        for (int i = 0; i < FIND_SPAWN_ATTEMPTS && pos == null; i++) {
            BetterRespawnMod.LOGGER.info("Searching for respawn location - Attempt {}/{}", i + 1, FIND_SPAWN_ATTEMPTS);
            pos = PlayerRespawnLogic.getSpawnPosInChunk(world, new ChunkPos(new BlockPos(getRandomRange(deathLocation.getX(), min, max), 0, getRandomRange(deathLocation.getZ(), min, max))));
            if (pos != null && !world.getWorldBorder().isWithinBounds(pos)) {
                pos = null;
            }
        }
        if (pos == null) {
            BetterRespawnMod.LOGGER.info("Found no valid respawn location after {} attempts", FIND_SPAWN_ATTEMPTS);
        } else {
            BetterRespawnMod.LOGGER.info("Found valid respawn location: [{}, {}, {}]", pos.getX(), pos.getY(), pos.getZ());
        }
        return pos;
    }

    private int getRandomRange(int actual, int minDistance, int maxDistance) {
        return actual + (random.nextBoolean() ? -1 : 1) * (minDistance + random.nextInt(maxDistance - minDistance));
    }

}
