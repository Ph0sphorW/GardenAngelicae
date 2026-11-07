package org.icarus.angelicae.garden.persist;

import org.icarus.angelicae.garden.Garden;
import org.icarus.angelicae.garden.MutableGardenRegistry;
import org.icarus.angelicae.garden.plant.GardenPlant;
import org.icarus.angelicae.garden.plant.PlantType;
import org.icarus.angelicae.garden.structure.PlantStructure;
import org.icarus.angelicae.garden.utility.Encoder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.persistence.ListPersistentDataType;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix3d;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

public class PlantPdcType implements PersistentDataType<byte[], GardenPlant> {

    private static final PlantPdcType PLANT_TYPE = new PlantPdcType();

    /**
     * The plants of a chunk, as the bytes each one is stored as.
     * <p>
     * Deliberately the stored bytes rather than {@link GardenPlant} values: turning an entry back into a
     * plant places that plant's fruits into the world, so a chunk that is only being rewritten would grow a
     * second set of fruit next to the one that is really there. The tag is the same either way, which is why
     * the same chunk can still be read back as plants through {@link #deserialize(byte[], PersistentDataAdapterContext)}.
     */
    public static final ListPersistentDataType<byte[], byte[]> PLANT_LIST_TYPE = PersistentDataType.LIST.listTypeFrom(PersistentDataType.BYTE_ARRAY);
    public static final NamespacedKey PLANT_KEY = Garden.key("plant");

    private static final byte VERSION = 0;

    /** Stores the plant as the bytes the chunk keeps. */
    public static byte[] serialize(GardenPlant plant, PersistentDataAdapterContext context) {
        return PLANT_TYPE.toPrimitive(plant, context);
    }

    /** Builds the plant a stored entry holds. */
    public static GardenPlant deserialize(byte[] primitive, PersistentDataAdapterContext context) {
        return PLANT_TYPE.fromPrimitive(primitive, context);
    }

    /**
     * The id a stored entry was saved under, read from the bytes instead of from a rebuilt plant because
     * rebuilding is exactly what rewriting the list must not do.
     *
     * @return The id, or {@code null} for bytes this version did not write, which are left in place as they are
     */
    public static @Nullable UUID plantId(byte[] primitive) {
        if (primitive.length < 17) {
            return null;
        }
        // The version is written first, then the id as two longs
        return Encoder.asUuid(Arrays.copyOfRange(primitive, 1, 17));
    }

    @NotNull
    @Override
    public Class<byte[]> getPrimitiveType() {
        return byte[].class;
    }

    @NotNull
    @Override
    public Class<GardenPlant> getComplexType() {
        return GardenPlant.class;
    }

    @Override
    public @NonNull byte[] toPrimitive(@NonNull GardenPlant complex, @NotNull PersistentDataAdapterContext context) {

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream(); DataOutputStream dataOutputStream = new DataOutputStream(outputStream)) {
            dataOutputStream.writeByte(VERSION);
            PlantStructure structure = complex.getStructure();
            Location origin = structure.origin();
            dataOutputStream.write(Encoder.asBytes(complex.getId()));
            dataOutputStream.write(Encoder.asBytes(origin.getWorld().getUID()));
            dataOutputStream.writeInt(origin.getBlockX());
            dataOutputStream.writeInt(origin.getBlockY());
            dataOutputStream.writeInt(origin.getBlockZ());
            dataOutputStream.writeInt(Encoder.asInteger(structure.transformation()));
            dataOutputStream.writeUTF(Garden.minimized(complex.getType().key()));
            dataOutputStream.writeUTF(complex.getTrack());
            dataOutputStream.writeInt(complex.getAge());
            dataOutputStream.writeInt(complex.getExpectedFruits());
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public @NonNull GardenPlant fromPrimitive(@NonNull byte[] primitive, @NotNull PersistentDataAdapterContext context) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(primitive); DataInputStream dataInputStream = new DataInputStream(inputStream)) {
            byte version = dataInputStream.readByte();
            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported version");
            }
            UUID id = Encoder.asUuid(dataInputStream.readNBytes(16));
            UUID worldUuid = Encoder.asUuid(dataInputStream.readNBytes(16));
            World world = Bukkit.getWorld(worldUuid);
            if (world == null) {
                throw new IllegalArgumentException("Unknown world!");
            }
            int x = dataInputStream.readInt();
            int y = dataInputStream.readInt();
            int z = dataInputStream.readInt();
            Location origin = new Location(world, x, y, z);
            Matrix3d transformation = Encoder.asTransformation(dataInputStream.readInt());
            NamespacedKey type = Garden.key(dataInputStream.readUTF());
            PlantType plantType = MutableGardenRegistry.PLANT_TYPE.get(type);
            if (plantType == null) {
                throw new IllegalArgumentException("Removed plant type");
            }
            String track = dataInputStream.readUTF();
            int age = dataInputStream.readInt();
            int fruits = dataInputStream.readInt();
            PlantStructure structure = plantType.newStructure(origin, age, track, transformation)
                    .orElseThrow(() -> new IllegalArgumentException("Can't find matching structure"));
            return new GardenPlant(id, plantType, structure, track, age, fruits, StorageSolution.PDC);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
