package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.network.message.MusicToClientMessage;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackCoordinator;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 为 B站 音频注入由 CC 字幕生成的歌词。
 *
 * <p>1.21.1 适配:26.x 的客户端处理类 com.github.tartaricacid.netmusic.network.client.MusicToClientMessageClient
 * 在 NetMusic 1.5.2(1.21.1)中已被并入 payload 记录类
 * com.github.tartaricacid.netmusic.network.message.MusicToClientMessage(record,final),处理逻辑位于其
 * private static void onHandle(MusicToClientMessage) 中(javap port-ref/netmusic-x 确认)。本 mixin 改为
 * 注入该私有方法(remap=false),白名单/歌词拦截逻辑保持不变。</p>
 */
@Mixin(value = MusicToClientMessage.class, remap = false)
public abstract class MusicToClientMessageClientMixin {
    @Inject(method = "onHandle", at = @At("HEAD"), cancellable = true)
    private static void net_music_can_play_bili$injectBiliLyrics(MusicToClientMessage message, CallbackInfo ci) {
        PlaybackSync.Metadata sync = PlaybackSync.parse(message.url());
        boolean modernTurntable = sync.hasSession() || net_music_can_play_bili$isModernTurntable(message);
        boolean biliSelection = ClientMediaPreparer.hasStoredBiliSelection(message.rawUrl(), message.url());

        // 现代化唱片机需要我们的 OpenAL 管线；普通唱片机只在 B站选曲时恢复客户端解析和请求头兼容层。
        if (!modernTurntable && !biliSelection) {
            return;
        }

        if (modernTurntable) {
            PlaybackSync.MinecartAnchor minecartAnchor = PlaybackSync.parseMinecartAnchor(message.url());
            var command = ModernTurntablePlaybackCoordinator.command(message.pos(),
                    message.rawUrl(), message.url(), message.songName(), message.timeSecond(), sync, minecartAnchor,
                    biliSelection);
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> ModernTurntablePlaybackCoordinator.play(command));
            ci.cancel();
            return;
        }

        var command = ModernTurntablePlaybackCoordinator.command(message.pos(), message.rawUrl(), message.url(),
                message.songName(), message.timeSecond(), sync, null, biliSelection);
        Minecraft.getInstance().execute(() -> ModernTurntablePlaybackCoordinator.playCompatible(command));
        ci.cancel();
    }

    @Unique
    private static boolean net_music_can_play_bili$isModernTurntable(MusicToClientMessage message) {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(message.pos()) instanceof ModernTurntableBlockEntity;
    }
}
