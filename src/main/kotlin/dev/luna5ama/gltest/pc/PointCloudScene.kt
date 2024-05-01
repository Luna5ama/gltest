package dev.luna5ama.gltest.pc

import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.*
import dev.luna5ama.gltest.terrain.Textures
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.kmogus.MemoryStack
import org.joml.Vector2i
import java.io.File
import java.util.*

class PointCloudScene(gameInstance: GameInstance) : AbstractScene(gameInstance) {
//    private val pcData = register(PointCloudData(this, File("dumpTest")))
        private val pcData = register(PointCloudData(this, File("dumpForestPC")))
//    private val pcData = register(PointCloudData(this, File("dumpMountainV")))
//    private val pcData = register(PointCloudData(this, File("dumpMountainS")))
//    private val pcData = register(PointCloudData(this, File("dumpMountainS00")))
//    private val pcData = register(PointCloudData(this, File("dumpMountainS64")))

    val vbuffer = register(PointCloudVisibilityBuffer(this))
    val fboGroup = register(FboGroup(this))

    private val lastUpdateSize = Vector2i(-1, -1)
    private val textures = register(Textures(this))

    private val taaPrevTex = register(TextureObject.Texture2D()).apply {
        label("prevTex")
    }

    private val depthRenderTex = register(TextureObject.Texture2D()).apply {
        label("depthTex32UI")
    }
    private val depthCopyTex = register(TextureObject.Texture2D()).apply {
        label("depthCopyTex")
    }

    private var frameIndex = 0

    private val drawShader = register(
        ShaderProgram(
            ShaderSource.Comp("/pc/PointDraw.comp") {
                define("BATCH_SIZE", PointCloudData.BATCH_SIZE)
            }
        )
    ).apply {
        label("drawShader")
    }
    private val holeFill = register(
        ShaderProgram(
            ShaderSource.Comp("/pc/HoleFill.comp")
        )
    ).apply {
        label("bufferBlit")
    }
    private val depthBlit = register(
        ShaderProgram(
            ShaderSource.Vert("/pc/Blit.vert"),
            ShaderSource.Frag("/pc/DepthBlit.frag")
        )
    ).apply {
        label("depthBlit")
    }
    private val shadingShader = register(
        ShaderProgram(
            ShaderSource.Comp("/pc/Shading.comp")
        )
    ).apply {
        label("shadingShader")
    }
    private val bufferBlit = register(
        ShaderProgram(
            ShaderSource.Comp("/pc/BufferBlit.comp")
        )
    ).apply {
        label("bufferBlit")
    }
    val basic = register(Basic())

    init {
        runCatching {
            val prop = Properties()
            prop.load(File("camera.properties").inputStream())
            camera.pos.x = prop["posX"].toString().toDouble()
            camera.pos.y = prop["posY"].toString().toDouble()
            camera.pos.z = prop["posZ"].toString().toDouble()
            camera.rotation.x = prop["yaw"].toString().toFloat()
            camera.rotation.y = prop["pitch"].toString().toFloat()
            camera.fov = prop["fov"].toString().toFloat()
            camera.viewDistance = prop["viewDistance"].toString().toFloat()
        }.onFailure {
            it.printStackTrace()
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            saveCameraPos()
        })
    }

    override fun destroy() {
        saveCameraPos()
    }

    private fun saveCameraPos() {
        val props = Properties()
        props["posX"] = camera.pos.x.toString()
        props["posY"] = camera.pos.y.toString()
        props["posZ"] = camera.pos.z.toString()
        props["yaw"] = (camera.rotation.x % 360.0f).toString()
        props["pitch"] = (camera.rotation.y % 360.0f).toString()
        props["fov"] = camera.fov.toString()
        props["viewDistance"] = camera.viewDistance.toString()
        runCatching {
            props.store(
                File("camera.properties").outputStream(), null
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    private val timer = GPUTimer(2500)
    private val timer2 = GPUTimer(2500)
    private val printTimer = TickTimer()
    private val printTimer2 = TickTimer()

    override suspend fun renderFrame() {
        updateCamera()

        if (camera.screenSize.x <= 1 || camera.screenSize.y <= 1) return
        updateRes()
        clear()

        glDebugGroup("Draw point cloud") { drawPointCloud() }
        glDebugGroup("Hole fill") { holeFill() }
        glDebugGroup("Depth blit") { depthBlit() }
        glDebugGroup("Shading") { shading() }
        glDebugGroup("Buffer blit") { bufferBlit() }

        glBlitNamedFramebuffer(
            fboGroup.input.fbo.id, 0,
            0, 0, fboGroup.output.fbo.width, fboGroup.output.fbo.height,
            0, 0, fboGroup.output.fbo.width, fboGroup.output.fbo.height,
            GL_COLOR_BUFFER_BIT, GL_NEAREST
        )
    }

    private fun clear() {
        MemoryStack {
            val ptr = malloc(16).ptr
            ptr.setFloatInc(Float.POSITIVE_INFINITY)
                .setFloatInc(Float.POSITIVE_INFINITY)
                .setFloatInc(Float.POSITIVE_INFINITY)
                .setFloatInc(Float.POSITIVE_INFINITY)
            depthRenderTex.clearImage(GL_RGBA_INTEGER, GL_UNSIGNED_INT, ptr)
        }
    }

    private val updateTimer = TickTimer()

    private suspend fun drawPointCloud() {
        timer.begin()
        if (updateTimer.tickAndReset(25)) {
            pcData.updateBatchMeta()
        }
        drawShader.bind()
        drawShader.uniform3f(
            "uval_originOffset",
            (originX - camera.pos.x - 0.5).toFloat(),
            (originY - camera.pos.y).toFloat(),
            (originZ - camera.pos.z - 0.5).toFloat()
        )
        drawShader.applyBinding {
            buffer(camera.uboBinding)
            buffer(pcData.bufferBindings)

            image(vbuffer.imageBindingSeparated)
            image("uimg_depthRenderTex", depthRenderTex)
        }
        drawShader.uniform2f("uval_viewportSize", camera.screenSize.x.toFloat(), camera.screenSize.y.toFloat())
        drawShader.uniform2ui("uval_viewportSizeUI", camera.screenSize.x, camera.screenSize.y)

        glDispatchCompute(pcData.batchCount, 1, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
        timer.end()
        if (printTimer.tickAndReset(2000)) {
            println("Draw point cloud: %.4f ms".format(timer.averageMS()))
        }
    }

    private fun holeFill() {
        holeFill.bind()
        holeFill.applyBinding {
            image("uimg_depthInput", depthRenderTex)
            image("uimg_depthOutput", depthCopyTex)
            image(vbuffer.imageBindingCombined)
            image(vbuffer.imageBindingSeparated)
        }
        glDispatchCompute((camera.screenSize.x + 15) / 16, (camera.screenSize.y + 15) / 16, 1)
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
    }

    private fun depthBlit() {
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LESS)

        fboGroup.output.fbo.bind()
        fboGroup.output.fbo.setDrawBuffer()
        depthBlit.bind()
        depthBlit.applyBinding {
            buffer(camera.uboBinding)
            sampler("usam_input", depthCopyTex)
        }
        basic.drawQuad()
        glDisable(GL_DEPTH_TEST)

        fboGroup.output.fbo.setDrawBufferAll()
        fboGroup.output.depthStencilTex.copyTo(fboGroup.input.depthStencilTex)
    }

    private fun shading() {
        timer2.begin()
        shadingShader.bind()
        shadingShader.applyBinding {
            buffer(camera.uboBinding)
            buffer(pcData.bufferBindings)

            sampler(textures.blockAlbedo)
            sampler(textures.lightMap)
            sampler(fboGroup.inputBindings)

            image(vbuffer.imageBindingCombined)
            image("uimg_output", fboGroup.output.colorTex)
        }
        glDispatchCompute((camera.screenSize.x + 15) / 16, (camera.screenSize.y + 15) / 16, 1)
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

        fboGroup.swap()
        timer2.end()
        if (printTimer2.tickAndReset(2000)) {
            println("Shading: %.4f ms".format(timer2.averageMS()))
        }
    }

    private fun bufferBlit() {
        bufferBlit.bind()
        bufferBlit.applyBinding {
            image("uimg_prevTexture", taaPrevTex)
            image("uimg_input", fboGroup.input.colorTex)
            image("uimg_output", fboGroup.output.colorTex)
        }
        bufferBlit.uniform1f("uval_mixFactor", 0.9f)

        glDispatchCompute((camera.screenSize.x + 15) / 16, (camera.screenSize.y + 15) / 16, 1)
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

        fboGroup.swap()
    }

    private fun updateRes() {
        if (camera.screenSize != lastUpdateSize) {
            lastUpdateSize.set(camera.screenSize)

            taaPrevTex.destroy()
            depthRenderTex.destroy()
            depthCopyTex.destroy()

            taaPrevTex.allocate(1, ImageFormat.R16_G16_B16_A16_F, camera.screenSize.x, camera.screenSize.y)
            depthRenderTex.allocate(1, ImageFormat.R32_UI, camera.screenSize.x, camera.screenSize.y)
            depthCopyTex.allocate(1, ImageFormat.R32_F, camera.screenSize.x, camera.screenSize.y)
        }

        fboGroup.reset()
        vbuffer.update()
    }

    private fun updateCamera() {
        camera.updateScreenSize()

        val range = 1.0
        val rangeHalf = range / 2.0
        val g = 1.32471795724474602596
        val a1 = 1.0 / g
        val a2 = 1.0 / (g * g)
        val n = frameIndex
        val x = ((0.5 + a1 * n) % range - rangeHalf) / rangeHalf
        val y = ((0.5 + a2 * n) % range - rangeHalf) / rangeHalf

        camera.update(x.toFloat() * 0.5f, y.toFloat() * 0.5f)

        frameIndex++
    }

    companion object {
        val originX get() = -PointCloudData.ORIGIN * 16.0f
        val originY get() = PointCloudData.MIN_CHUNK_Y * 16.0f
        val originZ get() = -PointCloudData.ORIGIN * 16.0f
    }
}