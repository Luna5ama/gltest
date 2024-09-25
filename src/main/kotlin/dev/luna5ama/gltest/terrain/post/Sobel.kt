package dev.luna5ama.gltest.terrain.post

import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.TerrainScene
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.*

class Sobel(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val shader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/post/Sobel.vert.glsl"),
            ShaderSource.Frag("terrain/post/Sobel.frag.glsl")
        )
    )

    fun render(fboGroup: FboGroup) {
        shader.bind()
        shader.applyBinding {
            buffer(scene.camera.uboBinding)
        }
        shader.uniform2f("uJitter", scene.camera.jitter.x, scene.camera.jitter.y)
        shader.applyBinding {
            sampler(scene.solidGBuffer.genericTexBindings)
            sampler(fboGroup.inputBindings)
        }

        fboGroup.output.fbo.bind()
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ZERO)
        scene.basic.drawQuad()

        fboGroup.swap()
    }
}