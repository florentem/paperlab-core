package io.papermc.paper.lab.zone;

import net.minecraft.core.BlockPos;

/**
 * An axis-aligned 3D bounding cuboid.
 */
public record ZoneCuboid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public ZoneCuboid {
        final int x1 = Math.min(minX, maxX);
        final int x2 = Math.max(minX, maxX);
        final int y1 = Math.min(minY, maxY);
        final int y2 = Math.max(minY, maxY);
        final int z1 = Math.min(minZ, maxZ);
        final int z2 = Math.max(minZ, maxZ);
        minX = x1;
        maxX = x2;
        minY = y1;
        maxY = y2;
        minZ = z1;
        maxZ = z2;
    }

    public static ZoneCuboid of(final int x1, final int y1, final int z1,
                                final int x2, final int y2, final int z2) {
        return new ZoneCuboid(x1, y1, z1, x2, y2, z2);
    }

    public boolean contains(final int x, final int y, final int z) {
        return x >= minX && x <= maxX
            && y >= minY && y <= maxY
            && z >= minZ && z <= maxZ;
    }

    public boolean contains(final BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean intersectsChunk(final int chunkX, final int chunkZ) {
        final int cMinX = chunkX << 4;
        final int cMaxX = cMinX + 15;
        final int cMinZ = chunkZ << 4;
        final int cMaxZ = cMinZ + 15;
        return maxX >= cMinX && minX <= cMaxX
            && maxZ >= cMinZ && minZ <= cMaxZ;
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (long) (maxY - minY + 1) * (long) (maxZ - minZ + 1);
    }

    public ZoneCuboid union(final ZoneCuboid other) {
        if (other == null) {
            return this;
        }
        return new ZoneCuboid(
            Math.min(minX, other.minX),
            Math.min(minY, other.minY),
            Math.min(minZ, other.minZ),
            Math.max(maxX, other.maxX),
            Math.max(maxY, other.maxY),
            Math.max(maxZ, other.maxZ)
        );
    }
}
