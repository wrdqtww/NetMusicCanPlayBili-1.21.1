package com.zhongbai233.net_music_can_play_bili.port.shim;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 1.21.1 helper replacing the 26.1.2 {@code SubmitCustomGeometryEvent} role.
 *
 * <p>Each {@link RenderLevelStageEvent} handler that wants to submit world-space
 * geometry creates a {@link PortSubmitNodeCollector} backed by its own
 * {@link MultiBufferSource.BufferSource}/{@link ByteBufferBuilder} (mirroring
 * {@code MultiBufferSource.immediate(new ByteBufferBuilder(n))}) and must call
 * {@link #end(PortSubmitNodeCollector)} once per stage. The scratch buffer is
 * reused across frames on the render thread and reclaimed after every batch.</p>
 */
public final class PortWorldRenderEvents {
    private static final int SCRATCH_BUFFER_BYTES = 4 << 13;

    private PortWorldRenderEvents() {
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** Creates a stage-owned collector whose {@code end()} draws its own batch. */
    public static PortSubmitNodeCollector begin() {
        Scratch scratch = SCRATCH.get();
        return new PortSubmitNodeCollector(scratch.source, true);
    }

    /** Flushes deferred texts, draws the batch, and reclaims the scratch buffer. */
    public static void end(PortSubmitNodeCollector collector) {
        if (collector == null) {
            return;
        }
        collector.end();
        SCRATCH.get().builder.discard();
    }

    /** Convenience stage test for handlers registered on the plain {@link RenderLevelStageEvent}. */
    public static boolean isStage(RenderLevelStageEvent event, RenderLevelStageEvent.Stage stage) {
        return event != null && stage != null && event.getStage() == stage;
    }

    private static final class Scratch {
        final ByteBufferBuilder builder = new ByteBufferBuilder(SCRATCH_BUFFER_BYTES);
        final MultiBufferSource.BufferSource source = MultiBufferSource.immediate(builder);

        Scratch() {
        }
    }
}