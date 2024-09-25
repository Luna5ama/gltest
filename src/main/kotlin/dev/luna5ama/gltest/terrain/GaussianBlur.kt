package dev.luna5ama.gltest.terrain

import dev.luna5ama.glwrapper.*
import dev.luna5ama.glwrapper.base.GL_BLEND
import dev.luna5ama.glwrapper.base.GL_COLOR_ATTACHMENT0
import dev.luna5ama.glwrapper.base.glDisable
import dev.luna5ama.glwrapper.base.glViewport
import dev.luna5ama.glwrapper.enums.*
import dev.luna5ama.glwrapper.objects.*
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.max

class GaussianBlur(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    private val map = mutableMapOf<ParamKey, ProgramPair>()

    private val fbo1 = register(FramebufferObject())
    private val fbo2 = register(FramebufferObject())
    private val sampler = scene.samplerManager.get {
        filter(FilterMode.Linear, FilterMode.Linear)
        wrap(WrapMode.ClampToEdge, WrapMode.ClampToEdge, WrapMode.ClampToEdge)
    }

    fun process(
        type: Type,
        kernelBaseRadius: Int,
        kernelRealSize: Int,
        radiusMul: Float,
        input: TextureObject.Texture2D,
        output: TextureObject.Texture2D,
    ) {
        process(
            type, kernelBaseRadius, kernelRealSize, radiusMul,
            input, 0, 0, input.width, input.height, 0,
            output, 0, 0, output.width, output.height, 0
        )
    }

    fun process(
        type: Type,
        kernelBaseRadius: Int,
        kernelRealSize: Int,
        radiusMul: Float,
        input: TextureObject.Texture2D,
        inputLevel: Int,
        output: TextureObject.Texture2D,
        outputLevel: Int
    ) {
        process(
            type, kernelBaseRadius, kernelRealSize, radiusMul,
            input, 0, 0, input.width, input.height, inputLevel,
            output, 0, 0, output.width, output.height, outputLevel
        )
    }

    fun process(
        type: Type,
        kernelBaseRadius: Int,
        kernelRealSize: Int,
        radiusMul: Float,
        input: TextureObject.Texture2D,
        inputX: Int, inputY: Int, inputWidth: Int, inputHeight: Int,
        inputLevel: Int,
        output: TextureObject.Texture2D,
        outputX: Int, outputY: Int, outputWidth: Int, outputHeight: Int,
        outputLevel: Int
    ) {
        scene.textureCache.useStack {
            glDisable(GL_BLEND)

            val shader = getShaderProgram(ParamKey(type, kernelBaseRadius, kernelRealSize))

            val swapTexture =
                getTexture2D(input.internalformat!!, max(inputWidth, outputWidth), max(inputHeight, outputHeight))
            fbo1.clearAttachments()
            fbo1.attach(swapTexture, GL_COLOR_ATTACHMENT0, 0)

            fbo2.clearAttachments()
            fbo2.attach(output, GL_COLOR_ATTACHMENT0, outputLevel)

            fbo1.bind()
            glViewport(0, 0, outputWidth, inputHeight)
            renderPass(shader.h, input, radiusMul, inputLevel, inputX, inputY, inputWidth, inputHeight)

            fbo2.bind()
            glViewport(outputX, outputY, outputWidth, outputHeight)
            renderPass(shader.v, swapTexture, radiusMul, 0, 0, 0, outputWidth, inputHeight)

            glViewport(0, 0, scene.camera.screenWidth, scene.camera.screenHeight)
        }
    }

    private fun renderPass(
        shader: ShaderProgram,
        input: TextureObject.Texture2D,
        radiusMul: Float,
        inputLevel: Int, inputX: Int, inputY: Int, inputWidth: Int, inputHeight: Int
    ) {
        val levelWidth = input.width shr inputLevel
        val levelHeight = input.height shr inputLevel
        shader.bind()
        shader.uniform1f("uval_radiusMul", radiusMul)
        shader.uniform4f(
            "uval_inputUVRange",
            inputX.toFloat() / levelWidth,
            inputY.toFloat() / levelHeight,
            (inputX + inputWidth).toFloat() / levelWidth,
            (inputY + inputHeight).toFloat() / levelHeight
        )
        shader.uniform2f("uval_inputTexelSize", 1.0f / levelWidth, 1.0f / levelHeight)
        shader.applyBinding {
            sampler("usam_input", input, sampler)
        }
        scene.basic.drawQuad()
    }

    private fun getShaderProgram(key: ParamKey): ProgramPair {
        return map.getOrPut(key) { createShaderProgram(key) }
    }

    private fun createShaderProgram(key: ParamKey): ProgramPair {
        val params = createParams(key)
        val offsets = params.offsets
        val weights = params.weights
        val codeH = buildString {
            for (i in offsets.indices) {
                append("float offset$i = ${offsets[i]} * uval_inputTexelSize.x * uval_radiusMul;")
            }
            for (i in offsets.indices) {
                val idx = offsets.lastIndex - i
                val weight = weights[idx]
                append("outputValue += sampleValue(vec2(fTexCoord.x - offset$idx, fTexCoord.y)) * $weight;")
            }
            for (i in offsets.indices) {
                val weight = weights[i]
                append("outputValue += sampleValue(vec2(fTexCoord.x + offset$i, fTexCoord.y)) * $weight;")
            }
        }

        val codeV = buildString {
            for (i in offsets.indices) {
                append("float offset$i = ${offsets[i]} * uval_inputTexelSize.y * uval_radiusMul;")
            }
            for (i in offsets.indices) {
                val idx = offsets.lastIndex - i
                val weight = weights[idx]
                append("outputValue += sampleValue(vec2(fTexCoord.x, fTexCoord.y - offset$idx)) * $weight;")
            }
            for (i in offsets.indices) {
                val weight = weights[i]
                append("outputValue += sampleValue(vec2(fTexCoord.x, fTexCoord.y + offset$i)) * $weight;")
            }
        }

        return ProgramPair(
            register(ShaderProgram(
                ShaderSource.Vert("terrain/filters/LinearGaussian.vert"),
                ShaderSource.Frag("terrain/filters/LinearGaussian.frag") {
                    define("CODE", codeH)
                }
            )),
            register(ShaderProgram(
                ShaderSource.Vert("terrain/filters/LinearGaussian.vert"),
                ShaderSource.Frag("terrain/filters/LinearGaussian.frag") {
                    define("CODE", codeV)
                }
            ))
        )
    }

    private fun createParams(key: ParamKey): Params {
        val offsets: DoubleArray
        val weights: DoubleArray

        val pascalOriginal = pascalTriangle(key.radius * 2 + 1)
        val pSize = key.realSize
        val halfWeight0 = Array(pSize) {
            pascalOriginal[it + key.radius]
        }
        val sum = (halfWeight0.sumOf { it } * BigInteger.TWO).toBigDecimal(32)
        val halfWeight = DoubleArray(pSize) {
            (halfWeight0[it].toBigDecimal(32) / sum).toDouble()
        }

        when (key.type) {
            Type.DISCRETE -> {
                offsets = DoubleArray(pSize) {
                    it.toDouble()
                }
                weights = DoubleArray(pSize) {
                    halfWeight[it]
                }
                check(abs((weights.sum() - weights[0]) * 2.0 + weights[0]) - 1.0 < 0.001)
            }

            Type.LINEAR -> {
                val centerFree = pSize and 1 == 1
                val newSize = (pSize + 1) / 2
                offsets = DoubleArray(newSize)
                weights = DoubleArray(newSize)
                if (centerFree) {
                    offsets[0] = 0.0
                    weights[0] = halfWeight[0]
                    for (i in 0 until newSize - 1) {
                        val oi2 = (i + 1) * 2
                        val oi1 = oi2 - 1
                        val w1 = halfWeight[oi1]
                        val w2 = halfWeight[oi2]
                        val totalWeight = w1 + w2
                        offsets[i + 1] = 1.0 + i * 2.0 + w2 / totalWeight
                        weights[i + 1] = totalWeight
                    }
                } else {
                    for (i in 0 until newSize) {
                        val oi2 = i * 2 + 1
                        val oi1 = oi2 - 1
                        val w1 = halfWeight[oi1]
                        val w2 = halfWeight[oi2]
                        val totalWeight = w1 + w2
                        offsets[i] = i * 2.0 + w2 / totalWeight
                        weights[i] = totalWeight
                    }
                }
            }
        }

        check(offsets.size == weights.size)

        return Params(key, offsets, weights)
    }

    private fun pascalTriangle(n: Int): Array<BigInteger> {
        val result = Array<BigInteger>(n) { BigInteger.ZERO }
        result[0] = BigInteger.ONE
        val nInt = n.toBigInteger()
        for (i in 1 until n) {
            val iInt = i.toBigInteger()
            result[i] = result[i - 1] * (nInt - iInt) / iInt
        }
        return result
    }

    private class ParamKey(val type: Type, baseRadius: Int, val realSize: Int) {
        val radius = baseRadius or 1

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParamKey) return false

            if (type != other.type) return false
            if (realSize != other.realSize) return false
            if (radius != other.radius) return false

            return true
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + realSize
            result = 31 * result + radius
            return result
        }
    }

    private data class Params(val key: ParamKey, val offsets: DoubleArray, val weights: DoubleArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Params) return false

            if (key != other.key) return false

            return true
        }

        override fun hashCode(): Int {
            return key.hashCode()
        }
    }

    private class ProgramPair(val h: ShaderProgram, val v: ShaderProgram)

    enum class Type {
        DISCRETE, LINEAR
    }
}