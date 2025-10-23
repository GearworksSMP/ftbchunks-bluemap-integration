package io.github.gaming32.ftbchunksbluemapintegration;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public enum ChunkPosDirection {
    UP(0, -1),
    RIGHT(1, 0),
    DOWN(0, 1),
    LEFT(-1, 0);

    private final int offsetX;
    private final int offsetZ;

    ChunkPosDirection(int offsetX, int offsetZ) {
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
    }

    public ChunkPos add(ChunkPos pos) {
        return new ChunkPos(pos.x + offsetX, pos.z + offsetZ);
    }

    public ChunkPosDirection getLeft() {
        return values()[(ordinal() + 3) & 3];
    }

    public ChunkPosDirection getRight() {
        return values()[(ordinal() + 1) & 3];
    }

    public static BlockPos getCorner(ChunkPos chunk, ChunkPosDirection d1, ChunkPosDirection d2) {
        return new BlockPos(
            d1.offsetX + d2.offsetX == 0 ? chunk.getMiddleBlockX() : (d1.offsetX + d2.offsetX > 0 ? chunk.getMaxBlockX() + 1 : chunk.getMinBlockX()),
            0,
            d1.offsetZ + d2.offsetZ == 0 ? chunk.getMiddleBlockZ() : (d1.offsetZ + d2.offsetZ > 0 ? chunk.getMaxBlockZ() + 1 : chunk.getMinBlockZ())
        );
    }
}
