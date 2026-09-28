package org.icarus.angelicae.garden.plant;

import org.bukkit.Location;

/**
 * One fruit a plant has put into the world.
 * <p>
 * A fruit is either a set of display entities with an interaction box to click, or a block such as a head.
 * Either way the plant has to be able to tell whether the fruit is still there, and to take it away when the
 * plant is removed, so both kinds are held in the same list.
 */
public sealed interface PlacedFruit permits PlacedFruitDisplays, PlacedFruitBlock {

    /** Whether the fruit is no longer in the world, because it was picked or broke. */
    boolean hasDepopulated();

    /**
     * Whether the fruit only lives as long as this plant does.
     * <p>
     * A display entity is not saved with the chunk, so it is gone for good once the plant lets go of it and
     * has to be taken down by the plant itself. A fruit that is a block is part of the world and outlives the
     * plant, so it is left alone and picked back up when the plant is built again, see
     * {@code GardenPlant#findStandingFruits()}.
     */
    boolean isTransient();

    /** Takes the fruit out of the world. */
    void remove();

    /** Where the fruit sits, which is where a fallen fruit is dropped. */
    Location location();
}
