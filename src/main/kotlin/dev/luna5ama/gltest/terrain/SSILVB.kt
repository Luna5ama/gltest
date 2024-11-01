package dev.luna5ama.gltest.terrain

import dev.fastmc.common.MathUtil
import dev.fastmc.common.TickTimer
import dev.fastmc.common.ceilToInt
import dev.fastmc.common.toRadians
import dev.luna5ama.gltest.GPUTimer
import dev.luna5ama.gltest.HiZBufferGen
import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.terrain.post.GBuffer
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.*
import kotlin.math.*

class SSILVB(private val scene: TerrainScene, private val solidLighting: SolidLighting) : IGLObjContainer by IGLObjContainer.Impl() {
//    private val steps = 16
//    private fun radiusToLodStep(y: Double): Double {
//        val a0 = 0.0771784
//        val a1 = 0.139577
//        val a2 = -1.34389
//        val yClamped = MathUtil.clamp(y, 16.0, 32768.0)
//        return a0 * log2(a1 * yClamped + a2)
//    }

    private val steps = 32
    private fun radiusToLodStep(y: Double): Double {
        val a0 = 0.0382693
        val a1 = 0.0663488
        val a2 = -1.20751
        val yClamped = MathUtil.clamp(y, 16.0, 32768.0)
        return a0 * log2(a1 * yClamped + a2)
    }

    private fun lodStepToRadius(x: Double): Double {
        return (0..<steps).sumOf {
            2.0.pow(x * it)
        }
    }

    private val linearDepthShader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/Blit.vert.glsl"),
            ShaderSource.Frag("terrain/hbao/LinearDepth.frag")
        )
    )

    val shader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/Blit.vert.glsl"),
            ShaderSource.Frag("terrain/hbao/SSILVB.frag") {
                define("SSILVB_SAMPLE_SLICES", 1)
                define("SSILVB_SAMPLE_STEPS", steps)
            }
        )
    ).apply {
        label = "SSILVB"
    }

    private val linearDepthTex = register(TextureObject.Texture2D()).apply { label = "HBAO.LinearDepth" }
    private val linearDepthTexBinding = ShaderBindingSpecs.Sampler("usam_linearDepth", linearDepthTex)
    val finalTex = register(TextureObject.Texture2D()).apply { label= "HBAO.Tex" }

    private var lastUpdateSizeBit = 0L

    private val linearDepthFbo = register(FramebufferObject())
    val finalFbo = register(FramebufferObject())

    val texBinding = ShaderBindingSpecs.Sampler("usam_hbao", finalTex)

    fun render(input: GBuffer) {
        glDebugGroup("update") {
            update(input)
        }
        glDebugGroup("linearDepth") {
            buildLinearDepth(input)
        }
        glDebugGroup("evaluate") {
            sample(input)
        }
    }

    private fun update(input: GBuffer) {
        val sizeBit = input.tex1.sizeBit
        if (sizeBit != lastUpdateSizeBit) {
            finalTex.apply {
                destroy()
                allocate(1, ImageFormat.R16G16B16A16_F, input.width, input.height)
            }

            finalFbo.apply {
                clearAttachments()
                attach(finalTex, GL_COLOR_ATTACHMENT0)
                setDrawBufferAll()
                check()
            }

            val levelsRequired = min(log2(max(input.width, input.height).toDouble()).ceilToInt(), 13)

            linearDepthTex.apply {
                destroy()
                setMipLevels(0, levelsRequired - 1)
                setMipLod(0.0f, levelsRequired - 1.0f)
                setFilter(GL_NEAREST_MIPMAP_NEAREST, GL_NEAREST)
                setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
                allocate(levelsRequired, ImageFormat.R32_F, input.width, input.height)
            }

            linearDepthFbo.apply {
                clearAttachments()
                attach(linearDepthTex, GL_COLOR_ATTACHMENT0)
                setDrawBufferAll()
                check()
            }

            lastUpdateSizeBit = sizeBit
        }
    }

    private val gpuTimer1 = GPUTimer(100)
    private val gpuTimer2 = GPUTimer(100)
    private val timer = TickTimer()

    private fun buildLinearDepth(input: GBuffer) {
        linearDepthFbo.bind()
        linearDepthShader.bind()
        linearDepthShader.applyBinding {
            sampler(input.genericTexBindings)
            buffer(scene.camera.uboBinding)
        }
        scene.basic.drawQuad()

        scene.hizGen.processColor(linearDepthTex, linearDepthTex, HiZBufferGen.KernelType.MAX)
    }

    private fun sample(input: GBuffer) {
        glDisable(GL_DITHER)
        finalFbo.bind()
        shader.bind()
        shader.applyBinding {
            sampler(input.genericTexBindings)
            sampler(solidLighting.texBingings)
            sampler(linearDepthTexBinding)
            buffer(scene.camera.uboBinding)
            buffer(scene.rtwsm.uboBinding)
        }
        val aoRadius = 8.0f
        val maxRadius = aoRadius * 4.0f
        val baseThickness = 0.05f
        shader.uniform1f("uval_thickness", baseThickness)
        shader.uniform4f("uval_radius", aoRadius, maxRadius, aoRadius * aoRadius, maxRadius * maxRadius)
        shader.uniform2f("uval_aoStrength", if (scene.gameInstance.toggles[1]) 0.0f else 1.0f, 2.0f)


        val aspect = scene.camera.aspectRatio
        val tanHalfFov = tan(scene.camera.fov.toRadians() / 2.0f)
        shader.uniform3f("uval_projectParams", 1.0f / (aspect * tanHalfFov), 1.0f / tanHalfFov, aspect)

        glViewport(0, 0, finalTex.width, finalTex.height)

        gpuTimer1.begin()
        glDebugGroup("Sample") {
            scene.basic.drawQuad()
        }
        gpuTimer1.end()

        gpuTimer2.begin()
        glDebugGroup("Denoise") {
            scene.denoiser.process(finalTex, scene.solidVelocity.ndpTex, 1)
        }
        gpuTimer2.end()

        if (timer.tickAndReset(1000)) {
            println("SSILVB: %.2f ms".format(gpuTimer1.averageMS()))
            println("Denoiser: %.2f ms".format(gpuTimer2.averageMS()))
        }

        glViewport(0, 0, scene.camera.screenWidth, scene.camera.screenHeight)
    }
}