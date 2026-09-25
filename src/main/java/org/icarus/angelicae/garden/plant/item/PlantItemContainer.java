package org.icarus.angelicae.garden.plant.item;

import org.icarus.angelicae.garden.plant.PlantType;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

public record PlantItemContainer(PlantItem plantItem, PlantType plantType, PlantItem.PlantItemType plantItemType) {

    public Optional<ItemStack> toItem(int amount) {
        return plantItem.item(amount, plantItemType, plantType);
    }
}
