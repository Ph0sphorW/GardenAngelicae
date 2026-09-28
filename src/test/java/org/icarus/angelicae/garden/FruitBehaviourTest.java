package org.icarus.angelicae.garden;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.icarus.angelicae.garden.plant.FruitPlacement;
import org.icarus.angelicae.garden.plant.PlantType;
import org.icarus.angelicae.garden.plant.item.FruitPlacementData;
import org.icarus.angelicae.garden.plant.item.PlantItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.MockBukkitExtension;
import org.mockbukkit.mockbukkit.MockBukkitInject;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.jspecify.annotations.NullMarked;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The items a plant hands out are the plugin's own, and the plugin has to keep them out of the world.
 * <p>
 * Only the placement is covered here. A plant that bears head shaped fruit can not be exercised on MockBukkit,
 * which can not build a skull block state, so the fruit bookkeeping of such a plant has to be checked on a
 * real server.
 */
@NullMarked
@ExtendWith(MockBukkitExtension.class)
class FruitBehaviourTest {

    @MockBukkitInject
    ServerMock serverMock;

    @Test
    void aFruitItemCanNotBePlaced() {
        NamespacedKey key = Garden.key("test_plant");
        loadGarden();
        MutableGardenRegistry.PLANT_TYPE.newBacking(List.of(plantType(key)));

        WorldMock world = serverMock.addSimpleWorld("world");
        PlayerMock player = serverMock.addPlayer();
        Block target = world.getBlockAt(0, 64, 0);

        ItemStack fruit = fruitItem(key);
        assertTrue(PlantItem.isGardenItem(fruit), "the item should be recognised as one of the plugin's");
        assertNotNull(PlantItem.plantItemKey(fruit), "the item should be recognised as a garden fruit");

        assertTrue(place(player, target, fruit).isCancelled(), "a fruit of a known plant must not be placeable");
    }

    /**
     * A fruit that was left behind by a plant whose type is gone is still an item the plugin made. A head a
     * tree dropped is exactly that as soon as its plant type is removed from the config, and it must not turn
     * into a block the player may put down.
     */
    @Test
    void aFruitItemOfAnUnknownPlantCanNotBePlacedEither() {
        loadGarden();
        MutableGardenRegistry.PLANT_TYPE.newBacking(List.of());

        WorldMock world = serverMock.addSimpleWorld("world");
        PlayerMock player = serverMock.addPlayer();
        Block target = world.getBlockAt(0, 64, 0);

        assertTrue(place(player, target, fruitItem(Garden.key("a_plant_that_is_gone"))).isCancelled(),
            "an item the plugin made must stay out of the world even when its plant type is gone");
    }

    private void loadGarden() {
        try {
            MockBukkit.load(Garden.class);
        } catch (Throwable e) {
            // MockBukkit can not serve every part of the plugin, but the plugin itself is up by now
            assertNotNull(Garden.getInstance(), "the plugin should still be there, but was not: " + e);
        }
    }

    private BlockPlaceEvent place(PlayerMock player, Block target, ItemStack item) {
        BlockPlaceEvent event = new BlockPlaceEvent(
            target,
            target.getState(),
            target.getRelative(BlockFace.DOWN),
            item,
            player,
            true,
            EquipmentSlot.HAND
        );
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    private static PlantType plantType(NamespacedKey key) {
        return new PlantType(key, 100, Map.of("default", List.of()), 1, FruitPlacement.ADJACENT,
            new DoingNothingItem(), new DoingNothingItem(), true);
    }

    private static ItemStack fruitItem(NamespacedKey key) {
        ItemStack item = ItemStack.of(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(PlantItem.PLANT_TYPE_KEY, PersistentDataType.STRING, key.toString());
        pdc.set(PlantItem.ITEM_TYPE_KEY, PersistentDataType.STRING, PlantItem.PlantItemType.FRUIT.name());
        item.setItemMeta(meta);
        return item;
    }

    /** Stands in for a seed or fruit item, the behavior under test does not ask it for anything. */
    private record DoingNothingItem() implements PlantItem {

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
            return new FruitPlacementData.HeadPlacement(
                new org.icarus.angelicae.garden.utility.CachedValue<>(() -> Bukkit.createProfile(java.util.UUID.randomUUID())));
        }
    }
}
