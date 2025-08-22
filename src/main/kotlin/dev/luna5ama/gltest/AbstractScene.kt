package dev.luna5ama.gltest

import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.SamplerManager
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.GL_PROGRAM_BINARY_LENGTH
import dev.luna5ama.glwrapper.base.glGetProgramBinary
import dev.luna5ama.glwrapper.base.glGetProgrami
import dev.luna5ama.glwrapper.enums.GLObjectType
import dev.luna5ama.glwrapper.objects.IGLObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

@Suppress("LeakingThis")
abstract class AbstractScene(val gameInstance: GameInstance, val mouse: Mouse, val keyboard: Keyboard) :
    IGLObjContainer by IGLObjContainer.Impl() {
    val camera = register(Camera(this))
    val samplerManager = register(SamplerManager())

    @Suppress("LeakingThis")
    val textureCache = register(TextureCache(this))

    val startTime = System.currentTimeMillis()
    var lastFrameTime = startTime
    var currentTime = System.currentTimeMillis()
    var frameTime = 0L
    var timeSinceStart = 0L

    fun update() {
        currentTime = System.currentTimeMillis()
        timeSinceStart = currentTime - startTime
        frameTime = currentTime - lastFrameTime
        lastFrameTime = currentTime
    }

    abstract suspend fun renderFrame()

    fun reloadShaders() {
        ShaderSource.clearCache()
        val objs = mutableMapOf<GLObjectType, MutableSet<IGLObject>>()
        collectObjs(objs)
        objs[GLObjectType.Program]?.forEach {
            if (it is ShaderProgram) {
                it.reload()
            }
        }
        dumpShaderAsm()
    }

    private fun dumpShaderAsm() {
        shaderAsmDumps().forEach { program ->
            dumpShaderAsm(program, program.label ?: "unnamed")
        }
    }

    protected open fun shaderAsmDumps() : List<ShaderProgram> {
        return emptyList()
    }

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
}