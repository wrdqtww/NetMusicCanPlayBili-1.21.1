package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.block.VideoProjectorBlock;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MinecartRevolution 成功装载投影仪后，清除玩家手中剩余物品的临时唱片机链接。
 *
 * <p><b>1.21.1 停用说明:</b>第三方 mod MinecartRevolution 在 NeoForge 1.21.1 无发行版,
 * 目标类 ml.mypals.minecartrevolution.events.MinecartInteractionEventHandler 缺失会导致服务端
 * mixin 加载崩溃。本兼容注入已从 net_music_can_play_bili.mixins.json 移除(不再装配),源文件保留,
 * 待目标 mod 出现 NeoForge 1.21.1 发行版后,按其新方法签名重新启用(校验 interact 签名与注入点后,
 * 将该类重新加入 mixins.json 的 "mixins" 列表)。</p>
 */
@Pseudo
@Mixin(targets = "ml.mypals.minecartrevolution.events.MinecartInteractionEventHandler", remap = false)
public abstract class MinecartRevolutionProjectorLinkCleanupMixin {
    @Inject(method = "interact", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;consume(ILnet/minecraft/world/entity/LivingEntity;)V", shift = At.Shift.AFTER), require = 1)
    private static void net_music_can_play_bili$clearConsumedProjectorLink(Player player, InteractionHand hand,
            AbstractMinecart interacted, Level level, CallbackInfo ci) {
        if (level.isClientSide()) {
            return;
        }
        ItemStack remainder = player.getItemInHand(hand);
        if (remainder.isEmpty() || !remainder.is(ModItems.VIDEO_PROJECTOR.get())) {
            return;
        }
        LinkHelper.clearLinkFromItem(remainder);
        VideoProjectorBlock.clearLinkedBlockEntityData(remainder);
    }
}