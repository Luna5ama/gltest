package dev.luna5ama.gltest.fastclear

import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.*
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.kmogus.MemoryStack
import java.util.*

class FastClearScene(gameInstance: GameInstance, mouse: Mouse, keyboard: Keyboard) :
    AbstractScene(gameInstance, mouse, keyboard) {

    val myTexSize = 8192

    private val r32uiImage = register(TextureObject.Texture2D()).apply {
        label = "r32uiImage"
        allocate(1, ImageFormat.R32_UI, myTexSize, myTexSize)
    }

    private val r32uifbo = register(FramebufferObject()).apply {
        label = "r32uifbo"
        attach(r32uiImage, GL_COLOR_ATTACHMENT0)
        setDrawBufferAll()
    }

    private val rgba8unImage = register(TextureObject.Texture2D()).apply {
        label = "rgba8unImage"
        allocate(1, ImageFormat.R8G8B8A8_UN, myTexSize, myTexSize)
    }

    private val rgba8unfbo = register(FramebufferObject()).apply {
        label = "rgba8unfbo"
        attach(rgba8unImage, GL_COLOR_ATTACHMENT0)
        setDrawBufferAll()
    }

    private val rgba16fImage = register(TextureObject.Texture2D()).apply {
        label = "rgba16fImage"
        allocate(1, ImageFormat.R16G16B16A16_F, myTexSize, myTexSize)
    }

    private val rgba16ffbo = register(FramebufferObject()).apply {
        label = "rgba16ffbo"
        attach(rgba16fImage, GL_COLOR_ATTACHMENT0)
        setDrawBufferAll()
    }

    private val bindings = ShaderBindingSpecs.of {
        image("r32uiImage", r32uiImage)
        image("rgba8unImage", rgba8unImage)
        image("rgba16fImage", rgba16fImage)
    }

    private val randomShader = object : ShaderProgram.Variants<ClearFormat>() {
        override fun create(key: ClearFormat): ShaderProgram {
            return register(ShaderProgram(ShaderSource.Comp("fastclear/Random.glsl") {
                define("FAST_CLEAR_FORMAT", key.ordinal)
            }))
        }
    }

    private val clearShader = object : ShaderProgram.Variants<Pair<ClearFormat, ClearType>>() {
        override fun create(key: Pair<ClearFormat, ClearType>): ShaderProgram {
            return register(ShaderProgram(ShaderSource.Comp("fastclear/Clear.glsl") {
                define("FAST_CLEAR_FORMAT", key.first.ordinal)
                define("CLEAR_TYPE", key.second.ordinal)
            }))
        }
    }

    private val clear2x2Shader = object : ShaderProgram.Variants<Pair<ClearFormat, ClearType>>() {
        override fun create(key: Pair<ClearFormat, ClearType>): ShaderProgram {
            return register(ShaderProgram(ShaderSource.Comp("fastclear/Clear2x2.glsl") {
                define("FAST_CLEAR_FORMAT", key.first.ordinal)
                define("CLEAR_TYPE", key.second.ordinal)
            }))
        }
    }

    private val clear4x4Shader = object : ShaderProgram.Variants<Pair<ClearFormat, ClearType>>() {
        override fun create(key: Pair<ClearFormat, ClearType>): ShaderProgram {
            return register(ShaderProgram(ShaderSource.Comp("fastclear/Clear4x4.glsl") {
                define("FAST_CLEAR_FORMAT", key.first.ordinal)
                define("CLEAR_TYPE", key.second.ordinal)
            }))
        }
    }

    private fun fillRandom(format: ClearFormat) {
        val rng = Random(1145141919810L)
        val shader = randomShader[format]
        shader.bind()
        shader.applyBinding(bindings)
        shader.uniform2ui("uval_seed", rng.nextInt(), rng.nextInt())
        glDispatchCompute(myTexSize / 16, myTexSize / 16, 1)
    }

    private fun glClearTexImage(format: ClearFormat, type: ClearType) {
        val image = when (format) {
            ClearFormat.R32UI -> r32uiImage
            ClearFormat.RGBA8UN -> rgba8unImage
            ClearFormat.RGBA16F -> rgba16fImage
        }
        val clearFormat = when (format) {
            ClearFormat.R32UI -> GL_RGBA_INTEGER
            ClearFormat.RGBA8UN -> GL_RGBA
            ClearFormat.RGBA16F -> GL_RGBA
        }
        val clearType = when (format) {
            ClearFormat.R32UI -> GL_UNSIGNED_INT
            ClearFormat.RGBA8UN -> GL_UNSIGNED_BYTE
            ClearFormat.RGBA16F -> GL_HALF_FLOAT
        }
        MemoryStack {
            val clearDataPtr = calloc(16L).ptr
            when (type) {
                ClearType.ZERO -> {
                    // already zeroed
                }

                ClearType.ONE -> {
                    when (clearType) {
                        GL_UNSIGNED_INT -> {
                            repeat(4) {
                                clearDataPtr.setInt(it * 4L, 1)
                            }
                        }

                        GL_UNSIGNED_BYTE -> {
                            repeat(16) {
                                clearDataPtr.setByte(it.toLong(), 1)
                            }
                        }

                        GL_HALF_FLOAT -> {
                            val oneHalfFloat = java.lang.Float.floatToFloat16(1.0f)
                            repeat(8) {
                                clearDataPtr.setShort(it * 2L, oneHalfFloat)
                            }
                        }
                    }
                }

                ClearType.ONE_BITS -> {
                    repeat(4) {
                        clearDataPtr.setInt(it * 4L, 0xFFFFFFFF.toInt())
                    }
                }
            }

            glClearTexImage(image.id, 0, clearFormat, clearType, clearDataPtr)
        }
    }

    private fun manualClear(format: ClearFormat, type: ClearType, kind: Int) {
        val shaders = when (kind) {
            1 -> clearShader
            2 -> clear2x2Shader
            4 -> clear4x4Shader
            else -> error("Invalid kind: $kind")
        }
        val shader = shaders[format to type]
        shader.bind()
        shader.applyBinding(bindings)
        val size = myTexSize / kind / 16
        glDispatchCompute(size, size, 1)
    }

    private fun glClear(format: ClearFormat, type: ClearType) {
        val fbo = when (format) {
            ClearFormat.R32UI -> r32uifbo
            ClearFormat.RGBA8UN -> rgba8unfbo
            ClearFormat.RGBA16F -> rgba16ffbo
        }
        fbo.bind()
        val clearValue = when (type) {
            ClearType.ZERO -> 0f
            ClearType.ONE -> 1f
            ClearType.ONE_BITS -> Float.fromBits(-1)
        }
        glClearColor(clearValue, clearValue, clearValue, clearValue)
        glClear(GL_COLOR_BUFFER_BIT)
    }

    private fun glClearBuffer(format: ClearFormat, type: ClearType) {
        val fbo = when (format) {
            ClearFormat.R32UI -> r32uifbo
            ClearFormat.RGBA8UN -> rgba8unfbo
            ClearFormat.RGBA16F -> rgba16ffbo
        }
        fbo.bind()
        MemoryStack {
            val ptr = calloc(16L).ptr
            when (format.dataType) {
                DataType.FLOAT -> {
                    val clearValue = when (type) {
                        ClearType.ZERO -> 0f
                        ClearType.ONE -> 1f
                        ClearType.ONE_BITS -> Float.fromBits(-1)
                    }
                    ptr.setFloatInc(clearValue).setFloatInc(clearValue).setFloatInc(clearValue).setFloatInc(clearValue)
                    glClearBufferfv(GL_COLOR, 0, ptr)
                }

                DataType.UINT, DataType.INT -> {
                    val clearValue = when (type) {
                        ClearType.ZERO -> 0
                        ClearType.ONE -> 1
                        ClearType.ONE_BITS -> -1
                    }
                    ptr.setIntInc(clearValue).setIntInc(clearValue).setIntInc(clearValue).setIntInc(clearValue)
                    if (format.dataType == DataType.UINT) {
                        glClearBufferuiv(GL_COLOR, 0, ptr)
                    } else {
                        glClearBufferiv(GL_COLOR, 0, ptr)
                    }
                }
            }
        }
    }

    private var timerIndex = 0
    private val gpuTimers = mutableListOf<GPUTimer>()

    fun getTimer(): GPUTimer {
        if (timerIndex >= gpuTimers.size) {
            gpuTimers.add(GPUTimer(10))
        }
        return gpuTimers[timerIndex++]
    }

    private var print = false
    private val printTimer = TickTimer()

    override suspend fun renderFrame() {
        camera.updateScreenSize()

        timerIndex = 0
        print = printTimer.tickAndReset(3000)

        glDebugGroup("Clear FBO") {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glViewport(0, 0, camera.screenWidth, camera.screenHeight)
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            glClear(GL_COLOR_BUFFER_BIT)
        }
        glDebugGroup("Initial glClearTexImage") {
            ClearFormat.entries.forEach {
                glClearTexImage(it, ClearType.ZERO)
            }
        }
        glFinish()

        ClearFormat.entries.forEach { format ->
            bench(format)
        }
        if (print) {
            println("===================================================\n")
        }
        glFinish()
        Thread.sleep(5)
    }

    private fun bench(format: ClearFormat) {
        if (print) {
            println("=== Format: ${format.name} ===")
        }
        glDebugGroup(format.name) {
            ClearType.entries.forEach { clearType ->
                if (print) {
                    println("--- Clear Type: ${clearType.name} ---")
                }
                glDebugGroup(clearType.name) {
                    bench("glClearTexImage", format, clearType, this::glClearTexImage)
                    bench("glClear", format, clearType, this::glClear)
                    bench("glClearBuffer", format, clearType, this::glClearBuffer)
                    bench("Manual Clear 1x1", format, clearType, 1)
                    bench("Manual Clear 2x2", format, clearType, 2)
                    bench("Manual Clear 4x4", format, clearType, 4)
                }
                if (print) {
                    println()
                }
            }
        }
        if (print) {
            println("\n")
        }
        glFinish()
    }

    private fun bench(
        name: String,
        clearFormat: ClearFormat,
        clearType: ClearType,
        kind: Int,
    ) {
        bench(name, clearFormat, clearType) { a, b -> manualClear(a, b, kind) }
    }

    private inline fun bench(
        name: String,
        clearFormat: ClearFormat,
        clearType: ClearType,
        func: (ClearFormat, ClearType) -> Unit
    ) {
        fillRandom(clearFormat)
        glMemoryBarrier(GL_ALL_BARRIER_BITS)
        glDebugGroup(name) {
            val timer = getTimer()
            timer.begin()
            func(clearFormat, clearType)
            timer.end()
            if (print) {
                println("$name: ${"%.4f".format(timer.averageMS())} ms")
            }
        }
        glMemoryBarrier(GL_ALL_BARRIER_BITS)
    }

    enum class DataType {
        FLOAT,
        UINT,
        INT
    }

    enum class ClearFormat(val dataType: DataType) {
        R32UI(DataType.UINT),
        RGBA8UN(DataType.FLOAT),
        RGBA16F(DataType.FLOAT)
    }

    enum class ClearType {
        ZERO,
        ONE,
        ONE_BITS
    }
}