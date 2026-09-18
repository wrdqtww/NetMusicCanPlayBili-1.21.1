package com.zhongbai233.net_music_can_play_bili.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4ItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.PadItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.HandheldArmRenderer;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 将第一人称 MP4 物品渲染替换为可交互的手持设备屏幕。
 *
 * <p>1.21.1 适配:26.x 渲染提交器 net.minecraft.client.renderer.SubmitNodeCollector 在 1.21.1
 * 已不存在,renderArmWithItem/renderMapHand/renderPlayerArm 的第 8/2 个参数统一改为
 * net.minecraft.client.renderer.MultiBufferSource(BufferSource),回调与 @Invoker 均已对齐。</p>
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true, remap = false)
    private void net_music_can_play_bili$renderMp4AsMap(AbstractClientPlayer player, float partialTick, float pitch,
            InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress, PoseStack poseStack,
            MultiBufferSource bufferSource, int light, CallbackInfo ci) {
        // 灵魂漫游使用代理相机，不能把真实玩家的手臂/手持物带入画面。
        if (ControlConsoleRoamingSession.isActive()) {
            ci.cancel();
            return;
        }
        if (stack.is(ModItems.MP4.get())) {
            MP4ItemScreenRenderer.renderMapLike(player, partialTick, pitch, hand, stack, swingProgress, equipProgress,
                    poseStack, bufferSource, light, new HandheldArmRenderer() {
                        @Override
                        public void renderMapHand(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                                HumanoidArm arm) {
                            net_music_can_play_bili$renderMapHand(poseStack, bufferSource, light, arm);
                        }

                        @Override
                        public void renderPlayerArm(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                                float equipProgress, float swingProgress, HumanoidArm arm) {
                            net_music_can_play_bili$renderPlayerArm(poseStack, bufferSource, light, equipProgress,
                                    swingProgress, arm);
                        }
                    });
            ci.cancel();
            return;
        }
        if (stack.is(ModItems.PAD.get())) {
            PadItemScreenRenderer.renderMapLike(player, partialTick, pitch, hand, stack, swingProgress, equipProgress,
                    poseStack, bufferSource, light, new HandheldArmRenderer() {
                        @Override
                        public void renderMapHand(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                                HumanoidArm arm) {
                            net_music_can_play_bili$renderMapHand(poseStack, bufferSource, light, arm);
                        }

                        @Override
                        public void renderPlayerArm(PoseStack poseStack, MultiBufferSource bufferSource, int light,
                                float equipProgress, float swingProgress, HumanoidArm arm) {
                            net_music_can_play_bili$renderPlayerArm(poseStack, bufferSource, light, equipProgress,
                                    swingProgress, arm);
                        }
                    });
            ci.cancel();
        }
    }

    @Invoker(value = "renderMapHand", remap = false)
    protected abstract void net_music_can_play_bili$renderMapHand(PoseStack poseStack, MultiBufferSource bufferSource,
            int light, HumanoidArm arm);

    @Invoker(value = "renderPlayerArm", remap = false)
    protected abstract void net_music_can_play_bili$renderPlayerArm(PoseStack poseStack, MultiBufferSource bufferSource,
            int light, float equipProgress, float swingProgress, HumanoidArm arm);

}