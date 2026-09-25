package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import org.slf4j.Logger;

/**
 * YUV420P/I420 帧转换工具。
 *
 * <p>
 * 主要作为诊断、基准测试和 shader 不可用时的 CPU RGBA 回退路径（在 1.21.1 上是视频渲染的默认
 * 路径）；正常视频渲染优先使用 NV12/YUV 多平面纹理和 fragment shader。
 * </p>
 *
 * <p>
 * <b>失败契约：</b>所有转换入口在尺寸不匹配、帧数据不足或格式不支持时返回 {@code null}，
 * 绝不抛未受检异常 —— 调用点在渲染 tick 上，抛出的 RuntimeException 会直接打断渲染。
 * 调用方必须判空。
 * </p>
 */
public final class Yuv420pConverter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MATRIX = VideoPipelineProperties.yuv().matrix();
    private static final int MATRIX_BT709_LIMITED = 0;
    private static final int MATRIX_BT601_LIMITED = 1;
    private static final int MATRIX_FULL_RANGE_BT709 = 2;
    /**
     * 色彩矩阵在类加载时定死。
     *
     * <p>原实现把 String switch 放在逐像素函数里：480x480 一帧就是 23 万次 String 比较。
     * 该路径现在是 1.21.1 的默认渲染路径（自定义 YUV 着色器在此版本不光栅化），必须移出内循环。</p>
     */
    private static final int MATRIX_KIND = switch (MATRIX) {
        case "bt601", "bt601_limited" -> MATRIX_BT601_LIMITED;
        case "full", "bt709_full", "full_range" -> MATRIX_FULL_RANGE_BT709;
        default -> MATRIX_BT709_LIMITED;
    };

    private Yuv420pConverter() {
    }

    /**
     * 把解码帧转成待上传的 RGBA 字节。
     *
     * <p>尺寸不匹配、格式不支持等一律返回 {@code null}（而不是抛未受检异常）：调用点在渲染 tick 上，
     * 只捕获 OutOfMemoryError，抛出的 IllegalArgumentException 会直接打断渲染。返回值必须判空。</p>
     */
    public static byte[] toUploadRgba(Fmp4NativeVideoDecoder.DecodedFrame frame, int width, int height) {
        if (frame == null) {
            return null;
        }
        if (frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA) {
            return frame.rgba();
        }
        if (frame.format() != Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P) {
            if (frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12) {
                return nv12ToRgba(frame.data(), width, height);
            }
            LOGGER.warn("不支持的视频帧格式，跳过上传: {}", frame.format());
            return null;
        }
        return yuv420pToRgba(frame.data(), width, height);
    }

    static byte[] toUploadRgba(VideoBillboardPreview.DecodedFrame frame, int width, int height) {
        if (frame == null) {
            return null;
        }
        if (frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA) {
            return frame.rgba();
        }
        if (frame.format() != Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P) {
            if (frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12) {
                return nv12ToRgba(frame.data(), width, height);
            }
            LOGGER.warn("不支持的视频帧格式，跳过上传: {}", frame.format());
            return null;
        }
        return yuv420pToRgba(frame.data(), width, height);
    }

    public static byte[] yuv420pToRgba(byte[] yuv, int width, int height) {
        if ((width & 1) != 0 || (height & 1) != 0) {
            LOGGER.warn("YUV420P 需要偶数宽高，跳过转换: {}x{}", width, height);
            return null;
        }
        int ySize = width * height;
        int uvWidth = width / 2;
        int uvHeight = height / 2;
        int uvSize = uvWidth * uvHeight;
        int required = ySize + uvSize * 2;
        if (yuv == null || yuv.length < required) {
            LOGGER.warn("YUV420P 帧数据不足，跳过转换: {} < {}", yuv == null ? -1 : yuv.length, required);
            return null;
        }

        byte[] rgba = new byte[ySize * 4];
        int uBase = ySize;
        int vBase = ySize + uvSize;
        int out = 0;
        for (int y = 0; y < height; y++) {
            int yRow = y * width;
            int uvRow = (y / 2) * uvWidth;
            for (int x = 0; x < width; x++) {
                int yy = yuv[yRow + x] & 0xFF;
                int uu = yuv[uBase + uvRow + x / 2] & 0xFF;
                int vv = yuv[vBase + uvRow + x / 2] & 0xFF;
                int rgb = convertPacked(yy, uu, vv);
                rgba[out++] = (byte) (rgb >> 16);
                rgba[out++] = (byte) (rgb >> 8);
                rgba[out++] = (byte) rgb;
                rgba[out++] = (byte) 255;
            }
        }
        return rgba;
    }

    public static byte[] nv12ToRgba(byte[] nv12, int width, int height) {
        if ((width & 1) != 0 || (height & 1) != 0) {
            LOGGER.warn("NV12 需要偶数宽高，跳过转换: {}x{}", width, height);
            return null;
        }
        int ySize = width * height;
        int required = ySize + ySize / 2;
        if (nv12 == null || nv12.length < required) {
            LOGGER.warn("NV12 帧数据不足，跳过转换: {} < {}", nv12 == null ? -1 : nv12.length, required);
            return null;
        }

        byte[] rgba = new byte[ySize * 4];
        int uvBase = ySize;
        int out = 0;
        for (int y = 0; y < height; y++) {
            int yRow = y * width;
            int uvRow = (y / 2) * width;
            for (int x = 0; x < width; x++) {
                int yy = nv12[yRow + x] & 0xFF;
                int uv = uvBase + uvRow + (x & ~1);
                int uu = nv12[uv] & 0xFF;
                int vv = nv12[uv + 1] & 0xFF;
                int rgb = convertPacked(yy, uu, vv);
                rgba[out++] = (byte) (rgb >> 16);
                rgba[out++] = (byte) (rgb >> 8);
                rgba[out++] = (byte) rgb;
                rgba[out++] = (byte) 255;
            }
        }
        return rgba;
    }

    public static int nv12PixelArgb(byte[] nv12, int width, int height, int x, int y) {
        if (nv12 == null || width <= 0 || height <= 0) {
            return 0xFF000000;
        }
        int safeX = Math.max(0, Math.min(width - 1, x));
        int safeY = Math.max(0, Math.min(height - 1, y));
        int ySize = width * height;
        int uv = ySize + (safeY / 2) * width + (safeX & ~1);
        if (nv12.length <= uv + 1 || nv12.length <= safeY * width + safeX) {
            return 0xFF000000;
        }
        int yy = nv12[safeY * width + safeX] & 0xFF;
        int uu = nv12[uv] & 0xFF;
        int vv = nv12[uv + 1] & 0xFF;
        return 0xFF000000 | convertPacked(yy, uu, vv);
    }

    public static int yuv420pPixelArgb(byte[] yuv, int width, int height, int x, int y) {
        if (yuv == null || width <= 0 || height <= 0) {
            return 0xFF000000;
        }
        int safeX = Math.max(0, Math.min(width - 1, x));
        int safeY = Math.max(0, Math.min(height - 1, y));
        int ySize = width * height;
        int uvWidth = Math.max(1, width / 2);
        int uvHeight = Math.max(1, height / 2);
        int uvSize = uvWidth * uvHeight;
        int u = ySize + (safeY / 2) * uvWidth + safeX / 2;
        int v = ySize + uvSize + (safeY / 2) * uvWidth + safeX / 2;
        if (yuv.length <= v || yuv.length <= safeY * width + safeX) {
            return 0xFF000000;
        }
        int yy = yuv[safeY * width + safeX] & 0xFF;
        int uu = yuv[u] & 0xFF;
        int vv = yuv[v] & 0xFF;
        return 0xFF000000 | convertPacked(yy, uu, vv);
    }

    /**
     * 单像素 YUV→RGB，返回打包的 {@code 0x00RRGGBB}。
     *
     * <p>原实现返回 {@code new int[] {r, g, b}}：整屏 480x480 每帧 23 万次堆分配。改为打包返回后
     * 热循环零分配。色彩矩阵已由 {@link #MATRIX_KIND} 在类加载时选定，内循环不再做分派。</p>
     */
    private static int convertPacked(int y, int u, int v) {
        int r;
        int g;
        int b;
        if (MATRIX_KIND == MATRIX_BT601_LIMITED) {
            int c = Math.max(0, y - 16);
            int d = u - 128;
            int e = v - 128;
            r = (298 * c + 409 * e + 128) >> 8;
            g = (298 * c - 100 * d - 208 * e + 128) >> 8;
            b = (298 * c + 516 * d + 128) >> 8;
        } else if (MATRIX_KIND == MATRIX_FULL_RANGE_BT709) {
            int d = u - 128;
            int e = v - 128;
            r = Math.round((float) (y + 1.5748D * e));
            g = Math.round((float) (y - 0.1873D * d - 0.4681D * e));
            b = Math.round((float) (y + 1.8556D * d));
        } else {
            int c = Math.max(0, y - 16);
            int d = u - 128;
            int e = v - 128;
            r = (298 * c + 459 * e + 128) >> 8;
            g = (298 * c - 55 * d - 136 * e + 128) >> 8;
            b = (298 * c + 541 * d + 128) >> 8;
        }
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : value > 255 ? 255 : value;
    }
}
