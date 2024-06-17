package dev.luna5ama.gltest.pc

import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.*
import dev.luna5ama.gltest.terrain.Textures
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr
import org.joml.Vector2i
import org.lwjgl.glfw.GLFW.*
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.*

class PointCloudScene(gameInstance: GameInstance) : AbstractScene(gameInstance) {
//        private val terrainMeshData = register(TerrainMeshData(this, File("dumpTest")))
//        private val terrainMeshData = register(TerrainMeshData(this, File("dumpForestPC")))
//    private val terrainMeshData = register(TerrainMeshData(this, File("dumpMountainV")))
//    private val terrainMeshData = register(TerrainMeshData(this, File("dumpMountainS")))
//    private val terrainMeshData = register(TerrainMeshData(this, File("dumpMountainS00")))
    val terrainMeshData = register(TerrainMeshData(this, File("dumpMountainS64")))
//    private val terrainMeshData = register(TerrainMeshData(this, File("dumpForest2")))

    val vbuffer = register(VisibilityBuffer(this))
    val fboGroup = register(FboGroup(this))

    private val lastUpdateSize = Vector2i(-1, -1)
    val textures = register(Textures(this, false))

    private val taaPrevTex = register(TextureObject.Texture2D()).apply {
        label("prevTex")
    }

    private var frameIndex = 0
    val overdrawDebug = false

    private val shadingShader = register(
        ShaderProgram(
            ShaderSource.Comp("pc/Shading.comp")
        )
    ).apply {
        label("shadingShader")
    }
    private val bufferBlit = register(
        ShaderProgram(
            ShaderSource.Comp("pc/BufferBlit.comp")
        )
    ).apply {
        label("bufferBlit")
    }
    val overdrawCounterTex = register(TextureObject.Texture2D())
    val basic = register(Basic())

    val terrainGeometryRenderer = register(TerrainGeometryRenderer(this))

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

    private fun dumpShaderAsm(program: ShaderProgram, name: String) {
        val binLen = glGetProgrami(program.id, GL_PROGRAM_BINARY_LENGTH)
        val file = File("shaderasm/$name")
        file.parentFile.mkdirs()
        file.delete()
        FileChannel.open(
            file.toPath(),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE
        ).use { channel ->
            val mapped = Arr.wrap(channel.map(FileChannel.MapMode.READ_WRITE, 0, binLen.toLong()))
            MemoryStack {
                val binaryFormat = calloc(4)
                glGetProgramBinary(program.id, binLen, Ptr.NULL, binaryFormat.ptr, mapped.ptr)
            }
        }
    }
    private val shadingTimer = GPUTimer(2000)

    override suspend fun renderFrame() {
//        exitProcess(0)
        glDisable(GL_BLEND)
        updateCamera()

        if (camera.screenSize.x <= 1 || camera.screenSize.y <= 1) return
        updateRes()
        clear()

        glDebugGroup("Geometry") { terrainGeometryRenderer.draw() }
        glDebugGroup("Shading") { shading() }
        glDebugGroup("Buffer blit") { bufferBlit() }

        glBlitNamedFramebuffer(
            fboGroup.input.fbo.id, 0,
            0, 0, fboGroup.output.fbo.width, fboGroup.output.fbo.height,
            0, 0, fboGroup.output.fbo.width, fboGroup.output.fbo.height,
            GL_COLOR_BUFFER_BIT, GL_NEAREST
        )
    }

    fun bindSWDrawShader(program: ShaderProgram) {
        program.bind()
        program.uniform3f(
            "uval_originOffset",
            (originX - camera.pos.x - 0.5).toFloat(),
            (originY - camera.pos.y - 1.62).toFloat(),
            (originZ - camera.pos.z - 0.5).toFloat()
        )
        program.uniform4f(
            "uval_viewportSize",
            camera.screenSize.x.toFloat(), camera.screenSize.y.toFloat(),
            1.0f / camera.screenSize.x.toFloat(), 1.0f / camera.screenSize.y.toFloat()
        )
        program.uniform2i("uval_viewportSizeI", camera.screenSize.x, camera.screenSize.y)
    }

    private fun shading() {
        shadingTimer.begin()
        shadingShader.bind()
        shadingShader.applyBinding {
            buffer(camera.uboBinding)
            buffer(terrainMeshData.bufferBindings)

            sampler(textures.blockAlbedo)
            sampler(textures.lightMap)
            sampler(fboGroup.inputBindings)
            sampler(vbuffer.samplerBindings)

            image(vbuffer.imageBindings)
            image("uimg_output", fboGroup.output.colorTex)
        }
        shadingShader.uniform3f(
            "uval_originOffset",
            (originX - camera.pos.x - 0.5).toFloat(),
            (originY - camera.pos.y - 1.62).toFloat(),
            (originZ - camera.pos.z - 0.5).toFloat()
        )
        shadingShader.uniform4f(
            "uval_viewportSize",
            camera.screenSize.x.toFloat(), camera.screenSize.y.toFloat(),
            1.0f / camera.screenSize.x.toFloat(), 1.0f / camera.screenSize.y.toFloat()
        )
        glDispatchCompute((camera.screenSize.x + 15) / 16, (camera.screenSize.y + 15) / 16, 1)
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

        fboGroup.swap()
        shadingTimer.end()
    }

    private fun bufferBlit() {
        bufferBlit.bind()
        bufferBlit.applyBinding {
            image("uimg_prevTexture", taaPrevTex)
            image("uimg_input", fboGroup.input.colorTex)
            image("uimg_output", fboGroup.output.colorTex)
        }
        bufferBlit.uniform1f("uval_mixFactor", 0.8f)

        glDispatchCompute((camera.screenSize.x + 15) / 16, (camera.screenSize.y + 15) / 16, 1)
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

        fboGroup.swap()
    }

    private fun updateRes() {
        if (camera.screenSize != lastUpdateSize) {
            lastUpdateSize.set(camera.screenSize)

            taaPrevTex.destroy()

            taaPrevTex.allocate(1, ImageFormat.R16_G16_B16_A16_F, camera.screenSize.x, camera.screenSize.y)
            if (overdrawDebug) overdrawCounterTex.allocate(
                1,
                ImageFormat.R32_UI,
                camera.screenSize.x,
                camera.screenSize.y
            )
        }

        fboGroup.reset()
        vbuffer.update()
    }

    private fun clear() {
        if (overdrawDebug) overdrawCounterTex.clearImage(GL_RGBA_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
    }

    private var lastKey = -1

    private fun updateCamera() {
        when {
            keyboard.pressing(GLFW_KEY_F7) -> {
                if (lastKey != GLFW_KEY_F7) {
                    println("F7 pressed")
                }
                lastKey = GLFW_KEY_F7

                camera.fov = 90.0f
                camera.viewDistance = 2048.0f
                camera.rotation.x = -89.0f
                camera.rotation.y = 75.0f
                camera.pos.x = -514.0
                camera.pos.y = 1024.0
                camera.pos.z = -95.0
            }

            keyboard.pressing(GLFW_KEY_F8) -> {
                if (lastKey != GLFW_KEY_F8) {
                    println("F8 pressed")
                }
                lastKey = GLFW_KEY_F8

                camera.fov = 90.0f
                camera.viewDistance = 2048.0f
                camera.rotation.x = -50.0f
                camera.rotation.y = 77.5f
                camera.pos.x = -256.0
                camera.pos.y = 485.0
                camera.pos.z = -275.0
            }

            keyboard.pressing(GLFW_KEY_F9) -> {
                if (lastKey != GLFW_KEY_F9) {
                    println("F9 pressed")
                }
                lastKey = GLFW_KEY_F9

                camera.fov = 90.0f
                camera.viewDistance = 2048.0f
                camera.rotation.x = -315.0f
                camera.rotation.y = 31.3f
                camera.pos.x = 508.0
                camera.pos.y = 270.0
                camera.pos.z = -435.0
            }

            keyboard.pressing(GLFW_KEY_F10) -> {
                if (lastKey != GLFW_KEY_F10) {
                    println("F10 pressed")
                }
                lastKey = GLFW_KEY_F10

                camera.fov = 90.0f
                camera.viewDistance = 2048.0f
                camera.rotation.x = -236.0f
                camera.rotation.y = 40.0f
                camera.pos.x = 746.0
                camera.pos.y = 112.0
                camera.pos.z = 768.0
            }
        }

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
        val originX get() = -TerrainMeshData.ORIGIN * 16.0f
        val originY get() = TerrainMeshData.MIN_CHUNK_Y * 16.0f
        val originZ get() = -TerrainMeshData.ORIGIN * 16.0f
    }
}