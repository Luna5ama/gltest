package dev.luna5ama.gltest.terrain

import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.gltest.linearClampEdge
import dev.luna5ama.gltest.terrain.post.GBuffer
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.enums.FilterMode
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.enums.WrapMode

class WaterRef(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val shader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/Blit.vert.glsl"),
            ShaderSource.Frag("terrain/water/Ref.frag.glsl")
        )
    )
    private val refUvTex = register(TextureObject.Texture2D())
    private val fbo = register(FramebufferObject())

    val refUvTexBinding = ShaderBindingSpecs.Sampler("usam_refUVTex", refUvTex)

    private fun update() {
        val width = scene.camera.screenWidth
        val height = scene.camera.screenHeight
        if (refUvTex.width != width || refUvTex.height != height) {
            fbo.destroyFbo()
            refUvTex.destroy()

            refUvTex.apply {
                allocate(1, ImageFormat.R16_G16_B16_A16_SN, width, height)
                linearClampEdge()
            }
            fbo.apply {
                attach(refUvTex, GL_COLOR_ATTACHMENT0)
                check()
            }
        }
    }

    fun render(input: GBuffer, fboGroup: FboGroup) {
        update()

        fbo.apply {
            attach(input.depthStencilTex, GL_DEPTH_ATTACHMENT)
            check()
            bind()
        }

        glClearColor(-1.0f, -1.0f, -1.0f, -1.0f)
        glClear(GL_COLOR_BUFFER_BIT)
        shader.bind()

        val nearestClampEdge = scene.samplerManager.get {
            filter(FilterMode.Nearest, FilterMode.Nearest)
            wrap(WrapMode.ClampToEdge, WrapMode.ClampToEdge)
        }
        shader.applyBinding {
            sampler(scene.solidGBuffer.texBindings, nearestClampEdge)
            sampler(scene.waterGBuffer.texBindings, nearestClampEdge)
            sampler(fboGroup.inputBindings)

            buffer(scene.camera.uboBinding)
        }

        glDisable(GL_DEPTH_TEST)
        glEnable(GL_STENCIL_TEST)
        glStencilFunc(GL_EQUAL, 1, 0xFF)
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP)
        glDisable(GL_BLEND)

        scene.basic.drawQuad()

        glDisable(GL_STENCIL_TEST)
    }
}