package org.icarus.angelicae.garden;

import com.dre.brewery.recipe.items.PluginItem;
import com.google.common.base.Preconditions;
import org.icarus.angelicae.garden.commands.GardenCommandManager;
import org.icarus.angelicae.garden.configuration.GardenConfig;
import org.icarus.angelicae.garden.configuration.GardenTranslator;
import org.icarus.angelicae.garden.integration.exported.BreweryGardenIngredient;
import org.icarus.angelicae.garden.integration.exported.TBPGardenIntegration;
import org.icarus.angelicae.garden.listener.BlockEventListener;
import org.icarus.angelicae.garden.listener.EventListeners;
import org.icarus.angelicae.garden.listener.WorldEventListener;
import org.icarus.angelicae.garden.nms.RecipeTransaction;
import org.icarus.angelicae.garden.persist.Database;
import org.icarus.angelicae.garden.persist.GardenPlantDataType;
import org.icarus.angelicae.garden.plant.GardenPlant;
import org.icarus.angelicae.garden.plant.GrowthManager;
import org.icarus.angelicae.garden.plant.PlantType;
import org.icarus.angelicae.garden.plant.item.PlantItem;
import org.icarus.angelicae.garden.utility.Logger;
import dev.thorinwasher.blockutil.api.BlockUtilAPI;
import dev.thorinwasher.blockutil.api.event.BlockDisableDropEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import lombok.Getter;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class Garden extends JavaPlugin {

    /**
     * Namespace of everything this plugin saves or names: plant type ids, persistent data keys and the prefix
     * other plugins use to reference garden items in their recipes (for example {@code garden:grape}).
     * <p>
     * Deliberately a constant rather than the plugin name: it is a data contract, so renaming the plugin must
     * not orphan the plants already saved in the database or break the recipes that already reference them.
     */
    public static final String NAMESPACE = "garden";

    /** Folder the plugin used before it was renamed, migrated once on startup if it is still around. */
    private static final String LEGACY_DATA_FOLDER_NAME = "Garden";

    @Getter
    private static Garden instance;
    @Getter
    private static PlantRegistry gardenRegistry;
    private Database database;
    @Getter
    private GardenPlantDataType gardenPlantDataType;
    @Getter
    private BlockUtilAPI blockUtil;
    @Getter
    private GardenTranslator translator;
    private boolean loadSuccess = false;
    private ScheduledTask growthTask;
    private WorldEventListener worldEventListener;

    @Override
    public void onLoad() {
        instance = this;
        migrateDataFolder();
        savePlantResources();
        try {
            // Registered under the namespace rather than the plugin name, so that renaming the plugin does
            // not change the prefix every recipe out there is written with
            PluginItem.registerForConfig(NAMESPACE, BreweryGardenIngredient::new);
        } catch (NoClassDefFoundError ignored) {
        }
        try {
            TBPGardenIntegration.loadIfPossible();
        } catch (NoClassDefFoundError ignored) {
        }
        this.loadSuccess = true;
    }

    @Override
    public void onEnable() {
        Preconditions.checkState(loadSuccess, "Failed on load, see logs.");
        this.database = new Database();
        try {
            database.init(this.getDataFolder());
        } catch (IOException | SQLException e) {
            throw new RuntimeException(e);
        }
        gardenRegistry = new PlantRegistry();
        gardenPlantDataType = database.plantDataType();
        this.blockUtil = new BlockUtilAPI.Builder()
                .withConnectionSupplier(() -> {
                    try {
                        return database.getConnection();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                })
                .withDropEventHandler(this::handleBlockDrops)
                .withPluginOwner(this)
                .build();

        translator = new GardenTranslator(new File(this.getDataFolder(), "locale"));
        translator.reload();
        GlobalTranslator.translator().addSource(translator);
        this.worldEventListener = new WorldEventListener(gardenRegistry, gardenPlantDataType);
        Bukkit.getWorlds().forEach(worldEventListener::loadWorld);
        Bukkit.getPluginManager().registerEvents(new EventListeners(gardenRegistry, gardenPlantDataType), this);
        Bukkit.getPluginManager().registerEvents(new BlockEventListener(gardenRegistry, gardenPlantDataType), this);
        Bukkit.getPluginManager().registerEvents(worldEventListener, this);
        this.registerPlantRecipes();
        new GardenCommandManager(this).register();
        GrowthManager growthManager = new GrowthManager(gardenRegistry, gardenPlantDataType);
        growthTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, t -> growthManager.tick(), 1, 200);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, t -> {
            gardenRegistry.getPlants()
                    .forEach(GardenPlant::tick);
        }, 1, 100);
        if (MutableGardenRegistry.PLANT_TYPE.values().isEmpty()) {
            Logger.logErr("There's no available plant types for garden!");
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (growthTask != null) {
            growthTask.cancel();
        }
        // Every field here may still be unset: the server disables a plugin that failed part way through
        // enabling it, and throwing out of onDisable would bury the original failure under this one.
        if (translator != null) {
            GlobalTranslator.translator().removeSource(translator);
        }
        if (database != null) {
            database.close();
        }
    }

    private void savePlantResources() {
        File plantsFolder = getDataPath().resolve("plants").toFile();
        if (plantsFolder.exists()) {
            return;
        }
        URL resource = Garden.class.getResource("/plants");
        if (resource == null) {
            throw new RuntimeException("Could not find internal resource: '/plants' :(");
        }

        try {
            URI uri = resource.toURI();
            if ("jar".equals(uri.getScheme())) {
                try (FileSystem fileSystem = FileSystems.newFileSystem(uri, Map.of())) {
                    copyResourceTree(fileSystem.getPath("/plants"), plantsFolder.toPath());
                }
            } else {
                copyResourceTree(Path.of(uri), plantsFolder.toPath());
            }
        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void copyResourceTree(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                String relative = source.relativize(path).toString();
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void handleBlockDrops(BlockDisableDropEvent blockDisableDropEvent) {
        Material material = blockDisableDropEvent.getBlock().getType();
        if (GardenConfig.instance().dropsDefaultItems().stream().anyMatch(tag -> tag.isTagged(material))) {
            blockDisableDropEvent.setDisableDrops(false);
            return;
        }
        for (Map.Entry<Tag<Material>, Material> entry : GardenConfig.instance().dropOverride().entrySet()) {
            if (entry.getKey().isTagged(material)) {
                blockDisableDropEvent.setDropOverride(List.of(new ItemStack(entry.getValue())));
                return;
            }
        }
    }

    public void reload() {
        GardenConfig.MEMORIZED.reload();
        translator.reload();
        gardenRegistry.clear();
        MutableGardenRegistry.PLANT_TYPE.newBacking(PlantType.readPlantTypes());
        Bukkit.getWorlds().forEach(worldEventListener::loadWorld);
        registerPlantRecipes();
        if (MutableGardenRegistry.PLANT_TYPE.values().isEmpty()) {
            Logger.logErr("There's no available plant types for garden!");
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void registerPlantRecipes() {
        // Adding a recipe already notifies every player, and `Bukkit.updateRecipes()` notifies them again on
        // top. Doing that once per plant type turns a reload into a burst of full recipe book resends, so the
        // work is kept inside a single recipe transaction, which sends one update at the end instead.
        try (RecipeTransaction transaction = RecipeTransaction.begin(this)) {
            for (PlantType plantType : MutableGardenRegistry.PLANT_TYPE.values()) {
                NamespacedKey namespacedKey = plantType.key();
                transaction.remove(namespacedKey);
                Optional<ItemStack> optionalSeeds = plantType.seedItem().item(4, PlantItem.PlantItemType.SEEDS, plantType);
                Optional<ItemStack> optionalFruits = plantType.fruitItem().item(PlantItem.PlantItemType.FRUIT, plantType);
                if (optionalFruits.isEmpty() || optionalSeeds.isEmpty()) {
                    continue;
                }
                ShapelessRecipe recipe = new ShapelessRecipe(namespacedKey, optionalSeeds.get());
                recipe.addIngredient(optionalFruits.get());
                transaction.add(recipe);
            }
        }
    }

    /**
     * Moves the data of the pre-rename plugin over, so that an existing installation keeps its plants and
     * configuration instead of starting empty next to an orphaned folder.
     */
    private void migrateDataFolder() {
        File current = getDataFolder();
        if (current.exists()) {
            return;
        }
        Path parent = current.toPath().getParent();
        if (parent == null) {
            return;
        }
        Path previous = parent.resolve(LEGACY_DATA_FOLDER_NAME);
        if (!Files.isDirectory(previous)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(previous)) {
            for (Path path : paths.toList()) {
                Path destination = current.toPath().resolve(previous.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Logger.log("Moved files from %s to %s".formatted(LEGACY_DATA_FOLDER_NAME, current.getName()));
        } catch (IOException e) {
            Logger.logErr("Failed to move the data folder, starting fresh instead");
            Logger.logErr(e);
        }
    }

    public static NamespacedKey key(String string) {
        String[] split = string.split(":", 2);
        String value;
        String namespace;
        if (split.length == 1) {
            namespace = NAMESPACE;
            value = string;
        } else {
            namespace = split[0];
            value = split[1];
        }
        if (!Key.parseableValue(value) || !Key.parseableNamespace(namespace)) {
            throw new IllegalArgumentException("Invalid key: " + string);
        }
        return new NamespacedKey(namespace, value);
    }

    public static String minimized(Key key) {
        if (key.namespace().equalsIgnoreCase(NAMESPACE)) {
            return key.value();
        }
        return key.asString();
    }

}