package com.zhongbai233.net_music_can_play_bili.port.shim;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import java.util.function.Function;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * 1.21.1 render-type helpers for debug/world overlays.
 *
 * <p>1.21.1 removed the per-vertex {@code VertexConsumer#setLineWidth}; line width is a
 * per-batch {@link RenderStateShard.LineStateShard}. This factory reproduces the 26.x
 * {@code RenderType.linesTranslucent()} look (translucent, view-offset-z, depth-safe)
 * with an explicit pixel width, so callers can group line segments per width.</p>
 */
public final class PortDebugRenderTypes {
    private PortDebugRenderTypes() {
    }

    private static final Function<Float, RenderType> TRANSLUCENT_LINES = Util.memoize(
            width -> RenderType.create(
                    "ncpb_debug_lines_" + width,
                    DefaultVertexFormat.POSITION_COLOR_NORMAL,
                    VertexFormat.Mode.LINES,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                            .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(width)))
                            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                            .setCullState(RenderStateShard.NO_CULL)
                            .createCompositeState(false)));

    /** Translucent debug lines with the given GL line width in pixels. */
    public static RenderType translucentLines(float width) {
        return TRANSLUCENT_LINES.apply(width);
    }
}