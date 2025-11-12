package dev.luna5ama.gltest

import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.ShaderSource.Companion.invoke
import dev.luna5ama.glwrapper.base.GL_PROGRAM_BINARY_LENGTH
import dev.luna5ama.glwrapper.base.glGetProgramBinary
import dev.luna5ama.glwrapper.base.glGetProgrami
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

private fun dumpShaderAsm(program: ShaderProgram, name: String) {
    val binLen = glGetProgrami(program.id, GL_PROGRAM_BINARY_LENGTH)
    val file = File("shaderasm/$name.txt")
    file.parentFile.mkdirs()
    file.delete()
    FileChannel.open(
        file.toPath(),
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE
    ).use { channel ->
        val mapped = Arr.wrap(channel.map(FileChannel.MapMode.READ_WRITE, 0, binLen.toLong()))
        MemoryStack {
            val binaryFormat = calloc(4)
            glGetProgramBinary(program.id, binLen, Ptr.NULL, binaryFormat.ptr, mapped.ptr)
        }
    }
}

fun main() {
    System.setProperty("joml.forceUnsafe", "true")
    System.setProperty("joml.fastmath", "true")
    System.setProperty("joml.sinLookup", "true")
    System.setProperty("joml.format", "false")
    System.setProperty("joml.useMathFma", "true")

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
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, 1)
        glfwWindowHint(GLFW_DOUBLEBUFFER, GLFW_TRUE)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_SAMPLES, 0)

        val window = glfwCreateWindow(1920, 1080, "", 0, 0)
        glfwMakeContextCurrent(window)
        runCatching { GL.create() }
        val glCapabilities = GL.createCapabilities()
        try {
            val path = Path("""...""")
            val regex = "(\\d{3})_.*".toRegex()
            val shaders = path.listDirectoryEntries()
                .filter { it.name.matches(regex) }
                .groupBy { regex.matchEntire(it.name)!!.groupValues[1] }
                .mapValues { entry -> entry.value.associateBy { it.extension } }

            shaders.values.forEach { shaders ->
                shaders["csh"]?.let {
                    val program = ShaderProgram(ShaderSource.Comp(it.pathString))
                    dumpShaderAsm(program, it.name)
                }
                val rasterShaders = listOf(
                    ShaderSource.Vert.Companion to shaders["vsh"],
                    ShaderSource.Geom.Companion to shaders["gsh"],
                    ShaderSource.Frag.Companion to shaders["fsh"]
                )
                    .mapNotNull {
                        @Suppress("UNCHECKED_CAST")
                        it.takeIf { it.second != null } as Pair<ShaderSource.Provider<*>, Path>?
                    }
                if (rasterShaders.isNotEmpty()) {
                    val shaderSources = rasterShaders
                        .map { it.first.invoke(it.second.pathString) }
                        .toTypedArray()
                    val program = ShaderProgram(*shaderSources)
                    dumpShaderAsm(program, shaders["fsh"]!!.name)
                }
            }
        } finally {
            GL.destroy()
            glfwFreeCallbacks(window)
            glfwDestroyWindow(window)
        }

    } finally {
        glfwTerminate()
    }
}

