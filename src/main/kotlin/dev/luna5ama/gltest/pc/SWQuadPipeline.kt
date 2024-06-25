package dev.luna5ama.gltest.pc

import dev.fastmc.common.ParallelUtils
import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.structs.CullTestCommand
import dev.luna5ama.gltest.structs.QuadBatchInfo
import dev.luna5ama.gltest.structs.sizeof
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.ensureCapacity
import dev.luna5ama.kmogus.memcpy
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

    val drawShaders = List(4) { register(DrawShader(it)) }

    private val ctcBuffer = register(BufferObject.Immutable()).apply {
        label("SWQuad.CullTestCommandBuffer")
    }

    override suspend fun update(chunks: List<TerrainMeshData.ChunkLayerInfo>) {
        val batchStructSize = sizeof(QuadBatchInfo)
        val ctcBufferBaseOffset = renderer.cullPipeline.ctcBufferBaseOffset
        val ctcStructSize = sizeof(CullTestCommand)
        Arr.malloc(chunks.size * batchStructSize).use { uploadBuffer ->
            Arr.malloc(ctcBufferBaseOffset + chunks.size * ctcStructSize).use { ctcUploadBuffer ->
                val quadCountGlobal = AtomicInteger(0)
                val localBuffers = List(ParallelUtils.CPU_THREADS) { Arr.malloc(0L) }
                val localBatchCounts = IntArray(ParallelUtils.CPU_THREADS)
                var threadIndexCounter = 0
                val ctcCount = AtomicInteger(0)

                coroutineScope {
                    launch {
                        for (i in chunks.indices) {
                            val chunkInfo = chunks[i]
                            val offset = ctcCount.getAndIncrement() * ctcStructSize
                            val ctcStruct = CullTestCommand(ctcUploadBuffer.ptr + ctcBufferBaseOffset + offset)
                            ctcStruct.chunkIndex = chunkInfo.chunkIndex
                        }
                    }
                    ParallelUtils.splitListIndex(
                        chunks.size,
                        blockForEach = { start, end ->
                            val threadIndex = threadIndexCounter++
                            launch {
                                val localBuffer = localBuffers[threadIndex]
                                localBuffer.ensureCapacity((end - start) * batchStructSize, false)
                                var localQuadCount = 0
                                var localBatchCount = 0

                                for (i in start until end) {
                                    val chunkInfo = chunks[i]
                                    var quadIdx = 0
                                    while (quadIdx < chunkInfo.quadCount) {
                                        val batchQuadCount = min(chunkInfo.quadCount - quadIdx, quadsPerWorkGroup)

                                        localBuffer.ensureCapacity((localBatchCount + 1L) * batchStructSize, false)
                                        val struct = QuadBatchInfo(localBuffer.ptr + localBatchCount * batchStructSize)
                                        struct.chunkIndex = chunkInfo.chunkIndex
                                        struct.first = chunkInfo.startIndex + quadIdx
                                        struct.count = batchQuadCount

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
                    uploadBuffer.ensureCapacity((batchCount + localBatchCount) * batchStructSize, false)
                    memcpy(
                        arr.ptr,
                        0L,
                        uploadBuffer.ptr,
                        batchCount * batchStructSize,
                        localBatchCount * batchStructSize
                    )
                    batchCount += localBatchCount
                }

                localBuffers.forEach { it.free() }

                quadCount = quadCountGlobal.get()
                chunkCount = chunks.size

                if (batchCount == 0) return

                renderer.cullPipeline.setupIndirect(ctcUploadBuffer.ptr, ctcCount.get())

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

                        val ctcLen = ctcBufferBaseOffset + ctcCount.get() * ctcStructSize
                        if (ctcBuffer.size < ctcLen) {
                            ctcBuffer.destroy()
                            ctcBuffer.allocate(ctcLen, ctcUploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
                        } else {
                            ctcBuffer.invalidate()
                            ctcBuffer.upload(0L, ctcLen, ctcUploadBuffer.ptr)
                        }
                    }
                }
            }
        }
    }

    override fun draw() {
        if (batchCount == 0) return

        if (!scene.gameInstance.toggle3) {
            drawPass(1)

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

            glDebugGroup("CullPass-DepthBlit") {
                renderer.blitSW2HWDepth()
            }
            renderer.cullPipeline.draw(ctcBuffer)

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

            drawPass(2)
        } else {
            drawPass(3)
        }
    }

    private fun drawPass(index: Int) {
        glDebugGroup("DrawPass-$index") {
            glEnable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_GEQUAL)
            glDepthMask(true)
            glColorMask(true, true, true, true)
            val quadDrawShader = drawShaders[index]
            scene.bindSWDrawShader(quadDrawShader)
            quadDrawShader.applyBinding {
                sampler(scene.textures.blockAlbedo)
                buffer(scene.camera.uboBinding)
                buffer(scene.terrainMeshData.bufferBindings)
                buffer(scene.vbuffer.bufferBinding)
                buffer(bufferBinding)
                buffer(renderer.bufferBindings)
                image(scene.vbuffer.imageBindings)
                image("uimg_overdrawCounter", scene.overdrawCounterTex)
            }
            glDispatchCompute(batchCount, 1, 1)
        }
    }

    inner class DrawShader(val index: Int) : ShaderProgram(
        ShaderSource.Comp("pc/geometry/SWQuad.comp") {
            define("WORKGROUP_SIZE", workGroupSize)
            define("QUADS_PER_WORK_GROUP", quadsPerWorkGroup)
            define("uint_least8_t", renderer.visibilityFlagType.glslTypeName)
            define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
            define("CULL_PASS", index)
            if (scene.overdrawDebug) {
                define("OVERDRAW_DEBUG")
            }
        }
    ) {
        init {
            label("SWQuad.DrawShader-$index")
        }
    }
}