package dev.luna5ama.gltest.pc

import dev.fastmc.common.ParallelUtils
import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.structs.QuadBatchInfo
import dev.luna5ama.gltest.structs.sizeof
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.GL_DYNAMIC_STORAGE_BIT
import dev.luna5ama.glwrapper.api.glDispatchCompute
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.ensureCapacity
import dev.luna5ama.kmogus.memcpy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class SWQuadPipeline(scene: PointCloudScene, renderer: TerrainGeometryRenderer) : TerrainDrawPipeline(scene, renderer) {
    private val workGroupSize = 128
    private val quadsPerWorkGroup = workGroupSize * 16

    private val batchInfoBuffer = register(BufferObject.Immutable()).apply {
        label("SWQuad.BatchInfoBuffer")
    }

    val bufferBinding = ShaderBindingSpecs.Buffer("QuadBatchInfoBuffer", batchInfoBuffer, BufferTarget.ShaderStorage)

    val quadDrawShader = register(
        ShaderProgram(
            ShaderSource.Comp("pc/geometry/SWQuad.comp") {
                define("WORKGROUP_SIZE", workGroupSize)
                define("QUADS_PER_WORK_GROUP", quadsPerWorkGroup)
                define("uint_least8_t", renderer.visibilityFlagType.glslTypeName)
                if (scene.overdrawDebug) {
                    define("OVERDRAW_DEBUG")
                }
            }
        )
    ).apply {
        label("SWQuad.shader")
    }

    private val tempBuffer = register(BufferObject.Immutable()).apply {
        label("SWQuad.tempBuffer")
    }

    private val tempBufferBinding = ShaderBindingSpecs.Buffer("TempBuffer", tempBuffer, BufferTarget.ShaderStorage)

    override suspend fun update(chunks: List<TerrainMeshData.ChunkInfo>) {
        val structSize = sizeof(QuadBatchInfo)
        Arr.malloc(chunks.size * structSize).use { uploadBuffer ->
            val quadCountGlobal = AtomicInteger(0)
            val localBuffers = List(ParallelUtils.CPU_THREADS) { Arr.malloc(0L) }
            val localBatchCounts = IntArray(ParallelUtils.CPU_THREADS)
            var threadIndexCounter = 0

            coroutineScope {
                ParallelUtils.splitListIndex(
                    chunks.size,
                    blockForEach = { start, end ->
                        val threadIndex = threadIndexCounter++
                        launch(Dispatchers.Default) {
                            val localBuffer = localBuffers[threadIndex]
                            localBuffer.ensureCapacity((end - start) * structSize, false)
                            var localQuadCount = 0
                            var localBatchCount = 0

                            for (i in start until end) {
                                val chunkInfo = chunks[i]
                                var quadIdx = 0
                                while (quadIdx < chunkInfo.quadCount) {
                                    val batchQuadCount = min(chunkInfo.quadCount - quadIdx, quadsPerWorkGroup)

                                    localBuffer.ensureCapacity((localBatchCount + 1L) * structSize, false)
                                    val struct = QuadBatchInfo(localBuffer.ptr + localBatchCount * structSize)
                                    struct.first = chunkInfo.startIndex + quadIdx
                                    struct.count = batchQuadCount
                                    struct.offsetX = chunkInfo.offset.x.toByte()
                                    struct.offsetY = chunkInfo.offset.y.toByte()
                                    struct.offsetZ = chunkInfo.offset.z.toByte()

                                    quadIdx += batchQuadCount
                                    localBatchCount++
                                }
                                localQuadCount += quadIdx
                            }

                            localBatchCounts[threadIndex] = localBatchCount
                            quadCountGlobal.addAndGet(localQuadCount)
                        }
                    }
                )
            }

            batchCount = 0
            localBuffers.forEachIndexed { index, arr ->
                val localBatchCount = localBatchCounts[index]
                uploadBuffer.ensureCapacity((batchCount + localBatchCount) * structSize, false)
                memcpy(arr.ptr, 0L, uploadBuffer.ptr, batchCount * structSize, localBatchCount * structSize)
                batchCount += localBatchCount
            }

            localBuffers.forEach { it.free() }

            quadCount = quadCountGlobal.get()
            chunkCount = chunks.size

            if (batchCount == 0) return

            withContext(scene.gameInstance.mainContext) {
                val quadMetaLen = batchCount * sizeof(QuadBatchInfo)
                if (quadMetaLen > 0) {
                    if (batchInfoBuffer.size < quadMetaLen) {
                        batchInfoBuffer.destroy()
                        batchInfoBuffer.allocate(quadMetaLen, uploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
                    } else {
                        batchInfoBuffer.invalidate()
                        batchInfoBuffer.upload(0L, quadMetaLen, uploadBuffer.ptr)
                    }
                }
            }
        }
    }

//    override fun startUpdate(): UpdateContextImpl {
//        return UpdateContextImpl()
//    }
//
//    override fun endUpdate(context: UpdateContextImpl) {
//        batchCount = context.batchCount
//        chunkCount = context.chunkCount
//        quadCount = context.quadCount
//
//        if (context.batchCount == 0) return
//
//        val quadMetaLen = context.batchCount * sizeof(QuadBatchInfo)
//        if (quadMetaLen > 0) {
//            if (batchInfoBuffer.size < quadMetaLen) {
//                batchInfoBuffer.destroy()
//                batchInfoBuffer.allocate(quadMetaLen, context.uploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
//            } else {
//                batchInfoBuffer.invalidate()
//                batchInfoBuffer.upload(0L, quadMetaLen, context.uploadBuffer.ptr)
//            }
//        }
//    }

    private val printTimer = TickTimer()

    override fun draw() {
        if (batchCount == 0) return
        val tempDataLen = batchCount * 4L
        if (tempBuffer.size < tempDataLen) {
            tempBuffer.destroy()
            tempBuffer.allocate(tempDataLen, 0)
        }

//        tempBuffer.clear(GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
        scene.bindSWDrawShader(quadDrawShader)
        quadDrawShader.applyBinding {
            sampler(scene.textures.blockAlbedo)
            buffer(scene.camera.uboBinding)
            buffer(scene.terrainMeshData.bufferBindings)
            buffer(scene.vbuffer.bufferBinding)
            buffer(tempBufferBinding)
            buffer(bufferBinding)
            image(scene.vbuffer.imageBindings)
            image("uimg_overdrawCounter", scene.overdrawCounterTex)
        }
        glDispatchCompute(batchCount, 1, 1)

//        if (printTimer.tickAndReset(3000L)) {
//            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
//            val array = Arr.malloc(tempDataLen).use { arr ->
//                val ptr = arr.ptr
//                glGetNamedBufferSubData(tempBuffer.id, 0, arr.len, ptr)
//                val array = IntArray(batchCount)
//                memcpy(ptr, 0L, array, 0L, tempDataLen)
//                array
//            }
//            array.sort()
//            println(array.min())
//            println(array.max())
//            println(array.average())
//            println(array[batchCount / 2])
//        }
    }

//    inner class UpdateContextImpl : UpdateContext() {
//        val uploadBuffer = Arr.malloc(0L)
//
//        override fun add(chunkInfo: TerrainMeshData.ChunkInfo) {
//            var quadIdx = 0
//            while (quadIdx < chunkInfo.quadCount) {
//                val batchQuadCount = min(chunkInfo.quadCount - quadIdx, quadsPerWorkGroup)
//
//                uploadBuffer.ensureCapacity((batchCount + 1L) * sizeof(QuadBatchInfo), false)
//                val struct = QuadBatchInfo(uploadBuffer.ptr + batchCount * sizeof(QuadBatchInfo))
//                struct.first = chunkInfo.startIndex + quadIdx
//                struct.count = batchQuadCount
//                struct.offsetX = chunkInfo.offset.x.toByte()
//                struct.offsetY = chunkInfo.offset.y.toByte()
//                struct.offsetZ = chunkInfo.offset.z.toByte()
//
//                quadIdx += batchQuadCount
//                batchCount++
//            }
//            chunkCount++
//            quadCount += quadIdx
//        }
//
//        override fun close() {
//            uploadBuffer.free()
//        }
//    }
}