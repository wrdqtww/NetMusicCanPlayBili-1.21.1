package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 将 RenderTarget 颜色附件暴露为可采样纹理。
 *
 * <p>
 * 1.21.1 的 AbstractTexture 没有 26.x 的 texture/textureView/sampler 字段；这里直接把 GL id
 * 转发到 RenderTarget 的颜色附件 {@link RenderTarget#getColorTextureId()}，FBO 生命周期
 * (resize/close) 仍由 RenderTarget 自身管理。
 * </p>
 */
final class RenderTargetTexture extends AbstractTexture {
    private final RenderTarget target;

    RenderTargetTexture(RenderTarget target) {
        this.target = target;
        refreshView();
    }

    void refreshView() {
        // 1.21.1 无独立 texture view/sampler 概念；GL id 始终跟随 target 颜色附件。
    }

    @Override
    public int getId() {
        RenderSystem.assertOnRenderThreadOrInit();
        return target.getColorTextureId();
    }

    @Override
    public void bind() {
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            RenderSystem.recordRenderCall(() -> GlStateManager._bindTexture(this.getId()));
        } else {
            GlStateManager._bindTexture(this.getId());
        }
    }

    @Override
    public void load(ResourceManager resourceManager) {
        // 纹理数据来自 RenderTarget，无资源文件需要加载。
    }

    @Override
    public void releaseId() {
        // 颜色附件归 RenderTarget 所有，这里禁止释放。
    }

    @Override
    public void close() {
        // 无自有资源；target 由 OffscreenGuiRenderTargetContext 关闭。
    }
}