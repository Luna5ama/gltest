package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.structs.DrawArraysIndirectCommand
import dev.luna5ama.gltest.structs.MeshShaderBatchInfo
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

class HWMeshShaderPipeline(scene: PointCloudScene) :
    TerrainDrawPipeline<HWMeshShaderPipeline.UpdateContextImpl>(scene) {
    private val shader = register(
        ShaderProgram(
            ShaderSource.Task("pc/QuadDrawHW.task"),
            ShaderSource.Mesh("pc/QuadDrawHW.mesh"),
            ShaderSource.Frag("pc/QuadDrawHW.frag") {
                define("MESH_SHADER")
            }
        )
    ).apply {
        label("HWMeshShaderPipeline.shader")
    }
    private val batchInfoBuffer = register(BufferObject.Immutable()).apply {
        label("HWMeshShaderPipeline.BatchInfoBuffer")
    }
    private val bufferBinding =
        ShaderBindingSpecs.Buffer("BatchInfoBuffer", batchInfoBuffer, BufferTarget.ShaderStorage)

    override fun startUpdate(): UpdateContextImpl {
        return UpdateContextImpl()
    }

    override fun endUpdate(context: UpdateContextImpl) {
        batchCount = context.batchCount
        quadCount = context.quadCount
        chunkCount = context.chunkCount

        if (context.batchCount == 0) return
        val batchInfoLen = context.batchCount * sizeof(MeshShaderBatchInfo)
        if (batchInfoBuffer.size < batchInfoLen) {
            batchInfoBuffer.destroy()
            batchInfoBuffer.allocate(batchInfoLen, context.uploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
        } else {
            batchInfoBuffer.invalidate()
            batchInfoBuffer.upload(0L, batchInfoLen, context.uploadBuffer.ptr)
        }
    }

    override fun draw() {
        if (batchCount == 0) return

        shader.bind()
        shader.applyBinding {
            sampler(scene.textures.blockAlbedo)
            buffer(scene.camera.uboBinding)
            buffer(bufferBinding)
            buffer(scene.terrainMeshData.bufferBindings)
        }
        shader.uniform3f(
            "uval_originOffset",
            (PointCloudScene.originX - scene.camera.pos.x - 0.5).toFloat(),
            (PointCloudScene.originY - scene.camera.pos.y - 1.62).toFloat(),
            (PointCloudScene.originZ - scene.camera.pos.z - 0.5).toFloat()
        )
        glDrawMeshTasksNV(0, batchCount)
    }

    inner class UpdateContextImpl : UpdateContext() {
        val uploadBuffer = Arr.malloc(0L)

        override fun add(chunkInfo: TerrainMeshData.ChunkInfo) {
            val offset = chunkInfo.offset
            uploadBuffer.ensureCapacity((batchCount + 1L) * sizeof(MeshShaderBatchInfo), false)
            val struct = MeshShaderBatchInfo(uploadBuffer.ptr + batchCount * sizeof(MeshShaderBatchInfo))
            struct.chunkIndex = chunkInfo.chunkIndex
            struct.quadStartIndex = chunkInfo.startIndex
            struct.quadCount = chunkInfo.quadCount
            struct.chunkOffsetEncoded = (offset.x and xBits) or (offset.y and yBits shl 13) or (offset.z shl (13 + 6))

            batchCount++
            chunkCount++
            quadCount += chunkInfo.quadCount
        }

        override fun close() {
            uploadBuffer.free()
        }
    }

    private companion object {
        private const val xBits = (1 shl 13) - 1
        private const val yBits = (1 shl 6) - 1
    }
}