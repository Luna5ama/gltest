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

        if (elementBuffer.size < context.maxQuadCount * 6 * 4) {
            elementBuffer.destroy()
            Arr.malloc(context.maxQuadCount * 6 * 4L).use { arr ->
                val ptr = arr.ptr
                for (i in 0 until context.maxQuadCount) {
                    val baseIdx = i * 6L * 4L
                    val vertBaseIdx = i * 4
                    ptr.setInt(baseIdx, vertBaseIdx)
                    ptr.setInt(baseIdx + 4, vertBaseIdx + 1)
                    ptr.setInt(baseIdx + 8, vertBaseIdx + 2)
                    ptr.setInt(baseIdx + 12, vertBaseIdx + 2)
                    ptr.setInt(baseIdx + 16, vertBaseIdx + 3)
                    ptr.setInt(baseIdx + 20, vertBaseIdx)
                }
                elementBuffer.allocate(arr.len, ptr, 0)
            }
            vao.attachEbo(elementBuffer)
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
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0L, batchCount, 0)
    }

    inner class UpdateContextImpl : UpdateContext() {
        val indirectCmdUpload = Arr.malloc(0L)
        var maxQuadCount = 0

        override fun add(chunkInfo: TerrainMeshData.ChunkInfo) {
            indirectCmdUpload.ensureCapacity((batchCount + 1L) * sizeof(DrawElementsIndirectCommand), false)
            val structOffset = batchCount * sizeof(DrawElementsIndirectCommand)
            val struct = DrawElementsIndirectCommand(indirectCmdUpload.ptr + structOffset)
            struct.count = chunkInfo.quadCount * 6
            struct.instanceCount = 1
            struct.firstIndex = 0
            struct.baseVertex = chunkInfo.startIndex * 4
            struct.baseInstance = chunkInfo.chunkIndex
            maxQuadCount = maxOf(maxQuadCount, chunkInfo.quadCount)

            batchCount++
            chunkCount++
            quadCount += chunkInfo.quadCount
        }

        override fun close() {
            indirectCmdUpload.free()
        }
    }
}