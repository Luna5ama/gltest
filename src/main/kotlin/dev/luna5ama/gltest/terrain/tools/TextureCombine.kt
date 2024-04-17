@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.luna5ama.gltest.terrain.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import dev.luna5ama.gltest.toRGBAByteArray
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

fun main() {
    runBlocking {
        val texWidth = 8192
        val texHeight = 4096
        val spriteSize = 128

        val uvCsv = File("tex\\uv.csv")
//        val normalOutput = File("tex\\blocks_0_n.png")
        val specularOutput = File("tex\\blocks_0_s.png")
        val texDirs = listOf(
//            File("tex\\Vanilla-Normals-Renewed-master\\assets\\minecraft\\textures\\block")
            File("tex\\AVPBR Retexture R2\\assets\\minecraft\\textures\\block"),
            File("tex\\Vanilla-labPBR-1-3-2\\assets\\minecraft\\textures\\block")
        )

        val uvMap = uvCsv.readLines()
            .map { it.split(",") }
            .associate { it[0] to UvMap(it[0], it[1].toDouble(), it[2].toDouble()) }

        fun getFileList(suffix: String): Map<String, File> {
            val result = mutableMapOf<String, File>()
            texDirs.forEach { dir ->
                dir.listFiles()!!
                    .filter { it.isFile }
                    .filter { it.extension == "png" }
                    .filter { it.nameWithoutExtension.endsWith(suffix) }
                    .forEach {
                        result.putIfAbsent(it.nameWithoutExtension.removeSuffix(suffix), it)
                    }
            }
            return result
        }

        suspend fun readImages(files: Map<String, File>): Map<String, RawImage> {
            return coroutineScope {
                files.map {
                    async {
                        val image = ImageIO.read(it.value)
                        val result = RawImage(image.width, image.height, 4)
                        image.toRGBAByteArray().copyInto(result.data)
                        it.key to result.resize(spriteSize, spriteSize)
                    }
                }.awaitAll().toMap()
            }
        }

        fun merge(output: RawImage, inputs: Map<String, RawImage>) {
            inputs.forEach { (name, image) ->
                val uv = uvMap[name] ?: return@forEach
                image.copyTo(
                    output,
                    0, 0,
                    (uv.u * texWidth).toInt(), (uv.v * texHeight).toInt(),
                    spriteSize, spriteSize
                )
            }
        }

        val normalImages = readImages(getFileList("_n"))
        val specularImages = readImages(getFileList("_s"))

//        val test = BufferedImage(spriteSize, spriteSize, BufferedImage.TYPE_INT_ARGB)
//        normalImages["stone"]!!.toBufferedImage(test)
//        ImageIO.write(test, "png", File("tex\\test.png"))

//        val normalAtlas = RawImage(texWidth, texHeight, 4).apply {
//            fill(135u, 116u, 251u, 251u)
//        }
        val specularAtlas = RawImage(texWidth, texHeight, 4).apply {
            fill(8u, 8u, 0u, 255u)
        }

//        merge(normalAtlas, normalImages)
        merge(specularAtlas, specularImages)

//        val normalOutputImage = BufferedImage(texWidth, texHeight, BufferedImage.TYPE_INT_ARGB)
        val specularOutputImage = BufferedImage(texWidth, texHeight, BufferedImage.TYPE_INT_ARGB)

//        normalAtlas.toBufferedImage(normalOutputImage, true)
        specularAtlas.toBufferedImage(specularOutputImage, true)

//        ImageIO.write(normalOutputImage, "png", normalOutput)
        ImageIO.write(specularOutputImage, "png", specularOutput)
    }
}

private class UvMap(val name: String, val u: Double, val v: Double)

private class RawImage(val width: Int, val height: Int, val channels: Int) {
    val data = UByteArray(width * height * channels)

    fun fill(vararg values: UByte) {
        check(values.size == channels)
        for (i in data.indices step channels) {
            values.copyInto(data, i)
        }
    }

    operator fun set(x: Int, y: Int, channel: Int, value: UByte) {
        data[(y * width + x) * channels + channel] = value
    }

    operator fun get(x: Int, y: Int, channel: Int): UByte {
        return data[(y * width + x) * channels + channel]
    }

    fun copyTo(dst: RawImage, srcX: Int, srcY: Int, dstX: Int, dstY: Int, width: Int, height: Int) {
        check(channels == dst.channels)
        val src = this
        for (y in 0 until height) {
            val srcOffset = ((srcY + y) * src.width + srcX) * channels
            val dstOffset = ((dstY + y) * dst.width + dstX) * channels
            data.copyInto(dst.data, dstOffset, srcOffset, srcOffset + width * channels)
        }
    }

    fun resize(width: Int, height: Int): RawImage {
        if (width == this.width && height == this.height) {
            return this
        }

        val result = RawImage(width, height, channels)
        val ratioX = this.width / width.toDouble()
        val ratioY = this.height / height.toDouble()
        for (dstY in 0 until height) {
            for (dstX in 0 until width) {
                val srcX = (dstX * ratioX).toInt()
                val srcY = (dstY * ratioY).toInt()
                for (channel in 0 until channels) {
                    result[dstX, dstY, channel] = this[srcX, srcY, channel]
                }
            }
        }
        return result
    }

    fun toBufferedImage(output: BufferedImage, flip: Boolean) {
        check(output.width == width && output.height == height)

        when (channels) {
            1 -> {
                for (iy in 0 until height) {
                    val y = if (flip) height - iy - 1 else iy
                    for (x in 0 until width) {
                        val value = this[x, y, 0]
                        output.setRGB(x, iy, (value.toInt() shl 16) or (value.toInt() shl 8) or value.toInt())
                    }
                }
            }

            3 -> {
                for (iy in 0 until height) {
                    val y = if (flip) height - iy - 1 else iy
                    for (x in 0 until width) {
                        val r = this[x, y, 0]
                        val g = this[x, y, 1]
                        val b = this[x, y, 2]
                        output.setRGB(x, iy, (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt())
                    }
                }
            }

            4 -> {
                for (iy in 0 until height) {
                    val y = if (flip) height - iy - 1 else iy
                    for (x in 0 until width) {
                        val r = this[x, y, 0]
                        val g = this[x, y, 1]
                        val b = this[x, y, 2]
                        val a = this[x, y, 3]
                        output.setRGB(
                            x,
                            iy,
                            (r.toInt() shl 16) or (g.toInt() shl 8) or (b.toInt() shl 0) or (a.toInt() shl 24)
                        )
                    }
                }
            }
        }
    }
}