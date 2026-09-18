package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.platform.NativeImage;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoFrame;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** MP4 手持视频层在 Iris shaderpack 下使用的 RGBA 回退纹理。 */
final class MP4RgbaVideoLayer implements AutoCloseable {
    private static final Map<PlaybackSourceId, MP4RgbaVideoLayer> LAYERS = new ConcurrentHashMap<>();
    private static final Map<PlaybackSourceId, MP4RgbaVideoLayer> HANDHELD_LAYERS = new ConcurrentHashMap<>();

    private final ResourceLocation textureId;
    private DynamicTexture texture;
    private long uploadedSequence = -1L;
    private int uploadedWidth;
    private int uploadedHeight;

    private MP4RgbaVideoLayer(UUID deviceId) {
        this(deviceId, "mp4_video");
    }

    private MP4RgbaVideoLayer(UUID deviceId, String texturePrefix) {
        String suffix = deviceId.toString().replace('-', '_');
        this.textureId = ResourceLocation.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/" + texturePrefix + "_" + suffix + "_rgba");
    }

    static MP4RgbaVideoLayer forDevice(UUID deviceId) {
        if (deviceId == null) {
            throw new IllegalArgumentException("MP4 RGBA video layer requires a device id");
        }
        return LAYERS.computeIfAbsent(PlaybackSourceId.of(deviceId),
                sourceId -> new MP4RgbaVideoLayer(sourceId.value()));
    }

    static MP4RgbaVideoLayer forHandheldDevice(UUID deviceId) {
        if (deviceId == null) {
            throw new IllegalArgumentException("MP4 handheld RGBA video layer requires a device id");
        }
        return HANDHELD_LAYERS.computeIfAbsent(PlaybackSourceId.of(deviceId),
                sourceId -> new MP4RgbaVideoLayer(sourceId.value(), "pad_video"));
    }

    static void releaseAll() {
        LAYERS.values().forEach(layer -> layer.close());
        LAYERS.clear();
        releaseAllHandheld();
    }

    static void releaseAllHandheld() {
        HANDHELD_LAYERS.values().forEach(layer -> layer.close());
        HANDHELD_LAYERS.clear();
    }

    static void release(UUID deviceId) {
        if (deviceId == null) {
            return;
        }
        MP4RgbaVideoLayer layer = LAYERS.remove(PlaybackSourceId.of(deviceId));
        if (layer != null) {
            layer.close();
        }
    }

    static void releaseHandheld(UUID deviceId) {
        if (deviceId == null) {
            return;
        }
        MP4RgbaVideoLayer layer = HANDHELD_LAYERS.remove(PlaybackSourceId.of(deviceId));
        if (layer != null) {
            layer.close();
        }
    }

    boolean uploadLatest(UUID deviceId) {
        try (HandheldVideoFrame frame = MP4HandheldVideoClient.acquireLatestFrame(deviceId)) {
            if (frame == null || frame.format() != Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA) {
                return false;
            }
            long sequence = MP4HandheldVideoClient.frameSequence(deviceId);
            if (texture != null && sequence == uploadedSequence
                    && uploadedWidth == frame.width() && uploadedHeight == frame.height()) {
                return true;
            }
            ensureTexture(frame.width(), frame.height());
            NativeImage image = texture.getPixels();
            if (image == null) {
                return false;
            }
            uploadPixels(image, frame);
            texture.upload();
            uploadedSequence = sequence;
            uploadedWidth = frame.width();
            uploadedHeight = frame.height();
            return true;
        }
    }

    ResourceLocation textureId() {
        return textureId;
    }

    private void ensureTexture(int width, int height) {
        if (texture != null && uploadedWidth == width && uploadedHeight == height) {
            return;
        }
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            texture = null;
        }
        texture = new DynamicTexture(width, height, false);
        Minecraft.getInstance().getTextureManager().register(textureId, texture);
        uploadedSequence = -1L;
        uploadedWidth = width;
        uploadedHeight = height;
    }

    private static void uploadPixels(NativeImage image, HandheldVideoFrame frame) {
        byte[] data = frame.data();
        ByteBuffer buffer = frame.buffer();
        int width = frame.width();
        int height = frame.height();
        int i = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r;
                int g;
                int b;
                int a;
                if (buffer != null) {
                    r = buffer.get(i) & 0xFF;
                    g = buffer.get(i + 1) & 0xFF;
                    b = buffer.get(i + 2) & 0xFF;
                    a = buffer.get(i + 3) & 0xFF;
                } else {
                    r = data[i] & 0xFF;
                    g = data[i + 1] & 0xFF;
                    b = data[i + 2] & 0xFF;
                    a = data[i + 3] & 0xFF;
                }
                image.setPixelRGBA(x, y, FastColor.ABGR32.color(a, r, g, b));
                i += 4;
            }
        }
    }

    @Override
    public void close() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            texture = null;
        }
        uploadedSequence = -1L;
        uploadedWidth = 0;
        uploadedHeight = 0;
    }
}
