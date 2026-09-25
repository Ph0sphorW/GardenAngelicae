package org.icarus.angelicae.garden.structure;

import dev.thorinwasher.schem.Schematic;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.joml.Matrix3d;
import org.joml.Vector3i;
import org.jspecify.annotations.NullMarked;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The world-independent half of a plant structure: where each of its blocks sits relative to the plant's
 * origin, and which block state belongs there.
 * <p>
 * This exists because {@link Schematic#apply} is expensive and stateless but was being called for every
 * lookup. Every call re-reads the whole block index and re-rotates every palette entry, which for the bundled
 * structures means a few hundred {@link BlockData#clone()} calls and several hundred short-lived objects,
 * while a plant only ever uses one of eight rotations. Structures of the same type, stage and rotation all
 * share one instance of this, built the first time one of them needs it.
 * <p>
 * Air is left out: none of the callers ever did anything with it.
 */
@NullMarked
public final class StructureGeometry {

    /**
     * Receives one block of a structure, in world coordinates.
     */
    @FunctionalInterface
    public interface BlockVisitor {
        /**
         * @return {@code false} to stop the walk, {@code true} to continue with the next block
         */
        boolean visit(int x, int y, int z, BlockData state);
    }

    private static final Map<Key, StructureGeometry> CACHE = new ConcurrentHashMap<>();

    private final int[] x;
    private final int[] y;
    private final int[] z;
    private final BlockData[] states;

    private StructureGeometry(int[] x, int[] y, int[] z, BlockData[] states) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.states = states;
    }

    /**
     * Returns the geometry of one rotation of a schematic, building it on first use.
     *
     * @param offset The offset the caller places the structure with, which the block positions are relative to
     */
    public static StructureGeometry of(Schematic schematic, Matrix3d transformation, Vector3i offset) {
        return CACHE.computeIfAbsent(new Key(schematic, transformation, offset.x, offset.y, offset.z),
            key -> build(schematic, transformation, offset));
    }

    /**
     * Drops everything built so far.
     * <p>
     * Needed when the plant types are read again: that reads the schematic files into fresh objects, so their
     * entries would otherwise never be reachable again while still counting towards the cache.
     */
    public static void invalidate() {
        CACHE.clear();
    }

    private static StructureGeometry build(Schematic schematic, Matrix3d transformation, Vector3i offset) {
        Builder builder = new Builder();
        schematic.apply(transformation, (position, state) -> {
            if (state.getMaterial().isAir()) {
                return;
            }
            if (state instanceof Leaves leaves) {
                // Shared from here on, and always wanted: these structures are placed by the plugin, so the
                // leaves they carry must not decay just because no log is next to them.
                leaves.setPersistent(false);
            }
            position.sub(offset);
            builder.add(position.x, position.y, position.z, state);
        });
        return builder.build();
    }

    public int count() {
        return x.length;
    }

    /** Walks every block of the structure. */
    public void forEach(int originX, int originY, int originZ, BlockVisitor visitor) {
        for (int i = 0; i < x.length; i++) {
            if (!visitor.visit(originX + x[i], originY + y[i], originZ + z[i], states[i])) {
                return;
            }
        }
    }

    /** Walks every block of the structure that the filter accepts. */
    public void forEach(int originX, int originY, int originZ, Predicate<BlockData> filter, BlockVisitor visitor) {
        for (int i = 0; i < x.length; i++) {
            if (!filter.test(states[i])) {
                continue;
            }
            if (!visitor.visit(originX + x[i], originY + y[i], originZ + z[i], states[i])) {
                return;
            }
        }
    }

    /**
     * Cache key of one rotation.
     * <p>
     * The schematic is compared by identity and hashed by identity: a record's own {@code hashCode} walks the
     * whole palette and block array, which is exactly the cost this class exists to avoid. Two separately read
     * copies of the same file simply get two entries, which costs one extra build and no correctness.
     */
    private record Key(Schematic schematic, Matrix3d transformation, int offsetX, int offsetY, int offsetZ) {

        @Override
        public int hashCode() {
            return System.identityHashCode(schematic) * 31
                + Arrays.hashCode(transformation.get(new double[9]))
                + offsetX * 31 + offsetY * 31 + offsetZ;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                && key.schematic == schematic
                && key.offsetX == offsetX
                && key.offsetY == offsetY
                && key.offsetZ == offsetZ
                && key.transformation.equals(transformation);
        }
    }

    /** Collects the positions before freezing them, since the amount of blocks is not known up front. */
    private static final class Builder {

        private int[] x = new int[32];
        private int[] y = new int[32];
        private int[] z = new int[32];
        private BlockData[] states = new BlockData[32];
        private int size;

        void add(int x, int y, int z, BlockData state) {
            if (size == this.x.length) {
                int capacity = size * 2;
                this.x = Arrays.copyOf(this.x, capacity);
                this.y = Arrays.copyOf(this.y, capacity);
                this.z = Arrays.copyOf(this.z, capacity);
                this.states = Arrays.copyOf(this.states, capacity);
            }
            this.x[size] = x;
            this.y[size] = y;
            this.z[size] = z;
            this.states[size] = state;
            size++;
        }

        StructureGeometry build() {
            return new StructureGeometry(
                Arrays.copyOf(x, size),
                Arrays.copyOf(y, size),
                Arrays.copyOf(z, size),
                Arrays.copyOf(states, size));
        }
    }
}
