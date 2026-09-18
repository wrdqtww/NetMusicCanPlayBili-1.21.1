package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.slf4j.Logger;

/**
 * GPU 端 YUV/NV12 转 RGB 的 RenderType 工厂。
 *
 * <p>26.x 的 {@code RenderPipeline}/{@code RenderSetup} 抽象在 1.21.1 不存在，这里改为
 * 1.21.1 的自定义 {@link RenderType#create} + core shader（沿用原有三个 .fsh 资产）：
 * Sampler0 由 TextureStateShard 绑定，Sampler1/Sampler2 在 setupState 里通过
 * {@link RenderSystem#setShaderTexture} 绑定，绘制时 ShaderInstance.setDefaultUniforms
 * 会把 Sampler0..2 对齐到这三张纹理。多平面采样器顺序与 YUV .fsh 的 Sampler0/1/2 声明一致。</p>
 */
public final class YuvVideoRenderTypes {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation YUV420P_SHADER = ResourceLocation.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "bili_yuv420p_entity");
    private static final ResourceLocation NV12_SHADER = ResourceLocation.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "bili_nv12_entity");
    private static final ResourceLocation TEXTURED_PROBE_SHADER = ResourceLocation.fromNamespaceAndPath(
            NetMusicCanPlayBili.MODID, "bili_yuv420p_textured_probe_entity");
    private static final String YUV_SHADER_DEBUG = VideoPipelineProperties.yuv().shaderDebug();
    private static final boolean YUV_NO_DEPTH_WRITE = VideoYuvRenderPolicy.disableDepthWrite();

    private static volatile ShaderInstance yuv420pShader;
    private static volatile ShaderInstance nv12Shader;
    private static volatile ShaderInstance texturedProbeShader;
    private static volatile boolean shaderFailuresLogged;

    private YuvVideoRenderTypes() {
    }

    /**
     * 26.x 时代的 {@code RegisterRenderPipelinesEvent} 注册点在 1.21.1 没有对应物；
     * 保留此方法签名使调用方（客户端事件注册类）无需改动，1.21.1 下为空操作。
     */
    public static void registerPipelines(EntityRenderersEvent.RegisterRenderers event) {
        // No-op in 1.21.1: the custom YUV core shaders are compiled lazily on
        // first YUV RenderType creation instead of being registered up front.
        if (!YUV_SHADER_DEBUG.isBlank()) {
            LOGGER.warn("YUV shader 可视化诊断已启用: mode={}。若画面不变，说明当前后端没有执行本模组 YUV fragment shader。",
                    YUV_SHADER_DEBUG);
        }
    }

    /** 在渲染线程预热 YUV/NV12 core shader(失败仅记录日志,YUV 面回退单平面显示)。 */
    public static void warmupYuvShaders() {
        yuvShader(YUV420P_SHADER);
        yuvShader(NV12_SHADER);
    }

    /** Whether the custom YUV/NV12 shader programs compiled successfully(仅渲染线程下才有意义)。 */
    public static boolean yuvShadersRegistered() {
        return RenderSystem.isOnRenderThread()
                && yuvShader(YUV420P_SHADER) != null && yuvShader(NV12_SHADER) != null;
    }

    private static ShaderInstance yuvShader(ResourceLocation id) {
        // ShaderInstance 构造与 uniform 定位都要求渲染线程;非渲染线程一律按"未加载"处理。
        if (!RenderSystem.isOnRenderThread()) {
            return null;
        }
        if (id.equals(YUV420P_SHADER)) {
            ShaderInstance loaded = yuv420pShader;
            if (loaded == null) {
                yuv420pShader = loaded = loadShader("bili_yuv420p_entity");
            }
            return loaded;
        }
        if (id.equals(NV12_SHADER)) {
            ShaderInstance loaded = nv12Shader;
            if (loaded == null) {
                nv12Shader = loaded = loadShader("bili_nv12_entity");
            }
            return loaded;
        }
        ShaderInstance loaded = texturedProbeShader;
        if (loaded == null) {
            texturedProbeShader = loaded = loadShader("bili_yuv420p_textured_probe_entity");
        }
        return loaded;
    }

    private static ShaderInstance loadShader(String path) {
        ShaderInstance shader = null;
        try {
            shader = new ShaderInstance(Minecraft.getInstance().getResourceManager(),
                    ResourceLocation.fromNamespaceAndPath(NetMusicCanPlayBili.MODID, path),
                    DefaultVertexFormat.NEW_ENTITY);
        } catch (Exception error) {
            shader = null;
        }
        if (shader == null && !shaderFailuresLogged) {
            shaderFailuresLogged = true;
            LOGGER.error(
                    "YUV core shader '{}' failed to compile. YUV/NV12 surfaces fall back to a single-plane display; "
                            + "restart with video.iris.disable_yuv_shader=true to force CPU RGBA conversion instead.",
                    path);
        }
        return shader;
    }

    public static RenderType yuv420pEntity(ResourceLocation yTexture, ResourceLocation uTexture,
            ResourceLocation vTexture) {
        return yuvEntity("bili_yuv420p_entity", yuvShader(YUV420P_SHADER), yTexture, uTexture, vTexture);
    }

    public static RenderType nv12Entity(ResourceLocation yTexture, ResourceLocation uvTexture,
            ResourceLocation placeholderTexture) {
        return nv12Entity("bili_nv12_entity", yTexture, uvTexture, placeholderTexture);
    }

    public static RenderType padNv12Entity(ResourceLocation yTexture, ResourceLocation uvTexture,
            ResourceLocation placeholderTexture) {
        return nv12Entity("ncpb_pad_video_nv12_entity", yTexture, uvTexture, placeholderTexture);
    }

    private static RenderType nv12Entity(String name, ResourceLocation yTexture, ResourceLocation uvTexture,
            ResourceLocation placeholderTexture) {
        return yuvEntity(name, yuvShader(NV12_SHADER), yTexture, uvTexture, placeholderTexture);
    }

    private static RenderType yuvEntity(String name, ShaderInstance shader, ResourceLocation sampler0,
            ResourceLocation sampler1, ResourceLocation sampler2) {
        if (shader == null) {
            // 保守回退：至少把 Y 平面以 cutout 方式显示出来，避免整块视频面消失。
            return RenderType.entityCutout(sampler0);
        }
        return RenderType.create(
                name,
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(() -> shader))
                        .setTextureState(new RenderStateShard.TextureStateShard(sampler0, false, false))
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setWriteMaskState(YUV_NO_DEPTH_WRITE
                                ? RenderStateShard.COLOR_WRITE
                                : RenderStateShard.COLOR_DEPTH_WRITE)
                        .setTexturingState(new RenderStateShard.TexturingStateShard("yuv_planes",
                                () -> {
                                    RenderSystem.setShaderTexture(1, sampler1);
                                    RenderSystem.setShaderTexture(2, sampler2);
                                },
                                () -> {
                                }))
                        .createCompositeState(false));
    }

    static RenderType yOnlyTexturedProbeEntity(ResourceLocation yTexture) {
        return yuvEntity("bili_yuv420p_textured_probe_entity", yuvShader(TEXTURED_PROBE_SHADER),
                yTexture, yTexture, yTexture);
    }

    /** Flat-lit opaque RGBA video surface. */
    public static RenderType videoRgbaEntity(ResourceLocation texture) {
        return RenderType.entityCutout(texture);
    }

    /** Flat-lit translucent RGBA surface (loading / idle / decal overlays). */
    public static RenderType videoRgbaTranslucentEntity(ResourceLocation texture) {
        return RenderType.entityTranslucent(texture);
    }

    /** Flat-lit emissive (lightmap-independent) RGBA overlay. */
    public static RenderType videoRgbaEmissiveEntity(ResourceLocation texture) {
        return RenderType.entityTranslucentEmissive(texture);
    }

    public static RenderType padVideoRgbaEntity(ResourceLocation texture) {
        return RenderType.entityCutout(texture);
    }
}