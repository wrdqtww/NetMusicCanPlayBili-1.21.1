package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;

/**
 * 裸 GL_RG8 纹理，用于承载 NV12 的 interleaved UV 平面。
 *
 * <p>
 * 1.21.1 的 {@link com.mojang.blaze3d.platform.NativeImage.Format} 没有 RG8（只有
 * RGBA/LUMINANCE_ALPHA/LUMINANCE），因此这里直接用原生 OpenGL 创建 GL_RG8 存储，
 * 再包装为 {@link AbstractTexture} 以便注册到 TextureManager 供 shader 采样。
 * 创建失败时由 {@link Nv12UvTexture} 回退 RGBA8 承载 interleaved UV。
 * </p>
 */
final class RawRg8GlTexture extends AbstractTexture {
    private static final int GL_TEXTURE_MAX_LEVEL = 0x813D;
    private static final int GL_TEXTURE_BASE_LEVEL = 0x813C;

    private final String label;
    private final int width;
    private final int height;
    private boolean created;

    private RawRg8GlTexture(String label, int width, int height) {
        this.label = label;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    static RawRg8GlTexture create(String label, int width, int height) {
        RawRg8GlTexture texture = new RawRg8GlTexture(label, width, height);
        texture.ensureCreated();
        return texture;
    }

    String label() {
        return label;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    boolean isCreated() {
        return created;
    }

    /** 在渲染线程创建 GL_RG8 存储并设置采样参数；失败时抛出异常由调用方回退 RGBA8。 */
    void ensureCreated() {
        if (created) {
            return;
        }
        int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int previousUnpackAlignment = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        try {
            GlStateManager._bindTexture(getId());
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RG8,
                    width, height, 0,
                    GL30C.GL_RG, GL11C.GL_UNSIGNED_BYTE, 0L);
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR) {
                releaseId();
                throw new IllegalStateException("创建 GL_RG8 NV12 UV 纹理失败，glError=" + error);
            }
            created = true;
        } finally {
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, previousUnpackAlignment);
            GlStateManager._bindTexture(previousTexture);
        }
    }

    void upload(ByteBuffer pixels) {
        ensureCreated();
        GlStateManager._bindTexture(getId());
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL30C.GL_RG, GL11C.GL_UNSIGNED_BYTE, pixels);
    }

    @Override
    public void load(ResourceManager resourceManager) {
        // 运行时上传的 NV12 UV 平面，无资源文件需要加载。
    }

    @Override
    public void close() {
        created = false;
        releaseId();
    }
}