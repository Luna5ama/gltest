package dev.luna5ama.gltest.terrain.post

import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.TerrainScene
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.glwrapper.objects.setFilter
import dev.luna5ama.glwrapper.objects.setWrap

class GBuffer(private val scene: TerrainScene, val name: String) : IGLObjContainer by IGLObjContainer.Impl() {
    val tex1 = register(TextureObject.Texture2D()).apply {
        label("GBuffer.$name.Tex1")
    }
    val tex2 = register(TextureObject.Texture2D()).apply {
        label("GBuffer.$name.Tex2")
    }
    val depthStencilTex = register(TextureObject.Texture2D()).apply {
        label("GBuffer.$name.DepthStencil")
    }

    val genericTexBindings = listOf(
        ShaderBindingSpecs.Sampler("usam_gData1", tex1),
        ShaderBindingSpecs.Sampler("usam_gData2", tex2),
        ShaderBindingSpecs.Sampler("usam_gDepth", depthStencilTex)
    )

    val texBindings = listOf(
        ShaderBindingSpecs.Sampler("usam_g${name}Data1", tex1),
        ShaderBindingSpecs.Sampler("usam_g${name}Data2", tex2),
        ShaderBindingSpecs.Sampler("usam_g${name}Depth", depthStencilTex)
    )

    val fbo = register(FramebufferObject())

    private val clearShader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/ClearGBuffer.vert.glsl"),
            ShaderSource.Frag("terrain/ClearGBuffer.frag.glsl")
        )
    )

    val width get() = fbo.width
    val height get() = fbo.height

    fun bindFbo() {
        fbo.bind()
        setDrawBuffers()
    }

    fun setDrawBuffers() {
        fbo.setDrawBufferAll()
    }

    fun clear() {
        glDisable(GL_DITHER)
        bindFbo()
        glClearStencil(0)
        glClearDepth(1.0)
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT or GL_STENCIL_BUFFER_BIT)

        setDrawBuffers()
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_STENCIL_TEST)
        glDisable(GL_BLEND)
        clearShader.bind()
        clearShader.applyBinding {
            buffer(scene.camera.uboBinding)
        }
        scene.basic.drawQuad()
        glEnable(GL_DITHER)
    }

    fun update() {
        val screenWidth = scene.camera.screenWidth
        val screenHeight = scene.camera.screenHeight

        if (fbo.width != screenWidth || fbo.height != screenHeight) {
            fbo.destroy()
            tex1.apply {
                allocate(1, ImageFormat.R32_G32_B32_A32_UI, screenWidth, screenHeight)
                setFilter(GL_NEAREST, GL_NEAREST)
                setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
            }
            tex2.apply {
                allocate(1, ImageFormat.R32_G32_UI, screenWidth, screenHeight)
                setFilter(GL_NEAREST, GL_NEAREST)
                setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
            }
            depthStencilTex.apply {
                allocate(8, ImageFormat.Depth32FStencil8, screenWidth, screenHeight)
                setFilter(GL_LINEAR_MIPMAP_LINEAR, GL_NEAREST)
                setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
            }
            fbo.apply {
                attachNonLayered(tex1, GL_COLOR_ATTACHMENT0, 0)
                attachNonLayered(tex2, GL_COLOR_ATTACHMENT1, 0)
                attachNonLayered(depthStencilTex, GL_DEPTH_STENCIL_ATTACHMENT, 0)
                check()
            }
        }
    }
}