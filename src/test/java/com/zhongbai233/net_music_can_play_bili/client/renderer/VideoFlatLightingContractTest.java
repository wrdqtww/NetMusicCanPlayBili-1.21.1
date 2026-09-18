package com.zhongbai233.net_music_can_play_bili.client.renderer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks video surfaces to intrinsic brightness instead of orientation-dependent entity lighting. */
class VideoFlatLightingContractTest {
    @Test
    void rgbaAndYuvPipelinesDisableDirectionalLighting() throws Exception {
        String renderTypes = Files.readString(Path.of("src/main/java/com/zhongbai233/net_music_can_play_bili/"
                + "client/renderer/video/YuvVideoRenderTypes.java"));
        String geometry = Files.readString(Path.of("src/main/java/com/zhongbai233/net_music_can_play_bili/"
                + "client/renderer/video/VideoBillboardGeometrySupport.java"));

        // 1.21.1 等价物:YUV/NV12/single-sampler 三条自定义管线共享 yuvEntity 构造,
        // 且不设置 LightmapStateShard → 与 26.x NO_CARDINAL_LIGHTING 同为平光(不随实体朝向变亮暗)。
        assertFalse(renderTypes.contains("setLightmapState"),
                "YUV/NV12/single-sampler pipelines must not bind entity lightmaps (flat-lit)");
        assertTrue(renderTypes.contains("createCompositeState(false)"),
                "YUV composite state must stay sort-free and flat-lit");
        // RGBA 保持非自发光并使用真实光照贴图;透明叠加走 translucent,不落 cutout。
        assertTrue(renderTypes.contains("RenderType.entityCutout(texture)"),
                "RGBA stays non-emissive and uses the real lightmap");
        assertTrue(renderTypes.contains("RenderType.entityTranslucent(texture)"),
                "RGBA translucent overlays keep the translucent bucket");
        assertFalse(geometry.contains("RenderTypes.itemCutout(texture)"));
        assertFalse(geometry.contains("RenderTypes.itemTranslucent(texture)"));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }
}
