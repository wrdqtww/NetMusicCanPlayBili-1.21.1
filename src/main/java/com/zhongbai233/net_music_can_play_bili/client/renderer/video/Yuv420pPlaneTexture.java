package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker.Category;

import java.nio.ByteBuffer;

/**
 * 单个 8 位 Y/U/V 平面，底层使用裸 GL_R8 GPU 纹理。
 *
 * <p>
 * 1.21.1 没有 {@code com.mojang.blaze3d.textures.TextureFormat}；R8 直接用原生 GL_R8 存储并
 * 通过 glTexSubImage2D 上传，等价于 26.x 的 RED8 + command-encoder writeToTexture 路径。
 * shader 侧同样以 {@code texture(SamplerN, uv).r} 采样，语义不变。
 * </p>
 */
final class Yuv420pPlaneTexture extends AbstractTexture {
    private static final int GL_TEXTURE_MAX_LEVEL = 0x813D;
    private static final int GL_TEXTURE_BASE_LEVEL = 0x813C;

    private final String label;
    private int width;
    private int height;
    private ByteBuffer uploadBuffer;
    private Nv12PboUploader pboUploader;
    private boolean created;

    Yuv420pPlaneTexture(String label, int width, int height) {
        this.label = label;
        recreate(width, height);
    }

    boolean matches(int width, int height) {
        return this.width == width && this.height == height;
    }

    void recreate(int width, int height) {
        close();
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int previousUnpackAlignment = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        try {
            int id = getId();
            GlStateManager._bindTexture(id);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R8,
                    this.width, this.height, 0,
                    GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, 0L);
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR) {
                releaseId();
                throw new IllegalStateException("创建 GL_R8 YUV 平面纹理失败，glError=" + error);
            }
            created = true;
            try {
                this.uploadBuffer = MemoryUtil.memAlloc(this.width * this.height);
                MemoryResourceTracker.allocated(Category.TEXTURE_STAGING, this.uploadBuffer.capacity());
            } catch (RuntimeException | LinkageError e) {
                close();
                throw e;
            }
        } finally {
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, previousUnpackAlignment);
            GlStateManager._bindTexture(previousTexture);
        }
    }

    void upload(byte[] yuv420p, int offset) {
        if (!created || this.width == 0) {
            recreate(width, height);
        }
        int byteCount = width * height;
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
        uploadBuffer.put(yuv420p, offset, byteCount);
        uploadBuffer.flip();
        GlStateManager._bindTexture(getId());
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, uploadBuffer);
    }

    boolean uploadPbo(byte[] data, int offset) {
        if (!created) {
            recreate(width, height);
        }
        if (pboUploader == null) {
            pboUploader = new Nv12PboUploader(label + "_pbo");
        }
        return pboUploader.uploadRed8(this, data, offset, width, height);
    }

    boolean uploadPbo(ByteBuffer data, int offset) {
        if (!created) {
            recreate(width, height);
        }
        if (pboUploader == null) {
            pboUploader = new Nv12PboUploader(label + "_pbo");
        }
        return pboUploader.uploadRed8(this, data, offset, width, height);
    }

    @Override
    public void load(ResourceManager resourceManager) {
        // 运行时上传的视频平面，无资源文件需要加载。
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
        created = false;
        releaseId();
    }
}