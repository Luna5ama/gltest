package dev.luna5ama.gltest.terrain.post

import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.*
import dev.luna5ama.gltest.terrain.TerrainScene

class GammaCorrection(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val shader = register(ShaderProgram(
        ShaderSource.Vert("terrain/Blit.vert.glsl"),
        ShaderSource.Frag("terrain/post/GammaCorrection.frag.glsl")
    )
)
    fun render(fboGroup: FboGroup) {
        shader.bind()
        shader.uniform1f("uInvGamma", 1.0f / 2.2f)
        shader.applyBinding {
            sampler(fboGroup.inputBindings)
            buffer(scene.camera.noJitterUboBinding)
        }

        fboGroup.input.colorTex.bindTextureUnit(0)
        fboGroup.output.fbo.bind()
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ZERO)
        scene.basic.drawQuad()

        fboGroup.swap()
    }
}