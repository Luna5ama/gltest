package dev.luna5ama.gltest.terrain.post

import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.TerrainScene
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.*
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.*
import dev.luna5ama.glwrapper.objects.*
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr
import dev.luna5ama.kmogus.memcpy

class AutoExposure(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val autoExposureShader = register(ShaderProgram(
        ShaderSource.Vert("terrain/post/AutoExposure.vert.glsl"),
        ShaderSource.Frag("terrain/post/AutoExposure.frag.glsl")
    )
)
    private val lumHisto = register(ShaderProgram(ShaderSource.Comp("terrain/post/LumHisto.comp.glsl")))
    private val lumAvg = register(ShaderProgram(ShaderSource.Comp("terrain/post/LumAvg.comp.glsl")))
    private val buffer = register(BufferObject.Immutable()).apply {
        allocate(256 * 4, 0)
    }
    private val avgLumTex = register(TextureObject.Texture2D()).apply {
        allocate(1, ImageFormat.R32_F, 1, 1)
    }

    private val readBuffer = register(BufferObject.Immutable()).apply {
        allocate(257 * 4L, GL_MAP_READ_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT)
        label = "AutoExposureReadBuffer"
    }
    private val mapped = readBuffer.map(GL_MAP_READ_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT)
    private val timer = TickTimer()

    init {
        MemoryStack {
            val arr = malloc(4L)
            arr.ptr.setFloat(0.0f)
            glClearTexImage(avgLumTex.id, 0, GL_RED, GL_FLOAT, arr.ptr)
        }
    }

    private val histogramBinding = ShaderBindingSpecs.Buffer("ssbo_histogramBuffer", buffer, BufferTarget.ShaderStorage)
    private val lumOutputBinding = ShaderBindingSpecs.Image("uimg_avgLum", avgLumTex)

    val avgLumSamplerBinding = ShaderBindingSpecs.Sampler("usam_avgLum", avgLumTex)

    fun update(fboGroup: FboGroup){
        val timeCoeff = 0.005f
        val minInc = 0.02f
        val maxInc = 0.1f
//        val inc = 0.05f
//        val minInc = inc
//        val maxInc = inc
        val pixelTotal = (fboGroup.input.fbo.width * fboGroup.input.fbo.height).toFloat()

        buffer.clear(GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)

        lumHisto.bind()
        lumHisto.applyBinding {
            sampler(fboGroup.inputBindings)
            buffer(histogramBinding)
        }
        glDispatchCompute((fboGroup.input.fbo.width + 15) / 16, (fboGroup.input.fbo.height + 15) / 16, 1)

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

        lumAvg.bind()
        lumAvg.applyBinding {
            image(lumOutputBinding)
            buffer(histogramBinding)
        }
        val topTargetPercent = 5
        val topTargetCount = fboGroup.input.fbo.width * fboGroup.input.fbo.height / (100.0 / topTargetPercent)
        lumAvg.uniform4f(
            "uParams",
            timeCoeff, topTargetCount.toFloat(), minInc, maxInc
        )
        glDispatchCompute(1, 1, 1)

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
    }

    fun render(fboGroup: FboGroup) {
        autoExposureShader.bind()
        autoExposureShader.applyBinding {
            sampler(avgLumSamplerBinding)
            sampler(fboGroup.inputBindings)

            buffer(scene.camera.noJitterUboBinding)
        }
        fboGroup.output.fbo.bind()

        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)

        scene.basic.drawQuad()

        fboGroup.swap()

//        if (timer.tickAndReset(1000)) {
//            readBuffer.bind(GL_PIXEL_PACK_BUFFER)
//            glGetTextureImage(avgLumTex.id, 0, GL_RED, GL_FLOAT, 4, 0L)
//            readBuffer.unbind(GL_PIXEL_PACK_BUFFER)
//
//            println("AvgLum: ${mapped.ptr.getFloat()}")
//
//            buffer.copyTo(readBuffer, 0L, 4L, 256 * 4)
//            val arr = IntArray(256)
//            memcpy(mapped.ptr, 4, arr, 0, 256 * 4)
//            val topBinV = arr.last().toDouble() / (fboGroup.input.fbo.width * fboGroup.input.fbo.height).toDouble()
//            println("TopBin: $topBinV")
//            println()
//        }
    }
}