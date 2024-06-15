package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.structs.DrawArraysIndirectCommand
import dev.luna5ama.gltest.structs.sizeof
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.GL_DRAW_INDIRECT_BUFFER
import dev.luna5ama.glwrapper.api.GL_DYNAMIC_STORAGE_BIT
import dev.luna5ama.glwrapper.api.GL_TRIANGLES
import dev.luna5ama.glwrapper.api.glMultiDrawArraysIndirect
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.VertexArrayObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.ensureCapacity

class HWVertexShaderPipeline(scene: PointCloudScene) : TerrainDrawPipeline<HWVertexShaderPipeline.UpdateContextImpl>(scene) {
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
    private val chunkStartIndex = register(BufferObject.Immutable()).apply {
        label("QuadDrawHW.chunkStartIndex")
    }
    private val bufferBinding =
        ShaderBindingSpecs.Buffer("ChunkStartIndexBuffer", chunkStartIndex, BufferTarget.ShaderStorage)

    override fun startUpdate(): UpdateContextImpl {
        return UpdateContextImpl()
    }

    override fun endUpdate(context: UpdateContextImpl) {
        batchCount = context.batchCount
        quadCount = context.quadCount

        if (context.batchCount == 0) return
        val indirctCmdLen = context.batchCount * sizeof(DrawArraysIndirectCommand)
        if (indirectCmds.size < indirctCmdLen) {
            indirectCmds.destroy()
            indirectCmds.allocate(indirctCmdLen, context.indirectCmdUpload.ptr, GL_DYNAMIC_STORAGE_BIT)
        } else {
            indirectCmds.invalidate()
            indirectCmds.upload(0L, indirctCmdLen, context.indirectCmdUpload.ptr)
        }

        val startIndexLen = context.batchCount * 4L
        if (chunkStartIndex.size < startIndexLen) {
            chunkStartIndex.destroy()
            chunkStartIndex.allocate(startIndexLen, context.startIndexUpload.ptr, GL_DYNAMIC_STORAGE_BIT)
        } else {
            chunkStartIndex.invalidate()
            chunkStartIndex.upload(0L, startIndexLen, context.startIndexUpload.ptr)
        }
    }

    override fun draw() {
        if (batchCount == 0) return

        vao.bind()
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
        indirectCmds.bind(GL_DRAW_INDIRECT_BUFFER)
        glMultiDrawArraysIndirect(GL_TRIANGLES, 0L, batchCount, 0)
    }

    inner class UpdateContextImpl : UpdateContext() {
        val indirectCmdUpload = Arr.malloc(0L)
        val startIndexUpload = Arr.malloc(0L)

        override fun add(chunkInfo: TerrainMeshData.ChunkInfo) {
            indirectCmdUpload.ensureCapacity((batchCount + 1L) * sizeof(DrawArraysIndirectCommand), false)
            val struct = DrawArraysIndirectCommand(
                indirectCmdUpload.ptr + batchCount * sizeof(
                    DrawArraysIndirectCommand
                )
            )
            struct.count = chunkInfo.quadCount * 6
            struct.instanceCount = 1
            struct.first = 0
            struct.baseInstance = chunkInfo.chunkIndex

            startIndexUpload.ensureCapacity((batchCount + 1L) * 4L, false)
            startIndexUpload.ptr.setInt(batchCount * 4L, chunkInfo.startIndex)

            batchCount++
            quadCount += chunkInfo.quadCount
        }

        override fun close() {
            indirectCmdUpload.free()
            startIndexUpload.free()
        }
    }
}