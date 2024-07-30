package dev.luna5ama.gltest.pc

import dev.fastmc.common.ParallelUtils
import dev.fastmc.common.TickTimer
import dev.fastmc.common.floorToInt
import dev.fastmc.common.sort.IntIntrosort
import dev.luna5ama.gltest.GPUTimer
import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.pc.TerrainMeshData.ChunkLayerInfo
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.commons.lang3.mutable.MutableBoolean
import org.joml.*
import kotlin.math.max
import kotlin.math.min

class TerrainGeometryRenderer(val scene: PointCloudScene) : IGLObjContainer by IGLObjContainer.Impl() {
    val visibilityFlagType = when {
        scene.gameInstance.glCapabilities.GL_NV_gpu_shader5 -> {
            VisibilityFlagType.INT8
        }

        scene.gameInstance.glCapabilities.GL_AMD_gpu_shader_int16 -> {
            VisibilityFlagType.INT16
        }

        else -> {
            VisibilityFlagType.INT32
        }
    }
//    val visibilityFlagType = VisibilityFlagType.INT32

    val threadBatchSize = if (GLWrapper.instance.vendor == GpuVendor.NVIDIA) 96 else 128

    init {
        println("Visibility flag type: ${visibilityFlagType.name}")
        println("Using thread batch size: $threadBatchSize")
    }

    val hwVsPipeline = register(HWVertexShaderPipeline(scene, this))
    val hwMsPipeline = register(HWMeshShaderPipeline(scene, this))
    val swQuadPipeline = register(SWQuadPipeline(scene, this))
    //    val swPointPipeline = register(SWPointPipeline(scene, this))

    val cullPipeline = register(OcclusionCullingPipeline.VertexShader(scene, this))
//    val cullPipeline = register(OcclusionCullingPipeline.MeshShader(scene, this))
//    val cullPipeline = register(OcclusionCullingPipeline.SWRaster(scene, this))

    private val visibleBuffer = register(BufferObject.Immutable()).apply {
        label("Terrain.VisibleBuffer")
    }

    private val chunkPosData = register(BufferObject.Immutable()).apply {
        label("Terrain.ChunkPosData")
    }

    val bufferBindings = listOf(
        ShaderBindingSpecs.Buffer("VisibleBuffer", visibleBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("ChunkPosData", chunkPosData, BufferTarget.ShaderStorage)
    )

    val visibleBufferStateLen1: Long

    init {
        val terrainMeshData = scene.terrainMeshData
        MemoryStack {
            val arr = malloc((terrainMeshData.chunkPos.size + 2) * 16L)
            val ptr = arr.ptr
            for (i in terrainMeshData.chunkPos.indices) {
                val chunkPos = terrainMeshData.chunkPos[i]
                ptr.setInt((i + 2) * 16L, chunkPos.x)
                ptr.setInt((i + 2) * 16L + 4L, chunkPos.y)
                ptr.setInt((i + 2) * 16L + 8L, chunkPos.z)
            }
            chunkPosData.allocate(arr.len, arr.ptr, GL_DYNAMIC_STORAGE_BIT)
        }

        visibleBufferStateLen1 = (terrainMeshData.chunkPos.size * visibilityFlagType.byteSize + 63L) / 64 * 64L
        val len = 16L + visibleBufferStateLen1 * 2
        visibleBuffer.allocate(len, Ptr.NULL, 0)
        MemoryStack {
            val ptr = malloc(4L).ptr
            ptr.setInt((visibleBufferStateLen1 / visibilityFlagType.byteSize).toInt())
            visibleBuffer.clear(GL_R32UI, 0L, 4L, GL_RED_INTEGER, GL_UNSIGNED_INT, ptr)
        }
    }

    private val hwVsDrawTimer = GPUTimer(500)
    private val hwMsDrawTimer = GPUTimer(500)
    private val swDrawTimer = GPUTimer(500)
    private val printTimer = TickTimer()
    val updateTimer = TickTimer()

    private val sw2hwShader = register(
        ShaderProgram(
            ShaderSource.Vert("pc/Blit.vert"),
            ShaderSource.Frag("pc/geometry/SW2HW.frag")
        )
    ).apply {
        label("VBuffer.SW2HW")
    }
    private val sw2hwDepthShader = register(
        ShaderProgram(
            ShaderSource.Vert("pc/Blit.vert"),
            ShaderSource.Frag("pc/geometry/SW2HWDepth.frag")
        )
    ).apply {
        label("VBuffer.SW2HW")
    }
    private val hw2swShader = register(
        ShaderProgram(
            ShaderSource.Comp("pc/geometry/HW2SW.comp")
        )
    ).apply {
        label("VBuffer.HW2SW")
    }

    private val visibleBufferUpdateShader = register(
        ShaderProgram(ShaderSource.Comp("pc/geometry/VisibleBufferUpdate.comp") {
            define("COPY_LEN_SHIFT", (2 - visibilityFlagType.byteSize.countTrailingZeroBits()) + 2)
        })
    ).apply {
        label("VisibleBufferUpdate")
    }

    private var chunkIndices = IntArray(scene.terrainMeshData.chunkLayerInfo.size) { it }

    suspend fun draw() {
        MemoryStack {
            val arr = malloc(16 + 16L)
            val ptr = arr.ptr
            val chunkX = scene.camera.pos.x.floorToInt() shr 4
            val chunkY = scene.camera.pos.y.floorToInt() shr 4
            val chunkZ = scene.camera.pos.z.floorToInt() shr 4
            ptr.setFloatInc((scene.camera.pos.x - (chunkX shl 4)).toFloat())
                .setFloatInc((scene.camera.pos.y - (chunkY shl 4)).toFloat())
                .setFloatInc((scene.camera.pos.z - (chunkZ shl 4)).toFloat())
                .setIntInc(0)
                .setIntInc(chunkX)
                .setIntInc(chunkY)
                .setIntInc(chunkZ)
                .setIntInc(0)
            chunkPosData.invalidate(0, arr.len)
            chunkPosData.upload(0, arr.len, arr.ptr)
        }

        if (!scene.gameInstance.toggle3) {
            if (updateTimer.tickAndReset(5)) {
                updateBatchMeta()
            }

//        if (glfwGetTime() < 1.0) {
//            updateBatchMeta()
//        }
        }

        hwMsPipeline.preDraw()
        hwVsPipeline.preDraw()
        swQuadPipeline.preDraw()

        if (!scene.gameInstance.toggle3) {
//        val chunkCount = scene.terrainMeshData.chunkOffsets.size.toLong()
//        val visibleFlagLen = chunkCount * visibilityFlagType.byteSize
//        visibleBuffer.copyTo(visibleBuffer, 4L, 4L + visibleFlagLen, visibleFlagLen)
//        visibleBuffer.clear(GL_R8UI, 4L, visibleFlagLen, GL_RED_INTEGER, GL_UNSIGNED_BYTE, Ptr.NULL)

            visibleBufferUpdateShader.bind()
            visibleBufferUpdateShader.applyBinding {
                buffer(bufferBindings)
            }
            val bytesPerGroup = 16 * 128
            glDispatchCompute(((visibleBufferStateLen1 + bytesPerGroup - 1) / bytesPerGroup).toInt(), 1, 1)
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
        }

        glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)
        glDebugGroup("HW Draw") {
            hwDraw()
        }
        glDebugGroup("HW2SW Blit") {
            blitHW2SW()
        }
        glDebugGroup("SW Draw") {
            swDraw()
        }

        glDebugGroup("SW2HW Blit") {
            blitSW2HW()
//            scene.vbuffer.fbo.blitTo(scene.fboGroup.input.fbo, GL_DEPTH_BUFFER_BIT, GL_NEAREST)
//            scene.vbuffer.fbo.blitTo(scene.fboGroup.output.fbo, GL_DEPTH_BUFFER_BIT, GL_NEAREST)
        }
        glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE)

        if (printTimer.tickAndReset(3000)) {
//            println("Min distance ${sqrt(sortKeys.min().toDouble())}")
//            println("Min sample count: ${sampleCountArray.min()}")
//            println("Average sample count: ${sampleCountArray.average()}")
//            println("Max sample count: ${sampleCountArray.max()}")
//            println()
            if (hwVsPipeline.batchCount > 0) {
                println("HW VS: ${chunkCountString(hwVsPipeline)} batches")
            } else {
                println("HW MS: ${chunkCountString(hwMsPipeline)} batches")
            }
            println("SW Quad: ${chunkCountString(swQuadPipeline)}")
//            println("SW Point: ${chunkCountString(swPointPipeline)}")
            println()
            println("HW Draw: %.3f ms".format(hwMsDrawTimer.averageMS() + hwVsDrawTimer.averageMS()))
            println("SW Draw: %.3f ms".format(swDrawTimer.averageMS()))
            println("Total Draw: %.3f ms".format(swDrawTimer.averageMS() + hwVsDrawTimer.averageMS() + hwMsDrawTimer.averageMS()))
            println()
        }
    }

    private val frustumCullingFlags = Array(scene.terrainMeshData.chunkPos.size) {
        MutableBoolean(true)
    }
    private val sampleCountArray = FloatArray(scene.terrainMeshData.chunkPos.size)

    private suspend fun updateBatchMeta() {
        coroutineScope {
            launch(Dispatchers.Default) {
                sortChunks()
            }
            launch(Dispatchers.Default) {
                calcSampleCount()
            }
            launch(Dispatchers.Default) {
                doFrustumCulling()
            }
        }

        val hwVs = mutableListOf<ChunkLayerInfo>()
        val hwMs = mutableListOf<ChunkLayerInfo>()
        val swQuad = mutableListOf<ChunkLayerInfo>()
//        val swPoint = mutableListOf<ChunkLayerInfo>()

        for (i in chunkIndices) {
            val chunkInfo = scene.terrainMeshData.chunkLayerInfo[i]
            if (!frustumCullingFlags[chunkInfo.chunkIndex].value) continue
            if (sampleCountArray[chunkInfo.chunkIndex] > 4.0f) {
                if (!scene.gameInstance.toggle1) {
                    hwVs.add(chunkInfo)
                }
            } else {
                if (!scene.gameInstance.toggle2) {
                    swQuad.add(chunkInfo)
                }
            }
//            when {
//                sampleCountArray[chunkInfo.chunkIndex] > 32.0f -> {
//                }
//                sampleCountArray[chunkInfo.chunkIndex] > 4.0f -> {
//                    hwVs.add(chunkInfo)
//                }
//                else -> {
//                }
//            }
        }

        coroutineScope {
            launch(Dispatchers.Default) { hwVsPipeline.update(hwVs) }
            launch(Dispatchers.Default) { hwMsPipeline.update(hwMs) }
            launch(Dispatchers.Default) { swQuadPipeline.update(swQuad) }
        }
    }

    private fun chunkCountString(pipeline: TerrainDrawPipeline): String {
        return "%,d quads, %,d batches, %,d/%,d chunks".format(
            pipeline.quadCount,
            pipeline.chunkCount,
            pipeline.batchCount,
            scene.terrainMeshData.chunkLayerInfo.size
        )
    }

    private fun CoroutineScope.calcSampleCount() {
        ParallelUtils.splitListIndex(
            scene.terrainMeshData.chunkPos.size,
            blockForEach = { start, end ->
                launch {
                    val areaCalc = QuadAreaCalc()
                    for (i in start until end) {
                        sampleCountArray[i] = areaCalc.calc(scene.terrainMeshData.chunkPos[i])
                    }
                }
            }
        )
    }

    private val sortKeys = IntArray(scene.terrainMeshData.chunkLayerInfo.size)

    private suspend fun sortChunks() {
        coroutineScope {
            val chunkLayerInfoList = scene.terrainMeshData.chunkLayerInfo
            ParallelUtils.splitListIndex(
                chunkLayerInfoList.size,
                blockForEach = { start, end ->
                    launch {
                        val originOffsetX = scene.camera.pos.x.floorToInt()
                        val originOffsetY = scene.camera.pos.y.floorToInt()
                        val originOffsetZ = scene.camera.pos.z.floorToInt()
                        for (i in start until end) {
                            val chunkInfo = chunkLayerInfoList[i]
                            val dx = chunkInfo.pos.x * 16 + 8 - originOffsetX
                            val dy = chunkInfo.pos.y * 16 + 8 - originOffsetY
                            val dz = chunkInfo.pos.z * 16 + 8 - originOffsetZ
                            sortKeys[i] = (dx * dx + dy * dy + dz * dz)
                        }
                    }
                }
            )
        }

        IntIntrosort.sort(chunkIndices, sortKeys)
    }

    private fun CoroutineScope.doFrustumCulling() {
        val frustumCull = FrustumIntersection(scene.camera.combined, false)
        ParallelUtils.splitListIndex(
            scene.terrainMeshData.chunkPos.size,
            blockForEach = { start, end ->
                launch {
                    for (i in start until end) {
                        val chunkPos = scene.terrainMeshData.chunkPos[i]
                        val worldPosX = chunkPos.x shl 4
                        val worldPosY = chunkPos.y shl 4
                        val worldPosZ = chunkPos.z shl 4
                        frustumCullingFlags[i].setValue(
                            frustumCull.testAab(
                                ((worldPosX - 1) - scene.camera.pos.x).toFloat(),
                                ((worldPosY - 1) - scene.camera.pos.y).toFloat(),
                                ((worldPosZ - 1) - scene.camera.pos.z).toFloat(),
                                ((worldPosX + 17) - scene.camera.pos.x).toFloat(),
                                ((worldPosY + 17) - scene.camera.pos.y).toFloat(),
                                ((worldPosZ + 17) - scene.camera.pos.z).toFloat()
                            )
                        )
                    }
                }
            }
        )
    }

    private inner class QuadAreaCalc {
        private val rotateMatrix = Matrix4f()
        private val vec3f1 = Vector3f()
        private val vec3f2 = Vector3f()

        private val point1 = Vector4f()
        private val point2 = Vector4f()
        private val point3 = Vector4f()
        private val point4 = Vector4f()

        private fun setPoint() {
            point1.set(-0.5f, -0.5f, 0.0f, 1.0f)
            point2.set(0.5f, -0.5f, 0.0f, 1.0f)
            point3.set(0.5f, 0.5f, 0.0f, 1.0f)
            point4.set(-0.5f, 0.5f, 0.0f, 1.0f)
        }

        fun calc(chunkPos: Vector3i): Float {
            val worldPosX = chunkPos.x shl 4
            val worldPosY = chunkPos.y shl 4
            val worldPosZ = chunkPos.z shl 4
            vec3f1.set(
                0.0f.coerceIn(
                    (worldPosX - 1 - scene.camera.pos.x).toFloat(),
                    (worldPosX + 17 - scene.camera.pos.x).toFloat()
                ),
                0.0f.coerceIn(
                    (worldPosY - 1 - scene.camera.pos.y).toFloat(),
                    (worldPosY + 17 - scene.camera.pos.y).toFloat()
                ),
                0.0f.coerceIn(
                    (worldPosZ - 1 - scene.camera.pos.z).toFloat(),
                    (worldPosZ + 17 - scene.camera.pos.z).toFloat()
                )
            )
            if (vec3f1.lengthSquared() < 32 * 32) {
                return 114514.0f
            }

            vec3f1.mul(-1.0f)
            vec3f2.set(1.0f, 1.0f, 1.0f)
            rotateMatrix.identity()
            rotateMatrix.rotationTowards(vec3f1, vec3f2)
            vec3f1.mul(-1.0f)

            setPoint()
            rotateMatrix.transform(point1)
            rotateMatrix.transform(point2)
            rotateMatrix.transform(point3)
            rotateMatrix.transform(point4)
            point1.add(vec3f1.x(), vec3f1.y(), vec3f1.z(), 0.0f)
            point2.add(vec3f1.x(), vec3f1.y(), vec3f1.z(), 0.0f)
            point3.add(vec3f1.x(), vec3f1.y(), vec3f1.z(), 0.0f)
            point4.add(vec3f1.x(), vec3f1.y(), vec3f1.z(), 0.0f)

            scene.camera.noJitterCombined.transformProject(point1)
            scene.camera.noJitterCombined.transformProject(point2)
            scene.camera.noJitterCombined.transformProject(point3)
            scene.camera.noJitterCombined.transformProject(point4)

            point1.mul(0.5f, 0.5f, 0.5f, 1.0f).add(0.5f, 0.5f, 0.5f, 0.0f)
            point2.mul(0.5f, 0.5f, 0.5f, 1.0f).add(0.5f, 0.5f, 0.5f, 0.0f)
            point3.mul(0.5f, 0.5f, 0.5f, 1.0f).add(0.5f, 0.5f, 0.5f, 0.0f)
            point4.mul(0.5f, 0.5f, 0.5f, 1.0f).add(0.5f, 0.5f, 0.5f, 0.0f)

            point1.mul(scene.camera.screenWidth.toFloat(), scene.camera.screenHeight.toFloat(), 1.0f, 1.0f)
            point2.mul(scene.camera.screenWidth.toFloat(), scene.camera.screenHeight.toFloat(), 1.0f, 1.0f)
            point3.mul(scene.camera.screenWidth.toFloat(), scene.camera.screenHeight.toFloat(), 1.0f, 1.0f)
            point4.mul(scene.camera.screenWidth.toFloat(), scene.camera.screenHeight.toFloat(), 1.0f, 1.0f)

            val minX = min(min(point1.x(), point2.x()), min(point3.x(), point4.x()))
            val minY = min(min(point1.y(), point2.y()), min(point3.y(), point4.y()))
            val maxX = min(max(point1.x(), point2.x()), max(point3.x(), point4.x()))
            val maxY = min(max(point1.y(), point2.y()), max(point3.y(), point4.y()))

            val maxEdge = max(maxX - minX, maxY - minY)

            return maxEdge
        }
    }

    private fun hwDraw() {
        hwVsDrawTimer.begin()
        if (hwVsPipeline.batchCount > 0) {
            hwVsPipeline.draw()
        }
        hwVsDrawTimer.end()
        hwMsDrawTimer.begin()
        if (hwMsPipeline.batchCount > 0) {
            hwMsPipeline.draw()
        }
        hwMsDrawTimer.end()
    }

    private fun swDraw() {
        swDrawTimer.begin()

        swQuadPipeline.draw()
//        swPointPipeline.draw()

        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)

        swDrawTimer.end()
    }

    private fun blitSW2HW() {
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_ALWAYS)
        glDisable(GL_DEPTH_CLAMP)

        sw2hwShader.bind()
        sw2hwShader.applyBinding {
            sampler(scene.vbuffer.samplerBindings)
            buffer(scene.vbuffer.bufferBinding)
            buffer(scene.camera.uboBinding)
        }
        sw2hwShader.uniform2i("uval_viewportSizeI", scene.camera.screenSize.x, scene.camera.screenSize.y)
        scene.vbuffer.fbo.bind()
        scene.basic.drawQuad()
    }

    fun blitSW2HWDepth() {
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_ALWAYS)
        glDisable(GL_DEPTH_CLAMP)

        sw2hwDepthShader.bind()
        sw2hwDepthShader.applyBinding {
            sampler(scene.vbuffer.samplerBindings)
            buffer(scene.vbuffer.bufferBinding)
            buffer(scene.camera.uboBinding)
        }
        sw2hwDepthShader.uniform2i("uval_viewportSizeI", scene.camera.screenSize.x, scene.camera.screenSize.y)
        scene.vbuffer.depthFbo.bind()
        scene.basic.drawQuad()
    }

    private fun blitHW2SW() {
        hw2swShader.bind()
        hw2swShader.applyBinding {
            sampler(scene.vbuffer.samplerBindings)
            buffer(scene.vbuffer.bufferBinding)
        }
        glDispatchCompute(
            (scene.camera.screenSize.x + 15) / 16,
            (scene.camera.screenSize.y + 15) / 16,
            1
        )
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
    }

    enum class VisibilityFlagType(val glslTypeName: String, val byteSize: Int) {
        INT8("uint8_t", 1),
        INT16("uint16_t", 2),
        INT32("uint", 4)
    }
}