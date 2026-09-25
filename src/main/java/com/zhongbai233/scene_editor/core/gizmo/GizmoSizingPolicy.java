package com.zhongbai233.scene_editor.core.gizmo;

import org.joml.Vector3fc;

/** Keeps handles outside the selected bounds while preserving usable minimum sizes. */
public final class GizmoSizingPolicy {
    public static final double MIN_AXIS_LENGTH = 1.25D;
    public static final double MIN_ROTATION_RADIUS = 1.05D;
    public static final double MIN_SCALE_HANDLE_LENGTH = 1.35D;
    private static final double AXIS_CLEARANCE = 0.35D;
    private static final double ROTATION_CLEARANCE = 0.20D;
    private static final double SCALE_CLEARANCE = 0.45D;

    private GizmoSizingPolicy() {
    }

    public static Sizes calculate(double radius) {
        if (!Double.isFinite(radius) || radius < 0.0D) {
            throw new IllegalArgumentException("selection radius must be finite and non-negative");
        }
        return new Sizes(Math.max(MIN_AXIS_LENGTH, radius + AXIS_CLEARANCE),
                Math.max(MIN_ROTATION_RADIUS, radius + ROTATION_CLEARANCE),
                Math.max(MIN_SCALE_HANDLE_LENGTH, radius + SCALE_CLEARANCE));
    }

    public static double worldBoundingRadius(Vector3fc scale) {
        java.util.Objects.requireNonNull(scale, "scale");
        if (!Float.isFinite(scale.x()) || !Float.isFinite(scale.y()) || !Float.isFinite(scale.z())) {
            throw new IllegalArgumentException("scale must be finite");
        }
        return 0.5D * Math.sqrt((double) scale.x() * scale.x()
                + (double) scale.y() * scale.y() + (double) scale.z() * scale.z());
    }

    public record Sizes(double axisLength, double rotationRadius, double scaleHandleLength) {
        public Sizes {
            if (!positiveFinite(axisLength) || !positiveFinite(rotationRadius)
                    || !positiveFinite(scaleHandleLength)) {
                throw new IllegalArgumentException("gizmo dimensions must be positive and finite");
            }
        }

        private static boolean positiveFinite(double value) {
            return Double.isFinite(value) && value > 0.0D;
        }
    }
}
