package com.zhongbai233.net_music_can_play_bili.port.shim;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 1.21.1 shim replacing the 26.1.2 {@code net.minecraft.client.gui.render.pip.PictureInPictureRenderer}.
 *
 * <p>26.x rendered registered PIP states into a region of the GUI atlas texture, driven by the
 * GUI frame renderer. 1.21.1 has no GUI atlas pipeline, so this class renders the identical
 * content into a private {@link RenderTarget} (RGBA8, depth enabled, transparent clear) and
 * exposes {@link #colorTextureId()} for the screen to blit with
 * {@link PortGuiFramebufferBlitter}. The editor screens call {@link #render} once per GUI frame,
 * which matches the per-frame frequency of the 26.x pipeline.</p>
 *
 * <p>The renderer owns an isolated {@link MultiBufferSource.BufferSource} built on its own
 * {@link ByteBufferBuilder}: the shared GUI/world buffer sources can therefore never be flushed
 * while the pip framebuffer or projection is active.</p>
 */
public abstract class PortPictureInPictureRenderer<S> implements AutoCloseable {
    private static final Map<Class<?>, Function<MultiBufferSource.BufferSource, ? extends PortPictureInPictureRenderer<?>>>
            PIP_FACTORIES = new ConcurrentHashMap<>();

    /** Geometry buffer isolated from the shared {@link Minecraft#renderBuffers()} sources. */
    protected final MultiBufferSource.BufferSource bufferSource;

    /** 1.21.1 lines render type takes the width from a global {@link RenderSystem#lineWidth}; track it. */
    protected float activeLineWidth = 1.0F;

    private RenderTarget target;
    private int lastWidth;
    private int lastHeight;

    protected PortPictureInPictureRenderer(MultiBufferSource.BufferSource suppliedSource) {
        // 1.21.1 has no GUI-provided pip buffer; the supplied source (kept for 26.x call-site
        // compatibility with the renderer factory contract) is ignored so that the pip can never
        // flush shared batches under a private projection.
        this.bufferSource = MultiBufferSource.immediate(new ByteBufferBuilder(4096));
    }

    /**
     * 26.x {@code RegisterPictureInPictureRenderersEvent.register(Class, factory)} replacement.
     * Registration is optional in 1.21.1: screens may also acquire the concrete renderer directly.
     */
    public static <S> void registerPipRenderer(Class<S> stateClass,
            Function<MultiBufferSource.BufferSource, ? extends PortPictureInPictureRenderer<S>> factory) {
        PIP_FACTORIES.put(stateClass, factory);
    }

    /** Lookup used by ported registration sites; returns null when nothing was registered. */
    @SuppressWarnings("unchecked")
    public static <S> PortPictureInPictureRenderer<S> createPipRenderer(Class<S> stateClass,
            MultiBufferSource.BufferSource source) {
        Function<MultiBufferSource.BufferSource, ? extends PortPictureInPictureRenderer<?>> factory =
                PIP_FACTORIES.get(stateClass);
        return factory == null ? null : (PortPictureInPictureRenderer<S>) factory.apply(source);
    }

    public abstract Class<S> getRenderStateClass();

    /** Renders the scene into the pip framebuffer; the subclass owns projection switching. */
    protected abstract void renderToTexture(S state, PoseStack poseStack);

    protected abstract String getTextureLabel();

    /** Kept for 26.x signature compatibility; the 1.21.1 path does not use atlas translation. */
    protected float getTranslateY(int textureHeight, int pixelScale) {
        return 0.0F;
    }

    /**
     * Renders {@code state} into the private framebuffer sized {@code width} x {@code height}
     * and leaves the pipeline bound back to the main target with GUI state restored.
     */
    public final void render(S state, int width, int height) {
        if (state == null || width <= 0 || height <= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        RenderSystem.assertOnRenderThread();
        if (target == null || lastWidth != width || lastHeight != height) {
            lastWidth = width;
            lastHeight = height;
            if (target != null) {
                target.destroyBuffers();
            }
            target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            target.setFilterMode(9729);
        }

        float previousFogStart = RenderSystem.getShaderFogStart();
        float previousFogEnd = RenderSystem.getShaderFogEnd();
        // The preview is a stylized 3D scene: neutralize world fog exactly like the 26.x
        // FogRenderer.FogMode.NONE path did for the pip renderer.
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        activeLineWidth = 1.0F;
        RenderSystem.lineWidth(1.0F);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().identity();
        RenderSystem.applyModelViewMatrix();

        target.bindWrite(true);
        RenderSystem.enableScissor(0, 0, width, height);
        RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        RenderSystem.clear(16640, Minecraft.ON_OSX);

        PoseStack pipPoseStack = new PoseStack();
        try {
            renderToTexture(state, pipPoseStack);
            bufferSource.endBatch();
        } finally {
            RenderSystem.disableScissor();
            target.unbindWrite();
            minecraft.getMainRenderTarget().bindWrite(true);
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.disableCull();
            RenderSystem.disableDepthTest();
            RenderSystem.lineWidth(1.0F);
            activeLineWidth = 1.0F;
            RenderSystem.setShaderFogStart(previousFogStart);
            RenderSystem.setShaderFogEnd(previousFogEnd);
            Lighting.setupForFlatItems();
        }
    }

    /** Color attachment of the pip framebuffer; -1 before the first successful render. */
    public int colorTextureId() {
        return target != null ? target.getColorTextureId() : -1;
    }

    /**
     * Flushes the pip buffers and switches the global line width when a subsequent
     * {@link net.minecraft.client.renderer.RenderType#lines()} batch needs a different width
     * (1.21.1 carries the width in a global uniform, not per vertex).
     */
    protected void applyLineWidth(float width) {
        if (width != activeLineWidth) {
            bufferSource.endBatch();
            RenderSystem.lineWidth(width);
            activeLineWidth = width;
        }
    }

    @Override
    public void close() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}