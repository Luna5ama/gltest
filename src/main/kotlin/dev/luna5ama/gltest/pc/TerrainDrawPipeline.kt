package dev.luna5ama.gltest.pc

import dev.fastmc.common.collection.StaticBitSet
import dev.luna5ama.gltest.structs.CullTestCommand
import dev.luna5ama.gltest.structs.sizeof
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.base.GL_DYNAMIC_STORAGE_BIT
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.Arr
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlinx.coroutines.withContext

abstract class TerrainDrawPipeline(
    name: String,
    protected val scene: PointCloudScene,
    protected val renderer: TerrainGeometryRenderer
) : IGLObjContainer by IGLObjContainer.Impl() {
    var batchCount = 0; protected set
    var chunkCount = 0; protected set
    var quadCount = 0; protected set

    protected val ctcBuffer = register(BufferObject.Immutable()).apply {
        label = "$name.CullTestCommandBuffer"
    }

    protected fun IntArrayList.chunkIndex(i: Int) = this.getInt(i * 3)
    protected fun IntArrayList.startIndex(i: Int) = this.getInt(i * 3 + 1)
    protected fun IntArrayList.quadCount(i: Int) = this.getInt(i * 3 + 2)

    abstract suspend fun update(chunks: IntArrayList)

    open fun preDraw() {}
    abstract fun draw()

    protected suspend fun updateCtcBuffer(chunks: IntArrayList) {
        val ctcBufferBaseOffset = renderer.cullPipeline.ctcBufferBaseOffset
        val ctcStructSize = sizeof(CullTestCommand)
        Arr.malloc(ctcBufferBaseOffset + chunks.size / 3 * ctcStructSize).use { ctcUploadBuffer ->
            var chunkCounter = 0
            val chunkIndexBitSet = StaticBitSet(scene.terrainMeshData.chunkPos.size)
            for (i in 0..<chunks.size / 3) {
                val chunkIndex = chunks.chunkIndex(i)
                if (!chunkIndexBitSet.add(chunkIndex)) continue
                val offset = chunkCounter++ * ctcStructSize
                val ctcStruct = CullTestCommand(ctcUploadBuffer.ptr + ctcBufferBaseOffset + offset)
                ctcStruct.chunkIndex = chunkIndex
            }
            chunkCount = chunkCounter
            if (chunkCount == 0) return
            renderer.cullPipeline.setupIndirect(ctcUploadBuffer.ptr, chunkCounter)

            withContext(scene.gameInstance.mainContext) {
                val ctcLen = ctcBufferBaseOffset + chunkCounter * ctcStructSize
                if (ctcBuffer.size < ctcLen) {
                    ctcBuffer.destroy()
                    ctcBuffer.allocate(ctcLen, ctcUploadBuffer.ptr, GL_DYNAMIC_STORAGE_BIT)
                } else {
                    ctcBuffer.invalidate()
                    ctcBuffer.upload(0L, ctcLen, ctcUploadBuffer.ptr)
                }
            }
        }
    }
}