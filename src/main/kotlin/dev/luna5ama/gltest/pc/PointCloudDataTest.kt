package dev.luna5ama.gltest.pc

import dev.fastmc.common.MathUtil
import dev.fastmc.common.ParallelUtils
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
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.asByteBuffer
import dev.luna5ama.kmogus.ensureCapacity
import dev.luna5ama.kmogus.memcpy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.apache.commons.lang3.mutable.MutableBoolean
import org.joml.*
import java.io.File
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
import kotlin.system.exitProcess

class PointCloudDataTest(private val scene: AbstractScene, dir: File) : IGLObjContainer by IGLObjContainer.Impl() {
    val chunkinfos = mutableListOf<ChunkInfo>()

    var chunkIndices: IntArray

    var pointCount = 0L
        private set

    init {
        runBlocking {
            readData(dir)
            chunkIndices = IntArray(chunkinfos.size) { it }
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

        val quadPosDataBuffer = Arr.malloc(pointCount * 24L)
        val quadAttribBuffer = Arr.malloc(pointCount * 48L)

        fun addPosData(v1: TerrainVertex, v2: TerrainVertex, v3: TerrainVertex, v4: TerrainVertex): Int {
            val key = PosKey(
                (v1.posX.toLong() and 0xFFFF) or ((v2.posY.toLong() and 0xFFFF) shl 16) or ((v3.posZ.toLong() and 0xFFFF) shl 32) or ((v4.posX.toLong() and 0xFFFF) shl 48),
                (v1.posY.toLong() and 0xFFFF) or ((v2.posZ.toLong() and 0xFFFF) shl 16) or ((v3.posX.toLong() and 0xFFFF) shl 32) or ((v4.posY.toLong() and 0xFFFF) shl 48),
                (v1.posZ.toLong() and 0xFFFF) or ((v2.posX.toLong() and 0xFFFF) shl 16) or ((v3.posY.toLong() and 0xFFFF) shl 32) or ((v4.posZ.toLong() and 0xFFFF) shl 48)
            )
            return quadPosDataIdxMap.computeIfAbsent(key) {
                val idx = quadPosDataCounter.getAndIncrement()
                val offset = idx * 24L
                val pPosStruct = PointPos(quadPosDataBuffer.ptr + offset)
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
                var pAttribStruct = PointAttrib(quadAttribBuffer.ptr + offset)
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

        val quadPosIdxBuffer = Arr.malloc(pointCount * 4L)
        val quadAttribIdxBuffer = Arr.malloc(pointCount * 4L)

        coroutineScope {
            val context = this.coroutineContext
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
                                                val chunkIndex = (info.chunkY - MIN_CHUNK_Y) + ((info.chunkX + ORIGIN) + (info.chunkZ + ORIGIN) * SIZE_XZ) * SIZE_Y

                                                memcpy(
                                                    localPosIdxBufferArray,
                                                    0L,
                                                    quadPosIdxBuffer.ptr,
                                                    quadIndex * 4L,
                                                    quadCount * 4L
                                                )

                                                memcpy(
                                                    localAttribIdxBufferArray,
                                                    0L,
                                                    quadAttribIdxBuffer.ptr,
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

        for (chunkInfoList in channel) {
            chunkinfos += chunkInfoList
        }
        chunkinfos.forEachIndexed { index, chunkInfo ->
            chunkInfo.listIndex = index
        }
        println(quadPosDataCounter.get())
        println(quadAttribDataCounter.get())
        println(quadCounter.get())
        this@PointCloudDataTest.pointCount = quadCounter.get()
        exitProcess(0)
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