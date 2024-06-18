package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.terrain.IGLObjContainer

abstract class TerrainDrawPipeline(
    protected val scene: PointCloudScene,
    protected val renderer: TerrainGeometryRenderer
) : IGLObjContainer by IGLObjContainer.Impl() {
    var batchCount = 0; protected set
    var chunkCount = 0; protected set
    var quadCount = 0; protected set

    abstract suspend fun update(chunks: List<TerrainMeshData.ChunkInfo>)
    abstract fun draw()
}