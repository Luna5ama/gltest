package dev.luna5ama.gltest.pc

import dev.fastmc.common.ParallelUtils
import dev.fastmc.common.TickTimer
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
import dev.luna5ama.kmogus.*
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class SWQuadPipeline(scene: PointCloudScene, renderer: TerrainGeometryRenderer) : TerrainDrawPipeline(scene, renderer) {
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

    private val tempBuffer = register(BufferObject.Immutable()).apply {
        label("SWQuad.TempBuffer")
        allocate(128L, 0)
    }

    private val bufferBindings = listOf(
        ShaderBindingSpecs.Buffer("QuadBatchInfoBuffer", quadBatchInfo, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("BatchIndicesIndirect", batchIndices.createView(0L, 16L), BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("BatchIndicesData", batchIndices.createView(16L), BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("BatchFilterFlag", batchFilterFlag, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("ScanBuffer", scanBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("ScanBufferCount", scanBuffer.createView(0L, 16L), BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("TempBuffer", tempBuffer, BufferTarget.ShaderStorage),
    )

    private val clearShader = register(ShaderProgram(ShaderSource.Comp("pc/geometry/swquad/Clear.comp"))).apply {
        label("SWQuad.ClearShader")
    }

    private val filterShader = List(4) {
        register(ShaderProgram(
            ShaderSource.Comp("pc/geometry/swquad/BatchFilter.comp") {
                define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
                define("CULL_PASS", it)
            }
        )).apply {
            label("SWQuad.SWQuadBatchFilter-$it")
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
        }
    )).apply {
        label("SWQuad.DrawShader")
    }

    private val ctcBuffer = register(BufferObject.Immutable()).apply {
        label("SWQuad.CullTestCommandBuffer")
    }

    override suspend fun update(chunks: List<TerrainMeshData.ChunkLayerInfo>) {
        val batchStructSize = sizeof(QuadBatchInfo)
        val ctcBufferBaseOffset = renderer.cullPipeline.ctcBufferBaseOffset
        val ctcStructSize = sizeof(CullTestCommand)
        val quadBatchInfoMetaSize = 16L
        Arr.malloc(quadBatchInfoMetaSize + chunks.size * batchStructSize).use { uploadBuffer ->
            Arr.malloc(ctcBufferBaseOffset + chunks.size * ctcStructSize).use { ctcUploadBuffer ->
                val quadCountGlobal = AtomicInteger(0)
                val localBuffers = List(ParallelUtils.CPU_THREADS) { Arr.malloc(0L) }
                val localBatchCounts = IntArray(ParallelUtils.CPU_THREADS)
                var threadIndexCounter = 0
                val chunkCounter = AtomicInteger(0)

                coroutineScope {
                    launch {
                        val chunkIndexSet = IntOpenHashSet()
                        for (i in chunks.indices) {
                            val chunkInfo = chunks[i]
                            if (!chunkIndexSet.add(chunkInfo.chunkIndex)) continue
                            val offset = chunkCounter.getAndIncrement() * ctcStructSize
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
                    uploadBuffer.ensureCapacity(
                        quadBatchInfoMetaSize + (batchCount + localBatchCount) * batchStructSize,
                        false
                    )
                    memcpy(
                        arr.ptr,
                        0L,
                        uploadBuffer.ptr,
                        quadBatchInfoMetaSize + batchCount * batchStructSize,
                        localBatchCount * batchStructSize
                    )
                    batchCount += localBatchCount
                }

                uploadBuffer.ptr.setInt(0L, batchCount)

                localBuffers.forEach { it.free() }

                quadCount = quadCountGlobal.get()
                chunkCount = chunkCounter.get()

                if (batchCount == 0) return

                renderer.cullPipeline.setupIndirect(ctcUploadBuffer.ptr, chunkCounter.get())

                withContext(scene.gameInstance.mainContext) {
                    val quadMetaLen = quadBatchInfoMetaSize + batchCount * sizeof(QuadBatchInfo)
                    if (quadBatchInfo.size < quadMetaLen) {
                        quadBatchInfo.destroy()
                        quadBatchInfo.allocate(quadMetaLen, uploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
                    } else {
                        quadBatchInfo.invalidate()
                        quadBatchInfo.upload(0L, quadMetaLen, uploadBuffer.ptr)
                    }

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

                    val ctcLen = ctcBufferBaseOffset + chunkCounter.get() * ctcStructSize
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

    override fun preDraw() {
        super.preDraw()
    }

    private val printTimer = TickTimer()

    override fun draw() {
        if (batchCount == 0) return

        tempBuffer.clear(GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)

        if (!scene.gameInstance.toggle3) {
            drawPass(1)

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

            renderer.cullPipeline.swDepthBlit()
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

            drawPass(2)
        } else {
            drawPass(0)
        }

        if (printTimer.tickAndReset(3000)) {
            MemoryStack {
                val ptr = malloc(tempBuffer.size).ptr
                glGetNamedBufferSubData(tempBuffer.id, 0, tempBuffer.size, ptr)
                println("Input Quad Count: %,d".format(ptr.getInt(0)))
                println("Output Quad Count: %,d".format(ptr.getInt(4)))
            }
        }
    }

    private fun drawPass(pass: Int) {
        glDebugGroup("DrawPass-$pass-Setup") {
            passSetup(pass)
        }
        glDebugGroup("DrawPass-$pass-Draw") {
            passDraw()
        }
    }

    private fun passSetup(index: Int) {
        val scanWorkGroupSize = scene.gpuScan.workGroupSize
        val scanGroupCount = (batchCount + scanWorkGroupSize - 1) / scanWorkGroupSize
        clearShader.bind()
        clearShader.applyBinding {
            buffer(bufferBindings)
        }
        glDispatchCompute(1, 1, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
        val setupShader = filterShader[index]
        setupShader.bind()
        setupShader.applyBinding {
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

    private fun passDraw() {
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