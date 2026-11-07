package org.icarus.angelicae.garden.persist;

import dev.thorinwasher.schem.Schematic;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.icarus.angelicae.garden.Garden;
import org.icarus.angelicae.garden.MutableGardenRegistry;
import org.icarus.angelicae.garden.listener.WorldEventListener;
import org.icarus.angelicae.garden.plant.FruitPlacement;
import org.icarus.angelicae.garden.plant.GardenPlant;
import org.icarus.angelicae.garden.plant.PlantType;
import org.icarus.angelicae.garden.plant.item.FruitPlacementData;
import org.icarus.angelicae.garden.plant.item.PlantItem;
import org.icarus.angelicae.garden.structure.PlantStructure;
import org.icarus.angelicae.garden.structure.StructureGeometry;
import org.joml.Matrix3d;
import org.joml.Vector3i;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.MockBukkitExtension;
import org.mockbukkit.mockbukkit.MockBukkitInject;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the chunk storage format of a plant, which is kept as bytes so that rewriting a chunk does not
 * rebuild the plants it holds: rebuilding a plant places its fruit into the world again.
 */
@NullMarked
@ExtendWith(MockBukkitExtension.class)
class PlantPdcTypeTest {

    @MockBukkitInject
    ServerMock serverMock;

    /** One structure, shared so that a plant and its rebuild end up with the same geometry. */
    private final Schematic schematic = oneStoneBlock();

    @Test
    void aPlantRoundTripsThroughTheBytesTheChunkKeeps() {
        loadGarden();
        WorldMock world = serverMock.addSimpleWorld("world");
        NamespacedKey key = Garden.key("test_plant");
        PlantType plantType = plantType(key, schematic);
        MutableGardenRegistry.PLANT_TYPE.newBacking(List.of(plantType));

        GardenPlant plant = new GardenPlant(UUID.randomUUID(), plantType, structureAt(schematic, world, 0, 64, 0),
            "default", 0, 0, StorageSolution.PDC);
        PersistentDataContainer pdc = world.getChunkAt(0, 0).getPersistentDataContainer();

        byte[] stored = PlantPdcType.serialize(plant, pdc.getAdapterContext());
        pdc.set(PlantPdcType.PLANT_KEY, PlantPdcType.PLANT_LIST_TYPE, List.of(stored));
        List<byte[]> readBack = pdc.get(PlantPdcType.PLANT_KEY, PlantPdcType.PLANT_LIST_TYPE);

        assertNotNull(readBack, "the bytes just written should read back");
        assertEquals(1, readBack.size());
        assertEquals(plant.getId(), PlantPdcType.plantId(readBack.get(0)),
            "the id has to be readable straight from the stored bytes, without rebuilding the plant");

        GardenPlant rebuilt = PlantPdcType.deserialize(readBack.get(0), pdc.getAdapterContext());
        assertEquals(plant.getId(), rebuilt.getId());
        assertEquals(plant.getAge(), rebuilt.getAge());
        assertEquals(plant.getTrack(), rebuilt.getTrack());
        assertEquals(plant.getType(), rebuilt.getType());
        assertEquals(plant.getStructure(), rebuilt.getStructure());
    }

    /**
     * A plant whose stored data this version can not read, such as one whose type was removed, must not take
     * the rest of the chunk's plants with it when the chunk loads.
     */
    @Test
    void aStoredPlantThatCanNotBeReadDoesNotHideTheOthers() {
        loadGarden();
        WorldMock world = serverMock.addSimpleWorld("world");
        NamespacedKey key = Garden.key("test_plant");
        PlantType plantType = plantType(key, schematic);
        MutableGardenRegistry.PLANT_TYPE.newBacking(List.of(plantType));

        GardenPlant plant = new GardenPlant(UUID.randomUUID(), plantType, structureAt(schematic, world, 0, 64, 0),
            "default", 0, 0, StorageSolution.PDC);
        PersistentDataContainer pdc = world.getChunkAt(0, 0).getPersistentDataContainer();
        byte[] stored = PlantPdcType.serialize(plant, pdc.getAdapterContext());
        pdc.set(PlantPdcType.PLANT_KEY, PlantPdcType.PLANT_LIST_TYPE,
            List.of(Arrays.copyOf(stored, 3), stored));

        new WorldEventListener(Garden.getGardenRegistry(), Garden.getInstance().getGardenPlantDataType())
            .loadWorld(world);

        assertNotNull(Garden.getGardenRegistry().getByID(plant.getId()),
            "the readable plant should still be loaded next to the unreadable one");
    }

    /**
     * Rewriting the chunk for one plant has to keep the entries this version can not read, and must not trip
     * over them: the rewrite identifies entries by their bytes rather than by rebuilding them.
     */
    @Test
    void rewritingTheChunkKeepsAnEntryThisVersionCanNotRead() {
        loadGarden();
        WorldMock world = serverMock.addSimpleWorld("world");
        NamespacedKey key = Garden.key("test_plant");
        PlantType plantType = plantType(key, schematic);
        MutableGardenRegistry.PLANT_TYPE.newBacking(List.of(plantType));

        GardenPlant plant = new GardenPlant(UUID.randomUUID(), plantType, structureAt(schematic, world, 0, 64, 0),
            "default", 0, 0, StorageSolution.PDC);
        PersistentDataContainer pdc = world.getChunkAt(0, 0).getPersistentDataContainer();
        byte[] unreadable = Arrays.copyOf(PlantPdcType.serialize(plant, pdc.getAdapterContext()), 3);
        assertNull(PlantPdcType.plantId(unreadable),
            "bytes this version did not write should have no id rather than a made up one");
        pdc.set(PlantPdcType.PLANT_KEY, PlantPdcType.PLANT_LIST_TYPE,
            List.of(unreadable, PlantPdcType.serialize(plant, pdc.getAdapterContext())));

        assertDoesNotThrow(plant::registerFruitPicked,
            "an entry this version can not read must not break the rewrite of the chunk");

        List<byte[]> stored = pdc.get(PlantPdcType.PLANT_KEY, PlantPdcType.PLANT_LIST_TYPE);
        assertNotNull(stored);
        assertEquals(2, stored.size());
        assertTrue(stored.stream().anyMatch(entry -> Arrays.equals(entry, unreadable)),
            "the entry this version can not read should be left as it was");
        assertTrue(stored.stream().anyMatch(entry -> plant.getId().equals(PlantPdcType.plantId(entry))),
            "the plant should still have exactly one entry");
    }

    /**
     * The item sides of a plant type need the plugin, because their persistent data keys are made from it.
     */
    private void loadGarden() {
        try {
            MockBukkit.load(Garden.class);
        } catch (Throwable e) {
            // MockBukkit can not serve every part of the plugin, but the plugin itself is up by now
            assertNotNull(Garden.getInstance(), "the plugin should still be there, but was not: " + e);
        }
    }

    private static PlantType plantType(NamespacedKey key, Schematic schematic) {
        PlantItem item = new TestItem(new FruitPlacementData.MaterialPlacement("apple", 2, ignored -> {
        }, 1F));
        return new PlantType(key, 100, Map.of("default", List.of(schematic)), 1, FruitPlacement.UNDER,
            item, item, true);
    }

    /**
     * A structure of one stone block, enough for a plant to be built and rebuilt from its saved data.
     */
    private static Schematic oneStoneBlock() {
        List<BlockData> palette = List.of(Material.AIR.createBlockData(), Material.STONE.createBlockData());
        ByteArrayOutputStream blocks = new ByteArrayOutputStream();
        blocks.write(1);
        return new Schematic(new Vector3i(1, 1, 1), new Vector3i(), palette.toArray(BlockData[]::new),
            blocks.toByteArray());
    }

    private static PlantStructure structureAt(Schematic schematic, World world, int x, int y, int z) {
        StructureGeometry geometry = StructureGeometry.of(schematic, new Matrix3d(), new Vector3i());
        return new PlantStructure(geometry, x, y, z, new Matrix3d(), world.getUID());
    }

    /** A seed or fruit item that only has to answer where its fruit goes, which the persistence never asks. */
    private record TestItem(FruitPlacementData placement) implements PlantItem {

        @Override
        public void validate(String context) {
        }

        @Override
        public Optional<ItemStack> item(int amount, PlantItemType type, PlantType plantType) {
            return Optional.empty();
        }

        @Override
        public Component displayName() {
            return Component.empty();
        }

        @Override
        public Optional<Color> color() {
            return Optional.empty();
        }

        @Override
        public FruitPlacementData fruitPlacement() {
            return placement;
        }
    }
}
