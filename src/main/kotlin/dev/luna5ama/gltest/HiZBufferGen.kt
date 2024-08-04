package dev.luna5ama.gltest

import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.TerrainScene
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderBindingSpecs
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.api.*
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.enums.ShaderStage
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.objects.TextureObject
import org.joml.Vector2f
import kotlin.math.max

class HiZBufferGen(val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val depthShader = ShaderProgram(
        ShaderSource.Vert("terrain/filters/HiZ.vert.glsl"),
        ShaderSource.Frag("terrain/filters/HiZ.frag.glsl") {
            define("DEPTH_OUTPUT")
        }
    )
    private val colorShader = ShaderProgram(
        ShaderSource.Vert("terrain/filters/HiZ.vert.glsl"),
        ShaderSource.Frag("terrain/filters/HiZ.frag.glsl") {
            define("COLOR_OUTPUT")
        }
    )

    private val fbo = register(FramebufferObject())

    fun processDepth(
        input: TextureObject.Texture2D,
        output: TextureObject.Texture2D,
        kernelType: KernelType,
        depthType: DepthType ,
        planeZ: Vector2f
    ) {
        require(input.internalformat is ImageFormat.Depth) { "Input texture must be depth texture" }
        require(output.internalformat is ImageFormat.Depth) { "Output texture must be depth texture" }

        var inputWidth = input.width
        var inputHeight = input.height

        glDisable(GL_STENCIL_TEST)
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_ALWAYS)
        glDepthMask(true)
        glDisable(GL_BLEND)

        if (input.id != output.id) {
            glViewport(0, 0, inputWidth, inputHeight)

            glNamedFramebufferTexture(fbo.id, GL_DEPTH_ATTACHMENT, output.id, 0)
            fbo.check()
            fbo.bind()
            scene.basic.blitShader.bind()
            scene.basic.blitShader.applyBinding {
                sampler("usam_input", input, scene.samplers.nearestClampEdge)
                subroutine(scene.basic.sampleRRRBinding)
                subroutine(depthType.subroutineBindings)
                subroutine(kernelType.subroutineBinding)
            }
            scene.basic.drawQuad()
        }

        output.bindTextureUnit(0)

        depthShader.uniform2f("uval_planeZ", planeZ)

        for (i in 1 until output.levels) {
            glNamedFramebufferTexture(fbo.id, GL_DEPTH_ATTACHMENT, output.id, i)
            fbo.bind()

            val halfWidth = inputWidth / 2
            val halfHeight = inputHeight / 2

            depthShader.bind()
            depthShader.uniform4f(
                "uval_InputSize",
                inputWidth.toFloat(),
                inputHeight.toFloat(),
                0.0f,
                0.0f
            )
            depthShader.uniform1i("uval_level", max(0, i - 1))
            depthShader.applyBinding {
                sampler("usam_input", output, scene.samplers.nearestClampEdge)
                subroutine(kernelType.subroutineBinding)
                subroutine(depthType.subroutineBindings)
            }

            glViewport(0, 0, halfWidth, halfHeight)

            scene.basic.drawQuad()

            inputWidth = halfWidth
            inputHeight = halfHeight
        }

        glViewport(0, 0, scene.camera.screenWidth, scene.camera.screenHeight)
        scene.samplers.clearBinding()

        glDepthFunc(GL_LEQUAL)

    }

    fun processColor(input: TextureObject.Texture2D, output: TextureObject.Texture2D, kernelType: KernelType) {
        var inputWidth = input.width
        var inputHeight = input.height

        glDisable(GL_STENCIL_TEST)
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_ALWAYS)
        glDepthMask(true)
        glDisable(GL_BLEND)

        if (input.id != output.id) {
            glViewport(0, 0, inputWidth, inputHeight)

            glNamedFramebufferTexture(fbo.id, GL_COLOR_ATTACHMENT0, output.id, 0)
            fbo.check()
            fbo.bind()
            scene.basic.blitShader.bind()
            scene.basic.blitShader.applyBinding {
                sampler("usam_input", input, scene.samplers.nearestClampEdge)
                subroutine(scene.basic.sampleRGBABinding)
            }
            scene.basic.drawQuad()
        }

        output.bindTextureUnit(0)

        for (i in 1 until output.levels) {
            glNamedFramebufferTexture(fbo.id, GL_COLOR_ATTACHMENT0, output.id, i)
            fbo.bind()

            val halfWidth = inputWidth / 2
            val halfHeight = inputHeight / 2

            colorShader.bind()
            colorShader.uniform4f(
                "uval_InputSize",
                inputWidth.toFloat(),
                inputHeight.toFloat(),
                0.0f,
                0.0f
            )
            colorShader.uniform1i("uval_level", max(0, i - 1))
            colorShader.applyBinding {
                sampler("usam_input", output, scene.samplers.nearestClampEdge)
                subroutine(kernelType.subroutineBinding)
            }

            glViewport(0, 0, halfWidth, halfHeight)

            scene.basic.drawQuad()

            inputWidth = halfWidth
            inputHeight = halfHeight
        }

        glViewport(0, 0, scene.camera.screenWidth, scene.camera.screenHeight)
        scene.samplers.clearBinding()

        glDepthFunc(GL_LEQUAL)
    }

    enum class KernelType(val subroutineBinding: ShaderBindingSpecs.Subroutine) {
        MIN(ShaderBindingSpecs.Subroutine("usubroutine_kernel", ShaderStage.FragmentShader, "Kernel_min")),
        MAX(ShaderBindingSpecs.Subroutine("usubroutine_kernel", ShaderStage.FragmentShader, "Kernel_max")),
        AVG(ShaderBindingSpecs.Subroutine("usubroutine_kernel", ShaderStage.FragmentShader, "Kernel_avg"))
    }

    enum class DepthType(vararg subroutineBindings: ShaderBindingSpecs.Subroutine) {
        LINEAR(
            ShaderBindingSpecs.Subroutine(
                "usubroutine_depthToFloat",
                ShaderStage.FragmentShader,
                "DepthToFloat_Linear"
            ),
            ShaderBindingSpecs.Subroutine("usubroutine_floatToDepth", ShaderStage.FragmentShader, "FloatToDepth_Linear")
        ),
        LOG(
            ShaderBindingSpecs.Subroutine("usubroutine_depthToFloat", ShaderStage.FragmentShader, "DepthToFloat_Log"),
            ShaderBindingSpecs.Subroutine("usubroutine_floatToDepth", ShaderStage.FragmentShader, "FloatToDepth_Log")
        );

        val subroutineBindings = subroutineBindings.toList()
    }
}