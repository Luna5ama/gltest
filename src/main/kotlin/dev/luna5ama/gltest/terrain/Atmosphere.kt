package dev.luna5ama.gltest.terrain

import dev.luna5ama.gltest.FboGroup
import dev.luna5ama.gltest.uniform3f
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import org.joml.Vector3d

class Atmosphere(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val aerialShader = register(
        ShaderProgram(
            ShaderSource.Vert("/terrain/atmosphere/Aerial.vert.glsl"),
            ShaderSource.Frag("/terrain/atmosphere/Aerial.frag.glsl")
        )
    ).apply {
        label("AtmoSphere.Aerial")
    }

    fun postPass(fboGroup: FboGroup) {
        postPassParams()

        glBlitNamedFramebuffer(
            fboGroup.input.fbo.id,
            fboGroup.output.fbo.id,
            0, 0, fboGroup.input.fbo.width, fboGroup.input.fbo.height,
            0, 0, fboGroup.output.fbo.width, fboGroup.output.fbo.height,
            GL_COLOR_BUFFER_BIT,
            GL_LINEAR
        )

        aerialShader.bind()
        aerialShader.applyBinding {
            sampler(scene.waterGBuffer.genericTexBindings)

            buffer(scene.camera.uboBinding)
            buffer(scene.rtwsm.uboBinding)
        }

        fboGroup.input.colorTex.bindTextureUnit(5)
        fboGroup.output.fbo.bind()

        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glDepthFunc(GL_NOTEQUAL)
        glDisable(GL_STENCIL_TEST)
        glDisable(GL_BLEND)

        scene.basic.drawQuad()

        fboGroup.swap()
    }

    private fun postPassParams() {
//        val betaScAir = Vector3d(6.95e-6, 1.18e-5, 2.44e-5)
//        val betaScHaze = Vector3d(2e-5, 3e-5, 4e-5)

        val betaScAir = Vector3d(6.95e-2, 1.18e-1, 2.44e-1)
        val betaScHaze = Vector3d(2e-2, 3e-2, 4e-2)

//        val betaScAir = Vector3d(1.95e-2, 1.1e-1, 2.94e-1)
//        val betaScHaze = Vector3d(4e-2, 4e-2, 4e-2)

//        val betaScAir = Vector3d(6.95e-2, 1.18e-1, 2.44e-1)
//        val betaScHaze = Vector3d(4e-2, 4e-2, 4e-2)

        val inScatterG = 0.8f
        val hazeG = 0.6f

        val rayleighLengthCoeff = 0.005f
        val mieLengthCoeff = 0.001f

        aerialShader.uniform2f("uCoefficients", rayleighLengthCoeff, mieLengthCoeff)
        aerialShader.uniform3f("uBetaScAir", betaScAir)
        aerialShader.uniform3f("uBetaScHaze", betaScHaze)
        aerialShader.uniform2f("uMieG", inScatterG, hazeG)
    }
}