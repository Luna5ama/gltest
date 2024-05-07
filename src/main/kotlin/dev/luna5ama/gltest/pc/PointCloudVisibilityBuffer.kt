package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.nearestClampEdge
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr

class PointCloudVisibilityBuffer(private val scene: PointCloudScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val texRender = register(TextureObject.Texture2D()).apply {
        label("VBuffer.Combined")
    }
    private val tex = register(TextureObject.Texture2D()).apply {
        label("VBuffer.Combined")
    }

    val imageBindings = listOf(
        ShaderBindingSpecs.Image("uimg_vbufferRender", texRender),
        ShaderBindingSpecs.Image("uimg_vbuffer", tex)
    )
    val samplerBindings = listOf(
        ShaderBindingSpecs.Sampler("usam_vbufferRender", texRender),
        ShaderBindingSpecs.Sampler("usam_vbuffer", tex)
    )
    private val bufferRender = register(BufferObject.Immutable()).apply {
        label("VBuffer.Render")
    }
    val bufferBinding = ShaderBindingSpecs.Buffer("VBufferRender", bufferRender, BufferTarget.ShaderStorage)

    fun update() {
        if (scene.fboGroup.combined.sizeBit != tex.sizeBit) {
            texRender.apply {
                destroy()
                allocate(1, ImageFormat.R32_UI, scene.camera.screenWidth, scene.camera.screenHeight)
                nearestClampEdge()
            }
            bufferRender.apply {
                destroy()
                allocate(scene.camera.screenWidth * scene.camera.screenHeight * 8L, 0)
            }
            tex.apply {
                destroy()
                allocate(1, ImageFormat.R32_G32_UI, scene.camera.screenWidth, scene.camera.screenHeight)
                nearestClampEdge()
            }
        }

        MemoryStack {
            val ptr = malloc(4).ptr
            ptr.setInt(Int.MAX_VALUE)
            bufferRender.clear(GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, ptr)
        }

        texRender.clearImage(GL_RGBA_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
        tex.clearImage(GL_RGBA_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
    }
}