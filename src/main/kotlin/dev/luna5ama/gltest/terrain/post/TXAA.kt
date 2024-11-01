package dev.luna5ama.gltest.terrain.post

import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.gltest.terrain.TerrainScene

class TXAA(scene: TerrainScene, samples: Int, radius: Float) : IGLObjContainer by IGLObjContainer.Impl() {
    val taa = register(TAA(scene, samples, radius))
    private val sharpening = register(Sharpening(scene))
    private val fxaa = register(FXAA(scene))

    val baseJitter get() = taa.baseJitter
    val currentJitter get() = taa.currentJitter

    fun render(fboGroup: FboGroup) {
        fxaa.render(fboGroup)
        taa.render(fboGroup)
        sharpening.render(fboGroup)
    }
}