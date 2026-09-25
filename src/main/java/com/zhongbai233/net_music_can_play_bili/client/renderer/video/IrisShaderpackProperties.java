package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

import java.util.Locale;

/** Dynamic JVM property boundary for Iris shaderpack YUV compatibility. */
final class IrisShaderpackProperties {
    static final String FORCE_YUV_SHADER = "ncpb.video.iris.force_yuv_shader";
    static final String DISABLE_CUSTOM_YUV_SHADER = "ncpb.video.iris.disable_yuv_shader";
    static final String LEGACY_DISABLE_CUSTOM_YUV_SHADER = "bili.video.iris.disable_yuv_shader";
    static final String ALLOW_THREE_PLANE_YUV = "ncpb.video.iris.allow_three_plane";
    static final String ENABLE_YUV_SHADERPACK_BYPASS = "ncpb.video.iris.yuv_bypass";
    static final String YUV_PROGRAM = "ncpb.video.iris.program";
    static final String YUV_SHADER_KEY = "ncpb.video.iris.shader_key";
    static final String DEFAULT_YUV_PROGRAM = "ENTITIES_TRANSLUCENT";

    private IrisShaderpackProperties() {
    }

    static boolean forceYuvShaderEnabled() {
        return NcpbSystemProperties.booleanValue(FORCE_YUV_SHADER, true);
    }

    /**
     * 是否禁用自定义 YUV 着色器，改走 CPU→RGBA 回退路径。
     *
     * <p><b>1.21.1 移植默认值 = true（与上游 26.x 的 false 相反）。</b>
     * 26.x 的 YUV 多平面渲染依赖 {@code RenderPipeline}/{@code RenderSetup}，1.21.1 没有对应
     * API，移植版只能用自建 {@link RenderType} + 手写 core shader 承载。实测该 RenderType
     * 在 1.21.1 下提交的四边形不会被光栅化：用恒定色片段着色器（仅验证几何与提交链，不依赖
     * 纹理）测试时，整个屏幕 1280x720 采样 57600 点仅命中 2 个像素，即视频面始终一个片元都
     * 不产生，表现为"只有字幕、没有画面"。同一条提交链上的占位图与 CPU→RGBA 路径使用原版
     * RenderType，均能正常显示，因此默认回退到后者。</p>
     *
     * <p>需要继续实验 YUV 路径时，显式设置
     * {@code -Dncpb.video.iris.disable_yuv_shader=false} 打开。</p>
     */
    static boolean customYuvShaderDisabled() {
        return NcpbSystemProperties.booleanValue(
                DISABLE_CUSTOM_YUV_SHADER, LEGACY_DISABLE_CUSTOM_YUV_SHADER, true);
    }

    static boolean threePlaneYuvAllowed() {
        return NcpbSystemProperties.booleanValue(ALLOW_THREE_PLANE_YUV, true);
    }

    static boolean yuvShaderpackBypassEnabled() {
        return NcpbSystemProperties.booleanValue(ENABLE_YUV_SHADERPACK_BYPASS, true);
    }

    static String yuvProgramName() {
        return NcpbSystemProperties.stringValue(YUV_PROGRAM, DEFAULT_YUV_PROGRAM)
                .toUpperCase(Locale.ROOT);
    }

    static String yuvShaderKeyName() {
        return NcpbSystemProperties.stringValue(YUV_SHADER_KEY, "").toUpperCase(Locale.ROOT);
    }
}
