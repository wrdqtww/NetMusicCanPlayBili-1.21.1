package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

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
    /**
     * 已解析一次并缓存的 Iris 状态探测句柄；Iris 不可用或解析失败时为 null。
     *
     * <p>{@code isShaderPackInUse()} 在渲染线程上每帧会被调用十几次（中控台、投影仪、掌机等
     * 多处管线），原实现每次都要 {@code Class.forName} + 两次 {@code getMethod}（各遍历一遍声明
     * 方法并复制 Method 对象）+ {@code invoke}。这里把类查找与方法解析挪到一次性初始化里，
     * 热路径只剩一次 {@code invoke}。</p>
     */
    private static volatile BooleanSupplier shaderPackProbe;

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
        BooleanSupplier probe = shaderPackProbe;
        if (!available || probe == null) {
            return false;
        }
        try {
            boolean inUse = probe.getAsBoolean();
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

    /**
     * 解析一次 Iris API 并返回探测句柄；任一步失败（类不存在、方法签名不符、调用抛错）返回 null。
     *
     * <p>1.21.1 的 Iris 有 {@code isShaderPackActive}，更老的版本只有 {@code isShaderPackInUse}，
     * 因此先按新签名解析，失败再退回旧签名——两种都只在初始化时尝试一次。</p>
     */
    @Nullable
    private static BooleanSupplier resolveShaderPackProbe() {
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Method probe;
            try {
                probe = apiClass.getMethod("isShaderPackActive");
            } catch (NoSuchMethodException missingActiveMethod) {
                probe = apiClass.getMethod("isShaderPackInUse");
            }
            Method resolved = probe;
            // Method.invoke 抛的是受检异常，而 BooleanSupplier 的 lambda 不能抛出受检异常，
            // 所以在句柄内部就地兜住；任一步失败一律按"未启用 shaderpack"处理，与整体约定一致。
            return () -> {
                try {
                    return Boolean.TRUE.equals(resolved.invoke(api));
                } catch (ReflectiveOperationException error) {
                    return false;
                }
            };
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.debug("Iris API 解析失败，按未安装 Iris 处理", error);
            return null;
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
                shaderPackProbe = resolveShaderPackProbe();
                available = shaderPackProbe != null;
                if (available) {
                    LOGGER.debug("检测到 Iris API，启用 shaderpack 兼容检测");
                }
            } catch (LinkageError error) {
                available = false;
            } catch (RuntimeException error) {
                available = false;
                LOGGER.debug("Iris API 初始化失败，按未安装 Iris 处理", error);
            }
        }
    }
}