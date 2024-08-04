package dev.luna5ama.gltest.pc

import dev.fastmc.common.ParallelUtils
import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.structs.DrawElementsIndirectCommand
import dev.luna5ama.gltest.structs.QuadBatchInfo
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
    TerrainDrawPipeline("HWVS", scene, renderer) {
    private val vao = register(VertexArrayObject())
    private val clearShader = register(
        ShaderProgram(
            ShaderSource.Comp("pc/geometry/hwvs/Clear.comp")
        )
    ).apply {
        label("HWVS.Clear")
    }
    private val indirectSetupShaders = List(4) { register(IndirectSetupShader(it)) }
    private val drawShader = register(
        ShaderProgram(
            ShaderSource.Vert("pc/geometry/HWVS.vert"),
            ShaderSource.Frag("pc/geometry/HW.frag")
        )
    ).apply {
        label("HWVS.Draw")
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
        Arr.malloc(chunks.size * batchStructSize).use { uploadBuffer ->
            coroutineScope {
                val quadCountGlobal = AtomicInteger(0)
                val localBuffers = List(ParallelUtils.CPU_THREADS) { Arr.malloc(0L) }
                val localBatchCounts = IntArray(ParallelUtils.CPU_THREADS)
                var threadIndexCounter = 0

                coroutineScope {
                    launch {
                        updateCtcBuffer(chunks)
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

                quadCount = quadCountGlobal.get()
                batchCount = localBatchCounts.sum()
                if (batchCount == 0) return@coroutineScope

                val quadBatchInfoMetaSize = 16L

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

                uploadBuffer.ptr.setInt(batchCount)

                withContext(scene.gameInstance.mainContext) {
                    val indirectCmdLen = 16L + batchCount * batchStructSize
                    if (inputIndirectBuffer.size < indirectCmdLen) {
                        inputIndirectBuffer.destroy()
                        inputIndirectBuffer.allocate(indirectCmdLen, uploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
                        outputIndirectBuffer.destroy()
                        outputIndirectBuffer.allocate(indirectCmdLen, Ptr.NULL, 0)
                    } else {
                        inputIndirectBuffer.invalidate()
                        inputIndirectBuffer.upload(0L, indirectCmdLen, uploadBuffer.ptr)
                    }
                }
            }
        }
    }

    override fun draw() {
        if (batchCount == 0) return

        if (!scene.gameInstance.toggle3) {
            cullPass(1)
            drawPass(outputIndirectBuffer)

            renderer.cullPipeline.depthBuffer.hwDepthBlit()
            glViewport(0, 0, scene.camera.screenWidth / 8, scene.camera.screenHeight / 8)
            renderer.cullPipeline.draw(ctcBuffer, chunkCount)

            glEnable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_GEQUAL)
            glDepthMask(true)
            glColorMask(true, true, true, true)

            cullPass(2)
            drawPass(outputIndirectBuffer)
        } else {
            cullPass(3)
            drawPass(outputIndirectBuffer)

            drawPass(inputIndirectBuffer)
        }
    }

    private fun cullPass(passIndex: Int) {
        clearShader.bind()
        clearShader.applyBinding {
            buffer(indirectBufferBindings)
        }
        glDispatchCompute(1, 1, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
        val shader = indirectSetupShaders[passIndex]
        shader.bind()
        shader.applyBinding {
            buffer(indirectBufferBindings)
            buffer(renderer.bufferBindings)
            buffer(scene.terrainMeshData.bufferBindings)
            buffer(scene.camera.uboBinding)
        }
        glDispatchCompute((batchCount + 255) / 256, 1, 1)
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)
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
        buffer.bind(GL_DRAW_INDIRECT_BUFFER)
        buffer.bind(GL_PARAMETER_BUFFER)
        glMultiDrawElementsIndirectCount(GL_TRIANGLES, GL_UNSIGNED_SHORT, 16L, 0L, batchCount, 0)
    }

    private inner class IndirectSetupShader(cullPass: Int) : ShaderProgram(
        ShaderSource.Comp("pc/geometry/CullIndirectSetup.comp") {
            define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
            define("CULL_PASS", cullPass)
        },
    )

    companion object {
        const val BATCH_MAX_QUAD = 65536 / 4
    }
}