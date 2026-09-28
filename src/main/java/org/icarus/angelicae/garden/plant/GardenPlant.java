package org.icarus.angelicae.garden.plant;

import com.google.common.collect.ImmutableSet;
import org.icarus.angelicae.garden.Garden;
import org.icarus.angelicae.garden.PlantRegistry;
import org.icarus.angelicae.garden.configuration.GardenConfig;
import org.icarus.angelicae.garden.persist.GardenPlantDataType;
import org.icarus.angelicae.garden.persist.PlantPdcType;
import org.icarus.angelicae.garden.persist.StorageSolution;
import org.icarus.angelicae.garden.plant.item.FruitPlacementData;
import org.icarus.angelicae.garden.plant.item.PlantItem;
import org.icarus.angelicae.garden.plant.item.PlayerHeadBased;
import org.icarus.angelicae.garden.structure.PlantStructure;
import lombok.Getter;
import lombok.ToString;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockType;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

@Getter
@ToString
public class GardenPlant {

    private final UUID id;
    private final PlantType type;
    private PlantStructure structure;
    private final String track;
    private int age;
    private boolean bloomed = false;
    private final List<PlacedFruit> placedFruits = new ArrayList<>();
    private int expectedFruits;
    private StorageSolution currentStorage = null;

    private static final Set<Material> DECORATIVE_PLANT_BLOCKS = compileDecorativePlantBlocks();

    /** The structures care about their leaves, and asking by state avoids rebuilding a location list for it. */
    private static final Predicate<BlockData> LEAVES = blockData -> Tag.LEAVES.isTagged(blockData.getMaterial());

    private static Set<Material> compileDecorativePlantBlocks() {
        ImmutableSet.Builder<Material> builder = new ImmutableSet.Builder<>();
        builder.addAll(Tag.BUTTONS.getValues());
        builder.addAll(Tag.SLABS.getValues());
        builder.addAll(Tag.TRAPDOORS.getValues());
        builder.add(Material.MOSS_CARPET, Material.PINK_PETALS);
        return builder.build();
    }

    public GardenPlant(PlantType type, Location location) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.age = 0;
        List<String> tracks = type.structures().keySet().stream().toList();
        this.track = tracks.get(ThreadLocalRandom.current().nextInt(tracks.size()));
        // Every track that made it into the plant type holds at least one stage, so stage zero exists
        this.structure = type.newStructure(location, age, track)
                .orElseThrow(() -> new IllegalStateException("Plant type %s has no structure for track '%s'"
                        .formatted(type.key(), track)));
        this.expectedFruits = 0;
    }

    public GardenPlant(UUID id, PlantType type, PlantStructure structure, String track, int age, int fruits, StorageSolution currentStorage) {
        this.id = id;
        this.type = type;
        this.age = age;
        this.track = track;
        this.structure = structure;
        this.expectedFruits = fruits;
        this.currentStorage = currentStorage;
        // A fruit that is a block sits in the chunk and is still standing after a reload, so the plant looks
        // for the ones it left behind rather than assuming it lost them. Without that it would grow a second
        // crop on top of the first, and every fruit standing there could be picked again.
        if (fruits > 0 || FruitPlacementData.placesBlocks(type.fruitItem())) {
            Bukkit.getGlobalRegionScheduler().run(Garden.getInstance(), t -> {
                placedFruits.addAll(findStandingFruits());
                expectedFruits = Math.max(expectedFruits, placedFruits.size());
                if (expectedFruits > placedFruits.size()) {
                    placedFruits.addAll(placeFruits(expectedFruits - placedFruits.size()));
                }
            });
        }
    }

    public boolean isFullyGrown() {
        List<?> track = type.structures().get(this.track);
        return track == null || track.isEmpty() || this.age >= track.size() - 1;
    }

    /**
     * Moves the plant on by the given amount of stages.
     *
     * @return {@code false} when the track has no structure that far along, leaving the plant as it was
     */
    public boolean incrementGrowthStage(int amount, PlantRegistry registry, GardenPlantDataType dataType) {
        return setGrowthStage(age + amount, registry, dataType);
    }

    public void tick() {
        if (!structure.origin().isChunkLoaded()) {
            return;
        }
        int validCount = (int) placedFruits.stream()
                .filter(placedFruit -> !placedFruit.hasDepopulated())
                .count();
        List<PlacedFruit> toRemove = placedFruits
                .stream()
                .filter(PlacedFruit::hasDepopulated)
                .toList();
        int toPopulate = expectedFruits - validCount;
        placedFruits.removeIf(PlacedFruit::hasDepopulated);
        boolean fallFruit = GardenConfig.instance().fallFruit() && ThreadLocalRandom.current().nextInt(20) == 0;
        if (toPopulate > 0 || fallFruit) {
            Bukkit.getRegionScheduler().run(Garden.getInstance(), structure.origin(), t -> {
                if (!structure.origin().isChunkLoaded()) {
                    return;
                }
                if (toPopulate > 0) {
                    placedFruits.addAll(placeFruits(toPopulate));
                }
                if (!placedFruits.isEmpty() && fallFruit) {
                    PlacedFruit toDrop = placedFruits.remove(ThreadLocalRandom.current().nextInt(placedFruits.size()));
                    toDrop.remove();
                    expectedFruits--;
                    type.fruitItem().item(PlantItem.PlantItemType.FRUIT, type)
                            .ifPresent(item -> toDrop.location().getWorld().dropItem(toDrop.location().toCenterLocation(), item));
                    updatePersistently(Garden.getInstance().getGardenPlantDataType());
                }
                toRemove.forEach(PlacedFruit::remove);
            });
        }
    }

    /**
     * Grows the plant into another stage, swapping the old structure for the new one.
     *
     * @return {@code false} when the track has no structure that far along, leaving the plant as it was
     */
    public boolean setGrowthStage(int growthStage, PlantRegistry registry, GardenPlantDataType dataType) {
        if (!structure.origin().isChunkLoaded()) {
            return false;
        }
        Optional<PlantStructure> next = type.newStructure(this.structure.origin(), growthStage, track);
        if (next.isEmpty()) {
            // Checked before anything is taken down, so an unreachable stage leaves the plant standing
            return false;
        }
        PlantStructure newStructure = next.get();
        this.structure.remove().thenAccept(empty -> {
            if (!newStructure.fits(blockData -> !DECORATIVE_PLANT_BLOCKS.contains(blockData.getMaterial()))) {
                this.structure.paste();
                return;
            }
            this.age = growthStage;
            registry.unregisterPlant(this);
            newStructure.paste();
            this.structure = newStructure;
            registry.registerPlant(this);
            updatePersistently(dataType);
        });
        return true;
    }

    private void updatePersistently(GardenPlantDataType dataType) {
        if (GardenConfig.instance().storageSolution() == StorageSolution.SQLITE && currentStorage != StorageSolution.PDC) {
            dataType.update(this);
        } else {
            if (currentStorage == StorageSolution.SQLITE) {
                dataType.remove(this);
                currentStorage = StorageSolution.PDC;
            }
            Chunk chunk = structure.origin().getChunk();
            List<GardenPlant> plants;
            try {
                plants = chunk
                        .getPersistentDataContainer()
                        .get(PlantPdcType.PLANT_KEY, PlantPdcType.PLANT_LIST_TYPE);
            } catch (IllegalArgumentException e) {
                plants = null;
            }
            List<GardenPlant> gardenPlants;
            if (plants == null) {
                gardenPlants = new ArrayList<>();
            } else {
                gardenPlants = new ArrayList<>(plants);
            }
            gardenPlants.removeIf(plant -> plant.id.equals(id));
            gardenPlants.add(this);
            chunk.getPersistentDataContainer().set(PlantPdcType.PLANT_KEY, PlantPdcType.PLANT_LIST_TYPE, gardenPlants);
        }
    }

    public void bloom() {
        if (!structure.origin().isChunkLoaded() || !placedFruits.isEmpty()) {
            return;
        }
        World world = structure.world();
        if (world == null) {
            return;
        }
        List<BlockFace> placement = type.fruitPlacement().vectors();
        // One walk answers both questions: whether a fruit is in the way, and which leaves can be bloomed
        List<Block> leaves = new ArrayList<>();
        boolean[] occupied = {false};
        structure.forEachBlock(LEAVES, (x, y, z, state) -> {
            Block block = world.getBlockAt(x, y, z);
            for (BlockFace relative : placement) {
                if (PlayerHeadBased.getPlantType(block.getRelative(relative)) != null) {
                    occupied[0] = true;
                    return false;
                }
            }
            leaves.add(block);
            return true;
        });
        if (occupied[0]) {
            return;
        }
        Collections.shuffle(leaves);
        if (leaves.isEmpty()) {
            return;
        }
        int amount = ThreadLocalRandom.current().nextInt(1, leaves.size() + 1);
        for (Block block : leaves) {
            if (!Tag.LEAVES.isTagged(block.getType())) {
                continue;
            }
            if (amount-- <= 0) {
                break;
            }
            block.setBlockData(
                    BlockType.FLOWERING_AZALEA_LEAVES.createBlockData()
            );
            this.bloomed = true;
        }
    }

    public void placeFruits() {
        placedFruits.addAll(placeFruits(-1));
        expectedFruits = placedFruits.size();
        updatePersistently(Garden.getInstance().getGardenPlantDataType());
    }

    /**
     * The fruits of this plant that are still standing in the world.
     * <p>
     * A fruit that is a block belongs to the chunk, so it outlives the plant instance that placed it and has
     * to be picked back up when the plant is built again. Only fruits beside the plant's own leaves count,
     * which is what keeps two plants of the same kind from claiming each other's.
     */
    private List<PlacedFruit> findStandingFruits() {
        if (!structure.origin().isChunkLoaded() || !type.bearFruits()) {
            return List.of();
        }
        List<BlockFace> placement = type.fruitPlacement().vectors();
        List<PlacedFruit> standing = new ArrayList<>();
        structure.forEachBlock(LEAVES, (x, y, z, state) -> {
            Block leaf = structure.world().getBlockAt(x, y, z);
            if (!Tag.LEAVES.isTagged(leaf.getType())) {
                return true;
            }
            for (BlockFace relative : placement) {
                Block candidate = leaf.getRelative(relative);
                PlantType found = PlayerHeadBased.getPlantType(candidate);
                if (found == null || !found.key().equals(type.key())) {
                    continue;
                }
                standing.add(new PlacedFruitBlock(candidate, type.key()));
            }
            return true;
        });
        return standing;
    }

    private List<PlacedFruit> placeFruits(int amount) {
        if (!structure.origin().isChunkLoaded() || !type.bearFruits() || !placedFruits.isEmpty()) {
            return List.of();
        }
        World world = structure.world();
        if (world == null) {
            return List.of();
        }
        PlantItem fruit = type.fruitItem();
        List<BlockFace> placement = type.fruitPlacement().vectors();
        List<PlacedFruit> output = new ArrayList<>();
        int[] count = {0};
        structure.forEachBlock((x, y, z, state) -> {
            if (amount != -1 && count[0] >= amount) {
                return false;
            }
            Block block = world.getBlockAt(x, y, z);
            if (!Tag.LEAVES.isTagged(block.getType())) {
                return true;
            }
            if (amount == -1 && Material.FLOWERING_AZALEA_LEAVES != block.getType()) {
                return true;
            }
            List<BlockFace> relatives = placement.stream()
                    .filter(relative -> block.getRelative(relative).getType().isAir())
                    .toList();
            if (relatives.isEmpty()) {
                return true;
            }
            BlockFace chosenRelative = relatives.get(ThreadLocalRandom.current().nextInt(relatives.size()));
            fruit.fruitPlacement().place(block.getRelative(chosenRelative), chosenRelative, id, type)
                    .ifPresent(output::add);
            count[0]++;
            return true;
        });
        this.structure.paste(); // Clear all bloom blocks
        bloomed = false;
        return output;
    }

    public boolean hasBloomed() {
        return bloomed;
    }

    public boolean isAlive() {
        return structure.hasLeaves() && !structure.origin().getBlock().getType().isAir();
    }


    public Location origin() {
        return structure.origin();
    }

    public void registerFruitPicked() {
        for (int i = 0; i < placedFruits.size(); i++) {
            if (placedFruits.get(i).hasDepopulated()) {
                placedFruits.get(i).remove();
                placedFruits.remove(i);
                break;
            }
        }
        expectedFruits--;
        if (expectedFruits < 0) {
            expectedFruits = 0;
        }
        updatePersistently(Garden.getInstance().getGardenPlantDataType());
    }

    public void clearBoundEntities() {
        // Only what the world would otherwise lose: a fruit that is a block belongs to the chunk and is picked
        // up again when the plant is loaded, so taking it down here would throw away the plant's harvest
        placedFruits.stream()
                .filter(PlacedFruit::isTransient)
                .forEach(PlacedFruit::remove);
        expectedFruits = 0;
    }
}
