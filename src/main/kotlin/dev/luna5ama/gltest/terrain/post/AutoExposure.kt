package dev.luna5ama.gltest.terrain.post

import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.TerrainScene
import dev.luna5ama.glwrapper.*
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.*
import dev.luna5ama.glwrapper.objects.*
import dev.luna5ama.kmogus.MemoryStack

class AutoExposure(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val autoExposureShader = ShaderProgram(
        ShaderSource.Vert("/terrain/post/AutoExposure.vert.glsl"),
        ShaderSource.Frag("/terrain/post/AutoExposure.frag.glsl")
    )

    private val lumHisto = ShaderProgram(ShaderSource.Comp("/terrain/post/LumHisto.comp.glsl"))
    private val lumAvg = ShaderProgram(ShaderSource.Comp("/terrain/post/LumAvg.comp.glsl"))
    private val buffer = BufferObject.Immutable().apply {
        allocate(256 * 4, 0)
    }
    private val avgLumTex = TextureObject.Texture2D().apply {
        allocate(1, ImageFormat.R32_F, 1, 1)
    }

    private val readBuffer = BufferObject.Immutable().apply {
        allocate(4L, GL_MAP_READ_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT)
        label("AutoExposureReadBuffer")
    }
    private val mapped = readBuffer.map(GL_MAP_READ_BIT or GL_MAP_PERSISTENT_BIT or GL_MAP_COHERENT_BIT)
    private val timer = TickTimer()

    init {
        MemoryStack {
            val arr = malloc(4L)
            arr.ptr.setFloat(0.03f)
            glClearTexImage(avgLumTex.id, 0, GL_RED, GL_FLOAT, arr.ptr)
        }
    }

    private val histogramBinding = ShaderBindingSpecs.Buffer("ssbo_histogramBuffer", buffer, BufferTarget.ShaderStorage)
    private val lumOutputBinding = ShaderBindingSpecs.Image("uimg_avgLum", avgLumTex)

    val avgLumSamplerBinding = ShaderBindingSpecs.Sampler("usam_avgLum", avgLumTex)

    fun render(fboGroup: FboGroup) {
        val input = fboGroup.input
        val output = fboGroup.output

        val pRangePower = 4.0f
        val pLumRange = 4.0f
        val timeCoeff = 0.05f
        val pixelTotal = (input.fbo.width * input.fbo.height).toFloat()

        val targetLuma = 0.08f
        val minInc = 0.2f
        val maxInc = 1.0f
        val exposureMul = 1.0f

        lumHisto.bind()
        lumHisto.applyBinding {
            sampler(fboGroup.inputBindings)
            buffer(histogramBinding)
        }
        lumHisto.uniform4f(
            "uParams",
            pRangePower, 1.0f / pLumRange, timeCoeff, pixelTotal
        )
        glDispatchCompute((input.fbo.width + 15) / 16, (input.fbo.height + 15) / 16, 1)

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

        lumAvg.bind()
        lumAvg.applyBinding {
            image(lumOutputBinding)
            buffer(histogramBinding)
        }
        lumAvg.uniform4f(
            "uParams",
            1.0f / pRangePower, pLumRange, timeCoeff, pixelTotal
        )
        glDispatchCompute(1, 1, 1)

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

        autoExposureShader.bind()
        autoExposureShader.uniform4f("uParams", targetLuma, minInc, maxInc, exposureMul)
        autoExposureShader.applyBinding {
            sampler(avgLumSamplerBinding)
            sampler(fboGroup.inputBindings)

            buffer(scene.camera.noJitterUboBinding)
        }
        output.fbo.bind()

        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)

        scene.basic.drawQuad()

        fboGroup.swap()

//        if (timer.tickAndReset(1000)) {
//            readBuffer.bind(GL_PIXEL_PACK_BUFFER)
//            glGetTextureImage(lumOutput.id, 0, GL_RED, GL_FLOAT, 4, 0L)
//            readBuffer.unbind(GL_PIXEL_PACK_BUFFER)
//
//            println(mapped.ptr.getFloat())
//        }
    }
}