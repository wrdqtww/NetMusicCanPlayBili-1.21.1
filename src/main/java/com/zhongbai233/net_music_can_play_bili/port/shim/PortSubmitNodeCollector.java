package com.zhongbai233.net_music_can_play_bili.port.shim;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 1.21.1 shim replacing the 26.1.2 {@code SubmitNodeCollector}.
 *
 * <p>26.x deferred geometry submission is mapped to the classic 1.21.1
 * {@link MultiBufferSource} model: {@link #submitCustomGeometry} pulls a
 * {@link VertexConsumer} for the requested {@link RenderType} and invokes the
 * emitter immediately with the top {@link PoseStack.Pose}. Text submission keeps
 * the 26.x deferred semantics: {@link #submitText} queues the draw with a snapshot
 * of the pose matrix, and {@link #end()} replays the queue through
 * {@link Font#drawInBatch} into the wrapped source (mirroring vanilla
 * {@code DisplayRenderer.TextDisplayRenderer}).</p>
 *
 * <p><b>Batch ownership:</b> when the wrapped source was created by the caller for
 * this stage only (ownBatch = true), {@link #end()} also calls
 * {@link MultiBufferSource.BufferSource#endBatch()}. For the shared BER
 * {@link MultiBufferSource} the framework flushes the batch itself, so it is
 * left untouched.</p>
 */
public final class PortSubmitNodeCollector implements AutoCloseable {
    private final MultiBufferSource source;
    private final boolean ownBatch;
    private final List<TextEntry> pendingTexts = new ArrayList<>(8);
    private Font font;

    public PortSubmitNodeCollector(MultiBufferSource source) {
        this(source, false);
    }

    public PortSubmitNodeCollector(MultiBufferSource source, boolean ownBatch) {
        this.source = source;
        this.ownBatch = ownBatch;
    }

    public MultiBufferSource source() {
        return source;
    }

    /** Legacy 26.x call shape: {@code collector.submitCustomGeometry(pose, renderType, (pose, buffer) -&gt; ...)}. */
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
            BiConsumer<PoseStack.Pose, VertexConsumer> emitter) {
        emitter.accept(poseStack.last(), source.getBuffer(renderType));
    }

    /**
     * Legacy 26.x deferred text call, keeping the exact call-site shape
     * {@code submitText(pose, x, y, visual, shadow, mode, packedLight, color, backgroundColor, packedOverlay)}.
     * Rendering is delayed until {@link #end()} so that callers may keep mutating the
     * pose stack between submissions.
     */
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence visual, boolean shadow,
            Font.DisplayMode mode, int packedLight, int color, int backgroundColor, int packedOverlay) {
        pendingTexts.add(new TextEntry(new Matrix4f(poseStack.last().pose()), x, y, visual, shadow, mode,
                packedLight, color, backgroundColor, packedOverlay));
    }

    /** Convenience overload taking a {@link Component}. */
    public void submitText(PoseStack poseStack, float x, float y, Component text, boolean shadow,
            Font.DisplayMode mode, int packedLight, int color, int backgroundColor, int packedOverlay) {
        submitText(poseStack, x, y, text.getVisualOrderText(), shadow, mode, packedLight, color,
                backgroundColor, packedOverlay);
    }

    /**
     * Replays all queued texts into the wrapped source. Called by {@link #end()};
     * safe to call mid-stage as well.
     */
    public void flush() {
        if (pendingTexts.isEmpty()) {
            return;
        }
        if (font == null) {
            font = Minecraft.getInstance() != null ? Minecraft.getInstance().font : null;
        }
        if (font == null) {
            pendingTexts.clear();
            return;
        }
        for (TextEntry entry : pendingTexts) {
            // Same background handling as vanilla DisplayRenderer.TextDisplayRenderer.
            if ((entry.backgroundColor & 0xFF000000) != 0) {
                VertexConsumer background = source.getBuffer(RenderType.textBackground());
                int width = font.width(entry.visual);
                int height = font.lineHeight;
                background.addVertex(entry.pose, entry.x - 1.0F, entry.y - 1.0F, 0.0F)
                        .setColor(entry.backgroundColor).setLight(entry.packedLight);
                background.addVertex(entry.pose, entry.x - 1.0F, entry.y + height, 0.0F)
                        .setColor(entry.backgroundColor).setLight(entry.packedLight);
                background.addVertex(entry.pose, entry.x + width, entry.y + height, 0.0F)
                        .setColor(entry.backgroundColor).setLight(entry.packedLight);
                background.addVertex(entry.pose, entry.x + width, entry.y - 1.0F, 0.0F)
                        .setColor(entry.backgroundColor).setLight(entry.packedLight);
            }
            font.drawInBatch(entry.visual, entry.x, entry.y, entry.color, entry.shadow, entry.pose, source,
                    entry.mode, entry.packedLight, entry.packedOverlay);
        }
        pendingTexts.clear();
    }

    /** Flushes deferred texts and, when this collector owns its batch, draws the buffer. */
    public void end() {
        flush();
        if (ownBatch && source instanceof MultiBufferSource.BufferSource bufferSource) {
            bufferSource.endBatch();
        }
    }

    @Override
    public void close() {
        end();
    }

    private record TextEntry(Matrix4f pose, float x, float y, FormattedCharSequence visual, boolean shadow,
            Font.DisplayMode mode, int packedLight, int color, int backgroundColor, int packedOverlay) {
    }
}