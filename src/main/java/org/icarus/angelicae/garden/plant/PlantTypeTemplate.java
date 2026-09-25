package org.icarus.angelicae.garden.plant;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.icarus.angelicae.garden.Garden;
import org.icarus.angelicae.garden.configuration.serdes.ColorSerializer;
import org.icarus.angelicae.garden.configuration.serdes.ComponentSerializer;
import org.icarus.angelicae.garden.configuration.serdes.ConsumeEffectSerializer;
import org.icarus.angelicae.garden.configuration.serdes.ExtraItemDataSerializer;
import org.icarus.angelicae.garden.configuration.serdes.FruitPlacementDataSerializer;
import org.icarus.angelicae.garden.configuration.serdes.KeyedSerializer;
import org.icarus.angelicae.garden.configuration.serdes.NamespacedKeySerializer;
import org.icarus.angelicae.garden.configuration.serdes.PdcEntriesSerializer;
import org.icarus.angelicae.garden.configuration.serdes.PdcPrimitiveSerializer;
import org.icarus.angelicae.garden.configuration.serdes.PlantItemSerializer;
import org.icarus.angelicae.garden.configuration.serdes.PotionEffectSerializer;
import org.icarus.angelicae.garden.plant.item.FruitPlacementData;
import org.icarus.angelicae.garden.plant.item.PlantItem;
import org.icarus.angelicae.garden.plant.item.extra.ExtraItemData;
import org.icarus.angelicae.garden.plant.item.extra.PdcEntries;
import org.icarus.angelicae.garden.plant.item.extra.PdcPrimitive;
import org.icarus.angelicae.garden.utility.Logger;
import org.icarus.angelicae.garden.utility.TimeUtil;
import dev.thorinwasher.schem.Schematic;
import dev.thorinwasher.schem.SchematicReader;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import io.papermc.paper.registry.RegistryKey;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.joml.Vector3i;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.awt.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
@Accessors(fluent = true)
@NullMarked
@ConfigSerializable
@SuppressWarnings({"unused", "NotNullFieldNotInitialized"})
public final class PlantTypeTemplate {

    private static final String SCHEM_EXTENSION = ".schem";
    private static final String RANDOM_TRACK = "*";
    private static final String PLANT_FILE = "plant.yml";
    // Case insensitive to match the extension check, so a file that one accepts is not rejected by the other
    private static final Pattern STAGE_NUMBER =
        Pattern.compile("(\\d+)(?=\\D*\\.schem$)", Pattern.CASE_INSENSITIVE);

    private transient String name;
    private transient File directory;

    private @Nullable List<String> tracks;
    private String approximateGrowthTime;
    private FruitPlacement fruitPlacement;
    private @Nullable PlantItem seeds;
    private @Nullable PlantItem fruit;
    private @Nullable Boolean bearFruits;


    public Optional<PlantType> asPlantType() {
        if (!Key.parseableValue(name)) {
            Logger.logWarn("Could not read plant type, file name has to be a valid namespaced key: " + name);
            return Optional.empty();
        }
        if (seeds == null) {
            Logger.logWarn("Undefined seeds material in plant type %s".formatted(name));
            return Optional.empty();
        }
        if (fruit == null) {
            Logger.logWarn("Undefined fruit material in plant type %s".formatted(name));
            return Optional.empty();
        }
        // Checked here rather than while reading the file, because a plant.yml without seeds or fruit is
        // also the case this has to survive
        try {
            fruit.validate("%s.fruit".formatted(name));
            seeds.validate("%s.seeds".formatted(name));
        } catch (RuntimeException e) {
            Logger.logWarn("Could not read plant type, invalid item in " + name);
            Logger.logErr(e);
            return Optional.empty();
        }

        Map<String, List<Schematic>> tracks = this.tracks();
        if (tracks.isEmpty()) {
            Logger.logWarn("Could not read plant type, no usable track folder found: " + name);
            return Optional.empty();
        }
        int maxStages = tracks.values().stream()
                .map(Collection::size)
                .max(Integer::compareTo)
                .orElse(0);
        return Optional.of(new PlantType(
                Garden.key(name),
                this.growthTime(),
                tracks,
                maxStages,
                fruitPlacement,
                seeds,
                fruit,
                bearFruits != null ? bearFruits : true
        ));
    }

    public int growthTime() {
        return TimeUtil.parseTime(approximateGrowthTime);
    }

    private @Nullable String resolveTrack() {
        File[] children = directory.listFiles();
        if (children == null) {
            return null;
        }

        if (tracks != null && !tracks.isEmpty() && !tracks.contains(RANDOM_TRACK)) { // pick from the following tracks
            List<String> availableTracks = new ArrayList<>();
            for (String track : tracks) {
                File trackDirectory = new File(directory, track);
                if (trackDirectory.isDirectory()) {
                    availableTracks.add(track);
                }
            }
            if (availableTracks.isEmpty()) {
                return null;
            }
            return availableTracks.get(ThreadLocalRandom.current().nextInt(availableTracks.size()));
        }

        List<String> trackNames = new ArrayList<>();
        for (File child : children) {
            if (child.isDirectory()) {
                trackNames.add(child.getName());
            }
        }
        if (trackNames.isEmpty()) {
            return null;
        }
        return trackNames.get(ThreadLocalRandom.current().nextInt(trackNames.size()));
    }

    public Map<String, List<Schematic>> tracks() {
        File[] children = directory.listFiles();
        if (children == null) {
            return Map.of();
        }
        ImmutableMap.Builder<String, List<Schematic>> builder = ImmutableMap.builder();
        for (File trackDirectory : children) {
            if (!trackDirectory.isDirectory()) {
                continue;
            }
            String track = trackDirectory.getName();
            File[] schematicFiles = trackDirectory.listFiles();
            if (schematicFiles == null || schematicFiles.length == 0) {
                continue;
            }
            TreeMap<Integer, Schematic> ordered = new TreeMap<>();
            for (File schematicFile : schematicFiles) {
                if (!schematicFile.getName().toLowerCase(Locale.ROOT).endsWith(SCHEM_EXTENSION)) {
                    continue;
                }
                int stage = extractStageNumber(schematicFile.getName());
                if (stage < 0) {
                    Logger.logWarn("Skipping %s, the file name carries no growth stage".formatted(schematicFile));
                    continue;
                }
                Schematic schematic;
                try (InputStream inputStream = new FileInputStream(schematicFile)) {
                    schematic = new SchematicReader().read(inputStream);
                } catch (IOException | RuntimeException e) {
                    // One unreadable file used to throw out of the plant type and from there out of the
                    // registry's static initialiser, which takes the whole plugin with it. Skipping it leaves
                    // the rest of the folder usable.
                    Logger.logWarn("Skipping %s, it could not be read".formatted(schematicFile));
                    Logger.logErr(e);
                    continue;
                }
                if (!hasEnoughBlocks(schematic)) {
                    Logger.logWarn("Skipping %s, it holds fewer blocks than its size needs".formatted(schematicFile));
                    continue;
                }
                ordered.put(stage, schematic);
            }
            if (ordered.isEmpty()) {
                continue;
            }
            builder.put(track, toStages(track, ordered));
        }
        return builder.build();
    }

    /**
     * Guards against a schematic that claims a bigger size than it carries. The reader reads exactly
     * {@code width * height * length} block indices, so a file that is short one runs off the end of its own
     * buffer when the structure is finally placed, far away from anything that could explain it.
     */
    private static boolean hasEnoughBlocks(Schematic schematic) {
        Vector3i size = schematic.size();
        if (size.x() <= 0 || size.y() <= 0 || size.z() <= 0) {
            return false;
        }
        return schematic.blocks().length >= size.x() * size.y() * size.z();
    }

    /**
     * Turns the stage numbers into the list a growth stage is looked up in.
     * <p>
     * A growth stage is the index into that list, so the numbers have to run from zero without gaps. A folder
     * that is numbered differently is left usable rather than renumbered: the missing stages repeat the shape
     * before them, and are reported.
     */
    private static List<Schematic> toStages(String track, TreeMap<Integer, Schematic> ordered) {
        int highest = ordered.lastKey();
        if (ordered.size() == highest + 1) {
            return ImmutableList.copyOf(ordered.values());
        }
        Logger.logWarn("Track '%s' is numbered %s, expected 0 to %d; missing stages repeat the stage before them"
            .formatted(track, ordered.keySet(), highest));
        ImmutableList.Builder<Schematic> builder = new ImmutableList.Builder<>();
        Schematic previous = ordered.firstEntry().getValue();
        for (int stage = 0; stage <= highest; stage++) {
            Schematic found = ordered.get(stage);
            if (found != null) {
                previous = found;
            }
            builder.add(previous);
        }
        return builder.build();
    }


    private static int extractStageNumber(String fileName) {
        Matcher matcher = STAGE_NUMBER.matcher(fileName);
        if (!matcher.find()) {
            return -1;
        }
        return Integer.parseInt(matcher.group(1));
    }


    private static Optional<PlantTypeTemplate> fromDirectory(File plantDirectory) {
        File plantFile = new File(plantDirectory, PLANT_FILE);
        if (!plantFile.isFile()) {
            Logger.logWarn("Could not read plant type, missing '" + PLANT_FILE + "' in " + plantDirectory.getName());
            return Optional.empty();
        }

        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .file(plantFile)
                .nodeStyle(NodeStyle.BLOCK)
                .defaultOptions(opts -> opts.serializers(serializers -> {
                    serializers.register(Component.class, new ComponentSerializer());
                    serializers.register(PlantItem.class, new PlantItemSerializer());
                    serializers.register(Color.class, new ColorSerializer());
                    serializers.register(ExtraItemData.class, new ExtraItemDataSerializer());
                    serializers.register(PdcEntries.class, new PdcEntriesSerializer());
                    serializers.register(PdcPrimitive.class, new PdcPrimitiveSerializer());
                    serializers.register(NamespacedKey.class, new NamespacedKeySerializer());
                    serializers.register(PotionEffectType.class, new KeyedSerializer<>(RegistryKey.MOB_EFFECT));
                    serializers.register(PotionEffect.class, new PotionEffectSerializer());
                    serializers.register(ConsumeEffect.class, new ConsumeEffectSerializer());
                    serializers.register(FruitPlacementData.class, new FruitPlacementDataSerializer());
                }).shouldCopyDefaults(false))
                .indent(2)
                .build();
        try {
            CommentedConfigurationNode root = loader.load();
            PlantTypeTemplate template = root.get(PlantTypeTemplate.class);
            if (template == null) {
                Logger.logWarn("Could not read plant type, " + PLANT_FILE + " deserialized to null: " + plantDirectory.getName());
                return Optional.empty();
            }
            template.name = plantDirectory.getName();
            template.directory = plantDirectory;
            return Optional.of(template);
        } catch (ConfigurateException e) {
            Logger.logWarn("Could not read plant type, failed to load " + PLANT_FILE + ": " + plantDirectory.getName());
            Logger.logErr(e);
            return Optional.empty();
        }
    }

    public static List<PlantTypeTemplate> resolvePlantTypes() {
        File[] directories = plantsDirectory().toFile().listFiles();
        if (directories == null) {
            return List.of();
        }
        ImmutableList.Builder<PlantTypeTemplate> builder = new ImmutableList.Builder<>();
        for (File directory : directories) {
            fromDirectory(directory).ifPresent(builder::add);
        }
        return builder.build();
    }

    /**
     * Resolved per call rather than held in a field, so that it follows the data folder instead of being
     * frozen at whatever it was when this class was first touched.
     */
    private static Path plantsDirectory() {
        return Garden.getInstance().getDataPath().resolve("plants");
    }
}