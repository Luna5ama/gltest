package dev.luna5ama.gltest.pc

import dev.fastmc.common.ParallelUtils
import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.VisibilityState
import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.structs.QuadBatchInfo
import dev.luna5ama.gltest.structs.sizeof
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class SWQuadPipeline(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
    TerrainDrawPipeline("SWQuad", scene, renderer) {
    private val workGroupSize = renderer.threadBatchSize
    private val quadsPerWorkGroup = workGroupSize * 4

    private val quadBatchInfo = register(BufferObject.Immutable()).apply {
        label("SWQuad.QuadBatchInfo")
    }

    private val batchIndices = register(BufferObject.Immutable()).apply {
        label("SWQuad.BatchIndices")
    }

    private val batchFilterFlag = register(BufferObject.Immutable()).apply {
        label("SWQuad.BatchFilterFlag")
    }

    private val scanBuffer = register(BufferObject.Immutable()).apply {
        label("SWQuad.ScanBuffer")
    }

    private val bufferBindings = listOf(
        ShaderBindingSpecs.Buffer("QuadBatchInfoBuffer", quadBatchInfo, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("BatchIndicesIndirect", batchIndices.createView(0L, 16L), BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("BatchIndicesData", batchIndices.createView(16L), BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("BatchFilterFlag", batchFilterFlag, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("ScanBuffer", scanBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("ScanBufferCount", scanBuffer.createView(0L, 16L), BufferTarget.ShaderStorage),
    )

    private val clearShader = register(ShaderProgram(ShaderSource.Comp("pc/geometry/swquad/Clear.comp"))).apply {
        label("SWQuad.ClearShader")
    }

    private val filterShader = object : ShaderProgram.Variants<VisibilityState>() {
        override fun create(key: VisibilityState): ShaderProgram {
            return register(ShaderProgram(
                ShaderSource.Comp("pc/geometry/swquad/BatchFilter.comp") {
                    define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
                    define(key.defineName)
                }
            )).apply {
                label("SWQuad.SWQuadBatchFilter-$key")
            }
        }
    }

    private val batchIndicesShader = register(ShaderProgram(
        ShaderSource.Comp("pc/geometry/swquad/BatchIndices.comp") {
            define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
            define("SHARED_SUM_SIZE", run {
                val subgroupSize = glGetInteger(GL_SUBGROUP_SIZE_KHR)
                val subgroupCount = scene.gpuScan.workGroupSize / subgroupSize
                max(subgroupCount, subgroupSize)
            })
        }
    )).apply {
        label("SWQuad.BatchIndicesShader")
    }

    val drawShader = register(ShaderProgram(
        ShaderSource.Comp("pc/geometry/swquad/Draw.comp") {
            define("WORKGROUP_SIZE", workGroupSize)
            define("QUADS_PER_WORK_GROUP", quadsPerWorkGroup)
            define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
            if (scene.overdrawDebug) {
                define("OVERDRAW_DEBUG")
            }
            if (renderer.debugQuadCount) {
                define("DEBUG_QUAD_COUNT")
            }
        }
    )).apply {
        label("SWQuad.DrawShader")
    }

    override suspend fun update(chunks: List<TerrainMeshData.ChunkLayerInfo>) {
        val batchStructSize = sizeof(QuadBatchInfo)
        val quadBatchInfoMetaSize = 16L
        Arr.malloc(quadBatchInfoMetaSize + chunks.size * batchStructSize).use { uploadBuffer ->
            coroutineScope {
                launch {
                    updateCtcBuffer(chunks)
                }

                val quadCountGlobal = AtomicInteger(0)
                val localBuffers = List(ParallelUtils.CPU_THREADS) { Arr.malloc(0L) }
                val localBatchCounts = IntArray(ParallelUtils.CPU_THREADS)
                var threadIndexCounter = 0

                coroutineScope {
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
                                        val struct =
                                            QuadBatchInfo(localBuffer.ptr + localBatchCount * batchStructSize)
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

                quadCount = quadCountGlobal.get()
                batchCount = localBatchCounts.sum()
                if (batchCount == 0) return@coroutineScope

                coroutineScope {
                    launch(scene.gameInstance.mainContext) {
                        val batchIndicesLen = 16L + batchCount * 4L
                        if (batchIndices.size < batchIndicesLen) {
                            batchIndices.destroy()
                            batchIndices.allocate(batchIndicesLen, 0)
                        }

                        val batchMaskBallotsLen = batchCount * renderer.visibilityFlagType.byteSize.toLong()
                        if (batchFilterFlag.size < batchMaskBallotsLen) {
                            batchFilterFlag.destroy()
                            batchFilterFlag.allocate(batchMaskBallotsLen, 0)
                        }

                        val scanWorkGroupCount = scene.gpuScan.workGroupSize
                        val scanInputSize = 16L + (batchCount + scanWorkGroupCount - 1) / scanWorkGroupCount * 4L
                        if (scanBuffer.size < scanInputSize) {
                            scanBuffer.destroy()
                            scanBuffer.allocate(scanInputSize, 0)
                        }
                    }

                    launch {
                        uploadBuffer.ensureCapacity(quadBatchInfoMetaSize + batchCount * batchStructSize, false)

                        var batchOffset = 0
                        localBuffers.forEachIndexed { index, arr ->
                            val localBatchCount = localBatchCounts[index]
                            memcpy(
                                arr.ptr,
                                0L,
                                uploadBuffer.ptr,
                                quadBatchInfoMetaSize + batchOffset * batchStructSize,
                                localBatchCount * batchStructSize
                            )
                            batchOffset += localBatchCount
                            arr.free()
                        }

                        uploadBuffer.ptr.setInt(0L, batchCount)

                        withContext(scene.gameInstance.mainContext) {
                            val quadMetaLen = quadBatchInfoMetaSize + batchCount * sizeof(QuadBatchInfo)
                            if (quadBatchInfo.size < quadMetaLen) {
                                quadBatchInfo.destroy()
                                quadBatchInfo.allocate(quadMetaLen, uploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
                            } else {
                                quadBatchInfo.invalidate()
                                quadBatchInfo.upload(0L, quadMetaLen, uploadBuffer.ptr)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun preDraw() {
        super.preDraw()
    }

    private val printTimer = TickTimer()

    override fun draw() {
        if (batchCount == 0) return

        if (renderer.debugQuadCount) {
            renderer.tempBuffer.clear(GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
        }

        if (!scene.gameInstance.toggle3) {
            drawPass(VisibilityState.LAST_VISIBLE)

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

            renderer.cullPipeline.depthBuffer.swDepthBlit()
            glViewport(0, 0, scene.camera.screenWidth / 4, scene.camera.screenHeight / 4)
            renderer.cullPipeline.draw(ctcBuffer, chunkCount)
            glViewport(0, 0, scene.camera.screenWidth, scene.camera.screenHeight)
            glEnable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_GEQUAL)
            glDepthMask(true)
            glColorMask(true, true, true, true)

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

            drawPass(VisibilityState.NEWLY_VISIBLE)
        } else {
            drawPass(VisibilityState.CURRENT_VISIBLE)
        }

        if (renderer.debugQuadCount && printTimer.tickAndReset(3000)) {
            MemoryStack {
                val ptr = malloc(renderer.tempBuffer.size).ptr
                glGetNamedBufferSubData(renderer.tempBuffer.id, 0, renderer.tempBuffer.size, ptr)
                println("SWQuad: Input Quad Count: %,d".format(ptr.getInt(0)))
                println("SWQuad: Output Quad Count: %,d".format(ptr.getInt(4)))
            }
        }
    }

    private fun drawPass(state: VisibilityState) {
        glDebugGroup("DrawPass-$state-Setup") {
            val scanWorkGroupSize = scene.gpuScan.workGroupSize
            val scanGroupCount = (batchCount + scanWorkGroupSize - 1) / scanWorkGroupSize
            clearShader.bind()
            clearShader.applyBinding {
                buffer(bufferBindings)
            }
            glDispatchCompute(1, 1, 1)
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
            val filterShader1 = filterShader[state]
            filterShader1.bind()
            filterShader1.applyBinding {
                buffer(renderer.bufferBindings)
                buffer(scene.vbuffer.bufferBinding)
                buffer(bufferBindings)
            }
            glDispatchCompute(scanGroupCount, 1, 1)
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_UNIFORM_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)
            val scanDataView = scanBuffer.createView(16L)
            scene.gpuScan.process(
                scanDataView,
                scanBuffer.createView(0L, 16L),
                scanDataView,
                scanGroupCount
            )
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
            batchIndicesShader.bind()
            batchIndicesShader.applyBinding {
                buffer(bufferBindings)
            }
            glDispatchCompute(scanGroupCount, 1, 1)
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)
        }
        glDebugGroup("DrawPass-$state-Draw") {
            glEnable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_GEQUAL)
            glDepthMask(true)
            glColorMask(true, true, true, true)
            scene.bindSWDrawShader(drawShader)
            drawShader.applyBinding {
                sampler(scene.textures.blockAlbedo)
                buffer(scene.camera.uboBinding)
                buffer(scene.terrainMeshData.bufferBindings)
                buffer(scene.vbuffer.bufferBinding)
                buffer(bufferBindings)
                buffer(renderer.bufferBindings)
                image(scene.vbuffer.imageBindings)
                image("uimg_overdrawCounter", scene.overdrawCounterTex)
            }
            batchIndices.bind(GL_DISPATCH_INDIRECT_BUFFER)
            glDispatchComputeIndirect(0L)
        }
    }

}