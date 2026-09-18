package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 灵魂漫游会话期间的键盘拦截(退出/放置快捷键)。
 *
 * <p>1.21.1 适配:26.x 的 keyPress(long, int, KeyEvent) 打包事件参数在 1.21.1 展开为原始
 * 参数 keyPress(long window, int key, int scancode, int action, int modifiers),
 * net.minecraft.client.input.KeyEvent 已不存在,回调改用展开后的 key/action。</p>
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerRoamingMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true, remap = false)
    private void net_music_can_play_bili$consumeRoamingEscape(long window, int key, int scancode, int action,
            int modifiers, CallbackInfo callback) {
        if (ControlConsoleRoamingSession.handleEscape(key, action)
            || ControlConsoleRoamingSession.handlePlacementKey(key, action)) {
            callback.cancel();
        }
    }
}