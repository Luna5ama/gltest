package dev.luna5ama.gltest.cirrus

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

    private val binding = ShaderBindingSpecs.of {
        sampler("usam_noiseField", noiseField, samplerManager.get {
            filter(FilterMode.Linear, FilterMode.Linear)
            wrap(WrapMode.Repeat, WrapMode.Repeat)
        })
        sampler("usam_renderCount", renderCountImage, samplerManager.get {
            filter(FilterMode.Linear, FilterMode.Linear)
            wrap(WrapMode.ClampToEdge, WrapMode.ClampToEdge)
        })
        image("uimg_noiseField", noiseField)
        image("uimg_renderCount", renderCountImage)

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
    private val maxCounterShader = register(ShaderProgram(ShaderSource.Comp("cirrus/MaxCounter.comp.glsl")))

    private val basic = register(Basic())

    private var counter = 0L

    override suspend fun renderFrame() {
        resetCounterShader.bind()
        resetCounterShader.applyBinding(binding)
        glDispatchCompute(1, 1, 1)

//        repeat(16) {
            initNoiseFieldShader.bind()
            initNoiseFieldShader.applyBinding(binding)
            val div = 1024.0 * 1024.0
            initNoiseFieldShader.uniform1f("uval_noiseZ", (counter.toDouble() / div).toFloat())
            glDispatchCompute(NOISE_FIELD_SIZE / 16, NOISE_FIELD_SIZE / 16, 1)
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            counter = (counter + 1) % 32768

            updateParticleShader.bind()
            updateParticleShader.applyBinding(binding)
            glDispatchCompute(PARTICLE_COUNT / 128, 1, 1)
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
//        }

        maxCounterShader.bind()
        maxCounterShader.applyBinding(binding)
        glDispatchCompute(NOISE_FIELD_SIZE / 16, NOISE_FIELD_SIZE / 16, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

        glViewport(0, 0, NOISE_FIELD_SIZE, NOISE_FIELD_SIZE)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        finalBlitShader.bind()
        finalBlitShader.applyBinding(binding)
        basic.drawQuad()
    }

    override fun shaderAsmDumps(): List<ShaderProgram> {
        return emptyList()
    }
}