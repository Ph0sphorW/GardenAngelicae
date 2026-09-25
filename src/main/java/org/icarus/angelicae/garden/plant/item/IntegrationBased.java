package org.icarus.angelicae.garden.plant.item;

import org.icarus.angelicae.garden.plant.PlantType;
import org.icarus.angelicae.garden.utility.ItemResolver;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.awt.Color;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@NullMarked
public record IntegrationBased(String materialKey, Component displayName,
                               List<Component> lore, @Nullable Color color0,
                               Consumer<ItemStack> extraModifications,
                               FruitPlacementData fruitPlacement) implements PlantItem {

    @Override
    public void validate(String context) {
        ItemResolver.validate(materialKey, context);
    }

    @Override
    public Optional<ItemStack> item(int amount, PlantItemType type, PlantType plantType) {
        Optional<ItemStack> itemOptional = ItemResolver.resolve(materialKey);
        if (itemOptional.isEmpty()) {
            return Optional.empty();
        }
        ItemStack item = itemOptional.get();
        item.setAmount(amount);
        item.setData(DataComponentTypes.CUSTOM_NAME, displayName
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
                .colorIfAbsent(NamedTextColor.WHITE));
        if (!lore.isEmpty()) {
            item.setData(DataComponentTypes.LORE, ItemLore.lore(
                    lore.stream()
                            .map(component -> component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                            .map(component -> component.colorIfAbsent(NamedTextColor.GRAY))
                            .toList()
            ));
        }
        item.editPersistentDataContainer(pdc -> {
            pdc.set(ITEM_TYPE_KEY, PersistentDataType.STRING, type.name());
            pdc.set(PLANT_TYPE_KEY, PersistentDataType.STRING, plantType.key().toString());
        });
        extraModifications.accept(item);
        return Optional.of(item);
    }


    @Override
    public Optional<Color> color() {
        return Optional.ofNullable(color0);
    }
}
