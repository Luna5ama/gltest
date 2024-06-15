package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.terrain.IGLObjContainer

abstract class TerrainDrawPipeline<T : TerrainDrawPipeline.UpdateContext>(protected val scene: PointCloudScene) :
    IGLObjContainer by IGLObjContainer.Impl() {
    var batchCount = 0; protected set
    var chunkCount = 0; protected set
    var quadCount = 0; protected set

    abstract fun startUpdate(): T
    abstract fun endUpdate(context: T)
    abstract fun draw()

    abstract class UpdateContext : AutoCloseable {
        var batchCount = 0; protected set
        var chunkCount = 0; protected set
        var quadCount = 0; protected set
        abstract fun add(chunkInfo: TerrainMeshData.ChunkInfo)
    }
}