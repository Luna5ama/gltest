package dev.luna5ama.gltest.terrain

import dev.fastmc.common.TickTimer
import dev.fastmc.common.toRadians
import dev.luna5ama.gltest.GPUTimer
import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.terrain.post.GBuffer
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.objects.TextureObject

class HBAO(private val scene: TerrainScene, private val solidLighting: SolidLighting) : IGLObjContainer by IGLObjContainer.Impl() {
    private val shader = register(
        ShaderProgram(
            ShaderSource.Vert("/terrain/Blit.vert.glsl"),
            ShaderSource.Frag("/terrain/hbao/HBAO2.frag") {
                define("HBAO_DIR_SAMPLES", 4)
                define("HBAO_STEP_SAMPLES", 4)
            }
        )
    )

    val finalTex = register(TextureObject.Texture2D()).apply { label("HBAO.Tex") }

    private var lastUpdateSizeBit = 0L

    private val finalFbo = register(FramebufferObject())

    val texBinding = ShaderBindingSpecs.Sampler("usam_hbao", finalTex)

    fun render(input: GBuffer) {
        glDebugGroup("update") {
            update(input)
        }
        glDebugGroup("sample") {
            sample(input)
        }
    }

    private fun update(input: GBuffer) {
        val sizeBit = input.tex1.sizeBit
        if (sizeBit != lastUpdateSizeBit) {
            finalTex.apply {
                destroy()
                allocate(1, ImageFormat.R16_G16_B16_A16_F, input.width, input.height)
            }
            finalFbo.apply {
                clearAttachments()
                attachNonLayered(finalTex, GL_COLOR_ATTACHMENT0)
                setDrawBufferAll()
                check()
            }

            lastUpdateSizeBit = sizeBit
        }
    }

    private val gpuTimer = GPUTimer(100)
    private val timer = TickTimer()

    private fun sample(input: GBuffer) {
        glDisable(GL_DITHER)
        finalFbo.bind()
//        finalFbo.attachNonLayered(input.depthStencilTex, GL_DEPTH_STENCIL_ATTACHMENT)
        finalFbo.clearColor(0, 0.0f, 0.0f, 0.0f, 0.0f)
        finalFbo.clearColor(1, 0.0f, 0.0f, 0.0f, 0.0f)
        shader.bind()
        shader.applyBinding {
            sampler(input.genericTexBindings)
            sampler(solidLighting.texBingings)
            buffer(scene.camera.uboBinding)
        }
//        val invTanRad = 1.0f / tan(scene.camera.fov.toRadians() * 0.5f)
        shader.uniform1f("uval_tangentBias", 20.0f.toRadians())
        shader.uniform1f("uval_radius", 4.0f)
        shader.uniform2f("uval_aoStrength", 2.0f, 2.0f)
        shader.uniform1f("uval_giStrength", 1.0f)
        shader.uniform1ui("uval_idk", 1)
        shader.uniform1i("uval_randR", 2)
//        shader.uniform4f(
//            "uval_aoRes",
//            scene.camera.screenWidth.toFloat(),
//            scene.camera.screenHeight.toFloat(),
//            1.0f / scene.camera.screenWidth.toFloat(),
//            1.0f / scene.camera.screenHeight.toFloat()
//        )
//        shader.uniform2f(
//            "uval_focalLen",
//            invTanRad * (scene.camera.screenHeight.toFloat() / scene.camera.screenWidth.toFloat()),
//            invTanRad,
//        )
//        shader.uniform1f("uval_aoStrength", 0.9f)

        glViewport(0, 0, finalTex.width, finalTex.height)

        gpuTimer.begin()
        scene.basic.drawQuad()
        gpuTimer.end()

//        if (timer.tickAndReset(3000)) {
//            println("HBAO: %.2f ms".format(gpuTimer.averageMS()))
//        }

        glViewport(0, 0, scene.camera.screenWidth, scene.camera.screenHeight)
    }
}