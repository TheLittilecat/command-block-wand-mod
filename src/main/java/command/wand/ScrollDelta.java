package command.wand;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWScrollCallbackI;

import java.util.concurrent.atomic.AtomicBoolean;

final class ScrollDelta {

    private static volatile double capturedY;

    private static volatile boolean suppressOriginal;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private static GLFWScrollCallback original;

    private ScrollDelta() {}

    static void install(Minecraft mc) {
        if (mc == null || mc.getWindow() == null) {
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) return;
        GLFWScrollCallback previous = null;
        try {
            long handle = mc.getWindow().handle();
            GLFWScrollCallbackI wrapper = (window, xOffset, yOffset) -> {

                capturedY += yOffset;
                if (suppressOriginal) return;
                if (original != null) original.invoke(window, xOffset, yOffset);
            };
            previous = GLFW.glfwSetScrollCallback(handle, wrapper);
            if (previous == null) {

                GLFW.glfwSetScrollCallback(handle, null);
                INSTALLED.set(false);
                return;
            }
            original = previous;
        } catch (Throwable ignored) {
            INSTALLED.set(false);
            original = null;
            if (previous != null) {
                try {
                    GLFW.glfwSetScrollCallback(mc.getWindow().handle(), previous);
                } catch (Throwable ignoredAgain) {}
            }
        }
    }

    static void setSuppressOriginal(boolean suppress) {
        suppressOriginal = suppress;
    }

    static double takeY() {
        double value = capturedY;
        if (value == 0.0) return 0.0;
        capturedY = 0.0;
        return value;
    }
}
