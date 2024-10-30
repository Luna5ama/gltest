package dev.luna5ama.gltest.terrain

import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.linearClampEdge
import dev.luna5ama.gltest.nearestClampEdge
import dev.luna5ama.gltest.terrain.post.GBuffer
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.objects.TextureObject
import org.joml.Vector3d

class VelocityBuffer(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    val uvDiffTex = register(TextureObject.Texture2D())
    val projFlags = register(TextureObject.Texture2D())
    val ndpTex = register(TextureObject.Texture2D())
    val prevNDTex = register(TextureObject.Texture2D()).apply { create() }

    val samplerBindings = listOf(
        ShaderBindingSpecs.Sampler("usam_velocity_uvVelocity", uvDiffTex),
        ShaderBindingSpecs.Sampler("usam_velocity_projFlags", projFlags),
        ShaderBindingSpecs.Sampler("usam_velocity_ndTex", ndpTex, scene.samplers.nearestClampEdge),
        ShaderBindingSpecs.Sampler("usam_velocity_prevNDTex", prevNDTex, scene.samplers.nearestClampEdge)
    )

    private val fbo = register(FramebufferObject())
    private val prevCameraPos = Vector3d()

    private val velocityBufferShader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/Blit.vert.glsl"),
            ShaderSource.Frag("terrain/vbuffer/VelocityBuffer.frag.glsl")
        )
    )

    fun render(input: GBuffer) {
        glDebugGroup("update") {
            update(input)
        }
        glDebugGroup("velocity") {
            velocityBuffer(input)
        }
    }

    private fun velocityBuffer(input: GBuffer) {
        checkSizeAndCopy(ndpTex, prevNDTex)
        scene.normalDepthPacking.render(input, ndpTex)

        fbo.bind()

        glDisable(GL_STENCIL_TEST)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)

        velocityBufferShader.bind()
        velocityBufferShader.applyBinding {
            sampler(input.genericTexBindings)
            sampler(samplerBindings)

            buffer(scene.camera.noJitterUboBinding)
        }
        velocityBufferShader.uniform3f(
            "uCameraDelta",
            (prevCameraPos.x - scene.camera.pos.x).toFloat(),
            (prevCameraPos.y - scene.camera.pos.y).toFloat(),
            (prevCameraPos.z - scene.camera.pos.z).toFloat()
        )

        scene.basic.drawQuad()

        prevCameraPos.set(scene.camera.pos)
    }

    private fun update(input: GBuffer) {
        if (uvDiffTex.width != input.width || uvDiffTex.height != input.height) {
            fbo.destroy()

            uvDiffTex.apply {
                allocate(1, ImageFormat.R16G16_SN, input.width, input.height)
                linearClampEdge()
            }
            projFlags.apply {
                allocate(1, ImageFormat.R8G8_UN, input.width, input.height)
                linearClampEdge()
            }
            ndpTex.apply {
                destroy()
                allocate(1, ImageFormat.R32G32_UI, input.width, input.height)
                nearestClampEdge()
            }
            fbo.apply {
                attach(uvDiffTex, GL_COLOR_ATTACHMENT0)
                attach(projFlags, GL_COLOR_ATTACHMENT1)
                setDrawBufferAll()
                check()
            }
        }
    }
}