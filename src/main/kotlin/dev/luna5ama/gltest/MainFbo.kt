package dev.luna5ama.gltest

import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.base.GL_COLOR_ATTACHMENT0
import dev.luna5ama.glwrapper.base.GL_DEPTH_STENCIL_ATTACHMENT
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.objects.TextureObject

class MainFbo(private val scene: AbstractScene, private val name: String) : IGLObjContainer by IGLObjContainer.Impl() {
    val colorTex = register(TextureObject.Texture2D())
    val depthStencilTex = register(TextureObject.Texture2D())
    val fbo = register(FramebufferObject())

    fun update() {
        val screenWidth = scene.camera.screenWidth
        val screenHeight = scene.camera.screenHeight
        if (fbo.width != screenWidth || fbo.height != screenHeight) {
            fbo.destroy()

            colorTex.apply {
                label = "MainFBO.$name.Color"
                allocate(1, FORMAT, screenWidth, screenHeight)
                linearClampEdge()
            }
            depthStencilTex.apply {
                label = "MainFBO.$name.Depth"
                allocate(1, ImageFormat.Depth32FStencil8, screenWidth, screenHeight)
                linearClampEdge()
            }

            fbo.apply {
                clearAttachments()
                attach(colorTex, GL_COLOR_ATTACHMENT0) // Regular color
                attach(depthStencilTex, GL_DEPTH_STENCIL_ATTACHMENT) // Depth & Stencil
                check()
                label = "MainFBO.$name.FBO"
            }
        }
    }

    companion object {
//         val FORMAT = ImageFormat.R32G32B32A32_F
        val FORMAT = ImageFormat.R16G16B16A16_F
    }
}