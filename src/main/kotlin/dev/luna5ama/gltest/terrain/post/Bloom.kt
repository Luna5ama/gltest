package dev.luna5ama.gltest.terrain.post

import dev.luna5ama.gltest.linearClampEdge
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.gltest.terrain.TerrainScene
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.TextureObject

class Bloom(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val initPass = register(
        ShaderProgram(
            ShaderSource.Vert("/terrain/Blit.vert.glsl"),
            ShaderSource.Frag("/terrain/bloom/BloomInitPass.frag.glsl")
        )
    )
    private val downPass = register(
        HalfTexelPass(
            "/terrain/bloom/DualBlurDown.vert.glsl",
            "/terrain/bloom/DualBlurDown.frag.glsl"
        )
    )
    private val upPass = register(
        HalfTexelPass(
            "/terrain/bloom/DualBlurUp.vert.glsl",
            "/terrain/bloom/DualBlurUp.frag.glsl"
        )
    )
    private val finalPass = register(object : HalfTexelPass(
        "/terrain/bloom/DualBlurUp.vert.glsl",
        "/terrain/bloom/BloomFinalPass.frag.glsl"
    ) {
        fun setBloom(amount: Float, power: Float, subtract: Float) {
            uniform1f("bloomAmount", amount)
            uniform1f("bloomPower", power)
            uniform1f("subtractAmount", subtract)
        }
    })

    private val blurPass = 3
    private val swapFbos = SwapFbos()

    fun render(fboGroup: TerrainScene.FboGroup) {
        swapFbos.update()

        glDisable(GL_BLEND)
        glDisable(GL_DEPTH_TEST)

        setParams()
        renderInitPass(fboGroup)
        renderBlurPasses()
        renderFinalPass(fboGroup)
    }

    private fun renderInitPass(fboGroup: TerrainScene.FboGroup) {
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_NOTEQUAL)
        glDepthMask(false)

        val fbo = swapFbos.fbos[0]
        glClearNamedFramebufferf(fbo.id, GL_COLOR, 0, 0.0f, 0.0f, 0.0f, 0.0f)
        fbo.attachNonLayered(scene.waterGBuffer.depthStencilTex, GL_DEPTH_ATTACHMENT)
        fbo.bind()

        initPass.bind()
        initPass.applyBinding {
            sampler(fboGroup.inputBindings)
            buffer(scene.camera.noJitterUboBinding)
        }
        scene.basic.drawQuad()

        glDepthMask(true)
        glDepthFunc(GL_LEQUAL)
        glDisable(GL_DEPTH_TEST)
    }

    private fun renderBlurPasses() {
        for (i in 0 until blurPass) {
            renderDownScalePass(i)
        }
        for (i in blurPass - 1 downTo 1) {
            renderUpScalePass(i)
        }
    }

    private fun renderDownScalePass(i: Int) {
        glViewport(0, 0, scene.camera.screenWidth shr (i + 1), scene.camera.screenHeight shr (i + 1))

        downPass.bind()
        downPass.setHalfTexel(scene.camera.screenWidth shr i, scene.camera.screenHeight shr i)
        swapFbos.textures[i].bindTextureUnit(0)
        swapFbos.fbos[i + 1].bind()
        scene.basic.drawQuad()
    }

    private fun renderUpScalePass(i: Int) {
        glViewport(0, 0, scene.camera.screenWidth shr i, scene.camera.screenHeight shr i)

        upPass.bind()
        upPass.setHalfTexel(scene.camera.screenWidth shr (i + 1), scene.camera.screenHeight shr (i + 1))
        swapFbos.textures[i + 1].bindTextureUnit(0)
        swapFbos.fbos[i].bind()
        scene.basic.drawQuad()
    }

    private fun renderFinalPass(fboGroup: TerrainScene.FboGroup) {
        fboGroup.swap()

        glViewport(0, 0, scene.camera.screenWidth, scene.camera.screenHeight)
        glEnable(GL_BLEND)
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_ONE, GL_ZERO)

        finalPass.bind()
        finalPass.setHalfTexel(scene.camera.screenWidth shr 1, scene.camera.screenHeight shr 1)
        swapFbos.textures[0].bindTextureUnit(0)
        swapFbos.textures[1].bindTextureUnit(1)

        fboGroup.output.fbo.bind()
        scene.basic.drawQuad()

        fboGroup.swap()
    }

    private fun setParams() {
        val lumaMul = 0.5f
        val lumaAdd = 0.1f
        val lumaPow = 0.5f
        initPass.uniform3f(
            "uParams",
            lumaMul,
            lumaAdd,
            lumaPow
        )
        finalPass.setBloom(0.3f, 3.0f, 0.0f)
    }

    private open class HalfTexelPass(vert: String, frag: String) : ShaderProgram(
        ShaderSource.Vert(vert),
        ShaderSource.Frag(frag),
    ) {
        fun setHalfTexel(inputSizeX: Int, inputSizeY: Int) {
            val radius = 0.0f
            uniform2f(
                "halfTexel",
                (0.5f + radius) / inputSizeX,
                (0.5f + radius) / inputSizeY
            )
        }
    }

    inner class SwapFbos {
        var width = -1
        var height = -1
        val textures = Array(blurPass + 1) { register(TextureObject.Texture2D()) }
        val fbos = Array(blurPass + 1) { register(FramebufferObject()) }

        fun update() {
            val screenWidth = scene.camera.screenWidth
            val screenHeight = scene.camera.screenHeight
            if (width != screenWidth || height != screenHeight) {
                for (i in fbos.indices) {
                    textures[i].destroy()
                    fbos[i].destroyFbo()

                    textures[i].apply {
                        allocate(1, ImageFormat.R16_G16_B16_A16_F, screenWidth shr i, screenHeight shr i)
                            linearClampEdge()
                    }
                    fbos[i].apply {
                        attachNonLayered(textures[i], GL_COLOR_ATTACHMENT0) // Color
                        check()
                    }
                }

                width = screenWidth
                height = screenHeight
            }
        }
    }
}