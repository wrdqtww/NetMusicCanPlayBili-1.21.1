package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.platform.NativeImage;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldMediaProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** MP4 离屏 GUI 纹理入口。 */
final class MP4GuiTexture implements AutoCloseable {
    static final int WIDTH = MP4HandheldMediaProfile.SCREEN.portraitWidth();
    static final int HEIGHT = MP4HandheldMediaProfile.SCREEN.portraitHeight();

    private static final ResourceLocation WHITE_TEXTURE_ID = ResourceLocation.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
            "dynamic/mp4_gui_white");
    private static DynamicTexture sharedWhiteTexture;

    private final MP4OffscreenGuiRenderer offscreenRenderer;

    MP4GuiTexture(String textureKey) {
        this.offscreenRenderer = new MP4OffscreenGuiRenderer(textureKey);
    }

    ResourceLocation textureId() {
        return textureId(null);
    }

    ResourceLocation textureId(UUID deviceId) {
        return offscreenRenderer.textureId(deviceId);
    }

    void renderFrameStart(UUID deviceId) {
        offscreenRenderer.renderFrameStart(deviceId);
    }

    ResourceLocation whiteTextureId() {
        ensureWhiteTexture();
        return WHITE_TEXTURE_ID;
    }

    void warmup() {
        MP4FontManager.warmup();
        ensureWhiteTexture();
    }

    private void ensureWhiteTexture() {
        if (sharedWhiteTexture != null) {
            return;
        }
        sharedWhiteTexture = new DynamicTexture(1, 1, false);
        NativeImage image = sharedWhiteTexture.getPixels();
        if (image != null) {
            image.setPixelRGBA(0, 0, 0xFFFFFFFF);
            sharedWhiteTexture.upload();
        }
        Minecraft.getInstance().getTextureManager().register(WHITE_TEXTURE_ID, sharedWhiteTexture);
    }

    @Override
    public void close() {
        offscreenRenderer.close();
    }
}
