package dev.luna5ama.gltest.terrain.post

import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.gltest.terrain.TerrainScene

class Sharpening(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val shader = register(ShaderProgram(
        ShaderSource.Vert("/terrain/Blit.vert.glsl"),
        ShaderSource.Frag("/terrain/post/Sharpening.frag.glsl")
    )
)
    fun  render(fboGroup: FboGroup) {
        fboGroup.output.fbo.bind()
        shader.bind()
        shader.applyBinding {
            sampler(fboGroup.inputBindings)
            buffer(scene.camera.noJitterUboBinding)
        }
        shader.uniform1f("uSharpnessAmount", 0.0f)

        scene.basic.drawQuad()

        fboGroup.swap()
    }
}