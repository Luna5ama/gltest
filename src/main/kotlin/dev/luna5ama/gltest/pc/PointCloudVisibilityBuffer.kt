package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.nearestClampEdge
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.api.GL_COLOR_ATTACHMENT0
import dev.luna5ama.glwrapper.api.GL_RGBA_INTEGER
import dev.luna5ama.glwrapper.api.GL_UNSIGNED_INT
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.kmogus.Ptr

class PointCloudVisibilityBuffer(private val scene: PointCloudScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val quadIndexTex = register(TextureObject.Texture2D()).apply {
        label("VBuffer.QuadIndex")
    }
    private val barycentricCoordTex = register(TextureObject.Texture2D()).apply {
        label("VBuffer.BarycentricCoord")
    }
    private val combinedTex = register(TextureObject.Texture2D()).apply {
        label("VBuffer.Combined")
    }

    val imageBindingSeparated = listOf(
        ShaderBindingSpecs.Image("uimg_quadIndex", quadIndexTex),
        ShaderBindingSpecs.Image("uimg_barycentricCoord", barycentricCoordTex)
    )
    val samplerBindingSeparated = listOf(
        ShaderBindingSpecs.Sampler("usam_quadIndex", quadIndexTex),
        ShaderBindingSpecs.Sampler("usam_barycentricCoord", barycentricCoordTex)
    )

    val imageBindingCombined = listOf(
        ShaderBindingSpecs.Image("uimg_vbufferCombined", combinedTex)
    )
    val samplerBindingCombined = listOf(
        ShaderBindingSpecs.Sampler("usam_vbufferCombined", combinedTex)
    )

    val fbo = register(FramebufferObject()).apply {
        label("VBuffer.FBO")
    }

    fun update() {
        if (scene.fboGroup.combined.sizeBit != fbo.sizeBit) {
            fbo.destroy()

            quadIndexTex.destroy()
            barycentricCoordTex.destroy()

            quadIndexTex.apply {
                allocate(1, ImageFormat.R32_UI, scene.camera.screenWidth, scene.camera.screenHeight)
                nearestClampEdge()
            }
            barycentricCoordTex.apply {
                allocate(1, ImageFormat.R32_UI, scene.camera.screenWidth, scene.camera.screenHeight)
                nearestClampEdge()
            }
            combinedTex.apply {
                allocate(1, ImageFormat.R32_G32_UI, scene.camera.screenWidth, scene.camera.screenHeight)
                nearestClampEdge()
            }
            fbo.apply {
                attachNonLayered(combinedTex, GL_COLOR_ATTACHMENT0)
                check()
                setDrawBufferAll()
            }
        }

        quadIndexTex.clearImage(GL_RGBA_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
        barycentricCoordTex.clearImage(GL_RGBA_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
        combinedTex.clearImage(GL_RGBA_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
    }
}