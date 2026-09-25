package org.icarus.angelicae.garden.listener;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.icarus.angelicae.garden.Garden;
import org.icarus.angelicae.garden.PlantRegistry;
import org.icarus.angelicae.garden.configuration.GardenConfig;
import org.icarus.angelicae.garden.persist.GardenPlantDataType;
import org.icarus.angelicae.garden.persist.PlantPdcType;
import org.icarus.angelicae.garden.persist.StorageSolution;
import org.icarus.angelicae.garden.plant.GardenPlant;

import java.util.Arrays;
import java.util.List;

/**
 * Loads plants as the chunks they stand in come into view, and releases them again when those chunks unload.
 * <p>
 * Both storage options are read the same way. The dynamic storage keeps a plant in the chunk data of its
 * origin chunk, and the SQL storage is queried for that same chunk, so either way a chunk load brings in
 * exactly the plants nearby and nothing else.
 */
public record WorldEventListener(PlantRegistry registry, GardenPlantDataType gardenPlantDataType) implements Listener {


    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        loadWorld(event.getWorld());
    }

    public void loadWorld(World world) {
        // Anything already loaded before this listener was registered; the rest arrives through onChunkLoad
        Arrays.stream(world.getLoadedChunks())
            .forEach(this::loadChunk);
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        registry.unregisterWorld(event.getWorld());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        loadChunk(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        registry.unregisterChunk(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    private void loadChunk(Chunk chunk) {
        readChunkData(chunk).forEach(registry::registerPlant);
        if (GardenConfig.instance().storageSolution() == StorageSolution.SQLITE) {
            readStoredPlants(chunk);
        }
    }

    private List<GardenPlant> readChunkData(Chunk chunk) {
        try {
            List<GardenPlant> plants = chunk
                .getPersistentDataContainer()
                .get(PlantPdcType.PLANT_KEY, PlantPdcType.PLANT_LIST_TYPE);
            return plants == null ? List.of() : plants;
        } catch (IllegalArgumentException e) {
            // The stored data does not match what this version expects, so there is nothing usable in it
            return List.of();
        }
    }

    /**
     * Registers the stored plants of a chunk once the query comes back.
     * <p>
     * The chunk may well have unloaded again in the meantime, so whether it is loaded is checked on the
     * region thread that owns it rather than on the thread the query finished on.
     */
    private void readStoredPlants(Chunk chunk) {
        gardenPlantDataType.fetch(chunk).thenAcceptAsync(plants -> {
            for (GardenPlant plant : plants) {
                Location origin = plant.origin();
                Bukkit.getRegionScheduler().run(Garden.getInstance(), origin, task -> {
                    if (origin.isChunkLoaded()) {
                        registry.registerPlant(plant);
                    }
                });
            }
        });
    }
}
