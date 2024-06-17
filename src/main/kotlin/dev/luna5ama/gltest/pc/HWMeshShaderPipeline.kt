package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.structs.CullTestCommand
import dev.luna5ama.gltest.structs.DrawMeshTasksIndirectCommandNV
import dev.luna5ama.gltest.structs.MeshShaderBatchInfo
import dev.luna5ama.gltest.structs.sizeof
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.GL_CULL_FACE
import dev.luna5ama.glwrapper.api.GL_DYNAMIC_STORAGE_BIT
import dev.luna5ama.glwrapper.api.glDisable
import dev.luna5ama.glwrapper.api.glDrawMeshTasksNV
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.ensureCapacity

class HWMeshShaderPipeline(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
    TerrainDrawPipeline<HWMeshShaderPipeline.UpdateContextImpl>(scene, renderer) {
    private val drawShaders = List(4) { register(DrawShader(it)) }
    private val batchInfoBuffer = register(BufferObject.Immutable()).apply {
        label("HWMS.BatchInfoBuffer")
    }
    private val bufferBinding =
        ShaderBindingSpecs.Buffer("BatchInfoBuffer", batchInfoBuffer, BufferTarget.ShaderStorage)

//    private val cullTestCommandBuffer = register(BufferObject.Immutable()).apply {
//        label("HWMS.CullTestCommandBuffer")
//    }

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
            batchInfoBuffer.allocate(batchInfoLen, context.batchInfoUploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
        } else {
            batchInfoBuffer.invalidate()
            batchInfoBuffer.upload(0L, batchInfoLen, context.batchInfoUploadBuffer.ptr)
        }

//        renderer.cullPipeline.setupIndirect(context.cullTestCommandUploadBuffer.ptr, context.batchCount)
//        val baseOffset = renderer.cullPipeline.ctcBufferBaseOffset
//        val cullTestCommandLen = baseOffset + context.batchCount * sizeof(CullTestCommand)
//        if (cullTestCommandBuffer.size < cullTestCommandLen) {
//            cullTestCommandBuffer.destroy()
//            cullTestCommandBuffer.allocate(cullTestCommandLen, context.cullTestCommandUploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
//        } else {
//            cullTestCommandBuffer.invalidate()
//            cullTestCommandBuffer.upload(0, cullTestCommandLen, context.cullTestCommandUploadBuffer.ptr)
//        }
    }

    override fun draw() {
        if (batchCount == 0) return

//        drawShaders[1].bind()
//        scene.vbuffer.fbo.bind()
//        glDrawMeshTasksNV(0, batchCount)
//
//        renderer.cullPipeline.draw(cullTestCommandBuffer)
//
        drawShaders[0].bind()
        scene.vbuffer.fbo.bind()
        glDrawMeshTasksNV(0, batchCount)
    }

    inner class DrawShader(cullPass: Int) : ShaderProgram(
        ShaderSource.Task("pc/geometry/HWMS.task") {
            define("CULL_PASS", cullPass)
            define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
        },
        ShaderSource.Mesh("pc/geometry/HWMS.mesh"),
        ShaderSource.Frag("pc/geometry/HW.frag") {
            define("MESH_SHADER")
        }
    ) {
        init {
            label("HWMS.DrawShader-$cullPass")
        }

        override fun bind() {
            super.bind()
            applyBinding {
                sampler(scene.textures.blockAlbedo)
                buffer(scene.camera.uboBinding)
                buffer(bufferBinding)
                buffer(scene.terrainMeshData.bufferBindings)
                buffer(renderer.visibleBufferBinding)
            }
            uniform3f(
                "uval_originOffset",
                (PointCloudScene.originX - scene.camera.pos.x - 0.5).toFloat(),
                (PointCloudScene.originY - scene.camera.pos.y - 1.62).toFloat(),
                (PointCloudScene.originZ - scene.camera.pos.z - 0.5).toFloat()
            )
            uniform4f(
                "uval_viewportSize",
                scene.camera.screenSize.x.toFloat(), scene.camera.screenSize.y.toFloat(),
                1.0f / scene.camera.screenSize.x.toFloat(), 1.0f / scene.camera.screenSize.y.toFloat()
            )
        }
    }

    inner class UpdateContextImpl : UpdateContext() {
        val batchInfoUploadBuffer = Arr.malloc(0L)
        val cullTestCommandUploadBuffer = Arr.malloc(0L)

        override fun add(chunkInfo: TerrainMeshData.ChunkInfo) {
            val chunkOffsetEncoded = chunkInfo.offsetEncoded

            batchInfoUploadBuffer.ensureCapacity((batchCount + 1L) * sizeof(MeshShaderBatchInfo), false)
            val struct = MeshShaderBatchInfo(batchInfoUploadBuffer.ptr + batchCount * sizeof(MeshShaderBatchInfo))
            struct.chunkIndex = chunkInfo.chunkIndex
            struct.quadStartIndex = chunkInfo.startIndex
            struct.quadCount = chunkInfo.quadCount
            struct.chunkOffsetEncoded = chunkOffsetEncoded

//            val ctcBaseOffset = 16L
//            val ctcLen = sizeof(CullTestCommand)
//            cullTestCommandUploadBuffer.ensureCapacity(
//                ctcBaseOffset + (batchCount + 1L) * ctcLen,
//                false
//            )
//            val ctcStruct = CullTestCommand(cullTestCommandUploadBuffer.ptr + ctcBaseOffset + batchCount * ctcLen)
//            ctcStruct.chunkIndex = chunkInfo.chunkIndex
//            ctcStruct.chunkOffsetEncoded = chunkOffsetEncoded

            batchCount++
            chunkCount++
            quadCount += chunkInfo.quadCount
        }

        override fun close() {
            batchInfoUploadBuffer.free()
            cullTestCommandUploadBuffer.free()
        }
    }
}