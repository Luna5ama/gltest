package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.glDispatchCompute
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import org.joml.Vector4f

class SWPointPipeline(scene: PointCloudScene, renderer: TerrainGeometryRenderer) :
    TerrainDrawPipeline("SWPoint", scene, renderer) {
    private val batchInfoBuffer = register(BufferObject.Immutable())

    val bufferBinding = ShaderBindingSpecs.Buffer("PointBatchInfoBuffer", batchInfoBuffer, BufferTarget.ShaderStorage)
    private val pointDrawShader: ShaderProgram

    init {
        val bcoords = listOf(
            Vector4f(0.25f, 0.25f, 0.25f, 0.25f),

            Vector4f(0.5f, 0.25f, 0.0f, 0.25f),
            Vector4f(0.0f, 0.25f, 0.5f, 0.25f),


            Vector4f(0.0f, 0.125f, 0.5f, 0.375f),
            Vector4f(0.375f, 0.0f, 0.125f, 0.5f),
            Vector4f(0.125f, 0.5f, 0.375f, 0.0f),
            Vector4f(0.5f, 0.375f, 0.0f, 0.125f),


            Vector4f(0.0f, 0.3125f, 0.125f, 0.5625f),
            Vector4f(0.125f, 0.5625f, 0.0f, 0.3125f),
            Vector4f(0.5625f, 0.0f, 0.1875f, 0.25f),
            Vector4f(0.1875f, 0.0f, 0.6875f, 0.125f),

            Vector4f(0.0f, 0.8125f, 0.0f, 0.1875f),
            Vector4f(0.0625f, 0.375f, 0.5625f, 0.0f),
            Vector4f(0.625f, 0.3125f, 0.0f, 0.0625f),
            Vector4f(0.0625f, 0.0f, 0.0625f, 0.875f),


            Vector4f(0.125f, 0.4375f, 0.0f, 0.4375f),
            Vector4f(0.3125f, 0.0f, 0.5625f, 0.125f),
            Vector4f(0.0f, 0.625f, 0.0625f, 0.3125f),
            Vector4f(0.1875f, 0.25f, 0.0f, 0.5625f),

            Vector4f(0.1875f, 0.1875f, 0.625f, 0.0f),
            Vector4f(0.625f, 0.1875f, 0.1875f, 0.0f),
            Vector4f(0.6875f, 0.0f, 0.1875f, 0.125f),
            Vector4f(0.0f, 0.1875f, 0.125f, 0.6875f),

            Vector4f(0.25f, 0.625f, 0.0f, 0.125f),
            Vector4f(0.0625f, 0.0f, 0.5f, 0.4375f),
            Vector4f(0.125f, 0.0f, 0.75f, 0.125f),
            Vector4f(0.0f, 0.75f, 0.125f, 0.125f),

            Vector4f(0.0f, 0.5f, 0.5f, 0.0f),
            Vector4f(0.1875f, 0.0625f, 0.0f, 0.75f),
            Vector4f(0.8125f, 0.125f, 0.0f, 0.0625f),
            Vector4f(0.0625f, 0.9375f, 0.0f, 0.0f)
        )
        val bcoordIdxOffsets = listOf(
            0,
            1,
            1 + 2,
            1 + 2 + 4,
            1 + 2 + 4 + 8
        )

        fun encodeBCoord(bcoord: Vector4f): Long {
            val tempX = ((bcoord.x * 2047.0f).toLong() and 2047L) shl 0
            val tempY = ((bcoord.y * 1023.0f).toLong() and 1023L) shl 11
            val tempZ = ((bcoord.z * 2047.0f).toLong() and 2047L) shl 21
            return tempX or tempY or tempZ
        }

        val encodedList = bcoords.map { encodeBCoord(it) }

        fun StringBuilder.generate(sampleCountPow2: Int) {
            val bCoordIdxOffset = bcoordIdxOffsets[sampleCountPow2]
            val sampleCount = 1 shl sampleCountPow2
            appendLine("case $sampleCountPow2:\\")
            repeat(sampleCount) { i ->
                val bCoordIdx = bCoordIdxOffset + i
                val bCoord = bcoords[bCoordIdx]
                val encoded = encodedList[bCoordIdx]
                appendLine(
                    "DRAW_POINT(vec4(%f, %f, %f, %f),0x%08Xu);\\".format(
                        bCoord.x,
                        bCoord.y,
                        bCoord.z,
                        bCoord.w,
                        encoded
                    )
                )
            }
            appendLine("break;\\")
        }

        val xd = buildString {
            repeat(5) {
                generate(it)
            }
        }

        pointDrawShader = register(
            ShaderProgram(
                ShaderSource.Comp("pc/geometry/PointDraw.comp") {
                    define("BATCH_SIZE", renderer.threadBatchSize)
                    define("UNROLLED_CODE", xd)
                    if (scene.overdrawDebug) {
                        define("OVERDRAW_DEBUG")
                    }
                }
            )
        ).apply {
            label("PointDraw")
        }
    }

    override suspend fun update(chunks: List<TerrainMeshData.ChunkLayerInfo>) {

    }

//    override fun startUpdate(): UpdateContextImpl {
//        return UpdateContextImpl()
//    }
//
//    override fun endUpdate(context: UpdateContextImpl) {
//        batchCount = context.batchCount
//        chunkCount = context.chunkCount
//        quadCount = context.quadCount
//
//        if (context.batchCount == 0) return
//
//        val quadMetaLen = context.batchCount * sizeof(QuadBatchInfo)
//        if (quadMetaLen > 0) {
//            if (batchInfoBuffer.size < quadMetaLen) {
//                batchInfoBuffer.destroy()
//                batchInfoBuffer.allocate(quadMetaLen, context.uploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
//            } else {
//                batchInfoBuffer.invalidate()
//                batchInfoBuffer.upload(0L, quadMetaLen, context.uploadBuffer.ptr)
//            }
//        }
//    }

    override fun draw() {
        if (batchCount == 0) return
        scene.bindSWDrawShader(pointDrawShader)
        pointDrawShader.applyBinding {
            sampler(scene.textures.blockAlbedo)
            buffer(scene.camera.uboBinding)
            buffer(scene.terrainMeshData.bufferBindings)
            buffer(scene.vbuffer.bufferBinding)
            buffer(bufferBinding)
            image(scene.vbuffer.imageBindings)
            image("uimg_overdrawCounter", scene.overdrawCounterTex)
        }
        glDispatchCompute(batchCount, 1, 1)
    }

//    inner class UpdateContextImpl : UpdateContext() {
//        val uploadBuffer = Arr.malloc(0L)
//
//        override fun add(chunkInfo: TerrainMeshData.ChunkLayerInfo) {
//            val sampleCountPow2 = renderer.sampleCountArray[chunkInfo.listIndex]
//            var quadIdx = 0
//            val sampleCount = 1 shl abs(sampleCountPow2).toInt()
//            val count = if (sampleCountPow2 > 0) renderer.threadBatchSize else renderer.threadBatchSize * sampleCount
//
//            while (quadIdx < chunkInfo.quadCount) {
//                uploadBuffer.ensureCapacity((batchCount + 1L) * sizeof(PointBatchInfo), false)
//                val struct = PointBatchInfo(uploadBuffer.ptr + batchCount * sizeof(PointBatchInfo))
//                val batchQuadCount = min(chunkInfo.quadCount - quadIdx, count)
//                struct.first = chunkInfo.startIndex + quadIdx
//                struct.count = batchQuadCount
//                struct.offsetX = chunkInfo.offset.x.toByte()
//                struct.offsetY = chunkInfo.offset.y.toByte()
//                struct.offsetZ = chunkInfo.offset.z.toByte()
//                struct.pointSampleCount = sampleCountPow2.toInt()
//                quadIdx += batchQuadCount
//                batchCount++
//            }
//            chunkCount++
//            quadCount += chunkInfo.quadCount
//        }
//
//        override fun close() {
//            uploadBuffer.free()
//        }
//    }
}