package dev.luna5ama.gltest.pc

import dev.fastmc.common.ParallelUtils
import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.structs.CullTestCommand
import dev.luna5ama.gltest.structs.DrawElementsIndirectCommand
import dev.luna5ama.gltest.structs.sizeof
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.VertexArrayObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.Ptr
import dev.luna5ama.kmogus.ensureCapacity
import dev.luna5ama.kmogus.memcpy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class HWVertexShaderPipeline(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
    TerrainDrawPipeline(scene, renderer) {
    private val vao = register(VertexArrayObject())
    private val indirectSetupShaders = List(4) { register(IndirectSetupShader(it)) }
    private val drawShader = register(
        ShaderProgram(
            ShaderSource.Vert("pc/geometry/HWVS.vert"),
            ShaderSource.Frag("pc/geometry/HW.frag")
        )
    ).apply {
        label("HWVS.shader")
    }
    private val inputIndirectBuffer = register(BufferObject.Immutable()).apply {
        label("HWVS.InputIndirectBuffer")
    }

    private val outputIndirectBuffer = register(BufferObject.Immutable()).apply {
        label("HWVS.OutputIndirectBuffer")
    }

    private val elementBuffer = register(BufferObject.Immutable()).apply {
        label("HWVS.ElementBuffer")
    }

    private val ctcBuffer = register(BufferObject.Immutable()).apply {
        label("SWQuad.CullTestCommandBuffer")
    }

    private val indirectBufferBindings = listOf(
        ShaderBindingSpecs.Buffer("InputIndirectBuffer", inputIndirectBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("OutputIndirectBuffer", outputIndirectBuffer, BufferTarget.ShaderStorage),
    )

    init {
        Arr.malloc(BATCH_MAX_QUAD * 6 * 2L).use { arr ->
            val ptr = arr.ptr
            for (i in 0 until BATCH_MAX_QUAD) {
                val baseIdx = i * 6L * 2L
                val vertBaseIdx = i * 4
                ptr.setShort(baseIdx, vertBaseIdx.toShort())
                ptr.setShort(baseIdx + 2, (vertBaseIdx + 1).toShort())
                ptr.setShort(baseIdx + 4, (vertBaseIdx + 2).toShort())
                ptr.setShort(baseIdx + 6, (vertBaseIdx + 2).toShort())
                ptr.setShort(baseIdx + 8, (vertBaseIdx + 3).toShort())
                ptr.setShort(baseIdx + 10, vertBaseIdx.toShort())
            }
            elementBuffer.allocate(arr.len, ptr, 0)
        }
        vao.attachEbo(elementBuffer)
    }

    override suspend fun update(chunks: List<TerrainMeshData.ChunkLayerInfo>) {
        val batchStructSize = sizeof(DrawElementsIndirectCommand)
        val ctcBufferBaseOffset = renderer.cullPipeline.ctcBufferBaseOffset
        val ctcStructSize = sizeof(CullTestCommand)
        Arr.malloc(chunks.size * batchStructSize).use { indirectCmdUpload ->
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
                            launch(Dispatchers.Default) {
                                val localBuffer = localBuffers[threadIndex]
                                localBuffer.ensureCapacity((end - start) * batchStructSize, false)
                                var quadCountLocal = 0
                                var localBatchCount = 0

                                for (i in start until end) {
                                    val chunkInfo = chunks[i]
                                    var quadIdx = 0
                                    while (quadIdx < chunkInfo.quadCount) {
                                        val batchQuadCount = min(chunkInfo.quadCount - quadIdx, BATCH_MAX_QUAD)

                                        localBuffer.ensureCapacity((localBatchCount + 1L) * batchStructSize, false)
                                        val structOffset = localBatchCount * batchStructSize
                                        val struct = DrawElementsIndirectCommand(localBuffer.ptr + structOffset)
                                        struct.count = batchQuadCount * 6
                                        struct.instanceCount = 1
                                        struct.firstIndex = 0
                                        struct.baseVertex = (chunkInfo.startIndex + quadIdx) * 4
                                        struct.baseInstance = chunkInfo.chunkIndex

                                        quadIdx += batchQuadCount
                                        localBatchCount++
                                    }

                                    quadCountLocal += chunkInfo.quadCount
                                }

                                localBatchCounts[threadIndex] = localBatchCount
                                quadCountGlobal.addAndGet(quadCountLocal)
                            }
                        }
                    )
                }

                batchCount = 0
                localBuffers.forEachIndexed { index, arr ->
                    val localBatchCount = localBatchCounts[index]
                    indirectCmdUpload.ensureCapacity(16L + (batchCount + localBatchCount) * batchStructSize, false)
                    memcpy(
                        arr.ptr,
                        0L,
                        indirectCmdUpload.ptr,
                        16L + batchCount * batchStructSize,
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
                    indirectCmdUpload.ptr.setInt(batchCount)
                    val indirectCmdLen = 16L + batchCount * batchStructSize
                    if (inputIndirectBuffer.size < indirectCmdLen) {
                        inputIndirectBuffer.destroy()
                        inputIndirectBuffer.allocate(indirectCmdLen, indirectCmdUpload.ptr, GL_DYNAMIC_STORAGE_BIT)
                        outputIndirectBuffer.destroy()
                        outputIndirectBuffer.allocate(indirectCmdLen, Ptr.NULL, 0)
                    } else {
                        inputIndirectBuffer.invalidate()
                        inputIndirectBuffer.upload(0L, indirectCmdLen, indirectCmdUpload.ptr)
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

    private val printTimer = TickTimer()

    override fun draw() {
        if (batchCount == 0) return

        if (!scene.gameInstance.toggle3) {
            indirectSetupShaders[1].dispatch()
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)
            drawPass(outputIndirectBuffer)

            renderer.cullPipeline.hwDepthBlit()
            glViewport(0, 0, scene.camera.screenWidth / 8, scene.camera.screenHeight / 8)
            renderer.cullPipeline.draw(ctcBuffer, chunkCount)

            glEnable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_GEQUAL)
            glDepthMask(true)
            glColorMask(true, true, true, true)

            indirectSetupShaders[2].dispatch()
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)
            drawPass(outputIndirectBuffer)
        } else {
//            indirectSetupShaders[3].dispatch()
//            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)
//            drawPass(outputIndirectBuffer)
//
            drawPass(inputIndirectBuffer)
        }
    }

    private fun drawPass(buffer: BufferObject.Immutable) {
        scene.vbuffer.fbo.bind()
        glViewport(0, 0, scene.vbuffer.fbo.width, scene.vbuffer.fbo.height)
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_GEQUAL)
        glDepthMask(true)
        glColorMask(true, true, true, true)

        vao.bind()
        drawShader.bind()
        drawShader.applyBinding {
            sampler(scene.textures.blockAlbedo)
            buffer(scene.camera.uboBinding)
            buffer(scene.terrainMeshData.bufferBindings)
            buffer(renderer.bufferBindings)
        }
//        drawShader.uniform3f(
//            "uval_originOffset",
//            (PointCloudScene.originX - scene.camera.pos.x - 0.5).toFloat(),
//            (PointCloudScene.originY - scene.camera.pos.y - 1.62).toFloat(),
//            (PointCloudScene.originZ - scene.camera.pos.z - 0.5).toFloat()
//        )
        buffer.bind(GL_DRAW_INDIRECT_BUFFER)
        buffer.bind(GL_PARAMETER_BUFFER)
        glMultiDrawElementsIndirectCount(GL_TRIANGLES, GL_UNSIGNED_SHORT, 16L, 0L, batchCount, 0)
    }

    private inner class IndirectSetupShader(cullPass: Int) : ShaderProgram(
        ShaderSource.Comp("pc/geometry/CullIndirectSetup.comp") {
            define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
            define("CULL_PASS", cullPass)
        },
    ) {
        fun dispatch() {
            bind()
            applyBinding {
                buffer(indirectBufferBindings)
                buffer(renderer.bufferBindings)
                buffer(scene.terrainMeshData.bufferBindings)
                buffer(scene.camera.uboBinding)
            }
            glDispatchCompute(1, 1, 1)
        }
    }

    companion object {
        const val BATCH_MAX_QUAD = 65536 / 4
    }
}