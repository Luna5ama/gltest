package dev.luna5ama.gltest.terrain.tools

import dev.fastmc.common.sq
import kotlinx.coroutines.*
import dev.luna5ama.gltest.readImage
import java.awt.image.BufferedImage
import java.awt.image.DataBuffer
import java.io.File

fun main() {
    runBlocking {
        val blockTexSize = 16
        val ogTex = File("tex\\blocks_0.png")
        val texDir = File("tex\\VanillaDefault+1.19.4\\assets\\minecraft\\textures\\block")
        val csvOutput = File("tex\\uv.csv")

        val flipped = readImage(ogTex)
        val ogTexImage = BufferedImage(flipped.width, flipped.height, flipped.type)
        val graphics = ogTexImage.createGraphics()
        graphics.drawImage(flipped, 0, 0, flipped.width, flipped.height, 0, flipped.height, flipped.width, 0, null)
        graphics.dispose()

        val slotCountX = flipped.width / blockTexSize
        val slotCountY = flipped.height / blockTexSize
        val texSlots = Array(slotCountX * slotCountY) {
            val x = it % slotCountX
            val y = it / slotCountX
            TexSlot(it, x, y)
        }

        val blockImages = coroutineScope {
            texSlots.forEach { slot ->
                launch(Dispatchers.Default) {
                    slot.data = ogTexImage.toRBGByteArray(
                        slot.slotX * blockTexSize,
                        slot.slotY * blockTexSize,
                        blockTexSize,
                        blockTexSize
                    )
                }
            }

            texDir.listFiles()!!
                .filter { it.isFile }
                .filter { it.extension == "png" }
                .filter { !it.nameWithoutExtension.endsWith("_n") }
                .filter { !it.nameWithoutExtension.endsWith("_s") }
                .map { async {
                    val image = readImage(it)
                    if (image.width != blockTexSize || image.height != blockTexSize) {
                        println("Invalid image size: ${it.name}")
                        return@async null
                    }
                    val data = image.toRBGByteArray(0, 0, 16, 16)
                    it to data
                     }
                }
                .awaitAll()
                .mapNotNull { it }

        }

        val matchedSlotIndex = IntArray(blockImages.size) { -1 }

        coroutineScope {
            blockImages.forEachIndexed { index, (_, data) ->
                launch(Dispatchers.Default) {
                    val slot = texSlots.minBy {
                        calcDiff(data, it.data)
                    }
                    matchedSlotIndex[index] = slot.index
                }
            }
        }

        csvOutput.bufferedWriter().use {
            blockImages.forEachIndexed { index, (file, data) ->
                val slot = texSlots[matchedSlotIndex[index]]
                val u = slot.slotX * blockTexSize / ogTexImage.width.toDouble()
                val v = slot.slotY * blockTexSize / ogTexImage.height.toDouble()
                it.appendLine("${file.nameWithoutExtension},$u,$v")
            }
        }
    }
}

private fun calcDiff(a: ByteArray, b: ByteArray): Double {
    var diff = 0.0
    for (i in a.indices) {
        diff += (a[i] / 255.0 - b[i] / 255.0).sq
    }
    return diff / 3.0
}

private fun BufferedImage.toRBGByteArray(srcX: Int, srcY: Int, width: Int, height: Int): ByteArray {
    val result = ByteArray(width * height * 3)
    val data = createDataArray()
    var i = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val dataElement = raster.getDataElements(srcX + x, srcY + y, data)
            result[i++] = colorModel.getRed(dataElement).toByte()
            result[i++] = colorModel.getGreen(dataElement).toByte()
            result[i++] = colorModel.getBlue(dataElement).toByte()
        }
    }
    return result
}

private fun BufferedImage.createDataArray(): Any {
    val numBands = raster.numBands
    return when (val dataType = raster.dataBuffer.dataType) {
        DataBuffer.TYPE_BYTE -> ByteArray(numBands)
        DataBuffer.TYPE_USHORT -> ShortArray(numBands)
        DataBuffer.TYPE_INT -> IntArray(numBands)
        DataBuffer.TYPE_FLOAT -> FloatArray(numBands)
        DataBuffer.TYPE_DOUBLE -> DoubleArray(numBands)
        else -> throw IllegalArgumentException("Unknown data buffer type: $dataType")
    }
}

private class TexSlot(val index: Int, val slotX: Int, val slotY: Int) {
    lateinit var data: ByteArray
}