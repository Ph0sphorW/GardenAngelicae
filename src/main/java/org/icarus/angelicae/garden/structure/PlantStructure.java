package org.icarus.angelicae.garden.structure;

import org.icarus.angelicae.garden.Garden;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.joml.Matrix3d;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * One plant's copy of a schematic, placed at an origin and rotated.
 * <p>
 * The blocks come from a shared {@link StructureGeometry}, so walking a plant never re-parses the schematic.
 * Reads go straight to the world from whatever thread calls them; pasting and removing are handed to the
 * region scheduler, because they write to it.
 */
@NullMarked
public record PlantStructure(StructureGeometry geometry, int originX, int originY, int originZ,
                             Matrix3d transformation, UUID worldUuid) {

    /** The world this structure was placed in, or {@code null} if it is not loaded. */
    public @Nullable World world() {
        return Bukkit.getWorld(worldUuid);
    }

    public Location origin() {
        return new Location(Bukkit.getWorld(worldUuid), originX, originY, originZ);
    }

    /** Walks every block of the structure. */
    public void forEachBlock(StructureGeometry.BlockVisitor visitor) {
        geometry.forEach(originX, originY, originZ, visitor);
    }

    /** Walks the blocks whose state the filter accepts. */
    public void forEachBlock(Predicate<BlockData> filter, StructureGeometry.BlockVisitor visitor) {
        geometry.forEach(originX, originY, originZ, filter, visitor);
    }

    /** Collects the blocks whose state the filter accepts. */
    public List<Block> blocks(Predicate<BlockData> filter) {
        World world = world();
        if (world == null) {
            return List.of();
        }
        List<Block> output = new ArrayList<>();
        geometry.forEach(originX, originY, originZ, filter, (x, y, z, state) -> {
            output.add(world.getBlockAt(x, y, z));
            return true;
        });
        return output;
    }

    /**
     * Whether every block the structure would place over a cell the filter accepts is free.
     * <p>
     * Lets a plant find out if it can grow into its next shape before the current one is taken down.
     */
    public boolean fits(Predicate<BlockData> filter) {
        World world = world();
        if (world == null) {
            return false;
        }
        boolean[] free = {true};
        geometry.forEach(originX, originY, originZ, filter, (x, y, z, state) -> {
            Material material = world.getBlockAt(x, y, z).getType();
            if (material.isAir() || Tag.REPLACEABLE_BY_TREES.isTagged(material)) {
                return true;
            }
            free[0] = false;
            return false;
        });
        return free[0];
    }

    /** Whether any block the structure placed is still a leaf block in the world. */
    public boolean hasLeaves() {
        World world = world();
        if (world == null) {
            return false;
        }
        boolean[] found = {false};
        geometry.forEach(originX, originY, originZ, (x, y, z, state) -> {
            if (!Tag.LEAVES.isTagged(world.getBlockAt(x, y, z).getType())) {
                return true;
            }
            found[0] = true;
            return false;
        });
        return found[0];
    }

    public CompletableFuture<Void> paste() {
        CompletableFuture<Void> output = new CompletableFuture<>();
        Bukkit.getRegionScheduler().run(Garden.getInstance(), origin(), t -> {
            pasteNow();
            output.complete(null);
        });
        return output;
    }

    /** Places the structure, returning the states it replaced so the caller can put them back. */
    public List<BlockState> pasteNow() {
        World world = world();
        if (world == null) {
            return List.of();
        }
        List<BlockState> output = new ArrayList<>();
        geometry.forEach(originX, originY, originZ, (x, y, z, state) -> {
            Block block = world.getBlockAt(x, y, z);
            Material replaced = block.getType();
            if (!replaced.isAir() && !Tag.REPLACEABLE_BY_TREES.isTagged(replaced)) {
                return true;
            }
            output.add(block.getState());
            block.setBlockData(state);
            Garden.getInstance().getBlockUtil().disableItemDrops(block);
            return true;
        });
        return output;
    }

    public CompletableFuture<Void> remove() {
        if (world() == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> output = new CompletableFuture<>();
        Bukkit.getRegionScheduler().run(Garden.getInstance(), origin(), ignored -> {
            World world = world();
            if (world == null) {
                output.complete(null);
                return;
            }
            geometry.forEach(originX, originY, originZ, (x, y, z, state) -> {
                Block block = world.getBlockAt(x, y, z);
                // Only clear what is still ours, so a structure a player has since changed does not take
                // unrelated blocks with it.
                if (block.getType() == state.getMaterial()) {
                    block.setType(Material.AIR);
                }
                Garden.getInstance().getBlockUtil().enableItemDrops(block);
                return true;
            });
            output.complete(null);
        });
        return output;
    }
}
