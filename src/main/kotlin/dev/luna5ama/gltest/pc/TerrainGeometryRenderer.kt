package dev.luna5ama.gltest.pc

import dev.fastmc.common.Direction
import dev.fastmc.common.TickTimer
import dev.fastmc.common.collection.CircularArray
import dev.fastmc.common.collection.FastObjectArrayList
import dev.fastmc.common.floorToInt
import dev.fastmc.common.sort.IntInsertionSort
import dev.fastmc.common.sort.IntIntrosort
import dev.fastmc.common.toRadians
import dev.luna5ama.gltest.GPUTimer
import dev.luna5ama.gltest.concurrent.LWWSP
import dev.luna5ama.gltest.concurrent.ParallelIntIntrosort
import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.pc.TerrainMeshData.ChunkLayerInfo
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.apache.commons.lang3.mutable.MutableBoolean
import org.joml.*
import java.lang.Runtime
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureNanoTime

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

    val threadBatchSize = if (GLWrapper.instance.properties.vendor == GpuVendor.NVIDIA) 96 else 128

    val debugQuadCount = false

    init {
        println("Visibility flag type: ${visibilityFlagType.name}")
        println("Using thread batch size: $threadBatchSize")
    }

    val lwwsp = LWWSP(Runtime.getRuntime().availableProcessors()) {
        it.isDaemon = true
    }
    val hwVsPipeline = register(HWVertexShaderPipeline(scene, this))
    val hwMsPipeline = register(HWMeshShaderPipeline(scene, this))
    val swQuadPipeline = register(SWQuadPipeline(scene, this))
    //    val swPointPipeline = register(SWPointPipeline(scene, this))

    //    val cullPipeline = register(OcclusionCullingPipeline.VertexShader(scene, this))
    val cullPipeline = register(OcclusionCullingPipeline.MeshShader(scene, this))
//    val cullPipeline = register(OcclusionCullingPipeline.SWRaster(scene, this))

    private val visibleBuffer = register(BufferObject.Immutable()).apply {
        label = "Terrain.VisibleBuffer"
    }

    private val chunkPosData = register(BufferObject.Immutable()).apply {
        label = "Terrain.ChunkPosData"
    }

    val tempBuffer = register(BufferObject.Immutable()).apply {
        label = "Terrain.TempBuffer"
        allocate(128L, 0)
    }

    val bufferBindings = listOf(
        ShaderBindingSpecs.Buffer("VisibleBuffer", visibleBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("ChunkPosData", chunkPosData, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("TempBuffer", tempBuffer, BufferTarget.ShaderStorage),
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
    val updateTimer = TickTimer().apply {
        reset(-999)
    }

    private val sw2hwShader = register(
        ShaderProgram(
            ShaderSource.Vert("pc/Blit.vert"),
            ShaderSource.Frag("pc/geometry/SW2HW.frag")
        )
    ).apply {
        label = "VBuffer.SW2HW"
    }
    private val sw2hwDepthShader = register(
        ShaderProgram(
            ShaderSource.Vert("pc/Blit.vert"),
            ShaderSource.Frag("pc/geometry/SW2HWDepth.frag")
        )
    ).apply {
        label = "VBuffer.SW2HW"
    }
    private val hw2swShader = register(
        ShaderProgram(
            ShaderSource.Comp("pc/geometry/HW2SW.comp")
        )
    ).apply {
        label = "VBuffer.HW2SW"
    }

    private val visibleBufferUpdateShader = register(
        ShaderProgram(ShaderSource.Comp("pc/geometry/VisibleBufferUpdate.comp") {
            define("COPY_LEN_SHIFT", (2 - visibilityFlagType.byteSize.countTrailingZeroBits()) + 2)
        })
    ).apply {
        label = "VisibleBufferUpdate"
    }

    private var chunkPosIndices = IntArray(scene.terrainMeshData.chunkPos.size) { it }

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

        if (updateTimer.tickAndReset(10)) {
            UpdateTask().run()
        }
        tryUpdate()

        cullPipeline.update()

        hwMsPipeline.preDraw()
        hwVsPipeline.preDraw()
        swQuadPipeline.preDraw()

        if (!scene.gameInstance.toggles[3]) {
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
        }
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

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
            scene.vbuffer.fbo.blitTo(scene.fboGroup.input.fbo, GL_DEPTH_BUFFER_BIT, GL_NEAREST)
            scene.vbuffer.fbo.blitTo(scene.fboGroup.output.fbo, GL_DEPTH_BUFFER_BIT, GL_NEAREST)
        }
        glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)

        if (printTimer.tickAndReset(3000)) {
//            println("Min distance ${sqrt(sortKeys.min().toDouble())}")
//            println("Min sample count: ${sampleCountArray.min()}")
//            println("Average sample count: ${sampleCountArray.average()}")
//            println("Max sample count: ${sampleCountArray.max()}")
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
            println("Update Async: %.3f ms".format(updateTimesAsync.average() / 1e6))
            println("Update Sync: %.3f ms".format(updateTimesSync.average() / 1e6))
            println()
        }
    }

    private val updateTimesAsync = CircularArray<Long>(200)
    private val updateTimesSync = CircularArray<Long>(200)

    private inner class UpdateTask {
        private val frustumCullingFlags = Array(scene.terrainMeshData.chunkPos.size) {
            MutableBoolean(true)
        }
        private val sampleCountArray = FloatArray(scene.terrainMeshData.chunkPos.size)
        private val faceMaskArray = IntArray(scene.terrainMeshData.chunkPos.size)
        private val chunkPosIndices = this@TerrainGeometryRenderer.chunkPosIndices.copyOf()

        fun run() {
            scene.gameInstance.backgroundTaskScope.launch(lwwsp) {
                measureNanoTime {
                    doUpdate1()
                    doUpdate2()
                }.let {
                    synchronized(updateTimesAsync) {
                        updateTimesAsync.add(it)
                    }
                }
            }
        }

        private suspend fun doUpdate1() {
            val cameraPos = scene.camera.pos
            val chunkPosList = scene.terrainMeshData.chunkPos

            val newMatrix = Matrix4f()
            scene.camera.apply {
                newMatrix.setupPerspective(
                    (min(fov + scene.camera.maxRotationAngle * 32.0f, 180.0f)).toRadians(),
                    aspectRatio,
                    nearPlane,
                    farPlane
                )
            }
            newMatrix.mul(scene.camera.modelView)
            val frustumCull = FrustumIntersection(newMatrix, false)

            class Scope1 {
                val originOffsetX = cameraPos.x.floorToInt()
                val originOffsetY = cameraPos.y.floorToInt()
                val originOffsetZ = cameraPos.z.floorToInt()
            }

            coroutineScope {
                launch {
                    LWWSP.iterativeTask(chunkPosList.size, 256, ::Scope1) { start, end ->
                        for (i in start until end) {
                            val chunkPos = chunkPosList[i]
                            val dx = chunkPos.x * 16 + 8 - originOffsetX
                            val dy = chunkPos.y * 16 + 8 - originOffsetY
                            val dz = chunkPos.z * 16 + 8 - originOffsetZ
                            sortKeys[i] = -(dx * dx + dy * dy + dz * dz)
                        }
                    }.submit().await().getOrThrow()

                    ParallelIntIntrosort(this).iSort(chunkPosIndices, sortKeys)
                }

                launch {
                    LWWSP.iterativeTask(chunkPosList.size, 256, ::QuadAreaCalc) { start, end ->
                        for (i in start until end) {
                            val chunkPos = chunkPosList[i]
                            sampleCountArray[i] = calc(chunkPos)

                            val worldPosX = chunkPos.x shl 4
                            val worldPosY = chunkPos.y shl 4
                            val worldPosZ = chunkPos.z shl 4
                            faceMaskArray[i] =
                                (Direction.B_EAST * (((worldPosX - 1) - scene.camera.pos.x).floorToInt() ushr 31)) or
                                        (Direction.B_WEST * ((scene.camera.pos.x - (worldPosX + 17)).floorToInt() ushr 31)) or
                                        (Direction.B_UP * (((worldPosY - 1) - scene.camera.pos.y).floorToInt() ushr 31)) or
                                        (Direction.B_DOWN * ((scene.camera.pos.y - (worldPosY + 17)).floorToInt() ushr 31)) or
                                        (Direction.B_SOUTH * (((worldPosZ - 1) - scene.camera.pos.z).floorToInt() ushr 31)) or
                                        (Direction.B_NORTH * ((scene.camera.pos.z - (worldPosZ + 17)).floorToInt() ushr 31))

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
                    }.submit().await().getOrThrow()
                }
            }

            this@TerrainGeometryRenderer.chunkPosIndices = chunkPosIndices
            this@TerrainGeometryRenderer.lastCameraPos.set(cameraPos)
        }

        private suspend fun doUpdate2() {
            class Scope2 {
                val updateData = UpdateData()
                val filteredChunks = arrayOfNulls<ChunkLayerInfo>(63)
            }

            val scopes = LWWSP.iterativeTask(chunkPosIndices.size, 256, ::Scope2) { start, end ->
                val cameraPos = scene.camera.pos
                val chunkPosList = scene.terrainMeshData.chunkPos
                val originOffsetX = cameraPos.x.floorToInt()
                val originOffsetY = cameraPos.y.floorToInt()
                val originOffsetZ = cameraPos.z.floorToInt()
                val rangeSq = scene.camera.viewDistance * scene.camera.viewDistance

                for (i1 in start until end) {
                    val chunkIndex = chunkPosIndices[i1]
                    val chunkPos = chunkPosList[chunkIndex]
                    val dx = chunkPos.x * 16 + 8 - originOffsetX
                    val dy = chunkPos.y * 16 + 8 - originOffsetY
                    val dz = chunkPos.z * 16 + 8 - originOffsetZ
                    if (dx * dx + dy * dy + dz * dz > rangeSq) continue
                    if (!frustumCullingFlags[chunkIndex].value) continue

                    val rawList = scene.terrainMeshData.chunk2LayerMap[chunkIndex] ?: continue
                    val rawListArray = rawList.elements()
                    var filteredSize = 0
                    for (i in rawList.indices) {
                        val chunk = rawListArray[i]
                        if (faceMaskArray[chunkIndex] and chunk.faceBits == 0) continue
                        filteredChunks[filteredSize++] = chunk
                    }

                    if (filteredSize == 0) continue
                    if (filteredSize == 1) {
                        updateData.add(filteredChunks[0]!!)
                        continue
                    }

                    var startingIndex = -1
                    fun flush(n: Int) {
                        if (startingIndex != -1) {
                            var quadCount = 0
                            for (i in startingIndex until n) {
                                quadCount += filteredChunks[i]!!.quadCount
                            }
                            val chunkLayerInfo = filteredChunks[startingIndex]!!
                            updateData.add(
                                ChunkLayerInfo(
                                    chunkLayerInfo.chunkIndex,
                                    chunkLayerInfo.pos,
                                    quadCount,
                                    chunkLayerInfo.startIndex,
                                    111111
                                )
                            )
                            startingIndex = -1
                        } else {
                            updateData.add(filteredChunks[n - 1]!!)
                        }
                    }
                    for (i in 1 until filteredSize) {
                        val last = filteredChunks[i - 1]!!
                        val current = filteredChunks[i]!!
                        if (last.startIndex + last.quadCount == current.startIndex) {
                            if (startingIndex == -1) {
                                startingIndex = i - 1
                            }
                            continue
                        }
                        flush(i)
                    }
                    flush(filteredSize)
                }
            }.submit().await().getOrThrow().map { it.updateData }

            val first = scopes.first()
            first.addAllOther(scopes.drop(1))

            pendingUpdateData.send(first)
        }

        inner class UpdateData {
            val lists = List(3) { FastObjectArrayList.typed<ChunkLayerInfo>() }
            val hwVs = lists[0]
            val hwMs = lists[1]
            val swQuad = lists[2]

            fun add(chunkInfo: ChunkLayerInfo) {
                if (sampleCountArray[chunkInfo.chunkIndex] > 8.0f) {
                    if (!scene.gameInstance.toggles[1]) {
                        hwMs.add(chunkInfo)
//                        hwVs.add(chunkInfo)
                    }
                } else {
                    if (!scene.gameInstance.toggles[2]) {
                        swQuad.add(chunkInfo)
                    }
                }
            }

            fun addAll(other: UpdateData) {
                for (i in lists.indices) {
                    lists[i].addAll(other.lists[i])
                }
            }

            fun addAllOther(others: List<UpdateData>) {
                for (i in lists.indices) {
                    val listThis = lists[i]
                    listThis.ensureCapacity(listThis.size + others.sumOf { it.lists[i].size })
                    for (other in others) {
                        listThis.addAll(other.lists[i])
                    }
                }
            }
        }
    }

    private val pendingUpdateData = Channel<UpdateTask.UpdateData>(1, BufferOverflow.DROP_OLDEST)

    private suspend fun tryUpdate() {
        val updateData = pendingUpdateData.tryReceive().getOrNull() ?: return

        measureNanoTime {
            coroutineScope {
                launch(lwwsp) { hwVsPipeline.update(updateData.hwVs) }
                launch(lwwsp) { hwMsPipeline.update(updateData.hwMs) }
                launch(lwwsp) { swQuadPipeline.update(updateData.swQuad) }
            }
        }.let {
            updateTimesSync.add(it)
        }
    }

    private fun chunkCountString(pipeline: TerrainDrawPipeline): String {
        return "%,d quads, %,d batches, %,d/%,d chunks".format(
            pipeline.quadCount,
            pipeline.batchCount,
            pipeline.chunkCount,
            scene.terrainMeshData.chunkPos.size
        )
    }

    private var sortKeys = IntArray(scene.terrainMeshData.chunkPos.size)
    private val lastCameraPos = Vector3d()

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

            val minX = minOf(point1.x(), point2.x(), point3.x(), point4.x())
            val minY = minOf(point1.y(), point2.y(), point3.y(), point4.y())
            val maxX = maxOf(point1.x(), point2.x(), point3.x(), point4.x())
            val maxY = maxOf(point1.y(), point2.y(), point3.y(), point4.y())
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

        if (swQuadPipeline.batchCount > 0) {
            swQuadPipeline.draw()
        }
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