package dev.luna5ama.gltest.pc

import dev.fastmc.common.ParallelUtils
import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.VisibilityState
import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.structs.MeshShaderBatchInfo
import dev.luna5ama.gltest.structs.sizeof
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class HWMeshShaderPipeline(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
    TerrainDrawPipeline("HWMS", scene, renderer) {
    private val drawShaders = object : ShaderProgram.Variants<VisibilityState>() {
        override fun create(key: VisibilityState): ShaderProgram {
            return register(ShaderProgram(
                ShaderSource.Task("pc/geometry/hw/HWMS.task") {
                    define(key.defineName)
                    define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
                },
                ShaderSource.Mesh("pc/geometry/hw/HWMS.mesh") {
                    if (renderer.debugQuadCount) {
                        define("DEBUG_QUAD_COUNT")
                    }
                },
                ShaderSource.Frag("pc/geometry/hw/HW.frag") {
                    define("MESH_SHADER")
                }
            )).apply {
                label = "HWMS.DrawShader-$key"
            }
        }
    }
    private val batchInfoBuffer = register(BufferObject.Immutable()).apply {
        label = "HWMS.BatchInfoBuffer"
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

        if (!scene.gameInstance.toggles[3]) {
            drawPass(VisibilityState.LAST_VISIBLE)

            renderer.cullPipeline.depthBuffer.hwDepthBlit()
            glViewport(0, 0, scene.camera.screenWidth / 8, scene.camera.screenHeight / 8)
            renderer.cullPipeline.draw(ctcBuffer, chunkCount)

            drawPass(VisibilityState.NEWLY_VISIBLE)
        } else {
            drawPass(VisibilityState.CURRENT_VISIBLE)
        }
    }

    private val printTimer = TickTimer()

    private fun drawPass(state: VisibilityState) {
        glDebugGroup("DrawPass-$state") {
            if (renderer.debugQuadCount) {
                renderer.tempBuffer.clear(GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
            }

            scene.vbuffer.fbo.bind()
            glViewport(0, 0, scene.vbuffer.fbo.width, scene.vbuffer.fbo.height)
            glEnable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_GEQUAL)
            glDepthMask(true)
            glColorMask(true, true, true, true)

            val drawShader = drawShaders[state]
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

            if (renderer.debugQuadCount && printTimer.tickAndReset(3000)) {
                MemoryStack {
                    val ptr = malloc(renderer.tempBuffer.size).ptr
                    glGetNamedBufferSubData(renderer.tempBuffer.id, 0, renderer.tempBuffer.size, ptr)
                    println("HWMS: Quad Count: %,d".format(ptr.getInt(0)))
                    println("HWMS: Vertex Count: %,d".format(ptr.getInt(4)))
                }
            }
        }
    }
}