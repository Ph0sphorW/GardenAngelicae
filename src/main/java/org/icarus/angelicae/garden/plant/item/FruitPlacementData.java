package org.icarus.angelicae.garden.plant.item;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.icarus.angelicae.garden.plant.PlacedFruit;
import org.icarus.angelicae.garden.plant.PlacedFruitBlock;
import org.icarus.angelicae.garden.plant.PlacedFruitDisplays;
import org.icarus.angelicae.garden.plant.PlantType;
import org.icarus.angelicae.garden.utility.CachedValue;
import org.icarus.angelicae.garden.utility.ItemResolver;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockType;
import org.bukkit.block.Skull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.icarus.angelicae.garden.plant.PlacedFruitDisplays.OWNING_PLANT;
import static org.icarus.angelicae.garden.plant.item.PlantItem.ITEM_TYPE_KEY;
import static org.icarus.angelicae.garden.plant.item.PlantItem.PLANT_TYPE_KEY;

@SuppressWarnings("UnstableApiUsage")
public sealed interface FruitPlacementData {

    Optional<PlacedFruit> place(Block relative, BlockFace facing, UUID owningPlant, PlantType plantType);

    /** Whether this placement leaves a fruit behind as a block rather than as a display entity. */
    static boolean placesBlocks(PlantItem fruitItem) {
        return fruitItem.fruitPlacement() instanceof HeadPlacement;
    }

    record HeadPlacement(CachedValue<PlayerProfile> profile) implements FruitPlacementData {
        @Override
        public Optional<PlacedFruit> place(Block relative, BlockFace facing, UUID owningPlant, PlantType plantType) {
            Skull skull;
            if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
                skull = (Skull) BlockType.PLAYER_HEAD.createBlockData().createBlockState();
            } else {
                skull = (Skull) BlockType.PLAYER_WALL_HEAD.createBlockData(wallHead -> wallHead.setFacing(facing)).createBlockState();
            }
            skull.setPlayerProfile(profile.get());
            // The block carries what an item made of it carries as well, so a head a player breaks is still
            // recognised as a fruit and does not become a block they may put down again.
            PersistentDataContainer pdc = skull.getPersistentDataContainer();
            pdc.set(PLANT_TYPE_KEY, PersistentDataType.STRING, plantType.key().toString());
            pdc.set(ITEM_TYPE_KEY, PersistentDataType.STRING, PlantItem.PlantItemType.FRUIT.name());
            pdc.set(OWNING_PLANT, PersistentDataType.BYTE_ARRAY, org.icarus.angelicae.garden.utility.Encoder.asBytes(owningPlant));
            skull.copy(relative.getLocation()).update(true);
            return Optional.of(new PlacedFruitBlock(relative, plantType.key()));
        }
    }

    record MaterialPlacement(String materialKey, int displayCount,
                             Consumer<ItemStack> extraModifications, float placedScale) implements FruitPlacementData {

        @Override
        public Optional<PlacedFruit> place(Block relative, BlockFace facing, UUID owningPlant, PlantType plantType)  {
            return ItemResolver.resolve(materialKey)
                    .map(item -> {
                        extraModifications.accept(item);
                        return PlacedFruitDisplays.generate(item, facing, relative, plantType.getKey(), owningPlant, placedScale, displayCount);
                    });
        }
    }
}
