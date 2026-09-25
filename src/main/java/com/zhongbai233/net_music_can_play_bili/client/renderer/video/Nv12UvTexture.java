package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker.Category;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

/**
 * NV12 UV 平面纹理。
 *
 * <p>
 * 1.21.1 的 {@link com.mojang.blaze3d.platform.NativeImage.Format} 没有 RG8，因此优先用裸
 * GL_RG8（{@link RawRg8GlTexture}）；若创建失败，回退 RGBA8 的 {@link DynamicTexture}
 * 临时承载 interleaved UV：R=U, G=V, B=0, A=255。两种形态的 shader 采样都是
 * {@code texture(Sampler1, uv).rg}，语义等价。
 * </p>
 */
final class Nv12UvTexture extends AbstractTexture {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean RG8_ENABLED = VideoPipelineProperties.upload().nv12UvRg8Enabled();

    private final String label;
    private int width;
    private int height;
    private RawRg8GlTexture rg8Texture;
    private DynamicTexture rgbaFallback;
    private ByteBuffer uploadBuffer;
    private Nv12PboUploader pboUploader;
    private boolean rg8Path;

    Nv12UvTexture(String label, int width, int height) {
        this.label = label;
        recreate(width, height);
    }

    /** 当前承载 GL 纹理的对象；随 RG8/RGBA8 路径切换。 */
    private AbstractTexture currentTexture() {
        return rg8Path ? rg8Texture : rgbaFallback;
    }

    @Override
    public int getId() {
        AbstractTexture current = currentTexture();
        return current != null ? current.getId() : super.getId();
    }

    @Override
    public void bind() {
        AbstractTexture current = currentTexture();
        if (current != null) {
            current.bind();
        } else {
            super.bind();
        }
    }

    boolean matches(int width, int height) {
        return this.width == width && this.height == height && currentTexture() != null;
    }

    void recreate(int width, int height) {
        close();
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.rg8Path = false;
        if (RG8_ENABLED) {
            try {
                RawRg8GlTexture created = RawRg8GlTexture.create(label, this.width, this.height);
                this.rg8Texture = created;
                this.rg8Path = true;
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("NV12: 创建 GL_RG8 UV 纹理失败，回退 RGBA8: {}", e.toString());
                if (this.rg8Texture != null) {
                    this.rg8Texture.close();
                }
                this.rg8Texture = null;
            }
        }
        try {
            if (this.rg8Texture == null) {
                this.rgbaFallback = new DynamicTexture(this.width, this.height, false);
            }
            this.uploadBuffer = MemoryUtil.memAlloc(this.width * this.height * (rg8Path ? 2 : 4));
            MemoryResourceTracker.allocated(Category.TEXTURE_STAGING, this.uploadBuffer.capacity());
        } catch (RuntimeException | LinkageError e) {
            close();
            throw e;
        }
    }

    void upload(byte[] nv12, int offset) {
        if (currentTexture() == null) {
            recreate(width, height);
        }
        int pixelCount = width * height;
        int byteCount = pixelCount * (rg8Path ? 2 : 4);
        if (uploadBuffer == null || uploadBuffer.capacity() < byteCount) {
            if (uploadBuffer != null) {
                MemoryResourceTracker.freed(Category.TEXTURE_STAGING, uploadBuffer.capacity());
                MemoryUtil.memFree(uploadBuffer);
                // 必须立即置空：memAlloc 失败抛的是 OutOfMemoryError，不被这里的
                // catch (RuntimeException | LinkageError) 覆盖，字段若仍指向已释放地址，
                // 后续 close() 会二次 memFree 同一指针。
                uploadBuffer = null;
            }
            uploadBuffer = MemoryUtil.memAlloc(byteCount);
            MemoryResourceTracker.allocated(Category.TEXTURE_STAGING, uploadBuffer.capacity());
        }
        uploadBuffer.clear();
        if (rg8Path) {
            uploadBuffer.put(nv12, offset, byteCount);
        } else {
            int src = offset;
            for (int i = 0; i < pixelCount; i++) {
                uploadBuffer.put(nv12[src++]);
                uploadBuffer.put(nv12[src++]);
                uploadBuffer.put((byte) 0);
                uploadBuffer.put((byte) 255);
            }
        }
        uploadBuffer.flip();
        if (pboUploader != null && rg8Path
                && pboUploader.uploadNv12UvAsRg8(rg8Texture, nv12, offset, width, height)) {
            return;
        }
        if (pboUploader != null && !rg8Path
                && pboUploader.uploadNv12UvAsRgba8(rgbaFallback, nv12, offset, width, height)) {
            return;
        }
        int targetId = currentTexture().getId();
        GlStateManager._bindTexture(targetId);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, width, height,
                rg8Path ? GL30C.GL_RG : GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, uploadBuffer);
    }

    boolean uploadPbo(byte[] nv12, int offset) {
        if (currentTexture() == null) {
            recreate(width, height);
        }
        if (pboUploader == null) {
            pboUploader = new Nv12PboUploader(label + "_pbo");
        }
        return rg8Path
                ? pboUploader.uploadNv12UvAsRg8(rg8Texture, nv12, offset, width, height)
                : pboUploader.uploadNv12UvAsRgba8(rgbaFallback, nv12, offset, width, height);
    }

    boolean uploadPbo(ByteBuffer nv12, int offset) {
        if (currentTexture() == null) {
            recreate(width, height);
        }
        if (!rg8Path) {
            return false;
        }
        if (pboUploader == null) {
            pboUploader = new Nv12PboUploader(label + "_pbo");
        }
        return pboUploader.uploadNv12UvAsRg8(rg8Texture, nv12, offset, width, height);
    }

    @Override
    public void load(ResourceManager resourceManager) {
        // 运行时上传的 NV12 UV 平面，无资源文件需要加载。
    }

    @Override
    public void close() {
        if (pboUploader != null) {
            pboUploader.close();
            pboUploader = null;
        }
        if (uploadBuffer != null) {
            MemoryResourceTracker.freed(Category.TEXTURE_STAGING, uploadBuffer.capacity());
            MemoryUtil.memFree(uploadBuffer);
            uploadBuffer = null;
        }
        if (rg8Texture != null) {
            rg8Texture.close();
            rg8Texture = null;
        }
        if (rgbaFallback != null) {
            rgbaFallback.close();
            rgbaFallback = null;
        }
        rg8Path = false;
        super.close();
    }
}