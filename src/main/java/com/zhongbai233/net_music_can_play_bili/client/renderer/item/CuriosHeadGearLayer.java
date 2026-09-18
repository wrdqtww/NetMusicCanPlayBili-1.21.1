package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.util.Set;

/**
 * 使用原版头部物品同款头部变换，渲染本模组装备的媒体装备。
 *
 * <p>1.21.1 没有 26.x 的 {@code AvatarRenderState}/{@code ItemStackRenderState} 实体渲染层
 * (1.21.1 的 RenderLayer 直接以 Entity 为类型参数),本类降级为原版
 * {@link CustomHeadLayer} 同款经典实现:头部部件 {@code translateAndRotate} +
 * {@link CustomHeadLayer#translateToHead(PoseStack, boolean)} +
 * {@link net.minecraft.client.renderer.entity.ItemRenderer#renderStatic}。</p>
 */
public final class CuriosHeadGearLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    private CuriosHeadGearLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    public static void register(EntityRenderersEvent.AddLayers event) {
        Set<PlayerSkin.Model> skins = event.getSkins();
        for (PlayerSkin.Model skin : skins) {
            EntityRenderer<? extends Player> renderer = event.getSkin(skin);
            if (renderer instanceof PlayerRenderer playerRenderer) {
                playerRenderer.addLayer(new CuriosHeadGearLayer(playerRenderer));
            }
        }
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int light, AbstractClientPlayer player,
            float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
            float headPitch) {
        ItemStack stack = curiosHeadGear(player);
        if (stack.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        // 与原版 CustomHeadLayer 相同的头部姿态(玩家模型为宽/瘦体共用一份变换)。
        getParentModel().getHead().translateAndRotate(poseStack);
        CustomHeadLayer.translateToHead(poseStack, false);
        Minecraft.getInstance().getItemRenderer().renderStatic(player, stack, ItemDisplayContext.HEAD, false,
                poseStack, buffer, player.level(), light, LivingEntityRenderer.getOverlayCoords(player, 0.0F),
                player.getId());
        poseStack.popPose();
    }

    private static ItemStack curiosHeadGear(AbstractClientPlayer player) {
        return EquippedMediaItems.firstCuriosEquipped(player, stack -> stack.getItem() == ModItems.CAT_HEADPHONES.get()
                || stack.getItem() == ModItems.HOLOGRAPHIC_GLASSES.get());
    }
}