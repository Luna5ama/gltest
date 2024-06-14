package dev.luna5ama.gltest.pc

import dev.fastmc.common.*
import dev.fastmc.common.sort.IntIntrosort
import dev.luna5ama.gltest.AbstractScene
import dev.luna5ama.gltest.structs.*
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.asByteBuffer
import dev.luna5ama.kmogus.ensureCapacity
import dev.luna5ama.kmogus.memcpy
import kotlinx.coroutines.*
import org.apache.commons.lang3.mutable.MutableBoolean
import org.joml.*
import java.io.File
import java.io.FileNotFoundException
import java.nio.channels.FileChannel
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.sqrt

class PointCloudData(private val scene: AbstractScene, dir: File) : IGLObjContainer by IGLObjContainer.Impl() {
    private val pointBatchInfoBuffer = register(BufferObject.Immutable())
    private val quadBatchInfoBuffer = register(BufferObject.Immutable())

    private val quadPosDataBuffer = register(BufferObject.Immutable())
    private val quadTexCoordDataBuffer = register(BufferObject.Immutable())
    private val quadExtraAttribDataBuffer = register(BufferObject.Immutable())
    private val quadPosIndexBuffer = register(BufferObject.Immutable())
    private val quadAttribIndexBuffer = register(BufferObject.Immutable())
    val chunkinfos = mutableListOf<ChunkInfo>()

    val BATCH_SIZE = if (GLWrapper.instance.vendor == GpuVendor.NVIDIA) 96 else 128

    val bufferBindings = listOf(
        ShaderBindingSpecs.Buffer("PointBatchInfoBuffer", pointBatchInfoBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("QuadBatchInfoBuffer", quadBatchInfoBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("QuadPosDataBuffer", quadPosDataBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("QuadTexCoordDataBuffer", quadTexCoordDataBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("QuadExtraAttribDataBuffer", quadExtraAttribDataBuffer, BufferTarget.ShaderStorage),
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

    var totalQuadCount = 0
        private set
    var hwBatchCount = 0
        private set
    var quadBatchCount = 0
        private set
    var pointBatchCount = 0
        private set

    init {
        runBlocking {
            try {
                readCData(dir)
            } catch (e: FileNotFoundException) {
                readData(dir)
            } catch (e: NoSuchFileException) {
                readData(dir)
            }
        }
        chunkIndices = IntArray(chunkinfos.size) { it }
        val attribDataSize = quadTexCoordDataBuffer.size + quadExtraAttribDataBuffer.size
        val bufferUsage = quadPosDataBuffer.size + attribDataSize + quadPosIndexBuffer.size + quadAttribIndexBuffer.size
        println("Using batch size: $BATCH_SIZE")
        println(
            "Quad pos data: %.2f MB"
                .format(quadPosDataBuffer.size / 1024.0 / 1024.0)
        )
        println(
            "Quad pos index: %.2f MB"
                .format(quadPosIndexBuffer.size / 1024.0 / 1024.0)
        )
        println(
            "Quad attrib data: %.2f MB"
                .format(attribDataSize / 1024.0 / 1024.0)
        )
        println(
            "Quad attrib index: %.2f MB"
                .format(quadAttribIndexBuffer.size / 1024.0 / 1024.0)
        )
        println(
            "Total buffer usage: %.2f MB"
                .format(bufferUsage / 1024.0 / 1024.0)
        )
        println("Quad count: $totalQuadCount")
    }

    private suspend fun readData(dir: File) {
        val layers = dir.listFiles()!!.count { it.name.startsWith("terrain_vbo_") }
        val pointCount = (0 until layers).sumOf { File(dir, "terrain_vbo_$it.bin").length() } / 4L / 20L

        val quadCounter = AtomicInteger(0)
        val channel = ConcurrentLinkedQueue<List<ChunkInfo>>()

        val quadPosDataCounter = AtomicInteger()
        val quadAttribDataCounter = AtomicInteger()

        val quadPosDataIdxMap = ConcurrentHashMap<PosKey, Int>()
        val quadAttribDataIdxMap = ConcurrentHashMap<AttribKey, Int>()

        val quadPosDataUploadBuffer = Arr.malloc(pointCount * 24L)
        val quadTexCoordUploadBuffer = Arr.malloc(pointCount * 16L)
        val quadExtraAttribUploadBuffer = Arr.malloc(pointCount * 24L)

        fun addPosData(v1: TerrainVertex, v2: TerrainVertex, v3: TerrainVertex, v4: TerrainVertex): Int {
            val key = PosKey(
                (v1.posX.toLong() and 0xFFFF) or ((v2.posY.toLong() and 0xFFFF) shl 16) or ((v3.posZ.toLong() and 0xFFFF) shl 32) or ((v4.posX.toLong() and 0xFFFF) shl 48),
                (v1.posY.toLong() and 0xFFFF) or ((v2.posZ.toLong() and 0xFFFF) shl 16) or ((v3.posX.toLong() and 0xFFFF) shl 32) or ((v4.posY.toLong() and 0xFFFF) shl 48),
                (v1.posZ.toLong() and 0xFFFF) or ((v2.posX.toLong() and 0xFFFF) shl 16) or ((v3.posY.toLong() and 0xFFFF) shl 32) or ((v4.posZ.toLong() and 0xFFFF) shl 48)
            )
            return quadPosDataIdxMap.computeIfAbsent(key) {
                val idx = quadPosDataCounter.getAndIncrement()
                val offset = idx * 24L
                val pPosStruct = QuadPos(quadPosDataUploadBuffer.ptr + offset)
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
            val materialID = v1.attrib.toInt() and 0xFF
            val key = AttribKey(
                materialID,
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
                val extraAttribStruct = QuadExtraAttrib(quadExtraAttribUploadBuffer.ptr + idx * 24L)
                val texCoordStruct = QuadTexCoord(quadTexCoordUploadBuffer.ptr + idx * 16L)
                texCoordStruct.x1 = v1.uvU
                texCoordStruct.x2 = v2.uvU
                texCoordStruct.x3 = v3.uvU
                texCoordStruct.x4 = v4.uvU

                texCoordStruct.y1 = v1.uvV
                texCoordStruct.y2 = v2.uvV
                texCoordStruct.y3 = v3.uvV
                texCoordStruct.y4 = v4.uvV

                extraAttribStruct.lightCoordX1 = v1.lightMapX
                extraAttribStruct.lightCoordX2 = v2.lightMapX
                extraAttribStruct.lightCoordX3 = v3.lightMapX
                extraAttribStruct.lightCoordX4 = v4.lightMapX

                extraAttribStruct.lightCoordY1 = v1.lightMapY
                extraAttribStruct.lightCoordY2 = v2.lightMapY
                extraAttribStruct.lightCoordY3 = v3.lightMapY
                extraAttribStruct.lightCoordY4 = v4.lightMapY

                extraAttribStruct.r1 = v1.colorR
                extraAttribStruct.r2 = v2.colorR
                extraAttribStruct.r3 = v3.colorR
                extraAttribStruct.r4 = v4.colorR

                extraAttribStruct.g1 = v1.colorG
                extraAttribStruct.g2 = v2.colorG
                extraAttribStruct.g3 = v3.colorG
                extraAttribStruct.g4 = v4.colorG

                extraAttribStruct.b1 = v1.colorB
                extraAttribStruct.b2 = v2.colorB
                extraAttribStruct.b3 = v3.colorB
                extraAttribStruct.b4 = v4.colorB

                extraAttribStruct.materialID = materialID

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

                                                val quadIndex = quadCounter.getAndAdd(quadCount)
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

                this@PointCloudData.totalQuadCount = quadCounter.get()

                quadPosDataBuffer.allocate(quadPosDataCounter.get() * 24L, quadPosDataUploadBuffer.ptr, 0)
                quadTexCoordDataBuffer.allocate(quadAttribDataCounter.get() * 16L, quadTexCoordUploadBuffer.ptr, 0)
                quadExtraAttribDataBuffer.allocate(
                    quadAttribDataCounter.get() * 24L,
                    quadExtraAttribUploadBuffer.ptr,
                    0
                )

                quadPosIndexBuffer.allocate(quadCounter.get() * 4L, quadPosIndexUploadBuffer.ptr, 0)
                quadAttribIndexBuffer.allocate(quadCounter.get() * 4L, quadAttribIndexUploadBuffer.ptr, 0)
            }

            fun CoroutineScope.writeFile(fileName: String, arr: Arr, len: Long) {
                launch(Dispatchers.IO) {
                    FileChannel.open(
                        File(dir, fileName).toPath(),
                        StandardOpenOption.WRITE, StandardOpenOption.CREATE
                    ).use { channel ->
                        channel.write(arr.ptr.asByteBuffer(len.toInt()))
                    }
                }
            }

            writeFile("pc_quad_pos_data.bin", quadPosDataUploadBuffer, quadPosDataCounter.get() * 24L)
            writeFile("pc_quad_texcoord_data.bin", quadTexCoordUploadBuffer, quadAttribDataCounter.get() * 16L)
            writeFile("pc_quad_extra_attrib_data.bin", quadExtraAttribUploadBuffer, quadAttribDataCounter.get() * 24L)

            writeFile("pc_quad_pos_index.bin", quadPosIndexUploadBuffer, quadCounter.get() * 4L)
            writeFile("pc_quad_attrib_index.bin", quadAttribIndexUploadBuffer, quadCounter.get() * 4L)
        }
    }

    private suspend fun readCData(dir: File) {
        coroutineScope {
            val infoFile = File(dir, "pc_info.csv")
            val chunkInfoList = infoFile.useLines { sequence ->
                sequence.drop(1).map {
                    val parts = it.split(",")
                    ChunkInfo(
                        parts[1].toInt(),
                        Vector3i(parts[2].toInt(), parts[3].toInt(), parts[4].toInt()),
                        parts[5].toInt(),
                        parts[6].toInt()
                    ).apply {
                        listIndex = parts[0].toInt()
                    }
                }.toList()
            }
            chunkinfos.addAll(chunkInfoList)
            totalQuadCount = chunkinfos.sumOf { it.quadCount }

            val mainCtx = this.coroutineContext

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
                readFileToBuffer(File(dir, "pc_quad_texcoord_data.bin"), quadTexCoordDataBuffer)
            }
            launch {
                readFileToBuffer(File(dir, "pc_quad_extra_attrib_data.bin"), quadExtraAttribDataBuffer)
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
    private val quadBatchUploadBuffer = Arr.malloc(chunkinfos.size * sizeof(QuadBatchInfo))
    private val pointBatchUploadBuffer = Arr.malloc(chunkinfos.size * sizeof(PointBatchInfo))
    private val frustumCullingFlags = Array(chunkinfos.size) {
        MutableBoolean(true)
    }

    private val sampleCountPow2Array = IntArray(chunkinfos.size)
    val quadDrawHWPipeline = register(QuadDrawHWPipeline(scene, this))

    var drawPointCount = 0L; private set
    var drawPointTotalCount = 0L; private set

    var drawQuadCount = 0L; private set

    suspend fun updateBatchMeta() {
        val print = false
//        val print = printTimer.tickAndReset(3000)
        if (print) {
            println()
        }

        coroutineScope {
            launch(Dispatchers.Default) {
                sortChunks(print)
            }
            launch(Dispatchers.Default) {
                calcSampleCount()
            }
            launch(Dispatchers.Default) {
                doFrustumCulling()
            }
        }

        if (print) {
            println("Min sample count: ${sampleCountPow2Array.min()}")
            println("Average sample count: ${sampleCountPow2Array.average()}")
            println("Max sample count: ${sampleCountPow2Array.max()}")
        }

        var pointMetaIdx = 0
        var quadMetaIdx = 0
        var hwIndirectCmdIdx = 0

        drawPointCount = 0L
        drawPointTotalCount = 0L

        drawQuadCount = 0L

        val indirectCommandUploadBuffer = quadDrawHWPipeline.indirectCommandUploadBuffer
        val chunkStartIndexUploadBuffer = quadDrawHWPipeline.chunkStartIndexUploadBuffer

        for (i in chunkIndices) {
            val chunkInfo = chunkinfos[i]
            if (!frustumCullingFlags[chunkInfo.listIndex].value) continue
            val offset = chunkOffsets[chunkInfo.chunkIndex]
            val sampleCountPow2 = sampleCountPow2Array[chunkInfo.listIndex]

            if (false) {
                indirectCommandUploadBuffer.ensureCapacity((hwIndirectCmdIdx + 1L) * sizeof(DrawArraysIndirectCommand), false)
                val struct = DrawArraysIndirectCommand(indirectCommandUploadBuffer.ptr + hwIndirectCmdIdx * sizeof(DrawArraysIndirectCommand))
                struct.count = chunkInfo.quadCount * 6
                struct.instanceCount = 1
                struct.first = 0
                struct.baseInstance = chunkInfo.chunkIndex

                chunkStartIndexUploadBuffer.ensureCapacity((hwIndirectCmdIdx + 1L) * 4L, false)
                chunkStartIndexUploadBuffer.ptr.setInt(hwIndirectCmdIdx * 4L, chunkInfo.startIndex)

                hwIndirectCmdIdx++
            } else if (true) {
                var quadIdx = 0
                while (quadIdx < chunkInfo.quadCount) {
                    quadBatchUploadBuffer.ensureCapacity((quadMetaIdx + 1L) * sizeof(QuadBatchInfo), false)
                    val struct = QuadBatchInfo(quadBatchUploadBuffer.ptr + quadMetaIdx * sizeof(QuadBatchInfo))
                    val batchCount = min(chunkInfo.quadCount - quadIdx, BATCH_SIZE)
                    struct.first = chunkInfo.startIndex + quadIdx
                    struct.count = batchCount.toByte()
                    struct.offsetX = offset.x.toByte()
                    struct.offsetY = offset.y.toByte()
                    struct.offsetZ = offset.z.toByte()
                    quadIdx += batchCount
                    quadMetaIdx++
                }
                drawQuadCount += quadIdx
            } else {
                var quadIdx = 0
                val sampleCount = 1 shl abs(sampleCountPow2)
                val count = if (sampleCountPow2 > 0) BATCH_SIZE else BATCH_SIZE * sampleCount

                while (quadIdx < chunkInfo.quadCount) {
                    pointBatchUploadBuffer.ensureCapacity((pointMetaIdx + 1L) * sizeof(PointBatchInfo), false)
                    val struct = PointBatchInfo(pointBatchUploadBuffer.ptr + pointMetaIdx * sizeof(PointBatchInfo))
                    val batchCount = min(chunkInfo.quadCount - quadIdx, count)
                    struct.first = chunkInfo.startIndex + quadIdx
                    struct.count = batchCount
                    struct.offsetX = offset.x.toByte()
                    struct.offsetY = offset.y.toByte()
                    struct.offsetZ = offset.z.toByte()
                    struct.pointSampleCount = sampleCountPow2
                    quadIdx += batchCount
                    pointMetaIdx++
                }
                drawPointCount += quadIdx
                drawPointTotalCount += if (sampleCountPow2 > 0) quadIdx * sampleCount else quadIdx / sampleCount
            }
        }

        val hwCommandLen = hwIndirectCmdIdx * sizeof(DrawArraysIndirectCommand)
        if (hwCommandLen > 0) {
            if (quadDrawHWPipeline.indirectCommands.size < hwCommandLen) {
                quadDrawHWPipeline.indirectCommands.destroy()
                quadDrawHWPipeline.indirectCommands.allocate(hwCommandLen, indirectCommandUploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
            } else {
                quadDrawHWPipeline.indirectCommands.invalidate()
                quadDrawHWPipeline.indirectCommands.upload(0L, hwCommandLen, indirectCommandUploadBuffer.ptr)
            }

            val chunkStartIndexLen = hwIndirectCmdIdx * 4L
            if (quadDrawHWPipeline.chunkStartIndex.size < chunkStartIndexLen) {
                quadDrawHWPipeline.chunkStartIndex.destroy()
                quadDrawHWPipeline.chunkStartIndex.allocate(chunkStartIndexLen, chunkStartIndexUploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
            } else {
                quadDrawHWPipeline.chunkStartIndex.invalidate()
                quadDrawHWPipeline.chunkStartIndex.upload(0L, chunkStartIndexLen, chunkStartIndexUploadBuffer.ptr)
            }
        }

        val pointMetaLen = pointMetaIdx * sizeof(PointBatchInfo)
        if (pointMetaLen > 0) {
            if (pointBatchInfoBuffer.size < pointMetaLen) {
                pointBatchInfoBuffer.destroy()
                pointBatchInfoBuffer.allocate(pointMetaLen, pointBatchUploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
            } else {
                pointBatchInfoBuffer.invalidate()
                pointBatchInfoBuffer.upload(0L, pointMetaLen, pointBatchUploadBuffer.ptr)
            }
        }

        val quadMetaLen = quadMetaIdx * sizeof(QuadBatchInfo)
        if (quadMetaLen > 0) {
            if (quadBatchInfoBuffer.size < quadMetaLen) {
                quadBatchInfoBuffer.destroy()
                quadBatchInfoBuffer.allocate(quadMetaLen, quadBatchUploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
            } else {
                quadBatchInfoBuffer.invalidate()
                quadBatchInfoBuffer.upload(0L, quadMetaLen, quadBatchUploadBuffer.ptr)
            }
        }

        hwBatchCount = hwIndirectCmdIdx
        pointBatchCount = pointMetaIdx
        quadBatchCount = quadMetaIdx

        if (print) {
            println("Quad Batch count: $quadBatchCount/${chunkinfos.size}")
            println("Quad count: $drawQuadCount")
            println("Point Batch count: $pointBatchCount/${chunkinfos.size}")
            println("Point count: $drawPointCount($drawPointTotalCount)")
            println()
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
                        if (area + 0.25f < 1.0f) {
                            val v = min(log2(1.0f / (area + 0.33f)).floorToInt(), 8)
                            sampleCountPow2Array[chunkInfo.listIndex] = -v
                        } else {
                            sampleCountPow2Array[chunkInfo.listIndex] =
                                MathUtil.clamp((log2(area + 0.25f) + 0.25f).ceilToInt(), 0, 5)
                        }
//                            sampleCountPow2Array[chunkInfo.listIndex] = MathUtil.clamp((log2(area) + 0.25f).ceilToInt(), 1, 5)
//                        sampleCountPow2Array[chunkInfo.listIndex] = 5
                    }
                }
            }
        )
    }

    private val distanceSqs = IntArray(chunkIndices.size)
    private suspend fun sortChunks(print: Boolean) {
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

        if (print) {
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

    class ChunkInfoCSVEntry(
        val chunkX: Int,
        val chunkY: Int,
        val chunkZ: Int,
        val vertexOffset: Long,
        val vertexLength: Long,
        val indexOffset: Long,
        val indexLength: Long
    )

    class ChunkInfo(val chunkIndex: Int, val pos: Vector3i, val quadCount: Int, val startIndex: Int) {
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
    }

    data class PosKey(val x: Long, val y: Long, val z: Long)

    data class AttribKey(val materialID: Int, val uv1: Long, val uv2: Long, val lightMapCoord: Long, val color1: Long, val color2: Long)
}