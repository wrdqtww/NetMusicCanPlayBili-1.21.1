package com.zhongbai233.net_music_can_play_bili.client.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 单块实体预览条目。
 *
 * <p>26.x 在客户端线程把 {@code BlockEntityRenderState} 提取出来供 PIP 消费;1.21.1 的 BER
 * 没有 render-state 提取/提交 API,因此改存活的 {@link BlockEntity} 本体——PIP 仍运行在客户端
 * 渲染线程,且该条目每个 tick 由 {@link TerrainPreviewManager} 重建,陈旧性至多一帧。</p>
 */
public record TerrainBlockEntityPreview(BlockPos worldPos, BlockEntity blockEntity) {
    public TerrainBlockEntityPreview {
        worldPos = java.util.Objects.requireNonNull(worldPos, "worldPos").immutable();
        java.util.Objects.requireNonNull(blockEntity, "blockEntity");
    }
}