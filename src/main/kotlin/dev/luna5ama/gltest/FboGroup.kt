package dev.luna5ama.gltest

import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.objects.FramebufferObject

class FboGroup(private val scene: AbstractScene) : IGLObjContainer by IGLObjContainer.Impl() {
    val mainFbo = register(MainFbo(scene, "Main"))
    val swapFbo = register(MainFbo(scene, "Swap"))

    private val inputBindingList = listOf(
        listOf(
            ShaderBindingSpecs.Sampler("usam_mainFbo_inputColor", swapFbo.colorTex),
            ShaderBindingSpecs.Sampler("usam_mainFbo_inputDepth", swapFbo.depthStencilTex)
        ),
        listOf(
            ShaderBindingSpecs.Sampler("usam_mainFbo_inputColor", mainFbo.colorTex),
            ShaderBindingSpecs.Sampler("usam_mainFbo_inputDepth", mainFbo.depthStencilTex)
        )
    )
    private val outputBindingList = listOf(listOf(
        ShaderBindingSpecs.Sampler("usam_mainFbo_outputColor", swapFbo.colorTex),
        ShaderBindingSpecs.Sampler("usam_mainFbo_outputDepth", swapFbo.depthStencilTex)
    ),
        listOf(
            ShaderBindingSpecs.Sampler("usam_mainFbo_outputColor", mainFbo.colorTex),
            ShaderBindingSpecs.Sampler("usam_mainFbo_outputDepth", mainFbo.depthStencilTex)
        ))

    private var swapIdx = 0
    private var fbos = listOf(swapFbo, mainFbo)

    val input get() = fbos[swapIdx]
    val output get() =  fbos[swapIdx xor 1]
    val combined = register(FramebufferObject())

    val inputBindings get() =  inputBindingList[swapIdx]
    val outputBindings get() =  outputBindingList[swapIdx xor 1]

    fun swap() {
        swapIdx = swapIdx xor 1
    }

    fun reset() {
        swapIdx = 0

        swapFbo.update()
        mainFbo.update()

        glColorMask(true, true, true, true)

        mainFbo.fbo.bind()
        glViewport(0, 0, scene.camera.screenWidth, scene.camera.screenHeight)
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        glClearDepth(0.0)
        glClearStencil(0)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT or GL_STENCIL_BUFFER_BIT)

        swapFbo.fbo.bind()
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        glClearDepth(0.0)
        glClearStencil(0)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT or GL_STENCIL_BUFFER_BIT)
//            glClearNamedFramebufferf(mainFbo.fbo.id, GL_COLOR, 0, 3.0f, 3.0f, 3.0f, 2.0f)
//            glClearNamedFramebufferfi(mainFbo.fbo.id, GL_DEPTH_STENCIL, 0, 1.0f, 0)
//
//            glClearNamedFramebufferf(swapFbo.fbo.id, GL_COLOR, 0, 0.0f, 0.0f, 0.0f, 0.0f)
//            glClearNamedFramebufferfi(swapFbo.fbo.id, GL_DEPTH_STENCIL, 0, 1.0f, 0)

        if (mainFbo.fbo.sizeBit != combined.sizeBit) {
            combined.apply {
                destroyFbo()
                attach(mainFbo.colorTex, GL_COLOR_ATTACHMENT0)
                attach(swapFbo.colorTex, GL_COLOR_ATTACHMENT1)
                attach(mainFbo.depthStencilTex, GL_DEPTH_STENCIL_ATTACHMENT)
                setDrawBufferAll()
            }
        }
    }
}