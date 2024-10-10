package dev.luna5ama.gltest

import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.TerrainScene
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.objects.FramebufferObject
import dev.luna5ama.glwrapper.objects.TextureObject
import org.joml.Vector2f
import kotlin.math.max

class HiZBufferGen(val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val shaders = object : ShaderProgram.Variants<ShaderKey>() {
        override fun create(key: ShaderKey): ShaderProgram {
            return register(ShaderProgram(
                ShaderSource.Vert("terrain/filters/HiZ.vert.glsl"),
                ShaderSource.Frag("terrain/filters/HiZ.frag.glsl") {
                    if (key.depthOutput) {
                        define("DEPTH_OUTPUT")
                    }
                    define(key.kernelType.defineName)
                    key.depthType?.let { define(it.defineName) }
                }
            ))
        }
    }

    private val depthFbo = register(FramebufferObject())
    private val colorFbo = register(FramebufferObject())

    fun processDepth(
        input: TextureObject.Texture2D,
        output: TextureObject.Texture2D,
        kernelType: KernelType,
        depthType: DepthType,
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

            depthFbo.clearAttachments()
            depthFbo.attach(output, GL_DEPTH_ATTACHMENT, 0)
            depthFbo.check()
            depthFbo.bind()
            val shader = scene.basic.blitShader[Basic.SampleChannel.RRR]
            shader.bind()
            shader.applyBinding {
                sampler("usam_input", input, scene.samplers.nearestClampEdge)
            }
            scene.basic.drawQuad()
        }

        output.bindTextureUnit(0)

        val depthShader = shaders[ShaderKey(kernelType, depthType)]
        depthShader.uniform2f("uval_planeZ", planeZ)

        for (i in 1 until output.levels) {
            depthFbo.clearAttachments()
            depthFbo.attach(output, GL_DEPTH_ATTACHMENT, i)
            depthFbo.bind()

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
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)

        if (input.id != output.id) {
            glViewport(0, 0, inputWidth, inputHeight)

            colorFbo.clearAttachments()
            colorFbo.attach(output, GL_COLOR_ATTACHMENT0, 0)
            colorFbo.check()
            colorFbo.bind()
            val shader = scene.basic.blitShader[Basic.SampleChannel.RGBA]
            shader.bind()
            shader.applyBinding {
                sampler("usam_input", input, scene.samplers.nearestClampEdge)
            }
            scene.basic.drawQuad()
        }

        output.bindTextureUnit(0)

        for (i in 1 until output.levels) {
            colorFbo.clearAttachments()
            colorFbo.attach(output, GL_COLOR_ATTACHMENT0, i)
            colorFbo.bind()

            val halfWidth = inputWidth / 2
            val halfHeight = inputHeight / 2

            val colorShader = shaders[ShaderKey(kernelType)]
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
            }

            glViewport(0, 0, halfWidth, halfHeight)

            scene.basic.drawQuad()

            inputWidth = halfWidth
            inputHeight = halfHeight
        }

        glViewport(0, 0, scene.camera.screenWidth, scene.camera.screenHeight)
        scene.samplers.clearBinding()
    }

    private class ShaderKey private constructor(
        val kernelType: KernelType,
        val depthType: DepthType?,
        val depthOutput: Boolean
    ) {
        constructor(kernelType: KernelType) : this(kernelType, null, false)
        constructor(kernelType: KernelType, depthType: DepthType) : this(kernelType, depthType, true)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ShaderKey) return false

            if (kernelType != other.kernelType) return false
            if (depthType != other.depthType) return false
            if (depthOutput != other.depthOutput) return false

            return true
        }

        override fun hashCode(): Int {
            var result = kernelType.hashCode()
            result = 31 * result + depthType.hashCode()
            result = 31 * result + depthOutput.hashCode()
            return result
        }
    }

    enum class KernelType(val defineName: String) {
        MIN("KERNEL_MIN"),
        MAX("KERNEL_MAX"),
        AVG("KERNEL_AVG");
    }

    enum class DepthType(val defineName: String) {
        LINEAR("DEPTH_LINEAR"),
        LOG("DEPTH_LOG");
    }
}