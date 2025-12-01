package dev.luna5ama.gltest.pc

import dev.fastmc.common.Direction
import dev.fastmc.common.ParallelUtils
import dev.luna5ama.gltest.structs.*
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.base.GL_MAP_INVALIDATE_BUFFER_BIT
import dev.luna5ama.glwrapper.base.GL_MAP_WRITE_BIT
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.asByteBuffer
import dev.luna5ama.kmogus.memcpy
import it.unimi.dsi.fastutil.ints.Int2ObjectFunction
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import kotlinx.coroutines.*
import org.joml.Vector3i
import java.io.File
import java.io.FileNotFoundException
import java.nio.channels.FileChannel
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.system.measureNanoTime

class TerrainMeshData(val scene: PointCloudScene, dir: File) : IGLObjContainer by IGLObjContainer.Impl() {
    private val quadPosDataBuffer = register(BufferObject.Immutable())
    private val quadTexCoordDataBuffer = register(BufferObject.Immutable())
    private val quadExtraAttribDataBuffer = register(BufferObject.Immutable())
    private val quadPosIndexBuffer = register(BufferObject.Immutable())
    private val quadAttribIndexBuffer = register(BufferObject.Immutable())
    val chunk2LayerMap: Int2ObjectOpenHashMap<ObjectArrayList<ChunkLayerInfo>>
    val chunkLayerInfo = mutableListOf<ChunkLayerInfo>()
    val chunkPos = mutableListOf<Vector3i>()

    val bufferBindings = listOf(
        ShaderBindingSpecs.Buffer("QuadPosDataBuffer", quadPosDataBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("QuadTexCoordDataBuffer", quadTexCoordDataBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("QuadExtraAttribDataBuffer", quadExtraAttribDataBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("QuadPosIndexBuffer", quadPosIndexBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("QuadAttribIndexBuffer", quadAttribIndexBuffer, BufferTarget.ShaderStorage)
    )

    var totalQuadCount = 0
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
        val attribDataSize = quadTexCoordDataBuffer.size + quadExtraAttribDataBuffer.size
        val bufferUsage = quadPosDataBuffer.size + attribDataSize + quadPosIndexBuffer.size + quadAttribIndexBuffer.size
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
        println("Quad count: %,d".format(totalQuadCount))

        val chunk2LayerMap = Int2ObjectOpenHashMap<ObjectArrayList<ChunkLayerInfo>>()

        chunkLayerInfo.forEach {
            check(chunkPos[it.chunkIndex] == it.pos) { "chunk pos mismatch" }
            chunk2LayerMap.computeIfAbsent(
                it.chunkIndex,
                Int2ObjectFunction { ObjectArrayList.wrap(arrayOfNulls<ChunkLayerInfo>(0)) }).add(it)
        }

        chunk2LayerMap.values.forEach { layers ->
            layers.sortBy { it.startIndex }
        }

        this.chunk2LayerMap = chunk2LayerMap
    }
    private suspend fun readData(dir: File) {
        val time = measureNanoTime {
            readData0(dir)
        }
        println("PC data built in %.2f ms".format(time / 1e6))
    }

    private suspend fun readData0(dir: File) {
        val layers = dir.listFiles()!!.count { it.name.startsWith("terrain_vbo_") }
        val pointCount = (0 until layers).sumOf { File(dir, "terrain_vbo_$it.bin").length() } / 4L / 20L

        val quadCounter = AtomicInteger(0)
        val channel = ConcurrentLinkedQueue<List<ChunkLayerInfo>>()

        val quadPosDataCounter = AtomicInteger()
        val quadAttribDataCounter = AtomicInteger()

        val quadPosDataIdxMap = ConcurrentHashMap<PosKey, Int>()
        val quadAttribDataIdxMap = ConcurrentHashMap<AttribKey, Int>()

        val quadPosDataUploadBuffer = Arr.malloc(pointCount * sizeof(QuadPos))
        val quadPosDataUploadBufferStructArray = QuadPos(quadPosDataUploadBuffer)
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
                val pPosStruct = quadPosDataUploadBufferStructArray[idx]
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

        val chunkCounter = AtomicInteger(0)
        val chunkIndexMap = ConcurrentHashMap<Vector3i, Int>()

        fun getChunkIndex(chunkPos: Vector3i): Int {
            return chunkIndexMap.computeIfAbsent(chunkPos) {
                chunkCounter.getAndIncrement()
            }
        }

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
                            launch(Dispatchers.Default) {
                                val chunkLayerInfoListLocal = mutableListOf<ChunkLayerInfo>()
                                val quadCounts = IntArray(63)
                                val localPosIdxBufferArrays = List(63) { IntArrayList() }
                                val localAttribIdxBufferArrays = List(63) { IntArrayList() }

                                fun calcFaceBits(v: TerrainVertex): Int {
                                    var bit = 0b00_00_00
                                    val eps = 0.01f

                                    val normalX = v.normalX.toInt()
                                    val normalY = v.normalY.toInt()
                                    val normalZ = v.normalZ.toInt()

                                    if (normalX - eps > 0) {
                                        bit = bit or Direction.B_EAST
                                    } else if (normalX + eps < 0.0f) {
                                        bit = bit or Direction.B_WEST
                                    }

                                    if (normalY - eps > 0.0f) {
                                        bit = bit or Direction.B_UP
                                    } else if (normalY + eps < 0.0f) {
                                        bit = bit or Direction.B_DOWN
                                    }

                                    if (normalZ - eps > 0.0f) {
                                        bit = bit or Direction.B_SOUTH
                                    } else if (normalZ + eps < 0.0f) {
                                        bit = bit or Direction.B_NORTH
                                    }

                                    if (bit == 0b00_00_00) {
                                        bit = 0b11_11_11
                                    }

                                    return bit
                                }

                                for (infoIdx in start until end) {
                                    val info = infoLines[infoIdx]
                                    if (info.chunkY - MIN_CHUNK_Y < 6) continue
                                    var readOffset = info.vertexOffset
                                    val readEnd = info.vertexOffset + info.vertexLength
                                    withContext(Dispatchers.IO) {
                                        FileChannel.open(vboFile.toPath(), StandardOpenOption.READ).use { channel ->
                                            while (readOffset < readEnd) {
                                                val buf = readBuffer.ptr.asByteBuffer(
                                                    min(readBuffer.len, info.vertexLength).toInt()
                                                )
                                                val read = channel.read(buf, readOffset)
                                                if (read == -1) {
                                                    throw IllegalStateException("Unexpected EOF")
                                                }

                                                if (read == 0) break

                                                readOffset += read

                                                val readQuadCount = read / 4 / 20

                                                var tempPtr = readBuffer.ptr

                                                for (i in 0 until readQuadCount) {
                                                    val v1 = TerrainVertex(tempPtr)
                                                    tempPtr += sizeof(TerrainVertex)
                                                    val v2 = TerrainVertex(tempPtr)
                                                    tempPtr += sizeof(TerrainVertex)
                                                    val v3 = TerrainVertex(tempPtr)
                                                    tempPtr += sizeof(TerrainVertex)
                                                    val v4 = TerrainVertex(tempPtr)
                                                    tempPtr += sizeof(TerrainVertex)

                                                    val faceBits = calcFaceBits(v1)
                                                    val faceIndex = faceBits - 1
                                                    val posData = addPosData(v1, v2, v3, v4)
                                                    val attribData = addAttribData(v1, v2, v3, v4)

                                                    quadCounts[faceIndex]++
                                                    localPosIdxBufferArrays[faceIndex].add(posData)
                                                    localAttribIdxBufferArrays[faceIndex].add(attribData)
                                                }
                                            }
                                        }
                                    }

                                    val pos = Vector3i(info.chunkX, info.chunkY, info.chunkZ)
                                    var quadIndex = quadCounter.getAndAdd(quadCounts.sum())

                                    for (i in 0 until 63) {
                                        val faceQuadCount = quadCounts[i]
                                        if (faceQuadCount == 0) continue

                                        memcpy(
                                            localPosIdxBufferArrays[i].elements(),
                                            0L,
                                            quadPosIndexUploadBuffer.ptr,
                                            quadIndex * 4L,
                                            faceQuadCount * 4L
                                        )

                                        memcpy(
                                            localAttribIdxBufferArrays[i].elements(),
                                            0L,
                                            quadAttribIndexUploadBuffer.ptr,
                                            quadIndex * 4L,
                                            faceQuadCount * 4L
                                        )

                                        chunkLayerInfoListLocal += ChunkLayerInfo(
                                            getChunkIndex(pos),
                                            pos,
                                            faceQuadCount,
                                            quadIndex,
                                            i + 1
                                        )

                                        quadIndex += faceQuadCount
                                    }

                                    localPosIdxBufferArrays.forEach {
                                        it.clear()
                                    }
                                    localAttribIdxBufferArrays.forEach {
                                        it.clear()
                                    }
                                    quadCounts.fill(0)
                                }

                                channel.add(chunkLayerInfoListLocal)
                                readBuffer.free()
                            }
                        }
                    )
                }
            }
        }

        coroutineScope {
            launch {
                for (chunkInfoList in channel) {
                    chunkLayerInfo.addAll(chunkInfoList)
                }
                chunkLayerInfo.forEachIndexed { index, chunkInfo ->
                    chunkInfo.listIndex = index
                    while (chunkPos.size <= chunkInfo.chunkIndex) {
                        chunkPos.add(Vector3i())
                    }
                    chunkPos[chunkInfo.chunkIndex].set(chunkInfo.pos)
                }

                launch(Dispatchers.IO) {
                    File(dir, "pc_info.csv").bufferedWriter().use {
                        it.appendLine("listIndex,chunkIndex,chunkX,chunkY,chunkZ,quadCount,startIndex,faceBits")
                        chunkLayerInfo.forEach { chunkInfo ->
                            it.append(chunkInfo.listIndex.toString())
                            it.append(',')
                            it.append(chunkInfo.chunkIndex.toString())
                            it.append(',')
                            it.append(chunkInfo.pos.x.toString())
                            it.append(',')
                            it.append(chunkInfo.pos.y.toString())
                            it.append(',')
                            it.append(chunkInfo.pos.z.toString())
                            it.append(',')
                            it.append(chunkInfo.quadCount.toString())
                            it.append(',')
                            it.append(chunkInfo.startIndex.toString())
                            it.append(',')
                            it.append(chunkInfo.faceBits.toString())
                            it.append('\n')
                        }
                    }
                }

                this@TerrainMeshData.totalQuadCount = quadCounter.get()

                quadPosDataBuffer.allocate(quadPosDataCounter.get() * sizeof(QuadPos), quadPosDataUploadBuffer.ptr, 0)
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

            writeFile("pc_quad_pos_data.bin", quadPosDataUploadBuffer, quadPosDataCounter.get() * sizeof(QuadPos))
            writeFile("pc_quad_texcoord_data.bin", quadTexCoordUploadBuffer, quadAttribDataCounter.get() * 16L)
            writeFile("pc_quad_extra_attrib_data.bin", quadExtraAttribUploadBuffer, quadAttribDataCounter.get() * 24L)

            writeFile("pc_quad_pos_index.bin", quadPosIndexUploadBuffer, quadCounter.get() * 4L)
            writeFile("pc_quad_attrib_index.bin", quadAttribIndexUploadBuffer, quadCounter.get() * 4L)
        }
    }

    private suspend fun readCData(dir: File) {
        coroutineScope {
            val infoFile = File(dir, "pc_info.csv")
            infoFile.useLines { sequence ->
                sequence.drop(1).mapTo(chunkLayerInfo) {
                    val parts = it.split(",")
                    ChunkLayerInfo(
                        parts[1].toInt(),
                        Vector3i(parts[2].toInt(), parts[3].toInt(), parts[4].toInt()),
                        parts[5].toInt(),
                        parts[6].toInt(),
                        parts[7].toInt()
                    ).apply {
                        listIndex = parts[0].toInt()
                    }
                }.toList()
            }
            totalQuadCount = chunkLayerInfo.sumOf { it.quadCount }
            chunkLayerInfo.forEach {
                while (chunkPos.size <= it.chunkIndex) {
                    chunkPos.add(Vector3i())
                }
                chunkPos[it.chunkIndex].set(it.pos)
            }

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

    class ChunkInfoCSVEntry(
        val chunkX: Int,
        val chunkY: Int,
        val chunkZ: Int,
        val vertexOffset: Long,
        val vertexLength: Long,
        val indexOffset: Long,
        val indexLength: Long
    )

    class ChunkLayerInfo(
        @JvmField val chunkIndex: Int,
        @JvmField val pos: Vector3i,
        @JvmField val quadCount: Int,
        @JvmField val startIndex: Int,
        @JvmField val faceBits: Int
    ) {
        var listIndex = 0
        val minX = pos.x shl 4
        val minY = pos.y shl 4
        val minZ = pos.z shl 4
        val maxX = minX + 16
        val maxY = minY + 16
        val maxZ = minZ + 16
    }

    private fun Vector3i.encode(): Long {
        return ((x.toLong() + XZ_BITS_BIAS) and XZ_BITS_MASK) or
                ((y.toLong() + Y_BITS_BIAS) and Y_BITS_MASK shl Y_BIT_SHIFT) or
                ((z.toLong() + XZ_BITS_BIAS) shl Z_BIT_SHIFT)
    }

    companion object {
        const val MIN_CHUNK_Y = -4
        const val SIZE_Y = 24
        const val SIZE_XZ = 512
        const val XZ_BIAS = SIZE_XZ shr 1
        const val ORIGIN = 0
        const val TOTAL_CHUNKS = SIZE_XZ * SIZE_XZ * SIZE_Y

        private const val XZ_BITS = 24
        private const val Y_BITS = 16
        private const val X_BIT_SHIFT = 0
        private const val Y_BIT_SHIFT = X_BIT_SHIFT + XZ_BITS
        private const val Z_BIT_SHIFT = Y_BIT_SHIFT + Y_BITS
        private const val XZ_BITS_MASK = (1L shl XZ_BITS) - 1L
        private const val Y_BITS_MASK = (1L shl Y_BITS) - 1L
        private const val XZ_BITS_BIAS = (1L shl (XZ_BITS - 1))
        private const val Y_BITS_BIAS = (1L shl (Y_BITS - 1))
    }

    data class PosKey(val x: Long, val y: Long, val z: Long)

    data class AttribKey(
        val materialID: Int,
        val uv1: Long,
        val uv2: Long,
        val lightMapCoord: Long,
        val color1: Long,
        val color2: Long
    )
}