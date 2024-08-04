package dev.luna5ama.gltest

import dev.luna5ama.glwrapper.api.GL_TRIANGLES
import dev.luna5ama.glwrapper.api.glDrawArrays
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.gltest.terrain.IGLObjContainer
import dev.luna5ama.gltest.terrain.register
import dev.luna5ama.glwrapper.*
import dev.luna5ama.glwrapper.enums.GLDataType
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.VertexArrayObject

class Basic : IGLObjContainer by IGLObjContainer.Impl() {
    val blitShader: ShaderProgram.Variants<SampleChannel> = object : ShaderProgram.Variants<SampleChannel>() {
        override fun create(key: SampleChannel): ShaderProgram {
            return register(ShaderProgram(
                ShaderSource.Vert("terrain/Blit.vert.glsl"),
                ShaderSource.Frag("terrain/Blit.frag.glsl") {
                    define(key.defineName)
                }
            ))
        }
    }
    val blitDepthOutputShader = register(ShaderProgram(
        ShaderSource.Vert("terrain/Blit.vert.glsl"),
        ShaderSource.Frag("terrain/Blit.frag.glsl") {
            define("DEPTH_OUTPUT")
        }
    ))
    val blitDepthShader = register(ShaderProgram(
        ShaderSource.Vert("terrain/Blit.vert.glsl"),
        ShaderSource.Frag("terrain/BlitDepth.frag.glsl")
    ))
    val blit1DV = register(ShaderProgram(
        ShaderSource.Vert("terrain/Blit.vert.glsl"),
        ShaderSource.Frag("terrain/Blit1D.frag.glsl") {
            define("VERTICAL")
        }
    ))
    val blit1DH = register(ShaderProgram(
        ShaderSource.Vert("terrain/Blit.vert.glsl"),
        ShaderSource.Frag("terrain/Blit1D.frag.glsl")
    ))
    val blit1DArrayV = register(ShaderProgram(
        ShaderSource.Vert("terrain/Blit.vert.glsl"),
        ShaderSource.Frag("terrain/Blit1DArray.frag.glsl") {
            define("VERTICAL")
        }
    ))
    val blit1DArrayH = register(ShaderProgram(
        ShaderSource.Vert("terrain/Blit.vert.glsl"),
        ShaderSource.Frag("terrain/Blit1DArray.frag.glsl")
    ))
    val blitVao = register(VertexArrayObject()).apply {
        val vbo = register(BufferObject.Immutable())
        MemoryStack {
            val arr = calloc(6 * 4)
            arr.ptr.setByteInc(-1)
                .setByteInc(1)
                .plus(2L)
                .setByteInc(-1)
                .setByteInc(-1)
                .plus(2L)
                .setByteInc(1)
                .setByteInc(1)
                .plus(2L)

                .setByteInc(1)
                .setByteInc(-1)
                .plus(2L)
                .setByteInc(1)
                .setByteInc(1)
                .plus(2L)
                .setByteInc(-1)
                .setByteInc(-1)
                .plus(2L)

            vbo.allocate(arr.len, arr.ptr, 0)
        }

        attachVbo(vbo, buildAttribute(4) {
            float(0, 2, GLDataType.GL_BYTE, false)
        })
    }

    fun drawQuad() {
        blitVao.bind()
        glDrawArrays(GL_TRIANGLES, 0, 6)
    }

    enum class SampleChannel(val defineName: String) {
        RRR("SAMPLE_CHANNEL_RRR"),
        GGG("SAMPLE_CHANNEL_GGG"),
        BBB("SAMPLE_CHANNEL_BBB"),
        AAA("SAMPLE_CHANNEL_AAA"),
        RGB("SAMPLE_CHANNEL_RGB"),
        RGBA("SAMPLE_CHANNEL_RGBA"),
        RGBPA("SAMPLE_CHANNEL_RGBA"),
    }
}