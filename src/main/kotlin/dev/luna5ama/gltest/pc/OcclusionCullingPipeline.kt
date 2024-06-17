package dev.luna5ama.gltest.pc

import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.structs.CullMSCommandHeader
import dev.luna5ama.gltest.structs.DispatchIndirectCommand
import dev.luna5ama.gltest.structs.DrawElementsIndirectCommand
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.VertexArrayObject
import dev.luna5ama.kmogus.*

abstract class OcclusionCullingPipeline(
    protected val scene: PointCloudScene,
    protected val renderer: TerrainGeometryRenderer
) : IGLObjContainer by IGLObjContainer.Impl() {
    abstract val ctcBufferBaseOffset: Long

    abstract fun setupIndirect(ptr: Ptr, count: Int)

    abstract fun draw(cullTestCommandBuffer: BufferObject)

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

        private val depthRangeBlitShader = register(
            ShaderProgram(
                ShaderSource.Vert("pc/Blit.vert"),
                ShaderSource.Frag("pc/geometry/DepthRangeBlit.frag")
            )
        ).apply {
            label("DepthRangeBlitShader")
        }

        override val ctcBufferBaseOffset: Long
            get() = 16L

        override fun setupIndirect(ptr: Ptr, count: Int) {
            val struct = CullMSCommandHeader(ptr)
            struct.first = 0
            struct.count = (count + 7) / 8
            struct.chunkCount = count
        }

        override fun draw(cullTestCommandBuffer: BufferObject) {
            scene.vbuffer.nDepthFbo.bind()
            glDisable(GL_DEPTH_CLAMP)
            glDisable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_ALWAYS)
            glDepthMask(true)
            glColorMask(true, true, true, true)

            depthRangeBlitShader.bind()
            depthRangeBlitShader.applyBinding {
                sampler(scene.vbuffer.samplerBindings)
                buffer(scene.camera.uboBinding)
            }
            scene.basic.drawQuad()

            glDisable(GL_DEPTH_CLAMP)
            glDisable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_LEQUAL)
            glDepthMask(false)
            glColorMask(false, false, false, false)

            cullShader.bind()
            cullShader.uniform3f(
                "uval_originOffset",
                (PointCloudScene.originX - scene.camera.pos.x - 0.5).toFloat(),
                (PointCloudScene.originY - scene.camera.pos.y - 1.62).toFloat(),
                (PointCloudScene.originZ - scene.camera.pos.z - 0.5).toFloat()
            )
            cullShader.applyBinding {
                buffer(renderer.visibleBufferBinding)
                buffer("CullTestCommandBuffer", cullTestCommandBuffer, BufferTarget.ShaderStorage)
                buffer(scene.camera.uboBinding)
            }
            cullTestCommandBuffer.bind(GL_DRAW_INDIRECT_BUFFER)
            glDrawMeshTasksIndirectNV(0L)
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

        private val depthRangeBlitShader = register(
            ShaderProgram(
                ShaderSource.Vert("pc/Blit.vert"),
                ShaderSource.Frag("pc/geometry/DepthRangeBlit.frag")
            )
        ).apply {
            label("DepthRangeBlitShader")
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

        override fun draw(cullTestCommandBuffer: BufferObject) {
            scene.vbuffer.nDepthFbo.bind()
            glDisable(GL_DEPTH_CLAMP)
            glDisable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_ALWAYS)
            glDepthMask(true)
            glColorMask(true, true, true, true)

            depthRangeBlitShader.bind()
            depthRangeBlitShader.applyBinding {
                sampler(scene.vbuffer.samplerBindings)
                buffer(scene.camera.uboBinding)
            }
            scene.basic.drawQuad()

            glDisable(GL_DEPTH_CLAMP)
            glDisable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_LEQUAL)
            glDepthMask(false)
            glColorMask(false, false, false, false)

            vao.bind()
            cullShader.bind()
            cullShader.uniform3f(
                "uval_originOffset",
                (PointCloudScene.originX - scene.camera.pos.x - 0.5).toFloat(),
                (PointCloudScene.originY - scene.camera.pos.y - 1.62).toFloat(),
                (PointCloudScene.originZ - scene.camera.pos.z - 0.5).toFloat()
            )
            cullShader.applyBinding {
                buffer(renderer.visibleBufferBinding)
                buffer("CullTestCommandBuffer", cullTestCommandBuffer, BufferTarget.ShaderStorage)
                buffer(scene.camera.uboBinding)
            }
            cullTestCommandBuffer.bind(GL_DRAW_INDIRECT_BUFFER)
            glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_BYTE, 0L)
        }
    }

    class SWRaster(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
        OcclusionCullingPipeline(scene, renderer) {
        private val shader = register(ShaderProgram(
            ShaderSource.Comp("pc/geometry/CullTest.comp") {
                define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
            }
        )).apply {
            label("CullSWRaster.Shader")
        }

        private val tempBuffer = register(BufferObject.Immutable()).apply {
            label("CullSWRaster.TempBuffer")
        }

        private val tempBufferBinding = ShaderBindingSpecs.Buffer("TempBuffer", tempBuffer, BufferTarget.ShaderStorage)

        init {
            tempBuffer.allocate(60000 * 6L * 4L, Ptr.NULL, 0)
        }

        override val ctcBufferBaseOffset: Long
            get() = 16L

        private var count = 0

        override fun setupIndirect(ptr: Ptr, count: Int) {
            val struct = DispatchIndirectCommand(ptr)
            struct.num_groups_x = count
            struct.num_groups_y = 6
            struct.num_groups_z = 1
            this.count = count
        }

        private val printTimer = TickTimer()

        override fun draw(cullTestCommandBuffer: BufferObject) {
            shader.bind()
            shader.applyBinding {
                sampler(scene.vbuffer.samplerBindings)
                buffer(renderer.visibleBufferBinding)
                buffer(scene.terrainMeshData.bufferBindings)
                buffer(scene.camera.uboBinding)
                buffer("CullTestCommandBuffer", cullTestCommandBuffer, BufferTarget.ShaderStorage)
                buffer(tempBufferBinding)
            }
            shader.uniform3f(
                "uval_originOffset",
                (PointCloudScene.originX - scene.camera.pos.x - 0.5).toFloat(),
                (PointCloudScene.originY - scene.camera.pos.y - 1.62).toFloat(),
                (PointCloudScene.originZ - scene.camera.pos.z - 0.5).toFloat()
            )

            shader.uniform4f(
                "uval_viewportSize",
                scene.camera.screenSize.x.toFloat(), scene.camera.screenSize.y.toFloat(),
                1.0f / scene.camera.screenSize.x.toFloat(), 1.0f / scene.camera.screenSize.y.toFloat()
            )
            shader.uniform2i("uval_viewportSizeI", scene.camera.screenSize.x, scene.camera.screenSize.y)
            cullTestCommandBuffer.bind(GL_DISPATCH_INDIRECT_BUFFER)
            glDispatchComputeIndirect(0L)
//            glDispatchCompute(4, 3, 1)

            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT)

            if (printTimer.tickAndReset(3000)) {
                val intArray = Arr.malloc(count * 6L * 4L).use {
                    glGetNamedBufferSubData(tempBuffer.id, 0, it.len, it.ptr)
                    val array = IntArray(count * 6)
                    memcpy(it.ptr, 0L, array, 0, it.len)
                    array
                }
                println(intArray.filter { it != 0 }.min())
                println(intArray.average())
                println(intArray.max())
            }
        }
    }
}