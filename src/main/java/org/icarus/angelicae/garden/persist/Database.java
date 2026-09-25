package org.icarus.angelicae.garden.persist;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.icarus.angelicae.garden.utility.Encoder;
import org.icarus.angelicae.garden.utility.FileUtil;
import org.icarus.angelicae.garden.utility.Logger;
import org.joml.Matrix3d;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Database {

    /** Schema this build expects. Bumped whenever the table definitions change. */
    private static final int DATABASE_VERSION = 2;

    /** What {@link #readVersion} reports for a database that has no version recorded yet. */
    private static final int UNKNOWN_VERSION = -1;

    private HikariDataSource hikariDataSource;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public Database() {

    }

    public void init(File dataFolder) throws IOException, SQLException {
        HikariConfig config = getHikariConfigForSqlite(dataFolder);
        config.setConnectionInitSql("PRAGMA foreign_keys = ON;");
        this.hikariDataSource = new HikariDataSource(config);
        executeMultiple("/sql/create_all_tables.sql");
        try (Connection connection = getConnection()) {
            int version = readVersion(connection);
            if (version > DATABASE_VERSION) {
                throw new IllegalStateException("Can not downgrade the plugin!");
            }
            if (version < DATABASE_VERSION) {
                migrate(connection, version);
                updateVersion(connection);
            }
            // Created here rather than in the table definition, because on an existing database the columns
            // it covers are only added by the migration above, and an index over a missing column is an error.
            ensureChunkIndex(connection);
        }
    }

    private void ensureChunkIndex(Connection connection) throws SQLException {
        execute(connection, """
            CREATE INDEX IF NOT EXISTS chunk_index
            ON plants(world_uuid, chunk_x, chunk_z);
            """);
    }

    private int readVersion(Connection connection) throws SQLException {
        try (PreparedStatement preparedStatement =
                 connection.prepareStatement(FileUtil.readInternalResource("/sql/get_version.sql"))) {
            ResultSet resultSet = preparedStatement.executeQuery();
            return resultSet.next() ? resultSet.getInt("version") : UNKNOWN_VERSION;
        }
    }

    private void updateVersion(Connection connection) throws SQLException {
        try (PreparedStatement preparedStatement =
                 connection.prepareStatement(FileUtil.readInternalResource("/sql/set_version.sql"))) {
            preparedStatement.setInt(1, DATABASE_VERSION);
            preparedStatement.execute();
        }
    }

    /**
     * Brings an existing table up to the current schema.
     * <p>
     * What runs is decided by the columns that are actually there rather than by the recorded version. A
     * database written before the version table existed reports no version at all, so the schema is the only
     * reliable thing to go by, and every step here is safe to run again.
     */
    private void migrate(Connection connection, int fromVersion) throws SQLException {
        Map<String, String> columns = columnTypes(connection, "plants");
        Logger.log("Upgrading the plant table (recorded version: %d)".formatted(fromVersion));

        // The number of growable fruits used to be part of the plant data blob
        if (!columns.containsKey("fruits")) {
            execute(connection, "ALTER TABLE plants ADD COLUMN fruits INTEGER;");
        }
        // Plants are only ever looked up by the chunk they stand in, which was not recorded at all before
        if (!columns.containsKey("chunk_x") || !columns.containsKey("chunk_z")) {
            execute(connection, "ALTER TABLE plants ADD COLUMN chunk_x INTEGER;");
            execute(connection, "ALTER TABLE plants ADD COLUMN chunk_z INTEGER;");
        }
        execute(connection, """
            UPDATE plants
            SET chunk_x = origin_x >> 4, chunk_z = origin_z >> 4
            WHERE chunk_x IS NULL OR chunk_z IS NULL;
            """);

        // The transformation used to be nine JSON doubles per row. It packs into a single integer, which is
        // what the chunk-storage format has always used. The declared column type is part of that: a TEXT
        // column would go on storing the number as text, so the table is rebuilt when the declaration is wrong.
        if (!"INTEGER".equalsIgnoreCase(columns.getOrDefault("transformation", ""))) {
            repackTransformations(connection);
        }
    }

    /**
     * Rebuilds the plant table with the packed transformation format.
     * <p>
     * SQLite cannot change a column's type in place, so the rows are read out, converted and written into a
     * replacement table that is swapped in afterwards. This runs in one transaction: either every row converts
     * or none of them do.
     */
    private void repackTransformations(Connection connection) throws SQLException {
        List<Object[]> rows = readAllPlants(connection);
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                CREATE TABLE plants_repacked (
                    id BINARY(16),
                    plant_type VARCHAR(32),
                    age INTEGER,
                    origin_x INTEGER,
                    origin_y INTEGER,
                    origin_z INTEGER,
                    chunk_x INTEGER,
                    chunk_z INTEGER,
                    world_uuid BINARY(16),
                    transformation INTEGER,
                    track TEXT,
                    fruits INTEGER,
                    PRIMARY KEY (id)
                );
                """);
            try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO plants_repacked VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (Object[] row : rows) {
                    for (int i = 0; i < row.length; i++) {
                        insert.setObject(i + 1, row[i]);
                    }
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            execute(connection, "DROP TABLE plants;");
            execute(connection, "ALTER TABLE plants_repacked RENAME TO plants;");
            connection.commit();
            Logger.log("Repacked %d plant transformation(s)".formatted(rows.size()));
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * Reads every plant into the shape the current table expects, converting both the transformation and the
     * chunk coordinates on the way.
     */
    private List<Object[]> readAllPlants(Connection connection) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM plants")) {
            Map<String, Integer> columns = columnIndexes(resultSet);
            while (resultSet.next()) {
                int originX = intAt(resultSet, columns, "origin_x");
                int originZ = intAt(resultSet, columns, "origin_z");
                rows.add(new Object[]{
                    bytesAt(resultSet, columns, "id"),
                    textAt(resultSet, columns, "plant_type"),
                    intAt(resultSet, columns, "age"),
                    originX,
                    intAt(resultSet, columns, "origin_y"),
                    originZ,
                    originX >> 4,
                    originZ >> 4,
                    bytesAt(resultSet, columns, "world_uuid"),
                    packedTransformation(resultSet, columns),
                    textAt(resultSet, columns, "track"),
                    intAt(resultSet, columns, "fruits")
                });
            }
        }
        return rows;
    }

    /**
     * Reads the transformation of one row, accepting both the old JSON text and the packed integer a row may
     * already have been written with.
     */
    private int packedTransformation(ResultSet resultSet, Map<String, Integer> columns) throws SQLException {
        Integer index = columns.get("transformation");
        String stored = index == null ? null : resultSet.getString(index);
        if (stored == null) {
            return Encoder.asInteger(new Matrix3d());
        }
        try {
            return Integer.parseInt(stored.trim());
        } catch (NumberFormatException ignored) {
            // Written before the packed format existed, so it is the old JSON text
        }
        try {
            return Encoder.asInteger(Encoder.deserializeTransformation(stored));
        } catch (RuntimeException e) {
            Logger.logWarn("Could not read a plant transformation, falling back to no rotation: " + stored);
            return Encoder.asInteger(new Matrix3d());
        }
    }

    private static Map<String, Integer> columnIndexes(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            columns.put(metaData.getColumnName(i).toLowerCase(Locale.ROOT), i);
        }
        return columns;
    }

    private static int intAt(ResultSet resultSet, Map<String, Integer> columns, String name) throws SQLException {
        Integer index = columns.get(name);
        return index == null ? 0 : resultSet.getInt(index);
    }

    private static String textAt(ResultSet resultSet, Map<String, Integer> columns, String name) throws SQLException {
        Integer index = columns.get(name);
        return index == null ? null : resultSet.getString(index);
    }

    private static byte[] bytesAt(ResultSet resultSet, Map<String, Integer> columns, String name) throws SQLException {
        Integer index = columns.get(name);
        return index == null ? null : resultSet.getBytes(index);
    }

    /**
     * @return The declared type of every column of the given table, keyed by the lower case column name
     */
    private Map<String, String> columnTypes(Connection connection, String table) throws SQLException {
        Map<String, String> columns = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                columns.put(resultSet.getString("name").toLowerCase(Locale.ROOT), resultSet.getString("type"));
            }
        }
        return columns;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    public Connection getConnection() throws SQLException {
        return hikariDataSource.getConnection();
    }

    private static @NonNull HikariConfig getHikariConfigForSqlite(File dataFolder) throws IOException {
        File databaseFile = new File(dataFolder, "garden.db");
        if (!databaseFile.exists() && !databaseFile.getParentFile().mkdirs() && !databaseFile.createNewFile()) {
            throw new IOException("Could not create file or dirs");
        }
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("SQLiteConnectionPool");
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + databaseFile);
        return hikariConfig;
    }

    public void close() {
        executor.shutdown();
        if (hikariDataSource != null) {
            hikariDataSource.close();
        }
    }

    private void executeMultiple(String resourceString) throws SQLException {
        try (Connection connection = hikariDataSource.getConnection()) {
            for (String statement : FileUtil.readInternalResource(resourceString).split(";")) {
                if (!statement.isBlank()) {
                    connection.prepareStatement(statement + ";").execute();
                }
            }
        }
    }

    public GardenPlantDataType plantDataType() {
        return new GardenPlantDataType(this, executor);
    }
}
