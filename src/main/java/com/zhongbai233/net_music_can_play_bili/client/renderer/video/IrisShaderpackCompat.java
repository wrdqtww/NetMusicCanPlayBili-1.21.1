package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * 可选 Iris 集成辅助工具。
 *
 * <p>1.21.1 的 Iris(1.8.x)只保留 shaderpack 状态查询;26.x 的
 * {@code RenderPipeline} assignment API({@code IrisApi.assignPipeline}、
 * {@code IrisPipelines.assignPipeline})在本版本没有对应物,相关方法已被移除。
 * shaderpack 状态统一通过反射查询 {@code net.irisshaders.iris.api.v0.IrisApi},
 * 任意反射失败一律按"未启用 shaderpack"处理。仍可通过
 * {@code -Dncpb.video.iris.disable_yuv_shader=true} 启用 CPU RGBA 回退。</p>
 */
public final class IrisShaderpackCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile boolean lastShaderPackInUse;

    private IrisShaderpackCompat() {
    }

    static boolean isForceYuvShaderEnabled() {
        return IrisShaderpackProperties.forceYuvShaderEnabled();
    }

    static String configuredYuvProgramName() {
        return IrisShaderpackProperties.yuvProgramName();
    }

    static String configuredYuvShaderKeyName() {
        return IrisShaderpackProperties.yuvShaderKeyName();
    }

    static boolean isTexturedProbeProgram() {
        return shouldApplyIrisYuvCompatibility() && "TEXTURED".equals(configuredYuvProgramName());
    }

    static boolean isThreePlaneIrisYuvAllowed() {
        return IrisShaderpackProperties.threePlaneYuvAllowed();
    }

    static boolean isYuvShaderpackBypassEnabled() {
        return IrisShaderpackProperties.yuvShaderpackBypassEnabled();
    }

    static boolean isSolidYuvRenderTypeExperimentEnabled() {
        return IrisVideoRenderTypePolicy.isSolidClassificationEnabled();
    }

    static boolean shouldForceSolidYuvRenderType() {
        return IrisVideoRenderTypePolicy.shouldForceSolidClassification(shouldApplyIrisYuvCompatibility());
    }

    static boolean shouldDrawYuvImmediate() {
        return IrisVideoRenderTypePolicy.shouldUseImmediateDraw(shouldApplyIrisYuvCompatibility());
    }

    static boolean shouldApplyIrisYuvCompatibility() {
        return isForceYuvShaderEnabled() && isShaderPackInUse();
    }

    static boolean shouldUseSingleSamplerProbe() {
        return shouldApplyIrisYuvCompatibility() && !isThreePlaneIrisYuvAllowed();
    }

    static boolean shouldForceSafeProbeRenderType() {
        return shouldApplyIrisYuvCompatibility() && !isThreePlaneIrisYuvAllowed();
    }

    static boolean shouldDisableCustomYuvShader() {
        if (IrisShaderpackProperties.customYuvShaderDisabled()) {
            return true;
        }
        if (isForceYuvShaderEnabled()) {
            return false;
        }
        return isShaderPackInUse();
    }

    /**
     * 反射查询 Iris shaderpack 状态。Iris API 不存在或任一步失败时一律返回 {@code false}。
     */
    public static boolean isShaderPackInUse() {
        ensureInitialized();
        if (!available) {
            return false;
        }
        try {
            boolean inUse;
            try {
                inUse = detectShaderPackInUse();
            } catch (ReflectiveOperationException error) {
                LOGGER.debug("Iris API 查询 shaderpack 状态失败，按未启用 shaderpack 处理", error);
                return false;
            }
            if (inUse != lastShaderPackInUse) {
                lastShaderPackInUse = inUse;
                LOGGER.info("Iris shaderpack 状态变化: shaderpackInUse={}, customYuvShaderDisabled={}", inUse,
                        IrisShaderpackProperties.customYuvShaderDisabled()
                                || (!isForceYuvShaderEnabled() && inUse));
            }
            return inUse;
        } catch (RuntimeException error) {
            LOGGER.debug("Iris API 查询 shaderpack 状态失败，按未启用 shaderpack 处理", error);
            return false;
        }
    }

    private static boolean detectShaderPackInUse() throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
        Object api = apiClass.getMethod("getInstance").invoke(null);
        try {
            Object active = apiClass.getMethod("isShaderPackActive").invoke(api);
            return Boolean.TRUE.equals(active);
        } catch (NoSuchMethodException missingActiveMethod) {
            Object inUse = apiClass.getMethod("isShaderPackInUse").invoke(api);
            return Boolean.TRUE.equals(inUse);
        }
    }

    private static void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (IrisShaderpackCompat.class) {
            if (initialized) {
                return;
            }
            initialized = true;
            try {
                if (!ModList.get().isLoaded("iris")) {
                    available = false;
                    return;
                }
                try {
                        detectShaderPackInUse();
                    } catch (ReflectiveOperationException ignored) {
                        available = false;
                        return;
                    }
                    available = true;
                LOGGER.debug("检测到 Iris API，启用 shaderpack 兼容检测");
            } catch (LinkageError error) {
                available = false;
            } catch (RuntimeException error) {
                available = false;
                LOGGER.debug("Iris API 初始化失败，按未安装 Iris 处理", error);
            }
        }
    }
}