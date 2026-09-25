package org.icarus.angelicae.garden.nms;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.Plugin;
import org.icarus.angelicae.garden.utility.Logger;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Batches recipe changes so that a reload only notifies the players once.
 * <p>
 * Registering a recipe is far more expensive than it looks. Every single add or remove ends up in the
 * server's recipe manager, which recomputes its lookup tables and then pushes a full resource reload at every
 * online player: the serialised tag registry, all advancements, and the recipe book. Garden registers two
 * recipes per plant type, so a plain reload resends that whole payload once per recipe.
 * <p>
 * The server provides a natural place to hold the notifications back.
 * {@code RecipeManager.finalizeRecipeLoading()} returns immediately while the private {@code featureflagset}
 * field is null, and that method is the recipe manager's only caller of the reload, so clearing the field
 * silences it. The changes themselves go through the same internal calls the server would make, and
 * everything is flushed once in {@link #close()}.
 * <p>
 * All of this is reflection: the plugin builds against the Bukkit API alone, and the server publishes no
 * replacement for these internals. Should a future server version move or rename any of them, the
 * transaction degrades to the plain Bukkit calls and says so once, rather than breaking the reload.
 */
public final class RecipeTransaction implements AutoCloseable {

    private static final String MINECRAFT_SERVER = "net.minecraft.server.MinecraftServer";
    private static final String RECIPE_MANAGER = "net.minecraft.world.item.crafting.RecipeManager";
    private static final String CRAFT_RECIPE = "org.bukkit.craftbukkit.inventory.CraftRecipe";
    private static final String CRAFT_SHAPELESS_RECIPE = "org.bukkit.craftbukkit.inventory.CraftShapelessRecipe";
    private static final String PLAYER_LIST = "net.minecraft.server.players.PlayerList";

    private static boolean resolved;
    private static boolean warned;
    /** Resolved once. Null means either not attempted yet or unavailable on this server build. */
    private static @Nullable Handles handles;

    private final Plugin plugin;
    /** Null when the internals are unavailable, in which case every call falls back to the Bukkit API. */
    private final @Nullable Handles internals;

    /** What the feature flag field held before it was cleared, put back verbatim in {@link #close()}. */
    private final @Nullable Object suppressedFeatureFlags;

    private RecipeTransaction(Plugin plugin, @Nullable Handles internals, @Nullable Object suppressedFeatureFlags) {
        this.plugin = plugin;
        this.internals = internals;
        this.suppressedFeatureFlags = suppressedFeatureFlags;
    }

    /**
     * Opens a transaction. Should the server internals be unreachable, the returned transaction still applies
     * every change, it merely cannot merge the player notifications.
     */
    public static RecipeTransaction begin(Plugin plugin) {
        Handles handles = handles();
        if (handles == null) {
            return new RecipeTransaction(plugin, null, null);
        }
        try {
            Object previous = handles.featureFlags.get(handles.recipeManager);
            handles.featureFlags.set(handles.recipeManager, null);
            return new RecipeTransaction(plugin, handles, previous);
        } catch (ReflectiveOperationException e) {
            giveUp(e);
            return new RecipeTransaction(plugin, null, null);
        }
    }

    /**
     * Registers a recipe without notifying the players. Types other than the one this plugin uses fall back to
     * the Bukkit call, which is correct, just not batched.
     */
    public void add(Recipe recipe) {
        if (internals == null || !(recipe instanceof ShapelessRecipe shapeless)) {
            plugin.getServer().addRecipe(recipe);
            return;
        }
        try {
            Object craftRecipe = internals.fromBukkitRecipe.invoke(null, shapeless);
            internals.addToCraftingManager.invoke(craftRecipe);
        } catch (ReflectiveOperationException e) {
            giveUp(e);
            plugin.getServer().addRecipe(recipe);
        }
    }

    /**
     * Removes the recipe registered under the given key. Removing something that is not there is not an error:
     * the recipe may simply never have been registered.
     */
    public void remove(NamespacedKey key) {
        if (internals == null) {
            plugin.getServer().removeRecipe(key);
            return;
        }
        try {
            Object resourceKey = internals.toMinecraft.invoke(null, key);
            internals.removeRecipe.invoke(internals.recipeManager, resourceKey);
        } catch (ReflectiveOperationException e) {
            giveUp(e);
            plugin.getServer().removeRecipe(key);
        }
    }

    /**
     * Recomputes the recipe lookup tables and sends a single update covering everything the transaction
     * changed.
     * <p>
     * The feature flag field is restored in a {@code finally} block on purpose. While it is null, no recipe
     * change ever reaches the players, so leaving it behind would be far worse than a missing update.
     */
    @Override
    public void close() {
        if (internals == null) {
            plugin.getServer().updateRecipes();
            return;
        }
        try {
            if (suppressedFeatureFlags != null) {
                internals.finalizeRecipeLoading.invoke(internals.recipeManager, suppressedFeatureFlags);
            }
            internals.reloadRecipes.invoke(internals.getPlayerList.invoke(internals.server));
        } catch (ReflectiveOperationException e) {
            giveUp(e);
            plugin.getServer().updateRecipes();
        } finally {
            restoreFeatureFlags();
        }
    }

    private void restoreFeatureFlags() {
        if (internals == null || suppressedFeatureFlags == null) {
            return;
        }
        try {
            internals.featureFlags.set(internals.recipeManager, suppressedFeatureFlags);
        } catch (ReflectiveOperationException e) {
            Logger.logErr("Could not restore the recipe manager state, recipes may stop syncing");
            Logger.logErr(e);
        }
    }

    /**
     * Disables batching for the rest of the session, because whatever went wrong would go wrong again.
     */
    private static void giveUp(Throwable cause) {
        handles = null;
        resolved = true;
        if (warned) {
            return;
        }
        warned = true;
        Logger.logWarn("Recipe batching is unavailable on this server build, reloads will be slower");
        Logger.logErr(cause);
    }

    private static @Nullable Handles handles() {
        if (resolved) {
            return handles;
        }
        resolved = true;
        try {
            Class<?> serverClass = Class.forName(MINECRAFT_SERVER);
            Class<?> recipeManagerClass = Class.forName(RECIPE_MANAGER);
            Class<?> craftRecipeClass = Class.forName(CRAFT_RECIPE);
            Class<?> craftShapelessClass = Class.forName(CRAFT_SHAPELESS_RECIPE);
            Class<?> playerListClass = Class.forName(PLAYER_LIST);

            Object server = serverClass.getMethod("getServer").invoke(null);
            Object recipeManager = serverClass.getMethod("getRecipeManager").invoke(server);

            Field featureFlags = recipeManagerClass.getDeclaredField("featureflagset");
            featureFlags.setAccessible(true);

            handles = new Handles(
                server,
                recipeManager,
                featureFlags,
                recipeManagerClass.getMethod("finalizeRecipeLoading", featureFlags.getType()),
                recipeManagerClass.getMethod("removeRecipe", Class.forName("net.minecraft.resources.ResourceKey")),
                craftRecipeClass.getMethod("toMinecraft", NamespacedKey.class),
                craftRecipeClass.getMethod("addToCraftingManager"),
                craftShapelessClass.getMethod("fromBukkitRecipe", ShapelessRecipe.class),
                serverClass.getMethod("getPlayerList"),
                playerListClass.getMethod("reloadRecipes")
            );
            return handles;
        } catch (ReflectiveOperationException | LinkageError e) {
            giveUp(e);
            return null;
        }
    }

    /**
     * The resolved server internals. Every member is looked up once, so a reload does not pay for reflection
     * lookups per recipe.
     */
    private record Handles(Object server, Object recipeManager, Field featureFlags,
                           Method finalizeRecipeLoading, Method removeRecipe, Method toMinecraft,
                           Method addToCraftingManager, Method fromBukkitRecipe, Method getPlayerList,
                           Method reloadRecipes) {
    }
}
