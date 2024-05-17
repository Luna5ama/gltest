package dev.luna5ama.gltest.terrain

import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.linearClampEdge
import dev.luna5ama.gltest.uniform2f
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.objects.TextureObject

class SolidLighting(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    val hbao = register(HBAO(scene, this))

    val tex1 = register(TextureObject.Texture2D()).apply {
        label("SolidLighting.Tex1")
    }
    val tex2 = register(TextureObject.Texture2D()).apply {
        label("SolidLighting.Tex2")
    }
    private val fbo = register(FramebufferObject()).apply {
        label("SolidLighting.FBO")
    }

    val texBingings = listOf(
        ShaderBindingSpecs.Sampler("usam_solidLighting1", tex1),
        ShaderBindingSpecs.Sampler("usam_solidLighting2", tex2)
    )

    private var lastUpdateSizeBit = 0L

    private val directShader = register(
        ShaderProgram(
            ShaderSource.Vert("/terrain/Blit.vert.glsl"),
            ShaderSource.Frag("/terrain/solid/DL.frag")
        )
    )
    private val indirectShader = register(
        ShaderProgram(
            ShaderSource.Vert("/terrain/Blit.vert.glsl"),
            ShaderSource.Frag("/terrain/solid/IL.frag")
        )
    )

    fun render(fboGroup: FboGroup) {
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)

        glDebugGroup("Blit depth stencil") {
            glDisable(GL_DITHER)
            glBlitNamedFramebuffer(
                scene.solidGBuffer.fbo.id,
                fboGroup.input.fbo.id,
                0, 0, scene.solidGBuffer.width, scene.solidGBuffer.height,
                0, 0, fboGroup.combined.width, fboGroup.combined.height,
                GL_STENCIL_BUFFER_BIT or GL_DEPTH_BUFFER_BIT, GL_NEAREST
            )
            glBlitNamedFramebuffer(
                scene.solidGBuffer.fbo.id,
                fboGroup.output.fbo.id,
                0, 0, scene.solidGBuffer.width, scene.solidGBuffer.height,
                0, 0, fboGroup.output.fbo.width, fboGroup.output.fbo.height,
                GL_STENCIL_BUFFER_BIT or GL_DEPTH_BUFFER_BIT, GL_NEAREST
            )
            glEnable(GL_DITHER)
        }

        glDebugGroup("Update") {
            update(fboGroup)
        }

        glDebugGroup("Solid velocity") {
            scene.solidVelocity.render(scene.solidGBuffer)
        }

        glDisable(GL_DEPTH_TEST)

        glEnable(GL_STENCIL_TEST)
        glStencilFunc(GL_EQUAL, 1, 0xFF)
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP)

        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE)

        glDebugGroup("Direct lighting") {
            directShader.bind()
            directShader.applyBinding {
                sampler(scene.solidGBuffer.texBindings)
                sampler(scene.textures.blockNormal)
                sampler(scene.textures.blockSpecular)
                sampler(scene.textures.lightMap)
                sampler(scene.rtwsm.shadowMapSamplerBindings)
                sampler(hbao.texBinding)

                buffer(scene.camera.uboBinding)
                buffer(scene.rtwsm.uboBinding)
            }

            fbo.bind()

            scene.basic.drawQuad()
        }

        glDebugGroup("HBAO") {
            hbao.render(scene.solidGBuffer)
        }

        glBlendFunc(GL_ONE, GL_ZERO)
        glDebugGroup("Composite") {
            indirectShader.bind()
            indirectShader.uniform4f("uval_aoParams", 1.0f, 2.0f, 4.0f, 8.0f)
            indirectShader.applyBinding {
                sampler(texBingings)
                sampler(hbao.texBinding)
                sampler(scene.solidGBuffer.texBindings)
                sampler(scene.textures.lightMap)

                buffer(scene.camera.uboBinding)
            }

            fboGroup.combined.bind()

            scene.basic.drawQuad()
        }

        glDisable(GL_STENCIL_TEST)
    }

    private fun update(fboGroup: FboGroup) {
        val tex = scene.solidGBuffer.tex1
        val sizeBit = tex.sizeBit
        if (sizeBit != lastUpdateSizeBit) {
            tex1.apply {
                destroy()
                allocate(1, ImageFormat.R16_G16_B16_A16_F, tex.width, tex.height)
                linearClampEdge()
            }
            tex2.apply {
                destroy()
                allocate(1, ImageFormat.R16_G16_B16_A16_F, tex.width, tex.height)
                linearClampEdge()
            }
            fbo.apply {
                clearAttachments()
                attachNonLayered(tex1, GL_COLOR_ATTACHMENT0)
                attachNonLayered(tex2, GL_COLOR_ATTACHMENT1)
                attachNonLayered(fboGroup.input.depthStencilTex, GL_DEPTH_STENCIL_ATTACHMENT)
                setDrawBufferAll()
                check()
            }
            lastUpdateSizeBit = sizeBit
        }

        fbo.clearColor(0, 0.0f, 0.0f, 0.0f, 0.0f)
        fbo.clearColor(1, 0.0f, 0.0f, 0.0f, 0.0f)
    }
}