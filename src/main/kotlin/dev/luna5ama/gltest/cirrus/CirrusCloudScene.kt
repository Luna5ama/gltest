package dev.luna5ama.gltest.cirrus

import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.*
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.enums.FilterMode
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.enums.WrapMode
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.kmogus.MemoryStack
import org.lwjgl.glfw.GLFW.glfwSwapInterval
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.absolute

private const val PARTICLE_COUNT = 1024 * 1024
private const val NOISE_FIELD_SIZE = 1024

class CirrusCloudScene(gameInstance: GameInstance, mouse: Mouse, keyboard: Keyboard) :
    AbstractScene(gameInstance, mouse, keyboard) {

    private val noiseField = register(TextureObject.Texture2D()).apply {
        label = "noiseField"
        allocate(1, ImageFormat.R16G16_SN, NOISE_FIELD_SIZE, NOISE_FIELD_SIZE)
    }

    private val renderCountImage = register(TextureObject.Texture2D()).apply {
        label = "renderCountImage"
        allocate(1, ImageFormat.R32_SI, NOISE_FIELD_SIZE, NOISE_FIELD_SIZE)
    }

    private val particleDataBuffer = register(BufferObject.Immutable()).apply {
        label = "particleDataBuffer"
        allocate(PARTICLE_COUNT * 32L, 0)
        MemoryStack {
            val temp = malloc(4L)
            temp.ptr.setInt((-1.0f).toRawBits())
            clear(GL_R32F, GL_RED, GL_FLOAT, temp.ptr)
        }
    }

    private val normalizedImage = register(TextureObject.Texture2D()).apply {
        label = "normalizedImage"
        allocate(1, ImageFormat.R16_UN, NOISE_FIELD_SIZE, NOISE_FIELD_SIZE)
    }

    private val tiledImage = register(TextureObject.Texture2D()).apply {
        label = "tiledImage"
        allocate(1, ImageFormat.R8_UN, NOISE_FIELD_SIZE, NOISE_FIELD_SIZE)
    }

    private val binding = ShaderBindingSpecs.of {
        sampler("usam_noiseField", noiseField, samplerManager.get {
            filter(FilterMode.Linear, FilterMode.Linear)
            wrap(WrapMode.Repeat, WrapMode.Repeat)
        })
        sampler("usam_renderCount", renderCountImage, samplerManager.get {
            filter(FilterMode.Linear, FilterMode.Linear)
            wrap(WrapMode.ClampToEdge, WrapMode.ClampToEdge)
        })
        sampler("usam_normalizedImage", normalizedImage, samplerManager.get {
            filter(FilterMode.Linear, FilterMode.Linear)
            wrap(WrapMode.MirroredRepeat, WrapMode.MirroredRepeat)
        })
        sampler("usam_tiledImage", tiledImage, samplerManager.get {
            filter(FilterMode.Linear, FilterMode.Linear)
            wrap(WrapMode.ClampToEdge, WrapMode.ClampToEdge)
        })
        image("uimg_noiseField", noiseField)
        image("uimg_renderCount", renderCountImage)
        image("uimg_normalizedImage", normalizedImage)
        image("uimg_tiledImage", tiledImage)

        buffer("ParticleDataBuffer", particleDataBuffer, BufferTarget.ShaderStorage)
    }

    private val initNoiseFieldShader = register(ShaderProgram(ShaderSource.Comp("cirrus/InitNoiseField.comp.glsl") {
        define("NOISE_FIELD_SIZE", NOISE_FIELD_SIZE)
        define("PARTICLE_COUNT", PARTICLE_COUNT)
    }))
    private val updateParticleShader = register(ShaderProgram(ShaderSource.Comp("cirrus/UpdateParticles.comp.glsl") {
        define("PARTICLE_COUNT", PARTICLE_COUNT)
        define("NOISE_FIELD_SIZE", NOISE_FIELD_SIZE)
    }))
    private val finalBlitShader = register(
        ShaderProgram(
            ShaderSource.Vert("cirrus/Blit.vert.glsl"),
            ShaderSource.Frag("cirrus/Blit.frag.glsl")
        )
    )
    private val resetCounterShader = register(ShaderProgram(ShaderSource.Comp("cirrus/ResetCounter.comp.glsl")))
    private val rangeCounterShader = register(ShaderProgram(ShaderSource.Comp("cirrus/RangeCounter.comp.glsl")))
    private val normalizeShader = register(ShaderProgram(ShaderSource.Comp("cirrus/Normalize.comp.glsl")))
    private val tilingShader = register(ShaderProgram(ShaderSource.Comp("cirrus/Tiling.comp.glsl")))

    private val fooShader = register(ShaderProgram(ShaderSource.Comp("cirrus/Foo.comp.glsl"))).apply {
        label = "Foo"
    }
    private val barShader = register(ShaderProgram(ShaderSource.Comp("cirrus/Bar.comp.glsl"))).apply {
        label = "Bar"
    }

    private val basic = register(Basic())

    private var counter = 0L
    private val counterMax = 2048
    private var saved = false

    private val printTimer = TickTimer()

    init {
        glfwSwapInterval(0)
    }

    override suspend fun renderFrame() {
        resetCounterShader.bind()
        resetCounterShader.applyBinding(binding)
        glDispatchCompute(1, 1, 1)

        repeat(2) {
            if (counter < counterMax) {
                initNoiseFieldShader.bind()
                initNoiseFieldShader.applyBinding(binding)
                val div = 1024.0 * 16.0
                initNoiseFieldShader.uniform1f("uval_noiseZ", (counter.toDouble() / div).toFloat())
                glDispatchCompute(NOISE_FIELD_SIZE / 16, NOISE_FIELD_SIZE / 16, 1)
                glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
                counter = (counter + 1) % 32768

                updateParticleShader.bind()
                updateParticleShader.applyBinding(binding)
                glDispatchCompute(PARTICLE_COUNT / 128, 1, 1)
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            } else {
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
                glfwSwapInterval(1)

                if (!saved) {
                    saved = true
                    saveImage()
                }
            }
        }
        if (printTimer.tickAndReset(3000)) {
            println(counter)
        }

        rangeCounterShader.bind()
        rangeCounterShader.applyBinding(binding)
        glDispatchCompute(NOISE_FIELD_SIZE / 16, NOISE_FIELD_SIZE / 16, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

        normalizeShader.bind()
        normalizeShader.applyBinding(binding)
        glDispatchCompute(NOISE_FIELD_SIZE / 16, NOISE_FIELD_SIZE / 16, 1)
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

        tilingShader.bind()
        tilingShader.applyBinding(binding)
        glDispatchCompute(NOISE_FIELD_SIZE / 16, NOISE_FIELD_SIZE / 16, 1)
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

        glViewport(0, 0, NOISE_FIELD_SIZE, NOISE_FIELD_SIZE)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        finalBlitShader.bind()
        finalBlitShader.applyBinding(binding)
        basic.drawQuad()
    }

    private fun saveImage() {
        println("Saving image...")
        val buffer = BufferObject.Immutable()
        buffer.allocate(NOISE_FIELD_SIZE * NOISE_FIELD_SIZE * 1L, GL_MAP_READ_BIT)
        buffer.bind(GL_PIXEL_PACK_BUFFER)
        glGetTextureImage(tiledImage.id, 0, GL_RED, GL_UNSIGNED_BYTE, buffer.size.toInt(), 0)
        glFinish()
        buffer.unbind(GL_PIXEL_PACK_BUFFER)
        val mapped = buffer.map(GL_MAP_READ_BIT)
        val ptr = mapped.ptr
        val outputImage = BufferedImage(NOISE_FIELD_SIZE, NOISE_FIELD_SIZE, BufferedImage.TYPE_BYTE_GRAY)
        val tempArray = IntArray(1)
        for (y in 0..<NOISE_FIELD_SIZE) {
            for (x in 0..<NOISE_FIELD_SIZE) {
                val value = ptr.getByte((y * NOISE_FIELD_SIZE + x).toLong())
                tempArray[0] = value.toUByte().toInt()
                outputImage.raster.setPixel(x, NOISE_FIELD_SIZE - 1 - y, tempArray)
            }
        }
        val outputDir = Path("cirrus").absolute()
        val numFiles = outputDir.toFile().list().size
        ImageIO.write(outputImage, "png", outputDir.resolve("cirrus_$numFiles.png").toFile())
        buffer.unmap()
        buffer.destroy()
    }

    override fun shaderAsmDumps(): List<ShaderProgram> {
        return listOf(fooShader, barShader)
    }
}