package dev.luna5ama.gltest.pc

import dev.fastmc.common.TickTimer
import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.structs.CullMSCommandHeader
import dev.luna5ama.gltest.structs.DispatchIndirectCommand
import dev.luna5ama.gltest.structs.DrawElementsIndirectCommand
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.glwrapper.objects.VertexArrayObject
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr
import dev.luna5ama.kmogus.byteLength
import dev.luna5ama.kmogus.memcpy
import kotlin.math.max

abstract class OcclusionCullingPipeline(
    protected val scene: PointCloudScene,
    protected val renderer: TerrainGeometryRenderer
) : IGLObjContainer by IGLObjContainer.Impl() {
    abstract val ctcBufferBaseOffset: Long

    open fun update() {}

    abstract fun setupIndirect(ptr: Ptr, count: Int)

    abstract fun draw(ctcBuffer: BufferObject, count: Int)

    inner class DownScaleDepthBuffer : IGLObjContainer by IGLObjContainer.Impl() {
        private val swDepthBlitShader = register(
            ShaderProgram(
                ShaderSource.Comp("pc/geometry/culling/SWDepthBlit.comp")
            )
        ).apply {
            label = "CullVS.DepthBlitSW"
        }

        private val hwDepthBlitShader = register(
            ShaderProgram(
                ShaderSource.Comp("pc/geometry/culling/HWDepthBlit.comp")
            )
        ).apply {
            label = "CullVS.DepthBlitHW"
        }

        private val float2DepthBlitShader = register(
            ShaderProgram(
                ShaderSource.Vert("pc/Blit.vert"),
                ShaderSource.Frag("pc/geometry/culling/Float2DepthBlit.frag")
            )
        ).apply {
            label = "CullVS.DepthBlitSW"
        }

        private val cullTempTex = register(TextureObject.Texture2D()).apply {
            label = "CullVS.CullTempTex"
        }

        private val cullDepthTex = register(TextureObject.Texture2D()).apply {
            label = "CullVS.CullDepthTex"
        }

        val fbo = register(FramebufferObject()).apply {
            label = "VBuffer.EmptyFBO"
        }

        private val imageBindings = listOf(
            ShaderBindingSpecs.Image("uimg_cullTempTex", cullTempTex)
        )
        private val samplerBindings = listOf(
            ShaderBindingSpecs.Sampler("usam_cullTempTex", cullTempTex),
            ShaderBindingSpecs.Sampler("usam_cullDepthTex", cullDepthTex)
        )

        fun update() {
            val width = scene.camera.screenWidth / 4
            val height = scene.camera.screenHeight / 4
            if (cullDepthTex.width != width || cullDepthTex.height != height) {
                cullTempTex.apply {
                    destroy()
                    allocate(1, ImageFormat.R32_F, width, height)
                }

                cullDepthTex.apply {
                    destroy()
                    allocate(1, ImageFormat.Depth32F, width, height)
                }


                fbo.apply {
                    destroyFbo()
                    attach(cullDepthTex, GL_DEPTH_ATTACHMENT)
                    check()
                }
            }
            cullTempTex.clearImage()
            cullDepthTex.clearImage()
        }

        fun swDepthBlit() {
            glDebugGroup("CullPass-SWDepthBlit") {
                swDepthBlitShader.bind()
                swDepthBlitShader.applyBinding {
                    buffer(scene.vbuffer.bufferBinding)
                    image(imageBindings)
                }
                val xClusters = (scene.camera.screenWidth + 3) / 4
                val yClusters = (scene.camera.screenHeight + 3) / 4
                swDepthBlitShader.uniform2i("uval_viewportSizeI", scene.camera.screenSize.x, scene.camera.screenSize.y)
                swDepthBlitShader.uniform2i("uval_clusterCount", xClusters, yClusters)
                val clusterPerWorkgroup = 128 / 16
                val totalClusters = xClusters * yClusters
                val numGroups = (totalClusters + clusterPerWorkgroup - 1) / clusterPerWorkgroup
                glDispatchCompute(numGroups, 1, 1)
                glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

                glEnable(GL_DEPTH_TEST)
                glDepthFunc(GL_ALWAYS)
                glDisable(GL_DEPTH_CLAMP)
                glViewport(0, 0, fbo.width, fbo.height)

                float2DepthBlitShader.bind()
                float2DepthBlitShader.applyBinding {
                    sampler(samplerBindings)
                    buffer(scene.camera.uboBinding)
                }
                fbo.bind()
                scene.basic.drawQuad()
            }
        }

        fun hwDepthBlit() {
            glDebugGroup("CullPass-HWDepthBlit") {
                hwDepthBlitShader.bind()
                hwDepthBlitShader.applyBinding {
                    buffer(scene.vbuffer.bufferBinding)
                    sampler(scene.vbuffer.samplerBindings)
                    image(imageBindings)
                }
                val xClusters = (scene.camera.screenWidth + 7) / 8
                val yClusters = (scene.camera.screenHeight + 7) / 8
                hwDepthBlitShader.uniform2i("uval_viewportSizeI", scene.camera.screenSize.x, scene.camera.screenSize.y)
                hwDepthBlitShader.uniform4f(
                    "uval_viewportSize",
                    scene.camera.screenSize.x.toFloat(), scene.camera.screenSize.y.toFloat(),
                    1.0f / scene.camera.screenSize.x.toFloat(), 1.0f / scene.camera.screenSize.y.toFloat()
                )
                hwDepthBlitShader.uniform2i("uval_clusterCount", xClusters, yClusters)
                val clusterPerWorkgroup = 128 / 16
                val totalClusters = xClusters * yClusters
                val numGroups = (totalClusters + clusterPerWorkgroup - 1) / clusterPerWorkgroup
                glDispatchCompute(numGroups, 1, 1)
                glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

                glEnable(GL_DEPTH_TEST)
                glDepthFunc(GL_ALWAYS)
                glDisable(GL_DEPTH_CLAMP)
                glViewport(0, 0, fbo.width / 2, fbo.height / 2)

                float2DepthBlitShader.bind()
                float2DepthBlitShader.applyBinding {
                    sampler(samplerBindings)
                    buffer(scene.camera.uboBinding)
                }
                fbo.bind()
                scene.basic.drawQuad()
            }
        }
    }

    class MeshShader(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
        OcclusionCullingPipeline(scene, renderer) {
        private val cullShader = register(
            ShaderProgram(
                ShaderSource.Mesh("pc/geometry/culling/CullTest.mesh") {
                    define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
                },
                ShaderSource.Frag("pc/geometry/culling/CullTest.frag") {
                    define("MESH_SHADER")
                    define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
                }
            )
        ).apply {
            label = "CullMSShader"
        }

        override val ctcBufferBaseOffset: Long
            get() = 16L

        val depthBuffer = register(DownScaleDepthBuffer())

        override fun update() {
            super.update()
            depthBuffer.update()
        }

        override fun setupIndirect(ptr: Ptr, count: Int) {
            val struct = CullMSCommandHeader(ptr)
            struct.first = 0
            struct.count = (count + 7) / 8
            struct.chunkCount = count
        }

        override fun draw(ctcBuffer: BufferObject, count: Int) {
            glDebugGroup("CullPass") {
                depthBuffer.fbo.bind()
                cullShader.bind()
                cullShader.applyBinding {
                    buffer(renderer.bufferBindings)
                    buffer("CullTestCommandBuffer", ctcBuffer, BufferTarget.ShaderStorage)
                    buffer(scene.camera.uboBinding)
                }


                glDisable(GL_DEPTH_CLAMP)
                glEnable(GL_CULL_FACE)
                glCullFace(GL_BACK)
                glEnable(GL_DEPTH_TEST)
                glDepthFunc(GL_GEQUAL)
                glDepthMask(false)
                glColorMask(false, false, false, false)


                ctcBuffer.bind(GL_DRAW_INDIRECT_BUFFER)
                glDrawMeshTasksIndirectNV(0L)
            }
        }
    }

    class VertexShader(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
        OcclusionCullingPipeline(scene, renderer) {
        private val vao = register(VertexArrayObject()).apply {
            label = "CullVS.VAO"
        }

        private val cullShader = register(
            ShaderProgram(
                ShaderSource.Vert("pc/geometry/culling/CullTest.vert") {
                    define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
                },
                ShaderSource.Frag("pc/geometry/culling/CullTest.frag") {
                    define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
                }
            )
        ).apply {
            label = "CullVSShader"
        }

        private val cubeElementBuffer = register(BufferObject.Immutable()).apply {
            label = "CullVS.CubeElementBuffer"
        }

        val depthBuffer = register(DownScaleDepthBuffer())

        init {
            val indices = byteArrayOf(
                // Left face
                0, 2, 4, 4, 2, 6,
                // Right face
                1, 5, 3, 3, 5, 7,
                // Bottom face
                0, 1, 2, 2, 1, 3,
                // Top face
                4, 6, 5, 5, 6, 7,
                // Back face
                1, 0, 5, 5, 0, 4,
                // Front face
                3, 7, 2, 2, 7, 6
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

        override fun update() {
            super.update()
            depthBuffer.update()
        }

        override fun setupIndirect(ptr: Ptr, count: Int) {
            val struct = DrawElementsIndirectCommand(ptr)
            struct.count = 36
            struct.instanceCount = count
            struct.firstIndex = 0
            struct.baseVertex = 0
            struct.baseInstance = 0
        }

        override fun draw(ctcBuffer: BufferObject, count: Int) {
            glDebugGroup("CullPass") {
                cullShader.bind()
                cullShader.applyBinding {
                    buffer(renderer.bufferBindings)
                    buffer("CullTestCommandBuffer", ctcBuffer, BufferTarget.ShaderStorage)
                    buffer(scene.camera.uboBinding)
                }
                vao.bind()
                depthBuffer.fbo.bind()

                glDisable(GL_DEPTH_CLAMP)
                glEnable(GL_CULL_FACE)
                glCullFace(GL_BACK)
                glEnable(GL_DEPTH_TEST)
                glDepthFunc(GL_GEQUAL)
                glDepthMask(false)
                glColorMask(false, false, false, false)

                ctcBuffer.bind(GL_DRAW_INDIRECT_BUFFER)
                glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)
                glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_BYTE, 0L)
            }
        }
    }

    class SWRaster(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
        OcclusionCullingPipeline(scene, renderer) {
        private val subgroupSizeOptimal = max(glGetInteger(GL_SUBGROUP_SIZE_KHR), 16)

        val ctqIndirectSetup = register(ShaderProgram(ShaderSource.Comp("pc/geometry/culling/CTQIndirectSetup.comp") {
            define("N_GROUPS", 128 / subgroupSizeOptimal)
        })).apply {
            label = "CullSWRaster.CTQIndirectSetup"
        }

        val setupShader = register(ShaderProgram(ShaderSource.Comp("pc/geometry/culling/CullTestSetup.comp"))).apply {
            label = "CullSWRaster.SetupShader"
        }
        val cullTestShader = register(ShaderProgram(
            ShaderSource.Comp("pc/geometry/culling/CullTest.comp") {
                define("SUBGROUP_SIZE", subgroupSizeOptimal)
                define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
            }
        )).apply {
            label = "CullSWRaster.CullTestShader"
        }

        private val ctqBuffer = register(BufferObject.Immutable()).apply {
            label = "CullSWRaster.CullTestQuadBuffer"
        }

        private val ctqBufferBinding =
            ShaderBindingSpecs.Buffer("CullTestQuadBuffer", ctqBuffer, BufferTarget.ShaderStorage)

        override val ctcBufferBaseOffset: Long
            get() = 4 * 4L

        private var count = 0

        override fun setupIndirect(ptr: Ptr, count: Int) {
            val struct = DispatchIndirectCommand(ptr)
            struct.num_groups_x = (count * 6 + 127) / 128
            struct.num_groups_y = 1
            struct.num_groups_z = 1
            ptr.setInt(12L, count)
            this.count = count
        }

        private val printTimer = TickTimer()

        override fun draw(ctcBuffer: BufferObject, count: Int) {
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)
            val ctqLen = 16L + count * 6L * 128L
            if (ctqBuffer.size < ctqLen) {
                ctqBuffer.destroy()
                ctqBuffer.allocate(ctqLen, 0)
            }

            glDebugGroup("CullTestSetup") {
                setupShader.bind()
                setupShader.applyBinding {
                    buffer(scene.camera.uboBinding)
                    buffer("CullTestCommandBuffer", ctcBuffer, BufferTarget.ShaderStorage)
                    buffer(renderer.bufferBindings)
                    buffer(ctqBufferBinding)
                }

                setupShader.uniform4f(
                    "uval_viewportSize",
                    scene.camera.screenSize.x.toFloat(), scene.camera.screenSize.y.toFloat(),
                    1.0f / scene.camera.screenSize.x.toFloat(), 1.0f / scene.camera.screenSize.y.toFloat()
                )
                ctcBuffer.bind(GL_DISPATCH_INDIRECT_BUFFER)
                glDispatchComputeIndirect(0L)
            }

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

            ctqIndirectSetup.bind()
            ctqIndirectSetup.applyBinding {
                buffer(ctqBufferBinding)
            }
            glDispatchCompute(1, 1, 1)

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)

            glDebugGroup("CullTest") {
                cullTestShader.bind()
                cullTestShader.applyBinding {
                    buffer(scene.vbuffer.bufferBinding)
                    buffer(renderer.bufferBindings)
                    buffer(ctqBufferBinding)
                }
                cullTestShader.uniform2i("uval_viewportSizeI", scene.camera.screenSize.x, scene.camera.screenSize.y)
                ctqBuffer.bind(GL_DISPATCH_INDIRECT_BUFFER)
                glDispatchComputeIndirect(0L)
            }
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

//            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT or GL_SHADER_STORAGE_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)
//            if (printTimer.tickAndReset(3000)) {
//                MemoryStack {
//                    val arr = malloc(4L * 4L)
//                    val ptr = arr.ptr
//                    glGetNamedBufferSubData(ctqBuffer.id, 0L, arr.len, ptr)
//                    println("CTQ: ${ptr.getInt(0)}, ${ptr.getInt(4)}, ${ptr.getInt(8)}, ${ptr.getInt(12)}")
//                    glGetNamedBufferSubData(ctcBuffer.id, 0L, arr.len, ptr)
//                    println("CTC: ${ptr.getInt(0)}, ${ptr.getInt(4)}, ${ptr.getInt(8)}, ${ptr.getInt(12)}")
//                }
//
//                val visibleBuffer = Arr.malloc(renderer.visibleBufferStateLen1).use {
//                    glGetNamedBufferSubData(renderer.visibleBuffer.id, 4, it.len, it.ptr)
//                    val array = ByteArray((renderer.visibleBufferStateLen1 * 4).toInt())
//                    memcpy(it.ptr, 0L, array, 0, it.len)
//                    array
//                }
//
//                println(visibleBuffer.count { it != 0.toByte() })
//            }
        }
    }
}