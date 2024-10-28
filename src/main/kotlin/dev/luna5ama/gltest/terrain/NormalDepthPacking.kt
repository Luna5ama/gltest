package dev.luna5ama.gltest.terrain

import dev.luna5ama.gltest.terrain.post.GBuffer
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.objects.TextureObject

class NormalDepthPacking(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val shader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/Blit.vert.glsl"),
            ShaderSource.Frag("terrain/NDPackingPass.frag")
        )
    )

    private val fbo = register(FramebufferObject())

    fun render(input: GBuffer, output: TextureObject.Texture2D) {
        check(output.internalformat == ImageFormat.R32G32_UI)
        fbo.clearAttachments()
        fbo.attach(output, GL_COLOR_ATTACHMENT0)
        fbo.bind()
        shader.bind()
        shader.applyBinding {
            buffer(scene.camera.uboBinding)
            sampler(input.genericTexBindings)
        }
        glDisable(GL_BLEND)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_STENCIL_TEST)
        scene.basic.drawQuad()
    }
}