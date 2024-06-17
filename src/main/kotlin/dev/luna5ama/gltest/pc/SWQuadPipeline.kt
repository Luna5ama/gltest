package dev.luna5ama.gltest.pc

import dev.fastmc.common.TickTimer
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
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr
import dev.luna5ama.kmogus.ensureCapacity
import kotlin.math.min

class SWQuadPipeline(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
    TerrainDrawPipeline<SWQuadPipeline.UpdateContextImpl>(scene, renderer) {
//    private val batchSize = renderer.threadBatchSize
    private val batchSize = 128

    private val batchInfoBuffer = register(BufferObject.Immutable()).apply {
        label("SWQuad.BatchInfoBuffer")
    }

    val bufferBinding = ShaderBindingSpecs.Buffer("QuadBatchInfoBuffer", batchInfoBuffer, BufferTarget.ShaderStorage)

    val quadDrawShader = register(
        ShaderProgram(
            ShaderSource.Comp("pc/geometry/SWQuad.comp") {
                define("BATCH_SIZE", batchSize)
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
        allocate(8L, 0)
    }

    private val tempBufferBinding = ShaderBindingSpecs.Buffer("TempBuffer", tempBuffer, BufferTarget.ShaderStorage)

    override fun startUpdate(): UpdateContextImpl {
        return UpdateContextImpl()
    }

    override fun endUpdate(context: UpdateContextImpl) {
        batchCount = context.batchCount
        chunkCount = context.chunkCount
        quadCount = context.quadCount

        if (context.batchCount == 0) return

        val quadMetaLen = context.batchCount * sizeof(QuadBatchInfo)
        if (quadMetaLen > 0) {
            if (batchInfoBuffer.size < quadMetaLen) {
                batchInfoBuffer.destroy()
                batchInfoBuffer.allocate(quadMetaLen, context.uploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
            } else {
                batchInfoBuffer.invalidate()
                batchInfoBuffer.upload(0L, quadMetaLen, context.uploadBuffer.ptr)
            }
        }
    }

    private val printTimer = TickTimer()

    override fun draw() {
        if (batchCount == 0) return
        tempBuffer.clear(GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
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

        if (printTimer.tickAndReset(3000L)) {
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)
            MemoryStack {
                val arr = malloc(8L)
                val ptr = arr.ptr
                glGetNamedBufferSubData(tempBuffer.id, 0, arr.len, ptr)
                println("${ptr.getInt()}/${ptr.getInt(4)}")
            }
        }
    }

    inner class UpdateContextImpl : UpdateContext() {
        val uploadBuffer = Arr.malloc(0L)

        override fun add(chunkInfo: TerrainMeshData.ChunkInfo) {
            var quadIdx = 0
            while (quadIdx < chunkInfo.quadCount) {
                val batchQuadCount = min(chunkInfo.quadCount - quadIdx, batchSize)

                uploadBuffer.ensureCapacity((batchCount + 1L) * sizeof(QuadBatchInfo), false)
                val struct = QuadBatchInfo(uploadBuffer.ptr + batchCount * sizeof(QuadBatchInfo))
                struct.first = chunkInfo.startIndex + quadIdx
                struct.count = (batchQuadCount - 1).toByte()
                struct.offsetX = chunkInfo.offset.x.toByte()
                struct.offsetY = chunkInfo.offset.y.toByte()
                struct.offsetZ = chunkInfo.offset.z.toByte()

                quadIdx += batchQuadCount
                batchCount++
            }
            chunkCount++
            quadCount += quadIdx
        }

        override fun close() {
            uploadBuffer.free()
        }
    }
}