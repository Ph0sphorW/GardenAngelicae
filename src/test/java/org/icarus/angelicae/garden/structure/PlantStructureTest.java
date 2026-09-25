package org.icarus.angelicae.garden.structure;

import com.google.common.collect.ImmutableList;
import dev.thorinwasher.schem.Schematic;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.joml.Matrix3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockbukkit.mockbukkit.MockBukkitExtension;
import org.mockbukkit.mockbukkit.MockBukkitInject;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the reads {@link PlantStructure} adds on top of the geometry: which blocks it would place, whether
 * they fit, and whether it still stands.
 * <p>
 * Placing and removing are left to the server, because they report the blocks to the plugin and go through
 * the region scheduler.
 */
@ExtendWith(MockBukkitExtension.class)
class PlantStructureTest {

    private static final BlockData AIR = Material.AIR.createBlockData();
    private static final BlockData STONE = Material.STONE.createBlockData();
    private static final BlockData LEAVES = Material.OAK_LEAVES.createBlockData();

    @MockBukkitInject
    ServerMock serverMock;

    @Test
    void reportsTheBlocksItWouldPlaceAndOnlyThoseTheFilterAccepts() {
        WorldMock world = serverMock.addSimpleWorld("world");
        PlantStructure structure = structureAt(world, 10, 100, 10);

        List<Block> all = structure.blocks(state -> true);
        assertEquals(3, all.size());
        assertEquals(
            List.of("10,100,10", "11,100,10", "10,101,10"),
            all.stream().map(block -> block.getX() + "," + block.getY() + "," + block.getZ()).toList(),
            "the blocks should sit where the schematic puts them, measured from the origin");

        List<Block> stone = structure.blocks(state -> state.getMaterial() == Material.STONE);
        assertEquals(2, stone.size());
        assertTrue(stone.stream().allMatch(block -> block.getType().isAir()),
            "nothing has been placed, so the positions are still empty until pasteNow runs");
    }

    @Test
    void fitsWhileTheBlocksItNeedsAreFree() {
        WorldMock world = serverMock.addSimpleWorld("world");
        PlantStructure structure = structureAt(world, 10, 100, 10);
        // Air and replaceable blocks such as grass are fine to build over
        for (Block block : structure.blocks(state -> true)) {
            block.setType(Material.SHORT_GRASS);
        }
        assertTrue(structure.fits(state -> state.getMaterial() == Material.STONE));

        Block solid = structure.blocks(state -> true).get(0);
        solid.setType(Material.STONE);
        assertFalse(structure.fits(state -> state.getMaterial() == Material.STONE),
            "a solid block in the way should stop the structure from fitting");
    }

    @Test
    void reportsWhetherAnyLeafItPlacedIsStillThere() {
        WorldMock world = serverMock.addSimpleWorld("world");
        PlantStructure structure = structureAt(world, 10, 100, 10);

        assertFalse(structure.hasLeaves(), "nothing has been placed yet");

        // Whichever block carries the leaves, standing it back up has to be picked up
        Block leaves = structure.blocks(state -> state.getMaterial() == Material.OAK_LEAVES).get(0);
        leaves.setType(Material.OAK_LEAVES);
        assertTrue(structure.hasLeaves());

        leaves.setType(Material.AIR);
        assertFalse(structure.hasLeaves());
    }

    @Test
    void findsNoWorldOnceItIsGone() {
        WorldMock world = serverMock.addSimpleWorld("world");
        PlantStructure placed = structureAt(world, 10, 100, 10);
        assertEquals(world, placed.world());

        // Same structure, but in a world the server does not have
        PlantStructure gone = new PlantStructure(placed.geometry(), 10, 100, 10, new Matrix3d(),
            UUID.randomUUID());
        assertNull(gone.world());
        assertEquals(List.of(), gone.blocks(state -> true));
        assertFalse(gone.fits(state -> true));
        assertFalse(gone.hasLeaves());
    }

    /**
     * A structure of three blocks: two stone, one above the other, and a leaf beside the lower one.
     */
    private static PlantStructure structureAt(World world, int x, int y, int z) {
        List<BlockData> palette = List.of(AIR, STONE, LEAVES);
        Vector3i size = new Vector3i(2, 2, 1);
        // Reader order is y, then z, then x: (0,0,0)=stone, (1,0,0)=stone, (0,1,0)=leaves, (1,1,0)=air
        int[][][] indices = {{{1, 1}}, {{2, 0}}};
        Schematic schematic = new Schematic(size, new Vector3i(), palette.toArray(BlockData[]::new),
            blocks(size, indices));
        StructureGeometry geometry =
            StructureGeometry.of(schematic, new Matrix3d(), new Vector3i());
        return new PlantStructure(geometry, x, y, z, new Matrix3d(), world.getUID());
    }

    private static byte[] blocks(Vector3i size, int[][][] indices) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) {
                    int value = indices[y][z][x];
                    while ((value & ~0x7F) != 0) {
                        out.write((value & 0x7F) | 0x80);
                        value >>>= 7;
                    }
                    out.write(value);
                }
            }
        }
        return out.toByteArray();
    }
}
