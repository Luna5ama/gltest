package dev.luna5ama.gltest

import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.api.GL_COLOR_ATTACHMENT0
import dev.luna5ama.glwrapper.api.GL_DEPTH_ATTACHMENT
import dev.luna5ama.glwrapper.api.GL_DEPTH_STENCIL_ATTACHMENT
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
                label("MainFBO.$name.Color")
                allocate(1, FORMAT, screenWidth, screenHeight)
                linearClampEdge()
            }
            depthStencilTex.apply {
                label("MainFBO.$name.Depth")
                allocate(1, ImageFormat.Depth32FStencil8NV, screenWidth, screenHeight)
                linearClampEdge()
            }

            fbo.apply {
                clearAttachments()
                attachNonLayered(colorTex, GL_COLOR_ATTACHMENT0) // Regular color
                attachNonLayered(depthStencilTex, GL_DEPTH_STENCIL_ATTACHMENT) // Depth & Stencil
                check()
                label("MainFBO.$name.FBO")
            }
        }
    }

    companion object {
//         val FORMAT = ImageFormat.R32_G32_B32_A32_F
        val FORMAT = ImageFormat.R16_G16_B16_A16_F
    }
}