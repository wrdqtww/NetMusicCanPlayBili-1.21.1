package com.zhongbai233.scene_editor.minecraft.input;

import com.zhongbai233.scene_editor.core.camera.StandardCameraView;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/** Minecraft/GLFW key mapping kept outside the host-neutral editor core. */
public final class MinecraftEditorInput {
    private MinecraftEditorInput() {
    }

    /**
     * Maps number-row and numeric-keypad keys to standard camera views.
     *
     * @param key GLFW key code
     * @return mapped view, or an empty optional when the key has no mapping
     */
    public static Optional<StandardCameraView> standardView(int key) {
        return Optional.ofNullable(switch (key) {
            case GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_KP_1 -> StandardCameraView.FRONT;
            case GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_KP_2 -> StandardCameraView.BACK;
            case GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_KP_3 -> StandardCameraView.LEFT;
            case GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_KP_4 -> StandardCameraView.RIGHT;
            case GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_KP_5 -> StandardCameraView.TOP;
            case GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_KP_6 -> StandardCameraView.BOTTOM;
            default -> null;
        });
    }

    /**
     * Maps editor movement keys to fly-camera controls.
     *
     * @param key GLFW key code
     * @param forwardOnW whether W/S control forward/backward instead of up/down
     * @return mapped control, or an empty optional when the key has no mapping
     */
    public static Optional<FlyControl> flyControl(int key, boolean forwardOnW) {
        return Optional.ofNullable(switch (key) {
            case GLFW.GLFW_KEY_W -> forwardOnW ? FlyControl.FORWARD : FlyControl.UP;
            case GLFW.GLFW_KEY_S -> forwardOnW ? FlyControl.BACKWARD : FlyControl.DOWN;
            case GLFW.GLFW_KEY_A -> FlyControl.LEFT;
            case GLFW.GLFW_KEY_D -> FlyControl.RIGHT;
            case GLFW.GLFW_KEY_C -> forwardOnW ? FlyControl.DOWN : null;
            case GLFW.GLFW_KEY_SPACE -> forwardOnW ? FlyControl.UP : null;
            case GLFW.GLFW_KEY_Q -> forwardOnW ? null : FlyControl.FORWARD;
            case GLFW.GLFW_KEY_E -> forwardOnW ? null : FlyControl.BACKWARD;
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> FlyControl.FAST;
            default -> null;
        });
    }

    /** Fly-camera action associated with a key binding. */
    public enum FlyControl {
        /** Move along the camera's forward direction. */
        FORWARD,
        /** Move opposite the camera's forward direction. */
        BACKWARD,
        /** Strafe left. */
        LEFT,
        /** Strafe right. */
        RIGHT,
        /** Move downward. */
        DOWN,
        /** Move upward. */
        UP,
        /** Temporarily increase movement speed. */
        FAST
    }
}
