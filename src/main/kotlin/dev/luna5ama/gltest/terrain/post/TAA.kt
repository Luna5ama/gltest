package dev.luna5ama.gltest.terrain.post

import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.gltest.HaltonSequence
import dev.luna5ama.gltest.linearClampEdge
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.TerrainScene
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.enums.FilterMode
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.enums.WrapMode
import dev.luna5ama.glwrapper.objects.*
import org.joml.Vector2f
import org.joml.Vector3d

class TAA(private val scene: TerrainScene, samples: Int, private val radius: Float) :
    IGLObjContainer by IGLObjContainer.Impl() {
    private var frameIndex = 0
    private val jitters = genJitters(samples)

    val baseJitter = Vector2f()
    val currentJitter = Vector2f()

    private val shader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/TAA.vert.glsl"),
            ShaderSource.Frag("terrain/TAA.frag.glsl")
        )
    )

    val lastMixTexture = register(TextureObject.Texture2D())
     var lastMixTextureView = TextureObject.Texture2D()
    private val prevFrameTex = register(TextureObject.Texture2D())
    private val lastFrameFbo = register(FramebufferObject())
    private val drawFbo = register(FramebufferObject())
    private val prevFrameTexBinding = ShaderBindingSpecs.Sampler("usam_prevFrame", prevFrameTex)

    private val prevCameraPos = Vector3d()

    private fun genJitters(samples: Int): Array<Vector2f> {
        val randomX = HaltonSequence(2)
        val randomY = HaltonSequence(3)
        return Array(samples) {
            Vector2f(
                (randomX.nextDouble() * 2.0 - 1.0).toFloat(),
                (randomY.nextDouble() * 2.0 - 1.0).toFloat()
            )
        }
    }

    private fun setParams() {
        val clampPixelPrev = 0.5f
        val clampPixelMin = 0.0f
        val clampPixelMax = 1.0f

        val clampWorldPrev = 0.0f
        val clampWorldMin = 0.0f
        val clampWorldMax = 0.97f

        val baseMix = if (scene.gameInstance.toggles[0]) 0.99f else 0.9f
        val minMax = 0.5f
        val maxMix = 0.99f

        val worldVelDiff = 1.0f
        val worldVelDiffMin = 0.1f

        val worldVelCurr = 0.4f
        val worldVelCurrMin = 0.2f

        val pixelVel = 0.0f
        val pixelVelMinMix = 0.5f

//        shader.uniform3f(
//            "uClampPixel",
//            clampPixelPrev,
//            clampPixelMin,
//            clampPixelMax
//        )

//        shader.uniform3f(
//            "uClampWorld",
//            clampWorldPrev,
//            clampWorldMin,
//            clampWorldMax
//        )

        shader.uniform3f(
            "uMix",
            baseMix,
            minMax,
            maxMix
        )

//        shader.uniform2f(
//            "uWorldVelDiff",
//            worldVelDiff,
//            worldVelDiffMin
//        )

//        shader.uniform2f(
//            "uWorldVelCurr",
//            worldVelCurr,
//            worldVelCurrMin
//        )

//        shader.uniform2f(
//            "uPixelVelocity",
//            pixelVel,
//            pixelVelMinMix
//        )

        shader.uniform2f(
            "uJitter",
            currentJitter.x, currentJitter.y
        )
        shader.uniform3f(
            "uCameraDelta",
            (scene.camera.pos.x - prevCameraPos.x).toFloat(),
            (scene.camera.pos.y - prevCameraPos.y).toFloat(),
            (scene.camera.pos.z - prevCameraPos.z).toFloat()
        )
        prevCameraPos.set(scene.camera.pos)
    }

    private val hx = HaltonSequence(2)
    private val hy = HaltonSequence(3)
    private val rx = org.joml.Random()
    private val ry = org.joml.Random()

    fun render(fboGroup: FboGroup) {
        if (lastFrameFbo.width != scene.camera.screenWidth || lastFrameFbo.height != scene.camera.screenHeight) {
            lastFrameFbo.destroy()
            prevFrameTex.allocate(1, ImageFormat.R16G16B16A16_F, scene.camera.screenWidth, scene.camera.screenHeight)
            prevFrameTex.linearClampEdge()

            lastFrameFbo.attach(prevFrameTex, GL_COLOR_ATTACHMENT0)
            lastFrameFbo.setDrawBufferAll()
            lastFrameFbo.check()
            lastFrameFbo.clearColor(0, 0.0f, 0.0f, 0.0f, 0.0f)

            lastMixTexture.apply {
                lastMixTexture.destroy()
                setMipLevels(0, 2)
                setMipLod(0.0f, 2.0f)
                setFilter(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR)
                setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
                lastMixTexture.allocate(3, ImageFormat.R8_UN, scene.camera.screenWidth, scene.camera.screenHeight)
            }
            lastMixTextureView.destroy()
            lastMixTextureView = lastMixTexture.createView(ImageFormat.R8_UN, 2, 1, 0, 1)
        }

        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)

        drawFbo.clearAttachments()
        drawFbo.attach(fboGroup.output.colorTex, GL_COLOR_ATTACHMENT0)
        drawFbo.attach(lastMixTexture, GL_COLOR_ATTACHMENT1)
        drawFbo.setDrawBufferAll()
        drawFbo.check()
        drawFbo.bind()

        shader.bind()
        setParams()
        shader.applyBinding {
            sampler(scene.solidGBuffer.genericTexBindings)
            sampler(scene.solidVelocity.samplerBindings)
            sampler(fboGroup.inputBindings)
            sampler(prevFrameTexBinding)
            sampler("usam_lastMixWeight", lastMixTextureView, scene.samplerManager.get {
                filter(FilterMode.Linear, FilterMode.Linear)
                wrap(WrapMode.ClampToEdge, WrapMode.ClampToEdge)
            })

            buffer(scene.camera.uboBinding)
        }

        scene.basic.drawQuad()

        drawFbo.setReadBuffer(GL_COLOR_ATTACHMENT0)
        drawFbo.blitTo(lastFrameFbo, GL_COLOR_BUFFER_BIT, GL_LINEAR)
        lastMixTexture.generateMipmap()

        val x: Double
        val y: Double
        val i = 4

        when (i) {
            0 -> {
                x = 0.0
                y = 0.0
            }
            1 -> {
                x = (rx.nextFloat() * 2.0 - 1.0)
                y = (ry.nextFloat() * 2.0 - 1.0)
            }
            2 -> {
                x = jitters[frameIndex % jitters.size].x.toDouble()
                y = jitters[frameIndex % jitters.size].y.toDouble()
            }
            3 -> {
                x = (hx.nextDouble() * 2.0 - 1.0)
                y = (hy.nextDouble() * 2.0 - 1.0)
            }
            else -> {
                val range = 1.0
                val rangeHalf = range / 2.0
                val g = 1.32471795724474602596
                val a1 = 1.0 / g
                val a2 = 1.0 / (g * g)
                val n = frameIndex
                x = ((0.5 + a1 * n) % range - rangeHalf) / rangeHalf
                y = ((0.5 + a2 * n) % range - rangeHalf) / rangeHalf
            }
        }

        frameIndex++
//        val x = 0.0
//        val y = 0.0

        baseJitter.set(x.toFloat(), y.toFloat())
        currentJitter.set(x.toFloat(), y.toFloat()).mul(radius)

        fboGroup.swap()
    }
}