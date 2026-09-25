package org.icarus.angelicae.garden.integration.exported;

import com.dre.brewery.recipe.items.PluginItem;
import org.icarus.angelicae.garden.Garden;
import org.icarus.angelicae.garden.plant.item.PlantItem;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.ItemStack;

public final class BreweryGardenIngredient extends PluginItem {
    @Override
    public boolean matches(ItemStack itemStack) {
        Key plantItem = PlantItem.plantItemKey(itemStack);
        if (plantItem != null) {
            return Garden.minimized(plantItem).equalsIgnoreCase(this.getItemId())
                    || Garden.minimized(plantItem).replace("_fruit", "").equalsIgnoreCase(this.getItemId());
        }
        return false;
    }
}