package com.zhongbai233.scene_editor.core.gizmo;

/** Conventional move, rotate and scale tools. */
public enum GizmoMode {
    MOVE("Move"),
    ROTATE("Rotate"),
    SCALE("Scale");

    private final String label;

    GizmoMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
