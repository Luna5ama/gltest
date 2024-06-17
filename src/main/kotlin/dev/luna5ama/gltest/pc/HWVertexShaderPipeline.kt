package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.glDebugGroup
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
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr
import dev.luna5ama.kmogus.ensureCapacity
import kotlin.math.min

class HWVertexShaderPipeline(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
    TerrainDrawPipeline<HWVertexShaderPipeline.UpdateContextImpl>(scene, renderer) {
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

    private val cullTestCommandBuffer = register(BufferObject.Immutable()).apply {
        label("HWMS.CullTestCommandBuffer")
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

    override fun startUpdate(): UpdateContextImpl {
        return UpdateContextImpl()
    }

    override fun endUpdate(context: UpdateContextImpl) {
        batchCount = context.batchCount
        quadCount = context.quadCount
        chunkCount = context.chunkCount

        if (context.batchCount == 0) return
        context.indirectCmdUpload.ptr.setInt(context.batchCount)
        val indirctCmdLen = 16L + context.batchCount * sizeof(DrawElementsIndirectCommand)
        if (inputIndirectBuffer.size < indirctCmdLen) {
            inputIndirectBuffer.destroy()
            inputIndirectBuffer.allocate(indirctCmdLen, context.indirectCmdUpload.ptr, GL_DYNAMIC_STORAGE_BIT)
            outputIndirectBuffer.destroy()
            outputIndirectBuffer.allocate(indirctCmdLen, Ptr.NULL, 0)
        } else {
            inputIndirectBuffer.invalidate()
            inputIndirectBuffer.upload(0L, indirctCmdLen, context.indirectCmdUpload.ptr)
        }

//        renderer.cullPipeline.setupIndirect(context.cullTestCommandUploadBuffer.ptr, context.batchCount)
//        val baseOffset = renderer.cullPipeline.ctcBufferBaseOffset
//        val cullTestCommandLen = baseOffset + context.batchCount * sizeof(CullTestCommand)
//        if (cullTestCommandBuffer.size < cullTestCommandLen) {
//            cullTestCommandBuffer.destroy()
//            cullTestCommandBuffer.allocate(
//                cullTestCommandLen,
//                context.cullTestCommandUploadBuffer.ptr,
//                GL_DYNAMIC_STORAGE_BIT
//            )
//        } else {
//            cullTestCommandBuffer.invalidate()
//            cullTestCommandBuffer.upload(0, cullTestCommandLen, context.cullTestCommandUploadBuffer.ptr)
//        }
    }

    override fun draw() {
        if (batchCount == 0) return

//        if (!scene.gameInstance.toggle3) {
//            glDebugGroup("Render Last Visible") {
//                glDebugGroup("Indirect Setup") {
//                    indirectSetupShaders[1].dispatch()
//                    glMemoryBarrier(GL_COMMAND_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
//                }
//                glDebugGroup("Draw") {
//                    drawPass(outputIndirectBuffer)
//                }
//            }
//
//            glDebugGroup("Cull Test") {
//                renderer.cullPipeline.draw(cullTestCommandBuffer)
//                glMemoryBarrier(GL_COMMAND_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
//            }
//
////            scene.vbuffer.clear()
//
//            glDebugGroup("Render New Visible") {
//                glDebugGroup("Indirect Setup") {
//                    indirectSetupShaders[2].dispatch()
//                    glMemoryBarrier(GL_COMMAND_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT)
//                }
//                glDebugGroup("Draw") {
//                    drawPass(outputIndirectBuffer)
//                }
//            }
//
//            indirectSetupShaders[3].dispatch()
//            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_BUFFER_UPDATE_BARRIER_BIT)
//
//            scene.vbuffer.fbo.bind()
//            glDisable(GL_DEPTH_CLAMP)
//            glEnable(GL_CULL_FACE)
//            glCullFace(GL_BACK)
//            glEnable(GL_DEPTH_TEST)
//            glDepthFunc(GL_LEQUAL)
//            glDepthMask(true)
//            glColorMask(true, true, true, true)
//
////            if (printTimer.tickAndReset(3000)) {
//                MemoryStack {
//                    val count = malloc(4)
//                    glGetNamedBufferSubData(outputIndirectBuffer.id, 0, 4L, count.ptr)
//                    println("Batch count: ${count.ptr.getInt()}/${batchCount}")
//                }
////            }
//        } else {
            drawPass(inputIndirectBuffer)
//        }
    }

    private fun drawPass(buffer: BufferObject.Immutable) {
        scene.vbuffer.fbo.bind()
        glDisable(GL_DEPTH_CLAMP)
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glDepthMask(true)
        glColorMask(true, true, true, true)

        vao.bind()
        drawShader.bind()
        drawShader.applyBinding {
            sampler(scene.textures.blockAlbedo)
            buffer(scene.camera.uboBinding)
            buffer(scene.terrainMeshData.bufferBindings)
        }
        drawShader.uniform3f(
            "uval_originOffset",
            (PointCloudScene.originX - scene.camera.pos.x - 0.5).toFloat(),
            (PointCloudScene.originY - scene.camera.pos.y - 1.62).toFloat(),
            (PointCloudScene.originZ - scene.camera.pos.z - 0.5).toFloat()
        )
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
                buffer(renderer.visibleBufferBinding)
                buffer(scene.terrainMeshData.bufferBindings)
                buffer(scene.camera.uboBinding)
            }
            uniform3f(
                "uval_originOffset",
                (PointCloudScene.originX - scene.camera.pos.x - 0.5).toFloat(),
                (PointCloudScene.originY - scene.camera.pos.y - 1.62).toFloat(),
                (PointCloudScene.originZ - scene.camera.pos.z - 0.5).toFloat()
            )
            glDispatchCompute(1, 1, 1)
        }
    }

    inner class UpdateContextImpl : UpdateContext() {
        val indirectCmdUpload = Arr.malloc(0L)
//        val cullTestCommandUploadBuffer = Arr.malloc(0L)

        override fun add(chunkInfo: TerrainMeshData.ChunkInfo) {
            var quadIdx = 0
            while (quadIdx < chunkInfo.quadCount) {
                val batchQuadCount = min(chunkInfo.quadCount - quadIdx, BATCH_MAX_QUAD)

                indirectCmdUpload.ensureCapacity(16L + (batchCount + 1L) * sizeof(DrawElementsIndirectCommand), false)
                val structOffset = batchCount * sizeof(DrawElementsIndirectCommand)
                val struct = DrawElementsIndirectCommand(indirectCmdUpload.ptr + 16L + structOffset)
                struct.count = batchQuadCount * 6
                struct.instanceCount = 1
                struct.firstIndex = 0
                struct.baseVertex = (chunkInfo.startIndex + quadIdx) * 4
                struct.baseInstance = chunkInfo.chunkIndex

                quadIdx += batchQuadCount
                batchCount++
            }

//            val ctcBaseOffset = renderer.cullPipeline.ctcBufferBaseOffset
//            val ctcLen = sizeof(CullTestCommand)
//            cullTestCommandUploadBuffer.ensureCapacity(
//                ctcBaseOffset + (chunkCount + 1L) * ctcLen,
//                false
//            )
//            val ctcStruct = CullTestCommand(cullTestCommandUploadBuffer.ptr + ctcBaseOffset + chunkCount * ctcLen)
//            ctcStruct.chunkIndex = chunkInfo.chunkIndex
//            ctcStruct.chunkOffsetEncoded = chunkInfo.offsetEncoded

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