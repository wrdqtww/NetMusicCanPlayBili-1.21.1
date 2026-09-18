package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainTintColors;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainNeighborhoodIndex;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainPackedLight;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 只读 20³ section 邻域模型/tint/light 视图，不持有 Level、chunk、Biome 或注册表对象。 */
final class TerrainPreviewBlockAndTintGetter implements BlockAndTintGetter {
    private static final int SIZE = 16;
    private static final int CELL_COUNT = SIZE * SIZE * SIZE;
    private final int[] grassColors = tintArray();
    private final int[] foliageColors = tintArray();
    private final int[] waterColors = tintArray();
    private final Map<Integer, List<Integer>> tintLayers = new HashMap<>();
    private final TerrainBlockSectionSnapshot snapshot;
    private final LevelLightEngine lightEngine = new LevelLightEngine(new LightChunkGetter() {
        @Override
        public @Nullable net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public net.minecraft.world.level.BlockGetter getLevel() {
            return TerrainPreviewBlockAndTintGetter.this;
        }
    }, true, true);

    TerrainPreviewBlockAndTintGetter(TerrainBlockSectionSnapshot snapshot) {
        this.snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
        for (TerrainBlockSectionSnapshot.VisibleBlock block : snapshot.blocks()) {
            int index = index(block.localX(), block.localY(), block.localZ());
            grassColors[index] = block.tintColors().color(TerrainTintColors.TintType.GRASS);
            foliageColors[index] = block.tintColors().color(TerrainTintColors.TintType.FOLIAGE);
            waterColors[index] = block.tintColors().color(TerrainTintColors.TintType.WATER);
            if (!block.tintLayers().isEmpty()) {
                tintLayers.put(index, block.tintLayers());
            }
        }
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return lightEngine;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        int localX = pos.getX() - snapshot.section().minBlockX();
        int localY = pos.getY() - snapshot.section().minBlockY();
        int localZ = pos.getZ() - snapshot.section().minBlockZ();
        byte packed = snapshot.neighborhoodLight(localX, localY, localZ);
        return layer == LightLayer.SKY ? TerrainPackedLight.sky(packed)
                : TerrainPackedLight.block(packed);
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        int localX = pos.getX() - snapshot.section().minBlockX();
        int localY = pos.getY() - snapshot.section().minBlockY();
        int localZ = pos.getZ() - snapshot.section().minBlockZ();
        if (!inside(localX, localY, localZ)) {
            return -1;
        }
        int index = index(localX, localY, localZ);
        if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) {
            return grassColors[index];
        }
        if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
            return foliageColors[index];
        }
        if (resolver == BiomeColors.WATER_COLOR_RESOLVER) {
            return waterColors[index];
        }
        return -1;
    }

    int precomputedTint(BlockPos pos, int layer) {
        int localX = pos.getX() - snapshot.section().minBlockX();
        int localY = pos.getY() - snapshot.section().minBlockY();
        int localZ = pos.getZ() - snapshot.section().minBlockZ();
        if (!inside(localX, localY, localZ) || layer < 0) {
            return -1;
        }
        List<Integer> colors = tintLayers.get(index(localX, localY, localZ));
        return colors != null && layer < colors.size() ? colors.get(layer) : -1;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int localX = pos.getX() - snapshot.section().minBlockX();
        int localY = pos.getY() - snapshot.section().minBlockY();
        int localZ = pos.getZ() - snapshot.section().minBlockZ();
        return TerrainNeighborhoodIndex.contains(localX, localY, localZ)
                ? snapshot.neighborhoodState(localX, localY, localZ)
                : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return TerrainNeighborhoodIndex.SIZE;
    }

    @Override
    public int getMinBuildHeight() {
        return snapshot.section().minBlockY() + TerrainNeighborhoodIndex.MIN_LOCAL;
    }

    @Override
    public float getShade(net.minecraft.core.Direction direction, boolean shaded) {
        return shaded ? 0.8F : 1.0F;
    }

    private static int[] tintArray() {
        int[] colors = new int[CELL_COUNT];
        Arrays.fill(colors, -1);
        return colors;
    }

    private static boolean inside(int x, int y, int z) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE && z >= 0 && z < SIZE;
    }

    private static int index(int x, int y, int z) {
        return (y * SIZE + z) * SIZE + x;
    }
}