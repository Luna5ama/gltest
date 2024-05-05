package dev.luna5ama.gltest.pc

import dev.fastmc.common.ParallelUtils
import dev.fastmc.common.TickTimer
import dev.fastmc.common.ceilToInt
import dev.fastmc.common.floorToInt
import dev.fastmc.common.sort.IntIntrosort
import dev.luna5ama.gltest.AbstractScene
import dev.luna5ama.gltest.structs.*
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.api.GL_DYNAMIC_STORAGE_BIT
import dev.luna5ama.glwrapper.api.GL_MAP_INVALIDATE_BUFFER_BIT
import dev.luna5ama.glwrapper.api.GL_MAP_WRITE_BIT
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.apache.commons.lang3.mutable.MutableBoolean
import org.joml.*
import java.io.File
import java.io.FileNotFoundException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.sqrt

class PointCloudData(private val scene: AbstractScene, dir: File) : IGLObjContainer by IGLObjContainer.Impl() {
    private val batchMetadataBuffer = register(BufferObject.Immutable())
    private val quadPosDataBuffer = register(BufferObject.Immutable())
    private val quadAttribDataBuffer = register(BufferObject.Immutable())
    private val quadPosIndexBuffer = register(BufferObject.Immutable())
    private val quadAttribIndexBuffer = register(BufferObject.Immutable())
    val chunkinfos = mutableListOf<ChunkInfo>()

    val bufferBindings = listOf(
        ShaderBindingSpecs.Buffer("BatchMetadataBuffer", batchMetadataBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("QuadPosDataBuffer", quadPosDataBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("QuadAttribDataBuffer", quadAttribDataBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("QuadPosIndexBuffer", quadPosIndexBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("QuadAttribIndexBuffer", quadAttribIndexBuffer, BufferTarget.ShaderStorage)
    )

    val chunkOffsets = Array(TOTAL_CHUNKS) { Vector3i() }.apply {
        var i = 0
        for (z in 0 until SIZE_XZ) {
            for (x in 0 until SIZE_XZ) {
                for (y in 0 until SIZE_Y) {
                    this[i++].set(x, y, z)
                }
            }
        }
    }

    var chunkIndices: IntArray

    var pointCount = 0L
        private set
    var batchCount = 0
        private set

    init {
        runBlocking {
            try {
                readCData(dir)
            } catch (e: FileNotFoundException) {
                readData(dir)
            }
            chunkIndices = IntArray(chunkinfos.size) { it }
            val bufferUsage =
                quadPosDataBuffer.size + quadAttribDataBuffer.size + quadPosIndexBuffer.size + quadAttribIndexBuffer.size
            println("Quad pos data buffer: %.2f MB".format(quadPosDataBuffer.size / 1024.0 / 1024.0))
            println("Quad pos index buffer: %.2f MB".format(quadPosIndexBuffer.size / 1024.0 / 1024.0))
            println("Quad attrib data buffer: %.2f MB".format(quadAttribDataBuffer.size / 1024.0 / 1024.0))
            println("Quad attrib index buffer: %.2f MB".format(quadAttribIndexBuffer.size / 1024.0 / 1024.0))
            println("Total buffer usage: %.2f MB".format(bufferUsage / 1024.0 / 1024.0))
        }
    }

    private suspend fun readData(dir: File) {
        val layers = 2
        val pointCount = (0 until layers).sumOf { File(dir, "terrain_vbo_$it.bin").length() } / 4L / 20L

        val quadCounter = AtomicLong(0)
        val channel = ConcurrentLinkedQueue<List<ChunkInfo>>()

        val quadPosDataCounter = AtomicInteger()
        val quadAttribDataCounter = AtomicInteger()

        val quadPosDataIdxMap = ConcurrentHashMap<PosKey, Int>()
        val quadAttribDataIdxMap = ConcurrentHashMap<AttribKey, Int>()

        val quadPosDataUploadBuffer = Arr.malloc(pointCount * 24L)
        val quadAttribUploadBuffer = Arr.malloc(pointCount * 48L)

        fun addPosData(v1: TerrainVertex, v2: TerrainVertex, v3: TerrainVertex, v4: TerrainVertex): Int {
            val key = PosKey(
                (v1.posX.toLong() and 0xFFFF) or ((v2.posY.toLong() and 0xFFFF) shl 16) or ((v3.posZ.toLong() and 0xFFFF) shl 32) or ((v4.posX.toLong() and 0xFFFF) shl 48),
                (v1.posY.toLong() and 0xFFFF) or ((v2.posZ.toLong() and 0xFFFF) shl 16) or ((v3.posX.toLong() and 0xFFFF) shl 32) or ((v4.posY.toLong() and 0xFFFF) shl 48),
                (v1.posZ.toLong() and 0xFFFF) or ((v2.posX.toLong() and 0xFFFF) shl 16) or ((v3.posY.toLong() and 0xFFFF) shl 32) or ((v4.posZ.toLong() and 0xFFFF) shl 48)
            )
            return quadPosDataIdxMap.computeIfAbsent(key) {
                val idx = quadPosDataCounter.getAndIncrement()
                val offset = idx * 24L
                val pPosStruct = PointPos(quadPosDataUploadBuffer.ptr + offset)
                pPosStruct.posX1 = v1.posX
                pPosStruct.posX2 = v2.posX
                pPosStruct.posX3 = v3.posX
                pPosStruct.posX4 = v4.posX

                pPosStruct.posY1 = v1.posY
                pPosStruct.posY2 = v2.posY
                pPosStruct.posY3 = v3.posY
                pPosStruct.posY4 = v4.posY

                pPosStruct.posZ1 = v1.posZ
                pPosStruct.posZ2 = v2.posZ
                pPosStruct.posZ3 = v3.posZ
                pPosStruct.posZ4 = v4.posZ
                idx
            }
        }

        fun addAttribData(v1: TerrainVertex, v2: TerrainVertex, v3: TerrainVertex, v4: TerrainVertex): Int {
            val key = AttribKey(
                (v1.uvU.toLong() and 0xFFFF) or ((v2.uvU.toLong() and 0xFFFF) shl 16)
                        or ((v3.uvU.toLong() and 0xFFFF) shl 32) or ((v4.uvU.toLong() and 0xFFFF) shl 48),
                (v1.uvV.toLong() and 0xFFFF) or ((v2.uvV.toLong() and 0xFFFF) shl 16)
                        or ((v3.uvV.toLong() and 0xFFFF) shl 32) or ((v4.uvV.toLong() and 0xFFFF) shl 48),
                (v1.lightMapX.toLong() and 0xFF) or ((v2.lightMapX.toLong() and 0xFF) shl 8)
                        or ((v3.lightMapX.toLong() and 0xFF) shl 16) or ((v4.lightMapX.toLong() and 0xFF) shl 24)
                        or ((v1.lightMapY.toLong() and 0xFF) shl 32) or ((v2.lightMapY.toLong() and 0xFF) shl 40)
                        or ((v3.lightMapY.toLong() and 0xFF) shl 48) or ((v4.lightMapY.toLong() and 0xFF) shl 56),
                (v1.colorR.toLong() and 0xFF) or ((v2.colorR.toLong() and 0xFF) shl 8)
                        or ((v3.colorR.toLong() and 0xFF) shl 16) or ((v4.colorR.toLong() and 0xFF) shl 24)
                        or ((v1.colorG.toLong() and 0xFF) shl 32) or ((v2.colorG.toLong() and 0xFF) shl 40)
                        or ((v3.colorG.toLong() and 0xFF) shl 48) or ((v4.colorG.toLong() and 0xFF) shl 56),
                (v1.colorB.toLong() and 0xFF) or ((v2.colorB.toLong() and 0xFF) shl 8)
                        or ((v3.colorB.toLong() and 0xFF) shl 16) or ((v4.colorB.toLong() and 0xFF) shl 24)

            )
            return quadAttribDataIdxMap.computeIfAbsent(key) {
                val idx = quadAttribDataCounter.getAndIncrement()
                val offset = idx * 48L
                var pAttribStruct = PointAttrib(quadAttribUploadBuffer.ptr + offset)
                pAttribStruct.uvU = v1.uvU
                pAttribStruct.uvV = v1.uvV
                pAttribStruct.lightMapX = v1.lightMapX
                pAttribStruct.lightMapY = v1.lightMapY
                pAttribStruct.colorR = v1.colorR
                pAttribStruct.colorG = v1.colorG
                pAttribStruct.colorB = v1.colorB
                pAttribStruct++

                pAttribStruct.uvU = v2.uvU
                pAttribStruct.uvV = v2.uvV
                pAttribStruct.lightMapX = v2.lightMapX
                pAttribStruct.lightMapY = v2.lightMapY
                pAttribStruct.colorR = v2.colorR
                pAttribStruct.colorG = v2.colorG
                pAttribStruct.colorB = v2.colorB
                pAttribStruct++

                pAttribStruct.uvU = v3.uvU
                pAttribStruct.uvV = v3.uvV
                pAttribStruct.lightMapX = v3.lightMapX
                pAttribStruct.lightMapY = v3.lightMapY
                pAttribStruct.colorR = v3.colorR
                pAttribStruct.colorG = v3.colorG
                pAttribStruct.colorB = v3.colorB
                pAttribStruct++

                pAttribStruct.uvU = v4.uvU
                pAttribStruct.uvV = v4.uvV
                pAttribStruct.lightMapX = v4.lightMapX
                pAttribStruct.lightMapY = v4.lightMapY
                pAttribStruct.colorR = v4.colorR
                pAttribStruct.colorG = v4.colorG
                pAttribStruct.colorB = v4.colorB

                idx
            }
        }

        val quadPosIndexUploadBuffer = Arr.malloc(pointCount * 4L)
        val quadAttribIndexUploadBuffer = Arr.malloc(pointCount * 4L)

        coroutineScope {
            repeat(layers) {
                launch(Dispatchers.Default) {
                    val vboFile = File(dir, "terrain_vbo_$it.bin")
                    val infoFile = File(dir, "terrain_info_$it.csv")
                    val infoLines = infoFile.useLines {
                        it.drop(1).map {
                            val parts = it.split(",")
                            ChunkInfoCSVEntry(
                                parts[2].toInt(),
                                parts[3].toInt(),
                                parts[4].toInt(),
                                parts[5].toLong(),
                                parts[6].toLong(),
                                parts[7].toLong(),
                                parts[8].toLong()
                            )
                        }.toList()
                    }
                    ParallelUtils.splitListIndex(
                        infoLines.size,
                        128,
                        blockForEach = { start, end ->
                            val readBuffer = Arr.malloc(16 * 1024 * 4L * 20L)
                            launch(Dispatchers.IO) {
                                val chunkInfoListLocal = mutableListOf<ChunkInfo>()
                                var localPosIdxBufferArray = IntArray(0)
                                var localAttribIdxBufferArray = IntArray(0)

                                for (infoIdx in start until end) {
                                    val info = infoLines[infoIdx]
                                    if (info.chunkY - MIN_CHUNK_Y < 6) continue
                                    var readOffset = info.vertexOffset
                                    val readEnd = info.vertexOffset + info.vertexLength
                                    FileChannel.open(vboFile.toPath(), StandardOpenOption.READ)
                                        .use { channel ->
                                            while (readOffset < readEnd) {
                                                val read = withContext(Dispatchers.IO) {
                                                    val buf = readBuffer.ptr.asByteBuffer(
                                                        min(readBuffer.len, info.vertexLength).toInt()
                                                    )
                                                    val read = channel.read(buf, readOffset)
                                                    if (read == -1) {
                                                        throw IllegalStateException("Unexpected EOF")
                                                    }
                                                    read
                                                }

                                                if (read == 0) break

                                                readOffset += read

                                                val quadCount = read / 4 / 20

                                                if (localPosIdxBufferArray.size < quadCount) {
                                                    localPosIdxBufferArray = IntArray(quadCount)
                                                    localAttribIdxBufferArray = IntArray(quadCount)
                                                }

                                                var tempPtr = readBuffer.ptr

                                                for (i in 0 until quadCount) {
                                                    val v1 = TerrainVertex(tempPtr)
                                                    tempPtr += 20L
                                                    val v2 = TerrainVertex(tempPtr)
                                                    tempPtr += 20L
                                                    val v3 = TerrainVertex(tempPtr)
                                                    tempPtr += 20L
                                                    val v4 = TerrainVertex(tempPtr)
                                                    tempPtr += 20L

                                                    localPosIdxBufferArray[i] = addPosData(v1, v2, v3, v4)
                                                    localAttribIdxBufferArray[i] = addAttribData(v1, v2, v3, v4)
                                                }

                                                val quadIndex = quadCounter.getAndAdd(quadCount.toLong())
                                                val chunkIndex =
                                                    (info.chunkY - MIN_CHUNK_Y) + ((info.chunkX + ORIGIN) + (info.chunkZ + ORIGIN) * SIZE_XZ) * SIZE_Y

                                                memcpy(
                                                    localPosIdxBufferArray,
                                                    0L,
                                                    quadPosIndexUploadBuffer.ptr,
                                                    quadIndex * 4L,
                                                    quadCount * 4L
                                                )

                                                memcpy(
                                                    localAttribIdxBufferArray,
                                                    0L,
                                                    quadAttribIndexUploadBuffer.ptr,
                                                    quadIndex * 4L,
                                                    quadCount * 4L
                                                )

                                                chunkInfoListLocal += ChunkInfo(
                                                    chunkIndex,
                                                    Vector3i(info.chunkX, info.chunkY, info.chunkZ),
                                                    quadCount,
                                                    quadIndex
                                                )
                                            }
                                        }
                                }
                                channel.add(chunkInfoListLocal)
                                readBuffer.free()
                            }
                        }
                    )
                }
            }
        }

        println()

        coroutineScope {
            launch {
                for (chunkInfoList in channel) {
                    chunkinfos.addAll(chunkInfoList)
                }
                chunkinfos.forEachIndexed { index, chunkInfo ->
                    chunkInfo.listIndex = index
                }

                launch(Dispatchers.IO) {
                    File(dir, "pc_info.csv").bufferedWriter().use {
                        it.appendLine("listIndex,chunkIndex,chunkX,chunkY,chunkZ,quadCount,startIndex")
                        chunkinfos.forEach { chunkInfo ->
                            it.appendLine("${chunkInfo.listIndex},${chunkInfo.chunkIndex},${chunkInfo.pos.x},${chunkInfo.pos.y},${chunkInfo.pos.z},${chunkInfo.quadCount},${chunkInfo.startIndex}")
                        }
                    }
                }

                this@PointCloudData.pointCount = quadCounter.get()

                quadPosDataBuffer.allocate(quadPosDataCounter.get() * 24L, quadPosDataUploadBuffer.ptr, 0)
                quadAttribDataBuffer.allocate(quadAttribDataCounter.get() * 48L, quadAttribUploadBuffer.ptr, 0)
                quadPosIndexBuffer.allocate(quadCounter.get() * 4L, quadPosIndexUploadBuffer.ptr, 0)
                quadAttribIndexBuffer.allocate(quadCounter.get() * 4L, quadAttribIndexUploadBuffer.ptr, 0)
            }
            fun FileChannel.write(data: Ptr, length: Long) {
                this.write(data.asByteBuffer(length.toInt()))
            }

            launch(Dispatchers.IO) {
                FileChannel.open(
                    File(dir, "pc_quad_pos_data.bin").toPath(),
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE
                ).use { channel ->
                    channel.write(quadPosDataUploadBuffer.ptr, quadPosDataCounter.get() * 24L)
                }
            }

            launch(Dispatchers.IO) {
                FileChannel.open(
                    File(dir, "pc_quad_attrib_data.bin").toPath(),
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE
                ).use { channel ->
                    channel.write(quadAttribUploadBuffer.ptr, quadAttribDataCounter.get() * 48L)
                }
            }

            launch(Dispatchers.IO) {
                FileChannel.open(
                    File(dir, "pc_quad_pos_index.bin").toPath(),
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE
                ).use { channel ->
                    channel.write(quadPosIndexUploadBuffer.ptr, quadCounter.get() * 4L)
                }
            }

            launch(Dispatchers.IO) {
                FileChannel.open(
                    File(dir, "pc_quad_attrib_index.bin").toPath(),
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE
                ).use { channel ->
                    channel.write(quadAttribIndexUploadBuffer.ptr, quadCounter.get() * 4L)
                }
            }
        }
    }

    private suspend fun readCData(dir: File) {
        coroutineScope {
            val mainCtx = this.coroutineContext
            launch(Dispatchers.IO) {
                val infoFile = File(dir, "pc_info.csv")
                val chunkInfoList = infoFile.useLines { sequence ->
                    sequence.drop(1).map {
                        val parts = it.split(",")
                        ChunkInfo(
                            parts[1].toInt(),
                            Vector3i(parts[2].toInt(), parts[3].toInt(), parts[4].toInt()),
                            parts[5].toInt(),
                            parts[6].toLong()
                        ).apply {
                            listIndex = parts[0].toInt()
                        }
                    }.toList()
                }
                chunkinfos.addAll(chunkInfoList)
                pointCount = chunkinfos.sumOf { it.quadCount.toLong() }
            }

            suspend fun readFileToBuffer(input: File, output: BufferObject) {
                val copyBuffer = BufferObject.Immutable()
                try {
                    copyBuffer.allocate(16 * 1024 * 1024, GL_MAP_WRITE_BIT)
                    val length = input.length()
                    output.allocate(length, 0)
                    withContext(Dispatchers.IO) {
                        FileChannel.open(
                            input.toPath(),
                            StandardOpenOption.READ
                        ).use { channel ->
                            var offset = 0L
                            var remaining = length

                            while (remaining > 0) {
                                val read = min(remaining, copyBuffer.size)
                                val mapped = withContext(mainCtx) {
                                    copyBuffer.map(GL_MAP_WRITE_BIT or GL_MAP_INVALIDATE_BUFFER_BIT)
                                }
                                channel.read(mapped.ptr.asByteBuffer(read.toInt()), offset)
                                withContext(mainCtx) {
                                    copyBuffer.unmap()
                                    copyBuffer.copyTo(output, 0L, offset, read)
                                }
                                offset += read
                                remaining -= read
                            }
                        }
                    }
                } finally {
                    copyBuffer.destroy()
                }
            }

            launch {
                readFileToBuffer(File(dir, "pc_quad_pos_data.bin"), quadPosDataBuffer)
            }
            launch {
                readFileToBuffer(File(dir, "pc_quad_attrib_data.bin"), quadAttribDataBuffer)
            }
            launch {
                readFileToBuffer(File(dir, "pc_quad_pos_index.bin"), quadPosIndexBuffer)
            }
            launch {
                readFileToBuffer(File(dir, "pc_quad_attrib_index.bin"), quadAttribIndexBuffer)
            }
        }
    }

    private val printTimer = TickTimer()
    private val batchMetaUploadBuffer = Arr.malloc(chunkinfos.size * sizeof(BatchMetadata))
    private val frustumCullingFlags = Array(chunkinfos.size) {
        MutableBoolean(true)
    }

    private val sampleCountPow2Array = IntArray(chunkinfos.size)

    suspend fun updateBatchMeta() {
//        printTimer.reset()
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

        if (printTimer.tick(3000)) {
            println()
            println("Min sample count: ${sampleCountPow2Array.min()}")
            println("Average sample count: ${sampleCountPow2Array.average()}")
            println("Max sample count: ${sampleCountPow2Array.max()}")
        }

        var metaIdx = 0
        for (i in chunkIndices) {
            val chunkInfo = chunkinfos[i]
            if (!frustumCullingFlags[chunkInfo.listIndex].value) continue
            val offset = chunkOffsets[chunkInfo.chunkIndex]
            var quadIdx = 0
//            val sampleCountPow2 = 4
            val sampleCountPow2 = sampleCountPow2Array[chunkInfo.listIndex]
            val sampleCount = 1 shl abs(sampleCountPow2)
            val count = if (sampleCountPow2 > 0) BATCH_SIZE / sampleCount else BATCH_SIZE * sampleCount

            while (quadIdx < chunkInfo.quadCount) {
                batchMetaUploadBuffer.ensureCapacity((metaIdx + 1L) * sizeof(BatchMetadata), false)
                val struct = BatchMetadata(batchMetaUploadBuffer.ptr + metaIdx * sizeof(BatchMetadata))
                val batchCount = min(chunkInfo.quadCount - quadIdx, count)
                struct.first = (chunkInfo.startIndex + quadIdx).toInt()
                struct.count = batchCount
                struct.offsetX = offset.x.toByte()
                struct.offsetY = offset.y.toByte()
                struct.offsetZ = offset.z.toByte()
                struct.pointSampleCount = sampleCountPow2
                quadIdx += batchCount
                metaIdx++
            }
        }
        val metaLen = metaIdx * sizeof(BatchMetadata)
        if (metaLen > 0) {
            if (batchMetadataBuffer.size < metaLen) {
                batchMetadataBuffer.destroy()
                batchMetadataBuffer.allocate(metaLen, batchMetaUploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
            } else {
                batchMetadataBuffer.invalidate()
                batchMetadataBuffer.upload(0L, metaLen, batchMetaUploadBuffer.ptr)
            }
        }
        batchCount = metaIdx

        if (printTimer.tick(3000)) {
            println("Batch count: ${chunkinfos.size}/$batchCount")
            println()
            printTimer.reset()
        }
    }

    private fun CoroutineScope.calcSampleCount() {
        ParallelUtils.splitListIndex(
            chunkinfos.size,
            blockForEach = { start, end ->
                launch {
                    val areaCalc = QuadAreaCalc()
                    for (i in start until end) {
                        val chunkInfo = chunkinfos[i]
                        val area = areaCalc.calc(chunkInfo)
                        if (area < 1.0f) {
                            val lol = min(log2(0.5f / area).floorToInt(), 8)
                            sampleCountPow2Array[chunkInfo.listIndex] = -lol
                        } else {
                            sampleCountPow2Array[chunkInfo.listIndex] = min(log2(area).ceilToInt(), 4)
                        }
                    }
                }
            }
        )
    }

    private val distanceSqs = IntArray(chunkIndices.size)
    private suspend fun sortChunks() {
        coroutineScope {
            ParallelUtils.splitListIndex(
                chunkIndices.size,
                blockForEach = { start, end ->
                    launch {
                        val originOffsetX = (PointCloudScene.originX - scene.camera.pos.x).floorToInt()
                        val originOffsetY = (PointCloudScene.originY - scene.camera.pos.y).floorToInt()
                        val originOffsetZ = (PointCloudScene.originZ - scene.camera.pos.z).floorToInt()
                        for (i in start until end) {
                            val chunkInfo = chunkinfos[i]
                            val offset = chunkOffsets[chunkInfo.chunkIndex]
                            val dx = offset.x * 16 + originOffsetX
                            val dy = offset.y * 16 + originOffsetY
                            val dz = offset.z * 16 + originOffsetZ
                            distanceSqs[i] = (dx * dx + dy * dy + dz * dz)
                        }
                    }
                }
            )
        }

        if (printTimer.tick(3000)) {
            println("Min distance ${sqrt(distanceSqs.min().toDouble())}")
        }

        IntIntrosort.sort(chunkIndices, distanceSqs)
    }

    private fun CoroutineScope.doFrustumCulling() {
        val frustumCull = FrustumIntersection(scene.camera.combined, false)
        ParallelUtils.splitListIndex(
            chunkIndices.size,
            blockForEach = { start, end ->
                launch {
                    for (i in start until end) {
                        val chunkInfo = chunkinfos[chunkIndices[i]]
                        frustumCullingFlags[chunkInfo.listIndex].setValue(
                            frustumCull.testAab(
                                (chunkInfo.minX - scene.camera.pos.x).toFloat(),
                                (chunkInfo.minY - scene.camera.pos.y).toFloat(),
                                (chunkInfo.minZ - scene.camera.pos.z).toFloat(),
                                (chunkInfo.maxX - scene.camera.pos.x).toFloat(),
                                (chunkInfo.maxY - scene.camera.pos.y).toFloat(),
                                (chunkInfo.maxZ - scene.camera.pos.z).toFloat()
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

        fun calc(chunkInfo: ChunkInfo): Float {
            vec3f1.set(
                0.0f.coerceIn(
                    (chunkInfo.minX - scene.camera.pos.x).toFloat(),
                    (chunkInfo.maxX - scene.camera.pos.x).toFloat()
                ),
                0.0f.coerceIn(
                    (chunkInfo.minY - scene.camera.pos.y).toFloat(),
                    (chunkInfo.maxY - scene.camera.pos.y).toFloat()
                ),
                0.0f.coerceIn(
                    (chunkInfo.minZ - scene.camera.pos.z).toFloat(),
                    (chunkInfo.maxZ - scene.camera.pos.z).toFloat()
                )
            )
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

            val area = 0.5f * (
                    point1.x() * point2.y() +
                            point2.x() * point3.y() +
                            point3.x() * point4.y() +
                            point4.x() * point1.y() -
                            point2.x() * point1.y() -
                            point3.x() * point2.y() -
                            point4.x() * point3.y() -
                            point1.x() * point4.y()
                    )

            return abs(area)
        }
    }

    private suspend fun readUpload(
        mainCtx: CoroutineContext,
        vboFile: File,
        infoFile: File,
        globalQuadCount: AtomicLong
    ): List<ChunkInfo> {
        val channel = Channel<List<ChunkInfo>>(Channel.UNLIMITED)
        val infoLines = infoFile.useLines {
            it.drop(1).map {
                val parts = it.split(",")
                ChunkInfoCSVEntry(
                    parts[2].toInt(),
                    parts[3].toInt(),
                    parts[4].toInt(),
                    parts[5].toLong(),
                    parts[6].toLong(),
                    parts[7].toLong(),
                    parts[8].toLong()
                )
            }.toList()
        }
        return coroutineScope {
            launch(Dispatchers.Default) {
                coroutineScope {
                    ParallelUtils.splitListIndex(
                        infoLines.size,
                        128,
                        blockForEach = { start, end ->
                            val tempBuffer = Arr.malloc(128 * 1024 * 4L * 20L)
                            val uploadBuffer = withContext(mainCtx) {
                                BufferObject.Immutable().apply {
                                    allocate(128 * 1024 * 16L, GL_MAP_WRITE_BIT)
                                }
                            }
                            launch(Dispatchers.IO) {
                                try {
                                    val chunkInfoListLocal = mutableListOf<ChunkInfo>()
                                    for (infoIdx in start until end) {
                                        val info = infoLines[infoIdx]
                                        if (info.chunkY - MIN_CHUNK_Y < 6) continue
                                        var readOffset = info.vertexOffset
                                        val readEnd = info.vertexOffset + info.vertexLength
                                        FileChannel.open(vboFile.toPath(), StandardOpenOption.READ).use { channel ->
                                            while (readOffset < readEnd) {
                                                val read = withContext(Dispatchers.IO) {
                                                    val buf = tempBuffer.ptr.asByteBuffer(
                                                        min(uploadBuffer.size / 72 * 80, info.vertexLength).toInt()
                                                    )
                                                    val read = channel.read(buf, readOffset)
                                                    if (read == -1) {
                                                        throw IllegalStateException("Unexpected EOF")
                                                    }
                                                    read
                                                }

                                                if (read == 0) break

                                                readOffset += read

                                                val mapped = withContext(mainCtx) {
                                                    uploadBuffer.map(GL_MAP_WRITE_BIT or GL_MAP_INVALIDATE_BUFFER_BIT)
                                                }
                                                val quadCount = read / 4 / 20
                                                var tempPtr = tempBuffer.ptr
                                                var writeStructPtr1 = PointPos(mapped.ptr)

                                                for (i in 0 until quadCount) {
                                                    val v1 = TerrainVertex(tempPtr)
                                                    tempPtr += 20L
                                                    val v2 = TerrainVertex(tempPtr)
                                                    tempPtr += 20L
                                                    val v3 = TerrainVertex(tempPtr)
                                                    tempPtr += 20L
                                                    val v4 = TerrainVertex(tempPtr)
                                                    tempPtr += 20L
                                                    writeStructPtr1.posX1 = v1.posX
                                                    writeStructPtr1.posX2 = v2.posX
                                                    writeStructPtr1.posX3 = v3.posX
                                                    writeStructPtr1.posX4 = v4.posX

                                                    writeStructPtr1.posY1 = v1.posY
                                                    writeStructPtr1.posY2 = v2.posY
                                                    writeStructPtr1.posY3 = v3.posY
                                                    writeStructPtr1.posY4 = v4.posY

                                                    writeStructPtr1.posZ1 = v1.posZ
                                                    writeStructPtr1.posZ2 = v2.posZ
                                                    writeStructPtr1.posZ3 = v3.posZ
                                                    writeStructPtr1.posZ4 = v4.posZ

                                                    writeStructPtr1++
                                                }

                                                var writeStructPtr2 = PointAttrib(writeStructPtr1.ptr)
                                                tempPtr = tempBuffer.ptr

                                                for (i in 0 until quadCount * 4) {
                                                    val v = TerrainVertex(tempPtr)
                                                    tempPtr += 20L

                                                    writeStructPtr2.uvU = v.uvU
                                                    writeStructPtr2.uvV = v.uvV
                                                    writeStructPtr2.lightMapX = v.lightMapX
                                                    writeStructPtr2.lightMapY = v.lightMapY
                                                    writeStructPtr2.colorR = v.colorR
                                                    writeStructPtr2.colorG = v.colorG
                                                    writeStructPtr2.colorB = v.colorB

                                                    writeStructPtr2++
                                                }
                                                val quadIndex = globalQuadCount.getAndAdd(quadCount.toLong())
                                                val chunkIndex =
                                                    (info.chunkY - MIN_CHUNK_Y) + ((info.chunkX + ORIGIN) + (info.chunkZ + ORIGIN) * SIZE_XZ) * SIZE_Y

                                                chunkInfoListLocal += ChunkInfo(
                                                    chunkIndex,
                                                    Vector3i(info.chunkX, info.chunkY, info.chunkZ),
                                                    quadCount,
                                                    quadIndex
                                                )

                                                withContext(mainCtx) {
                                                    uploadBuffer.unmap()
                                                    uploadBuffer.copyTo(
                                                        quadPosDataBuffer,
                                                        0L,
                                                        quadIndex * 24L,
                                                        quadCount * 24L
                                                    )
                                                    uploadBuffer.copyTo(
                                                        quadAttribDataBuffer,
                                                        quadCount * 24L,
                                                        quadIndex * 48L,
                                                        quadCount * 48L
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    channel.send(chunkInfoListLocal)
                                } finally {
                                    tempBuffer.free()
                                    withContext(mainCtx) {
                                        uploadBuffer.destroy()
                                    }
                                }
                            }
                        }
                    )
                }
                channel.close()
            }

            val listFunc = mutableListOf<ChunkInfo>()
            launch(Dispatchers.Default) {
                for (chunkInfoList in channel) {
                    listFunc += chunkInfoList
                }
            }
            listFunc
        }
    }

    class ChunkInfoCSVEntry(
        val chunkX: Int,
        val chunkY: Int,
        val chunkZ: Int,
        val vertexOffset: Long,
        val vertexLength: Long,
        val indexOffset: Long,
        val indexLength: Long
    )

    class ChunkInfo(val chunkIndex: Int, val pos: Vector3i, val quadCount: Int, val startIndex: Long) {
        var listIndex = 0
        val minX = pos.x shl 4
        val minY = pos.y shl 4
        val minZ = pos.z shl 4
        val maxX = minX + 16
        val maxY = minY + 16
        val maxZ = minZ + 16
    }

    companion object {
        const val MIN_CHUNK_Y = -4
        const val SIZE_Y = 24
        const val SIZE_XZ = 512
        const val ORIGIN = 128
        const val TOTAL_CHUNKS = SIZE_XZ * SIZE_XZ * SIZE_Y

        const val BATCH_SIZE = 128
    }

    data class PosKey(val x: Long, val y: Long, val z: Long)

    data class AttribKey(val uv1: Long, val uv2: Long, val lightMapCoord: Long, val color1: Long, val color2: Long)
}