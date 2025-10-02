package dev.luna5ama.gltest.noisetex

import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.*
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.ShaderSource.Companion.invoke
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.enums.FilterMode
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.enums.WrapMode
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.TextureObject
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFW.glfwSwapInterval
import java.awt.image.BufferedImage
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories

private const val NOISE_TEX_SIZE = 1024

class Curl2DNoiseTexScene(gameInstance: GameInstance, mouse: Mouse, keyboard: Keyboard) :
    AbstractScene(gameInstance, mouse, keyboard) {

    private val noiseImage = register(TextureObject.Texture2D()).apply {
        label = "noiseImage"
        allocate(1, ImageFormat.R32G32_F, NOISE_TEX_SIZE, NOISE_TEX_SIZE)
    }

    private val normalizedImage = register(TextureObject.Texture2D()).apply {
        label = "normalizedImage"
        allocate(1, ImageFormat.R16G16_SN, NOISE_TEX_SIZE, NOISE_TEX_SIZE)
    }

    private val dataBuffer = register(BufferObject.Immutable()).apply {
        label = "dataBuffer"
        allocate(16L, 0)
    }

    private val binding = ShaderBindingSpecs.of {
        image("uimg_noiseImage", noiseImage)
        image("uimg_normalizedImage", normalizedImage)
        sampler("usam_normalizedImage", normalizedImage, samplerManager.get {
            filter(FilterMode.Linear, FilterMode.Linear)
            wrap(WrapMode.Repeat, WrapMode.Repeat)
        })
        buffer("DataBuffer", dataBuffer, BufferTarget.ShaderStorage)
    }

    private var generateNoiseShader: ShaderProgram? = null
    private val resetCounterShader = register(ShaderProgram(ShaderSource.Comp("noisetex/ResetCounter.comp.glsl")))
    private val rangeCounterShader = register(ShaderProgram(ShaderSource.Comp("noisetex/RangeCounterCurl2D.comp.glsl")))
    private val normalizeShader = register(ShaderProgram(ShaderSource.Comp("noisetex/NormalizeCurl2D.comp.glsl")))


    private val finalBlitShader = register(
        ShaderProgram(
            ShaderSource.Vert("noisetex/Blit.vert.glsl"),
            ShaderSource.Frag("noisetex/Blit.frag.glsl") {
                define("NOISE_TEX_SIZE", NOISE_TEX_SIZE)
                define("SIGNED")
            }
        )
    )

    private val basic = register(Basic())
    private val beginTime = System.nanoTime()
    private var frameCounter = 0
    private val timer = TickTimer()
    private var lastSource = ""

    private fun getUpdatedShaderProgram(): ShaderProgram? {
        val src = ShaderSource.Comp("noisetex/GenerateNoiseCurl2D.comp.glsl")
        val srcStr = src.resolveCodeSrc()
        if (generateNoiseShader == null || lastSource != srcStr) {
            lastSource = srcStr
            val newProgram = try {
                ShaderProgram(src {
                    define("NOISE_TEX_SIZE", NOISE_TEX_SIZE)
                })
            } catch (_: IllegalStateException) {
                return null
            }
            generateNoiseShader?.destroy()
            generateNoiseShader = newProgram
            return newProgram
        }

        return null
    }

    init {
        glfwSwapInterval(2)

        keyboard.register(GLFW.GLFW_KEY_S) {
            if (it == GLFW.GLFW_PRESS) {
                saveImage()
            }
        }
    }

    override suspend fun renderFrame() {
        camera.updateScreenSize()

        if (timer.tickAndReset(10)) {
            var updated = getUpdatedShaderProgram()
//            updated = generateNoiseShader
            if (updated != null) {
                resetCounterShader.bind()
                resetCounterShader.applyBinding(binding)
                glDispatchCompute(1, 1, 1)

                val currTime = System.nanoTime()
                val time = (currTime - beginTime).toDouble() / TimeUnit.SECONDS.toNanos(1L).toDouble()
                updated.bind()
                updated.applyBinding(binding)
                updated.uniform1f("uval_time", time.toFloat())
                glDispatchCompute(NOISE_TEX_SIZE / 16, NOISE_TEX_SIZE / 16, 1)

                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

                rangeCounterShader.bind()
                rangeCounterShader.applyBinding(binding)
                glDispatchCompute(NOISE_TEX_SIZE / 32, NOISE_TEX_SIZE / 32, 1)
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

                normalizeShader.bind()
                normalizeShader.applyBinding(binding)
                glDispatchCompute(NOISE_TEX_SIZE / 16, NOISE_TEX_SIZE / 16, 1)
                glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            }
        }

        val windowSize = minOf(camera.screenWidth, camera.screenHeight)
        glViewport(0, 0, windowSize, windowSize)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        finalBlitShader.bind()
        finalBlitShader.applyBinding(binding)
        finalBlitShader.uniform1f("uval_screenSize", windowSize.toFloat())
        finalBlitShader.uniform1ui("uval_frameCounter", frameCounter)
        basic.drawQuad()

        frameCounter++
    }

    private fun saveImage() {
        println("Saving image...")
        val buffer = BufferObject.Immutable()
        buffer.allocate(NOISE_TEX_SIZE * NOISE_TEX_SIZE * 4L, GL_MAP_READ_BIT)
        buffer.bind(GL_PIXEL_PACK_BUFFER)
        glGetTextureImage(normalizedImage.id, 0, GL_RG, GL_SHORT, buffer.size.toInt(), 0)
        glFinish()
        buffer.unbind(GL_PIXEL_PACK_BUFFER)
        val mapped = buffer.map(GL_MAP_READ_BIT)


        val outputPath = Path("I:\\code\\mcshaders\\Alpha-Piscium\\shaders\\textures\\water_hf_curl.bin")
        FileChannel.open(outputPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)
            .use { fc ->
                Arena.ofConfined().use { arena ->
                    val mappedFileBuffer = fc.map(FileChannel.MapMode.READ_WRITE, 0, buffer.size, arena)
                    val wrapped = MemorySegment.ofAddress(mapped.ptr.address).reinterpret(mapped.len)
                    mappedFileBuffer.copyFrom(wrapped)
                }
            }

        buffer.unmap()
        buffer.destroy()
    }

    override fun shaderAsmDumps(): List<ShaderProgram> {
        timer.reset(-999999)
        return super.shaderAsmDumps()
    }
}