package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.nearestClampEdge
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.api.GL_RGBA_INTEGER
import dev.luna5ama.glwrapper.api.GL_UNSIGNED_INT
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.kmogus.Ptr

class PointCloudVisibilityBuffer(private val scene: PointCloudScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val texRender = register(TextureObject.Texture2D()).apply {
        label("VBuffer.Combined")
    }
    private val tex = register(TextureObject.Texture2D()).apply {
        label("VBuffer.Combined")
    }

    val imageBindings = listOf(
        ShaderBindingSpecs.Image("uimg_vbufferRender", tex),
        ShaderBindingSpecs.Image("uimg_vbuffer", tex)
    )
    val samplerBindings = listOf(
        ShaderBindingSpecs.Sampler("usam_vbufferRender", tex),
        ShaderBindingSpecs.Sampler("usam_vbuffer", tex)
    )

    fun update() {
        if (scene.fboGroup.combined.sizeBit != tex.sizeBit) {
            texRender.apply {
                destroy()
                allocate(1, ImageFormat.R32_G32_UI, scene.camera.screenWidth, scene.camera.screenHeight)
                nearestClampEdge()
            }
            tex.apply {
                destroy()
                allocate(1, ImageFormat.R32_G32_UI, scene.camera.screenWidth, scene.camera.screenHeight)
                nearestClampEdge()
            }
        }

        tex.clearImage(GL_RGBA_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
        tex.clearImage(GL_RGBA_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
    }
}