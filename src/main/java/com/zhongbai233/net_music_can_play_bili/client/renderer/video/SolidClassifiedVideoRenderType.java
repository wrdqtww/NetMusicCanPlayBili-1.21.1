package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/**
 * 26.x 的"归入 solid/cutout feature 阶段的视频 RenderType"在 1.21.1 没有对应机制:
 * 1.21.1 不存在 CustomFeatureRenderer 的 hasBlending() 分桶,渲染阶段由调用方所选
 * RenderLevelStageEvent 决定。视频几何统一在 AFTER_TRANSLUCENT_BLOCKS 提交,透明混合与
 * 深度写入由 1.21.1 RenderType 的 CompositeState 直接控制,因此该子类不再需要。
 */
final class SolidClassifiedVideoRenderType {
    private SolidClassifiedVideoRenderType() {
    }
}