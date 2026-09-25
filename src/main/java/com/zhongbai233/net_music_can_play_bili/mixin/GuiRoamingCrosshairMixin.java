package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 灵魂漫游使用自己的命中反馈准星，避免与原版准星叠加。
 * <p>1.21.1 适配:26.x 的 extractCrosshair(GuiGraphics, DeltaTracker) 在 1.21.1 改名为
 * renderCrosshair(GuiGraphics, DeltaTracker),注入点随之改名。</p> */
@Mixin(Gui.class)
public abstract class GuiRoamingCrosshairMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true, remap = false)
    private void net_music_can_play_bili$hideVanillaCrosshair(GuiGraphics graphics,
            DeltaTracker deltaTracker, CallbackInfo callback) {
        if (ControlConsoleRoamingSession.isActive()) {
            callback.cancel();
        }
    }
}