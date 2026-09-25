package org.icarus.angelicae.garden;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.icarus.angelicae.garden.plant.GardenPlant;
import org.icarus.angelicae.garden.structure.PlantStructure;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps track of every plant that is currently live in the world.
 * <p>
 * A plant is held under each block it occupies, so a click on any part of it finds it. It is also held under
 * the chunk its origin is in, which is what a chunk unload goes by: plants are loaded per chunk, so they have
 * to be released per chunk as well, whether they came from the SQL storage or from the chunk's own data.
 */
public class PlantRegistry {

    private final Map<UUID, Map<Long, GardenPlant>> gardenPlants = new ConcurrentHashMap<>();
    private final Map<UUID, GardenPlant> gardenPlantIds = new ConcurrentHashMap<>();
    /** Origin chunk of every registered plant, keyed by world and chunk. */
    private final Map<UUID, Map<Long, Set<GardenPlant>>> plantsByChunk = new ConcurrentHashMap<>();


    @Nullable
    public GardenPlant getByID(UUID id) {
        return gardenPlantIds.get(id);
    }

    @Nullable
    public GardenPlant getByLocation(Block block) {
        Map<Long, GardenPlant> positions = gardenPlants.get(block.getWorld().getUID());
        if (positions == null) {
            return null;
        }
        return positions.get(positionKey(block.getX(), block.getY(), block.getZ()));
    }

    public void registerPlant(GardenPlant plant) {
        PlantStructure structure = plant.getStructure();
        UUID worldUuid = structure.worldUuid();
        Map<Long, GardenPlant> positions =
            gardenPlants.computeIfAbsent(worldUuid, ignored -> new ConcurrentHashMap<>());
        structure.forEachBlock((x, y, z, state) -> {
            positions.put(positionKey(x, y, z), plant);
            return true;
        });
        gardenPlantIds.put(plant.getId(), plant);
        plantsByChunk.computeIfAbsent(worldUuid, ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(chunkKey(structure), ignored -> ConcurrentHashMap.newKeySet())
            .add(plant);
    }

    public void unregisterPlant(GardenPlant plant) {
        forget(plant);
    }

    public void unregister(UUID id) {
        GardenPlant plant = gardenPlantIds.get(id);
        if (plant != null) {
            forget(plant);
        }
    }

    /**
     * Releases every plant whose origin is in the given chunk.
     */
    public void unregisterChunk(World world, int chunkX, int chunkZ) {
        Map<Long, Set<GardenPlant>> perWorld = plantsByChunk.get(world.getUID());
        if (perWorld == null) {
            return;
        }
        Set<GardenPlant> plants = perWorld.get(chunkKey(chunkX, chunkZ));
        if (plants == null) {
            return;
        }
        // Copied because forget() removes from this very set
        List.copyOf(plants).forEach(this::forget);
    }

    public void unregisterWorld(@NonNull World world) {
        gardenPlants.remove(world.getUID());
        plantsByChunk.remove(world.getUID());
        for (GardenPlant gardenPlant : List.copyOf(gardenPlantIds.values())) {
            if (gardenPlant.getStructure().origin().getWorld() == world) {
                gardenPlantIds.remove(gardenPlant.getId());
            }
        }
    }

    public Collection<GardenPlant> getPlants() {
        return gardenPlantIds.values();
    }

    public void clear() {
        gardenPlantIds.values().forEach(GardenPlant::clearBoundEntities);
        gardenPlants.clear();
        gardenPlantIds.clear();
        plantsByChunk.clear();
    }

    private void forget(GardenPlant plant) {
        gardenPlantIds.remove(plant.getId());
        PlantStructure structure = plant.getStructure();
        UUID worldUuid = structure.worldUuid();

        Map<Long, GardenPlant> positions = gardenPlants.get(worldUuid);
        if (positions != null) {
            structure.forEachBlock((x, y, z, state) -> {
                positions.remove(positionKey(x, y, z));
                return true;
            });
        }

        Map<Long, Set<GardenPlant>> perWorld = plantsByChunk.get(worldUuid);
        if (perWorld != null) {
            long key = chunkKey(structure);
            Set<GardenPlant> plants = perWorld.get(key);
            if (plants != null) {
                plants.remove(plant);
                if (plants.isEmpty()) {
                    perWorld.remove(key);
                }
            }
        }
    }

    /**
     * Packs a block position into one value, so that indexing a plant's blocks does not allocate three
     * objects per block the way a {@code BlockVector} did.
     * <p>
     * X and Z need 26 bits each to reach the world border (±30,000,000) and Y needs 9 to cover the build
     * range, which comes to 61 of the 64 bits. The fields cannot overlap, so different positions cannot share
     * a key.
     */
    private static long positionKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 35) | ((long) (z & 0x3FFFFFF) << 9) | (y & 0x1FF);
    }

    /**
     * Packs a chunk position into one value. Chunk coordinates are signed, so the low half is masked before
     * being merged in, otherwise two different chunks could share a key.
     */
    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static long chunkKey(PlantStructure structure) {
        return chunkKey(structure.originX() >> 4, structure.originZ() >> 4);
    }
}
