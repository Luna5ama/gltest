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
import kotlin.math.tan

class HBAO(private val scene: TerrainScene, private val solidLighting: SolidLighting) : IGLObjContainer by IGLObjContainer.Impl() {
    private val shader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/Blit.vert.glsl"),
            ShaderSource.Frag("terrain/hbao/HBAO2.frag") {
//                define("HBAO_DIR_SAMPLES", 8)
//                define("HBAO_STEP_SAMPLES", 32)
//                define("HBAO_DIR_SAMPLES", 4)
//                define("HBAO_STEP_SAMPLES", 4)
                define("HBAO_DIR_SAMPLES", 4)
                define("HBAO_STEP_SAMPLES", 16)
            }
        )
    )

    val finalTex = register(TextureObject.Texture2D()).apply { label("HBAO.Tex") }

    private var lastUpdateSizeBit = 0L

    val finalFbo = register(FramebufferObject())

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
            buffer(scene.rtwsm.uboBinding)
        }
//        val invTanRad = 1.0f / tan(scene.camera.fov.toRadians() * 0.5f)
        shader.uniform1f("uval_tangentBias", 10.0f.toRadians())
        val aoRadius = 16.0f
        val maxRadius = 64.0f
        shader.uniform2f("uval_radius", aoRadius, maxRadius)
        shader.uniform2f("uval_aoStrength", if (scene.gameInstance.toggle2) 0.0f else 2.0f, 1.0f)
        shader.uniform2f("uval_giStrength",  64.0f, 64.0f)
        val aspect = scene.camera.aspectRatio
        val tanHalfFov = tan(scene.camera.fov.toRadians() / 2.0f)
        shader.uniform3f("uval_projectParams", 1.0f / (aspect * tanHalfFov), 1.0f / tanHalfFov, aspect)
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

        if (timer.tickAndReset(1000)) {
            println("HBAO: %.2f ms".format(gpuTimer.averageMS()))
        }

        glViewport(0, 0, scene.camera.screenWidth, scene.camera.screenHeight)
    }
}