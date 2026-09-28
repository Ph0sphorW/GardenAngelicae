package org.icarus.angelicae.garden.plant;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.icarus.angelicae.garden.plant.item.PlayerHeadBased;

/**
 * A fruit that is a block in the world, which is how the head shaped fruits are placed.
 * <p>
 * The block belongs to the chunk it stands in, so unlike a display entity it is still there after the chunk
 * has been unloaded and loaded again. A plant therefore has to look for its fruits rather than assume it lost
 * them, see {@code GardenPlant#findStandingFruits()}.
 *
 * @param block        The block the fruit is
 * @param plantTypeKey The plant the fruit belongs to, which is what the block records about itself
 */
public record PlacedFruitBlock(Block block, NamespacedKey plantTypeKey) implements PlacedFruit {

    @Override
    public boolean hasDepopulated() {
        PlantType current = PlayerHeadBased.getPlantType(block);
        return current == null || !current.key().equals(plantTypeKey);
    }

    @Override
    public boolean isTransient() {
        return false;
    }

    @Override
    public void remove() {
        block.setType(Material.AIR);
    }

    @Override
    public Location location() {
        return block.getLocation();
    }
}
