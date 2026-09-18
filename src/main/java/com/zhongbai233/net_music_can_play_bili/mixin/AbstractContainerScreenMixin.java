package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 滚轮在 MP4 物品所在栏位上滚动时切换本地播放队列索引。
 *
 * <p>1.21.1 适配说明:26.x 注入目标为 AbstractContainerScreen#mouseScrolled,该方法在
 * 1.21.1 已被移除(1.21.1 的 AbstractContainerScreen 不再分发滚轮事件)。1.21.1 中滚轮
 * 流程的唯一入口是 MouseHandler#onScroll(long, double, double),因此本 mixin 改注入该
 * 方法,再通过 AbstractContainerScreen#getSlotUnderMouse() 取回落点栏位,功能与 26.x 等价:</p>
 * <ul>
 *     <li>仅当当前屏幕为 AbstractContainerScreen 且落点栏位持有 MP4Item 时生效;</li>
 *     <li>纵向滚轮方向决定队列索引 +1/-1,并像原版一样只在消费成功时拦截事件。</li>
 * </ul>
 */
@Mixin(MouseHandler.class)
public abstract class AbstractContainerScreenMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, remap = false)
    private void net_music_can_play_bili$selectMp4QueueItem(long window, double xOffset, double yOffset,
            CallbackInfo ci) {
        if (yOffset == 0.0D) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        Slot hoveredSlot = screen.getSlotUnderMouse();
        if (hoveredSlot == null) {
            return;
        }
        ItemStack stack = hoveredSlot.getItem();
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        int queueSize = MP4Item.queueSize(stack);
        if (queueSize <= 0) {
            return;
        }
        MP4Item.State state = MP4Client.cachedStateFor(stack);
        int selected = Math.max(0, Math.min(queueSize - 1, state.selectedQueueIndex() + (yOffset < 0.0D ? 1 : -1)));
        MP4Client.selectQueueIndexLocally(stack, selected);
        ci.cancel();
    }
}