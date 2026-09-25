package org.icarus.angelicae.garden.structure;

import com.google.common.collect.ImmutableList;
import dev.thorinwasher.schem.Schematic;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.joml.Matrix3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockbukkit.mockbukkit.MockBukkitExtension;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the invariant {@link StructureGeometry} has to keep: walking it must visit exactly the blocks that
 * applying the schematic directly visits, at the same places, in the same order and with the same states.
 * <p>
 * Its whole reason to exist is that {@code apply} is too slow to call on every lookup, so the two are
 * expected to agree exactly rather than roughly.
 * <p>
 * The schematics are built here rather than read from the resources because the bundled ones name block
 * states the test environment cannot parse; a hand-made one still covers everything that can go wrong, which
 * is the two offsets (the schematic's own and the one the structure is centred with), skipping air, the
 * rotation of directional blocks, and the order the blocks come back in.
 * <p>
 * The test environment has no implementation of {@code Leaves} either, so that the plugin turns leaves into
 * non-decaying ones is checked on a running server rather than here.
 */
@ExtendWith(MockBukkitExtension.class)
class StructureGeometryTest {

    private static final BlockData AIR = Material.AIR.createBlockData();
    private static final BlockData STONE = Material.STONE.createBlockData();
    private static final BlockData LOG = Material.OAK_LOG.createBlockData();
    private static final BlockData LEAVES = Material.OAK_LEAVES.createBlockData();

    @Test
    void walksTheSameBlocksAsApplyingTheSchematicDirectly() {
        for (Schematic schematic : schematics()) {
            for (Matrix3d transformation : transformations()) {
                Vector3i size = schematic.size(transformation);
                Vector3i offset = new Vector3i(size.x() / 2, 0, size.z() / 2);

                List<String> direct = new ArrayList<>();
                schematic.apply(transformation, (position, state) -> {
                    if (state.getMaterial().isAir()) {
                        return;
                    }
                    if (state instanceof Leaves leaves) {
                        leaves.setPersistent(false);
                    }
                    position.sub(offset);
                    direct.add(describe(position.x, position.y, position.z, state));
                });

                StructureGeometry geometry = StructureGeometry.of(schematic, transformation, offset);
                assertSame(geometry, StructureGeometry.of(schematic, transformation, offset),
                    "the geometry of one rotation should only be built once");

                List<String> walked = new ArrayList<>();
                geometry.forEach(0, 0, 0, (x, y, z, state) -> {
                    walked.add(describe(x, y, z, state));
                    return true;
                });

                assertFalse(direct.isEmpty(), "the test schematic should not be all air");
                assertEquals(direct, walked, "mismatch for " + transformation);
                assertEquals(direct.size(), geometry.count());
            }
        }
    }

    @Test
    void offsetsTheBlocksByTheOriginItIsWalkedWith() {
        Schematic schematic = schematics().get(0);
        Matrix3d transformation = new Matrix3d();
        Vector3i size = schematic.size(transformation);
        StructureGeometry geometry =
            StructureGeometry.of(schematic, transformation, new Vector3i(size.x() / 2, 0, size.z() / 2));

        List<String> atOrigin = new ArrayList<>();
        geometry.forEach(0, 0, 0, (x, y, z, state) -> {
            atOrigin.add(x + "," + y + "," + z);
            return true;
        });

        List<String> moved = new ArrayList<>();
        geometry.forEach(100, -20, 7, (x, y, z, state) -> {
            moved.add((x - 100) + "," + (y + 20) + "," + (z - 7));
            return true;
        });

        assertEquals(atOrigin, moved);
    }

    @Test
    void countsOnlyTheBlocksTheFilterAccepts() {
        Schematic schematic = schematics().get(0);
        for (Matrix3d transformation : transformations()) {
            Vector3i size = schematic.size(transformation);
            StructureGeometry geometry =
                StructureGeometry.of(schematic, transformation, new Vector3i(size.x() / 2, 0, size.z() / 2));

            List<String> filtered = new ArrayList<>();
            geometry.forEach(0, 0, 0,
                state -> !state.getMaterial().isAir(),
                (x, y, z, state) -> {
                    filtered.add(describe(x, y, z, state));
                    return true;
                });

            List<String> all = new ArrayList<>();
            geometry.forEach(0, 0, 0, (x, y, z, state) -> {
                if (!state.getMaterial().isAir()) {
                    all.add(describe(x, y, z, state));
                }
                return true;
            });

            assertEquals(all, filtered, "mismatch for " + transformation);
        }
    }

    @Test
    void stopsWhenTheVisitorSaysSo() {
        Schematic schematic = schematics().get(0);
        Matrix3d transformation = new Matrix3d();
        Vector3i size = schematic.size(transformation);
        StructureGeometry geometry =
            StructureGeometry.of(schematic, transformation, new Vector3i(size.x() / 2, 0, size.z() / 2));

        int[] filteredVisits = {0};
        geometry.forEach(0, 0, 0, state -> false, (x, y, z, state) -> {
            filteredVisits[0]++;
            return true;
        });
        assertEquals(0, filteredVisits[0], "a filter that rejects everything should visit nothing");

        int[] visits = {0};
        geometry.forEach(0, 0, 0, (x, y, z, state) -> {
            visits[0]++;
            return false;
        });
        assertEquals(1, visits[0], "returning false should end the walk after the first block");
    }

    @Test
    void neverWalksAir() {
        StructureGeometry geometry = StructureGeometry.of(
            schematics().get(0), new Matrix3d(), new Vector3i());

        int[] visited = {0};
        geometry.forEach(0, 0, 0, (x, y, z, state) -> {
            visited[0]++;
            assertFalse(state.getMaterial().isAir(), "air should never be walked");
            return true;
        });

        assertTrue(visited[0] > 0, "the test schematic should not be all air");
        assertTrue(visited[0] < 27, "the test schematic should contain some air to leave out");
    }

    private static String describe(int x, int y, int z, BlockData state) {
        return x + "," + y + "," + z + "=" + state.getAsString();
    }

    /**
     * Two schematics of the same shape, one of them carrying a non-zero offset of its own. That offset is
     * added inside {@code apply} and then taken off again by the structure, so getting the two mixed up is
     * exactly the kind of mistake this has to catch.
     */
    private static List<Schematic> schematics() {
        List<BlockData> palette = List.of(AIR, STONE, LOG, LEAVES);
        Vector3i size = new Vector3i(3, 3, 3);
        int[][][] indices = new int[size.y()][size.z()][size.x()];
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) {
                    indices[y][z][x] = (x + 2 * z + 3 * y) % palette.size();
                }
            }
        }
        return ImmutableList.of(
            schematic(size, new Vector3i(), palette, indices),
            schematic(size, new Vector3i(1, -1, 2), palette, indices));
    }

    private static Schematic schematic(Vector3i size, Vector3i offset, List<BlockData> palette,
                                       int[][][] indices) {
        // The reader walks y, then z, then x, so the block indices are written in that order
        ByteArrayOutputStream blocks = new ByteArrayOutputStream();
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) {
                    writeVarInt(blocks, indices[y][z][x]);
                }
            }
        }
        return new Schematic(size, offset, palette.toArray(BlockData[]::new), blocks.toByteArray());
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /** The rotations a plant is stored with. */
    private static List<Matrix3d> transformations() {
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
        double[] values = input.get(new double[9]);
        for (int i = 0; i < values.length; i++) {
            values[i] = Math.round(values[i]);
        }
        return new Matrix3d(values[0], values[1], values[2], values[3], values[4], values[5], values[6],
            values[7], values[8]);
    }
}
