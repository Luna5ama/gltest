package dev.luna5ama.gltest.pc

import dev.luna5ama.gltest.nearestClampEdge
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.BufferTarget
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr

class VisibilityBuffer(private val scene: PointCloudScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val dataTex1 = register(TextureObject.Texture2D()).apply {
        label("VBuffer.DataTex1")
    }
    private val dataTex2 = register(TextureObject.Texture2D()).apply {
        label("VBuffer.DataTex2")
    }
    val depthTex = register(TextureObject.Texture2D()).apply {
        label("VBuffer.Depth")
    }

    val fbo = register(FramebufferObject()).apply {
        label("VBuffer.FBO")
    }

    val imageBindings = listOf(
        ShaderBindingSpecs.Image("uimg_vBufferTex1", dataTex1),
        ShaderBindingSpecs.Image("uimg_vBufferTex2", dataTex2)
    )
    val samplerBindings = listOf(
        ShaderBindingSpecs.Sampler("usam_vBufferTex1", dataTex1),
        ShaderBindingSpecs.Sampler("usam_vBufferTex2", dataTex2),
        ShaderBindingSpecs.Sampler("usam_vBufferDepth", depthTex)
    )
    private val buffer = register(BufferObject.Immutable()).apply {
        label("VBuffer.DepthQuadIndex")
    }
    val bufferBinding = ShaderBindingSpecs.Buffer("VBufferDepthQuadIndex", buffer, BufferTarget.ShaderStorage)

    private val sw2hwShader = register(ShaderProgram(
        ShaderSource.Vert("pc/Blit.vert"),
        ShaderSource.Frag("pc/vbuffer/SW2HW.frag")
    )).apply {
        label("VBuffer.SW2HW")
    }
    private val hw2swShader = register(ShaderProgram(
        ShaderSource.Comp("pc/vbuffer/HW2SW.comp")
    )).apply {
        label("VBuffer.HW2SW")
    }

    fun update() {
        if (scene.fboGroup.combined.sizeBit != depthTex.sizeBit) {
            dataTex1.apply {
                destroy()
                allocate(1, ImageFormat.R32_UI, scene.camera.screenWidth, scene.camera.screenHeight)
                nearestClampEdge()
            }
            dataTex2.apply {
                destroy()
                allocate(1, ImageFormat.R32_G32_UI, scene.camera.screenWidth, scene.camera.screenHeight)
                nearestClampEdge()
            }

            depthTex.apply {
                destroy()
                allocate(1, ImageFormat.Depth32FNV, scene.camera.screenWidth, scene.camera.screenHeight)
                nearestClampEdge()
            }
            fbo.apply {
                destroyFbo()
                attachNonLayered(dataTex2, GL_COLOR_ATTACHMENT0)
                attachNonLayered(depthTex, GL_DEPTH_ATTACHMENT)
            }

            buffer.apply {
                destroy()
                allocate(scene.camera.screenWidth * scene.camera.screenHeight * 8L, 0)
            }
        }

        MemoryStack {
            val ptr = malloc(4).ptr
            ptr.setFloat(Float.POSITIVE_INFINITY)
            buffer.clear(GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, ptr)
            depthTex.clearImage(GL_DEPTH_COMPONENT, GL_FLOAT, ptr)
        }

        dataTex1.clearImage(GL_RGBA_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
        dataTex2.clearImage(GL_RGBA_INTEGER, GL_UNSIGNED_INT, Ptr.NULL)
    }

    fun blitSW2HW() {
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_ALWAYS)
        glDisable(GL_DEPTH_CLAMP)

        sw2hwShader.bind()
        sw2hwShader.applyBinding {
            sampler(samplerBindings)
            buffer(bufferBinding)
            buffer(scene.camera.uboBinding)
        }
        fbo.bind()
        scene.basic.drawQuad()
    }

    fun blitHW2SW() {
        hw2swShader.bind()
        hw2swShader.applyBinding {
            sampler(samplerBindings)
            image(imageBindings)
            buffer(bufferBinding)
        }
        glDispatchCompute(
            (scene.camera.screenSize.x + 15) / 16,
            (scene.camera.screenSize.y + 15) / 16,
            1
        )
    }
}