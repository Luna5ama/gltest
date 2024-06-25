package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.structs.CullMSCommandHeader
import dev.luna5ama.gltest.structs.DrawElementsIndirectCommand
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.VertexArrayObject
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr
import dev.luna5ama.kmogus.byteLength
import dev.luna5ama.kmogus.memcpy

abstract class OcclusionCullingPipeline(
    protected val scene: PointCloudScene,
    protected val renderer: TerrainGeometryRenderer
) : IGLObjContainer by IGLObjContainer.Impl() {
    abstract val ctcBufferBaseOffset: Long

    abstract fun setupIndirect(ptr: Ptr, count: Int)

    abstract fun draw(ctcBuffer: BufferObject)

    class MeshShader(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
        OcclusionCullingPipeline(scene, renderer) {
        private val cullShader = register(
            ShaderProgram(
                ShaderSource.Mesh("pc/geometry/CullMS.mesh") {
                    define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
                },
                ShaderSource.Frag("pc/geometry/Cull.frag") {
                    define("MESH_SHADER")
                    define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
                }
            )
        ).apply {
            label("CullMSShader")
        }

        override val ctcBufferBaseOffset: Long
            get() = 16L

        override fun setupIndirect(ptr: Ptr, count: Int) {
            val struct = CullMSCommandHeader(ptr)
            struct.first = 0
            struct.count = (count + 7) / 8
            struct.chunkCount = count
        }

        override fun draw(ctcBuffer: BufferObject) {
            glDebugGroup("CullPass") {
                glDisable(GL_DEPTH_CLAMP)
                glEnable(GL_CULL_FACE)
                glCullFace(GL_BACK)
                glEnable(GL_DEPTH_TEST)
                glDepthFunc(GL_GEQUAL)
                glDepthMask(false)
                glColorMask(false, false, false, false)

                cullShader.bind()
                cullShader.applyBinding {
                    buffer(renderer.bufferBindings)
                    buffer("CullTestCommandBuffer", ctcBuffer, BufferTarget.ShaderStorage)
                    buffer(scene.camera.uboBinding)
                }
                ctcBuffer.bind(GL_DRAW_INDIRECT_BUFFER)
                glDrawMeshTasksIndirectNV(0L)
            }
        }
    }

    class VertexShader(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
        OcclusionCullingPipeline(scene, renderer) {
        private val vao = register(VertexArrayObject()).apply {
            label("CullVS.VAO")
        }

        private val cullShader = register(
            ShaderProgram(
                ShaderSource.Vert("pc/geometry/CullTest.vert") {
                    define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
                },
                ShaderSource.Frag("pc/geometry/Cull.frag") {
                    define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
                }
            )
        ).apply {
            label("CullVSShader")
        }

        private val cubeElementBuffer = register(BufferObject.Immutable()).apply {
            label("CullVS.CubeElementBuffer")
        }

        init {
            val indices = byteArrayOf(
                // Bottom face
                0, 1, 2,
                2, 1, 3,
                // Top face
                4, 5, 6,
                6, 5, 7,
                // Front face
                2, 3, 6,
                6, 3, 7,
                // Back face
                0, 1, 4,
                4, 1, 5,
                // Left face
                0, 2, 4,
                4, 2, 6,
                // Right face
                1, 3, 5,
                5, 3, 7
            )

            MemoryStack {
                val arr = malloc(indices.byteLength)
                memcpy(indices, 0, arr.ptr, 0, indices.byteLength)
                cubeElementBuffer.allocate(arr.len, arr.ptr, 0)
            }

            vao.attachEbo(cubeElementBuffer)
        }

        override val ctcBufferBaseOffset: Long
            get() = 32L

        override fun setupIndirect(ptr: Ptr, count: Int) {
            val struct = DrawElementsIndirectCommand(ptr)
            struct.count = 36
            struct.instanceCount = count
            struct.firstIndex = 0
            struct.baseVertex = 0
            struct.baseInstance = 0
        }

        override fun draw(ctcBuffer: BufferObject) {
            glDebugGroup("CullPass") {
                glDisable(GL_DEPTH_CLAMP)
                glDisable(GL_CULL_FACE)
                glCullFace(GL_FRONT)
                glEnable(GL_DEPTH_TEST)
                glDepthFunc(GL_GEQUAL)
                glDepthMask(false)
                glColorMask(false, false, false, false)

                vao.bind()
                cullShader.bind()
                cullShader.applyBinding {
                    buffer(renderer.bufferBindings)
                    buffer("CullTestCommandBuffer", ctcBuffer, BufferTarget.ShaderStorage)
                    buffer(scene.camera.uboBinding)
                }
                ctcBuffer.bind(GL_DRAW_INDIRECT_BUFFER)
                glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)
                glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_BYTE, 0L)
            }
        }
    }
}