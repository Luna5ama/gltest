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
import org.joml.Vector4f
import org.lwjgl.glfw.GLFW.*
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.*

class PointCloudScene(gameInstance: GameInstance) : AbstractScene(gameInstance) {
//        private val pcData = register(PointCloudData(this, File("dumpTest")))
//        private val pcData = register(PointCloudData(this, File("dumpForestPC")))
//    private val pcData = register(PointCloudData(this, File("dumpMountainV")))
//    private val pcData = register(PointCloudData(this, File("dumpMountainS")))
//    private val pcData = register(PointCloudData(this, File("dumpMountainS00")))
    private val pcData = register(PointCloudData(this, File("dumpMountainS64")))
//    private val pcData = register(PointCloudData(this, File("dumpForest2")))

    val vbuffer = register(PointCloudVisibilityBuffer(this))
    val fboGroup = register(FboGroup(this))

    private val lastUpdateSize = Vector2i(-1, -1)
    private val textures = register(Textures(this, false))

    private val taaPrevTex = register(TextureObject.Texture2D()).apply {
        label("prevTex")
    }

    private var frameIndex = 0
    private val overdrawDebug = false

//    private val pointDrawShader: ShaderProgram
    private val quadDrawShader = register(
        ShaderProgram(
            ShaderSource.Comp("pc/QuadDraw.comp") {
                define("BATCH_SIZE", pcData.BATCH_SIZE)
                if (overdrawDebug) {
                    define("OVERDRAW_DEBUG")
                }
            }
        )
    ).apply {
        label("QuadDraw")
    }
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
    private val overdrawCounterTex = register(TextureObject.Texture2D())
    val basic = register(Basic())

    init {
        val bcoords = listOf(
            Vector4f(0.25f, 0.25f, 0.25f, 0.25f),

            Vector4f(0.5f, 0.25f, 0.0f, 0.25f),
            Vector4f(0.0f, 0.25f, 0.5f, 0.25f),


            Vector4f(0.0f, 0.125f, 0.5f, 0.375f),
            Vector4f(0.375f, 0.0f, 0.125f, 0.5f),
            Vector4f(0.125f, 0.5f, 0.375f, 0.0f),
            Vector4f(0.5f, 0.375f, 0.0f, 0.125f),


            Vector4f(0.0f, 0.3125f, 0.125f, 0.5625f),
            Vector4f(0.125f, 0.5625f, 0.0f, 0.3125f),
            Vector4f(0.5625f, 0.0f, 0.1875f, 0.25f),
            Vector4f(0.1875f, 0.0f, 0.6875f, 0.125f),

            Vector4f(0.0f, 0.8125f, 0.0f, 0.1875f),
            Vector4f(0.0625f, 0.375f, 0.5625f, 0.0f),
            Vector4f(0.625f, 0.3125f, 0.0f, 0.0625f),
            Vector4f(0.0625f, 0.0f, 0.0625f, 0.875f),


            Vector4f(0.125f, 0.4375f, 0.0f, 0.4375f),
            Vector4f(0.3125f, 0.0f, 0.5625f, 0.125f),
            Vector4f(0.0f, 0.625f, 0.0625f, 0.3125f),
            Vector4f(0.1875f, 0.25f, 0.0f, 0.5625f),

            Vector4f(0.1875f, 0.1875f, 0.625f, 0.0f),
            Vector4f(0.625f, 0.1875f, 0.1875f, 0.0f),
            Vector4f(0.6875f, 0.0f, 0.1875f, 0.125f),
            Vector4f(0.0f, 0.1875f, 0.125f, 0.6875f),

            Vector4f(0.25f, 0.625f, 0.0f, 0.125f),
            Vector4f(0.0625f, 0.0f, 0.5f, 0.4375f),
            Vector4f(0.125f, 0.0f, 0.75f, 0.125f),
            Vector4f(0.0f, 0.75f, 0.125f, 0.125f),

            Vector4f(0.0f, 0.5f, 0.5f, 0.0f),
            Vector4f(0.1875f, 0.0625f, 0.0f, 0.75f),
            Vector4f(0.8125f, 0.125f, 0.0f, 0.0625f),
            Vector4f(0.0625f, 0.9375f, 0.0f, 0.0f)
        )
        val bcoordIdxOffsets = listOf(
            0,
            1,
            1 + 2,
            1 + 2 + 4,
            1 + 2 + 4 + 8
        )

        fun encodeBCoord(bcoord: Vector4f): Long {
            val tempX = ((bcoord.x * 2047.0f).toLong() and 2047L) shl 0
            val tempY = ((bcoord.y * 1023.0f).toLong() and 1023L) shl 11
            val tempZ = ((bcoord.z * 2047.0f).toLong() and 2047L) shl 21
            return tempX or tempY or tempZ
        }

        val encoded = bcoords.map { encodeBCoord(it) }

        fun StringBuilder.generate(sampleCountPow2: Int) {
            val bCoordIdxOffset = bcoordIdxOffsets[sampleCountPow2]
            val sampleCount = 1 shl sampleCountPow2
            appendLine("case $sampleCountPow2:\\")
            repeat(sampleCount) { i ->
                val bCoordIdx = bCoordIdxOffset + i
                val bCoord = bcoords[bCoordIdx]
                val encoded = encoded[bCoordIdx]
                appendLine("DRAW_POINT(vec4(%f, %f, %f, %f),0x%08Xu);\\".format(bCoord.x, bCoord.y, bCoord.z, bCoord.w, encoded))
            }
            appendLine("break;\\")
        }
        val xd = buildString {
            repeat(5) {
                generate(it)
            }
        }
        
//        pointDrawShader = register(
//            ShaderProgram(
//                ShaderSource.Comp("pc/PointDraw.comp") {
//                    define("BATCH_SIZE", pcData.BATCH_SIZE)
//                    define("UNROLLED_CODE", xd)
//                    if (overdrawDebug) {
//                        define("OVERDRAW_DEBUG")
//                    }
//                }
//            )
//        ).apply {
//            label("PointDraw")
//        }
        

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
//        dumpShaderAsm(pointDrawShader, "PointDraw.txt")
        dumpShaderAsm(quadDrawShader, "QuadDraw.txt")
    }

    override fun reloadShaders() {
        super.reloadShaders()
        dumpShaderAsm(quadDrawShader, "QuadDraw.txt")
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

    private val hwDrawTimer = GPUTimer(1000)
    private val swDrawTimer = GPUTimer(1000)
    private val shadingTimer = GPUTimer(1000)
    private val printTimer = TickTimer()
    private val updateTimer = TickTimer()

    override suspend fun renderFrame() {
//        exitProcess(0)
        glDisable(GL_BLEND)
        updateCamera()

        if (camera.screenSize.x <= 1 || camera.screenSize.y <= 1) return
        updateRes()
        clear()

        if (updateTimer.tickAndReset(10)) {
            pcData.updateBatchMeta()
        }
        glDebugGroup("HW Draw") { hwDraw() }
        glDebugGroup("HW2SW Blit") {
            vbuffer.blitHW2SW()
        }
        glDebugGroup("SW Draw") { swDraw() }
        glDebugGroup("SW2HW Blit") {
            vbuffer.blitSW2HW()
            vbuffer.fbo.blitTo(fboGroup.input.fbo, GL_DEPTH_BUFFER_BIT, GL_NEAREST)
            vbuffer.fbo.blitTo(fboGroup.output.fbo, GL_DEPTH_BUFFER_BIT, GL_NEAREST)
        }
        glDebugGroup("Shading") { shading() }
        glDebugGroup("Buffer blit") { bufferBlit() }


        if (printTimer.tickAndReset(3000)) {
            println("HW Draw: %.4f ms".format(hwDrawTimer.averageMS()))
            println("SW Draw: %.4f ms".format(swDrawTimer.averageMS()))
//            println("Shading: %.4f ms".format(shadingTimer.averageMS()))
//            val text = buildString {
//                appendLine("Draw: %.4f ms".format(timer.averageMS()))
//                appendLine("Quad count: %,d".format(pcData.quadBatchCount))
//                appendLine("Point count: %,d(%,d)".format(pcData.drawPointCount, pcData.drawPointTotalCount))
//            }
//            print(text)
//            File("G:\\temp\\xd.txt").writeText(text)
        }

        glBlitNamedFramebuffer(
            fboGroup.input.fbo.id, 0,
            0, 0, fboGroup.output.fbo.width, fboGroup.output.fbo.height,
            0, 0, fboGroup.output.fbo.width, fboGroup.output.fbo.height,
            GL_COLOR_BUFFER_BIT, GL_NEAREST
        )
    }


    private suspend fun hwDraw() {
        if (pcData.hwBatchCount == 0) return

        vbuffer.fbo.bind()
        vbuffer.fbo.setDrawBufferAll()

        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
//        glDepthBoundsdNV(0.0, 114514.0)
//        glDepthRangedNV(0.0, 114514.0)

        pcData.quadDrawHWPipeline.vao.bind()
        pcData.quadDrawHWPipeline.shader.bind()
        pcData.quadDrawHWPipeline.shader.applyBinding {
            sampler(textures.blockAlbedo)
            buffer(pcData.quadDrawHWPipeline.bufferBinding)
            buffer(pcData.bufferBindings)
            buffer(camera.uboBinding)
        }
        pcData.quadDrawHWPipeline.shader.uniform3f(
            "uval_originOffset",
            (originX - camera.pos.x - 0.5).toFloat(),
            (originY - camera.pos.y - 1.62).toFloat(),
            (originZ - camera.pos.z - 0.5).toFloat()
        )
        pcData.quadDrawHWPipeline.indirectCommands.bind(GL_DRAW_INDIRECT_BUFFER)

        hwDrawTimer.begin()
        glMultiDrawArraysIndirect(GL_TRIANGLES, 0L, pcData.hwBatchCount, 0)
        hwDrawTimer.end()
    }

    private suspend fun swDraw() {
        swDrawTimer.begin()

        if (pcData.quadBatchCount > 0) {
            bindSWDrawShader(quadDrawShader)
            glDispatchCompute(pcData.quadBatchCount, 1, 1)
        }

//        if (pcData.pointBatchCount > 0) {
//            bindSWDrawShader(pointDrawShader)
//            glDispatchCompute(pcData.pointBatchCount, 1, 1)
//        }

        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)

        swDrawTimer.end()
    }

    private fun bindSWDrawShader(program: ShaderProgram) {
        program.bind()
        program.uniform3f(
            "uval_originOffset",
            (originX - camera.pos.x - 0.5).toFloat(),
            (originY - camera.pos.y - 1.62).toFloat(),
            (originZ - camera.pos.z - 0.5).toFloat()
        )
        program.applyBinding {
            sampler(textures.blockAlbedo)
            buffer(camera.uboBinding)
            buffer(pcData.bufferBindings)
            buffer(vbuffer.bufferBinding)
            image(vbuffer.imageBindings)
            image("uimg_overdrawCounter", overdrawCounterTex)
        }
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
            buffer(pcData.bufferBindings)

            sampler(textures.blockAlbedo)
            sampler(textures.lightMap)
            sampler(fboGroup.inputBindings)

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
        bufferBlit.uniform1f("uval_mixFactor", 0.9f)

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
                camera.rotation.x = -260.0f
                camera.rotation.y = 22.0f
                camera.pos.x = 1288.0
                camera.pos.y = 172.0
                camera.pos.z = 337.0
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
        val originX get() = -PointCloudData.ORIGIN * 16.0f
        val originY get() = PointCloudData.MIN_CHUNK_Y * 16.0f
        val originZ get() = -PointCloudData.ORIGIN * 16.0f
    }
}