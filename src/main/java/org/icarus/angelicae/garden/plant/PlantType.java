package org.icarus.angelicae.garden.plant;

import com.google.common.collect.ImmutableList;
import org.icarus.angelicae.garden.plant.item.PlantItem;
import org.icarus.angelicae.garden.structure.PlantStructure;
import org.icarus.angelicae.garden.structure.StructureGeometry;
import dev.thorinwasher.schem.Schematic;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.joml.Matrix3d;
import org.joml.Vector3i;
import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

@NullMarked
public record PlantType(
        NamespacedKey key,
        int growthTime,
        Map<String, List<Schematic>> structures,
        int maxStages,
        FruitPlacement fruitPlacement,
        PlantItem seedItem,
        PlantItem fruitItem,
        boolean bearFruits
) implements Keyed {
    private static final List<Matrix3d> ALLOWED_TRANSFORMATIONS = compileAllowedTransformations();
    private static final Random RANDOM = new Random();


    private static List<Matrix3d> compileAllowedTransformations() {
        ImmutableList.Builder<Matrix3d> builder = new ImmutableList.Builder<>();
        Matrix3d identity = new Matrix3d();
        for (int i = 0; i < 4; i++) {
            builder.add(round(identity.rotateY(Math.PI / 2 * i, new Matrix3d())));
        }
        identity.negateZ();
        for (int i = 0; i < 4; i++) {
            builder.add(round(identity.rotateY(Math.PI / 2 * i, new Matrix3d())));
        }
        return builder.build();
    }

    private static Matrix3d round(Matrix3d input) {
        double[] array = input.get(new double[9]);
        for (int i = 0; i < array.length; i++) {
            array[i] = Math.round(array[i]);
        }
        return new Matrix3d(array[0], array[1], array[2], array[3], array[4], array[5], array[6], array[7], array[8]);
    }

    /**
     * Builds one growth stage of the plant, rotated at random.
     *
     * @return The structure, or empty when the track stops before that stage
     */
    public Optional<PlantStructure> newStructure(Location origin, int age, String track) {
        return newStructure(origin, age, track, randomTransformation());
    }

    /**
     * Builds one growth stage with a known rotation, which is how a plant that was saved earlier is rebuilt
     * looking the way it did.
     *
     * @return The structure, or empty when the track stops before that stage
     */
    public Optional<PlantStructure> newStructure(Location origin, int age, String track, Matrix3d transformation) {
        List<Schematic> stages = structures.get(track);
        if (stages == null || age < 0 || age >= stages.size()) {
            return Optional.empty();
        }
        Schematic schematic = stages.get(age);
        Vector3i size = schematic.size(transformation);
        // Centres the structure on its origin in the horizontal plane, which is where the player placed it
        Vector3i offset = new Vector3i(size.x() / 2, 0, size.z() / 2);
        return Optional.of(new PlantStructure(
                StructureGeometry.of(schematic, transformation, offset),
                origin.getBlockX(), origin.getBlockY(), origin.getBlockZ(),
                transformation, origin.getWorld().getUID()));
    }

    private static Matrix3d randomTransformation() {
        return ALLOWED_TRANSFORMATIONS.get(RANDOM.nextInt(ALLOWED_TRANSFORMATIONS.size()));
    }

    @Override
    public NamespacedKey getKey() {
        return key();
    }

    public static List<PlantType> readPlantTypes() {
        // The schematics are read anew, so the geometry built from the previous ones can no longer be reached
        StructureGeometry.invalidate();
        return PlantTypeTemplate.resolvePlantTypes()
                .stream()
                .map(it -> it.asPlantType().orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }
}
