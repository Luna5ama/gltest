package dev.luna5ama.gltest.terrain

import dev.fastmc.common.ceilToInt
import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.*
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

class SolidLighting(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    val hbao = register(SSILVB(scene, this))

    val tex1 = register(TextureObject.Texture2D()).apply {
        label = "SolidLighting.Tex1"
    }
    val tex2 = register(TextureObject.Texture2D()).apply {
        label = "SolidLighting.Tex2"
    }
    private val fbo = register(FramebufferObject()).apply {
        label = "SolidLighting.FBO"
    }

    val texBingings = listOf(
        ShaderBindingSpecs.Sampler("usam_solidLighting1", tex1),
        ShaderBindingSpecs.Sampler("usam_solidLighting2", tex2)
    )

    private var lastUpdateSizeBit = 0L

    private val directShader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/Blit.vert.glsl"),
            ShaderSource.Frag("terrain/solid/DL.frag")
        )
    )
    private val indirectShader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/Blit.vert.glsl"),
            ShaderSource.Frag("terrain/solid/IL.frag")
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

        glDisable(GL_BLEND)

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
            directShader.uniform1f("uval_mb", if (scene.gameInstance.toggles[3]) 0.0f else 2.0f)

            fbo.bind()

            scene.basic.drawQuad()
        }

        tex1.generateMipmap()
        tex2.generateMipmap()

        glBlendFuncSeparate(GL_ONE, GL_ONE, GL_ZERO, GL_ONE)
        glDebugGroup("HBAO") {
            hbao.render(scene.solidGBuffer)
        }

        glEnable(GL_BLEND)
        glBlendFuncSeparate(GL_ONE, GL_SRC_ALPHA, GL_ZERO, GL_ONE)

        glDebugGroup("Composite") {
            indirectShader.bind()
            indirectShader.uniform4f("uval_aoParams", 1.0f, 2.0f, 0.5f, 0.25f)
            indirectShader.uniform1f("uval_giParams", if (scene.gameInstance.toggles[0]) 0.0f else 1.0f)
            indirectShader.applyBinding {
                sampler(texBingings)
                sampler(hbao.texBinding)
                sampler(scene.solidGBuffer.texBindings)
                sampler(scene.textures.blockNormal)
                sampler(scene.textures.blockSpecular)
                sampler(scene.textures.lightMap)

                buffer(scene.camera.uboBinding)
            }

            fboGroup.output.fbo.bind()

            scene.basic.drawQuad()

            fboGroup.output.fbo.blitTo(fboGroup.input.fbo, GL_COLOR_BUFFER_BIT, GL_LINEAR)
        }


        glDisable(GL_STENCIL_TEST)
    }

    private fun update(fboGroup: FboGroup) {
        val tex = scene.solidGBuffer.tex1
        val sizeBit = tex.sizeBit
        if (sizeBit != lastUpdateSizeBit) {
            val levels = min(log2(max(fboGroup.input.fbo.width, fboGroup.output.fbo.height).toDouble()).ceilToInt(), 13)
            tex1.apply {
                destroy()
                allocate(levels, ImageFormat.R11G11B10_F, tex.width, tex.height)
                setMipLevels(0, levels - 1)
                setMipLod(0.0f, levels - 1.0f)
                setFilter(GL_NEAREST_MIPMAP_NEAREST, GL_NEAREST)
                setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
            }
            tex2.apply {
                destroy()
                allocate(levels, ImageFormat.R10G10B10A2_UN, tex.width, tex.height)
                setMipLevels(0, levels - 1)
                setMipLod(0.0f, levels - 1.0f)
                setFilter(GL_NEAREST_MIPMAP_NEAREST, GL_NEAREST)
                setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
            }
            fbo.apply {
                clearAttachments()
                attach(tex1, GL_COLOR_ATTACHMENT0)
                attach(tex2, GL_COLOR_ATTACHMENT1)
                attach(fboGroup.output.colorTex, GL_COLOR_ATTACHMENT2)
                attach(fboGroup.input.depthStencilTex, GL_DEPTH_STENCIL_ATTACHMENT)
                setDrawBufferAll()
                check()
            }
            lastUpdateSizeBit = sizeBit
        }

        fbo.clearColor(0, 0.0f, 0.0f, 0.0f, 0.0f)
        fbo.clearColor(1, 0.0f, 0.0f, 0.0f, 0.0f)
    }
}