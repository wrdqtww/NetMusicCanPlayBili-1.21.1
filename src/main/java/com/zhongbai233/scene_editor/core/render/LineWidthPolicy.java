package com.zhongbai233.scene_editor.core.render;

import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;

/** Physical-pixel line-width compensation for editor grids, outlines and gizmos. */
public final class LineWidthPolicy {
    private static final float BASE_SCALE = 1.30F;
    private static final float REFERENCE_DISTANCE = 4.0F;
    private static final float REFERENCE_ORTHO_HALF_HEIGHT = 3.0F;
    private static final float MIN_VISIBLE_WIDTH = 2.0F;
    private static final float MAX_VISIBLE_WIDTH = 8.0F;
    private static final float MAX_SCALE = 6.0F;

    private LineWidthPolicy() {
    }

    public static float forCamera(EditorCameraState camera) {
        java.util.Objects.requireNonNull(camera, "camera");
        return camera.mode() == EditorCameraMode.ORTHOGRAPHIC
                ? orthographic(camera.orthoScale())
                : perspective((float) camera.position().distance(camera.focus()));
    }

    public static float perspective(float cameraDistance) {
        float distanceRatio = Math.max(1.0F, finitePositive(cameraDistance) / REFERENCE_DISTANCE);
        return clampScale(BASE_SCALE * (float) Math.sqrt(distanceRatio));
    }

    public static float orthographic(float halfHeight) {
        float viewRatio = Math.max(1.0F, finitePositive(halfHeight) / REFERENCE_ORTHO_HALF_HEIGHT);
        return clampScale(BASE_SCALE * (float) Math.sqrt(viewRatio));
    }

    /** Converts a logical line width to a readable physical-pixel width. */
    public static float visibleWidth(float logicalWidth, float cameraScale) {
        float safeLogicalWidth = finitePositive(logicalWidth);
        float safeCameraScale = finitePositive(cameraScale);
        return Math.clamp(safeLogicalWidth * safeCameraScale * 1.15F,
                MIN_VISIBLE_WIDTH, MAX_VISIBLE_WIDTH);
    }

    private static float finitePositive(float value) {
        return Float.isFinite(value) && value > 0.0F ? value : 1.0F;
    }

    private static float clampScale(float value) {
        return Math.clamp(value, BASE_SCALE, MAX_SCALE);
    }
}
