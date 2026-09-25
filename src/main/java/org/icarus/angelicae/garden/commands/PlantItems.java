package org.icarus.angelicae.garden.commands;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.icarus.angelicae.garden.Garden;
import org.icarus.angelicae.garden.MutableGardenRegistry;
import org.icarus.angelicae.garden.plant.GardenPlant;
import org.icarus.angelicae.garden.plant.PlantType;
import org.icarus.angelicae.garden.plant.item.PlantItem;
import org.icarus.angelicae.garden.plant.item.PlantItemContainer;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.incendo.cloud.paper.util.sender.Source;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the name a command uses for a plant item into the item itself, and lists the names for completion.
 * <p>
 * The names are the same ones recipes use, so {@code garden:apple_fruit} becomes {@code apple_fruit}. They are
 * derived from the plant types, which are reloadable, so the lookup is rebuilt per call rather than cached.
 */
public final class PlantItems {

    private PlantItems() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * @return Every name a plant item can be referred to by, mapped to the item
     */
    public static Map<String, PlantItemContainer> all() {
        Map<String, PlantItemContainer> items = new LinkedHashMap<>();
        for (PlantType plant : MutableGardenRegistry.PLANT_TYPE.values()) {
            for (PlantItem.PlantItemType itemType : PlantItem.PlantItemType.values()) {
                PlantItem item = itemType == PlantItem.PlantItemType.SEEDS ? plant.seedItem() : plant.fruitItem();
                items.put(Garden.minimized(PlantItem.key(plant, itemType)), new PlantItemContainer(item, plant, itemType));
            }
        }
        return items;
    }

    /**
     * @param name The name a sender typed
     * @return The item, or null if no plant item goes by that name
     */
    public static PlantItemContainer get(String name) {
        return all().get(name);
    }

    /**
     * @return The names to offer for completion
     */
    public static SuggestionProvider<Source> suggestions() {
        return SuggestionProvider.blockingStrings((context, input) -> all().keySet());
    }

    /**
     * @return The plant the sender is looking at, or null if that block is not part of one
     */
    public static GardenPlant targetPlant(Player player, int maxDistance) {
        Block block = player.getTargetBlockExact(maxDistance);
        if (block == null) {
            return null;
        }
        return Garden.getGardenRegistry().getByLocation(block);
    }

    /**
     * @return The location of the plant the sender is looking at, or their own if there is none
     */
    public static Location targetOrigin(Player player, int maxDistance) {
        GardenPlant plant = targetPlant(player, maxDistance);
        return plant == null ? player.getLocation() : plant.origin();
    }
}
