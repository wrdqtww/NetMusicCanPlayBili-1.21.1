package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.github.tartaricacid.netmusic.client.model.ModelMusicPlayer;
import com.github.tartaricacid.netmusic.client.renderer.MusicPlayerRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;

/**
 * 将 NetMusic 自己烘焙的唱片子模型叠加到本模组唱片机机身上。
 *
 * <p>模型与 128×128 贴图始终从已加载的必需依赖 NetMusic 获取，本模组不复制或重打包其资源。
 * 下面的变换与 NetMusic 1.5.1 的唱片机渲染坐标一致；更换 Blockbench 机身后只需微调三个锚点常量。
 * 1.21.1 的 NetMusic 模型为 {@link ModelMusicPlayer}(root+disc 两个顶层子节点)，唱片旋转直接写在
 * {@code disc} 部件的 {@code yRot} 上，与 NetMusic 1.5.2 自己的 {@code MusicPlayerRenderer} 一致。</p>
 */
final class NetMusicDiscModelAdapter {
    private static final float MODEL_SCALE = (float) DiscPlacementPolicy.MODEL_SCALE;
    /** Blockbench 中八根唱片条共享的目标枢轴高度：Y = 3.6199。 */
    private static final double ANCHOR_Y = 1.3329083333333333D;
    private final ModelMusicPlayer model;

    NetMusicDiscModelAdapter(BlockEntityRendererProvider.Context context) {
        ModelPart bakedRoot = context.bakeLayer(ModelMusicPlayer.LAYER);
        this.model = new ModelMusicPlayer(bakedRoot);
        // createBodyLayer() 的直接子节点 "root" 是机身；"disc" 是独立唱片子树。
        // 每个 BER 都有自己的 bakeLayer 实例，因此隐藏它不会影响 NetMusic 自己的唱片机。
        if (bakedRoot.hasChild("root")) {
            bakedRoot.getChild("root").visible = false;
        }
    }

    void submit(boolean hasDisc, boolean playing, Direction facing, long gameTime, float partialTick,
            int lightCoords, PoseStack poseStack, MultiBufferSource bufferSource) {
        if (!hasDisc) {
            return;
        }
        // 1.21.1 NetMusic 没有 MusicPlayerRenderState；唱片旋转/可见性直接写在模型部件上。
        ModelPart disc = model.getDiscBone();
        disc.visible = true;
        disc.yRot = playing ? DiscRotationPolicy.rotationAt(gameTime, partialTick) : 0.0F;

        int clockwiseQuarterTurns = switch (facing) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        DiscPlacementPolicy.Placement placement =
            DiscPlacementPolicy.forClockwiseQuarterTurns(clockwiseQuarterTurns);

        poseStack.pushPose();
        poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
        poseStack.translate(placement.anchorX(), ANCHOR_Y, placement.anchorZ());
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - facing.get2DDataValue() * 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutout(MusicPlayerRenderer.TEXTURE));
        model.renderToBuffer(poseStack, consumer, lightCoords, OverlayTexture.NO_OVERLAY, -1);
        poseStack.popPose();
    }

}