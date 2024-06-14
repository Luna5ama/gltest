package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.AbstractScene
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.VertexArrayObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.MemoryStack

class QuadDrawHWPipeline(private val scene: AbstractScene, private val pcData: PointCloudData) :
    IGLObjContainer by IGLObjContainer.Impl() {
    val vao = register(VertexArrayObject())
    val shader = register(
        ShaderProgram(
            ShaderSource.Vert("pc/QuadDrawHW.vert"),
            ShaderSource.Frag("pc/QuadDrawHW.frag")
        )
    ).apply {
        label("QuadDrawHW.shader")
    }
    private val chunkOffsetData = register(BufferObject.Immutable()).apply {
        label("QuadDrawHW.chunkOffsetData")
    }
    val indirectCommands = register(BufferObject.Immutable()).apply {
        label("QuadDrawHW.indirectCommands")
    }
    val indirectCommandUploadBuffer = Arr.malloc(0L)
    val chunkStartIndex = register(BufferObject.Immutable()).apply {
        label("QuadDrawHW.chunkStartIndex")
    }
    val chunkStartIndexUploadBuffer = Arr.malloc(0L)
    val bufferBinding = listOf(
        ShaderBindingSpecs.Buffer("ChunkStartIndexBuffer", chunkStartIndex, BufferTarget.ShaderStorage),
        ShaderBindingSpecs.Buffer("ChunkOffsetData", chunkOffsetData, BufferTarget.ShaderStorage)
    )

    init {
        val xBits = (1 shl 13) - 1
        val yBits = (1 shl 6) - 1
        MemoryStack {
            val arr = malloc(pcData.chunkOffsets.size * 4L)
            val ptr = arr.ptr
            for (i in pcData.chunkOffsets.indices) {
                val offset = pcData.chunkOffsets[i]
                val offsetEncoded = (offset.x and xBits) or (offset.y and yBits shl 13) or (offset.z shl (13 + 6))
                ptr.setInt(i * 4L, offsetEncoded)
            }
            chunkOffsetData.allocate(arr.len, arr.ptr, 0)
        }
    }
}