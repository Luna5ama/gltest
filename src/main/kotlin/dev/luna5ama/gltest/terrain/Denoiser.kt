package dev.luna5ama.gltest.terrain

import dev.luna5ama.glwrapper.ShaderProgram
import dev.luna5ama.glwrapper.ShaderSource
import dev.luna5ama.glwrapper.base.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
import dev.luna5ama.glwrapper.base.glDispatchCompute
import dev.luna5ama.glwrapper.base.glMemoryBarrier
import dev.luna5ama.glwrapper.objects.TextureObject

class Denoiser(private val scene: TerrainScene) : IGLObjContainer by IGLObjContainer.Impl() {
    val shaders = object : ShaderProgram.Variants<Direction>() {
        override fun create(key: Direction): ShaderProgram {
            return register(ShaderProgram(
                ShaderSource.Comp("terrain/Denoiser.comp") {
                    define(key.name)
                }
            )).apply {
                label = "Denoiser-${key.name}"
            }
        }
    }

    fun process(inout: TextureObject.Texture2D, ndpTex: TextureObject.Texture2D, iteration: Int) {
        val wgSizeX = 128
        val wgXH = (inout.sizeX + wgSizeX - 1) / wgSizeX
        val wgXV = (inout.sizeY + wgSizeX - 1) / wgSizeX
        scene.textureCache.useStack {
            val swapTexture = getTexture2D(inout.internalformat!!, inout.sizeX, inout.sizeY)
            repeat(iteration) {
                val shaderH = shaders[Direction.HORIZONTAL]
                shaderH.bind()
                shaderH.applyBinding {
                    buffer(scene.camera.uboBinding)
                    sampler("usam_input", inout)
                    sampler(    "usam_ndpTex", ndpTex)
                    image("uimg_output", swapTexture)
                }
                glDispatchCompute(wgXH, inout.sizeY, 1)
                glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

                val shaderV = shaders[Direction.VERTICAL]
                shaderV.bind()
                shaderV.applyBinding {
                    buffer(scene.camera.uboBinding)
                    sampler("usam_input", swapTexture)
                    sampler("usam_ndpTex", ndpTex)
                    image("uimg_output", inout)
                }
                glDispatchCompute(wgXV, inout.sizeX, 1)
                glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            }
        }
    }

    enum class Direction {
        HORIZONTAL,
        VERTICAL
    }
}