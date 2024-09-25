package dev.luna5ama.gltest.terrain.post

import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.gltest.terrain.TerrainScene
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource

class FXAA(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val shader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/FXAA.vert.glsl"),
            ShaderSource.Frag("terrain/FXAA.frag.glsl")
        )
    )

    fun render(fboGroup: FboGroup) {
        shader.bind()
        fboGroup.output.fbo.bind()
        shader.applyBinding {
            sampler(fboGroup.inputBindings)
            buffer(scene.camera.noJitterUboBinding)
        }

        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ZERO)

        scene.basic.drawQuad()

        fboGroup.swap()
    }
}