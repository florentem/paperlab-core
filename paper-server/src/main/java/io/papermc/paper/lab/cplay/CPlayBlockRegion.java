package io.papermc.paper.lab.cplay;

import net.minecraft.core.BlockPos;

public record CPlayBlockRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public static CPlayBlockRegion of(BlockPos pos1, BlockPos pos2) {
        return new CPlayBlockRegion(
            Math.min(pos1.getX(), pos2.getX()),
            Math.min(pos1.getY(), pos2.getY()),
            Math.min(pos1.getZ(), pos2.getZ()),
            Math.max(pos1.getX(), pos2.getX()),
            Math.max(pos1.getY(), pos2.getY()),
            Math.max(pos1.getZ(), pos2.getZ())
        );
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }
}
