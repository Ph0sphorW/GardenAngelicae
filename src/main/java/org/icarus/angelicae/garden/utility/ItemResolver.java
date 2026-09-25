package org.icarus.angelicae.garden.utility;

import net.kyori.adventure.key.Key;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;

import java.util.Locale;
import java.util.Optional;

/**
 * Turns the item key a plant is configured with into an actual item.
 * <p>
 * Only vanilla items are supported, which is what every shipped plant uses. A key is either a bare item name
 * such as {@code apple} or a namespaced one such as {@code minecraft:apple}; anything in another namespace is
 * reported as unknown so that a leftover key from a removed plugin is noticed instead of silently producing
 * nothing.
 */
public final class ItemResolver {

    private ItemResolver() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * @param materialKey The item key a plant declares
     * @return The item, or empty if no vanilla item is named like that
     */
    public static Optional<ItemStack> resolve(String materialKey) {
        return itemType(materialKey).map(ItemType::createItemStack);
    }

    /**
     * Reports an item key that does not resolve, naming where it was found so a broken plant config can be
     * traced back.
     *
     * @param materialKey The item key a plant declares
     * @param context     Where the key came from, only used for the message
     */
    public static void validate(String materialKey, String context) {
        if (itemType(materialKey).isEmpty()) {
            Logger.logWarn("Unknown item '%s' in %s".formatted(materialKey, context));
        }
    }

    private static Optional<ItemType> itemType(String materialKey) {
        String key = materialKey.toLowerCase(Locale.ROOT);
        if (!key.contains(":")) {
            key = Key.MINECRAFT_NAMESPACE + ":" + key;
        }
        if (!Key.parseable(key)) {
            return Optional.empty();
        }
        Key parsed = Key.key(key);
        // Item types of other plugins are not registered in this registry, so this also rejects their keys
        if (!parsed.namespace().equals(Key.MINECRAFT_NAMESPACE)) {
            return Optional.empty();
        }
        return Optional.ofNullable(Registry.ITEM.get(parsed));
    }
}
