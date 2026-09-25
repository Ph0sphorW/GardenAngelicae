package org.icarus.angelicae.garden.persist;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.icarus.angelicae.garden.MutableGardenRegistry;
import org.icarus.angelicae.garden.plant.GardenPlant;
import org.icarus.angelicae.garden.plant.PlantType;
import org.icarus.angelicae.garden.structure.PlantStructure;
import org.icarus.angelicae.garden.utility.Encoder;
import org.icarus.angelicae.garden.utility.FileUtil;
import org.icarus.angelicae.garden.utility.Logger;
import org.joml.Matrix3d;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Reads and writes the plants kept in the SQL storage.
 * <p>
 * Plants are stored per chunk and only ever read back the same way. A world can hold far more plants than it
 * has chunks loaded, so loading a whole world at once would pull in every plant the server has ever saved;
 * a chunk load asks for the handful that are actually nearby.
 */
public class GardenPlantDataType {

    private final Database database;
    private final ScheduledExecutorService executor;


    public GardenPlantDataType(Database database, ScheduledExecutorService executor) {
        this.database = database;
        this.executor = executor;
    }

    public CompletableFuture<Void> insert(GardenPlant plant) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement preparedStatement =
                     connection.prepareStatement(FileUtil.readInternalResource("/sql/insert_plant.sql"))) {
                PlantStructure structure = plant.getStructure();
                preparedStatement.setBytes(1, Encoder.asBytes(plant.getId()));
                preparedStatement.setString(2, plant.getType().key().toString());
                preparedStatement.setInt(3, plant.getAge());
                preparedStatement.setInt(4, structure.originX());
                preparedStatement.setInt(5, structure.originY());
                preparedStatement.setInt(6, structure.originZ());
                preparedStatement.setInt(7, chunkOf(structure.originX()));
                preparedStatement.setInt(8, chunkOf(structure.originZ()));
                preparedStatement.setBytes(9, Encoder.asBytes(structure.worldUuid()));
                preparedStatement.setInt(10, Encoder.asInteger(structure.transformation()));
                preparedStatement.setString(11, plant.getTrack());
                preparedStatement.setInt(12, plant.getExpectedFruits());
                preparedStatement.execute();
            } catch (SQLException e) {
                logFailure("save", plant.getId(), e);
            }
        }, executor);
    }

    public CompletableFuture<Void> update(GardenPlant plant) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement preparedStatement =
                     connection.prepareStatement(FileUtil.readInternalResource("/sql/update_plant.sql"))) {
                preparedStatement.setInt(1, plant.getAge());
                preparedStatement.setInt(2, Encoder.asInteger(plant.getStructure().transformation()));
                preparedStatement.setInt(3, plant.getExpectedFruits());
                preparedStatement.setBytes(4, Encoder.asBytes(plant.getId()));
                preparedStatement.execute();
            } catch (SQLException e) {
                logFailure("update", plant.getId(), e);
            }
        }, executor);
    }

    public CompletableFuture<Void> remove(GardenPlant plant) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement preparedStatement =
                     connection.prepareStatement(FileUtil.readInternalResource("/sql/remove_plant.sql"))) {
                preparedStatement.setBytes(1, Encoder.asBytes(plant.getId()));
                preparedStatement.execute();
            } catch (SQLException e) {
                logFailure("delete", plant.getId(), e);
            }
        }, executor);
    }

    /**
     * Reads the plants standing in the given chunk.
     *
     * @param chunk The chunk to read, which is also the unit they are stored under
     */
    public CompletableFuture<List<GardenPlant>> fetch(Chunk chunk) {
        World world = chunk.getWorld();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        return CompletableFuture.supplyAsync(() -> {
            List<GardenPlant> output = new ArrayList<>();
            try (Connection connection = database.getConnection();
                 PreparedStatement preparedStatement =
                     connection.prepareStatement(FileUtil.readInternalResource("/sql/find_plant_chunk.sql"))) {
                preparedStatement.setBytes(1, Encoder.asBytes(world.getUID()));
                preparedStatement.setInt(2, chunkX);
                preparedStatement.setInt(3, chunkZ);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    while (resultSet.next()) {
                        readPlant(world, resultSet).ifPresent(output::add);
                    }
                }
            } catch (SQLException e) {
                Logger.logErr("Failed to read the plants of chunk %d,%d in %s"
                    .formatted(chunkX, chunkZ, world.getName()));
                Logger.logErr(e);
            }
            return output;
        }, executor);
    }

    /**
     * Rebuilds one stored plant. The structure is not stored: it is derived from the plant type's schematics
     * using the growth stage and the rotation that are.
     *
     * @return The plant, or empty if the row can no longer be represented
     */
    private Optional<GardenPlant> readPlant(World world, ResultSet resultSet) throws SQLException {
        String storedType = resultSet.getString("plant_type");
        Location origin = new Location(world, resultSet.getInt("origin_x"), resultSet.getInt("origin_y"),
            resultSet.getInt("origin_z"));
        PlantType plantType = MutableGardenRegistry.PLANT_TYPE.get(NamespacedKey.fromString(storedType));
        if (plantType == null) {
            Logger.logWarn("Ignoring the plant at %s, its type '%s' is unknown".formatted(origin, storedType));
            return Optional.empty();
        }

        int age = resultSet.getInt("age");
        String track = resultSet.getString("track");
        Matrix3d transformation = Encoder.asTransformation(resultSet.getInt("transformation"));
        Optional<PlantStructure> structure = plantType.newStructure(origin, age, track, transformation);
        if (structure.isEmpty()) {
            // The track it was planted on is gone, or was shortened. Rebuilding it on another track of the
            // same type keeps the plant in the world and reachable; leaving it out would strand its blocks
            // there while the row stayed in the database.
            String fallback = fallbackTrack(plantType, age);
            if (fallback != null) {
                Logger.logWarn("Plant at %s was planted on the unusable track '%s', rebuilding it on '%s'"
                    .formatted(origin, track, fallback));
                structure = plantType.newStructure(origin, age, fallback, transformation);
                track = fallback;
            }
        }
        if (structure.isEmpty()) {
            Logger.logWarn("Ignoring the plant at %s, no track of '%s' has a growth stage %d"
                .formatted(origin, plantType.key(), age));
            return Optional.empty();
        }

        return Optional.of(new GardenPlant(
            Encoder.asUuid(resultSet.getBytes("id")),
            plantType,
            structure.get(),
            track,
            age,
            resultSet.getInt("fruits"),
            StorageSolution.SQLITE
        ));
    }

    /**
     * Picks the track to rebuild a plant on when the one it was planted on cannot be used.
     *
     * @return A track that reaches the given stage, or the longest one if none does
     */
    private static String fallbackTrack(PlantType plantType, int age) {
        String longest = null;
        int longestSize = -1;
        for (Map.Entry<String, ? extends List<?>> entry : plantType.structures().entrySet()) {
            int size = entry.getValue().size();
            if (size > age) {
                return entry.getKey();
            }
            if (size > longestSize) {
                longest = entry.getKey();
                longestSize = size;
            }
        }
        return longest;
    }

    private static int chunkOf(int blockCoordinate) {
        return blockCoordinate >> 4;
    }

    private void logFailure(String action, UUID plantId, SQLException e) {
        Logger.logErr("Failed to %s plant %s".formatted(action, plantId));
        Logger.logErr(e);
    }
}
