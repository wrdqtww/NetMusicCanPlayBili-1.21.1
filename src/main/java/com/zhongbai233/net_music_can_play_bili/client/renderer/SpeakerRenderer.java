package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.zhongbai233.net_music_can_play_bili.block.SpeakerBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * 音响方块渲染器 — 监听连接的唱片机状态，同步 ACTIVATED 方块状态
 */
public class SpeakerRenderer implements BlockEntityRenderer<SpeakerBlockEntity> {

    public SpeakerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SpeakerBlockEntity speaker, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        boolean active = false;
        BlockPos linked = speaker.getLinkedTurntablePos();
        if (linked != null) {
            var level = speaker.getLevel();
            // 唱片机与直播机都通过 PlaybackAudioSource 提供播放状态
            if (level != null
                    && level.getBlockEntity(
                            linked) instanceof com.zhongbai233.net_music_can_play_bili.blockentity.PlaybackAudioSource source
                    && source.isPlaying()) {
                active = true;
            }
        }

        // 同步 ACTIVATED 方块状态
        BlockState currentState = speaker.getLevel() != null
                ? speaker.getLevel().getBlockState(speaker.getBlockPos())
                : null;
        if (currentState != null && currentState.hasProperty(SpeakerBlock.ACTIVATED)) {
            boolean currentlyActivated = currentState.getValue(SpeakerBlock.ACTIVATED);
            if (active != currentlyActivated) {
                final boolean newValue = active;
                Minecraft.getInstance().execute(() -> {
                    var lvl = speaker.getLevel();
                    if (lvl != null) {
                        BlockPos pos = speaker.getBlockPos();
                        BlockState bs = lvl.getBlockState(pos);
                        if (bs.hasProperty(SpeakerBlock.ACTIVATED)) {
                            lvl.setBlock(pos, bs.setValue(SpeakerBlock.ACTIVATED, newValue), 3);
                        }
                    }
                });
            }
        }

        // 模型由 Minecraft 内置方块模型渲染器处理，此处无需额外绘制
    }
}