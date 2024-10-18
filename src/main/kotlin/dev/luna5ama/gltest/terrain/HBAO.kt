package dev.luna5ama.gltest.terrain

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
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

class HBAO(private val scene: TerrainScene, private val solidLighting: SolidLighting) : IGLObjContainer by IGLObjContainer.Impl() {
    private val steps = 8

    private fun stepC(): Double {
        return 2.0.pow(steps) - 1.0
    }

    private fun baseLodToRadius(baseLod: Double): Double {
        return 1.0 + stepC() * 2.0.pow(baseLod)
    }

    private fun radiusToBaseLod(radius: Double): Double {
        return log2((radius - 1.0) / stepC())
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
            ShaderSource.Frag("terrain/hbao/HBAO3.frag") {
                define("HBAO_DIR_SAMPLES", 2)
                define("HBAO_STEP_SAMPLES", steps)
            }
        )
    )

    private val linearDepthTex = register(TextureObject.Texture2D()).apply { label = "HBAO.LinearDepth" }
    private val linearDepthTexBinding = ShaderBindingSpecs.Sampler("usam_linearDepth", linearDepthTex)
    val finalTex = register(TextureObject.Texture2D()).apply { label = "HBAO.Tex" }

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
                attach(finalTex, GL_COLOR_ATTACHMENT0)
                setDrawBufferAll()
                check()
            }

            val levelsRequired = radiusToBaseLod(max(input.width, input.height).toDouble()).ceilToInt() + steps

            linearDepthTex.apply {
                destroy()
                setMipLevels(0, levelsRequired)
                setMipLod(0.0f, levelsRequired.toFloat())
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

    private val gpuTimer = GPUTimer(100)
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
        val tangentAngleBias = 30.0f.toRadians()
        shader.uniform1f("uval_tangentBias", tangentAngleBias)
        val aoRadius = 3.0f
        val maxRadius = 4.0f
        shader.uniform2f("uval_radius", aoRadius, maxRadius)
        shader.uniform2f("uval_aoStrength", if (scene.gameInstance.toggle2) 0.0f else 1.0f, 2.0f)
        val aspect = scene.camera.aspectRatio
        val tanHalfFov = tan(scene.camera.fov.toRadians() / 2.0f)
        shader.uniform3f("uval_projectParams", 1.0f / (aspect * tanHalfFov), 1.0f / tanHalfFov, aspect)

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