package dev.luna5ama.gltest.pc

import dev.fastmc.common.TickTimer
import dev.luna5ama.glc2vk.capture.captureGlDispatchComputeIndirect
import dev.luna5ama.glc2vk.common.ShaderSourceContext
import dev.luna5ama.glc2vk.common.patchShaderForVulkan
import dev.luna5ama.gltest.VisibilityState
import dev.luna5ama.gltest.concurrent.LWWSP
import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.structs.QuadBatchInfo
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
import dev.luna5ama.kmogus.ensureCapacity
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.io.path.Path
import kotlin.math.max
import kotlin.math.min

class SWQuadPipeline(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
    TerrainDrawPipeline("SWQuad", scene, renderer) {
    private val workGroupSize = renderer.threadBatchSize
    private val quadsPerWorkGroup = workGroupSize * 4

    private val quadBatchInfo = register(BufferObject.Immutable()).apply {
        label = "SWQuad.QuadBatchInfo"
    }

    private val batchIndices = register(BufferObject.Immutable()).apply {
        label = "SWQuad.BatchIndices"
    }

    private val batchFilterFlag = register(BufferObject.Immutable()).apply {
        label = "SWQuad.BatchFilterFlag"
    }

    private val scanBuffer = register(BufferObject.Immutable()).apply {
        label = "SWQuad.ScanBuffer"
    }

    private val bufferBindings = listOf(
        ShaderBindingSpecs.Buffer("QuadBatchInfoBuffer", quadBatchInfo, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("BatchIndicesIndirect", batchIndices.createView(0L, 16L), BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("BatchIndicesData", batchIndices.createView(16L), BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("BatchFilterFlag", batchFilterFlag, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("ScanBuffer", scanBuffer, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("ScanBufferCount", scanBuffer.createView(0L, 16L), BufferTarget.ShaderStorage),
    )

    private val clearShader = register(ShaderProgram(ShaderSource.Comp("pc/geometry/swquad/Clear.comp"))).apply {
        label = "SWQuad.ClearShader"
    }

    private val filterShader = object : ShaderProgram.Variants<VisibilityState>() {
        override fun create(key: VisibilityState): ShaderProgram {
            return register(
                ShaderProgram(
                    ShaderSource.Comp("pc/geometry/swquad/BatchFilter.comp") {
                        define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
                        define(key.defineName)
                    }
                )).apply {
                label = "SWQuad.SWQuadBatchFilter-$key"
            }
        }
    }

    private val batchIndicesShader = register(
        ShaderProgram(
            ShaderSource.Comp("pc/geometry/swquad/BatchIndices.comp") {
                define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
                define("SHARED_SUM_SIZE", run {
                    val subgroupSize = glGetInteger(GL_SUBGROUP_SIZE_KHR)
                    val subgroupCount = scene.gpuScan.workGroupSize / subgroupSize
                    max(subgroupCount, subgroupSize)
                })
            }
        )).apply {
        label = "SWQuad.BatchIndicesShader"
    }

    val drawShaderSource = ShaderSource.Comp("pc/geometry/swquad/Draw.comp") {
        define("WORKGROUP_SIZE", workGroupSize)
        define("QUADS_PER_WORK_GROUP", quadsPerWorkGroup)
        define("FLAG_TYPE", renderer.visibilityFlagType.glslTypeName)
        if (scene.overdrawDebug) {
            define("OVERDRAW_DEBUG")
        }
        if (renderer.debugQuadCount) {
            define("DEBUG_QUAD_COUNT")
        }
    }
    val drawShader = register(
        ShaderProgram(drawShaderSource)
    ).apply {
        label = "SWQuad.DrawShader"
    }

    val shaderInfo = ShaderSourceContext(drawShaderSource.resolveCodeSrc()).run {
        patchShaderForVulkan()
        toShaderInfo()
    }

    override suspend fun update(chunks: IntArrayList) {
        class Scope {
            val localBuffer = Arr.malloc(0L)
            var localQuadCount = 0
            var localBatchCount = 0
        }

        val batchStructSize = sizeof(QuadBatchInfo)
        coroutineScope {
            launch {
                updateCtcBuffer(chunks)
            }

            val scopes = LWWSP.iterativeTask(chunks.size / 3, 128, ::Scope) { start, end ->
                localBuffer.ensureCapacity((localBatchCount + (end - start)) * batchStructSize, false)

                for (i in start until end) {
                    val chunkIndex = chunks.chunkIndex(i)
                    val startIndex = chunks.startIndex(i)
                    val quadCount = chunks.quadCount(i)
                    var quadIdx = 0
                    while (quadIdx < quadCount) {
                        val batchQuadCount = min(quadCount - quadIdx, quadsPerWorkGroup)

                        localBuffer.ensureCapacity((localBatchCount + 1L) * batchStructSize, false)
                        val struct = QuadBatchInfo(localBuffer.ptr + localBatchCount * batchStructSize)
                        struct.chunkIndex = chunkIndex
                        struct.first = startIndex + quadIdx
                        struct.count = batchQuadCount

                        quadIdx += batchQuadCount
                        localBatchCount++
                    }
                    localQuadCount += quadIdx
                }
            }.submit(renderer.lwwsp).await().getOrThrow()

            quadCount = scopes.sumOf { it.localQuadCount }
            batchCount = scopes.sumOf { it.localBatchCount }
            if (batchCount == 0) return@coroutineScope

            launch(scene.gameInstance.mainContext) {
                val batchIndicesLen = 16L + batchCount * 4L
                batchIndices.ensureCapacityDiscard(batchIndicesLen, 0)

                val batchMaskBallotsLen = batchCount * renderer.visibilityFlagType.byteSize.toLong()
                batchFilterFlag.ensureCapacityDiscard(batchMaskBallotsLen, 0)

                val scanWorkGroupCount = scene.gpuScan.workGroupSize
                val scanInputSize = 16L + (batchCount + scanWorkGroupCount - 1) / scanWorkGroupCount * 4L
                scanBuffer.ensureCapacityDiscard(scanInputSize, 0)

                val quadBatchInfoMetaSize = 16L
                val quadMetaLen = quadBatchInfoMetaSize + batchCount * batchStructSize
                quadBatchInfo.ensureCapacityDiscard(quadMetaLen, GL_DYNAMIC_STORAGE_BIT)

                MemoryStack {
                    val arr = calloc(quadMetaLen)
                    arr.ptr.setInt(0L, batchCount)
                    quadBatchInfo.upload(0, quadBatchInfoMetaSize, arr.ptr)
                }

                var batchOffset = 0
                scopes.forEachIndexed { index, scope ->
                    quadBatchInfo.upload(
                        quadBatchInfoMetaSize + batchOffset * batchStructSize,
                        scope.localBatchCount * batchStructSize,
                        scope.localBuffer.ptr
                    )
                    scope.localBuffer.free()
                    batchOffset += scope.localBatchCount
                }
            }
        }
    }

    private val printTimer = TickTimer()

    override fun draw() {
        if (batchCount == 0) return

        if (renderer.debugQuadCount) {
            renderer.tempBuffer.clear(GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
        }

        if (!scene.gameInstance.toggles[3]) {
            drawPass(VisibilityState.LAST_VISIBLE)

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

            renderer.cullPipeline.depthBuffer.swDepthBlit()
            glViewport(0, 0, scene.camera.screenWidth / 4, scene.camera.screenHeight / 4)
            renderer.cullPipeline.draw(ctcBuffer, chunkCount)
            glViewport(0, 0, scene.camera.screenWidth, scene.camera.screenHeight)
            glEnable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_GEQUAL)
            glDepthMask(true)
            glColorMask(true, true, true, true)

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)

            drawPass(VisibilityState.NEWLY_VISIBLE)
        } else {
            drawPass(VisibilityState.CURRENT_VISIBLE)
        }

        if (renderer.debugQuadCount && printTimer.tickAndReset(3000)) {
            MemoryStack {
                val ptr = malloc(renderer.tempBuffer.size).ptr
                glGetNamedBufferSubData(renderer.tempBuffer.id, 0, renderer.tempBuffer.size, ptr)
                println("SWQuad: Input Quad Count: %,d".format(ptr.getInt(0)))
                println("SWQuad: Output Quad Count: %,d".format(ptr.getInt(4)))
            }
        }
    }

    private var prevToggle = false

    private fun drawPass(state: VisibilityState) {
        glDebugGroup("DrawPass-$state-Setup") {
            val scanWorkGroupSize = scene.gpuScan.workGroupSize
            val scanGroupCount = (batchCount + scanWorkGroupSize - 1) / scanWorkGroupSize
            clearShader.bind()
            clearShader.applyBinding {
                buffer(bufferBindings)
            }
            glDispatchCompute(1, 1, 1)
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
            val filterShader1 = filterShader[state]
            filterShader1.bind()
            filterShader1.applyBinding {
                buffer(renderer.bufferBindings)
                buffer(scene.vbuffer.bufferBinding)
                buffer(bufferBindings)
            }
            glDispatchCompute(scanGroupCount, 1, 1)
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_UNIFORM_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)
            val scanDataView = scanBuffer.createView(16L)
            scene.gpuScan.process(
                scanDataView,
                scanBuffer.createView(0L, 16L),
                scanDataView,
                scanGroupCount
            )
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
            batchIndicesShader.bind()
            batchIndicesShader.applyBinding {
                buffer(bufferBindings)
            }
            glDispatchCompute(scanGroupCount, 1, 1)
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT or GL_COMMAND_BARRIER_BIT)
        }
        glDebugGroup("DrawPass-$state-Draw") {
            glEnable(GL_CULL_FACE)
            glCullFace(GL_BACK)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_GEQUAL)
            glDepthMask(true)
            glColorMask(true, true, true, true)
            scene.bindSWDrawShader(drawShader)
            drawShader.applyBinding {
                sampler(scene.textures.blockAlbedo)
                buffer(scene.camera.uboBinding)
                buffer(scene.terrainMeshData.bufferBindings)
                buffer(scene.vbuffer.bufferBinding)
                buffer(bufferBindings)
                buffer(renderer.bufferBindings)
                image(scene.vbuffer.imageBindings)
                image("uimg_overdrawCounter", scene.overdrawCounterTex)
            }
            batchIndices.bind(GL_DISPATCH_INDIRECT_BUFFER)
            val newToggle = scene.gameInstance.toggles[9]
            if (newToggle != prevToggle) {
                prevToggle = newToggle
                println("Capturing using glc2vk")
                captureGlDispatchComputeIndirect(shaderInfo, Path("glc2vk"), 0L)
            } else {
                glDispatchComputeIndirect(0L)
            }
        }
    }
}