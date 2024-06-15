package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.structs.DrawElementsIndirectCommand
import dev.luna5ama.gltest.structs.sizeof
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.VertexArrayObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.ensureCapacity
import kotlin.math.min

class HWVertexShaderPipeline(scene: PointCloudScene) :
    TerrainDrawPipeline<HWVertexShaderPipeline.UpdateContextImpl>(scene) {
    private val vao = register(VertexArrayObject())
    private val shader = register(
        ShaderProgram(
            ShaderSource.Vert("pc/QuadDrawHW.vert"),
            ShaderSource.Frag("pc/QuadDrawHW.frag")
        )
    ).apply {
        label("QuadDrawHW.shader")
    }
    private val indirectCmds = register(BufferObject.Immutable()).apply {
        label("QuadDrawHW.indirectCmds")
    }

    private val elementBuffer = register(BufferObject.Immutable()).apply {
        label("QuadDrawHW.elementBuffer")
    }

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

    override fun startUpdate(): UpdateContextImpl {
        return UpdateContextImpl()
    }

    override fun endUpdate(context: UpdateContextImpl) {
        batchCount = context.batchCount
        quadCount = context.quadCount
        chunkCount = context.chunkCount

        if (context.batchCount == 0) return
        val indirctCmdLen = context.batchCount * sizeof(DrawElementsIndirectCommand)
        if (indirectCmds.size < indirctCmdLen) {
            indirectCmds.destroy()
            indirectCmds.allocate(indirctCmdLen, context.indirectCmdUpload.ptr, GL_DYNAMIC_STORAGE_BIT)
        } else {
            indirectCmds.invalidate()
            indirectCmds.upload(0L, indirctCmdLen, context.indirectCmdUpload.ptr)
        }
    }

    override fun draw() {
        if (batchCount == 0) return

        vao.bind()
        shader.bind()
        shader.applyBinding {
            sampler(scene.textures.blockAlbedo)
            buffer(scene.camera.uboBinding)
            buffer(scene.terrainMeshData.bufferBindings)
        }
        shader.uniform3f(
            "uval_originOffset",
            (PointCloudScene.originX - scene.camera.pos.x - 0.5).toFloat(),
            (PointCloudScene.originY - scene.camera.pos.y - 1.62).toFloat(),
            (PointCloudScene.originZ - scene.camera.pos.z - 0.5).toFloat()
        )
        indirectCmds.bind(GL_DRAW_INDIRECT_BUFFER)
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_SHORT, 0L, batchCount, 0)
    }

    inner class UpdateContextImpl : UpdateContext() {
        val indirectCmdUpload = Arr.malloc(0L)

        override fun add(chunkInfo: TerrainMeshData.ChunkInfo) {
            var quadIdx = 0
            while (quadIdx < chunkInfo.quadCount) {
                val batchQuadCount = min(chunkInfo.quadCount - quadIdx, BATCH_MAX_QUAD)

                indirectCmdUpload.ensureCapacity((batchCount + 1L) * sizeof(DrawElementsIndirectCommand), false)
                val structOffset = batchCount * sizeof(DrawElementsIndirectCommand)
                val struct = DrawElementsIndirectCommand(indirectCmdUpload.ptr + structOffset)
                struct.count = batchQuadCount * 6
                struct.instanceCount = 1
                struct.firstIndex = 0
                struct.baseVertex = (chunkInfo.startIndex + quadIdx) * 4
                struct.baseInstance = chunkInfo.chunkIndex

                quadIdx += batchQuadCount
                batchCount++
            }

            chunkCount++
            quadCount += chunkInfo.quadCount
        }

        override fun close() {
            indirectCmdUpload.free()
        }
    }

    companion object {
        const val BATCH_MAX_QUAD = 65536 / 4
    }
}