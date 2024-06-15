package dev.luna5ama.gltest.pc

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
import kotlin.math.min

class SWQuadPipeline(scene: PointCloudScene) : TerrainDrawPipeline<SWQuadPipeline.UpdateContextImpl>(scene) {
    private val batchInfoBuffer = register(BufferObject.Immutable())

    val bufferBinding = ShaderBindingSpecs.Buffer("QuadBatchInfoBuffer", batchInfoBuffer, BufferTarget.ShaderStorage)

    private val quadDrawShader = register(
        ShaderProgram(
            ShaderSource.Comp("pc/QuadDraw.comp") {
                define("BATCH_SIZE", scene.terrainMeshData.batchSize)
                if (scene.overdrawDebug) {
                    define("OVERDRAW_DEBUG")
                }
            }
        )
    ).apply {
        label("QuadDraw")
    }

    override fun startUpdate(): UpdateContextImpl {
        return UpdateContextImpl()
    }

    override fun endUpdate(context: UpdateContextImpl) {
        batchCount = context.batchCount
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

    override fun draw() {
        if (batchCount == 0) return
        scene.bindSWDrawShader(quadDrawShader)
        quadDrawShader.applyBinding {
            sampler(scene.textures.blockAlbedo)
            buffer(scene.camera.uboBinding)
            buffer(scene.terrainMeshData.bufferBindings)
            buffer(scene.vbuffer.bufferBinding)
            buffer(bufferBinding)
            image(scene.vbuffer.imageBindings)
            image("uimg_overdrawCounter", scene.overdrawCounterTex)
        }
        glDispatchCompute(batchCount, 1, 1)
    }

    inner class UpdateContextImpl : UpdateContext() {
        val uploadBuffer = Arr.malloc(0L)

        override fun add(chunkInfo: TerrainMeshData.ChunkInfo) {
            var quadIdx = 0
            while (quadIdx < chunkInfo.quadCount) {
                uploadBuffer.ensureCapacity((batchCount + 1L) * sizeof(QuadBatchInfo), false)
                val struct = QuadBatchInfo(uploadBuffer.ptr + batchCount * sizeof(QuadBatchInfo))
                val batchQuadCount = min(chunkInfo.quadCount - quadIdx, scene.terrainMeshData.batchSize)
                struct.first = chunkInfo.startIndex + quadIdx
                struct.count = batchQuadCount.toByte()
                struct.offsetX = chunkInfo.offset.x.toByte()
                struct.offsetY = chunkInfo.offset.y.toByte()
                struct.offsetZ = chunkInfo.offset.z.toByte()
                quadIdx += batchQuadCount
                batchCount++
            }
            quadCount += quadIdx
        }

        override fun close() {
            uploadBuffer.free()
        }
    }
}