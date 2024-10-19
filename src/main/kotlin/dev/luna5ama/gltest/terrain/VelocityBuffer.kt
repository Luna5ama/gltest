package dev.luna5ama.gltest.terrain

import dev.luna5ama.gltest.glDebugGroup
import dev.luna5ama.gltest.linearClampEdge
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
    private val prevUvVelocityTex = register(TextureObject.Texture2D())
    private val prevWorldVelocityTex = register(TextureObject.Texture2D())

    val uvDiffTex = register(TextureObject.Texture2D())
    val worldVelocity = register(TextureObject.Texture2D())

    val samplerBindings = listOf(
        ShaderBindingSpecs.Sampler("usam_velocity_prevUvVelocity", prevUvVelocityTex),
        ShaderBindingSpecs.Sampler("usam_velocity_prevWorldVelocity", prevWorldVelocityTex),
        ShaderBindingSpecs.Sampler("usam_velocity_uvVelocity", uvDiffTex),
        ShaderBindingSpecs.Sampler("usam_velocity_worldVelocity", worldVelocity)
    )

    private val fbo = register(FramebufferObject())

    private val prevDepth = register(TextureObject.Texture2D()).apply {
        create()
    }

    private val prevCameraPos = Vector3d()

    private val velocityBufferShader = register(
        ShaderProgram(
            ShaderSource.Vert("terrain/Blit.vert.glsl"),
            ShaderSource.Frag("terrain/vbuffer/VelocityBuffer.frag.glsl")
        )
    )

    private val prevTexBinding = ShaderBindingSpecs.Sampler("usam_prevDepthTex", prevDepth, scene.samplers.linearClampEdge)


    fun render(input: GBuffer) {
        glDebugGroup("update") {
            update(input)
        }
        glDebugGroup("velocity") {
            velocityBuffer(input)
        }
    }

    private fun velocityBuffer(input: GBuffer) {
        fbo.bind()

        glDisable(GL_STENCIL_TEST)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)

        velocityBufferShader.bind()
        velocityBufferShader.applyBinding {
            sampler(input.genericTexBindings)
            sampler(prevTexBinding)

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

        glDebugGroup("copy") {
            checkSizeAndCopy(input.depthStencilTex, prevDepth)
        }
    }

    private fun update(input: GBuffer) {
        if (uvDiffTex.width != input.width || uvDiffTex.height != input.height) {
            fbo.destroy()

            uvDiffTex.apply {
                allocate(1, ImageFormat.R16G16_SN, input.width, input.height)
                linearClampEdge()
            }
            worldVelocity.apply {
                allocate(1, ImageFormat.R16G16B16A16_F, input.width, input.height)
                linearClampEdge()
            }
            fbo.apply {
                attach(uvDiffTex, GL_COLOR_ATTACHMENT0)
                attach(worldVelocity, GL_COLOR_ATTACHMENT1)
                setDrawBufferAll()
                check()
            }
        }

        checkSizeAndCopy(uvDiffTex, prevUvVelocityTex)
        checkSizeAndCopy(worldVelocity, prevWorldVelocityTex)
    }
}