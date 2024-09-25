package dev.luna5ama.gltest.terrain.post

import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.*
import dev.luna5ama.glwrapper.enums.*
import dev.luna5ama.glwrapper.objects.*
import dev.luna5ama.gltest.terrain.TerrainScene

class ToneMapping(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val toneMapping = register(ShaderProgram(
        ShaderSource.Vert("terrain/Blit.vert.glsl"),
        ShaderSource.Frag("terrain/post/ToneMapping.frag.glsl")
    )
)
    fun render(fboGroup: FboGroup) {
        toneMapping.bind()
        fboGroup.input.colorTex.bindTextureUnit(0)
        fboGroup.output.fbo.bind()

        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)

        scene.basic.drawQuad()

        fboGroup.swap()
    }
}