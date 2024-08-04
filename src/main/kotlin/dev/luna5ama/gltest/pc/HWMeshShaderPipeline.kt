package dev.luna5ama.gltest.pc

import dev.fastmc.common.ParallelUtils
import dev.luna5ama.gltest.glDebugGroup
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class HWMeshShaderPipeline(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
    TerrainDrawPipeline("HWMS", scene, renderer) {
    private val drawShaders = List(4) { register(DrawShader(it)) }
    private val batchInfoBuffer = register(BufferObject.Immutable()).apply {
        label("HWMS.BatchInfoBuffer")
    }
    private val bufferBinding =
        ShaderBindingSpecs.Buffer("BatchInfoBuffer", batchInfoBuffer, BufferTarget.ShaderStorage)

    override suspend fun update(chunks: List<TerrainMeshData.ChunkLayerInfo>) {
        val batchStructSize = sizeof(MeshShaderBatchInfo)
        Arr.malloc(chunks.size * batchStructSize).use { batchInfoUploadBuffer ->
                coroutineScope {
                    launch {
                        updateCtcBuffer(chunks)
                    }

                    val quadCountGlobal = AtomicInteger(0)

                    coroutineScope {
                        ParallelUtils.splitListIndex(
                            chunks.size,
                            blockForEach = { start, end ->
                                launch {
                                    var quadCountLocal = 0
                                    for (i in start until end) {
                                        val chunkInfo = chunks[i]

                                        val struct = MeshShaderBatchInfo(batchInfoUploadBuffer.ptr + i * batchStructSize)
                                        struct.chunkIndex = chunkInfo.chunkIndex
                                        struct.quadStartIndex = chunkInfo.startIndex
                                        struct.quadCount = chunkInfo.quadCount

                                        quadCountLocal += chunkInfo.quadCount
                                    }
                                    quadCountGlobal.addAndGet(quadCountLocal)
                                }
                            }
                        )
                    }

                    batchCount = chunks.size
                    quadCount = quadCountGlobal.get()
                    if (batchCount == 0) return@coroutineScope

                    withContext(scene.gameInstance.mainContext) {
                        val batchInfoLen = batchCount * batchStructSize
                        if (batchInfoBuffer.size < batchInfoLen) {
                            batchInfoBuffer.destroy()
                            batchInfoBuffer.allocate(batchInfoLen, batchInfoUploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
                        } else {
                            batchInfoBuffer.invalidate()
                            batchInfoBuffer.upload(0L, batchInfoLen, batchInfoUploadBuffer.ptr)
                        }
                    }
                }
            }
    }

    override fun draw() {
        if (batchCount == 0) return

        if (!scene.gameInstance.toggle3) {
            drawPass(1)

            renderer.cullPipeline.depthBuffer.hwDepthBlit()
            glViewport(0, 0, scene.camera.screenWidth / 8, scene.camera.screenHeight / 8)
            renderer.cullPipeline.draw(ctcBuffer, chunkCount)

            glEnable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_GEQUAL)
            glDepthMask(true)
            glColorMask(true, true, true, true)

            drawPass(2)
        } else {
            drawPass(3)
        }
    }

    private fun drawPass(index: Int) {
        glDebugGroup("DrawPass-$index") {
            scene.vbuffer.fbo.bind()
            glViewport(0, 0, scene.vbuffer.fbo.width, scene.vbuffer.fbo.height)
            glEnable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_GEQUAL)
            glDepthMask(true)
            glColorMask(true, true, true, true)

            val drawShader = drawShaders[index]
            drawShader.bind()
            drawShader.applyBinding {
                sampler(scene.textures.blockAlbedo)
                buffer(scene.camera.uboBinding)
                buffer(bufferBinding)
                buffer(scene.terrainMeshData.bufferBindings)
                buffer(renderer.bufferBindings)
            }
            drawShader.uniform4f(
                "uval_viewportSize",
                scene.camera.screenSize.x.toFloat(), scene.camera.screenSize.y.toFloat(),
                1.0f / scene.camera.screenSize.x.toFloat(), 1.0f / scene.camera.screenSize.y.toFloat()
            )
            scene.vbuffer.fbo.bind()
            glDrawMeshTasksNV(0, batchCount)
        }
    }

    inner class DrawShader(cullPass: Int) : ShaderProgram(
        ShaderSource.Task("pc/geometry/hw/HWMS.task") {
            define("CULL_PASS", cullPass)
            define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
        },
        ShaderSource.Mesh("pc/geometry/hw/HWMS.mesh"),
        ShaderSource.Frag("pc/geometry/hw/HW.frag") {
            define("MESH_SHADER")
        }
    ) {
        init {
            label("HWMS.DrawShader-$cullPass")
        }
    }
}