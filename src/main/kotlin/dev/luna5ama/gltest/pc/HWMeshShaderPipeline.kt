package dev.luna5ama.gltest.pc

import dev.fastmc.common.ParallelUtils
import dev.luna5ama.gltest.structs.MeshShaderBatchInfo
import dev.luna5ama.gltest.structs.sizeof
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.GL_DYNAMIC_STORAGE_BIT
import dev.luna5ama.glwrapper.api.glDrawMeshTasksNV
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.Arr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class HWMeshShaderPipeline(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
    TerrainDrawPipeline(scene, renderer) {
    private val drawShaders = List(4) { register(DrawShader(it)) }
    private val batchInfoBuffer = register(BufferObject.Immutable()).apply {
        label("HWMS.BatchInfoBuffer")
    }
    private val bufferBinding =
        ShaderBindingSpecs.Buffer("BatchInfoBuffer", batchInfoBuffer, BufferTarget.ShaderStorage)

//    private val cullTestCommandBuffer = register(BufferObject.Immutable()).apply {
//        label("HWMS.CullTestCommandBuffer")
//    }

    override suspend fun update(chunks: List<TerrainMeshData.ChunkLayerInfo>) {
        Arr.malloc(chunks.size * sizeof(MeshShaderBatchInfo)).use { batchInfoUploadBuffer ->
            val quadCountGlobal = AtomicInteger(0)

            coroutineScope {
                ParallelUtils.splitListIndex(
                    chunks.size,
                    blockForEach = { start, end ->
                        launch {
                            var quadCountLocal = 0
                            for (i in start until end) {
                                val chunkInfo = chunks[i]
                                val chunkOffsetEncoded = chunkInfo.offsetEncoded

                                val struct =
                                    MeshShaderBatchInfo(batchInfoUploadBuffer.ptr + i * sizeof(MeshShaderBatchInfo))
                                struct.chunkIndex = chunkInfo.chunkIndex
                                struct.quadStartIndex = chunkInfo.startIndex
                                struct.quadCount = chunkInfo.quadCount

//                                 val ctcBaseOffset = 16L
//                                val ctcLen = sizeof(CullTestCommand)
//                                cullTestCommandUploadBuffer.ensureCapacity(
//                                    ctcBaseOffset + (batchCount + 1L) * ctcLen,
//                                    false
//                                )
//                                val ctcStruct =
//                                    CullTestCommand(cullTestCommandUploadBuffer.ptr + ctcBaseOffset + batchCount * ctcLen)
//                                ctcStruct.chunkIndex = chunkInfo.chunkIndex
//                                ctcStruct.chunkOffsetEncoded = chunkOffsetEncoded

                                quadCountLocal += chunkInfo.quadCount
                            }
                            quadCountGlobal.addAndGet(quadCountLocal)
                        }
                    }
                )
            }

            batchCount = chunks.size
            quadCount = quadCountGlobal.get()
            chunkCount = chunks.size

            if (batchCount == 0) return

            withContext(scene.gameInstance.mainContext) {
                val batchInfoLen = batchCount * sizeof(MeshShaderBatchInfo)
                if (batchInfoBuffer.size < batchInfoLen) {
                    batchInfoBuffer.destroy()
                    batchInfoBuffer.allocate(batchInfoLen, batchInfoUploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
                } else {
                    batchInfoBuffer.invalidate()
                    batchInfoBuffer.upload(0L, batchInfoLen, batchInfoUploadBuffer.ptr)
                }
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
                buffer(renderer.bufferBindings)
            }
            uniform4f(
                "uval_viewportSize",
                scene.camera.screenSize.x.toFloat(), scene.camera.screenSize.y.toFloat(),
                1.0f / scene.camera.screenSize.x.toFloat(), 1.0f / scene.camera.screenSize.y.toFloat()
            )
        }
    }
}