package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * DynamicTexture 像素写入工具，集中处理 RGBA 字节流与诊断通道映射。
 *
 * <p>
 * 1.21.1 的 NativeImage 不暴露原生像素指针，因此统一走逐像素
 * {@link NativeImage#setPixelRGBA(int, int, int)}（ABGR 字节序）；渲染语义与 26.x
 * 逐像素回退路径一致。
 * </p>
 */
final class VideoFrameUploader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final VideoPipelineProperties.Upload PROPERTIES = VideoPipelineProperties.upload();
    private static final String PIXEL_MODE = PROPERTIES.pixelMode();
    private static final boolean FAST_NATIVE_UPLOAD = PROPERTIES.fastNativeUploadEnabled();

    private VideoFrameUploader() {
    }

    static String pixelMode() {
        return PIXEL_MODE;
    }

    static boolean fastNativeUploadAvailable() {
        // 1.21.1 NativeImage 无公开指针，不存在 26.x 的直接内存写入快路径；
        // 保留探测接口供 bench 上报，结果恒为 false。
        return FAST_NATIVE_UPLOAD && false;
    }

    static boolean uploadRgba(NativeImage image, byte[] rgba, int frameWidth, int frameHeight) {
        int i = 0;
        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {
                int r = rgba[i] & 0xFF;
                int g = rgba[i + 1] & 0xFF;
                int b = rgba[i + 2] & 0xFF;
                int a = rgba[i + 3] & 0xFF;
                image.setPixelRGBA(x, y, packPixel(r, g, b, a));
                i += 4;
            }
        }
        return true;
    }

    static boolean uploadPackedRgbaBytes(NativeImage image, byte[] packedRgbaBytes, int textureWidth,
            int textureHeight) {
        int i = 0;
        for (int y = 0; y < textureHeight; y++) {
            for (int x = 0; x < textureWidth; x++) {
                int r = packedRgbaBytes[i] & 0xFF;
                int g = packedRgbaBytes[i + 1] & 0xFF;
                int b = packedRgbaBytes[i + 2] & 0xFF;
                int a = packedRgbaBytes[i + 3] & 0xFF;
                image.setPixelRGBA(x, y, packPixel(r, g, b, a));
                i += 4;
            }
        }
        return true;
    }

    /**
     * 按像素通道诊断模式打包 1.21.1 NativeImage 使用的 ABGR32 int。
     */
    private static int packPixel(int r, int g, int b, int a) {
        return switch (PIXEL_MODE) {
            case "normal", "argb", "rgba" -> (a << 24) | (b << 16) | (g << 8) | r;
            case "swap_rb", "bgra" -> (a << 24) | (r << 16) | (g << 8) | b;
            case "green_only" -> (a << 24) | (g << 8);
            case "blue_only" -> (a << 24) | (b << 16);
            case "red_only" -> (a << 24) | r;
            case "grayscale" -> {
                int y = (r * 30 + g * 59 + b * 11) / 100;
                yield (a << 24) | (y << 16) | (y << 8) | y;
            }
            case "debug_rgb_bars" -> {
                int rr = r > 127 ? 255 : 0;
                int gg = g > 127 ? 255 : 0;
                int bb = b > 127 ? 255 : 0;
                yield (a << 24) | (bb << 16) | (gg << 8) | rr;
            }
            default -> {
                LOGGER.warn("未知 ncpb.video.pixel.mode={}，回退到 swap_rb", PIXEL_MODE);
                yield (a << 24) | (r << 16) | (g << 8) | b;
            }
        };
    }
}