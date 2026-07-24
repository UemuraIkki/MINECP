package dev.minecp.observation;

import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Incremental radius-16 sampler. It checks at most 1,024 block positions per
 * server tick instead of synchronously scanning all 35,937 positions for each
 * observation. Cached positions are revalidated as the cursor reaches them and
 * are discarded immediately when outside the live radius.
 */
public final class NearbyScanner {
    public record PointOfInterest(String kind, String id, BlockPos pos, double distance) {
    }

    public record NearbyEntities(List<Hostile> hostiles, int villagers) {
    }

    public record Hostile(String type, double distance, Vec3d pos) {
    }

    private static final int RADIUS = 16;
    private static final int DIAMETER = RADIUS * 2 + 1;
    private static final int TOTAL_POSITIONS = DIAMETER * DIAMETER * DIAMETER;
    private static final int SAMPLES_PER_TICK = 1024;

    private final Map<Long, CachedPoi> cached = new HashMap<>();
    private BlockPos center;
    private int cursor;

    public void reset() {
        cached.clear();
        center = null;
        cursor = 0;
    }

    public void tick(ServerPlayerEntity player) {
        BlockPos newCenter = player.getBlockPos();
        if (center == null || center.getSquaredDistance(newCenter) > 4.0) {
            center = newCenter.toImmutable();
            cursor = 0;
            cached.entrySet().removeIf(entry -> !withinRadius(BlockPos.fromLong(entry.getKey()), center));
        }

        ServerWorld world = player.getServerWorld();
        for (int sample = 0; sample < SAMPLES_PER_TICK; sample++) {
            int index = cursor++;
            if (cursor >= TOTAL_POSITIONS) {
                cursor = 0;
            }

            int dx = index % DIAMETER - RADIUS;
            int remaining = index / DIAMETER;
            int dz = remaining % DIAMETER - RADIUS;
            int dy = remaining / DIAMETER - RADIUS;
            BlockPos pos = center.add(dx, dy, dz);
            if (pos.getY() < world.getBottomY() || pos.getY() >= world.getTopY() || !world.isChunkLoaded(pos)) {
                cached.remove(pos.asLong());
                continue;
            }

            CachedPoi poi = classify(world.getBlockState(pos));
            if (poi == null) {
                cached.remove(pos.asLong());
            } else {
                cached.put(pos.asLong(), poi);
            }
        }
    }

    public List<PointOfInterest> pointsOfInterest(ServerPlayerEntity player) {
        BlockPos liveCenter = player.getBlockPos();
        List<PointOfInterest> result = new ArrayList<>();
        cached.forEach((packed, poi) -> {
            BlockPos pos = BlockPos.fromLong(packed);
            if (withinRadius(pos, liveCenter)) {
                result.add(new PointOfInterest(
                        poi.kind,
                        poi.id,
                        pos,
                        Math.sqrt(pos.getSquaredDistance(player.getPos()))
                ));
            }
        });
        result.sort(Comparator.comparingDouble(PointOfInterest::distance));
        return result;
    }

    public NearbyEntities nearbyEntities(ServerPlayerEntity player) {
        Box box = player.getBoundingBox().expand(RADIUS);
        ServerWorld world = player.getServerWorld();
        List<Hostile> hostiles = world.getEntitiesByClass(
                        HostileEntity.class,
                        box,
                        Entity::isAlive
                ).stream()
                .map(entity -> new Hostile(
                        Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                        entity.distanceTo(player),
                        entity.getPos()
                ))
                .sorted(Comparator.comparingDouble(Hostile::distance))
                .toList();
        int villagers = world.getEntitiesByClass(VillagerEntity.class, box, Entity::isAlive).size();
        return new NearbyEntities(hostiles, villagers);
    }

    private static boolean withinRadius(BlockPos pos, BlockPos center) {
        return Math.abs(pos.getX() - center.getX()) <= RADIUS
                && Math.abs(pos.getY() - center.getY()) <= RADIUS
                && Math.abs(pos.getZ() - center.getZ()) <= RADIUS;
    }

    private static CachedPoi classify(BlockState state) {
        if (state.getFluidState().isIn(FluidTags.LAVA)) {
            return new CachedPoi("lava", "minecraft:lava");
        }
        if (state.getFluidState().isIn(FluidTags.WATER)) {
            return new CachedPoi("water", "minecraft:water");
        }

        Block block = state.getBlock();
        Identifier id = Registries.BLOCK.getId(block);
        String path = id.getPath();
        if (path.endsWith("_ore") || block == Blocks.ANCIENT_DEBRIS) {
            return new CachedPoi("ore", id.toString());
        }
        if (block == Blocks.NETHER_PORTAL) {
            return new CachedPoi("nether_portal", id.toString());
        }
        if (block == Blocks.END_PORTAL_FRAME) {
            return new CachedPoi("end_portal_frame", id.toString());
        }
        if (block == Blocks.STONE_BRICKS
                || block == Blocks.MOSSY_STONE_BRICKS
                || block == Blocks.CRACKED_STONE_BRICKS
                || block == Blocks.INFESTED_STONE_BRICKS
                || block == Blocks.INFESTED_MOSSY_STONE_BRICKS
                || block == Blocks.INFESTED_CRACKED_STONE_BRICKS) {
            return new CachedPoi("stronghold_block", id.toString());
        }
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.ENDER_CHEST) {
            return new CachedPoi("chest", id.toString());
        }
        if (block == Blocks.CRAFTING_TABLE) {
            return new CachedPoi("crafting_table", id.toString());
        }
        if (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER) {
            return new CachedPoi("furnace", id.toString());
        }
        if (block instanceof BedBlock) {
            return new CachedPoi("bed", id.toString());
        }
        return null;
    }

    private record CachedPoi(String kind, String id) {
    }
}
