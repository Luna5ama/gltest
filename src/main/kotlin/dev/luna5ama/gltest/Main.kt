package dev.luna5ama.gltest

import kotlinx.coroutines.cancel
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL

fun main() {
    System.setProperty("joml.forceUnsafe", "true")
    System.setProperty("joml.fastmath", "true")
    System.setProperty("joml.sinLookup", "true")
    System.setProperty("joml.format", "false")
    System.setProperty("joml.useMathFma", "true")

    var gameInstance: GameInstance

    do {
        try {
            glfwInit()
            GLFWErrorCallback.createPrint(System.err).set()

            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API)
            glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_NATIVE_CONTEXT_API)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1)
            glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, 0)
            glfwWindowHint(GLFW_DOUBLEBUFFER, GLFW_TRUE)
            glfwWindowHint(GLFW_SAMPLES, 0)

            val window = glfwCreateWindow(1920, 1080, "gltest", 0, 0)
//            val window = glfwCreateWindow(1280, 720, "", 0, 0)
//            val window = glfwCreateWindow(800, 600, "", 0, 0)
//            val size = 1024
//            val window = glfwCreateWindow(size, size, "GLTest", 0, 0)

            try {
                gameInstance = GameInstance(window)
                try {
                    gameInstance.run()
                } finally {
                    gameInstance.backgroundTaskScope.cancel()
                }
            } finally {
                GL.destroy()
                glfwFreeCallbacks(window)
                glfwDestroyWindow(window)
            }

            if (!gameInstance.rerun) break
        } finally {
            glfwTerminate()
        }
    } while (gameInstance.rerun)
}

